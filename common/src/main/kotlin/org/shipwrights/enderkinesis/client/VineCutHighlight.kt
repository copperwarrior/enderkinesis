package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.util.Mth
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ShearsItem
import net.minecraft.world.item.SwordItem
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3d
import org.shipwrights.enderkinesis.vine.PICK_REACH
import org.shipwrights.enderkinesis.vine.VineCutNetwork
import org.shipwrights.enderkinesis.vine.VineNodeRef
import org.shipwrights.enderkinesis.vine.clientVineCollider
import org.shipwrights.enderkinesis.vine.rayHitsSegment
import org.valkyrienskies.mod.common.getShipManagingPos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Vanilla's block outline grows its shape by 0.002; a little more keeps the lines clear of the vine faces. */
private const val OUTLINE_MARGIN = 0.01
private const val OUTLINE_ALPHA = 0.4f
private const val MIN_EDGE = 1e-4
/** A fading vine in front is stepped through, as the server skips it; this caps the steps. */
private const val MAX_FADING_SKIPS = 8
private const val SKIP_STEP = 1e-3

/**
 * Outlines the vine link that shears, or a sword swing, would cut, as pickWoggakVine on the server finds it. The pick runs each frame,
 * as vanilla's crosshair pick does, on the collider's nodes; the outline sits on the drawn rope.
 */
object VineCutHighlight {
    private val origin = Vector3d()
    private val rayDir = Vector3d()
    private val ref = VineNodeRef()
    private val links = DoubleArray(9)
    private val box = DoubleArray(24)
    private val a = Vector3d(); private val b = Vector3d(); private val node = Vector3d()
    private val hitPoint = Vector3d()
    private var hitT = 0.0
    private var cutHeld = false

    private var pickChain = 0L
    private var pickLinkIndex = -1
    private var pickDistance = 0.0

    /** Mixin hook for `startAttack`: true when a sword click cut a vine, so vanilla must skip the attack. */
    @JvmStatic
    fun trySwordCut(mc: Minecraft): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        if (clientVineCollider.isEmpty || player.mainHandItem.item !is SwordItem) return false
        if (!pickLink(player, mc, level, 1f)) return false
        val entity = mc.hitResult as? EntityHitResult
        if (entity != null && entity.location.distanceTo(player.getEyePosition(1f)) < pickDistance) return false
        VineCutNetwork.sendCut()
        player.swing(InteractionHand.MAIN_HAND)
        cutHeld = true
        return true
    }

    /** Mixin hook for `continueAttack`: true while a sword press that cut a vine stays down, so no block breaks. */
    @JvmStatic
    fun blocksMining(mc: Minecraft): Boolean {
        if (!cutHeld) return false
        if (mc.options.keyAttack.isDown) return true
        cutHeld = false
        return false
    }

    /** Finds the vine link under the crosshair into [pickChain], [pickLinkIndex] and [pickDistance]. */
    private fun pickLink(player: LocalPlayer, mc: Minecraft, level: ClientLevel, partialTicks: Float): Boolean {
        var left = player.aim(mc, level, partialTicks)
        var traveled = 0.0
        for (skip in 0 until MAX_FADING_SKIPS) {
            if (!clientVineCollider.pick(origin, rayDir, left, ref)) return false
            val hit = hitLink(ref.chainId, ref.node, left)
            if (hit < 0) return false
            if (!WoggakVineRenderer.isFading(ref.chainId)) {
                pickChain = ref.chainId
                pickLinkIndex = hit
                pickDistance = traveled + hitT
                return true
            }
            val step = hitT + SKIP_STEP
            origin.fma(step, rayDir)
            left -= step
            traveled += step
            if (left <= 0) return false
        }
        return false
    }

    fun renderAll(
        poseStack: PoseStack, buffers: MultiBufferSource,
        cameraX: Double, cameraY: Double, cameraZ: Double, partialTicks: Float,
    ) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        if (clientVineCollider.isEmpty || !(player.shearsGetTheUse() || player.mainHandItem.item is SwordItem)) return
        if (!pickLink(player, mc, level, partialTicks)) return
        val chainId = pickChain
        val link = pickLinkIndex
        if (!WoggakVineRenderer.drawnLinkBox(chainId, link, OUTLINE_MARGIN, box)) return
        val out = buffers.getBuffer(RenderType.lines())
        val matrix = poseStack.last().pose()
        val normal = poseStack.last().normal()
        for (c in 0 until 4) {
            val n = (c + 1) % 4
            edge(out, matrix, normal, c, c + 4, cameraX, cameraY, cameraZ)
            edge(out, matrix, normal, c, n, cameraX, cameraY, cameraZ)
            edge(out, matrix, normal, c + 4, n + 4, cameraX, cameraY, cameraZ)
        }
    }

    /**
     * Server hand order: the main hand's use comes first, so off-hand shears get it only when the main-hand item has
     * no use of its own. A modded item with its own use is not seen here.
     */
    private fun LocalPlayer.shearsGetTheUse(): Boolean {
        if (mainHandItem.item is ShearsItem) return true
        if (offhandItem.item !is ShearsItem) return false
        val main = mainHandItem
        if (main.isEmpty) return true
        val food = main.item.foodProperties
        if (food != null) return !canEat(food.canAlwaysEat())
        return main.item is BlockItem || main.item.javaClass == Item::class.java
    }

    /**
     * Sets [origin] and [rayDir] as vanilla's crosshair pick does, and returns the reach: [PICK_REACH] cut at the
     * first block, as the server's clip does. A miss or an entity hit needs its own clip, the only allocating path.
     */
    private fun LocalPlayer.aim(mc: Minecraft, level: ClientLevel, partialTicks: Float): Double {
        origin.set(
            Mth.lerp(partialTicks.toDouble(), xo, x),
            Mth.lerp(partialTicks.toDouble(), yo, y) + eyeHeight,
            Mth.lerp(partialTicks.toDouble(), zo, z),
        )
        val pitch = getViewXRot(partialTicks) * Mth.DEG_TO_RAD
        val yaw = -getViewYRot(partialTicks) * Mth.DEG_TO_RAD
        val cosPitch = cos(pitch).toDouble()
        rayDir.set(sin(yaw) * cosPitch, -sin(pitch).toDouble(), cos(yaw) * cosPitch)
        val hit = mc.hitResult
        if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
            // VS2: the crosshair hit on a ship block is in shipyard coordinates.
            hitPoint.set(hit.location.x, hit.location.y, hit.location.z)
            level.getShipManagingPos(hit.blockPos)?.shipToWorld?.transformPosition(hitPoint)
            return minOf(PICK_REACH, hitPoint.distance(origin))
        }
        val eye = Vec3(origin.x, origin.y, origin.z)
        val end = eye.add(rayDir.x * PICK_REACH, rayDir.y * PICK_REACH, rayDir.z * PICK_REACH)
        // The VS2 clip mixin gives a world-frame location, ships included, as on the server.
        return level.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, this)).location.distanceTo(eye)
    }

    /** The pick gives the nearer node only; of the two links at [nearNode], the one the ray meets first is the cut. */
    private fun hitLink(chain: Long, nearNode: Int, reach: Double): Int {
        val before = nearNode > 0 && clientVineCollider.nodePosition(chain, nearNode - 1, a)
        val after = clientVineCollider.nodePosition(chain, nearNode + 1, b)
        if (!clientVineCollider.nodePosition(chain, nearNode, node)) return -1
        links[3] = node.x; links[4] = node.y; links[5] = node.z
        var best = -1
        hitT = Double.MAX_VALUE
        if (before) {
            links[0] = a.x; links[1] = a.y; links[2] = a.z
            val t = links.rayHitsSegment(origin, rayDir, 0, reach)
            if (t >= 0) { best = nearNode - 1; hitT = t }
        }
        if (after) {
            links[6] = b.x; links[7] = b.y; links[8] = b.z
            val t = links.rayHitsSegment(origin, rayDir, 3, reach)
            if (t >= 0 && t < hitT) { best = nearNode; hitT = t }
        }
        return best
    }

    @Suppress("LongParameterList") // Buffer, pose, two corner indices into the box and the camera.
    private fun edge(
        out: VertexConsumer, matrix: Matrix4f, normal: Matrix3f, from: Int, to: Int,
        cameraX: Double, cameraY: Double, cameraZ: Double,
    ) {
        val f = from * 3; val t = to * 3
        val dx = box[t] - box[f]; val dy = box[t + 1] - box[f + 1]; val dz = box[t + 2] - box[f + 2]
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < MIN_EDGE) return
        val nx = (dx / len).toFloat(); val ny = (dy / len).toFloat(); val nz = (dz / len).toFloat()
        out.vertex(matrix, (box[f] - cameraX).toFloat(), (box[f + 1] - cameraY).toFloat(), (box[f + 2] - cameraZ).toFloat())
            .color(0f, 0f, 0f, OUTLINE_ALPHA).normal(normal, nx, ny, nz).endVertex()
        out.vertex(matrix, (box[t] - cameraX).toFloat(), (box[t + 1] - cameraY).toFloat(), (box[t + 2] - cameraZ).toFloat())
            .color(0f, 0f, 0f, OUTLINE_ALPHA).normal(normal, nx, ny, nz).endVertex()
    }
}
