package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.Util
import net.minecraft.util.Mth
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3d
import org.shipwrights.enderkinesis.item.StaffBuild
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getShipManagingPos

/** Untextured outline of the capture box on the staff (wavering red), and of corner one (yellow) and two (orange). */
object CaptureBoxRenderer {
    // Outside the block faces, so the lines do not z-fight with them.
    private const val INSET = 0.002
    private const val AMPLITUDE = 0.035f
    private const val WAVE_RATE = 1.7
    private const val WAVE_PERIOD_MS = 3000L
    private const val PIECE = 0.5
    private const val MAX_PIECES = 48

    // Render thread only.
    private val shipPose = PoseStack()
    private val shipTurn = Matrix4f()
    private val shipNormal = Matrix3f()
    private val camLocal = Vector3d()

    @Suppress("LongParameterList") // Camera-relative doubles keep the lines exact far from the world origin.
    fun render(
        poseStack: PoseStack, consumers: MultiBufferSource, level: Level, view: StaffBuildClient.StaffView,
        camX: Double, camY: Double, camZ: Double,
    ) {
        val capture = view.capture ?: return
        if (capture.dimension != level.dimension()) return
        val a = capture.first ?: StaffBuildClient.gazeCorner
        val b = capture.second ?: StaffBuildClient.gazeCorner
        val pose = framePose(poseStack, level, capture, camX, camY, camZ) ?: return
        val cx = camLocal.x
        val cy = camLocal.y
        val cz = camLocal.z
        val lines = consumers.getBuffer(RenderType.lines())
        capture.first?.let { cell(pose, lines, it, cx, cy, cz, CORNER_ONE) }
        capture.second?.let { cell(pose, lines, it, cx, cy, cz, CORNER_TWO) }
        if (a == null || b == null) return
        wobblyBox(
            pose.last(), lines,
            minOf(a.x, b.x) - INSET, minOf(a.y, b.y) - INSET, minOf(a.z, b.z) - INSET,
            maxOf(a.x, b.x) + 1 + INSET, maxOf(a.y, b.y) + 1 + INSET, maxOf(a.z, b.z) + 1 + INSET,
            cx, cy, cz,
        )
    }

    /**
     * The pose of the capture frame, with the camera in that frame in [camLocal]. Shipyard corners draw through the
     * ship's render transform, so the box moves with the ship. Null while that ship is not loaded here.
     */
    @Suppress("LongParameterList") // Camera-relative doubles keep the lines exact far from the world origin.
    private fun framePose(
        poseStack: PoseStack, level: Level, capture: StaffBuild.CaptureBox, camX: Double, camY: Double, camZ: Double,
    ): PoseStack? {
        camLocal.set(camX, camY, camZ)
        val id = capture.ship ?: return poseStack
        val corner = capture.first ?: capture.second ?: return null
        val ship = level.getShipManagingPos(corner) as? ClientShip ?: return null
        if (ship.id != id) return null
        val transform = ship.renderTransform
        transform.worldToShip.transformPosition(camLocal)
        shipTurn.set(transform.shipToWorld).setTranslation(0f, 0f, 0f)
        val last = shipPose.last()
        last.pose().set(poseStack.last().pose()).mul(shipTurn)
        // RenderType.lines() widens each line along its normal, so the normal must turn with the ship.
        last.normal().set(poseStack.last().normal()).mul(shipNormal.set(shipTurn).normal())
        return shipPose
    }

    // The 12 edges, each cut into short pieces whose perpendicular offset follows a travelling sine wave.
    @Suppress("LongParameterList") // World-space extents and camera offsets stay separate doubles to avoid allocation.
    private fun wobblyBox(
        pose: PoseStack.Pose, lines: VertexConsumer,
        x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double,
        camX: Double, camY: Double, camZ: Double,
    ) {
        val time = (Util.getMillis() % WAVE_PERIOD_MS).toFloat() / WAVE_PERIOD_MS * Mth.TWO_PI
        for (i in 0 until 4) {
            val y = if (i and 1 == 0) y0 else y1
            val z = if (i and 2 == 0) z0 else z1
            edge(pose, lines, x0, y, z, x1, y, z, camX, camY, camZ, time)
            val x = if (i and 1 == 0) x0 else x1
            edge(pose, lines, x, y0, z, x, y1, z, camX, camY, camZ, time)
            val yy = if (i and 1 == 0) y0 else y1
            val xx = if (i and 2 == 0) x0 else x1
            edge(pose, lines, xx, yy, z0, xx, yy, z1, camX, camY, camZ, time)
        }
    }

    @Suppress("LongParameterList") // World-space ends and camera offsets stay separate doubles to avoid allocation.
    private fun edge(
        pose: PoseStack.Pose, lines: VertexConsumer,
        x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double,
        camX: Double, camY: Double, camZ: Double, time: Float,
    ) {
        val length = (x1 - x0) + (y1 - y0) + (z1 - z0)
        val pieces = (length / PIECE).toInt().coerceIn(1, MAX_PIECES)
        var px = x0
        var py = y0
        var pz = z0
        for (step in 1..pieces) {
            val t = step.toDouble() / pieces
            val nx = x0 + (x1 - x0) * t
            val ny = y0 + (y1 - y0) * t
            val nz = z0 + (z1 - z0) * t
            val ends = step == pieces
            wobbleSegment(pose, lines, px, py, pz, nx, ny, nz, camX, camY, camZ, time, step == 1, ends)
            px = nx
            py = ny
            pz = nz
        }
    }

    @Suppress("LongParameterList") // See edge.
    private fun wobbleSegment(
        pose: PoseStack.Pose, lines: VertexConsumer,
        ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double,
        camX: Double, camY: Double, camZ: Double, time: Float, pinStart: Boolean, pinEnd: Boolean,
    ) {
        val sx = if (pinStart) 0f else wave(ax, ay, az, time, 0f)
        val sy = if (pinStart) 0f else wave(ax, ay, az, time, 2f)
        val sz = if (pinStart) 0f else wave(ax, ay, az, time, 4f)
        val ex = if (pinEnd) 0f else wave(bx, by, bz, time, 0f)
        val ey = if (pinEnd) 0f else wave(bx, by, bz, time, 2f)
        val ez = if (pinEnd) 0f else wave(bx, by, bz, time, 4f)
        var dx = (bx + ex - ax - sx).toFloat()
        var dy = (by + ey - ay - sy).toFloat()
        var dz = (bz + ez - az - sz).toFloat()
        val norm = Mth.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1.0e-4f)
        dx /= norm
        dy /= norm
        dz /= norm
        lines.vertex(pose.pose(), (ax + sx - camX).toFloat(), (ay + sy - camY).toFloat(), (az + sz - camZ).toFloat())
            .color(BOX[0], BOX[1], BOX[2], BOX[3]).normal(pose.normal(), dx, dy, dz).endVertex()
        lines.vertex(pose.pose(), (bx + ex - camX).toFloat(), (by + ey - camY).toFloat(), (bz + ez - camZ).toFloat())
            .color(BOX[0], BOX[1], BOX[2], BOX[3]).normal(pose.normal(), dx, dy, dz).endVertex()
    }

    // Phase in the capture frame, so the wave does not slide as the camera or the ship moves.
    private fun wave(x: Double, y: Double, z: Double, time: Float, shift: Float): Float =
        Mth.sin(((x + y * 1.3 + z * 0.7) * WAVE_RATE).toFloat() + time + shift) * AMPLITUDE

    @Suppress("LongParameterList") // Camera-relative doubles keep the lines exact far from the world origin.
    private fun cell(
        poseStack: PoseStack, lines: VertexConsumer, pos: BlockPos,
        camX: Double, camY: Double, camZ: Double, rgba: FloatArray,
    ) {
        val pad = INSET * 2
        LevelRenderer.renderLineBox(
            poseStack, lines,
            pos.x - pad - camX, pos.y - pad - camY, pos.z - pad - camZ,
            pos.x + 1 + pad - camX, pos.y + 1 + pad - camY, pos.z + 1 + pad - camZ,
            rgba[0], rgba[1], rgba[2], rgba[3],
        )
    }

    private val BOX = floatArrayOf(1f, 0.12f, 0.1f, 1f)
    private val CORNER_ONE = floatArrayOf(1f, 0.9f, 0.1f, 1f)
    private val CORNER_TWO = floatArrayOf(1f, 0.5f, 0.05f, 1f)
}
