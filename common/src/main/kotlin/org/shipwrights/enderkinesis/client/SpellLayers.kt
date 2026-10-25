package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.SpellVisuals
import kotlin.random.Random

/**
 * Spell effects drawn on living entities, after the entity itself, in its own space (origin at
 * its feet). Uses only vanilla textures and the Staff of Aegis's glow style:
 *
 * - **Frozen:** an ice shell around the hitbox.
 * - **Entangled:** mangrove roots wound around the lower body.
 * - **Crystallized:** small glowing cubes studding the body, in the Aegis look, swelling as
 *   the effect runs.
 */
object SpellLayers {
    private val ICE = RenderType.entityTranslucent(ResourceLocation("textures/block/ice.png"))
    private val ROOTS = RenderType.entityCutoutNoCull(ResourceLocation("textures/block/mangrove_roots_side.png"))

    private const val ICE_MARGIN = 0.08f
    private const val ROOT_MARGIN = 0.04f
    /** Roots climb to this share of the hitbox height, at most [ROOT_MAX_HEIGHT]. */
    private const val ROOT_HEIGHT_SHARE = 0.45f
    private const val ROOT_MAX_HEIGHT = 1.0f
    private const val CRYSTAL_COUNT = 7

    @JvmStatic
    fun render(entity: LivingEntity, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        val bits = SpellNetworkClient.visuals(entity.id)
        if (bits == 0) return
        val w = entity.bbWidth / 2f
        val h = entity.bbHeight
        val matrix = pose.last().pose()
        val normal = pose.last().normal()
        if (bits and SpellVisuals.ENTANGLED != 0) {
            val m = w + ROOT_MARGIN
            val top = minOf(h * ROOT_HEIGHT_SHARE, ROOT_MAX_HEIGHT)
            texturedBox(buffers.getBuffer(ROOTS), matrix, normal, -m, 0f, -m, m, top, m, 0xFFFFFFFF.toInt(), light)
        }
        if (bits and SpellVisuals.CRYSTALLIZED != 0) crystals(entity, partialTick, matrix, buffers, w, h)
        if (bits and SpellVisuals.FROZEN != 0) {
            val m = w + ICE_MARGIN
            texturedBox(buffers.getBuffer(ICE), matrix, normal, -m, -0.02f, -m, m, h + ICE_MARGIN, m, 0xD0FFFFFF.toInt(), light)
        }
    }

    /** Small cubes at fixed spots on the body (seeded by entity id), each a glowing face set
     *  plus a wireframe, as the Staff of Aegis draws its box. */
    private fun crystals(entity: LivingEntity, partialTick: Float, matrix: Matrix4f, buffers: MultiBufferSource, w: Float, h: Float) {
        val random = Random(entity.id)
        val c = Element.PURITY.color
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        val pulse = 0.85f + 0.15f * Mth.sin((entity.tickCount + partialTick) * 0.15f)
        val glow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        val lines = buffers.getBuffer(RenderType.lines())
        repeat(CRYSTAL_COUNT) {
            // A point on the hitbox's side walls, at body height.
            val side = random.nextInt(4)
            val along = (random.nextFloat() * 2f - 1f) * w
            val y = h * (0.2f + 0.7f * random.nextFloat())
            val (x, z) = when (side) {
                0 -> w to along
                1 -> -w to along
                2 -> along to w
                else -> along to -w
            }
            val s = (0.08f + 0.08f * random.nextFloat()) * pulse
            glowCube(glow, matrix, x, y, z, s, r, g, b)
            wireCube(lines, matrix, x, y, z, s, r, g, b)
        }
    }

    private fun texturedBox(
        consumer: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, argb: Int, light: Int,
    ) {
        // Six faces, counter-clockwise from outside, UVs 0..1 per face.
        quad(consumer, matrix, normal, argb, light, 0f, 0f, -1f, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0)
        quad(consumer, matrix, normal, argb, light, 0f, 0f, 1f, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1)
        quad(consumer, matrix, normal, argb, light, -1f, 0f, 0f, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0)
        quad(consumer, matrix, normal, argb, light, 1f, 0f, 0f, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1)
        quad(consumer, matrix, normal, argb, light, 0f, 1f, 0f, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0)
        quad(consumer, matrix, normal, argb, light, 0f, -1f, 0f, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1)
    }

    @Suppress("LongParameterList") // A quad's four corners.
    private fun quad(
        consumer: VertexConsumer, matrix: Matrix4f, normal: Matrix3f, argb: Int, light: Int,
        nx: Float, ny: Float, nz: Float,
        ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
    ) {
        vertex(consumer, matrix, normal, argb, light, nx, ny, nz, ax, ay, az, 0f, 1f)
        vertex(consumer, matrix, normal, argb, light, nx, ny, nz, bx, by, bz, 1f, 1f)
        vertex(consumer, matrix, normal, argb, light, nx, ny, nz, cx, cy, cz, 1f, 0f)
        vertex(consumer, matrix, normal, argb, light, nx, ny, nz, dx, dy, dz, 0f, 0f)
    }

    @Suppress("LongParameterList") // One NEW_ENTITY vertex.
    private fun vertex(
        consumer: VertexConsumer, matrix: Matrix4f, normal: Matrix3f, argb: Int, light: Int,
        nx: Float, ny: Float, nz: Float, x: Float, y: Float, z: Float, u: Float, v: Float,
    ) {
        consumer.vertex(matrix, x, y, z)
            .color((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, (argb ushr 24) and 0xFF)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(light)
            .normal(normal, nx, ny, nz)
            .endVertex()
    }

    private fun corners(x: Float, y: Float, z: Float, s: Float): Array<FloatArray> = Array(8) { i ->
        floatArrayOf(x + if (i and 1 != 0) s else -s, y + if (i and 2 != 0) s else -s, z + if (i and 4 != 0) s else -s)
    }

    /** Each face of the cube, by corner index (bits: x, y, z). */
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

    private fun glowCube(consumer: VertexConsumer, matrix: Matrix4f, x: Float, y: Float, z: Float, s: Float, r: Int, g: Int, b: Int) {
        val p = corners(x, y, z, s)
        for (face in FACES) for (i in face) consumer.vertex(matrix, p[i][0], p[i][1], p[i][2]).color(r, g, b, GLOW_ALPHA).endVertex()
    }

    private fun wireCube(consumer: VertexConsumer, matrix: Matrix4f, x: Float, y: Float, z: Float, s: Float, r: Int, g: Int, b: Int) {
        val p = corners(x, y, z, s)
        for ((a, c) in EDGES) {
            val dx = p[c][0] - p[a][0]; val dy = p[c][1] - p[a][1]; val dz = p[c][2] - p[a][2]
            val len = Mth.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
            consumer.vertex(matrix, p[a][0], p[a][1], p[a][2]).color(r, g, b, 255).normal(dx / len, dy / len, dz / len).endVertex()
            consumer.vertex(matrix, p[c][0], p[c][1], p[c][2]).color(r, g, b, 255).normal(dx / len, dy / len, dz / len).endVertex()
        }
    }
}
