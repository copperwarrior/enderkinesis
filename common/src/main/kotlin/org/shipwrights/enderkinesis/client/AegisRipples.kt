package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.item.AegisBox

object AegisRipples {

    private const val RING_DURATION_TICKS: Double = 12.0
    private const val RING_COUNT: Int = 2
    private const val RING_DELAY_TICKS: Double = 3.0
    private const val LIFETIME_TICKS: Double = RING_DURATION_TICKS + (RING_COUNT - 1) * RING_DELAY_TICKS
    private const val GLOW_INSET: Double = 0.35
    private const val GLOW_ALPHA: Int = 110
    private const val MAX_RIPPLES: Int = 32

    /** Overshoot of the ease-out-back curve. This gives the bounce. */
    private const val OVERSHOOT: Double = 1.70158

    private const val HALF_W: Double = AegisBox.WIDTH * 0.5
    private const val HALF_H: Double = AegisBox.HEIGHT * 0.5
    private const val HALF_D: Double = AegisBox.VISUAL_DEPTH * 0.5

    /** Position in the box-local frame, thus the ripple moves with the shield. */
    private class Ripple(
        val wielderId: Int,
        val lx: Double, val ly: Double, val lz: Double,
        val halfSize: Double,
        val startGameTime: Long,
    )

    /** Client thread only. */
    private val ripples: ArrayDeque<Ripple> = ArrayDeque()

    /** Render thread only. Corner i is at index i * 3. */
    private val corners = DoubleArray(12)

    fun add(wielderId: Int, hit: Vec3, halfSize: Float) {
        val level = Minecraft.getInstance().level ?: return
        val wielder = level.getEntity(wielderId) as? Player ?: return
        val frame = AegisBox.forPlayer(wielder)
        val rel = hit.subtract(frame.center)
        if (ripples.size >= MAX_RIPPLES) ripples.removeFirst()
        ripples.addLast(
            Ripple(
                wielderId,
                rel.dot(frame.axisX).coerceIn(-HALF_W, HALF_W),
                rel.dot(frame.axisY).coerceIn(-HALF_H, HALF_H),
                rel.dot(frame.axisZ).coerceIn(-HALF_D, HALF_D),
                halfSize.toDouble(),
                level.gameTime,
            )
        )
    }

    fun tick(level: ClientLevel) {
        if (ripples.isEmpty()) return
        ripples.removeAll { level.gameTime - it.startGameTime >= LIFETIME_TICKS || wielder(level, it) == null }
    }

    fun render(
        pose: PoseStack, consumers: MultiBufferSource,
        camX: Double, camY: Double, camZ: Double, tickDelta: Float,
    ) {
        if (ripples.isEmpty()) return
        val level = Minecraft.getInstance().level ?: return
        pose.pushPose()
        pose.translate(-camX, -camY, -camZ)
        val matrix = pose.last().pose()
        val normal = pose.last().normal()
        // Two passes: a second getBuffer call ends the batch of the first render type.
        val glow = consumers.getBuffer(AegisRenderType.FACE_GLOW)
        forEachRing(level, tickDelta) { alpha -> emitGlow(glow, matrix, alpha) }
        val lines = consumers.getBuffer(RenderType.lines())
        forEachRing(level, tickDelta) { alpha -> emitLines(lines, matrix, normal, alpha) }
        pose.popPose()
    }

    private fun wielder(level: ClientLevel, ripple: Ripple): Player? =
        (level.getEntity(ripple.wielderId) as? Player)?.takeIf(AegisClient::isWielding)

    /** Fills [corners] for each visible ring, then calls [emit] with the ring fade (0..1). */
    private inline fun forEachRing(level: ClientLevel, tickDelta: Float, emit: (Double) -> Unit) {
        for (ripple in ripples) {
            val player = wielder(level, ripple) ?: continue
            val frame = AegisBox.forPlayer(player, tickDelta)
            val age = (level.gameTime - ripple.startGameTime) + tickDelta.toDouble()
            for (ring in 0 until RING_COUNT) {
                val t = (age - ring * RING_DELAY_TICKS) / RING_DURATION_TICKS
                if (t < 0.0 || t >= 1.0) continue
                fillCorners(frame, ripple, ripple.halfSize * easeOutBack(t))
                emit(1.0 - t)
            }
        }
    }

    private fun easeOutBack(t: Double): Double {
        val u = t - 1.0
        return 1.0 + (OVERSHOOT + 1.0) * u * u * u + OVERSHOOT * u * u
    }

    private fun fillCorners(frame: AegisBox.Frame, ripple: Ripple, half: Double) {
        val x = frame.axisX
        val y = frame.axisY
        val z = frame.axisZ
        val c = frame.center
        val cx = c.x + x.x * ripple.lx + y.x * ripple.ly + z.x * ripple.lz
        val cy = c.y + x.y * ripple.lx + y.y * ripple.ly + z.y * ripple.lz
        val cz = c.z + x.z * ripple.lx + y.z * ripple.ly + z.z * ripple.lz
        for (i in 0 until 4) {
            val sx = if (i == 1 || i == 2) half else -half
            val sy = if (i >= 2) half else -half
            corners[i * 3] = cx + x.x * sx + y.x * sy
            corners[i * 3 + 1] = cy + x.y * sx + y.y * sy
            corners[i * 3 + 2] = cz + x.z * sx + y.z * sy
        }
    }

    private fun emitGlow(consumer: VertexConsumer, matrix: Matrix4f, fade: Double) {
        val alpha = (GLOW_ALPHA * fade).toInt()
        for (i in 0 until 4) {
            val j = (i + 1) and 3
            glowVertex(consumer, matrix, i, 0.0, alpha)
            glowVertex(consumer, matrix, j, 0.0, alpha)
            glowVertex(consumer, matrix, j, GLOW_INSET, 0)
            glowVertex(consumer, matrix, i, GLOW_INSET, 0)
        }
    }

    /** [inset] moves the corner toward the square centre, as a fraction. */
    private fun glowVertex(consumer: VertexConsumer, matrix: Matrix4f, corner: Int, inset: Double, alpha: Int) {
        val opposite = (corner + 2) and 3
        val k = inset * 0.5
        val px = corners[corner * 3] + (corners[opposite * 3] - corners[corner * 3]) * k
        val py = corners[corner * 3 + 1] + (corners[opposite * 3 + 1] - corners[corner * 3 + 1]) * k
        val pz = corners[corner * 3 + 2] + (corners[opposite * 3 + 2] - corners[corner * 3 + 2]) * k
        consumer.vertex(matrix, px.toFloat(), py.toFloat(), pz.toFloat()).color(255, 255, 255, alpha).endVertex()
    }

    private fun emitLines(consumer: VertexConsumer, matrix: Matrix4f, normal: Matrix3f, fade: Double) {
        val alpha = (255 * fade).toInt()
        for (i in 0 until 4) {
            val a = i * 3
            val b = ((i + 1) and 3) * 3
            val dx = (corners[b] - corners[a]).toFloat()
            val dy = (corners[b + 1] - corners[a + 1]).toFloat()
            val dz = (corners[b + 2] - corners[a + 2]).toFloat()
            val invLen = 1f / kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
            lineVertex(consumer, matrix, normal, a, dx * invLen, dy * invLen, dz * invLen, alpha)
            lineVertex(consumer, matrix, normal, b, dx * invLen, dy * invLen, dz * invLen, alpha)
        }
    }

    private fun lineVertex(
        consumer: VertexConsumer, matrix: Matrix4f, normal: Matrix3f,
        p: Int, nx: Float, ny: Float, nz: Float, alpha: Int,
    ) {
        consumer.vertex(matrix, corners[p].toFloat(), corners[p + 1].toFloat(), corners[p + 2].toFloat())
            .color(255, 255, 255, alpha)
            .normal(normal, nx, ny, nz)
            .endVertex()
    }
}
