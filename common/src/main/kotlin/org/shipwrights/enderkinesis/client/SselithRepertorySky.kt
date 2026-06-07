package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import org.lwjgl.opengl.GL11
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.math.Axis
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.Random
import net.minecraft.client.Camera
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.dimension.SselithRepertory

/**
 * Custom sky for [org.shipwrights.enderkinesis.dimension.SselithRepertory] —
 * called from [org.shipwrights.enderkinesis.mixin.LevelRendererSselithRepertorySkyMixin]
 * in place of the vanilla End sky.
 *
 *  - **Background**: dark orange (clear-color fill)
 *  - **Sun**: 200×200 textured quad fixed at 34.5° pitch / 34.5° yaw / 34.5° roll, yellow tint
 *  - **Stars**: pre-baked field built with the same math beta/vanilla Minecraft uses in
 *    `LevelRenderer.drawStars` — random points on the unit sphere, projected to radius 100,
 *    each oriented as a billboard facing the player (azimuth + polar + per-star twinkle
 *    rotations bake the quad corners into world space at construction time). Beta's seed
 *    `10842L` is used directly, so the field is identical to vanilla's. A very slow global
 *    Y-axis rotation is the only animation, giving stars the *slight* drift the player
 *    expects without making the whole sky obviously spin — see [STAR_DRIFT_DEG_PER_TICK].
 *  - **Shooting stars**: occasional meteor streaks. Each one traces a great-circle arc on
 *    the star sphere, dragging a [SHOOTER_TRAIL_FRAMES]-frame fading trail behind a bright
 *    head. Spawning is Poisson-ish (random interval between [SHOOTER_SPAWN_MIN_TICKS] and
 *    [SHOOTER_SPAWN_MAX_TICKS]); each shooter lives [SHOOTER_LIFETIME_FRAMES] frames before
 *    despawning. Each spawn event has a [CLOUD_PROBABILITY] chance of being a *cloud* — a
 *    cluster of [CLOUD_MIN_COUNT]–[CLOUD_MAX_COUNT] shooters travelling together with
 *    shared heading and small per-member position/heading jitter. See [updateShooters] /
 *    [renderShooters] / [spawnCloud].
 */
object SselithRepertorySky {

    // ---- background ----

    // Upper-hemisphere ("space") colour — very dark so stars/sun read against it.
    private const val SKY_R = 0.06f
    private const val SKY_G = 0.02f
    private const val SKY_B = 0.005f

    // Horizon-fog tint — matches FogRendererSselithRepertoryMixin's override values, so
    // terrain fading into fog reads as the same warm yellow as the lower portion of the
    // sky. Tweak together with that mixin if changing the dimension's palette.
    private const val FOG_R = 1.00f
    private const val FOG_G = 0.88f
    private const val FOG_B = 0.40f

    /** Radius at which the horizon-fade dome sits — same sphere the stars/sun use, so the
     *  dome's horizon ring is in plane with the visible terrain horizon. */
    private const val HORIZON_DOME_R = 100f

    /** Sectors in the dome's triangle fan; 32 is plenty for a smooth circle at radius 100. */
    private const val HORIZON_DOME_SECTORS = 32

    // ---- sun ----

    /**
     * Sselith's sun disc — RGBA asset shipped under
     * `assets/enderkinesis/textures/sselith_sun.png`. Has a proper alpha channel
     * (transparent corners), so `defaultBlendFunc` cleanly draws the disc on top of the
     * stars and the corners contribute nothing. Vanilla's `textures/environment/sun.png` is
     * 8-bit RGB only (black background, no alpha) — that's why earlier attempts to use it
     * with anything other than additive blending produced the black-square artifact.
     */
    private val SUN_TEXTURE = ResourceLocation("enderkinesis", "textures/sselith_sun.png")

    /**
     * 1×1 opaque-white texture for the stars and shooting stars. Its ONLY purpose is to make
     * those quads textured: untextured (POSITION_COLOR) sky geometry routes to a shaderpack's
     * `gbuffers_skybasic`, which overwrites it with the procedural atmosphere, so the stars
     * vanish under shaders. Drawing them as POSITION_TEX_COLOR with a bound texture routes them
     * to `gbuffers_skytextured` instead — whose "custom sky" path (MC ≥ 1.13) preserves the
     * geometry — so the starfield survives. The single white texel multiplies the vertex colour
     * by 1.0, leaving the no-shader appearance identical. (See sselith-shaderpack-compat notes.)
     */
    private val STAR_TEXTURE = ResourceLocation("enderkinesis", "textures/sselith_star.png")

    /** Sun half-extent in sky-distance units. Vanilla sun is 30; this is "massive". */
    private const val SUN_HALF = 100f

    /** Distance from origin to the sun's quad plane. Vanilla draws the sun at +100 along the
     *  rotated "up" axis (Y); we follow the same convention so the quad ends up above the
     *  horizon after our XYZ rotations. Previously this was -100 which placed the quad below
     *  the player and made the sun invisible. */
    private const val SUN_Y = 100f

    private const val SUN_R = 1.0f
    private const val SUN_G = 0.92f
    private const val SUN_B = 0.45f

    /** Fixed rotation applied sequentially around X (pitch), Y (yaw), Z (roll). The directional
     *  light vector in [org.shipwrights.enderkinesis.client.SselithRepertoryLighting] is derived
     *  from this same angle, so changes here propagate to world lighting. */
    const val SUN_ROTATION_DEG = 34.5f

    // ---- stars ----

    /** Number of attempts at sampling star positions. Beta/vanilla use 1500. Some rejections
     *  happen below (vectors landing inside the inner radius are skipped) so the actual quad
     *  count is slightly lower. */
    private const val STAR_SAMPLE_BUDGET = 1500

    /** Global rotation rate of the starfield around the world Y axis, in degrees per
     *  game-tick. 0.004 deg/tick at 20 tps = 0.08 deg/s ≈ 75 minutes for a full sweep.
     *  Slow enough to read as a *slight* drift over a play session, not the conspicuous
     *  spin the previous 0.15 deg/tick produced. */
    private const val STAR_DRIFT_DEG_PER_TICK = 0.004f

    // Star tint: warm yellow to match the dimension's yellow-sun palette. Slightly paler
    // than the sun itself ([SUN_R]/[SUN_G]/[SUN_B] = 1.0/0.92/0.45) so stars don't read as
    // tiny suns — full red, near-full green, partial blue puts them at a soft starlight
    // yellow against the dark sky.
    private const val STAR_R = 1.00f
    private const val STAR_G = 0.95f
    private const val STAR_B = 0.70f

    /** Twinkle amplitude — alpha varies between `(1 - this)` and `1`, per star, on a
     *  phase-offset sine wave. Keep this small: the previous large value plus large stars
     *  read as "flickering", not twinkling. */
    private const val STAR_TWINKLE_AMP = 0.45f

    /** Twinkle speed in radians per game-tick. At 20 tps, 0.05 rad/tick → one cycle every
     *  ~6 seconds; combined with per-star phase offsets the field doesn't pulse in unison. */
    private const val STAR_TWINKLE_RATE = 0.05f

    /** Size band for each star, in sky-sphere units (sphere radius = 100). Vanilla is
     *  0.15–0.25; this is ~2× that — clearly individual points, but no longer the giant
     *  patches the previous 0.6–2.0 range produced. */
    private const val STAR_SIZE_MIN = 0.30f
    private const val STAR_SIZE_RANGE = 0.20f

    /** Radius of the star sphere — same convention as vanilla `LevelRenderer.drawStars`. */
    private const val STAR_SPHERE_R = 100.0f

    /** Beta/vanilla star RNG seed. Using the same constant means our field matches the
     *  pattern long-time Minecraft players recognise (beyond the cosmetic differences in
     *  size and tint). */
    private const val STAR_SEED = 10842L

    /** A single star's four pre-baked corners in world space, [x0,y0,z0, x1,y1,z1, …], plus
     *  a per-star [twinklePhase] used to offset the alpha sine wave so neighbours don't pulse
     *  in unison. The beta star math bakes the billboard orientation in at construction time
     *  so per-frame work is just the alpha computation and vertex emission. */
    private class StarQuad(val corners: FloatArray, val twinklePhase: Float)

    /** Lazily built once: beta seed + beta math → identical starfield every world load. */
    private val stars: Array<StarQuad> by lazy { buildStars() }

    /** Pre-baked star VertexBuffers, one per twinkle slot. Index =
     *  `(gameTick / STAR_TWINKLE_TICK_STEP) % STAR_TWINKLE_SLOTS`. Each
     *  buffer holds all star quads at that slot's per-star twinkle alpha,
     *  uploaded once and reused every frame without CPU rebuild. The array
     *  is null until the first entry into Sselith and freed on dimension
     *  leave to avoid holding ~4.5 MB of GPU memory in other dimensions. */
    private var starBuffers: Array<VertexBuffer?>? = null

    /** Number of ticks per twinkle slot. 4 ticks → 5 Hz updates on a
     *  6-second cycle; the alpha step per slot is ≤ 0.090 (23/255 levels),
     *  which is imperceptible for this twinkle rate. */
    private const val STAR_TWINKLE_TICK_STEP = 4

    /** Number of pre-baked slots. ceil(2π / RATE) / STEP ≈ 126 / 4 ≈ 32. */
    private const val STAR_TWINKLE_SLOTS = 32

    /**
     * Build the star quads using the same construction beta Minecraft used in
     * `LevelRenderer.drawStars` (carried forward into 1.20 with cosmetic-only changes).
     *
     * For each random unit-sphere point that survives the inner-radius rejection filter, the
     * quad is oriented so its normal points back at the player:
     *
     *  1. (azimuth, polar) extracted from the star's position give the rotation that maps
     *     the player's +Z view direction onto the star's radial direction.
     *  2. A per-star twinkle angle [t] rotates the quad inside its own plane, so neighbouring
     *     stars don't all line up with the same square orientation.
     *
     *  Corners (`d18`, `d19`) ∈ `(±size, ±size)` are first rotated by [t], then projected
     *  through the polar and azimuth rotations to land on the sphere of radius
     *  [STAR_SPHERE_R]. The output is 12 floats per star (x,y,z × 4 corners) added to the
     *  star at its world-space sphere point.
     */
    private fun buildStars(): Array<StarQuad> {
        val rng = Random(STAR_SEED)
        val built = ArrayList<StarQuad>(STAR_SAMPLE_BUDGET)
        for (i in 0 until STAR_SAMPLE_BUDGET) {
            val ax = rng.nextFloat() * 2.0f - 1.0f
            val ay = rng.nextFloat() * 2.0f - 1.0f
            val az = rng.nextFloat() * 2.0f - 1.0f
            val size = STAR_SIZE_MIN + rng.nextFloat() * STAR_SIZE_RANGE
            val sq = ax * ax + ay * ay + az * az
            // Beta filter: keep points inside the unit ball but not at the origin. Pushes
            // the angular density toward uniform on the sphere after normalisation.
            if (sq >= 1.0f || sq <= 0.01f) continue

            val invLen = 1.0f / sqrt(sq)
            val nx = ax * invLen
            val ny = ay * invLen
            val nz = az * invLen

            val cx = nx * STAR_SPHERE_R
            val cy = ny * STAR_SPHERE_R
            val cz = nz * STAR_SPHERE_R

            val azim = atan2(nx, nz)
            val sAz = sin(azim)
            val cAz = cos(azim)

            val polar = atan2(sqrt(nx * nx + nz * nz), ny)
            val sPo = sin(polar)
            val cPo = cos(polar)

            val twinkle = (rng.nextDouble() * Math.PI * 2.0).toFloat()
            val sTw = sin(twinkle)
            val cTw = cos(twinkle)

            val corners = FloatArray(12)
            for (j in 0 until 4) {
                // (corner.x, corner.y) ∈ (±size, ±size) — beta's bit pattern for the 4 corners.
                val q18 = ((j and 2) - 1).toFloat() * size
                val q19 = (((j + 1) and 2) - 1).toFloat() * size

                // 1) twinkle rotation in the quad's own 2D plane.
                val r21 = q18 * cTw - q19 * sTw
                val r22 = q19 * cTw + q18 * sTw

                // 2) polar rotation — tilt the quad off the equatorial plane to face the star.
                val r23 = r21 * sPo
                val r24 = -r21 * cPo

                // 3) azimuth rotation — spin the tilted quad around Y to the star's heading.
                val r25 = r24 * sAz - r22 * cAz
                val r27 = r22 * sAz + r24 * cAz

                corners[j * 3]     = cx + r25
                corners[j * 3 + 1] = cy + r23
                corners[j * 3 + 2] = cz + r27
            }
            // Per-star phase offset for the twinkle sine — pulled from the same RNG so the
            // field is fully deterministic.
            val twinklePhase = (rng.nextDouble() * Math.PI * 2.0).toFloat()
            built.add(StarQuad(corners, twinklePhase))
        }
        return built.toTypedArray()
    }

    // ---- entry ----

    fun renderSky(
        poseStack: PoseStack,
        projection: Matrix4f,
        partialTick: Float,
        camera: Camera,
        isFoggy: Boolean,
        setupFog: Runnable,
    ) {
        val gameTime = camera.entity.level().gameTime + partialTick

        // 1. Background — clear colour blends between fog yellow (at y=0, fog at full
        // strength) and dark sky (at |y|>=128, no fog). This is what makes the *entire*
        // sky respond to the height curve: previously only the horizon dome's alpha
        // shifted, but the clear colour underneath stayed fog yellow, so high-altitude
        // players still saw a yellow lower hemisphere. With the lerp the lower hemisphere
        // tracks the dome to a unified dark sky at altitude. Curve matches
        // SselithRepertory.fogDensityAt — see FogRendererSselithRepertoryMixin for the
        // matching terrain-fog alpha.
        val fogDensity = SselithRepertory.fogDensityAt(camera.position.y)
        val clearR = SKY_R + (FOG_R - SKY_R) * fogDensity
        val clearG = SKY_G + (FOG_G - SKY_G) * fogDensity
        val clearB = SKY_B + (FOG_B - SKY_B) * fogDensity
        RenderSystem.clearColor(clearR, clearG, clearB, 1.0f)
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX)

        // GL setup matching vanilla End-sky pattern (no depth writes during sky). Culling is
        // disabled across the whole sky pass — the player sits inside the sky sphere, so
        // both the star quads (built around outward-radial normals) and the sun quad (which
        // ends up normal-out after its XYZ rotation) present their *back* face to the camera.
        // With default back-face culling on, those faces get discarded and the sky looks
        // empty. Without this `disableCull` the previous attempt left stars invisible.
        RenderSystem.enableBlend()
        RenderSystem.depthMask(false)
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()

        renderHorizonFade(poseStack)
        renderStars(poseStack, gameTime)
        updateShooters(gameTime)
        renderShooters(poseStack, gameTime)
        renderSun(poseStack)

        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
        RenderSystem.disableBlend()
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
    }

    // ---- horizon fade ----

    /**
     * Draws the dark "space" cap on top of the (now-blended) sky background. A triangle
     * fan from the zenith down to a ring at horizon level: zenith vertex is fully opaque
     * dark sky, ring vertices are the same dark tint but alpha 0 so the clear colour
     * shows through.
     *
     * The height-driven *amount* of visible yellow at horizon is handled by lerping the
     * clear colour itself between [SKY_R/G/B] and [FOG_R/G/B] in [renderSky] above. We
     * don't also modulate ring alpha here — that would double-attenuate and break the
     * smooth blend at intermediate altitudes.
     *
     * The dome lives at the same radius as the star sphere ([HORIZON_DOME_R]); with
     * `depthMask` off and `disableCull`, draw order is what matters and the dome renders
     * before stars/sun so they're correctly drawn on top.
     */
    private fun renderHorizonFade(poseStack: PoseStack) {
        val matrix = poseStack.last().pose()

        RenderSystem.setShader { GameRenderer.getPositionColorShader() }

        val r = (SKY_R * 255f).toInt()
        val g = (SKY_G * 255f).toInt()
        val b = (SKY_B * 255f).toInt()

        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR)

        // Zenith — always fully opaque dark sky.
        builder.vertex(matrix, 0f, HORIZON_DOME_R, 0f).color(r, g, b, 255).endVertex()

        // Ring at horizon (y = 0) — dark tint, alpha 0 so the (height-blended) clear
        // colour shows through.
        for (i in 0..HORIZON_DOME_SECTORS) {
            val angle = (i.toFloat() / HORIZON_DOME_SECTORS) * Math.PI.toFloat() * 2f
            val x = cos(angle) * HORIZON_DOME_R
            val z = sin(angle) * HORIZON_DOME_R
            builder.vertex(matrix, x, 0f, z).color(r, g, b, 0).endVertex()
        }

        BufferUploader.drawWithShader(builder.end())
    }

    // ---- sun ----

    private fun renderSun(poseStack: PoseStack) {
        poseStack.pushPose()
        // 34.5° on pitch, yaw, roll — applied X, then Y, then Z. Gives an asymmetric
        // fixed orientation above the horizon.
        poseStack.mulPose(Axis.XP.rotationDegrees(SUN_ROTATION_DEG))
        poseStack.mulPose(Axis.YP.rotationDegrees(SUN_ROTATION_DEG))
        poseStack.mulPose(Axis.ZP.rotationDegrees(SUN_ROTATION_DEG))

        val matrix = poseStack.last().pose()

        // Standard alpha blend (SRC_ALPHA, ONE_MINUS_SRC_ALPHA) — the sun disc's opaque
        // centre replaces what's behind it (the previously-drawn stars + sky background),
        // while the disc's anti-aliased edge blends. This is what hides stars *behind* the
        // sun. The previous additive blend (SRC_ALPHA, ONE) added sun colour on top of the
        // stars in the disc area, leaving them clearly visible through the sun.
        //
        // The sun renders properly even with this blend — the earlier "sun has no alpha"
        // bug was caused by back-face culling discarding the quad, which is now disabled at
        // the outer renderSky level.
        RenderSystem.defaultBlendFunc()

        RenderSystem.setShader { GameRenderer.getPositionTexShader() }
        RenderSystem.setShaderTexture(0, SUN_TEXTURE)
        RenderSystem.setShaderColor(SUN_R, SUN_G, SUN_B, 1.0f)

        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX)
        builder.vertex(matrix, -SUN_HALF, SUN_Y, -SUN_HALF).uv(0f, 0f).endVertex()
        builder.vertex(matrix,  SUN_HALF, SUN_Y, -SUN_HALF).uv(1f, 0f).endVertex()
        builder.vertex(matrix,  SUN_HALF, SUN_Y,  SUN_HALF).uv(1f, 1f).endVertex()
        builder.vertex(matrix, -SUN_HALF, SUN_Y,  SUN_HALF).uv(0f, 1f).endVertex()
        BufferUploader.drawWithShader(builder.end())

        poseStack.popPose()
    }

    // ---- stars ----

    private fun renderStars(poseStack: PoseStack, gameTime: Float) {
        // Ensure all 32 slot buffers are built. Building happens lazily on
        // first entry into Sselith; subsequent frames just draw.
        val buffers = starBuffers ?: buildStarBuffers().also { starBuffers = it }

        // Pick the slot for the current game tick. Integer division floors
        // to the nearest 4-tick boundary, giving a stable slot for all
        // render frames within the same 4-tick window.
        val tick = gameTime.toLong()
        val slot = ((tick / STAR_TWINKLE_TICK_STEP) % STAR_TWINKLE_SLOTS).toInt()
        val buf = buffers[slot] ?: return

        poseStack.pushPose()
        val driftDeg = (gameTime * STAR_DRIFT_DEG_PER_TICK) % 360f
        poseStack.mulPose(Axis.YP.rotationDegrees(driftDeg))

        RenderSystem.setShader { GameRenderer.getPositionTexColorShader() }
        RenderSystem.setShaderTexture(0, STAR_TEXTURE)

        // Draw the pre-baked buffer with the current drift matrix. No per-star
        // CPU work; the tessellator is not touched.
        buf.bind()
        buf.drawWithShader(poseStack.last().pose(), RenderSystem.getProjectionMatrix(),
            GameRenderer.getPositionTexColorShader()!!)
        VertexBuffer.unbind()

        poseStack.popPose()
    }

    /** Build all [STAR_TWINKLE_SLOTS] VertexBuffers, one per 4-tick twinkle
     *  slot. Each is uploaded once and never re-touched until [freeStarBuffers]
     *  is called. Total GPU cost ≈ 32 × 144 KB ≈ 4.5 MB. */
    private fun buildStarBuffers(): Array<VertexBuffer?> {
        val starList = stars
        val rByte = (STAR_R * 255f).toInt()
        val gByte = (STAR_G * 255f).toInt()
        val bByte = (STAR_B * 255f).toInt()
        val result = arrayOfNulls<VertexBuffer>(STAR_TWINKLE_SLOTS)
        val tesselator = Tesselator.getInstance()
        for (slot in 0 until STAR_TWINKLE_SLOTS) {
            // Representative game tick for this slot — the midpoint of the
            // 4-tick window, so we sample the sine wave near the centre
            // rather than the leading edge.
            val tick = (slot * STAR_TWINKLE_TICK_STEP + STAR_TWINKLE_TICK_STEP / 2).toFloat()
            val builder = tesselator.builder
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
            for (star in starList) {
                val twinkle = 1.0f - STAR_TWINKLE_AMP *
                    (0.5f + 0.5f * sin(tick * STAR_TWINKLE_RATE + star.twinklePhase))
                val aByte = (twinkle * 255f).toInt().coerceIn(0, 255)
                val c = star.corners
                for (j in 0 until 4) {
                    builder.vertex(
                        c[j * 3].toDouble(), c[j * 3 + 1].toDouble(), c[j * 3 + 2].toDouble()
                    ).uv(0f, 0f).color(rByte, gByte, bByte, aByte).endVertex()
                }
            }
            val vb = VertexBuffer(VertexBuffer.Usage.STATIC)
            vb.bind()
            vb.upload(builder.end())
            VertexBuffer.unbind()
            result[slot] = vb
        }
        return result
    }

    /** Release all star VertexBuffers back to the GPU. Called when the player
     *  leaves Sselith so ~4.5 MB of GPU memory isn't held in other dimensions. */
    fun freeStarBuffers() {
        starBuffers?.forEach { it?.close() }
        starBuffers = null
    }

    // ---- shooting stars ----

    /** How many frames of trail are visible behind a shooter's head. Per the spec. */
    private const val SHOOTER_TRAIL_FRAMES = 10

    /** Total frames a shooter exists. Longer than [SHOOTER_TRAIL_FRAMES] so the trail forms,
     *  then the head continues past it for a stretch of "in-flight" before despawning. */
    private const val SHOOTER_LIFETIME_FRAMES = 32

    /** How many frames at the end of a shooter's life are used to fade the head's alpha to
     *  zero — prevents the streak from popping out of existence. */
    private const val SHOOTER_FADE_FRAMES = 6

    /** Angular speed along the great circle, in radians per game-tick. 0.035 rad/tick
     *  → ~2°/frame → ~20° trail length when fully formed, ~58° total path. */
    private const val SHOOTER_ANG_SPEED = 0.035f

    /** Half-width of the streak quad in sky-sphere units. Thin — the streak should read as
     *  a fine line, not a slab. Previously 0.45 read as a thick smear. */
    private const val SHOOTER_HALF_WIDTH = 0.18f

    /** Spawn interval bounds in game-ticks (20/sec). 20–90 = roughly one shooter per 1–4.5
     *  seconds, ~2.5 s average — frequent enough to feel like a meteor-active sky without
     *  becoming a continuous shower. */
    private const val SHOOTER_SPAWN_MIN_TICKS = 20f
    private const val SHOOTER_SPAWN_MAX_TICKS = 90f

    /** Chance that a spawn event produces a whole cloud of shooters travelling together
     *  rather than a lone streak. At 0.5 the sky alternates roughly evenly between solo
     *  meteors and short bursts. */
    private const val CLOUD_PROBABILITY = 0.5f

    /** Bounds on the number of shooters in a cloud spawn. Inclusive. */
    private const val CLOUD_MIN_COUNT = 4
    private const val CLOUD_MAX_COUNT = 8

    /** Positional jitter inside a cloud: how far each shooter's start direction is offset
     *  from the cloud's centre direction, along/across the heading. ~0.08 rad ≈ 4.6° on the
     *  unit sphere — enough spread to read as a swarm, tight enough to read as *one* cloud. */
    private const val CLOUD_START_JITTER = 0.08f

    /** Heading jitter inside a cloud, in radians. Smaller than positional jitter so the
     *  cluster moves *together*; if this were large the streaks would fan apart fast and
     *  the cloud would dissolve into a meteor shower radiant pattern. */
    private const val CLOUD_HEADING_JITTER = 0.045f

    /** Maximum stagger of the per-shooter spawn ticks inside a cloud. Even with zero stagger
     *  the streaks would form simultaneously; a few ticks of jitter makes the cloud arrive
     *  as a quick burst (lead streak first, stragglers trailing) instead of a perfect chord. */
    private const val CLOUD_TIME_JITTER_TICKS = 10f

    /**
     * Active shooting star. Position math runs on a great circle on the star sphere — the
     * head at time `t` from spawn is `start*cos(t*ω) + tangent*sin(t*ω)`, scaled to
     * [STAR_SPHERE_R]. The tail follows the same formula offset by [SHOOTER_TRAIL_FRAMES],
     * clamped at the spawn point so the trail grows during the first frames rather than
     * extending behind the spawn site.
     */
    private class Shooter(
        val sx: Float, val sy: Float, val sz: Float,        // start direction (unit vector)
        val tx: Float, val ty: Float, val tz: Float,        // tangent direction (unit vector)
        val spawnTick: Float,
    )

    private val shooters = ArrayList<Shooter>()
    private val shooterRng = Random()
    private var nextShooterSpawn = Float.NaN
    private var lastUpdateTick = Float.NaN

    /**
     * Advance the shooter list: spawn any new shooters whose scheduled tick has passed and
     * drop ones that have lived past [SHOOTER_LIFETIME_FRAMES]. Resets cleanly if game-time
     * jumps backwards (world reload, /time set) by detecting a non-monotonic gameTime.
     */
    private fun updateShooters(gameTime: Float) {
        if (nextShooterSpawn.isNaN() || gameTime < lastUpdateTick) {
            shooters.clear()
            nextShooterSpawn = gameTime + nextSpawnDelay()
        }
        lastUpdateTick = gameTime

        while (gameTime >= nextShooterSpawn) {
            spawnShooter(nextShooterSpawn)
            nextShooterSpawn += nextSpawnDelay()
        }

        // Drop expired shooters. Iterate by index from end so removal is O(1) per drop.
        var i = shooters.size - 1
        while (i >= 0) {
            if (gameTime - shooters[i].spawnTick >= SHOOTER_LIFETIME_FRAMES) {
                shooters.removeAt(i)
            }
            i--
        }
    }

    private fun nextSpawnDelay(): Float =
        SHOOTER_SPAWN_MIN_TICKS +
            shooterRng.nextFloat() * (SHOOTER_SPAWN_MAX_TICKS - SHOOTER_SPAWN_MIN_TICKS)

    /**
     * Dispatch a spawn event. With probability [CLOUD_PROBABILITY] this becomes a cloud of
     * [CLOUD_MIN_COUNT]–[CLOUD_MAX_COUNT] shooters travelling together; otherwise a single
     * shooter. The `nextSpawnDelay` cadence in [updateShooters] is unchanged, so a cloud
     * spawn doesn't push back the next event — clouds are just a burst on a regular schedule.
     */
    private fun spawnShooter(atTick: Float) {
        if (shooterRng.nextFloat() < CLOUD_PROBABILITY) {
            spawnCloud(atTick)
        } else {
            spawnSingle(atTick, atTick)
        }
    }

    /**
     * Add a single shooter with the given centre direction sampling. [spawnTick] is when
     * it becomes active in [renderShooters]; passing a value `> atTick` produces a "future
     * spawn" that renders nothing until then (used by cloud staggering).
     *
     * Returns true if the shooter was successfully added, false if the random helper happened
     * to align with the start direction (degenerate tangent).
     */
    private fun spawnSingle(atTick: Float, spawnTick: Float): Boolean {
        // Uniform random unit vector for the start direction. Inverse-CDF on cos(phi) keeps
        // density uniform over the sphere rather than clustered at the poles.
        val u = shooterRng.nextFloat() * 2.0f - 1.0f
        val theta = shooterRng.nextFloat() * (Math.PI.toFloat() * 2.0f)
        val rxy = sqrt(1.0f - u * u)
        val sx = rxy * cos(theta)
        val sy = u
        val sz = rxy * sin(theta)

        // Tangent direction: a random vector projected onto the tangent plane of the start
        // point, then normalised. Gives a uniform random heading along the great circle.
        var hx = shooterRng.nextFloat() - 0.5f
        var hy = shooterRng.nextFloat() - 0.5f
        var hz = shooterRng.nextFloat() - 0.5f
        val dot = hx * sx + hy * sy + hz * sz
        hx -= dot * sx
        hy -= dot * sy
        hz -= dot * sz
        val hLen = sqrt(hx * hx + hy * hy + hz * hz)
        if (hLen < 1e-4f) return false  // degenerate (helper aligned with start); skip
        hx /= hLen
        hy /= hLen
        hz /= hLen

        // Force a downward initial heading: if the tangent points upward (hy > 0), negate
        // it — the great circle is the same, just traversed in the opposite direction. After
        // this flip, d/dt(head.y) at t=0 is `hy * ω ≤ 0`, so every shooter visibly streaks
        // toward the horizon. Within the short [SHOOTER_LIFETIME_FRAMES] window the path
        // doesn't traverse far enough for the great circle to curve back upward.
        if (hy > 0f) {
            hx = -hx; hy = -hy; hz = -hz
        }

        shooters.add(Shooter(sx, sy, sz, hx, hy, hz, spawnTick))
        return true
    }

    /**
     * Spawn a cloud of shooters around a shared centre direction and centre heading. Each
     * cluster member is offset by a small jitter in both position (along/across the heading)
     * and heading angle, plus a stagger on its spawn tick. The visual result is a tight
     * "flock" of streaks crossing the same patch of sky on roughly parallel paths.
     */
    private fun spawnCloud(atTick: Float) {
        // Centre start direction (uniform on the sphere).
        val u = shooterRng.nextFloat() * 2.0f - 1.0f
        val theta = shooterRng.nextFloat() * (Math.PI.toFloat() * 2.0f)
        val rxy = sqrt(1.0f - u * u)
        val csx = rxy * cos(theta)
        val csy = u
        val csz = rxy * sin(theta)

        // Centre heading (random tangent to the centre start).
        var chx = shooterRng.nextFloat() - 0.5f
        var chy = shooterRng.nextFloat() - 0.5f
        var chz = shooterRng.nextFloat() - 0.5f
        val dot = chx * csx + chy * csy + chz * csz
        chx -= dot * csx
        chy -= dot * csy
        chz -= dot * csz
        val cLen = sqrt(chx * chx + chy * chy + chz * chz)
        if (cLen < 1e-4f) return  // degenerate, abort cloud entirely
        chx /= cLen
        chy /= cLen
        chz /= cLen

        // Force the cluster's centre heading downward. With the cluster's [CLOUD_HEADING_JITTER]
        // (~2.6°) much smaller than the available downward hemisphere, every member's
        // heading will still point downward after jitter + re-orthogonalisation; the safety
        // check inside the loop handles edge cases where the perturbed tangent happens to
        // cross the horizon.
        if (chy > 0f) {
            chx = -chx; chy = -chy; chz = -chz
        }

        // Side axis: perpendicular to both the centre start and the centre heading, in the
        // tangent plane of the centre. Combined with the heading axis these span the tangent
        // plane, giving us a 2-D local basis for the cluster spread.
        val pX = csy * chz - csz * chy
        val pY = csz * chx - csx * chz
        val pZ = csx * chy - csy * chx

        val count = CLOUD_MIN_COUNT + shooterRng.nextInt(CLOUD_MAX_COUNT - CLOUD_MIN_COUNT + 1)
        for (i in 0 until count) {
            // Offset start direction by a random vector in the (heading, side) tangent basis.
            // Renormalise back to the sphere so all cluster members share the radius-100 path.
            val offH = (shooterRng.nextFloat() - 0.5f) * 2f * CLOUD_START_JITTER
            val offP = (shooterRng.nextFloat() - 0.5f) * 2f * CLOUD_START_JITTER
            var sx = csx + offH * chx + offP * pX
            var sy = csy + offH * chy + offP * pY
            var sz = csz + offH * chz + offP * pZ
            val sLen = sqrt(sx * sx + sy * sy + sz * sz)
            sx /= sLen; sy /= sLen; sz /= sLen

            // Perturb the heading by mixing in a small side component, then re-orthogonalise
            // against the (perturbed) start direction so the tangent is well-defined and
            // unit-length for this cluster member.
            val headJit = (shooterRng.nextFloat() - 0.5f) * 2f * CLOUD_HEADING_JITTER
            var hx = chx + headJit * pX
            var hy = chy + headJit * pY
            var hz = chz + headJit * pZ
            val rd = hx * sx + hy * sy + hz * sz
            hx -= rd * sx; hy -= rd * sy; hz -= rd * sz
            val hLen = sqrt(hx * hx + hy * hy + hz * hz)
            if (hLen < 1e-4f) continue
            hx /= hLen; hy /= hLen; hz /= hLen

            // Safety net for the rare member whose jitter + re-orthogonalisation flipped its
            // heading above the horizon. Negate to keep the whole cloud streaking downward.
            if (hy > 0f) {
                hx = -hx; hy = -hy; hz = -hz
            }

            // Stagger spawn into the future so the cluster arrives as a quick burst rather
            // than as a perfect chord. `renderShooters` skips negative-age shooters.
            val spawnAt = atTick + shooterRng.nextFloat() * CLOUD_TIME_JITTER_TICKS
            shooters.add(Shooter(sx, sy, sz, hx, hy, hz, spawnAt))
        }
    }

    private fun renderShooters(poseStack: PoseStack, gameTime: Float) {
        if (shooters.isEmpty()) return

        poseStack.pushPose()
        // Same slow drift as the star field — shooters drift with the rest of the sky over
        // their short lifetime (~1.6 s), keeping the cosmos visually unified.
        val driftDeg = (gameTime * STAR_DRIFT_DEG_PER_TICK) % 360f
        poseStack.mulPose(Axis.YP.rotationDegrees(driftDeg))

        val matrix = poseStack.last().pose()

        // Textured (white) like the star field — routes to gbuffers_skytextured so shooters
        // survive under shaders. See STAR_TEXTURE / renderStars.
        RenderSystem.setShader { GameRenderer.getPositionTexColorShader() }
        RenderSystem.setShaderTexture(0, STAR_TEXTURE)

        val rByte = (STAR_R * 255f).toInt()
        val gByte = (STAR_G * 255f).toInt()
        val bByte = (STAR_B * 255f).toInt()

        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)

        for (s in shooters) {
            val age = gameTime - s.spawnTick
            // Cloud spawns stagger member shooters into the future, so a shooter may exist
            // in the list before its spawn tick. Skip it until then.
            if (age < 0f) continue
            // Tail trails the head by exactly [SHOOTER_TRAIL_FRAMES] frames once the streak
            // is fully formed. Clamp tail to age=0 during the first frames so the streak
            // *grows* from the spawn point rather than dragging a phantom trail backwards.
            val tailAge = maxOf(0f, age - SHOOTER_TRAIL_FRAMES)

            val headTheta = age * SHOOTER_ANG_SPEED
            val sH = sin(headTheta); val cH = cos(headTheta)
            val hx = (s.sx * cH + s.tx * sH) * STAR_SPHERE_R
            val hy = (s.sy * cH + s.ty * sH) * STAR_SPHERE_R
            val hz = (s.sz * cH + s.tz * sH) * STAR_SPHERE_R

            val tailTheta = tailAge * SHOOTER_ANG_SPEED
            val sT = sin(tailTheta); val cT = cos(tailTheta)
            val tx = (s.sx * cT + s.tx * sT) * STAR_SPHERE_R
            val ty = (s.sy * cT + s.ty * sT) * STAR_SPHERE_R
            val tz = (s.sz * cT + s.tz * sT) * STAR_SPHERE_R

            // Streak direction (head − tail). Skip degenerate (age 0, both at start).
            val dx = hx - tx
            val dy = hy - ty
            val dz = hz - tz
            val dLen = sqrt(dx * dx + dy * dy + dz * dz)
            if (dLen < 1e-3f) continue
            val dnx = dx / dLen; val dny = dy / dLen; val dnz = dz / dLen

            // Quad "side" axis: perpendicular to both the streak direction and the radial
            // direction at the head, so the slab lies in the tangent plane of the sphere and
            // is invariant to where the player is looking from inside.
            val rhx = hx / STAR_SPHERE_R; val rhy = hy / STAR_SPHERE_R; val rhz = hz / STAR_SPHERE_R
            var sX = dny * rhz - dnz * rhy
            var sY = dnz * rhx - dnx * rhz
            var sZ = dnx * rhy - dny * rhx
            val sLen = sqrt(sX * sX + sY * sY + sZ * sZ)
            if (sLen < 1e-4f) continue
            sX = sX / sLen * SHOOTER_HALF_WIDTH
            sY = sY / sLen * SHOOTER_HALF_WIDTH
            sZ = sZ / sLen * SHOOTER_HALF_WIDTH

            // Fade head alpha out over the last [SHOOTER_FADE_FRAMES] of the lifetime.
            val remaining = SHOOTER_LIFETIME_FRAMES - age
            val overallAlpha = (remaining / SHOOTER_FADE_FRAMES).coerceIn(0f, 1f)
            val headA = (overallAlpha * 255f).toInt().coerceIn(0, 255)
            // Tail is always alpha 0 — the trail fades to nothing along the streak length.

            builder.vertex(matrix, hx - sX, hy - sY, hz - sZ)
                .uv(0f, 0f).color(rByte, gByte, bByte, headA).endVertex()
            builder.vertex(matrix, hx + sX, hy + sY, hz + sZ)
                .uv(0f, 0f).color(rByte, gByte, bByte, headA).endVertex()
            builder.vertex(matrix, tx + sX, ty + sY, tz + sZ)
                .uv(0f, 0f).color(rByte, gByte, bByte, 0).endVertex()
            builder.vertex(matrix, tx - sX, ty - sY, tz - sZ)
                .uv(0f, 0f).color(rByte, gByte, bByte, 0).endVertex()
        }

        BufferUploader.drawWithShader(builder.end())

        poseStack.popPose()
    }
}
