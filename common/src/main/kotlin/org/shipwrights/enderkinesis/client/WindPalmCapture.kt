package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.model.EntityModel
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f

/** World palm of each casting player, read from the arm as the level pass draws it. Render thread only. */
object WindPalmCapture {
    /** A capture older than this many frames is stale (the player was culled or not drawn). */
    private const val MAX_AGE_FRAMES = 1L
    private const val PRUNE_AGE_FRAMES = 20L

    /** End-face centre of the arm in part space, blocks: the wide arm box spans x -3..1 (right) and y -2..10. */
    private const val WIDE_PALM_X = 1.0f / 16f
    private const val SLIM_PALM_X = 0.5f / 16f
    private const val PALM_Y = 10f / 16f

    private class Entry {
        var frame = 0L
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var nx = 0.0
        var ny = 0.0
        var nz = 0.0
    }

    private val entries = Int2ObjectOpenHashMap<Entry>()
    private var frame = 0L
    private var inLevel = false
    private val invView = Matrix4f()
    private val armPose = Matrix4f()
    private val point = Vector4f()
    private val dir = Vector3f()

    /** Call at renderLevel HEAD; the level pose stack then holds only the view rotation. */
    @JvmStatic
    fun beginLevel(view: Matrix4f) {
        frame++
        inLevel = true
        invView.set(view).invert()
        entries.values.removeIf { frame - it.frame > PRUNE_AGE_FRAMES }
    }

    /** Call at renderLevel TAIL, so GUI renders of the player model (inventory) are not captured. */
    @JvmStatic
    fun endLevel() {
        inLevel = false
    }

    /** Call just before the living-entity model draws; the pose stack is then in camera-relative model space. */
    @JvmStatic
    fun onModelRender(entity: LivingEntity, model: EntityModel<*>, poseStack: PoseStack) {
        if (!inLevel) return
        val player = entity as? AbstractClientPlayer ?: return
        val hand = player.usedItemHand
        if (!IncantationOfWindsClient.isCastingWinds(player, hand)) return
        val humanoid = model as? HumanoidModel<*> ?: return
        val arm = if (hand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite
        val right = arm == HumanoidArm.RIGHT
        val part = if (right) humanoid.rightArm else humanoid.leftArm

        poseStack.pushPose()
        part.translateAndRotate(poseStack)
        armPose.set(poseStack.last().pose())
        poseStack.popPose()
        armPose.mulLocal(invView)

        val palmX = if (player.modelName == "slim") SLIM_PALM_X else WIDE_PALM_X
        armPose.transform(point.set(if (right) -palmX else palmX, PALM_Y, 0f, 1f))
        armPose.transformDirection(dir.set(0f, 1f, 0f)).normalize()
        val cam = Minecraft.getInstance().gameRenderer.mainCamera.position
        val e = entries.computeIfAbsent(player.id) { Entry() }
        e.frame = frame
        e.x = cam.x + point.x
        e.y = cam.y + point.y
        e.z = cam.z + point.z
        e.nx = dir.x.toDouble()
        e.ny = dir.y.toDouble()
        e.nz = dir.z.toDouble()
    }

    /** The palm from the last drawn frame, or null when the player was not drawn. */
    fun palm(player: Player): IncantationOfWindsClient.Palm? {
        val e = entries.get(player.id) ?: return null
        if (frame - e.frame > MAX_AGE_FRAMES) return null
        return IncantationOfWindsClient.Palm(Vec3(e.x, e.y, e.z), Vec3(e.nx, e.ny, e.nz))
    }
}
