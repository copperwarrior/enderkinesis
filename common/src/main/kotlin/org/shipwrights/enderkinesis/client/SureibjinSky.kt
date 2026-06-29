package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientReloadShadersEvent
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceProvider
import org.joml.Matrix4f
import org.lwjgl.opengl.GL11
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Custom dream-sky for [org.shipwrights.enderkinesis.dimension.Sureibjin] —
 * called from `LevelRendererSureibjinSkyMixin` in place of the vanilla sky.
 *
 * **Composition** (drawn in order, alpha-blended):
 *
 *   1. Noise sphere at radius [SKY_R_DIST]. The fragment shader at
 *      `rendertype_sureibjin_sky.fsh` samples 2D value noise from each
 *      fragment's spherical direction and mixes a three-stop palette —
 *      per-pixel resolution, no visible mesh facets. Geometry is `POSITION`
 *      only; the shader does the colour.
 *   2. Horizon ring — wide band at the equator, multi-row alpha curve, BLACK.
 *   3. Stars — square BLACK cores with WHITE outlines, surrounded by an
 *      inverted (alpha-blended black) glow halo that darkens the sphere.
 *   4. East-horizon eye-shape glow.
 *   5. Sun bloom — soft radial bright spot (alpha gradient, not a hard disc).
 *
 * Vertex format is `POSITION_COLOR` for everything except the sphere
 * (`POSITION` only). [init] registers the custom shader with the
 * [ClientReloadShadersEvent] resource reload pipeline; until that's
 * complete, [skyShader] is null and the sphere falls back to a single-colour
 * clear pass.
 */
object SureibjinSky {


    private var skyShader: ShaderInstance? = null

    /** Called from `EnderkinesisModClient.initClient`. Registers the dream-sky
     *  noise shader (which also folds in the sun-nebula effect) with
     *  [ClientReloadShadersEvent] so it's reloaded on resource-pack swaps. */
    @JvmStatic
    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            sink.registerShader(
                ShaderInstance(
                    namespaced(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_sureibjin_sky",
                    DefaultVertexFormat.POSITION,
                )
            ) { instance -> skyShader = instance }
        }
    }

    private fun namespaced(delegate: ResourceProvider, namespace: String) =
        ResourceProvider { loc ->
            if (loc.namespace == ResourceLocation.DEFAULT_NAMESPACE) {
                val rewritten = ResourceLocation(namespace, loc.path)
                val attempt = delegate.getResource(rewritten)
                if (attempt.isPresent) attempt else delegate.getResource(loc)
            } else {
                delegate.getResource(loc)
            }
        }


    // #1f272f — clear-fill mid tone. (The fragment shader hard-codes its own
    // palette — this constant just keeps the clear colour matched in case the
    // shader is null during reload.)
    private const val MID_R = 0.122f
    private const val MID_G = 0.153f
    private const val MID_B = 0.184f

    // Star outline — muted grey, darker than white so it reads as a trim
    // rather than a bright bezel against the dim sky.
    private const val OUTLINE_R = 90
    private const val OUTLINE_G = 90
    private const val OUTLINE_B = 90

    // #deeae9 — sun bloom.
    private const val SUN_R = 0.871f
    private const val SUN_G = 0.918f
    private const val SUN_B = 0.914f


    private const val SKY_R_DIST = 100f
    private const val LATITUDE_RINGS = 32
    private const val LONGITUDE_SECTORS = 64

    // Sun: opaque bright disc with a thin gradient halo around it. The disc
    // is a hard-edged full circle ([SUN_DISC_RADIUS]); the halo is the
    // annular ring between [SUN_DISC_RADIUS] and [SUN_BLOOM_RADIUS],
    // alpha 255 → 0. [SUN_Y] lifts the sun + eye-shape glow above the horizon
    // line so the bloom isn't bisected by the horizon band.
    private const val SUN_DIST = SKY_R_DIST - 1.5f
    private const val SUN_Y = 21.3f
    private const val SUN_DISC_RADIUS = 6.5f
    private const val SUN_BLOOM_RADIUS = 9.0f
    private const val SUN_SECTORS = 48


    private const val STAR_COUNT = 140
    private const val STAR_DIST = SKY_R_DIST - 2.0f
    /** Half-edge of the inner black square. */
    private const val STAR_HALF = 0.55f
    /** Half-edge of the outer white square — outline is `OUTLINE_HALF − HALF`
     *  thick on each side. Kept tight so the white reads as a trim, not a
     *  border. */
    private const val STAR_OUTLINE_HALF = 0.65f
    /** Half-edge of the inverted-glow halo. */
    private const val HALO_HALF = 2.8f
    private const val HALO_ALPHA = 180

    private val HALO_PERIMETER_RU = floatArrayOf(
         1.000f,  0.000f,
         0.707f,  0.707f,
         0.000f,  1.000f,
        -0.707f,  0.707f,
        -1.000f,  0.000f,
        -0.707f, -0.707f,
         0.000f, -1.000f,
         0.707f, -0.707f,
    )

    private val STAR_CORNER_RU = floatArrayOf(
        -1f, +1f,
        +1f, +1f,
        +1f, -1f,
        -1f, -1f,
    )


    @JvmStatic
    fun renderSky(
        poseStack: PoseStack,
        @Suppress("UNUSED_PARAMETER") projection: Matrix4f,
        partialTick: Float,
        camera: Camera,
        @Suppress("UNUSED_PARAMETER") isFoggy: Boolean,
        setupFog: Runnable,
    ) {
        val gameTime = (camera.entity.level().gameTime + partialTick).toDouble()

        // Run vanilla's sky-pass fog setup. Without this, our sea plane (and
        // anything else using a vanilla fog-aware shader) renders with
        // whatever fog uniforms were left over from the previous pass —
        // typically the terrain fog from the last frame. Calling setupFog
        // also triggers FogRendererSureibjinMixin's TAIL injection that
        // pushes the dim's MID-blue tint and tight distance fade.
        setupFog.run()

        RenderSystem.clearColor(MID_R, MID_G, MID_B, 1.0f)
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX)

        RenderSystem.enableBlend()
        RenderSystem.depthMask(false)
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableCull()

        renderDreamSphere(poseStack, gameTime)

        // Switch to POSITION_COLOR for stars and sun. The sun nebula is now
        // computed inside the sky shader itself — see rendertype_sureibjin_sky.fsh.
        RenderSystem.setShader { GameRenderer.getPositionColorShader() }
        renderStars(poseStack)
        renderSunBloom(poseStack)

        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
        RenderSystem.disableBlend()
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
    }


    private fun renderDreamSphere(poseStack: PoseStack, gameTime: Double) {
        val shader = skyShader ?: return   // pre-reload fallback: clear fill stands in
        val matrix = poseStack.last().pose()

        // Wrap Time modulo a large bounded value so Float precision stays
        // tight even across long sessions. 100000 ticks ≈ 83 minutes.
        val tWrapped = (gameTime % 100000.0).toFloat()
        shader.safeGetUniform("Time")?.set(tWrapped)
        // Inverse of the host-side pose matrix: the vertex shader uses this
        // to undo the camera rotation embedded in the Position attribute so
        // the noise samples in WORLD space (sky stays fixed when the player
        // turns). Recomputed every frame because the camera rotates.
        val invCam = Matrix4f(matrix).invert()
        shader.safeGetUniform("InverseCamera")?.set(invCam)
        // Sun direction in WORLD coords — the fragment shader uses this to
        // centre the nebula effect. Static (sun is fixed at east + slight
        // pitch) so we could hardcode it in GLSL, but a uniform keeps the
        // tuning hot-reloadable from Kotlin.
        val sunLen = kotlin.math.sqrt(SUN_DIST * SUN_DIST + SUN_Y * SUN_Y)
        shader.safeGetUniform("SunDir")?.set(SUN_DIST / sunLen, SUN_Y / sunLen, 0f)

        RenderSystem.setShader { shader }

        // Precompute sin/cos rings.
        val sinPhi = FloatArray(LATITUDE_RINGS + 1)
        val cosPhi = FloatArray(LATITUDE_RINGS + 1)
        for (r in 0..LATITUDE_RINGS) {
            val phi = (Math.PI / 2.0) - (r.toDouble() / LATITUDE_RINGS) * Math.PI
            sinPhi[r] = sin(phi).toFloat()
            cosPhi[r] = cos(phi).toFloat()
        }
        val sinTh = FloatArray(LONGITUDE_SECTORS + 1)
        val cosTh = FloatArray(LONGITUDE_SECTORS + 1)
        for (s in 0..LONGITUDE_SECTORS) {
            val th = (s.toDouble() / LONGITUDE_SECTORS) * Math.PI * 2.0
            sinTh[s] = sin(th).toFloat()
            cosTh[s] = cos(th).toFloat()
        }

        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION)

        for (r in 0 until LATITUDE_RINGS) {
            val ySinT = sinPhi[r] * SKY_R_DIST
            val ySinB = sinPhi[r + 1] * SKY_R_DIST
            val cosT = cosPhi[r]
            val cosB = cosPhi[r + 1]
            for (s in 0 until LONGITUDE_SECTORS) {
                val xT0 = cosT * cosTh[s] * SKY_R_DIST
                val zT0 = cosT * sinTh[s] * SKY_R_DIST
                val xT1 = cosT * cosTh[s + 1] * SKY_R_DIST
                val zT1 = cosT * sinTh[s + 1] * SKY_R_DIST
                val xB0 = cosB * cosTh[s] * SKY_R_DIST
                val zB0 = cosB * sinTh[s] * SKY_R_DIST
                val xB1 = cosB * cosTh[s + 1] * SKY_R_DIST
                val zB1 = cosB * sinTh[s + 1] * SKY_R_DIST

                builder.vertex(matrix, xT0, ySinT, zT0).endVertex()
                builder.vertex(matrix, xB0, ySinB, zB0).endVertex()
                builder.vertex(matrix, xB1, ySinB, zB1).endVertex()

                builder.vertex(matrix, xT0, ySinT, zT0).endVertex()
                builder.vertex(matrix, xB1, ySinB, zB1).endVertex()
                builder.vertex(matrix, xT1, ySinT, zT1).endVertex()
            }
        }

        BufferUploader.drawWithShader(builder.end())
    }



    private val starData: FloatArray by lazy { computeStarData() }

    private fun computeStarData(): FloatArray {
        val rng = java.util.Random(0xDEEAE9_BADC0DEL)
        val data = FloatArray(STAR_COUNT * 9)
        for (i in 0 until STAR_COUNT) {
            val ry = rng.nextFloat() * 2f - 1f
            val phi = rng.nextFloat() * (Math.PI * 2.0).toFloat()
            val rad = sqrt(1f - ry * ry)
            val rx = rad * cos(phi)
            val rz = rad * sin(phi)
            val mag = sqrt(rx * rx + rz * rz)
            val rightX: Float; val rightY: Float; val rightZ: Float
            if (mag < 1e-4f) {
                rightX = 1f; rightY = 0f; rightZ = 0f
            } else {
                rightX = rz / mag; rightY = 0f; rightZ = -rx / mag
            }
            val upX = ry * rightZ - rz * rightY
            val upY = rz * rightX - rx * rightZ
            val upZ = rx * rightY - ry * rightX
            val b = i * 9
            data[b] = rx;   data[b+1] = ry; data[b+2] = rz
            data[b+3] = rightX; data[b+4] = rightY; data[b+5] = rightZ
            data[b+6] = upX;    data[b+7] = upY;    data[b+8] = upZ
        }
        return data
    }

    private fun renderStars(poseStack: PoseStack) {
        val matrix = poseStack.last().pose()
        val data = starData
        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR)

        for (i in 0 until STAR_COUNT) {
            val b = i * 9
            val cx = data[b]     * STAR_DIST
            val cy = data[b + 1] * STAR_DIST
            val cz = data[b + 2] * STAR_DIST
            val rX = data[b + 3]; val rY = data[b + 4]; val rZ = data[b + 5]
            val uX = data[b + 6]; val uY = data[b + 7]; val uZ = data[b + 8]

            // 1. Inverted black halo (no outline) — fan from black centre
            //    (alpha HALO_ALPHA) to black perimeter (alpha 0).
            for (k in 0 until 8) {
                val k0 = k * 2
                val k1 = ((k + 1) and 7) * 2
                val r0 = HALO_PERIMETER_RU[k0];     val u0 = HALO_PERIMETER_RU[k0 + 1]
                val r1 = HALO_PERIMETER_RU[k1];     val u1 = HALO_PERIMETER_RU[k1 + 1]
                val x0 = cx + (rX * r0 + uX * u0) * HALO_HALF
                val y0 = cy + (rY * r0 + uY * u0) * HALO_HALF
                val z0 = cz + (rZ * r0 + uZ * u0) * HALO_HALF
                val x1 = cx + (rX * r1 + uX * u1) * HALO_HALF
                val y1 = cy + (rY * r1 + uY * u1) * HALO_HALF
                val z1 = cz + (rZ * r1 + uZ * u1) * HALO_HALF
                builder.vertex(matrix, cx, cy, cz).color(0, 0, 0, HALO_ALPHA).endVertex()
                builder.vertex(matrix, x0, y0, z0).color(0, 0, 0, 0).endVertex()
                builder.vertex(matrix, x1, y1, z1).color(0, 0, 0, 0).endVertex()
            }

            // 2. Outline square — larger grey square drawn under the smaller
            //    black square. The visible difference at the perimeter becomes
            //    the outline.
            emitStarSquare(builder, matrix, cx, cy, cz, rX, rY, rZ, uX, uY, uZ,
                STAR_OUTLINE_HALF, OUTLINE_R, OUTLINE_G, OUTLINE_B)

            // 3. Black inner square on top of the white — leaves a thin white
            //    band around the star.
            emitStarSquare(builder, matrix, cx, cy, cz, rX, rY, rZ, uX, uY, uZ,
                STAR_HALF, 0, 0, 0)
        }

        BufferUploader.drawWithShader(builder.end())
    }

    private fun emitStarSquare(
        builder: com.mojang.blaze3d.vertex.BufferBuilder,
        matrix: Matrix4f,
        cx: Float, cy: Float, cz: Float,
        rX: Float, rY: Float, rZ: Float,
        uX: Float, uY: Float, uZ: Float,
        half: Float, cr: Int, cg: Int, cb: Int,
    ) {
        val cR0 = STAR_CORNER_RU[0]; val cU0 = STAR_CORNER_RU[1]
        val cR1 = STAR_CORNER_RU[2]; val cU1 = STAR_CORNER_RU[3]
        val cR2 = STAR_CORNER_RU[4]; val cU2 = STAR_CORNER_RU[5]
        val cR3 = STAR_CORNER_RU[6]; val cU3 = STAR_CORNER_RU[7]
        val xTL = cx + (rX * cR0 + uX * cU0) * half
        val yTL = cy + (rY * cR0 + uY * cU0) * half
        val zTL = cz + (rZ * cR0 + uZ * cU0) * half
        val xTR = cx + (rX * cR1 + uX * cU1) * half
        val yTR = cy + (rY * cR1 + uY * cU1) * half
        val zTR = cz + (rZ * cR1 + uZ * cU1) * half
        val xBR = cx + (rX * cR2 + uX * cU2) * half
        val yBR = cy + (rY * cR2 + uY * cU2) * half
        val zBR = cz + (rZ * cR2 + uZ * cU2) * half
        val xBL = cx + (rX * cR3 + uX * cU3) * half
        val yBL = cy + (rY * cR3 + uY * cU3) * half
        val zBL = cz + (rZ * cR3 + uZ * cU3) * half
        builder.vertex(matrix, xTL, yTL, zTL).color(cr, cg, cb, 255).endVertex()
        builder.vertex(matrix, xBL, yBL, zBL).color(cr, cg, cb, 255).endVertex()
        builder.vertex(matrix, xBR, yBR, zBR).color(cr, cg, cb, 255).endVertex()
        builder.vertex(matrix, xTL, yTL, zTL).color(cr, cg, cb, 255).endVertex()
        builder.vertex(matrix, xBR, yBR, zBR).color(cr, cg, cb, 255).endVertex()
        builder.vertex(matrix, xTR, yTR, zTR).color(cr, cg, cb, 255).endVertex()
    }


    /** Sun = solid bright disc + thin gradient halo ring around it. Two
     *  passes within the same buffer so the alpha boundary at the disc edge
     *  is crisp (255 either side of the boundary) and the halo fades smoothly
     *  outward over `SUN_BLOOM_RADIUS − SUN_DISC_RADIUS` blocks. */
    private fun renderSunBloom(poseStack: PoseStack) {
        val matrix = poseStack.last().pose()
        val r = (SUN_R * 255f).toInt()
        val g = (SUN_G * 255f).toInt()
        val b = (SUN_B * 255f).toInt()
        val discX = SUN_DIST + 0.05f
        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR)

        // 1. Solid disc — triangle fan, all vertices opaque.
        for (s in 0 until SUN_SECTORS) {
            val th0 = (s.toDouble() / SUN_SECTORS) * Math.PI * 2.0
            val th1 = ((s + 1).toDouble() / SUN_SECTORS) * Math.PI * 2.0
            val y0 = SUN_Y + (sin(th0) * SUN_DISC_RADIUS).toFloat()
            val z0 = (cos(th0) * SUN_DISC_RADIUS).toFloat()
            val y1 = SUN_Y + (sin(th1) * SUN_DISC_RADIUS).toFloat()
            val z1 = (cos(th1) * SUN_DISC_RADIUS).toFloat()
            builder.vertex(matrix, discX, SUN_Y, 0f).color(r, g, b, 255).endVertex()
            builder.vertex(matrix, discX, y0, z0).color(r, g, b, 255).endVertex()
            builder.vertex(matrix, discX, y1, z1).color(r, g, b, 255).endVertex()
        }

        // 2. Thin halo ring — inner edge at disc radius, alpha 255; outer edge
        //    at bloom radius, alpha 0. Two triangles per sector.
        for (s in 0 until SUN_SECTORS) {
            val th0 = (s.toDouble() / SUN_SECTORS) * Math.PI * 2.0
            val th1 = ((s + 1).toDouble() / SUN_SECTORS) * Math.PI * 2.0
            val iy0 = SUN_Y + (sin(th0) * SUN_DISC_RADIUS).toFloat()
            val iz0 = (cos(th0) * SUN_DISC_RADIUS).toFloat()
            val iy1 = SUN_Y + (sin(th1) * SUN_DISC_RADIUS).toFloat()
            val iz1 = (cos(th1) * SUN_DISC_RADIUS).toFloat()
            val oy0 = SUN_Y + (sin(th0) * SUN_BLOOM_RADIUS).toFloat()
            val oz0 = (cos(th0) * SUN_BLOOM_RADIUS).toFloat()
            val oy1 = SUN_Y + (sin(th1) * SUN_BLOOM_RADIUS).toFloat()
            val oz1 = (cos(th1) * SUN_BLOOM_RADIUS).toFloat()
            builder.vertex(matrix, discX, iy0, iz0).color(r, g, b, 255).endVertex()
            builder.vertex(matrix, discX, iy1, iz1).color(r, g, b, 255).endVertex()
            builder.vertex(matrix, discX, oy1, oz1).color(r, g, b, 0).endVertex()

            builder.vertex(matrix, discX, iy0, iz0).color(r, g, b, 255).endVertex()
            builder.vertex(matrix, discX, oy1, oz1).color(r, g, b, 0).endVertex()
            builder.vertex(matrix, discX, oy0, oz0).color(r, g, b, 0).endVertex()
        }

        BufferUploader.drawWithShader(builder.end())
    }
}
