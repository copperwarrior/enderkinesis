package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import org.joml.Matrix4f
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.CrownOfTheDarkStar

/** Render thread only: the tint wrappers and the billboard axes are reused scratch. */
object DarkStarCrownRenderer {

    private const val STAR_COUNT = 5
    private const val ORBIT_TICKS = 200L
    private const val ORBIT_RADIUS = 0.42f
    private const val HEIGHT_ABOVE_EYES = 0.4f
    private val STAR_SCALES = floatArrayOf(0.13f, 0.07f, 0.1f, 0.055f, 0.085f)
    private const val OUTLINE_WHITE = 255

    private const val FLASH_PERIOD_TICKS = 60L
    /** Part of the period that a flash takes to fade out. */
    private const val FLASH_FRACTION = 0.25f
    /** Unsynced offsets, so the beams do not flash all at once. */
    private val FLASH_OFFSETS = longArrayOf(0L, 23L, 41L, 11L, 52L)
    private const val BEAM_BASE_ALPHA = 0.12f
    private const val BEAM_CORE_HALF = 0.006f
    private const val BEAM_GLOW_HALF = 0.03f

    private val STARS: RenderType = darkStarType("dark_star_crown", RenderStateShard.TRANSLUCENT_TRANSPARENCY)
    private val BEAMS: RenderType = darkStarType("dark_star_crown_beams", RenderStateShard.LIGHTNING_TRANSPARENCY)

    private fun darkStarType(name: String, transparency: RenderStateShard.TransparencyStateShard): RenderType =
        RenderType.create(
            "${EnderkinesisMod.MOD_ID}_$name",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.TRIANGLES,
            256,
            false,
            false,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                .setTransparencyState(transparency)
                .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                .setCullState(RenderStateShard.NO_CULL)
                .createCompositeState(false)
        )

    private var modelTint: BlackTintVertexConsumer? = null
    private var layerTint: BlackTintBufferSource? = null
    private val right = Vector3f()
    private val up = Vector3f()
    private val view = Vector3f()
    private val side = Vector3f()
    private val starPos = FloatArray(STAR_COUNT * 3)

    @JvmStatic
    fun tintModel(entity: LivingEntity, consumer: VertexConsumer): VertexConsumer {
        if (consumer is BlackTintVertexConsumer || !CrownOfTheDarkStar.isWorn(entity)) return consumer
        val tint = modelTint ?: BlackTintVertexConsumer(consumer).also { modelTint = it }
        tint.delegate = consumer
        return tint
    }

    @JvmStatic
    fun tintLayers(entity: Entity, buffer: MultiBufferSource): MultiBufferSource {
        if (buffer is BlackTintBufferSource || !CrownOfTheDarkStar.isWorn(entity)) return buffer
        val tint = layerTint ?: BlackTintBufferSource(buffer).also { layerTint = it }
        tint.delegate = buffer
        return tint
    }

    /** [poseStack] is at the entity origin: LivingEntityRenderer has popped its model transforms. */
    @JvmStatic
    fun renderStars(entity: LivingEntity, partialTick: Float, poseStack: PoseStack, buffer: MultiBufferSource) {
        if (!CrownOfTheDarkStar.isWorn(entity)) return
        val camera = Minecraft.getInstance().entityRenderDispatcher.cameraOrientation()
        right.set(1f, 0f, 0f).rotate(camera)
        up.set(0f, 1f, 0f).rotate(camera)
        view.set(0f, 0f, 1f).rotate(camera)
        val matrix = poseStack.last().pose()
        val time = entity.level().gameTime
        val y = entity.eyeHeight + HEIGHT_ABOVE_EYES
        val turn = ((time % ORBIT_TICKS) + partialTick) / ORBIT_TICKS * 2.0 * PI
        for (i in 0 until STAR_COUNT) {
            val a = turn + i * 2.0 * PI / STAR_COUNT
            starPos[i * 3] = (cos(a) * ORBIT_RADIUS).toFloat()
            starPos[i * 3 + 1] = y
            starPos[i * 3 + 2] = (sin(a) * ORBIT_RADIUS).toFloat()
        }

        // Halos, then beams, then bodies: a later halo would darken the beams and the white outlines.
        val stars = buffer.getBuffer(STARS)
        for (i in 0 until STAR_COUNT) {
            DarkStarGlyph.emitHalo(stars, matrix, starPos[i * 3], starPos[i * 3 + 1], starPos[i * 3 + 2],
                right.x, right.y, right.z, up.x, up.y, up.z, STAR_SCALES[i])
        }
        val beams = buffer.getBuffer(BEAMS)
        for (i in 0 until STAR_COUNT) {
            emitBeam(beams, matrix, i, (i + 1) % STAR_COUNT, flashAlpha(time + FLASH_OFFSETS[i], partialTick))
        }
        val bodies = buffer.getBuffer(STARS)
        for (i in 0 until STAR_COUNT) {
            DarkStarGlyph.emitBody(bodies, matrix, starPos[i * 3], starPos[i * 3 + 1], starPos[i * 3 + 2],
                right.x, right.y, right.z, up.x, up.y, up.z, STAR_SCALES[i], OUTLINE_WHITE)
        }
    }

    /** Sharp flash at the start of each period, linear fade, then a dim rest. */
    private fun flashAlpha(time: Long, partialTick: Float): Int {
        val phase = ((time % FLASH_PERIOD_TICKS) + partialTick) / FLASH_PERIOD_TICKS
        val flash = if (phase < FLASH_FRACTION) 1f - phase / FLASH_FRACTION else 0f
        return ((BEAM_BASE_ALPHA + (1f - BEAM_BASE_ALPHA) * flash) * 255f).toInt()
    }

    /** Camera-facing ribbon: soft glow edges around a solid white core. */
    private fun emitBeam(out: VertexConsumer, matrix: Matrix4f, from: Int, to: Int, alpha: Int) {
        val ax = starPos[from * 3]; val ay = starPos[from * 3 + 1]; val az = starPos[from * 3 + 2]
        val bx = starPos[to * 3]; val by = starPos[to * 3 + 1]; val bz = starPos[to * 3 + 2]
        side.set(bx - ax, by - ay, bz - az).cross(view)
        if (side.lengthSquared() < 1e-8f) return
        side.normalize()
        emitBeamStrip(out, matrix, ax, ay, az, bx, by, bz, -BEAM_GLOW_HALF, -BEAM_CORE_HALF, 0, alpha)
        emitBeamStrip(out, matrix, ax, ay, az, bx, by, bz, -BEAM_CORE_HALF, BEAM_CORE_HALF, alpha, alpha)
        emitBeamStrip(out, matrix, ax, ay, az, bx, by, bz, BEAM_CORE_HALF, BEAM_GLOW_HALF, alpha, 0)
    }

    @Suppress("LongParameterList")
    private fun emitBeamStrip(
        out: VertexConsumer, matrix: Matrix4f,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        o0: Float, o1: Float, alpha0: Int, alpha1: Int,
    ) {
        val sx0 = side.x * o0; val sy0 = side.y * o0; val sz0 = side.z * o0
        val sx1 = side.x * o1; val sy1 = side.y * o1; val sz1 = side.z * o1
        out.vertex(matrix, ax + sx0, ay + sy0, az + sz0).color(255, 255, 255, alpha0).endVertex()
        out.vertex(matrix, bx + sx0, by + sy0, bz + sz0).color(255, 255, 255, alpha0).endVertex()
        out.vertex(matrix, bx + sx1, by + sy1, bz + sz1).color(255, 255, 255, alpha1).endVertex()
        out.vertex(matrix, ax + sx0, ay + sy0, az + sz0).color(255, 255, 255, alpha0).endVertex()
        out.vertex(matrix, bx + sx1, by + sy1, bz + sz1).color(255, 255, 255, alpha1).endVertex()
        out.vertex(matrix, ax + sx1, ay + sy1, az + sz1).color(255, 255, 255, alpha1).endVertex()
    }
}
