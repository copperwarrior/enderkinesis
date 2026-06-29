package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import org.valkyrienskies.mod.common.shipObjectWorld

/** Per-frame check that swaps ships into / out of [CloakingMixinSupport]'s concealed
 *  set as their client-side cloak progress crosses **1.0** (the full-strength
 *  threshold). Crossing the threshold triggers a chunk re-compile for every chunk that
 *  intersects the ship's shipyard AABB — forces vanilla / Sodium to ask
 *  `BlockRenderDispatcher.renderBatched` again and observe the cancel.
 *
 *  This is the only place that mutates [CloakingMixinSupport]'s map; the mixins are
 *  read-only. Driven from the same per-frame `Camera.setup` TAIL inject that drives
 *  the post-effect (see [ConcealmentPostEffect.tick]).
 */
object ConcealmentTransitionWatcher {

    /** Chunk-level rendering is now redirected, not concealed: VS2's
     *  `renderShip` event hooks (see [ShipOnlyRenderer]) bind the side FB
     *  around each ship's chunk render, so ship blocks end up in
     *  [ConcealmentShipFB] instead of the main FB. No chunk concealment, no
     *  chunk recompile.
     *
     *  This threshold is now ONLY for **non-chunk** render paths — block
     *  entities, living entities, particles. Those mixins still cancel based
     *  on [CloakingMixinSupport.isPositionConcealed]; without the cancel, a
     *  cloaked ship's chests, riders, and particles would render to the main
     *  FB even while the chunks are hidden. */
    private const val CONCEAL_THRESHOLD: Float = 0.001f

    /** Snapshot of ships we *believe* are currently concealed. Compared against the
     *  truth from [ClientShipCloakingState] each frame to find the transitions. */
    private val seen: MutableSet<Long> = HashSet()

    @JvmStatic
    fun tick() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: run {
            // Level gone — drop everything.
            if (seen.isNotEmpty()) {
                seen.clear()
                clearAllConcealment()
            }
            return
        }

        val nowConcealed = HashSet<Long>()
        for (id in ClientShipCloakingState.activeShipIds()) {
            if (ClientShipCloakingState.getProgress(id) >= CONCEAL_THRESHOLD) {
                nowConcealed.add(id)
            }
        }

        // Newly concealed.
        for (id in nowConcealed) {
            if (seen.add(id)) {
                val ship = level.shipObjectWorld.allShips.getById(id) ?: continue
                val aabb = ship.shipAABB ?: continue
                CloakingMixinSupport.markConcealed(
                    id,
                    aabb.minX(), aabb.minY(), aabb.minZ(),
                    aabb.maxX(), aabb.maxY(), aabb.maxZ(),
                )
                // Force a chunk-worker recompile of the ship's sections. The
                // ShipRendererCloakingMixin just flipped this ship from BATCHED to
                // VANILLA, but vanilla's CompiledChunk for those sections may not
                // have valid solid/cutout meshes — either because the chunks have
                // only ever been compiled into VS2's batched mesh (separate
                // storage), or because they were compiled while a previous version
                // of the cloak mixin was cancelling solid emission. Re-dirtying
                // forces a vanilla recompile so per-ship layer caches see real
                // content and the renderShip events fire for the solid pass.
                markChunksDirty(
                    aabb.minX(), aabb.minY(), aabb.minZ(),
                    aabb.maxX(), aabb.maxY(), aabb.maxZ(),
                )
            }
        }

        // Newly un-concealed — also dirty-mark, so the ship re-renters batched-
        // renderer territory without stale empty vanilla meshes hanging around.
        val iter = seen.iterator()
        while (iter.hasNext()) {
            val id = iter.next()
            if (id !in nowConcealed) {
                iter.remove()
                val ship = level.shipObjectWorld.allShips.getById(id)
                val aabb = ship?.shipAABB
                CloakingMixinSupport.markVisible(id)
                if (aabb != null) {
                    markChunksDirty(
                        aabb.minX(), aabb.minY(), aabb.minZ(),
                        aabb.maxX(), aabb.maxY(), aabb.maxZ(),
                    )
                }
            }
        }

        // Refresh "which concealed bubbles contain the camera right now" so
        // the cancel + redirect mixins firing later this frame skip ships the
        // observer is inside. Runs AFTER the concealed map mutations above.
        val camPos = mc.gameRenderer.mainCamera.position
        CloakingMixinSupport.updateInsideBubbles(camPos.x, camPos.y, camPos.z)
    }

    fun reset() {
        seen.clear()
        clearAllConcealment()
    }

    private fun clearAllConcealment() {
        for (id in CloakingMixinSupport.concealedShipIds().toList()) {
            CloakingMixinSupport.markVisible(id)
        }
    }

    /** Mark every chunk section overlapping `[min, max]` (shipyard coords) dirty so
     *  the chunk worker recompiles them under the current cloak architecture —
     *  see the call sites for why this matters. */
    private fun markChunksDirty(minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int) {
        val renderer = Minecraft.getInstance().levelRenderer
        renderer.setBlocksDirty(minX, minY, minZ, maxX, maxY, maxZ)
    }
}
