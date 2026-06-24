package org.shipwrights.enderkinesis.client

import com.mojang.logging.LogUtils
import java.util.function.IntSupplier
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.PostPass
import org.shipwrights.enderkinesis.EnderkinesisMod

/** Manages the Staff of Concealment refraction post-effect.
 *
 *  The bubble pass ([ConcealmentBubbleRenderer]) writes a per-pixel intensity into
 *  [ConcealmentMaskFB]'s red channel before the post-effect runs. The post-shader then
 *  reads that mask and applies refraction at each pixel by its mask value — no global
 *  intensity uniform needed; the mask itself encodes per-pixel cloak progress.
 *
 *  Lifecycle: load the PostChain ONCE per session (lazily on first detected cloak, or
 *  eagerly via [preload]), then toggle [GameRenderer]'s `effectActive` flag on/off as
 *  cloaks engage and disengage. The PostChain stays in memory the whole session.
 *  Reason: every fresh `loadEffect` triggers a one-frame visual artifact (first-use
 *  GLSL compile + intermediate-target allocation + initial sampler-binding state).
 *  By loading once and toggling the gate, engagements after the first are clean — and
 *  with [preload] called at world join, even the first engagement is clean because the
 *  load happened on a frame the user wasn't watching the cloak.
 *
 *  On load, the `MaskSampler` and `ShipSampler` textures are wired to each post-pass
 *  as `addAuxAsset` so the framework binds our R8 / RGBA FBs to the correct texture
 *  slots at render time. */
object ConcealmentPostEffect {

    private val LOG = LogUtils.getLogger()
    private val EFFECT_LOC = EnderkinesisMod.id("shaders/post/concealment_refract.json")

    /** True once we've successfully `loadEffect`'d the PostChain this session. Once
     *  true, stays true until [reset] is called (world leave) — we never call
     *  `shutdownEffect` between cloaks. */
    private var loaded: Boolean = false
    /** True when we last set `effectActive=true` on GameRenderer. Tracks our own
     *  intent so we can avoid redundant accessor writes per frame. */
    private var running: Boolean = false
    private var startMs: Long = 0L

    /** Tracks the previous frame's "is any cloak active" so we can detect the
     *  rising edge (no cloak → cloak) and pick a fresh random ripple origin. */
    private var wasCloakActive: Boolean = false
    /** Random screen-UV corner picked once at the rising edge of each cloak
     *  engagement and held for the whole engagement. Replaces the per-frame
     *  ship-centre projection — the user wants ripples emanating from a
     *  corner, not from the ship's projected position. */
    private var rippleOriginU: Float = 0f
    private var rippleOriginV: Float = 0f
    private val rng = java.util.Random()

    /** Per-frame entry point. Inspects all currently-cloaking ships and toggles
     *  the post-effect; pushes uniforms for the shader animation. */
    @JvmStatic
    fun tick() {
        // Idempotent — wires up the VS2 ship-render event listeners on first call.
        // Once wired, this is a no-op.
        ShipOnlyRenderer.init()

        val mc = Minecraft.getInstance()
        if (mc.level == null) {
            if (running) setRunning(mc, false)
            return
        }

        // Preload eagerly on EVERY frame the player is in-world — idempotent via
        // the `loaded` flag, so only the first frame of the session actually
        // loads. By the time the user triggers a cloak, the PostChain is staged
        // and the first engagement doesn't pay the cold-load cost (first-use
        // GLSL compile + intermediate-target alloc + initial sampler-binding
        // state) that produces the one-frame dark vignette flash.
        ensureLoaded(mc)

        val cloakActive = anyCloakActive()
        ShipOnlyRenderer.maybeLogDiagnostics(cloakActive)

        // Rising edge — cloak just engaged this frame. Pick a fresh random
        // screen corner as the ripple origin and hold it for the duration of
        // this engagement.
        if (cloakActive && !wasCloakActive) pickRippleOrigin()
        wasCloakActive = cloakActive

        // Always assert the desired state (no `if (running)` short-circuit):
        // vanilla `loadEffect` sets `effectActive=true` as a side effect on the
        // preload frame, so we have to actively flip it back to false until a
        // cloak engages. Cheap — single field write per frame.
        setRunning(mc, cloakActive)
        if (cloakActive) pushUniforms(mc)
    }

    fun reset() {
        val mc = Minecraft.getInstance()
        if (running || loaded) {
            mc.gameRenderer.shutdownEffect()
            running = false
            loaded = false
        }
        wasCloakActive = false
        ConcealmentMaskFB.destroy()
        ShipOnlyRenderer.reset()
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

    private fun ensureLoaded(mc: Minecraft) {
        if (loaded) return
        val gr = mc.gameRenderer as org.shipwrights.enderkinesis.mixin.GameRendererPostEffectInvoker
        try {
            gr.`enderkinesis$loadEffect`(EFFECT_LOC)
            wireMaskSampler(mc)
            startMs = Util.getMillis()
            loaded = true
        } catch (t: Throwable) {
            LOG.warn("Failed to load concealment refraction post-effect", t)
        }
    }

    /** Flip GameRenderer's `effectActive` to gate whether `postEffect.process`
     *  runs each frame. PostChain stays loaded either way.
     *
     *  Always writes (no `if (running == on)` short-circuit): vanilla's F4
     *  `togglePostEffect` can flip the field underneath us between ticks. By
     *  re-asserting our desired state each frame we stay authoritative. */
    private fun setRunning(mc: Minecraft, on: Boolean) {
        if (!loaded && on) return
        val gr = mc.gameRenderer as org.shipwrights.enderkinesis.mixin.GameRendererPostEffectInvoker
        gr.`enderkinesis$setEffectActive`(on)
        running = on
    }

    /** Bind both side framebuffers as aux samplers on every pass of the freshly-
     *  loaded post-chain:
     *  - `MaskSampler` → bubble silhouette (R8).
     *  - `ShipSampler` → ship-only RGBA, source for the refraction sample. */
    private fun wireMaskSampler(mc: Minecraft) {
        val gr = mc.gameRenderer as org.shipwrights.enderkinesis.mixin.GameRendererPostEffectInvoker
        val chain = gr.`enderkinesis$getPostEffect`() ?: return
        val passes = (chain as org.shipwrights.enderkinesis.mixin.PostChainAccessor)
            .`enderkinesis$getPasses`()
        val width = mc.mainRenderTarget.width
        val height = mc.mainRenderTarget.height
        val maskSupplier = IntSupplier { ConcealmentMaskFB.colorTextureId }
        val shipSupplier = IntSupplier { ConcealmentShipFB.colorTextureId }
        for (pass in passes) {
            (pass as PostPass).addAuxAsset("MaskSampler", maskSupplier, width, height)
            pass.addAuxAsset("ShipSampler", shipSupplier, width, height)
        }
    }

    private fun pushUniforms(mc: Minecraft) {
        val gr = mc.gameRenderer as org.shipwrights.enderkinesis.mixin.GameRendererPostEffectInvoker
        val chain = gr.`enderkinesis$getPostEffect`()
        if (chain == null) {
            // Something nulled our PostChain (vanilla on resource reload, another
            // mod, etc.). Drop our tracking so the next tick re-loads cleanly.
            loaded = false
            running = false
            return
        }
        val passes = (chain as org.shipwrights.enderkinesis.mixin.PostChainAccessor)
            .`enderkinesis$getPasses`()
        val tSec = ((Util.getMillis() - startMs) / 1000.0).toFloat()
        // Linear progress (from the bubble renderer's last frame) drives the
        // shader's amp+freq ease and the cloak fade. The ripple origin is the
        // random screen corner picked at engagement — pushed via the
        // `ShipCenterUV` uniform name for historical reasons (the shader still
        // calls it that, but it's any screen UV).
        val progress = ConcealmentBubbleRenderer.lastProgress
        // Camera-inside-bubble probe: if the player is physically inside any
        // cloak bubble, this is the highest-progress containing cloak's value;
        // 0 otherwise. Drives the shader's inside-observer mode (crisp cloaked
        // content + warbly outside world).
        val camPos = mc.gameRenderer.mainCamera.position
        val insideProgress = CloakingMixinSupport.bubbleProgressAt(camPos.x, camPos.y, camPos.z)
        for (pass in passes) {
            val effect = (pass as org.shipwrights.enderkinesis.mixin.PostPassAccessor)
                .`enderkinesis$getEffect`()
            effect.safeGetUniform("TimeSec").set(tSec)
            effect.safeGetUniform("CloakProgress").set(progress)
            effect.safeGetUniform("ShipCenterUV").set(rippleOriginU, rippleOriginV)
            effect.safeGetUniform("InsideBubbleProgress").set(insideProgress)
        }
    }
}
