package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.core.Direction
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.block.QuasarOfPurityBlock
import org.shipwrights.enderkinesis.blockentity.QuasarOfPurityBlockEntity
import org.shipwrights.enderkinesis.item.AegisBox
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Staff-of-Aegis box in miniature, spinning on a tilted axis, with two opposed jets along its local Y. */
class QuasarOfPurityRenderer : BlockEntityRenderer<QuasarOfPurityBlockEntity> {

    override fun render(
        be: QuasarOfPurityBlockEntity,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val level = be.level ?: return
        val t = (level.gameTime % SPIN_PERIOD_TICKS) + partialTick
        drawQuasar(pose, buffers, be.blockState.getValue(QuasarOfPurityBlock.FACING), t)
    }

    /** Draws the box and beam in block-local space; [t] is in ticks. */
    fun drawQuasar(pose: PoseStack, buffers: MultiBufferSource, facing: Direction, t: Float) {
        pose.pushPose()
        pose.translate(0.5, 0.5, 0.5)
        pose.mulPose(facing.rotation)
        pose.translate(0.0, BOX_OFFSET, 0.0)
        pose.mulPose(Axis.YP.rotation(t * SPIN_RAD_PER_TICK))
        pose.mulPose(Axis.XP.rotationDegrees(TILT_DEG))
        val matrix = pose.last().pose()

        val glow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        for (face in AegisClient.FACES) emitFaceGlow(glow, matrix, face)
        val pulse = 1f - JET_PULSE_DEPTH * (0.5f + 0.5f * sin(t * JET_PULSE_RAD_PER_TICK))
        emitBeam(glow, matrix, (JET_ALPHA * pulse).toInt())

        emitEdges(buffers.getBuffer(RenderType.lines()), matrix, pose.last().normal())
        pose.popPose()
    }

    private fun emitFaceGlow(consumer: VertexConsumer, matrix: Matrix4f, face: IntArray) {
        var cx = 0f; var cy = 0f; var cz = 0f
        for (i in face) { cx += cornerX(i); cy += cornerY(i); cz += cornerZ(i) }
        cx *= 0.25f; cy *= 0.25f; cz *= 0.25f
        for (k in 0 until 4) {
            val a = face[k]
            val b = face[(k + 1) % 4]
            vertex(consumer, matrix, cornerX(a), cornerY(a), cornerZ(a), FACE_ALPHA)
            vertex(consumer, matrix, cornerX(b), cornerY(b), cornerZ(b), FACE_ALPHA)
            vertex(consumer, matrix, inset(cornerX(b), cx), inset(cornerY(b), cy), inset(cornerZ(b), cz), 0)
            vertex(consumer, matrix, inset(cornerX(a), cx), inset(cornerY(a), cy), inset(cornerZ(a), cz), 0)
        }
    }

    /** Crossed planes through the cube centre; each quad is lit only at the axis centre, so edges and tips fade to zero. */
    private fun emitBeam(consumer: VertexConsumer, matrix: Matrix4f, alpha: Int) {
        for (k in 0 until JET_PLANES) {
            val ang = k * Math.PI.toFloat() / JET_PLANES
            val dx = cos(ang)
            val dz = sin(ang)
            for (sign in BEAM_SIGNS) {
                val tipY = sign * JET_REACH
                for (side in BEAM_SIGNS) {
                    val cx = side * dx
                    val cz = side * dz
                    vertex(consumer, matrix, 0f, 0f, 0f, alpha)
                    vertex(consumer, matrix, cx * JET_CENTER_HALF_WIDTH, 0f, cz * JET_CENTER_HALF_WIDTH, 0)
                    vertex(consumer, matrix, cx * JET_TIP_HALF_WIDTH, tipY, cz * JET_TIP_HALF_WIDTH, 0)
                    vertex(consumer, matrix, 0f, tipY, 0f, 0)
                }
            }
        }
    }

    private fun emitEdges(consumer: VertexConsumer, matrix: Matrix4f, normal: Matrix3f) {
        val edges = AegisBox.edges
        for (i in edges.indices step 2) {
            val a = edges[i]
            val b = edges[i + 1]
            val dx = cornerX(b) - cornerX(a)
            val dy = cornerY(b) - cornerY(a)
            val dz = cornerZ(b) - cornerZ(a)
            val inv = 1f / sqrt(dx * dx + dy * dy + dz * dz)
            lineVertex(consumer, matrix, normal, a, dx * inv, dy * inv, dz * inv)
            lineVertex(consumer, matrix, normal, b, dx * inv, dy * inv, dz * inv)
        }
    }

    private fun lineVertex(
        consumer: VertexConsumer, matrix: Matrix4f, normal: Matrix3f, corner: Int, nx: Float, ny: Float, nz: Float,
    ) {
        consumer.vertex(matrix, cornerX(corner), cornerY(corner), cornerZ(corner))
            .color(255, 255, 255, 255)
            .normal(normal, nx, ny, nz)
            .endVertex()
    }

    private fun vertex(consumer: VertexConsumer, matrix: Matrix4f, x: Float, y: Float, z: Float, alpha: Int) {
        consumer.vertex(matrix, x, y, z).color(255, 255, 255, alpha).endVertex()
    }

    companion object {
        /** Box centre sits at y = 10/16 in the pedestal model; block centre is 8/16. */
        private const val BOX_OFFSET = 2.0 / 16.0
        private const val HALF = 1f / 16f
        private const val TILT_DEG = -22.5f
        private const val SPIN_RAD_PER_TICK = (2.0 * Math.PI / 60.0).toFloat()
        const val SPIN_PERIOD_TICKS = 60L * 1000L
        private const val FACE_ALPHA = 140
        private const val GLOW_INSET = 0.32f

        private const val JET_PLANES = 3
        private const val JET_REACH = 6f / 16f
        private const val JET_CENTER_HALF_WIDTH = 1.5f / 16f
        private const val JET_TIP_HALF_WIDTH = 2.5f / 16f
        private const val JET_ALPHA = 130f
        private val BEAM_SIGNS = floatArrayOf(1f, -1f)
        private const val JET_PULSE_DEPTH = 0.35f
        private const val JET_PULSE_RAD_PER_TICK = (2.0 * Math.PI / 30.0).toFloat()

        // Same 3-bit (sx, sy, sz) corner packing as AegisBox.Frame.corner.
        private fun cornerX(i: Int): Float = if (i and 1 != 0) HALF else -HALF
        private fun cornerY(i: Int): Float = if (i and 2 != 0) HALF else -HALF
        private fun cornerZ(i: Int): Float = if (i and 4 != 0) HALF else -HALF
        private fun inset(p: Float, c: Float): Float = p + (c - p) * GLOW_INSET
    }
}
