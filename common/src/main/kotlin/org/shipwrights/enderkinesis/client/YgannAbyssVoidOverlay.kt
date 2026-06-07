package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientGuiEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.DeathScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import org.shipwrights.enderkinesis.dimension.YgannAbyss
import org.shipwrights.enderkinesis.dimension.YgannAbyssVoidFade
import org.shipwrights.enderkinesis.registry.EKEffects
import kotlin.math.max

/**
 * Client half of the YgannAbyss void-death flow (see [YgannAbyssVoidFade]).
 *
 *  ## Position-driven progress
 *
 *  The overlay's intensity is keyed to how far the player has fallen into the void
 *  *plus* a baseline from the [EKEffects.YGANN_MADNESS] effect:
 *
 *  ```
 *  fallProgress    = clamp( (minBuildHeight − playerY) / 64 , 0, 1 )  // Abyss only
 *  madnessProgress = (4 + (amplifier + 1) × 7) / 64                    // any dimension
 *  rawProgress     = max(fallProgress, madnessProgress)
 *  ```
 *
 *  The fall component is 0 at the top of the void (`y = minBuildHeight`) and 1 at the
 *  killplane. A free-faller crosses the 64-block zone in <1 s; a slow descent (elytra,
 *  hovering ship) lingers and the fade lingers with it. Death lands exactly when raw
 *  progress reaches 1 because the server kills on the same y check.
 *
 *  Madness contributes a fixed baseline anywhere in the world: level 1 = 11 block-
 *  equivalents of descent (clearly visible blobs), level 5 = 39 block-equivalents
 *  (heavy coverage with portal showing through deepest cells), linear in between
 *  (7 per level). Layered under any actual void fall via `max`.
 *
 *  ## Rendering architecture
 *
 *  All visual work happens in the [YgannAbyssRenderTypes.ABYSS_PORTAL] fragment shader:
 *
 *   - 4-octave fBm value noise sampled per pixel (was: 2-octave at 8 px tile corners)
 *   - Four-band depth model: edge fade-in, solid-black hold, portal crossfade, full portal
 *   - Vanilla end-portal colour (same textures, matrices, palette as the block)
 *
 *  This file just passes the four dynamic inputs through as uniforms:
 *
 *  | Uniform      | Source                                                      |
 *  | ------------ | ----------------------------------------------------------- |
 *  | `Progress`   | the `rawProgress` computed above                            |
 *  | `NoiseDrift` | `gameTime × DRIFT_RATE_{X,Y}` — slow flow in noise space    |
 *  | `NoiseSeed`  | re-rolled on each entry into the fading state (per "fall")  |
 *  | `ScreenSize` | current GUI framebuffer size                                |
 *
 *  …then emits one full-screen quad and lets the shader do everything else. No host-
 *  side noise, no tile grid, no per-vertex colour.
 *
 *  ## Why no [net.minecraft.world.effect.MobEffects.BLINDNESS]
 *
 *  Blindness only reduces fog *distance*, which dims geometry. The Abyss has no
 *  geometry below the killplane — the player just sees an open sky-coloured void — so
 *  blindness reads as basically no change. A direct GUI overlay is the only way to
 *  actually darken the screen.
 */
object YgannAbyssVoidOverlay {

    /** Z translation applied while drawing the overlay so it sits in front of container
     *  slot items (z=100) and tooltips (z=400) in MC's GUI ortho projection (where
     *  larger z = closer to viewer with the LEQUAL depth test). 500 leaves enough
     *  headroom that we won't be hidden by anything reasonable, while staying inside
     *  the GUI projection's valid range. */
    private const val OVERLAY_Z = 500.0

    /** Tracks the previous frame's fading state so we can detect entry into the void
     *  (raw progress 0 → >0) and re-seed the noise on that transition. */
    private var wasFading = false

    /** Per-fade noise seed offsets. Re-rolled the moment the player enters the void
     *  zone so each session is its own pattern. Bounded to a small range — the shader
     *  hash `fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453)` loses precision
     *  when `dot(p, …)` exceeds ~2²³ ≈ 8M. With pixel-level coord variation of
     *  ~0.03 noise units, a seed in the millions makes adjacent pixels round to the
     *  same hash input → the whole screen samples one constant noise value. Cap at
     *  100 — still 10⁴+ unique (x,y) combos per session, comfortably within precision. */
    private var seedX = 0.0
    private var seedY = 0.0

    /** Game-time tick when the current fading session began. Drift is computed
     *  relative to this rather than the absolute `level.gameTime` — on a long-running
     *  world `gameTime` can be tens of millions of ticks, blowing up the noiseCoord
     *  magnitude into the same precision-loss region as a large seed. Session-relative
     *  drift stays in [0, ~21] noise units for a 7-minute madness session. */
    private var sessionStartGameTime: Long = 0L

    fun init() {
        // Two-pass strategy to control which UI sits above the void overlay:
        //
        //   - RENDER_HUD fires at the tail of Gui.render(): covers the HUD (hotbar /
        //     hearts / chat / F3 / boss bars), and also lays the overlay UNDER any
        //     subsequently-rendered Screen — including pause/death, whose translucent
        //     backgrounds let it show through behind their text/buttons.
        //
        //   - RENDER_POST fires after Screen.render(): re-draws the overlay so it
        //     sits ABOVE most screens (inventory, container, chat, recipe book, etc.).
        //     Skipped for PauseScreen and DeathScreen so those stay on top of the
        //     overlay and remain fully readable.
        //
        // Net result per situation:
        //   no screen     → drawn once (RENDER_HUD), over HUD
        //   inventory etc → drawn once (RENDER_POST), over the screen
        //   pause / death → drawn once (RENDER_HUD), screen draws on top
        ClientGuiEvent.RENDER_HUD.register(::renderHud)
        ClientGuiEvent.RENDER_POST.register(::renderPost)
    }

    private fun renderHud(graphics: GuiGraphics, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val screen = mc.screen
        if (screen != null && screen !is PauseScreen && screen !is DeathScreen) return
        // Cheap dim/effect gate up front so the overlay does ZERO
        // work for players who are nowhere near Ygann. Spark v8
        // showed drawOverlay accumulating 116 ms cumulative on the
        // render thread despite the player never visiting the
        // Abyss — drawOverlay's internal rawProgress<=0 gate fires
        // correctly but only AFTER it touches player + level +
        // effect map every frame, which adds up at high FPS.
        if (!shouldRenderForF1Fallback()) return
        drawOverlay(mc, graphics, partialTick)
    }

    private fun renderPost(screen: Screen, graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (screen is PauseScreen || screen is DeathScreen) return
        if (!shouldRenderForF1Fallback()) return
        drawOverlay(Minecraft.getInstance(), graphics, partialTick)
    }

    /**
     * Cheap predicate for [org.shipwrights.enderkinesis.mixin.GameRendererYgannAbyssOverlayMixin]
     * to decide whether to force `Gui.render` to be called in the F1+no-screen case.
     * Mirrors [drawOverlay]'s "do we have anything to render" gates without doing any
     * actual rendering work.
     */
    @JvmStatic
    fun shouldRenderForF1Fallback(): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        if (player.isSpectator) return false
        val inAbyssBelowVoid = level.dimension() == YgannAbyss.LEVEL_KEY &&
            player.y < level.minBuildHeight.toDouble()
        val hasMadness = player.getEffect(EKEffects.YGANN_MADNESS.get()) != null
        return inAbyssBelowVoid || hasMadness
    }

    /**
     * Fallback entry point invoked from [org.shipwrights.enderkinesis.mixin.GuiYgannAbyssOverlayMixin].
     *
     * Architectury's [ClientGuiEvent.RENDER_HUD] wraps Fabric's `HudRenderCallback`,
     * which is wired inside `Gui.render`'s `!hideGui` branch — so when the player has
     * the HUD hidden via F1 and no screen is open (or only pause/death is open), neither
     * `RENDER_HUD` nor [ClientGuiEvent.RENDER_POST] fire and our overlay disappears.
     * The mixin injects at the `RETURN` of `Gui.render` and calls here for exactly
     * those cases, so the overlay survives F1.
     *
     * The mixin handles its own gating; this method just delegates to [drawOverlay].
     */
    @JvmStatic
    fun renderForF1Fallback(graphics: GuiGraphics, partialTick: Float) {
        drawOverlay(Minecraft.getInstance(), graphics, partialTick)
    }

    private fun drawOverlay(mc: Minecraft, graphics: GuiGraphics, partialTick: Float) {
        val player = mc.player
        val level = mc.level
        if (player == null || level == null) { wasFading = false; return }
        if (player.isSpectator) { wasFading = false; return }

        // Two contributors to rawProgress — take the max so they cleanly overlap:
        //   (a) fallProgress — only in the Abyss, partial-tick-interpolated y so the
        //       overlay grows smoothly as the player falls.
        //   (b) madnessProgress — anywhere, fixed baseline from YGANN_MADNESS amplifier.
        val pt = partialTick.toDouble()
        val voidTop = level.minBuildHeight.toDouble()
        val fallProgress = if (level.dimension() == YgannAbyss.LEVEL_KEY) {
            val playerY = player.yo + (player.y - player.yo) * pt
            ((voidTop - playerY) / 64.0).coerceIn(0.0, 1.0)
        } else 0.0
        // Density mapping: L1 = 11 blocks-equivalent, L5 = 39 blocks-equivalent, linear
        // in between (7 per level). The (amp + 1) lift fixes an off-by-one: visually,
        // L2 was reading like L1 — each level was showing the previous level's intended
        // density. Treating `amp + 1` as the display level number aligns the visual
        // ramp with the level shown in the GUI.
        val madnessAmp = player.getEffect(EKEffects.YGANN_MADNESS.get())?.amplifier
        val madnessProgress = if (madnessAmp != null) {
            val level = madnessAmp.coerceIn(0, 4) + 1   // 1..5
            (4.0 + level * 7.0) / 64.0
        } else 0.0
        val rawProgress = max(fallProgress, madnessProgress)
        if (rawProgress <= 0.0) { wasFading = false; return }

        // Crossing INTO the fading state — re-roll noise seed and pin the session
        // start time so each fall has its own splotch layout and the drift starts
        // from zero (rather than the world's absolute gameTime, which on long-running
        // worlds is large enough to blow shader hash precision).
        if (!wasFading) {
            seedX = Math.random() * 100.0
            seedY = Math.random() * 100.0
            sessionStartGameTime = level.gameTime
            wasFading = true
        }

        // The shader takes a frame or two to compile after a resource reload. If it
        // isn't ready yet, draw nothing — the overlay re-renders every frame and will
        // pick up the shader as soon as it's available.
        val shader = YgannAbyssRenderTypes.getShader() ?: return

        // Session-relative time in seconds. The shader uses this with per-octave drift
        // speeds to morph each fBm octave at its own rate, producing apparent shape-
        // shifting of the blobs rather than a uniform translation. Session-relative
        // (not world epoch) keeps the value small enough for shader hash precision.
        val sessionTicks = (level.gameTime - sessionStartGameTime).toDouble() + pt
        val sessionTime = sessionTicks / 20.0

        val width = graphics.guiWidth()
        val height = graphics.guiHeight()

        // Custom uniforms. Set on the cached ShaderInstance before endBatch — values
        // are read during shader.apply() inside the draw. ModelViewMat / ProjMat /
        // GameTime are populated automatically by MC from RenderSystem state.
        shader.safeGetUniform("Progress").set(rawProgress.toFloat())
        shader.safeGetUniform("SessionTime").set(sessionTime.toFloat())
        shader.safeGetUniform("NoiseSeed").set(seedX.toFloat(), seedY.toFloat())
        shader.safeGetUniform("ScreenSize").set(width.toFloat(), height.toFloat())

        // Flush whatever previous passes (HUD elements / Screen items / tooltips) had
        // queued — without this our quad batches with the screen's draws and the
        // per-RenderType flush order can leave items above us.
        graphics.flush()
        // Push pose to OVERLAY_Z so our fragments pass the LEQUAL depth test against
        // everything below (slot items at z=100, tooltips at z=400).
        graphics.pose().pushPose()
        graphics.pose().translate(0.0, 0.0, OVERLAY_Z)

        val bufferSource = graphics.bufferSource()
        val matrix = graphics.pose().last().pose()
        val portalType = YgannAbyssRenderTypes.ABYSS_PORTAL
        val portalConsumer = bufferSource.getBuffer(portalType)

        // One full-screen quad; the fragment shader handles per-pixel noise, depth
        // bands, alpha, blend, and the vanilla end-portal colour. TL → BL → BR → TR
        // (CCW in Y-down) matching GuiGraphics.fill order.
        val w = width.toFloat()
        val h = height.toFloat()
        portalConsumer.vertex(matrix, 0f, 0f, 0f).endVertex()
        portalConsumer.vertex(matrix, 0f, h,  0f).endVertex()
        portalConsumer.vertex(matrix, w,  h,  0f).endVertex()
        portalConsumer.vertex(matrix, w,  0f, 0f).endVertex()

        // Force-flush our batch HERE rather than relying on end-of-frame. Our custom
        // RenderType isn't in vanilla's fixed-buffer list — it shares the dynamic
        // builder. If anything between renderPost and end-of-frame gets a different
        // RenderType's buffer, our queued draw gets evicted as part of the state
        // transition. Explicit flush here pins our draw to the current state.
        bufferSource.endBatch(portalType)
        graphics.pose().popPose()
    }
}
