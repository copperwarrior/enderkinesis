package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.VoidHarnessBlock
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.valkyrienskies.core.api.VsBeta
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.util.PhysTickOnly
import org.valkyrienskies.core.api.world.PhysLevel
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.api.BlockEntityPhysicsListener
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Phys-tick force applicator for the Void Harness.
 *
 * Two operating modes, switched by whether the harness block sits on a ship or in the world:
 *  - **Ship source** (BE is on a ship): the `physShip` param is non-null. Pulls *this* ship's
 *    COM toward the harness's current world position; the body-frame direction (COM → harness)
 *    is constant, so the ship gets a constant body-frame thrust.
 *  - **World source** (BE in the world): `physShip` is null. The game tick pre-computes the set
 *    of nearby ship ids (everything within `PULL_RADIUS` of the harness); the phys tick reads
 *    that snapshot, looks each one up via `PhysLevel.getShipById`, and pulls its COM toward the
 *    harness's static world position.
 *
 * Force is in the XZ plane only (Y component zeroed) and applied at each ship's COM via
 * [PhysShip.applyInvariantForce] — invariant means the force vector is in world frame and does
 * not rotate with the body, which is what we want when steering a ship toward a world point.
 *
 * Thread-safety: all fields read on the phys thread are `@Volatile`, and `cachedNearbyShipIds`
 * is replaced by reference (atomic) rather than mutated in place. VS2's `MixinLevelChunk`
 * auto-registers any [BlockEntityPhysicsListener] on chunk load, so no explicit setup is
 * needed.
 */
@OptIn(PhysTickOnly::class, VsBeta::class)
class VoidHarnessBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.VOID_HARNESS.get(), pos, state),
    BlockEntityPhysicsListener {

    @Volatile override lateinit var dimension: DimensionId

    @Volatile private var cachedPowerLevel: Int = state.getValue(VoidHarnessBlock.POWER)
    @Volatile private var cachedHx: Double = pos.x + 0.5
    @Volatile private var cachedHy: Double = pos.y + 0.5
    @Volatile private var cachedHz: Double = pos.z + 0.5
    @Volatile private var cachedNearbyShipIds: LongArray = EMPTY_IDS

    // Client-side rotation-phase accumulator. Read/written only by the render thread; the BER
    // integrates `current_rate · dt` each frame so changes in redstone power adjust the rate of
    // phase advance without resetting the current phase (no visual snap).
    var clientRotationPhase: Double = 0.0
    /** `-1.0` = no previous render frame; the BER initialises on first render and integrates
     *  thereafter. Time is in ticks (`gameTime + partialTick`). */
    var clientLastRenderTime: Double = -1.0

    fun serverTick(level: ServerLevel, pos: BlockPos, state: BlockState) {
        cachedPowerLevel = state.getValue(VoidHarnessBlock.POWER)

        // Harness world position — body-frame block centre transformed through the ship's
        // shipToWorld if we're on a ship, otherwise the raw world centre.
        val ship = level.getLoadedShipManagingPos(pos)
        val center = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        ship?.transform?.shipToWorld?.transformPosition(center)
        cachedHx = center.x
        cachedHy = center.y
        cachedHz = center.z

        // World-placed + powered: snapshot the list of ships within range. The phys tick reads
        // this array (replaced by reference, never mutated in place) to dispatch pulls.
        if (cachedPowerLevel > 0 && ship == null) {
            val r2 = PULL_RADIUS * PULL_RADIUS
            val ids = ArrayList<Long>()
            for (s in level.shipObjectWorld.allShips) {
                val com = s.transform.positionInWorld
                val dx = com.x() - cachedHx
                val dy = com.y() - cachedHy
                val dz = com.z() - cachedHz
                if (dx * dx + dy * dy + dz * dz <= r2) ids.add(s.id)
            }
            cachedNearbyShipIds = ids.toLongArray()
        } else {
            cachedNearbyShipIds = EMPTY_IDS
        }

    }

    override fun physTick(physShip: PhysShip?, physLevel: PhysLevel) {
        val power = cachedPowerLevel
        if (power <= 0) return
        // Block-sourced scale³ multiplier. World-placed harness stays at 1.0.
        val hostS1 = physShip?.transform?.shipToWorldScaling?.x() ?: 1.0
        val hostS3 = hostS1 * hostS1 * hostS1
        val peakForce = FORCE_MAGNITUDE * (power / 15.0) * hostS3
        val hx = cachedHx
        val hy = cachedHy
        val hz = cachedHz
        if (physShip != null) {
            // Ship-resident: XZ-only pull (no vertical thrust on the host ship). Applied at
            // the harness's own shipyard position — off-COM application produces a torque that
            // rotates the ship to face the pull direction.
            applyPullAtHarness(physShip, hx, hz, peakForce)
        } else {
            // World-placed: full 3D pull at each target ship's COM (pure linear pull, no
            // torque), with exponential gravitational falloff.
            for (id in cachedNearbyShipIds) {
                val s = physLevel.getShipById(id) ?: continue
                applyPull3D(s, hx, hy, hz, peakForce)
            }
        }
    }

    /** XZ-only pull (ship-resident case) applied at the harness block's shipyard position
     *  rather than the ship's COM. Force direction is in world frame; VS2 translates the
     *  shipyard ("model") position into a body-frame offset internally and emits the resulting
     *  torque, so a harness mounted off-COM yaws the ship to face its pull direction. Y
     *  component of the force is zeroed so the host ship isn't lifted/sunk by its own harness. */
    private fun applyPullAtHarness(ship: PhysShip, hx: Double, hz: Double, peakForce: Double) {
        val com = ship.kinematics.position
        val dx = hx - com.x()
        val dz = hz - com.z()
        val xz = Math.sqrt(dx * dx + dz * dz)
        if (xz < 1e-3) return
        ship.applyWorldForceToModelPos(
            Vector3d(dx / xz * peakForce, 0.0, dz / xz * peakForce),
            Vector3d(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5),
        )
    }

    /** Full 3D pull (world-placed case) with exponential gravitational falloff:
     *  `F(r) = peakForce · exp(-r / EXP_FALLOFF_SCALE)`. [peakForce] has already been scaled
     *  by redstone power level. Strong at close range, fading smoothly toward 0 by the
     *  [PULL_RADIUS] cutoff — ships near the harness get yanked hard, distant ones drift gently. */
    private fun applyPull3D(ship: PhysShip, hx: Double, hy: Double, hz: Double, peakForce: Double) {
        val com = ship.kinematics.position
        val dx = hx - com.x()
        val dy = hy - com.y()
        val dz = hz - com.z()
        val len = Math.sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-3) return
        // `exp(-len / EXP_FALLOFF_SCALE)` is the long-range falloff (1.0 at the harness, smooth
        // decay outward). The extra [softCoreMultiplier] knocks the force down again at very
        // close range so a near-coincident ship stops oscillating around the harness.
        val magnitude = peakForce * Math.exp(-len / EXP_FALLOFF_SCALE) * softCoreMultiplier(len)
        ship.applyInvariantForce(
            Vector3d(dx / len * magnitude, dy / len * magnitude, dz / len * magnitude)
        )
    }

    /**
     * Smooth pull-suppression near the harness: `1 - exp(-(r / SOFT_CORE_RADIUS)²)`. Returns 0
     * at `r = 0` and approaches 1 asymptotically beyond a couple of [SOFT_CORE_RADIUS]. Used to
     * keep the pull force from spiking when a ship's COM sits inside the harness's own block —
     * applying peak force at r ≈ 0 causes the ship to oscillate violently around the harness on
     * subsequent ticks.
     *
     *  - r = 0.25 → ~6 %     (deep inside the harness block)
     *  - r = 0.5  → ~22 %    (within the harness block)
     *  - r = 1.0  → ~63 %    (at the block face)
     *  - r = 2.0  → ~98 %    (two blocks out — effectively the unattenuated long-range force)
     */
    private fun softCoreMultiplier(r: Double): Double {
        val u = r / SOFT_CORE_RADIUS
        return 1.0 - Math.exp(-u * u)
    }

    companion object {
        /** Radius (blocks) within which a world-placed harness affects ships. */
        const val PULL_RADIUS: Double = 32.0
        /** Peak pull-force magnitude (Newtons) at full redstone power (15). Linearly scaled
         *  down at lower power: `peak = FORCE_MAGNITUDE · power / 15`. For ship-resident the
         *  force is flat at the peak value; for world-placed it then decays exponentially with
         *  distance from this peak. */
        const val FORCE_MAGNITUDE: Double = 240_000.0
        /** Characteristic length for the world-pull exponential falloff (blocks).
         *  `F(r) = FORCE_MAGNITUDE · exp(-r / scale)`. At `r = scale` the force is ~37 %; at
         *  `r = 2·scale` ~14 %; at `r = 3·scale` ~5 %. Tuned so the force is still meaningfully
         *  felt at half [PULL_RADIUS] (16 blocks → ~14 %) and effectively zero at the cutoff. */
        const val EXP_FALLOFF_SCALE: Double = 8.0
        /** Characteristic length for the close-range *soft core* — the pull is suppressed by
         *  `1 - exp(-(r / SOFT_CORE_RADIUS)²)`. At `r = SOFT_CORE_RADIUS` (1 block) the force is
         *  at ~63 %; well within the block it's only a few percent, which stops a near-
         *  coincident ship from being yanked back and forth around the harness each tick. */
        const val SOFT_CORE_RADIUS: Double = 1.0
        private val EMPTY_IDS = LongArray(0)
    }
}
