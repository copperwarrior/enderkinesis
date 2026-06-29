package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.dimension.YgannAbyss
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The "Watching Eyes" sky element for [YgannAbyss]. Two distinct apparitions:
 *
 *  - **The Big Pair** — a single large pair, slow and prominent, very rare.
 *  - **The Cloud** — a brief swarm of small pairs (1–20 sets) clustered together, fired at
 *    the old 1 %-per-tick rate. Each cloud pair has its own size variation, lifetime, and
 *    fade timings so the swarm doesn't read as 20 identical clones.
 *
 *  Every pair, big or cloud, **oscillates its position slowly** — each is created with a
 *  random azimuth/elevation phase and the render path resolves the live direction every
 *  frame as `(baseAz + Asin(2π·t/T + φ_az), baseEl + Bsin(2π·t·k/T + φ_el))`. Periods are
 *  long (8 s) and amplitudes are a few degrees, so the eyes seem to *breathe* rather than
 *  swing around.
 *
 *  State is a [List] of pairs, culled by each pair's own `visibleTicks`. Per-tick we attempt
 *  both spawn rolls independently; a tick can fire neither, one, or both.
 */
object YgannAbyssWatchingEyes {

    private val TEXTURE: ResourceLocation = EnderkinesisMod.id("textures/environment/watching_eye.png")


    /** Very rare — averages roughly one big-pair appearance every ~100 s. */
    private const val BIG_PAIR_CHANCE_PER_TICK: Double = 0.0005

    private const val BIG_PAIR_VISIBLE_TICKS: Long = 180L      // 9 s — longer to accommodate the longer fade-in
    private const val BIG_PAIR_FADE_IN: Float = 80f            // ~4 s slow swell
    private const val BIG_PAIR_FADE_OUT: Float = 30f
    private const val BIG_PAIR_EYE_WIDTH: Float = 10f
    private const val BIG_PAIR_EYE_GAP: Float = 3f


    /** One cloud roll every 5 s on average — matches the original single-pair rate. */
    private const val CLOUD_CHANCE_PER_TICK: Double = 0.01

    /** Pair count per cloud, uniform on `[CLOUD_SIZE_MIN, CLOUD_SIZE_MAX]`. */
    private const val CLOUD_SIZE_MIN: Int = 1
    private const val CLOUD_SIZE_MAX: Int = 10

    /** Base width of a cloud pair — much smaller than the big pair. */
    private const val CLOUD_PAIR_EYE_WIDTH_BASE: Float = 1.0f
    private const val CLOUD_PAIR_EYE_GAP_BASE: Float = 0.3f

    /** Per-pair size multiplier range — gives a cloud some visual variety so the swarm
     *  doesn't read as identical clones. Width in `[base × 0.6, base × 1.4]`. */
    private const val CLOUD_PAIR_SIZE_FACTOR_MIN: Float = 0.6f
    private const val CLOUD_PAIR_SIZE_FACTOR_MAX: Float = 1.4f

    /** Per-pair lifetime range (ticks). Each cloud pair rolls its own — mixed-tempo swarm.
     *  Lengthened from the original `[25, 65]` to `[60, 150]` ticks (3–7.5 s) so individual
     *  pairs linger long enough to actually feel watched. */
    private const val CLOUD_PAIR_LIFETIME_MIN: Int = 60
    private const val CLOUD_PAIR_LIFETIME_MAX: Int = 150

    /** Fade-in / fade-out as fractions of the chosen lifetime. Fade-in pushed to *half* the
     *  lifetime so the swell is the dominant phase — the pair spends much longer drifting
     *  into visibility than holding or fading out, which reads as the watcher slowly
     *  noticing you rather than blinking on. Fade-out left at ~third-of-lifetime for the
     *  "linger, dissolve" tail. */
    private const val CLOUD_PAIR_FADE_IN_FRAC: Float = 0.50f
    private const val CLOUD_PAIR_FADE_OUT_FRAC: Float = 0.32f

    /** Angular spread of cloud pairs around the cloud's centre direction. A bit further than
     *  the previous 15° so individual pairs read as distinct rather than overlapping. */
    private const val CLOUD_SPREAD_DEG: Double = 25.0


    /** Position-oscillation period in ticks. Slowed from the original 160 (8 s) to 360
     *  ticks (18 s at 20 TPS) — the previous "breathing" cadence still felt like swinging
     *  on a fast pendulum; at 18 s per cycle the drift is barely perceptible per-frame and
     *  reads as the eyes subtly shifting their gaze rather than moving. */
    private const val OSCILLATION_PERIOD_TICKS: Float = 360f

    /** Oscillation amplitudes in degrees — small enough that the eyes stay roughly where
     *  they spawned, large enough to feel alive. */
    private const val OSC_AMP_AZIMUTH_DEG: Float = 3.0f
    private const val OSC_AMP_ELEVATION_DEG: Float = 1.5f

    /** Frequency ratio between elevation and azimuth oscillation. Non-integer keeps the
     *  motion path from looping cleanly — the eye traces a slow Lissajous figure. */
    private const val OSC_EL_FREQ_RATIO: Float = 1.3f


    private const val SKY_DISTANCE: Float = 100f
    private const val MIN_ELEVATION_DEG: Double = 10.0
    private const val MAX_ELEVATION_DEG: Double = 80.0

    /** Hard cap on simultaneously-visible pairs. A maxed cloud is 20; 40 leaves room for
     *  two overlapping clouds plus the rare big pair. */
    private const val MAX_CONCURRENT_PAIRS: Int = 40

    /** Peak alpha at the hold portion of every pair's fade curve. Capping below 1.0 makes
     *  the eyes feel ghostly / "behind the sky" rather than as solid as the texture's own
     *  rendered pixels — combined with the additive blend, this also keeps a busy cloud
     *  from washing the sky out to pure-white when lots of pairs overlap. */
    private const val MAX_ALPHA: Float = 0.55f

    /** One occurrence. Stores spherical coordinates (`baseAz/baseEl`) of where the eye
     *  spawned, plus an independent phase per axis so each pair oscillates on its own
     *  rhythm; the render path resolves the live direction every frame. Size + lifetime +
     *  fade are per-pair so the big and cloud apparitions share one render path. */
    private data class EyePair(
        val baseAzimuth: Double,
        val baseElevation: Double,
        val phaseAzimuth: Float,
        val phaseElevation: Float,
        val spawnTick: Long,
        val visibleTicks: Long,
        val fadeIn: Float,
        val fadeOut: Float,
        val eyeWidth: Float,
        val eyeGap: Float,
    )

    @Volatile private var pairs: List<EyePair> = emptyList()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level ->
            tick(level)
        })
    }

    private fun tick(level: ClientLevel) {
        if (level.dimension() != YgannAbyss.LEVEL_KEY) {
            if (pairs.isNotEmpty()) pairs = emptyList()
            return
        }
        val now = level.gameTime
        val rng = level.random
        var alive = pairs.filter { (now - it.spawnTick) < it.visibleTicks }

        if (alive.size < MAX_CONCURRENT_PAIRS && rng.nextDouble() < BIG_PAIR_CHANCE_PER_TICK) {
            alive = alive + spawnBigPair(rng, now)
        }
        if (alive.size < MAX_CONCURRENT_PAIRS && rng.nextDouble() < CLOUD_CHANCE_PER_TICK) {
            alive = alive + spawnCloud(rng, now, MAX_CONCURRENT_PAIRS - alive.size)
        }
        if (alive !== pairs) pairs = alive
    }

    private fun spawnBigPair(rng: RandomSource, now: Long): EyePair {
        val (az, el) = randomUpperHemisphereAzEl(rng)
        return EyePair(
            baseAzimuth = az,
            baseElevation = el,
            phaseAzimuth = (rng.nextFloat() * 2 * Math.PI).toFloat(),
            phaseElevation = (rng.nextFloat() * 2 * Math.PI).toFloat(),
            spawnTick = now,
            visibleTicks = BIG_PAIR_VISIBLE_TICKS,
            fadeIn = BIG_PAIR_FADE_IN,
            fadeOut = BIG_PAIR_FADE_OUT,
            eyeWidth = BIG_PAIR_EYE_WIDTH,
            eyeGap = BIG_PAIR_EYE_GAP,
        )
    }

    /** Spawn 1–[CLOUD_SIZE_MAX] pairs clustered around a shared centre direction, each
     *  with its own size variation, lifetime, fade timings, and oscillation phase. Capped
     *  at [available] slots so a near-full pair list keeps part of the cloud rather than
     *  dropping the whole thing. */
    private fun spawnCloud(rng: RandomSource, now: Long, available: Int): List<EyePair> {
        val requested = CLOUD_SIZE_MIN + rng.nextInt(CLOUD_SIZE_MAX - CLOUD_SIZE_MIN + 1)
        val count = requested.coerceAtMost(available)
        if (count <= 0) return emptyList()

        val centreAz = rng.nextDouble() * 2.0 * Math.PI
        val centreEl = Math.toRadians(
            MIN_ELEVATION_DEG + rng.nextDouble() * (MAX_ELEVATION_DEG - MIN_ELEVATION_DEG)
        )
        val spreadRad = Math.toRadians(CLOUD_SPREAD_DEG)
        val minElRad = Math.toRadians(MIN_ELEVATION_DEG)
        val maxElRad = Math.toRadians(MAX_ELEVATION_DEG)

        return List(count) {
            val az = centreAz + (rng.nextDouble() - 0.5) * 2.0 * spreadRad
            val el = (centreEl + (rng.nextDouble() - 0.5) * 2.0 * spreadRad)
                .coerceIn(minElRad, maxElRad)
            val sizeFactor = CLOUD_PAIR_SIZE_FACTOR_MIN +
                rng.nextFloat() * (CLOUD_PAIR_SIZE_FACTOR_MAX - CLOUD_PAIR_SIZE_FACTOR_MIN)
            val lifetime = CLOUD_PAIR_LIFETIME_MIN +
                rng.nextInt(CLOUD_PAIR_LIFETIME_MAX - CLOUD_PAIR_LIFETIME_MIN + 1)
            val lifetimeF = lifetime.toFloat()

            EyePair(
                baseAzimuth = az,
                baseElevation = el,
                phaseAzimuth = (rng.nextFloat() * 2 * Math.PI).toFloat(),
                phaseElevation = (rng.nextFloat() * 2 * Math.PI).toFloat(),
                spawnTick = now,
                visibleTicks = lifetime.toLong(),
                fadeIn = lifetimeF * CLOUD_PAIR_FADE_IN_FRAC,
                fadeOut = lifetimeF * CLOUD_PAIR_FADE_OUT_FRAC,
                eyeWidth = CLOUD_PAIR_EYE_WIDTH_BASE * sizeFactor,
                eyeGap = CLOUD_PAIR_EYE_GAP_BASE * sizeFactor,
            )
        }
    }

    private fun randomUpperHemisphereAzEl(rng: RandomSource): Pair<Double, Double> {
        val az = rng.nextDouble() * 2.0 * Math.PI
        val el = Math.toRadians(
            MIN_ELEVATION_DEG + rng.nextDouble() * (MAX_ELEVATION_DEG - MIN_ELEVATION_DEG)
        )
        return az to el
    }

    fun isShowing(level: ClientLevel): Boolean =
        pairs.isNotEmpty() && level.dimension() == YgannAbyss.LEVEL_KEY

    fun renderSky(poseStack: PoseStack, partialTick: Float) {
        val level = Minecraft.getInstance().level ?: return
        if (level.dimension() != YgannAbyss.LEVEL_KEY) return
        val activePairs = pairs
        if (activePairs.isEmpty()) return

        val nowFloat = (level.gameTime + partialTick.toDouble()).toFloat()
        val oscAmpAzRad = Math.toRadians(OSC_AMP_AZIMUTH_DEG.toDouble())
        val oscAmpElRad = Math.toRadians(OSC_AMP_ELEVATION_DEG.toDouble())
        val twoPiOverPeriod = (2.0 * Math.PI / OSCILLATION_PERIOD_TICKS).toFloat()

        RenderSystem.enableBlend()
        RenderSystem.blendFunc(
            com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE,
        )
        RenderSystem.depthMask(false)
        RenderSystem.disableDepthTest()
        RenderSystem.setShader { GameRenderer.getPositionColorTexShader() }
        RenderSystem.setShaderTexture(0, TEXTURE)

        val ts = Tesselator.getInstance()
        val bb = ts.builder
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX)

        val pose = poseStack.last().pose()
        for (pair in activePairs) {
            val age = nowFloat - pair.spawnTick
            val alpha = computeAlpha(age, pair.visibleTicks, pair.fadeIn, pair.fadeOut)
            if (alpha <= 0f) continue
            val dir = oscillatedDir(pair, nowFloat, twoPiOverPeriod, oscAmpAzRad, oscAmpElRad)
            drawPair(bb, pose, dir, alpha, pair.eyeWidth, pair.eyeGap)
        }

        ts.end()

        RenderSystem.enableDepthTest()
        RenderSystem.depthMask(true)
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableBlend()
    }

    /** Resolve the live direction for an eye pair at the current frame: take its base
     *  spherical coords and add a slow Lissajous-style oscillation in azimuth and
     *  elevation. Each pair has independent random phases per axis so the swarm doesn't
     *  move in lockstep. */
    private fun oscillatedDir(
        pair: EyePair, nowFloat: Float, twoPiOverPeriod: Float,
        oscAmpAzRad: Double, oscAmpElRad: Double,
    ): Vector3f {
        val azPhase = nowFloat * twoPiOverPeriod + pair.phaseAzimuth
        val elPhase = nowFloat * twoPiOverPeriod * OSC_EL_FREQ_RATIO + pair.phaseElevation
        val az = pair.baseAzimuth + oscAmpAzRad * sin(azPhase.toDouble())
        val el = (pair.baseElevation + oscAmpElRad * sin(elPhase.toDouble())).coerceIn(
            Math.toRadians(MIN_ELEVATION_DEG), Math.toRadians(MAX_ELEVATION_DEG),
        )
        val horiz = cos(el)
        return Vector3f(
            (horiz * sin(az)).toFloat(),
            sin(el).toFloat(),
            (horiz * cos(az)).toFloat(),
        )
    }

    private fun computeAlpha(t: Float, visibleTicks: Long, fadeIn: Float, fadeOut: Float): Float {
        if (t < 0f) return 0f
        if (t >= visibleTicks) return 0f
        // All branches are scaled by [MAX_ALPHA] — the curve still rises to its peak across
        // [fadeIn] ticks and decays across [fadeOut] ticks, the hold portion just sits at
        // MAX_ALPHA instead of 1.0.
        if (t < fadeIn) return MAX_ALPHA * (t / fadeIn)
        val fadeOutStart = visibleTicks - fadeOut
        if (t > fadeOutStart) return MAX_ALPHA * (1f - (t - fadeOutStart) / fadeOut)
        return MAX_ALPHA
    }

    private fun drawPair(
        bb: com.mojang.blaze3d.vertex.BufferBuilder,
        pose: org.joml.Matrix4f,
        direction: Vector3f,
        alpha: Float,
        eyeWidth: Float,
        eyeGap: Float,
    ) {
        val cx = direction.x * SKY_DISTANCE
        val cy = direction.y * SKY_DISTANCE
        val cz = direction.z * SKY_DISTANCE

        var rx = -direction.z
        var rz = direction.x
        val rLen = sqrt(rx * rx + rz * rz)
        if (rLen < 1.0e-3f) return
        rx /= rLen
        rz /= rLen

        val upx = -rz * direction.y
        val upy = rz * direction.x - rx * direction.z
        val upz = rx * direction.y

        val halfW = eyeWidth * 0.5f
        val halfH = eyeWidth / 6f                              // texture aspect 3:1
        val pairOffset = halfW + eyeGap * 0.5f

        val r = 1f; val g = 1f; val b = 1f

        for (sign in intArrayOf(-1, 1)) {
            val ecx = cx + sign * pairOffset * rx
            val ecy = cy
            val ecz = cz + sign * pairOffset * rz
            val rxw = rx * halfW; val rzw = rz * halfW
            val uxh = upx * halfH; val uyh = upy * halfH; val uzh = upz * halfH
            bb.vertex(pose, ecx - rxw - uxh, ecy - uyh, ecz - rzw - uzh).color(r, g, b, alpha).uv(0f, 1f).endVertex()
            bb.vertex(pose, ecx + rxw - uxh, ecy - uyh, ecz + rzw - uzh).color(r, g, b, alpha).uv(1f, 1f).endVertex()
            bb.vertex(pose, ecx + rxw + uxh, ecy + uyh, ecz + rzw + uzh).color(r, g, b, alpha).uv(1f, 0f).endVertex()
            bb.vertex(pose, ecx - rxw + uxh, ecy + uyh, ecz - rzw + uzh).color(r, g, b, alpha).uv(0f, 0f).endVertex()
        }
    }
}
