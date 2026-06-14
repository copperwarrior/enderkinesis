package org.shipwrights.enderkinesis.blockentity

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.CrystalExplosiveBlock
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.valkyrienskies.core.api.ships.properties.ShipId
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Detonates on a VS2 contact at THIS block's exact position. Per-block sensitivity (stern
 * doesn't fire when bow gets hit) is the router's job. Phys thread queues via
 * [queueDetonation]; game-thread [serverTick] drains and calls Level.explode.
 */
class CrystalExplosiveBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.CRYSTAL_EXPLOSIVE.get(), pos, state) {

    /** `mass × |v_relative|` at the contact, packed as a double bit pattern. */
    private val pendingImpulse: AtomicLong = AtomicLong(0L)

    @Volatile private var detonating: Boolean = false

    private var registeredShipId: ShipId? = null

    private var registeredDim: DimensionId? = null

    override fun setLevel(level: Level) {
        super.setLevel(level)
        if (level is ServerLevel) refreshRegistration(level)
    }

    fun serverTick(level: ServerLevel, pos: BlockPos, state: BlockState) {
        refreshRegistration(level)

        val packed = pendingImpulse.getAndSet(0L)
        if (packed != 0L && !detonating) {
            val impulse = java.lang.Double.longBitsToDouble(packed)
            if (impulse > 0.0) {
                val rawRadius = (Math.sqrt(impulse) * RADIUS_PER_SQRT_IMPULSE).toFloat()
                val radius = Math.min(rawRadius, MAX_EXPLOSION_RADIUS)
                if (radius >= MIN_EXPLOSION_RADIUS) {
                    detonating = true
                    // BE pos is in shipyard space when on a ship (~30M blocks from origin); must
                    // transform to world or the explosion fires in the empty shipyard void.
                    val worldPos = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
                    val ship = level.getLoadedShipManagingPos(pos)
                    if (ship != null) ship.transform.shipToWorld.transformPosition(worldPos)

                    LOG.info(
                        "detonate pos={} world=({},{},{}) impulse={} rawRadius={} clampedRadius={}",
                        pos,
                        "%.2f".format(worldPos.x), "%.2f".format(worldPos.y), "%.2f".format(worldPos.z),
                        "%.1f".format(impulse),
                        "%.2f".format(rawRadius), "%.2f".format(radius),
                    )

                    // Suppress the self-chain: our own block is about to be destroyed
                    // by this explosion, which fires its `Block.wasExploded` and would
                    // otherwise queue *another* chain explosion at the same spot.
                    EXPLODING_POSITIONS.add(pos.immutable())
                    try {
                        // Pre-remove so vanilla's ray-walk doesn't burn its power budget on the
                        // explosive itself (resistance 6.0 absorbs ~1.89/hit; ~4 in-cell samples
                        // zero a radius-5 explosion before it reaches neighbours).
                        level.removeBlock(pos, false)
                        level.explode(
                            null,
                            worldPos.x, worldPos.y, worldPos.z,
                            radius,
                            Level.ExplosionInteraction.TNT,
                        )
                    } finally {
                        EXPLODING_POSITIONS.remove(pos)
                    }
                } else {
                    LOG.info(
                        "drop pos={} impulse={} radius={} below MIN_EXPLOSION_RADIUS={}",
                        pos, "%.1f".format(impulse),
                        "%.2f".format(rawRadius), MIN_EXPLOSION_RADIUS,
                    )
                }
            }
        }
    }

    /** Called from the physics thread by [CrystalExplosiveCollisionRouter]. Stores
     *  the largest impulse seen since the last drain via a lock-free max-CAS. */
    fun queueDetonation(impulse: Double) {
        if (impulse < IMPACT_IMPULSE_THRESHOLD) {
            LOG.info(
                "reject pos={} impulse={} below IMPACT_IMPULSE_THRESHOLD={}",
                blockPos, "%.1f".format(impulse), "%.1f".format(IMPACT_IMPULSE_THRESHOLD),
            )
            return
        }
        val packed = java.lang.Double.doubleToRawLongBits(impulse)
        while (true) {
            val current = pendingImpulse.get()
            val currentVal = java.lang.Double.longBitsToDouble(current)
            if (impulse <= currentVal) return
            if (pendingImpulse.compareAndSet(current, packed)) return
        }
    }

    /** Keep the registration in sync as the block transitions between ship and
     *  world contexts (e.g. assembly/disassembly around this position). */
    private fun refreshRegistration(level: ServerLevel) {
        val ship = level.getLoadedShipManagingPos(blockPos)
        val newShipId = ship?.id
        val newDim = if (ship == null) level.dimensionId else null

        if (newShipId != registeredShipId || newDim != registeredDim) {
            // Drop the old registration (if any) before adding the new one — a ship
            // forming around a previously world-placed explosive flips dim→ship; a
            // ship dissolving flips ship→dim.
            registeredShipId?.let { CrystalExplosiveCollisionRouter.removeShip(this, it) }
            registeredDim?.let { CrystalExplosiveCollisionRouter.removeWorld(this, it) }

            if (newShipId != null) {
                CrystalExplosiveCollisionRouter.addShip(this, newShipId)
            } else if (newDim != null) {
                CrystalExplosiveCollisionRouter.addWorld(this, newDim)
            }
            registeredShipId = newShipId
            registeredDim = newDim
        }
    }

    override fun setRemoved() {
        super.setRemoved()
        registeredShipId?.let { CrystalExplosiveCollisionRouter.removeShip(this, it) }
        registeredDim?.let { CrystalExplosiveCollisionRouter.removeWorld(this, it) }
        registeredShipId = null
        registeredDim = null
    }

    companion object {
        private val LOG = LogUtils.getLogger()

        /** Positions of crystal-explosive blocks whose self-detonation `level.explode`
         *  call is currently in flight. `Block.wasExploded` consults this set so the
         *  initiating block doesn't fire a chain at itself and loop forever. */
        val EXPLODING_POSITIONS: MutableSet<BlockPos> = ConcurrentHashMap.newKeySet()

        /** Fixed radius used for chain-reaction detonations (when an external explosion
         *  destroys a crystal explosive). Independent of impulse: a sympathetic
         *  detonation doesn't know what kinematic context triggered it, so use a
         *  consistent visual + damage profile. Roughly TNT × 1.25. */
        const val CHAIN_EXPLOSION_RADIUS: Float = 5.0f

        /** Convert a block-pos in the originating block's frame (shipyard or world)
         *  to world XYZ, then queue a chain-style explosion on the next server tick.
         *  Deferred so a long fuse of explosives propagates over consecutive ticks
         *  instead of recursively blowing the call stack. */
        fun queueChainExplosion(level: ServerLevel, pos: BlockPos) {
            val worldPos = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            val ship = level.getLoadedShipManagingPos(pos)
            if (ship != null) ship.transform.shipToWorld.transformPosition(worldPos)
            val immut = pos.immutable()
            level.server.execute {
                LOG.info(
                    "chain pos={} world=({},{},{}) radius={}",
                    immut,
                    "%.2f".format(worldPos.x), "%.2f".format(worldPos.y), "%.2f".format(worldPos.z),
                    CHAIN_EXPLOSION_RADIUS,
                )
                EXPLODING_POSITIONS.add(immut)
                try {
                    level.explode(
                        null,
                        worldPos.x, worldPos.y, worldPos.z,
                        CHAIN_EXPLOSION_RADIUS,
                        Level.ExplosionInteraction.TNT,
                    )
                } finally {
                    EXPLODING_POSITIONS.remove(immut)
                }
            }
        }

        /** Minimum impulse (`mass·|v|`, kg·m/s) to count as a detonation-worthy hit.
         *  Calibration:
         *    -  1 t ship at  5 m/s →   5 000 — light bumps don't fire
         *    - 10 t ship at  5 m/s →  50 000 — a real collision fires
         *    -  1 t ship at 10 m/s →  10 000 — ramming attacks count */
        const val IMPACT_IMPULSE_THRESHOLD: Double = 10_000.0

        /** Impulse → explosion radius via `r ≈ k · √impulse`. Square-root because
         *  explosion linear-extent scales with √energy while delivered impulse
         *  scales linearly with `mass × |Δv|`. Calibration with [MIN_EXPLOSION_RADIUS]
         *  = 3 and [MAX_EXPLOSION_RADIUS] = 12:
         *    - 10 t at  5 m/s, impulse  50 000 → r ≈  5.6
         *    - 10 t at 20 m/s, impulse 200 000 → r ≈ 11.2
         *    -100 t at  5 m/s, impulse 500 000 → r =  12.0 (capped) */
        const val RADIUS_PER_SQRT_IMPULSE: Double = 0.025

        /** Don't fire pillow-bumps that pass the impulse threshold but produce a
         *  radius too small to actually break anything. */
        const val MIN_EXPLOSION_RADIUS: Float = 3.0f

        /** Hard cap. Beyond this the VS2 mixin's cube-summed blast force scales like
         *  a small nuke on the host ship and the vanilla `O(r³)` ray sweep hitches
         *  the server. */
        const val MAX_EXPLOSION_RADIUS: Float = 12.0f
    }
}
