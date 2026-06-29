package org.shipwrights.enderkinesis.item

import com.mojang.logging.LogUtils
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.impl.game.ShipTeleportDataImpl
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val LOG = LogUtils.getLogger()

/** Server-side application of Staff-of-Scales transform-scale changes.
 *
 *  Scaling goes through `level.shipObjectWorld.teleportShip(ship,
 *  ShipTeleportDataImpl(pos, rot, vel, omega, dim, newScale))` — the same
 *  path VMod's `ScaleMode` uses, and the only one that actually takes effect
 *  in VS2 2.4.13. `ship.unsafeSetTransform(...)` accepts a BodyTransform with
 *  the new scaling field but silently ignores the scaling component (verified
 *  by repeated `prior == 1.0` reads after writes); only the teleport pipeline
 *  propagates scale to physics + rendering.
 *
 *  Uniform scaling only (same factor on all three axes); the staff's single
 *  slider drives a single value.
 *
 *  ## Dimension blacklist
 *  Some mods own scaling as a critical part of their own mechanics (e.g. the
 *  Genesis space dimension uses scaling for its ecosystem). On those
 *  dimensions, both this staff AND that mod would be writing the same
 *  property in a fight nobody wins. We refuse the staff on those dimensions
 *  rather than trigger a tug-of-war the player can't see. */
object ScalesManager {

    const val MIN_SCALE: Double = 0.25
    const val MAX_SCALE: Double = 1.5
    /** Within reach: ship's nearest world point ≤ this many blocks from the player. */
    const val INTERACTION_RANGE: Double = 64.0
    private const val INTERACTION_RANGE_SQ: Double = INTERACTION_RANGE * INTERACTION_RANGE

    /** Duration of the left-click reset tween, in server ticks (1 second @ 20 TPS). */
    const val RESET_TWEEN_TICKS: Int = 20

    /** Active reset tweens keyed by player UUID. The drag stream and tween
     *  can both write scale in the same tick; the stream wins last-write
     *  because [tickResetTweens] runs *before* COMMIT packets are processed.
     *  The user can release the staff to let the tween finish unopposed. */
    private data class ResetTween(
        val ship: LoadedServerShip,
        val startScale: Double,
        val startGameTime: Long,
    )
    private val resetTweens: MutableMap<UUID, ResetTween> = ConcurrentHashMap()

    /** Dimensions where this staff is disabled because another mod owns the
     *  scaling property on ships in that dimension. Match is by the dimension
     *  ResourceLocation of the player's level (i.e. the dimension THE PLAYER is
     *  in when they invoke the staff — that's where the ship is too, since
     *  cross-dimension ship operations aren't allowed). Edit this set to add
     *  more dimensions if other mods declare the same scaling ownership. */
    val BLACKLISTED_DIMENSIONS: Set<ResourceLocation> = setOf(
        // Genesis space (`SPACE_DIM = great_unknown`) and wormhole
        // (`WORMHOLE_DIM = subspace`) dimensions — both use VS2 ship scaling
        // as part of their ecosystem. We and Genesis would write the same
        // kinematics scaling property in a tug-of-war if the staff fired in
        // either, so we refuse rather than fight. Planet dimensions (`moon`,
        // `malachite`) are accessed via Genesis but the wiki/source indicate
        // scaling is not actively driven there, so they're left out for now —
        // add if that changes upstream.
        ResourceLocation("genesis", "great_unknown"),
        ResourceLocation("genesis", "subspace"),
    )

    /** Current uniform scale (1.0 if the ship is at default). Read from the
     *  ship's shipToWorldScaling (X axis), which is the value the teleport
     *  pipeline writes — matching what we just stored, not the stale
     *  kinematic transform that ignores scaling. */
    fun getCurrentScale(ship: LoadedServerShip): Double {
        return ship.transform.shipToWorldScaling.x()
    }

    /** True if the player's current dimension is on [BLACKLISTED_DIMENSIONS]. */
    fun isDimensionBlacklisted(player: ServerPlayer): Boolean {
        val dim = player.level().dimension().location()
        return dim in BLACKLISTED_DIMENSIONS
    }

    /** Apply a new uniform scale. Clamps to [MIN_SCALE, MAX_SCALE].
     *
     *  Returns true if applied, false on validation failure (out of range,
     *  ship unreachable, dimension blacklisted). */
    /** Direct-on-reference variant used by the live stream — the network
     *  layer holds the ship reference picked at BEGIN, so we skip the
     *  by-id lookup entirely (it returns null in some configurations even
     *  when `getLoadedShipManagingPos` resolves the same ship). */
    fun commitScaleOnShip(player: ServerPlayer, ship: LoadedServerShip, requestedScale: Double): Boolean {
        if (isDimensionBlacklisted(player)) {
            LOG.info("[Scales] commitScale: dimension blacklisted, refusing")
            player.displayClientMessage(
                Component.translatable("item.enderkinesis.staff_of_scales.blacklisted_dimension"), true,
            )
            return false
        }
        if (!isReachable(player, ship)) {
            LOG.info("[Scales] commitScale: ship {} out of reach", ship.id)
            return false
        }
        val newScale = requestedScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val priorScale = ship.transform.shipToWorldScaling.x()
        applyScaleViaTeleport(ship, newScale, player.serverLevel())
        LOG.info("[Scales] commitScale APPLIED (teleport) shipId={} prior={} new={}",
            ship.id, priorScale, newScale)
        return true
    }

    /** Start a 1-second tween that lerps `ship`'s uniform scale back to 1.0.
     *  Idempotent — calling again from the same player just restarts the
     *  tween from the ship's current scale. No-op if the ship is already at
     *  1.0 (within ε), the dimension is blacklisted, or the ship is out of
     *  reach. */
    fun startResetTween(player: ServerPlayer, ship: LoadedServerShip) {
        if (isDimensionBlacklisted(player)) {
            LOG.info("[Scales] startResetTween: dimension blacklisted, refusing")
            player.displayClientMessage(
                Component.translatable("item.enderkinesis.staff_of_scales.blacklisted_dimension"), true,
            )
            return
        }
        if (!isReachable(player, ship)) {
            LOG.info("[Scales] startResetTween: ship {} out of reach", ship.id)
            return
        }
        val currentScale = ship.transform.shipToWorldScaling.x()
        if (kotlin.math.abs(currentScale - 1.0) < 1e-4) {
            LOG.info("[Scales] startResetTween: ship {} already at 1.0 — nothing to do", ship.id)
            return
        }
        resetTweens[player.uuid] = ResetTween(ship, currentScale, player.serverLevel().gameTime)
        LOG.info("[Scales] startResetTween player={} shipId={} startScale={}",
            player.name.string, ship.id, currentScale)
    }

    /** Advance all reset tweens whose ship lives in this level. Runs once
     *  per server tick from a `TickEvent.SERVER_LEVEL_POST` listener
     *  registered in [ScalesNetwork.init]. */
    fun tickResetTweens(level: ServerLevel) {
        if (resetTweens.isEmpty()) return
        val now = level.gameTime
        val levelDim = level.dimensionId
        val iter = resetTweens.entries.iterator()
        while (iter.hasNext()) {
            val (uuid, tween) = iter.next()
            // Only this level's tweens get advanced this tick — VS2's
            // `teleportShip` belongs to the ship's dimension's world.
            if (tween.ship.chunkClaimDimension != levelDim) continue
            val elapsed = now - tween.startGameTime
            val progress = (elapsed.toDouble() / RESET_TWEEN_TICKS).coerceIn(0.0, 1.0)
            val newScale = (tween.startScale + (1.0 - tween.startScale) * progress)
                .coerceIn(MIN_SCALE, MAX_SCALE)
            applyScaleViaTeleport(tween.ship, newScale, level)
            if (progress >= 1.0) {
                LOG.info("[Scales] reset tween complete uuid={} shipId={} finalScale={}",
                    uuid, tween.ship.id, newScale)
                iter.remove()
            }
        }
    }

    /** Teleport-write the new scale onto `ship`. Position / rotation /
     *  velocity / dimension all preserved; only scale changes. */
    private fun applyScaleViaTeleport(ship: LoadedServerShip, newScale: Double, level: ServerLevel) {
        val tf = ship.transform
        val data = ShipTeleportDataImpl(
            tf.positionInWorld,
            tf.shipToWorldRotation,
            ship.velocity,
            ship.omega,
            ship.chunkClaimDimension,
            newScale,
        )
        level.shipObjectWorld.teleportShip(ship, data)
    }

    /** Legacy by-id entrypoint kept for symmetry; not used by the live
     *  stream anymore. */
    fun commitScale(player: ServerPlayer, shipId: Long, requestedScale: Double): Boolean {
        val ship = player.level().shipObjectWorld.allShips.getById(shipId) as? LoadedServerShip
        if (ship == null) {
            LOG.info("[Scales] commitScale: ship {} not found / not LoadedServerShip", shipId)
            return false
        }
        return commitScaleOnShip(player, ship, requestedScale)
    }

    private fun isReachable(player: ServerPlayer, ship: LoadedServerShip): Boolean {
        val px = player.x; val py = player.y; val pz = player.z
        val aabb = ship.worldAABB
        val nx = px.coerceIn(aabb.minX(), aabb.maxX())
        val ny = py.coerceIn(aabb.minY(), aabb.maxY())
        val nz = pz.coerceIn(aabb.minZ(), aabb.maxZ())
        val dx = nx - px; val dy = ny - py; val dz = nz - pz
        return (dx * dx + dy * dy + dz * dz) <= INTERACTION_RANGE_SQ
    }
}
