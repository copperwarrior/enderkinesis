package org.shipwrights.enderkinesis.physics

import org.joml.Vector3d
import org.shipwrights.enderkinesis.blockentity.CrepusculiteLatticeBlockEntity
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel

/**
 * Per-block Archimedes — fractional submersion makes the water surface a soft ramp instead of
 * a step, which (with submersion-scaled drag) is what keeps the ship from bouncing. Force-at-
 * position follows vstuffcontinued's thruster pattern (`blockCenter − transform.positionInShip`).
 */
class CrepusculiteLatticeForceInducer(
    /** World-space Y of the virtual sea surface (lattice world Y when this was created). */
    var waterLineY: Double = 0.0,
    /** Virtual fluid density (kg/m³). Water is 1000; default heavier so ships ride high. */
    var fluidDensity: Double = DEFAULT_FLUID_DENSITY,
    /**
     * Linear-velocity drag (N per m/s) applied per submerged block, opposing the ship's *linear*
     * world velocity at the block's position — co-located with buoyancy so it damps heave and
     * horizontal translation. Strong by default: the buoyancy spring is very stiff, so it must be
     * near-critically damped or the explicit physics step pumps energy and the ship oscillates
     * apart.
     */
    var dragCoefficient: Double = 500.0,
    /**
     * Drag (N per m/s) applied per submerged block against that block's *rotational* point velocity
     * `ω × r` (i.e. the part of its motion that comes from the ship rotating, not translating).
     * The torque from this term is what damps roll/pitch/yaw, and decoupling it from
     * [dragCoefficient] lets us crank angular damping without also overdamping translation. Higher
     * default than the linear coefficient — the lattice fluid is meant to feel rotationally sticky.
     */
    var angularDragCoefficient: Double = 5000.0,
    /**
     * Depth (m) over which a block goes from "touching the surface" to "fully displacing". Larger
     * = softer, lower-stiffness buoyancy that is far easier to damp (and rides a touch deeper).
     */
    var submergeRamp: Double = 5.0,
) : ShipPhysicsListener {

    /** Ship-local block coords as x,y,z triples; set on the server thread. */
    @Transient private var blocks: IntArray = IntArray(0)

    /**
     * World game time (ticks), pushed from the server thread by the lattice block entity. The
     * client visual uses `level.getGameTime()` directly; both feed [GerstnerOcean] so the wave the
     * player sees and the wave that lifts the ship are the same field at the same instant.
     */
    @Transient var waveTime: Double = 0.0

    /** Depth-driven wave intensity (0..1), pushed from the server thread (it can read the heightmap). */
    @Transient var waveIntensity: Double = 1.0

    /** Which lattice block entity "owns" this inducer — i.e. is responsible for driving its
     *  state + emitting particles + floating entities. A ship can have many lattices, but only
     *  the authoritative one runs that work; the rest skip. Null = no authority yet (next
     *  lattice to tick claims it). Transient — reclaimed on restart.
     *
     *  Identity, not [BlockPos]: VS2's assembly path can leave **multiple BE instances at the
     *  same shipyard position** registered with the chunk's ticker list, all ticking every
     *  game tick. A [BlockPos]-keyed gate (`currentAuthority == ourPos`) lets every duplicate
     *  pass — they each see their own pos and claim — and each emits the full particle set,
     *  scans, splashes, and floats. Keying on the BE reference (`===`) instead means exactly
     *  one instance wins, every other duplicate falls through the gate's early-return, and the
     *  per-tick work runs once. (Cost: a strong reference to one BE for as long as the inducer
     *  lives — but the BE is already pinned in the chunk's ticker list, and a removed-BE
     *  reference is detected via [net.minecraft.world.level.block.entity.BlockEntity.isRemoved]
     *  in the staleness check, so authority recovers on the next tick rather than hanging on a
     *  dead instance.) */
    @Transient var authorityHolder: CrepusculiteLatticeBlockEntity? = null

    /** World gameTime of the authority lattice's last tick. If a follower sees this hasn't
     *  advanced in {@link AUTHORITY_STALE_TICKS} ticks, the authority is presumed dead (block
     *  broken, chunk unloaded, etc.) and the follower may claim authority. */
    @Transient var authorityTickedAt: Long = Long.MIN_VALUE

    fun setBlocks(packed: IntArray) {
        blocks = packed
    }

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        if (physShip.isStatic || physShip.mass <= 0.0) return
        if (fluidDensity <= 0.0) return            // disabled (redstone 15)
        val blockCount = blocks.size / 3
        if (blockCount == 0) return

        val transform = physShip.transform
        val s2w = transform.shipToWorld
        val com = transform.positionInShip
        // Block-sourced scale³ multiplier — keeps ship-lengths-per-second
        // constant across Staff-of-Scales settings against VS2's scale²
        // cross-section drag.
        val s1 = transform.shipToWorldScaling.x()
        val hostScale = s1 * s1 * s1

        // Block half-height in world units = `0.5 * s1` — a hard-coded `0.5` reports a
        // half-submerged block on a 2× ship as fully submerged (world-Y bottom is 0.5 below
        // centre, not 1.0). `submergeRamp` is also a block-unit depth, so it scales by `s1`
        // for partial buoyancy to span the same fraction of the scaled block.
        val blockHalfHeight = 0.5 * s1
        val scaledSubmergeRamp = submergeRamp * s1

        // Centre of mass in world space, plus the ship's world linear/angular velocity, so each
        // block's local point velocity v_p = v + ω × (blockWorld − comWorld) can be damped.
        val comWorld = Vector3d(com.x(), com.y(), com.z())
        s2w.transformPosition(comWorld)
        val vel = physShip.velocity
        val omega = physShip.angularVelocity

        var submerged = 0
        var i = 0
        while (i < blocks.size) {
            val cx = blocks[i] + 0.5
            val cy = blocks[i + 1] + 0.5
            val cz = blocks[i + 2] + 0.5
            i += 3

            val world = Vector3d(cx, cy, cz)
            s2w.transformPosition(world)

            // The water line follows the Gerstner surface at this block's world XZ — the exact
            // same field the visual particles ride (shared params + game time = perfectly synced).
            val surfaceY = waterLineY + GerstnerOcean.heightAt(world.x, world.z, waveTime, waveIntensity)
            // Softened submersion ramp (lower stiffness -> dampable). Both block half-height
            // and ramp depth scale with `s1` so a scaled block submerges across the same
            // fraction of its own size as a unit block does.
            val frac = ((surfaceY - (world.y - blockHalfHeight)) / scaledSubmergeRamp).coerceIn(0.0, 1.0)
            if (frac <= 0.0) continue
            submerged++

            // Split this block's point velocity into the ship's linear part and the part driven by
            // rotation (`ω × r`); drag each with its own coefficient so angular damping can be
            // stronger than linear without changing how translation feels.
            val rwx = world.x - comWorld.x
            val rwy = world.y - comWorld.y
            val rwz = world.z - comWorld.z
            val rotX = omega.y() * rwz - omega.z() * rwy
            val rotY = omega.z() * rwx - omega.x() * rwz
            val rotZ = omega.x() * rwy - omega.y() * rwx

            // Buoyancy up + linear drag + angular drag, all applied at this block position.
            // Scaled by host ship's scale (block-sourced output grows with the block).
            val dLin = -dragCoefficient * frac * hostScale
            val dAng = -angularDragCoefficient * frac * hostScale
            val force = Vector3d(
                vel.x() * dLin + rotX * dAng,
                fluidDensity * GRAVITY * frac * hostScale + vel.y() * dLin + rotY * dAng,
                vel.z() * dLin + rotZ * dAng,
            )
            val rel = Vector3d(cx - com.x(), cy - com.y(), cz - com.z())
            physShip.applyInvariantForceToPos(force, rel)
        }
    }

    companion object {
        /** kg/m³ of virtual fluid (water = 1000). The maximum density the lattice produces — the
         *  redstone signal scales [fluidDensity] from 0 up to this. */
        const val DEFAULT_FLUID_DENSITY = 1100.0

        /** VS2 world gravity magnitude (m/s²); VS2 uses g = 10. */
        private const val GRAVITY = 10.0

        /** Ticks of silence from the authority lattice (no `serverTick` refresh) before a
         *  follower may claim authority. Sized to comfortably exceed normal per-tick scheduling
         *  jitter while still recovering quickly from a broken / chunk-unloaded authority. */
        const val AUTHORITY_STALE_TICKS = 40L
    }
}
