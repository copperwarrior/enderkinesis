package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import org.joml.Quaternionf
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.SpellVisuals
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Spell effects drawn on living entities, after the entity itself, in its own space (origin at
 * its feet). Uses vanilla textures and the Staff of Aegis's glow style:
 *
 * - **Entangled:** root cubes creeping over the whole body, shifting as they grow, and vine
 *   sprites swaying from it.
 * - **Frozen:** jagged ice cubes, each turned its own way, studding the body. They turn with
 *   the body's tumble, as the model does.
 * - **Crystallized:** small glowing cubes studding the body, swelling as the effect runs.
 * - **Weighted:** dark rings sinking around the entity, dragging it down.
 *
 * Each render type is drawn in one pass: an immediate buffer source reuses one builder per
 * type.
 */
object SpellLayers {
    private val ICE = RenderType.entityTranslucent(ResourceLocation("textures/block/ice.png"))
    private val ROOTS = RenderType.entityCutoutNoCull(ResourceLocation("textures/block/mangrove_roots_side.png"))
    private val VINES = RenderType.entityCutoutNoCull(ResourceLocation("textures/block/vine.png"))

    /** Vanilla's foliage green, for the untinted vine texture. */
    private const val VINE_TINT = 0xFF48B518.toInt()
    private const val ROOT_CUBES = 18
    private const val VINE_SPRITES = 5
    private const val ICE_CUBES = 12
    private const val CRYSTAL_COUNT = 7
    private const val WEIGHT_RINGS = 3

    @JvmStatic
    fun render(entity: LivingEntity, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        val bits = SpellNetworkClient.visuals(entity.id)
        if (bits == 0) return
        val w = entity.bbWidth / 2f
        val h = entity.bbHeight
        val time = entity.tickCount + partialTick
        pose.pushPose()
        SpellVisuals.clientPose(entity.id, partialTick)?.let { tumble(pose, it, h) }
        if (bits and SpellVisuals.ENTANGLED != 0) {
            roots(pose, buffers.getBuffer(ROOTS), entity, w, h, time, light)
            vines(pose, buffers.getBuffer(VINES), entity, w, h, time, light)
        }
        if (bits and SpellVisuals.FROZEN != 0) ice(pose, buffers.getBuffer(ICE), entity, w, h, light)
        if (bits and SpellVisuals.CRYSTALLIZED != 0) crystals(pose, buffers, entity, w, h, time)
        pose.popPose()
        if (bits and SpellVisuals.WEIGHTED != 0) weight(pose, buffers, w, h, time)
    }

    private fun tumble(pose: PoseStack, rotation: Quaternionf, h: Float) {
        pose.translate(0f, h / 2f, 0f)
        pose.mulPose(rotation)
        pose.translate(0f, -h / 2f, 0f)
    }

    /** A point on the hitbox's side walls: [around] runs 0..1 once round the perimeter. */
    private fun perimeter(around: Float, w: Float): Pair<Float, Float> {
        val u = ((around % 1f) + 1f) % 1f * 4f
        val side = u.toInt().coerceIn(0, 3)
        val along = (u - side) * 2f - 1f
        return when (side) {
            0 -> w to along * w
            1 -> -along * w to w
            2 -> -w to -along * w
            else -> along * w to -w
        }
    }

    // ---- Entangled --------------------------------------------------------------------------

    private fun roots(pose: PoseStack, c: VertexConsumer, entity: LivingEntity, w: Float, h: Float, time: Float, light: Int) {
        val random = Random(entity.id)
        repeat(ROOT_CUBES) {
            val base = random.nextFloat()
            val climb = 0.004f + 0.006f * random.nextFloat()
            val drift = (random.nextFloat() - 0.5f) * 0.01f
            // Creep upward and around the body, wrapping back to the feet.
            val y = ((base + time * climb) % 1f) * h
            val (x, z) = perimeter(base * 3.1f + time * drift, w + 0.02f)
            val s = (0.06f + 0.06f * random.nextFloat()) * (0.85f + 0.15f * sin(time * 0.2f + base * 9f))
            pose.pushPose()
            pose.translate(x, y, z)
            pose.mulPose(Axis.YP.rotationDegrees(base * 360f + time * 2f))
            pose.mulPose(Axis.XP.rotationDegrees(base * 90f))
            cube(c, pose, s, 0xFFFFFFFF.toInt(), light)
            pose.popPose()
        }
    }

    private fun vines(pose: PoseStack, c: VertexConsumer, entity: LivingEntity, w: Float, h: Float, time: Float, light: Int) {
        val random = Random(entity.id * 7 + 3)
        repeat(VINE_SPRITES) {
            val around = random.nextFloat()
            val (x, z) = perimeter(around, w + 0.05f)
            val y = h * (0.15f + 0.6f * random.nextFloat())
            val size = 0.35f + 0.25f * random.nextFloat()
            pose.pushPose()
            pose.translate(x, y, z)
            pose.mulPose(Axis.ZP.rotationDegrees(8f * sin(time * 0.1f + around * 20f)))
            // Two quads crossed at right angles, as vanilla draws plants.
            for (turn in 0..1) {
                pose.pushPose()
                pose.mulPose(Axis.YP.rotationDegrees(45f + turn * 90f + around * 360f))
                plane(c, pose, size, VINE_TINT, light)
                pose.popPose()
            }
            pose.popPose()
        }
    }

    // ---- Frozen -----------------------------------------------------------------------------

    private fun ice(pose: PoseStack, c: VertexConsumer, entity: LivingEntity, w: Float, h: Float, light: Int) {
        val random = Random(entity.id * 13 + 5)
        repeat(ICE_CUBES) {
            val (x, z) = perimeter(random.nextFloat(), w * 0.95f)
            val y = h * (0.05f + 0.9f * random.nextFloat())
            val s = 0.09f + 0.09f * random.nextFloat()
            pose.pushPose()
            pose.translate(x, y, z)
            pose.mulPose(Quaternionf().rotateXYZ(random.nextFloat() * 6.28f, random.nextFloat() * 6.28f, random.nextFloat() * 6.28f))
            cube(c, pose, s, 0xD8FFFFFF.toInt(), light)
            pose.popPose()
        }
    }

    // ---- Crystallized -----------------------------------------------------------------------

    private fun crystals(pose: PoseStack, buffers: MultiBufferSource, entity: LivingEntity, w: Float, h: Float, time: Float) {
        val random = Random(entity.id)
        val pulse = 0.85f + 0.15f * Mth.sin(time * 0.15f)
        val cubes = List(CRYSTAL_COUNT) {
            val (x, z) = perimeter(random.nextFloat(), w)
            floatArrayOf(x, h * (0.2f + 0.7f * random.nextFloat()), z, (0.08f + 0.08f * random.nextFloat()) * pulse)
        }
        val (r, g, b) = rgb(Element.PURITY.color)
        val matrix = pose.last().pose()
        val glow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        for (cube in cubes) glowCube(glow, matrix, cube, r, g, b)
        val lines = buffers.getBuffer(RenderType.lines())
        for (cube in cubes) wireCube(lines, matrix, cube, r, g, b)
    }

    // ---- Weighted ---------------------------------------------------------------------------

    private fun weight(pose: PoseStack, buffers: MultiBufferSource, w: Float, h: Float, time: Float) {
        val matrix = pose.last().pose()
        val (r, g, b) = rgb(Element.GRAVITY.color)
        val shade = buffers.getBuffer(SpellVfxRenderTypes.SHADE)
        val rings = List(WEIGHT_RINGS) { i ->
            // Each ring sinks from the head to the feet, tightening as it goes, then starts again.
            val u = ((time * 0.04f + i.toFloat() / WEIGHT_RINGS) % 1f)
            floatArrayOf(h * (1.05f - u), (w + 0.35f) * (1.1f - 0.4f * u), (1f - u) * u * 4f)
        }
        for ((y, radius, a) in rings.map { it.toList() }) band(shade, matrix, y, radius, 0.12f, 30, 22, 40, (a * 150).toInt())
        val glow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        for ((y, radius, a) in rings.map { it.toList() }) band(glow, matrix, y, radius + 0.06f, 0.035f, r, g, b, (a * 200).toInt())
    }

    /** A flat ring at height [y], [radius] out, [width] wide, fading at both edges. */
    private fun band(c: VertexConsumer, m: org.joml.Matrix4f, y: Float, radius: Float, width: Float, r: Int, g: Int, b: Int, alpha: Int) {
        if (alpha <= 0) return
        val segments = 28
        for (k in 0 until segments) {
            val a0 = 2f * Math.PI.toFloat() * k / segments
            val a1 = 2f * Math.PI.toFloat() * (k + 1) / segments
            for ((inner, outer, aIn, aOut) in listOf(
                listOf(radius - width, radius, 0f, 1f), listOf(radius, radius + width, 1f, 0f),
            )) {
                c.vertex(m, cos(a0) * inner, y, sin(a0) * inner).color(r, g, b, (alpha * aIn).toInt()).endVertex()
                c.vertex(m, cos(a1) * inner, y, sin(a1) * inner).color(r, g, b, (alpha * aIn).toInt()).endVertex()
                c.vertex(m, cos(a1) * outer, y, sin(a1) * outer).color(r, g, b, (alpha * aOut).toInt()).endVertex()
                c.vertex(m, cos(a0) * outer, y, sin(a0) * outer).color(r, g, b, (alpha * aOut).toInt()).endVertex()
            }
        }
    }

    // ---- Geometry ---------------------------------------------------------------------------

    /** A textured cube of half-size [s] at the pose's origin, UVs 0..1 per face. */
    private fun cube(c: VertexConsumer, pose: PoseStack, s: Float, argb: Int, light: Int) {
        val m = pose.last().pose(); val n = pose.last().normal()
        fun face(nx: Float, ny: Float, nz: Float, v: Array<FloatArray>) {
            val uv = arrayOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 0f))
            for (i in 0 until 4) {
                c.vertex(m, v[i][0], v[i][1], v[i][2])
                    .color((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, (argb ushr 24) and 0xFF)
                    .uv(uv[i][0], uv[i][1]).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, nx, ny, nz).endVertex()
            }
        }
        val a = -s; val b = s
        face(0f, 0f, -1f, arrayOf(floatArrayOf(b, a, a), floatArrayOf(a, a, a), floatArrayOf(a, b, a), floatArrayOf(b, b, a)))
        face(0f, 0f, 1f, arrayOf(floatArrayOf(a, a, b), floatArrayOf(b, a, b), floatArrayOf(b, b, b), floatArrayOf(a, b, b)))
        face(-1f, 0f, 0f, arrayOf(floatArrayOf(a, a, a), floatArrayOf(a, a, b), floatArrayOf(a, b, b), floatArrayOf(a, b, a)))
        face(1f, 0f, 0f, arrayOf(floatArrayOf(b, a, b), floatArrayOf(b, a, a), floatArrayOf(b, b, a), floatArrayOf(b, b, b)))
        face(0f, 1f, 0f, arrayOf(floatArrayOf(a, b, b), floatArrayOf(b, b, b), floatArrayOf(b, b, a), floatArrayOf(a, b, a)))
        face(0f, -1f, 0f, arrayOf(floatArrayOf(a, a, a), floatArrayOf(b, a, a), floatArrayOf(b, a, b), floatArrayOf(a, a, b)))
    }

    /** One upright textured square, [size] tall, hanging from the pose's origin. */
    private fun plane(c: VertexConsumer, pose: PoseStack, size: Float, argb: Int, light: Int) {
        val m = pose.last().pose(); val n = pose.last().normal()
        val half = size / 2f
        val corners = arrayOf(
            floatArrayOf(-half, -size, 0f, 0f, 1f), floatArrayOf(half, -size, 0f, 1f, 1f),
            floatArrayOf(half, 0f, 0f, 1f, 0f), floatArrayOf(-half, 0f, 0f, 0f, 0f),
        )
        for (v in corners) {
            c.vertex(m, v[0], v[1], v[2])
                .color((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, (argb ushr 24) and 0xFF)
                .uv(v[3], v[4]).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n, 0f, 0f, 1f).endVertex()
        }
    }

    private fun corners(cube: FloatArray): Array<FloatArray> {
        val (x, y, z, s) = cube.toList()
        return Array(8) { i ->
            floatArrayOf(x + if (i and 1 != 0) s else -s, y + if (i and 2 != 0) s else -s, z + if (i and 4 != 0) s else -s)
        }
    }

    private val FACES = arrayOf(
        intArrayOf(0, 1, 3, 2), intArrayOf(4, 5, 7, 6), intArrayOf(0, 1, 5, 4),
        intArrayOf(2, 3, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 3, 7, 5),
    )

    private val EDGES = arrayOf(
        intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(4, 5), intArrayOf(6, 7),
        intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(4, 6), intArrayOf(5, 7),
        intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7),
    )

    private const val GLOW_ALPHA = 90

    private fun glowCube(c: VertexConsumer, m: org.joml.Matrix4f, cube: FloatArray, r: Int, g: Int, b: Int) {
        val p = corners(cube)
        for (face in FACES) for (i in face) c.vertex(m, p[i][0], p[i][1], p[i][2]).color(r, g, b, GLOW_ALPHA).endVertex()
    }

    private fun wireCube(c: VertexConsumer, m: org.joml.Matrix4f, cube: FloatArray, r: Int, g: Int, b: Int) {
        val p = corners(cube)
        for ((a, e) in EDGES) {
            val dx = p[e][0] - p[a][0]; val dy = p[e][1] - p[a][1]; val dz = p[e][2] - p[a][2]
            val len = Mth.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
            c.vertex(m, p[a][0], p[a][1], p[a][2]).color(r, g, b, 255).normal(dx / len, dy / len, dz / len).endVertex()
            c.vertex(m, p[e][0], p[e][1], p[e][2]).color(r, g, b, 255).normal(dx / len, dy / len, dz / len).endVertex()
        }
    }

    private fun rgb(rgb: Int) = Triple((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
}
