package org.shipwrights.enderkinesis.client

import com.mojang.logging.LogUtils
import org.valkyrienskies.mod.common.hooks.VSGameEvents

/** Staff of Concealment — ship-only render redirection.
 *
 *  Architecture: VS2 already renders every ship's chunks correctly with the right
 *  matrices, lighting, uniforms, samplers, fog, normals, animated textures, BlockEntity
 *  light propagation, etc. We don't reimplement any of that. Instead we listen to VS2's
 *  per-ship render events and, for cloaking ships, swap the active framebuffer to
 *  [ConcealmentShipFB] *just* for that ship's `renderChunkLayer` call.
 *
 *  - `shipsStartRendering` (once per frame) — clear our side FB.
 *  - `renderShip` (per ship × per render layer) — if the ship is cloaking, bind side FB
 *    and suspend our concealment cancel so VS2's render path actually emits geometry.
 *  - `postRenderShip` (per ship × per render layer) — restore main FB, resume cancel.
 *
 *  Net result: VS2 + vanilla render each cloaked ship to our side FB with byte-for-byte
 *  the same pipeline as a non-cloaked ship. No matrix duplication, no lighting wrapper,
 *  no chunk shader uniform plumbing, no Sodium parallel path. */
object ShipOnlyRenderer {

    private val LOG = LogUtils.getLogger()
    private var initialised = false
    private var sideFbBound = false


    // Diagnostic counters — flushed periodically so we don't spam the log every
    // frame. If `renderShipFiredFor` stays at 0 while a cloak is active, the
    // event hook is dead and the issue is upstream (ship not in per-ship loop).
    private var shipsStartFired: Int = 0
    private var renderShipFiredFor: Int = 0
    private var renderShipFiredForCloaking: Int = 0
    private var lastLogMs: Long = 0L

    /** Wire up the VS2 event listeners. Idempotent — safe to call multiple times. */
    @JvmStatic
    fun init() {
        if (initialised) return
        initialised = true
        LOG.info("[enderkinesis cloak] ShipOnlyRenderer wired up VS2 events")

        VSGameEvents.shipsStartRendering.on { _, _ ->
            shipsStartFired++
            // Idempotent within a frame (gated by ConcealmentShipFB's own
            // flag, reset by beginFrame() from LevelRenderer.renderLevel HEAD).
            // Vanilla path emits once per frame so this is effectively the
            // initial clear; kept here for symmetry with the Sodium path.
            ConcealmentShipFB.clearOnly()
        }

        VSGameEvents.renderShip.on { event, _ ->
            renderShipFiredFor++
            if (!isCloaking(event.ship.id)) return@on
            renderShipFiredForCloaking++
            // copyMainDepth is idempotent within a frame: only the first call
            // per frame across all paths (batched + vanilla) actually blits.
            ConcealmentShipFB.copyMainDepth()
            ConcealmentShipFB.bindForWrite()
            sideFbBound = true
            CloakingMixinSupport.setCancelSuspended(true)
        }

        VSGameEvents.postRenderShip.on { event, _ ->
            if (!isCloaking(event.ship.id)) return@on
            CloakingMixinSupport.setCancelSuspended(false)
            if (sideFbBound) {
                ConcealmentShipFB.unbind()
                sideFbBound = false
            }
        }

        // VS2 fires a separate set of events when its Sodium integration is
        // doing the ship draws — the vanilla `shipsStartRendering / renderShip
        // / postRenderShip` channels never fire on that path. Without these
        // Sodium-mirror subscriptions the side FB never clears (ghost trails)
        // and the cancel-suspend never engages (so even after the redirect
        // mixin runs, no ship pixels land in side FB).
        VSGameEvents.shipsStartRenderingSodium.on { _, _ ->
            shipsStartFired++
            // No-op after the first call per frame — see [ConcealmentShipFB.clearOnly].
            // Without that gate the per-pass fires would wipe earlier passes'
            // ship pixels and the side FB would only retain the LAST pass's
            // draws (translucent, near-empty for typical ships).
            ConcealmentShipFB.clearOnly()
        }

        VSGameEvents.renderShipSodium.on { event, _ ->
            renderShipFiredFor++
            if (!isCloaking(event.ship.id)) return@on
            renderShipFiredForCloaking++
            ConcealmentShipFB.copyMainDepth()
            ConcealmentShipFB.bindForWrite()
            sideFbBound = true
            CloakingMixinSupport.setCancelSuspended(true)
        }

        VSGameEvents.postRenderShipSodium.on { event, _ ->
            if (!isCloaking(event.ship.id)) return@on
            CloakingMixinSupport.setCancelSuspended(false)
            if (sideFbBound) {
                ConcealmentShipFB.unbind()
                sideFbBound = false
            }
        }
    }

    /** Periodic diagnostic flush — call from the per-frame tick. Logs once per
     *  second when a cloak is active, so we can tell if events are firing and
     *  whether the cloak ship is reaching the per-ship loop. */
    @JvmStatic
    fun maybeLogDiagnostics(anyCloakActive: Boolean) {
        if (!anyCloakActive) {
            shipsStartFired = 0
            renderShipFiredFor = 0
            renderShipFiredForCloaking = 0
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastLogMs < 1000) return
        lastLogMs = now
        LOG.info(
            "[enderkinesis cloak] tick: shipsStartRendering={} renderShip(all)={} renderShip(cloaking)={}",
            shipsStartFired, renderShipFiredFor, renderShipFiredForCloaking,
        )
        shipsStartFired = 0
        renderShipFiredFor = 0
        renderShipFiredForCloaking = 0
    }

    private fun isCloaking(shipId: Long): Boolean {
        val progress = ClientShipCloakingState.getProgress(shipId)
        return progress > 0.001f
    }

    /** Force-eject any FB binding (in case of cleanup mid-render). */
    fun reset() {
        if (sideFbBound) {
            ConcealmentShipFB.unbind()
            sideFbBound = false
        }
        CloakingMixinSupport.setCancelSuspended(false)
        ConcealmentShipFB.destroy()
    }
}
