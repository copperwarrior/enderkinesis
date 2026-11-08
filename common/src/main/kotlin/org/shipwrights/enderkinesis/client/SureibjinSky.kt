package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexBuffer
import com.mojang.blaze3d.vertex.VertexFormat
import dev.architectury.event.events.client.ClientReloadShadersEvent
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.material.FogType
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
 *   2. Horizon fog — band in the fog colour at the equator, drawn last so it covers all of the above.
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
    fun init() {
        ClientReloadShadersEvent.EVENT.register { provider, sink ->
            sink.registerShader(
                ShaderInstance(
                    namespacedShaderProvider(provider, EnderkinesisMod.MOD_ID),
                    "rendertype_sureibjin_sky",
                    DefaultVertexFormat.POSITION,
                )
            ) { instance -> skyShader = instance }
        }
    }



    // #1f272f — clear-fill mid tone. (The fragment shader hard-codes its own
    // palette — this constant just keeps the clear colour matched in case the
    // shader is null during reload.)
    private const val MID_R = 0.122f
    private const val MID_G = 0.153f
    private const val MID_B = 0.184f

    // #deeae9 — sun bloom.
    private const val SUN_R = 0.871f
    private const val SUN_G = 0.918f
    private const val SUN_B = 0.914f


    private const val SKY_R_DIST = SkySphereMesh.RADIUS

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
    /** Floats for each star: position, tangent, orbit normal, heading cosine and sine. */
    private const val STAR_STRIDE = 11
    private const val STAR_DIST = SKY_R_DIST - 2.0f
    /** Ticks for one full turn of a star along its great circle. */
    private const val STAR_ORBIT_TICKS = 24_000.0


    @JvmStatic
    fun renderSky(
        poseStack: PoseStack,
        @Suppress("UNUSED_PARAMETER") projection: Matrix4f,
        partialTick: Float,
        camera: Camera,
        @Suppress("UNUSED_PARAMETER") isFoggy: Boolean,
        setupFog: Runnable,
    ) {
        // A Float loses the partial tick above 2^23 ticks of world time.
        val gameTime = camera.entity.level().gameTime + partialTick.toDouble()

        // Run vanilla's sky-pass fog setup. Without this, our sea plane (and
        // anything else using a vanilla fog-aware shader) renders with
        // whatever fog uniforms were left over from the previous pass —
        // typically the terrain fog from the last frame. Calling setupFog
        // also triggers FogRendererSureibjinMixin's TAIL injection that
        // pushes the dim's MID-blue tint and tight distance fade.
        setupFog.run()

        // The sky shaders use no fog. Vanilla draws no sky with Blindness or Darkness, and the water
        // fog here is near black, so the frame keeps the fog-colour clear that vanilla made.
        if (camera.fluidInCamera != FogType.NONE || mobEffectBlocksSky(camera)) return

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
        renderStars(poseStack, gameTime)
        renderSunBloom(poseStack)
        renderHorizonFog(poseStack)

        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
        RenderSystem.disableBlend()
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
    }


    // Inside the stars and the sun. The sky pass has no depth, so the draw order makes the fog cover them.
    private const val HORIZON_FOG_DIST = SKY_R_DIST - 5.0f
    private const val HORIZON_FOG_TOP_Y = 14.0f
    private const val HORIZON_FOG_ROWS = 6
    private const val HORIZON_FOG_SECTORS = 32

    /** The terrain and the sea end in the fog colour. This band puts the same colour on the sky behind them. */
    private fun renderHorizonFog(poseStack: PoseStack) {
        val matrix = poseStack.last().pose()
        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR)
        for (s in 0 until HORIZON_FOG_SECTORS) {
            val th0 = (s.toDouble() / HORIZON_FOG_SECTORS) * Math.PI * 2.0
            val th1 = ((s + 1).toDouble() / HORIZON_FOG_SECTORS) * Math.PI * 2.0
            val x0 = (cos(th0) * HORIZON_FOG_DIST).toFloat()
            val z0 = (sin(th0) * HORIZON_FOG_DIST).toFloat()
            val x1 = (cos(th1) * HORIZON_FOG_DIST).toFloat()
            val z1 = (sin(th1) * HORIZON_FOG_DIST).toFloat()
            // Full fog from below the camera to the horizon.
            builder.vertex(matrix, x0, -SKY_R_DIST, z0).color(MID_R, MID_G, MID_B, 1.0f).endVertex()
            builder.vertex(matrix, x1, -SKY_R_DIST, z1).color(MID_R, MID_G, MID_B, 1.0f).endVertex()
            builder.vertex(matrix, x1, 0.0f, z1).color(MID_R, MID_G, MID_B, 1.0f).endVertex()
            builder.vertex(matrix, x0, 0.0f, z0).color(MID_R, MID_G, MID_B, 1.0f).endVertex()
            for (row in 0 until HORIZON_FOG_ROWS) {
                val t0 = row.toFloat() / HORIZON_FOG_ROWS
                val t1 = (row + 1).toFloat() / HORIZON_FOG_ROWS
                val a0 = (1.0f - t0) * (1.0f - t0)
                val a1 = (1.0f - t1) * (1.0f - t1)
                val y0 = t0 * HORIZON_FOG_TOP_Y
                val y1 = t1 * HORIZON_FOG_TOP_Y
                builder.vertex(matrix, x0, y0, z0).color(MID_R, MID_G, MID_B, a0).endVertex()
                builder.vertex(matrix, x1, y0, z1).color(MID_R, MID_G, MID_B, a0).endVertex()
                builder.vertex(matrix, x1, y1, z1).color(MID_R, MID_G, MID_B, a1).endVertex()
                builder.vertex(matrix, x0, y1, z0).color(MID_R, MID_G, MID_B, a1).endVertex()
            }
        }
        BufferUploader.drawWithShader(builder.end())
    }

    private fun mobEffectBlocksSky(camera: Camera): Boolean {
        val entity = camera.entity as? LivingEntity ?: return false
        return entity.hasEffect(MobEffects.BLINDNESS) || entity.hasEffect(MobEffects.DARKNESS)
    }

    private fun renderDreamSphere(poseStack: PoseStack, gameTime: Double) {
        val shader = skyShader ?: return   // pre-reload fallback: clear fill stands in

        // A shader Float loses precision at large values, so the time stays below
        // [SKY_TIME_PERIOD]. It runs up and then down: a wrap to zero makes the pattern jump.
        val phase = gameTime % (2.0 * SKY_TIME_PERIOD)
        val tBounded = (if (phase <= SKY_TIME_PERIOD) phase else 2.0 * SKY_TIME_PERIOD - phase).toFloat()
        shader.safeGetUniform("Time")?.set(tBounded)
        // The mesh holds world-aligned positions and the pose goes into ModelViewMat, so the
        // shader's world-space input needs no inverse.
        shader.safeGetUniform("InverseCamera")?.set(IDENTITY)
        // Sun direction in WORLD coords — the fragment shader uses this to
        // centre the nebula effect. Static (sun is fixed at east + slight
        // pitch) so we could hardcode it in GLSL, but a uniform keeps the
        // tuning hot-reloadable from Kotlin.
        val sunLen = kotlin.math.sqrt(SUN_DIST * SUN_DIST + SUN_Y * SUN_Y)
        shader.safeGetUniform("SunDir")?.set(SUN_DIST / sunLen, SUN_Y / sunLen, 0f)

        val mesh = SkySphereMesh.get()
        val modelView = Matrix4f(RenderSystem.getModelViewMatrix()).mul(poseStack.last().pose())
        mesh.bind()
        mesh.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), shader)
        VertexBuffer.unbind()
    }

    private val IDENTITY = Matrix4f()

    /** Upper limit of the shader `Time` uniform, in ticks (about 83 minutes). */
    private const val SKY_TIME_PERIOD = 100000.0



    private val starData: FloatArray by lazy { computeStarData() }

    private fun computeStarData(): FloatArray {
        val rng = java.util.Random(0xDEEAE9_BADC0DEL)
        // A second generator keeps the star positions of the first sequence unchanged.
        val headingRng = java.util.Random(0x5EA7_0F_D4EA35L)
        val data = FloatArray(STAR_COUNT * STAR_STRIDE)
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
            val heading = headingRng.nextFloat() * (Math.PI * 2.0).toFloat()
            val ch = cos(heading); val sh = sin(heading)
            val b = i * STAR_STRIDE
            data[b] = rx;   data[b+1] = ry; data[b+2] = rz
            // Direction of travel: a unit tangent at the start position.
            data[b+3] = ch * rightX + sh * upX
            data[b+4] = ch * rightY + sh * upY
            data[b+5] = ch * rightZ + sh * upZ
            // Orbit normal. It stays perpendicular to the star for the full orbit.
            data[b+6] = ch * upX - sh * rightX
            data[b+7] = ch * upY - sh * rightY
            data[b+8] = ch * upZ - sh * rightZ
            data[b+9] = ch; data[b+10] = sh
        }
        return data
    }

    private fun renderStars(poseStack: PoseStack, gameTime: Double) {
        val orbit = (gameTime % STAR_ORBIT_TICKS / STAR_ORBIT_TICKS * 2.0 * Math.PI).toFloat()
        val co = cos(orbit); val so = sin(orbit)
        val matrix = poseStack.last().pose()
        val data = starData
        val builder = Tesselator.getInstance().builder
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR)

        for (i in 0 until STAR_COUNT) {
            val b = i * STAR_STRIDE
            val pX = data[b];     val pY = data[b + 1]; val pZ = data[b + 2]
            val dX = data[b + 3]; val dY = data[b + 4]; val dZ = data[b + 5]
            val nX = data[b + 6]; val nY = data[b + 7]; val nZ = data[b + 8]
            val ch = data[b + 9]; val sh = data[b + 10]
            val cx = (co * pX + so * dX) * STAR_DIST
            val cy = (co * pY + so * dY) * STAR_DIST
            val cz = (co * pZ + so * dZ) * STAR_DIST
            // The tangent and the orbit normal carry the square along the path. A basis from
            // world up would spin the square near the zenith.
            val tX = co * dX - so * pX
            val tY = co * dY - so * pY
            val tZ = co * dZ - so * pZ
            val rX = ch * tX - sh * nX; val rY = ch * tY - sh * nY; val rZ = ch * tZ - sh * nZ
            val uX = sh * tX + ch * nX; val uY = sh * tY + ch * nY; val uZ = sh * tZ + ch * nZ

            DarkStarGlyph.emit(builder, matrix, cx, cy, cz, rX, rY, rZ, uX, uY, uZ, 1f)
        }

        BufferUploader.drawWithShader(builder.end())
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
