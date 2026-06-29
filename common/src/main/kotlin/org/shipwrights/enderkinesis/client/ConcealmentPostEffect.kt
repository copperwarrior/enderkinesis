package org.shipwrights.enderkinesis.client

import com.mojang.logging.LogUtils
import java.util.function.IntSupplier
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.EffectInstance

/** Staff of Concealment refraction-pass driver.
 *
 *  The bubble pass ([ConcealmentBubbleRenderer]) writes per-pixel mask data
 *  into [ConcealmentMaskFB] (R = cloak progress, G = silhouette indicator) and
 *  cloaked-ship blocks/entities/particles end up in [ConcealmentShipFB] via the
 *  cancel mixins and the [ShipOnlyRenderer] VS2 event hooks. Once per frame
 *  this object samples both side FBs plus main into the concealment_refract
 *  fragment shader, which composites the refracted ship+world back into main.
 *
 *  Effect dispatch goes through [RefractionPass] (direct fullscreen quad onto
 *  main) instead of vanilla's `PostChain`/`PostPass`. The PostChain dispatch
 *  fails to land its output on screen under the Prism Flatpak Mesa GL
 *  extension on gfx1201 — vanilla creeper/spider spectator effects exhibit the
 *  same breakage on that runtime, ruling out our shader code; the failure is
 *  in the framework. See commit `ca4195e` for the PostChain-based predecessor.
 *
 *  Per-frame contract:
 *   - [tick] (from `CameraScryingMixin` TAIL of `Camera.setup`) keeps the
 *     [EffectInstance] preloaded, runs rising-edge ripple-origin selection,
 *     and bootstraps [ShipOnlyRenderer]. Cheap — idempotent.
 *   - [renderRefraction] (from `LevelRendererBubblePassMixin` TAIL of
 *     `renderLevel`) runs the actual refraction pass when any cloak is active. */
object ConcealmentPostEffect {

    private val LOG = LogUtils.getLogger()

    @Volatile private var refractionEffect: EffectInstance? = null

    /** Wall-clock epoch for the `TimeSec` uniform — set to current time on
     *  effect load so the shader's noise field starts at t=0 each session and
     *  doesn't suffer precision loss from raw monotonic millis. */
    private var startMs: Long = 0L

    private var wasCloakActive: Boolean = false

    /** Random screen-UV corner picked at the rising edge of each cloak
     *  engagement and held for the duration. Pushed as `ShipCenterUV` (the
     *  shader's historical name — anatomically it's any 2D screen UV). */
    private var rippleOriginU: Float = 0f
    private var rippleOriginV: Float = 0f
    private val rng = java.util.Random()

    /** Per-frame keep-alive + state tick. */
    @JvmStatic
    fun tick() {
        // Idempotent VS2 event wire-up. Once wired, no-op.
        ShipOnlyRenderer.init()

        val mc = Minecraft.getInstance()
        if (mc.level == null) {
            wasCloakActive = false
            return
        }

        // Preload the shader on the first in-world frame so the first cloak
        // engagement doesn't pay the GLSL compile cost.
        ensureLoaded(mc)

        val cloakActive = anyCloakActive()
        ShipOnlyRenderer.maybeLogDiagnostics(cloakActive)

        if (cloakActive && !wasCloakActive) pickRippleOrigin()
        wasCloakActive = cloakActive
    }

    /** Run the refraction pass over main FB. Called from
     *  [org.shipwrights.enderkinesis.mixin.LevelRendererBubblePassMixin] at the
     *  TAIL of `LevelRenderer.renderLevel`, after the bubble draw populates
     *  the mask FB and cloak-ship rendering populates the ship FB. Skipped
     *  entirely when no cloak is active so non-cloak frames pay nothing. */
    @JvmStatic
    fun renderRefraction() {
        if (!anyCloakActive()) return
        val mc = Minecraft.getInstance()
        val effect = ensureLoaded(mc) ?: return
        val tSec = ((Util.getMillis() - startMs) / 1000.0).toFloat()
        val progress = ConcealmentBubbleRenderer.lastProgress
        val camPos = mc.gameRenderer.mainCamera.position
        val insideProgress = CloakingMixinSupport.bubbleProgressAt(camPos.x, camPos.y, camPos.z)
        RefractionPass.run(
            effect,
            extraSamplers = listOf(
                "MaskSampler" to IntSupplier { ConcealmentMaskFB.colorTextureId },
                "ShipSampler" to IntSupplier { ConcealmentShipFB.colorTextureId },
            ),
        ) { eff ->
            eff.safeGetUniform("TimeSec").set(tSec)
            eff.safeGetUniform("CloakProgress").set(progress)
            eff.safeGetUniform("ShipCenterUV").set(rippleOriginU, rippleOriginV)
            eff.safeGetUniform("InsideBubbleProgress").set(insideProgress)
        }
    }

    /** World-leave / client teardown cleanup. */
    fun reset() {
        refractionEffect?.close()
        refractionEffect = null
        wasCloakActive = false
        ConcealmentMaskFB.destroy()
        ShipOnlyRenderer.reset()
    }

    private fun ensureLoaded(mc: Minecraft): EffectInstance? {
        refractionEffect?.let { return it }
        return try {
            val e = EffectInstance(mc.resourceManager, "concealment_refract")
            refractionEffect = e
            startMs = Util.getMillis()
            e
        } catch (t: Throwable) {
            LOG.warn("Failed to load concealment refraction shader program", t)
            null
        }
    }

    private fun anyCloakActive(): Boolean {
        for (id in ClientShipCloakingState.activeShipIds()) {
            if (ClientShipCloakingState.getProgress(id) > 0f) return true
        }
        return false
    }

    /** One of the four screen-UV corners: (0,0), (1,0), (0,1), (1,1). Two
     *  independent bits of randomness — bit 0 → U corner, bit 1 → V corner. */
    private fun pickRippleOrigin() {
        val corner = rng.nextInt(4)
        rippleOriginU = if (corner and 1 == 0) 0f else 1f
        rippleOriginV = if (corner and 2 == 0) 0f else 1f
    }
}
