package org.shipwrights.enderkinesis.client

import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.EffectInstance
import org.shipwrights.enderkinesis.entity.Cataloger
import org.shipwrights.enderkinesis.util.Sga

/**
 * Local-only HUD overlay for tome summon: vignette + outward-drifting SGA glyphs +
 * screen-space refraction PostChain, all driven by a single envelope tied to the book's
 * open/close animation (not the full summon timeline).
 *
 * Vanilla only supports one PostChain at a time, so the refraction yields to scrying when
 * both fire. The render-pass split (vignette at GUI HEAD, glyphs at RETURN) matches
 * scrying — keeps glyphs above late vanilla composites and the vignette below chat/F3.
 */
object TomeSummonOverlay {

    @JvmStatic
    fun renderBackground(graphics: GuiGraphics, partialTick: Float) {
        val intensity = currentEnvelopeIntensity(partialTick)
        if (intensity <= 0.005f) return
        val w = graphics.guiWidth()
        val h = graphics.guiHeight()
        renderVignette(graphics, w, h, intensity)
    }

    @JvmStatic
    fun renderForeground(graphics: GuiGraphics, partialTick: Float) {
        val intensity = currentEnvelopeIntensity(partialTick)
        if (intensity <= 0.005f) return
        val w = graphics.guiWidth()
        val h = graphics.guiHeight()
        renderOutwardGlyphs(graphics, w, h, intensity)
    }

    /** Cached EffectInstance for the tome_summon_refract program. Lazily loaded
     *  on the first [renderRefraction] frame the envelope is non-zero. Survives
     *  across envelope-zero gaps so the GLSL compile doesn't repeat on every
     *  summon — actively closed only by [closeRefractionEffect] (resource
     *  reload, world leave). */
    @Volatile private var refractionEffect: EffectInstance? = null

    private fun ensureRefractionLoaded(mc: Minecraft): EffectInstance? {
        refractionEffect?.let { return it }
        return try {
            EffectInstance(mc.resourceManager, "tome_summon_refract").also { refractionEffect = it }
        } catch (t: Throwable) {
            LOG.warn("Failed to load tome-summon refraction shader program", t)
            null
        }
    }

    /** Release the cached EffectInstance. No callers yet — left here as the
     *  symmetric counterpart to [ensureRefractionLoaded] for future
     *  resource-reload or world-leave hooks. */
    @Suppress("unused")
    fun closeRefractionEffect() {
        refractionEffect?.close()
        refractionEffect = null
    }

    /** Run one refraction pass over the current main framebuffer.
     *
     *  Called from [org.shipwrights.enderkinesis.mixin.LevelRendererBubblePassMixin]
     *  at the TAIL of `LevelRenderer.renderLevel`. Skips entirely when the
     *  envelope is at zero, or when scrying is also active — the two effects
     *  used to share vanilla's single PostChain slot so a precedence rule was
     *  necessary; with the direct [RefractionPass] dispatch they could in
     *  principle compose, but visually they'd fight (both rewrite the
     *  background via the same fragment-shader pattern), so the same yield-to-
     *  scrying rule is retained. */
    @JvmStatic
    fun renderRefraction(partialTick: Float) {
        val mc = Minecraft.getInstance()
        if (mc.player == null) return
        if (ScryingClient.isActive()) return
        val intensity = currentEnvelopeIntensity(partialTick)
        if (intensity <= 0.005f) return
        val effect = ensureRefractionLoaded(mc) ?: return
        val tSec = (Util.getMillis() / 1000.0).toFloat()
        RefractionPass.run(effect) { eff ->
            eff.safeGetUniform("SessionTime").set(tSec)
            eff.safeGetUniform("Intensity").set(intensity)
        }
    }

    private val LOG: org.slf4j.Logger =
        org.slf4j.LoggerFactory.getLogger("EnderkinesisTomeSummonOverlay")

    /** Envelope tied to the BOOK animation, not the whole summon span:
     *   - **0** through the outbound flight (the book is closed, in
     *     transit — no effect yet).
     *   - **0 → 1** over the first [OPEN_RAMP_TICKS] of dwell — matches
     *     [CatalogerTomeRender.dwellOpenness], so the overlay winds up in
     *     lock-step with the pages opening.
     *   - **1** for the rest of dwell, including the matching
     *     book-closing ramp at the end (per "fades out *after* the book
     *     is taken or closed").
     *   - **1 → 0** over the inbound flight — in the return-to-shelf
     *     scenario the server keeps the summon state alive through
     *     inbound so this smoothstep plays out normally. In the **gift**
     *     scenario the server omits inbound entirely (totalTicks =
     *     OUTBOUND + DWELL) and removes the summon state at the moment
     *     the book is handed to the player; the [postEndFadeOut] branch
     *     below picks up from the last-seen intensity and plays the
     *     same-duration fade so the overlay never pops.
     *
     *  Reads the LOCAL player's active summon only — other players'
     *  summons drive the world-space book renderer but never the local
     *  HUD. */
    private fun currentEnvelopeIntensity(partialTick: Float): Float {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return 0f
        val state = PlayerTomeSummonClient.get(player)
        if (state == null) return postEndFadeOut(player, partialTick)

        val elapsed = (player.tickCount - state.startTick).toFloat() + partialTick
        val outboundEnd = Cataloger.TOME_OUTBOUND_TICKS.toFloat()
        val openRampEnd = outboundEnd + OPEN_RAMP_TICKS
        val dwellEnd = outboundEnd + Cataloger.TOME_DWELL_TICKS.toFloat()
        val inboundEnd = dwellEnd + Cataloger.TOME_INBOUND_TICKS.toFloat()
        val intensity = when {
            elapsed < outboundEnd -> 0f
            elapsed < openRampEnd -> smoothstep01(outboundEnd, openRampEnd, elapsed)
            elapsed < dwellEnd -> 1f
            elapsed < inboundEnd -> 1f - smoothstep01(dwellEnd, inboundEnd, elapsed)
            else -> 0f
        }
        // Capture each frame so the post-end fade can resume from this exact
        // value if the server clears state on the next frame (gift case).
        lastSeenIntensity = intensity
        lastSeenTickF = player.tickCount.toFloat() + partialTick
        return intensity
    }

    /** Smoothstep fade from [lastSeenIntensity] to zero over
     *  [POST_END_FADE_TICKS], driven by the local player's tick count
     *  past [lastSeenTickF]. Active only when the summon state vanished
     *  while the envelope was still above zero — i.e. the server pulled
     *  the rug out from under us, which is exactly what the gift path
     *  does on the dwell-end tick. */
    private fun postEndFadeOut(player: net.minecraft.client.player.LocalPlayer, partialTick: Float): Float {
        if (lastSeenIntensity <= 0f) return 0f
        val nowF = player.tickCount.toFloat() + partialTick
        val sinceEnd = nowF - lastSeenTickF
        if (sinceEnd <= 0f) return lastSeenIntensity
        if (sinceEnd >= POST_END_FADE_TICKS) {
            lastSeenIntensity = 0f
            return 0f
        }
        return lastSeenIntensity * (1f - smoothstep01(0f, POST_END_FADE_TICKS, sinceEnd))
    }

    @Volatile private var lastSeenIntensity: Float = 0f
    @Volatile private var lastSeenTickF: Float = 0f

    /** Mirror of [CatalogerTomeRender]'s `OPEN_RAMP_TICKS` (kept in sync
     *  here so the overlay's fade-in matches the page-opening animation
     *  tick-for-tick). */
    private const val OPEN_RAMP_TICKS: Float = 35f

    /** Duration of the gift-scenario post-end fade. Matches inbound length
     *  so a gift fades out over the same wall-clock window the return path
     *  takes during its (server-driven) inbound phase. */
    private val POST_END_FADE_TICKS: Float = Cataloger.TOME_INBOUND_TICKS.toFloat()

    private fun renderVignette(graphics: GuiGraphics, w: Int, h: Int, intensity: Float) {
        if (!TomeSummonRenderTypes.isReady()) return
        val shader = TomeSummonRenderTypes.getShader() ?: return

        shader.safeGetUniform("ScreenSize").set(w.toFloat(), h.toFloat())
        // Wall-clock drives the warble drift the same way scrying does so the
        // noise field looks identical — it's the Intensity envelope that makes
        // it "wind up" from still to fully warbling.
        shader.safeGetUniform("SessionTime").set((Util.getMillis() / 1000.0).toFloat())
        shader.safeGetUniform("Intensity").set(intensity)

        graphics.flush()
        graphics.pose().pushPose()
        graphics.pose().translate(0.0, 0.0, VIGNETTE_Z)

        val source = graphics.bufferSource()
        val consumer = source.getBuffer(TomeSummonRenderTypes.VIGNETTE)
        val matrix = graphics.pose().last().pose()
        val wf = w.toFloat()
        val hf = h.toFloat()
        consumer.vertex(matrix, 0f, 0f, 0f).endVertex()
        consumer.vertex(matrix, 0f, hf, 0f).endVertex()
        consumer.vertex(matrix, wf, hf, 0f).endVertex()
        consumer.vertex(matrix, wf, 0f, 0f).endVertex()
        source.endBatch(TomeSummonRenderTypes.VIGNETTE)

        graphics.pose().popPose()
    }

    private fun renderOutwardGlyphs(graphics: GuiGraphics, w: Int, h: Int, intensity: Float) {
        val font = Minecraft.getInstance().font
        val tSec = Util.getMillis() / 1000.0
        val cx = w * 0.5
        val cy = h * 0.5
        val baseRgb = GLYPH_COLOR_ARGB and 0x00FFFFFF

        graphics.pose().pushPose()
        graphics.pose().translate(0.0, 0.0, GLYPH_Z)

        for (i in 0 until GLYPH_COUNT) {
            // SplitMix64 finalizer — distinct seed from [ScryingClient]'s glyphs
            // so the two overlays don't share rune ordering when concurrent. The
            // naïve `(i * constant) xor seed` we had before had poor avalanche on
            // the top 4 bits for small `i`, so every glyph ended up landing on
            // the same side: visually all the runes drifted downward.
            val hash = glyphHash(i)
            val side = ((hash ushr 60) and 0x3L).toInt()
            val wordIdx = ((hash ushr 48) and 0xFFFL).toInt() % SSELITH_WORDS.size
            val edgeFrac = ((hash ushr 32) and 0xFFFFL).toInt() / 65536.0
            val lifeScale = 0.75 + ((hash ushr 16) and 0xFFL).toInt() / 255.0 * 0.5
            val lifeSec = GLYPH_LIFE_SECONDS * lifeScale
            val phaseSec = (hash and 0xFFFFL).toInt() / 65536.0 * lifeSec
            val cycle = ((tSec + phaseSec) % lifeSec) / lifeSec
            val progress = cycle.toFloat()

            val component = Sga.obfuscate(SSELITH_WORDS[wordIdx])
            val textW = font.width(component)

            // Per-glyph edge TARGET — same side-anchored math as scrying, but
            // here the glyph travels TO this point rather than FROM it.
            val edgeX: Double
            val edgeY: Double
            when (side) {
                0 -> { edgeX = edgeFrac * (w - textW); edgeY = -font.lineHeight.toDouble() }
                1 -> { edgeX = edgeFrac * (w - textW); edgeY = h.toDouble() }
                2 -> { edgeX = -textW.toDouble();      edgeY = edgeFrac * (h - font.lineHeight) }
                else -> { edgeX = w.toDouble();        edgeY = edgeFrac * (h - font.lineHeight) }
            }

            // Outward interpolation: 0 = at centre, 1 = at edge target.
            val travel = progress.toDouble()
            val x = (cx + (edgeX - cx) * travel).toInt()
            val y = (cy + (edgeY - cy) * travel).toInt()

            // Quick fade-in over the first 15% so the glyph isn't a hard pop at
            // centre, slow fade-out over the last 15% so it disappears just as
            // it lands at the edge. Multiplied by the envelope so the whole
            // field comes and goes with the vignette.
            val fadeIn = smoothstep01(0.00f, 0.15f, progress)
            val fadeOut = 1f - smoothstep01(0.85f, 1.00f, progress)
            val alphaF = fadeIn * fadeOut * intensity
            val alpha = (alphaF * 255f).toInt().coerceIn(0, 255)
            // Font.adjustColor() snaps alphas with top 6 bits zero (< 4) back
            // to fully opaque, so a low alpha would render at full brightness
            // — skip the draw entirely below that threshold.
            if (alpha < 4) continue
            val colorWithAlpha = (alpha shl 24) or baseRgb
            graphics.drawString(font, component, x, y, colorWithAlpha, false)
        }

        graphics.flush()
        graphics.pose().popPose()
    }

    private fun smoothstep01(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** SplitMix64 finalizer on the glyph index. Gives strong avalanche on
     *  every output bit so the (top-bit-derived) side / wordIdx / edgeFrac
     *  / lifeScale / phase values are independently and uniformly
     *  distributed across the small `i ∈ [0, GLYPH_COUNT)` we sample. */
    private fun glyphHash(i: Int): Long {
        var h = i.toLong() xor 0xA5A5A5A5A5A5A5A5UL.toLong()
        h = (h xor (h ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        h = (h xor (h ushr 27)) * 0x94D049BB133111EBUL.toLong()
        return h xor (h ushr 31)
    }

    /** Same curated Sselith vocabulary as [ScryingClient] — short-to-medium
     *  words so each render fits as a tight rune cluster. */
    private val SSELITH_WORDS: Array<String> = arrayOf(
        "aevkh", "aevkhegh", "aevocht", "aevust", "akh", "akkumulaskhun",
        "ambiensth", "amielegh", "amielkarn", "ankessthri", "ankhelfish",
        "apprentikh", "arbalistkh", "armkhvel", "augukh", "autoskave",
        "avakenelk", "azhtek", "banankh", "barokvekh", "ontnemkh",
        "kwevkust", "kwevegh", "morontkr", "ontpartkhust", "yghann",
        "yghannmornkt", "zhe", "kre", "vel", "mor", "ont", "toch",
        "vraestmorocht", "skarn", "schest", "moroch", "kelkargh",
        "elksvel", "thauntakh", "morakht", "vraestakh", "kwevakht",
    )

    /** Bright warm yellow for the SGA runes — #E8C84A, matching scrying. */
    private val GLYPH_COLOR_ARGB: Int = 0xFFE8C84A.toInt()

    /** Z translation for the vignette quad. Negative (deeper than vanilla
     *  HUD elements at z=0) so the hotbar, hearts, hunger, etc. composite ON
     *  TOP of the brown wall during a summon — the overlay doesn't suppress
     *  the HUD the way scrying does, and the tome summon HUD-hide trick
     *  isn't wanted here. Matches the convention vanilla `Gui.renderVignette`
     *  uses for its own under-HUD overlay (z=-90). */
    private const val VIGNETTE_Z: Double = -90.0

    /** Z for the SGA glyph pass. Sits between the vignette and the HUD so
     *  glyphs render above the brown wall but are still hidden by hotbar /
     *  inventory backgrounds where the player is reading the HUD. */
    private const val GLYPH_Z: Double = -50.0

    /** Number of glyphs alive on screen at any moment. */
    private const val GLYPH_COUNT: Int = 20

    /** Per-rune lifetime in seconds (jittered ±25% per glyph). */
    private const val GLYPH_LIFE_SECONDS: Double = 7.0
}
