package org.shipwrights.enderkinesis.client

import java.util.concurrent.ConcurrentHashMap
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider

/** Shared lookup surface for every cloaking-concealment mixin.
 *
 *  Maintains a per-ship "concealed?" map keyed by VS2 ship id. A ship enters this map
 *  once its client-side cloak progress reaches **1.0** (full strength), and leaves once
 *  progress drops back below 1.0. While present, the mixins return:
 *  - `BlockRenderDispatcher.renderBatched` → cancel for shipyard positions inside the ship's AABB.
 *  - `LivingEntityRenderer.render` → cancel for entities VS2 reports as dragged by the ship.
 *  - `BlockEntityRenderDispatcher.render` → cancel for BEs at shipyard positions inside the ship's AABB.
 *  - `Particle.render` → cancel for particles whose world position projects to inside the ship's volume.
 *
 *  The map is mutated from [ConcealmentTransitionWatcher] (per-tick) — never from inside
 *  a mixin callback. */
object CloakingMixinSupport {

    /** ship id → shipyard-coord AABB (inclusive on both sides). */
    private val concealed: ConcurrentHashMap<Long, ShipBox> = ConcurrentHashMap()

    /** Ships whose bubble currently contains the camera. Updated once per frame
     *  in [updateInsideBubbles] from [ConcealmentTransitionWatcher.tick]. While
     *  a ship is in this set, its blocks / BEs / dragged entities / particles
     *  are NOT cancelled and its batched draw is NOT redirected to the side FB
     *  — the ship just renders normally through the standard pipeline, so
     *  entities, hand items, and the ship itself depth-sort correctly without
     *  the post-shader needing to composite side FB content over them. */
    private val insideBubbles: ConcurrentHashMap.KeySetView<Long, Boolean> =
        ConcurrentHashMap.newKeySet()

    private class ShipBox(
        val minX: Int, val minY: Int, val minZ: Int,
        val maxX: Int, val maxY: Int, val maxZ: Int,
    ) {
        fun contains(x: Int, y: Int, z: Int): Boolean =
            x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }

    fun markConcealed(shipId: Long, minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int) {
        concealed[shipId] = ShipBox(minX, minY, minZ, maxX, maxY, maxZ)
    }

    fun markVisible(shipId: Long) {
        concealed.remove(shipId)
        insideBubbles.remove(shipId)
    }

    @JvmStatic
    fun isAnyShipConcealed(): Boolean = concealed.isNotEmpty()

    fun concealedShipIds(): Set<Long> = concealed.keys

    /** Thread-local "do not cancel" flag. The [ShipOnlyRenderer] needs to call
     *  `BlockRenderDispatcher.renderBatched` to build the ship-only mesh — but the
     *  concealment block mixin cancels exactly those calls. While this flag is set on
     *  the calling thread, [isPositionConcealed] reports false so the renderBatched
     *  call passes through and produces the geometry we need for the side FB. */
    private val cancelSuspended: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    @JvmStatic
    fun setCancelSuspended(suspended: Boolean) {
        cancelSuspended.set(suspended)
    }

    @JvmStatic
    fun isCancelSuspended(): Boolean = cancelSuspended.get()

    /** Recompute which concealed-ship bubbles currently contain the camera.
     *  Called once per frame from [ConcealmentTransitionWatcher.tick] before
     *  any cancel mixin queries fire this frame. */
    @JvmStatic
    fun updateInsideBubbles(x: Double, y: Double, z: Double) {
        insideBubbles.clear()
        if (concealed.isEmpty()) return
        val level = Minecraft.getInstance().level ?: return
        for ((shipId, box) in concealed.entries) {
            val ship = (level.shipObjectWorld.allShips.getById(shipId)
                as? org.valkyrienskies.core.api.ships.ClientShip) ?: continue
            val v = org.joml.Vector3d(x, y, z)
            ship.renderTransform.worldToShip.transformPosition(v)
            val cx = (box.minX + box.maxX + 1.0) * 0.5
            val cy = (box.minY + box.maxY + 1.0) * 0.5
            val cz = (box.minZ + box.maxZ + 1.0) * 0.5
            val hx = (box.maxX + 1 - box.minX) * 0.5 * BUBBLE_CIRCUMSCRIBE
            val hy = (box.maxY + 1 - box.minY) * 0.5 * BUBBLE_CIRCUMSCRIBE
            val hz = (box.maxZ + 1 - box.minZ) * 0.5 * BUBBLE_CIRCUMSCRIBE
            val nx = (v.x - cx) / hx
            val ny = (v.y - cy) / hy
            val nz = (v.z - cz) / hz
            if (nx * nx + ny * ny + nz * nz <= 1.0) {
                insideBubbles.add(shipId)
            }
        }
    }

    /** True when the camera is currently inside the bubble of this specific
     *  ship — i.e., that ship should render normally (cancel + redirect both
     *  skipped). */
    @JvmStatic
    fun isCameraInsideBubbleOf(shipId: Long): Boolean = insideBubbles.contains(shipId)

    /** True when [pos] (shipyard coords) is inside any currently-concealed ship's AABB.
     *  Used by the chunk + BE render cancel mixins. Returns false while this thread is
     *  in the ship-only render pass — see [setCancelSuspended]. */
    @JvmStatic
    fun isPositionConcealed(pos: BlockPos): Boolean {
        if (cancelSuspended.get()) return false
        if (concealed.isEmpty()) return false
        val x = pos.x; val y = pos.y; val z = pos.z
        // Iterate entries (not just values) so we can skip ships whose bubble
        // contains the camera — those ships render normally to main FB.
        for ((shipId, box) in concealed.entries) {
            if (!box.contains(x, y, z)) continue
            if (insideBubbles.contains(shipId)) continue
            return true
        }
        return false
    }

    /** True when the entity's `lastShipStoodOn` (VS2 dragging info) is a currently
     *  concealed ship. Uses the raw field rather than [EntityDraggingInformation.isEntityBeingDraggedByAShip]
     *  because that helper layers three gates (ship non-null AND
     *  `ticksSinceStoodOnShip < 25` AND `!mountedToEntity`) any one of which can
     *  briefly evaluate false between physics frames — and the entity would render
     *  un-concealed for those frames. Using the raw field is the simplest reading
     *  of "this entity is associated with that ship." */
    @JvmStatic
    fun isEntityConcealed(entity: Entity): Boolean {
        if (concealed.isEmpty()) return false
        val info = (entity as? IEntityDraggingInformationProvider)?.draggingInformation
            ?: return false
        val shipId = info.lastShipStoodOn ?: return false
        if (!concealed.containsKey(shipId)) return false
        // Camera inside that ship's bubble — the entity should render normally
        // (e.g., goats dragged by a ship the player is standing on).
        if (insideBubbles.contains(shipId)) return false
        return true
    }

    /** True when the world point projects into any concealed ship's BUBBLE volume —
     *  an ellipsoid CIRCUMSCRIBED around the ship-local AABB (half-axes =
     *  √3 × AABB half-extents, exactly matching what [ConcealmentBubbleRenderer]
     *  draws to the mask FB). Used by the particle filter so particles disappear
     *  on the same curved boundary the cloak bubble shows visually, not on the
     *  AABB rectangle.
     *
     *  Uses [ClientShip.renderTransform], NOT `ship.transform` — the latter is the
     *  game-tick (physics) transform, which lags one interpolation frame behind
     *  the visual position the player actually sees. Every visual on our side
     *  (bubble silhouette, side-FB ship render) is driven by `renderTransform`, so
     *  the particle world-point check has to project through the same transform
     *  or the cull boundary drifts away from the visible bubble. */
    @JvmStatic
    fun isWorldPointConcealed(x: Double, y: Double, z: Double): Boolean {
        if (concealed.isEmpty()) return false
        val level = Minecraft.getInstance().level ?: return false
        for (shipId in concealed.keys) {
            // Camera inside this ship's bubble — its particles render normally.
            if (insideBubbles.contains(shipId)) continue
            val ship = (level.shipObjectWorld.allShips.getById(shipId)
                as? org.valkyrienskies.core.api.ships.ClientShip) ?: continue
            val box = concealed[shipId] ?: continue
            val v = org.joml.Vector3d(x, y, z)
            ship.renderTransform.worldToShip.transformPosition(v)
            // VS2 shipAABB convention: maxX/Y/Z are INCLUSIVE — match
            // [ConcealmentBubbleRenderer]'s `+1` in the centre + half-extent math
            // so the ellipsoid here is identical to the one drawn to the mask FB.
            val cx = (box.minX + box.maxX + 1.0) * 0.5
            val cy = (box.minY + box.maxY + 1.0) * 0.5
            val cz = (box.minZ + box.maxZ + 1.0) * 0.5
            val hx = (box.maxX + 1 - box.minX) * 0.5 * BUBBLE_CIRCUMSCRIBE
            val hy = (box.maxY + 1 - box.minY) * 0.5 * BUBBLE_CIRCUMSCRIBE
            val hz = (box.maxZ + 1 - box.minZ) * 0.5 * BUBBLE_CIRCUMSCRIBE
            val nx = (v.x - cx) / hx
            val ny = (v.y - cy) / hy
            val nz = (v.z - cz) / hz
            if (nx * nx + ny * ny + nz * nz <= 1.0) return true
        }
        return false
    }

    /** Same √3 factor used by [ConcealmentBubbleRenderer.CIRCUMSCRIBE]: an
     *  ellipsoid with half-axes √3 × (AABB half-extents) circumscribes the AABB —
     *  the eight box corners lie exactly on the ellipsoid surface. */
    private val BUBBLE_CIRCUMSCRIBE: Double = Math.sqrt(3.0)

    /** If the world point falls inside any concealed ship's bubble ellipsoid,
     *  return the highest cloak progress of those ships. Otherwise return 0.
     *
     *  Used to drive the "camera-inside-bubble" inside-observer effect: when
     *  the player is physically inside a cloak bubble, the post-shader runs a
     *  different render mode — cloaked content (from side FB) shows crisply,
     *  the outside world (main FB) gets a light warbly distortion. Highest
     *  progress, not first match, so multiple overlapping bubbles converge on
     *  the most-engaged cloak's intensity for the warble strength. */
    @JvmStatic
    fun bubbleProgressAt(x: Double, y: Double, z: Double): Float {
        if (concealed.isEmpty()) return 0f
        val level = Minecraft.getInstance().level ?: return 0f
        var maxProgress = 0f
        for (shipId in concealed.keys) {
            val ship = (level.shipObjectWorld.allShips.getById(shipId)
                as? org.valkyrienskies.core.api.ships.ClientShip) ?: continue
            val box = concealed[shipId] ?: continue
            val v = org.joml.Vector3d(x, y, z)
            ship.renderTransform.worldToShip.transformPosition(v)
            val cx = (box.minX + box.maxX + 1.0) * 0.5
            val cy = (box.minY + box.maxY + 1.0) * 0.5
            val cz = (box.minZ + box.maxZ + 1.0) * 0.5
            val hx = (box.maxX + 1 - box.minX) * 0.5 * BUBBLE_CIRCUMSCRIBE
            val hy = (box.maxY + 1 - box.minY) * 0.5 * BUBBLE_CIRCUMSCRIBE
            val hz = (box.maxZ + 1 - box.minZ) * 0.5 * BUBBLE_CIRCUMSCRIBE
            val nx = (v.x - cx) / hx
            val ny = (v.y - cy) / hy
            val nz = (v.z - cz) / hz
            if (nx * nx + ny * ny + nz * nz <= 1.0) {
                val p = ClientShipCloakingState.getProgress(shipId)
                if (p > maxProgress) maxProgress = p
            }
        }
        return maxProgress
    }
}
