package org.shipwrights.enderkinesis.client

import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import org.joml.Vector3d
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Model pixels to blocks for the player model: PlayerRenderer scales it by 0.9375. */
private const val MODEL_SCALE = 0.9375 / 16.0
/** Model y 0 is the neck, 24 px over the feet; y grows down. */
private const val MODEL_FEET_PX = 24.0
/** Vertical gap between the two hands on the rope, in blocks; the right hand holds over the left. */
private const val HAND_SPREAD = 0.12
private const val MIN_AIM_LENGTH = 1e-6

/** Render-thread scratch for the hang pose. */
private object HangPoseScratch {
    val grip = Vector3d()
}

/**
 * For HumanoidModelVineHangPoseMixin: both arms up on the held rope, at [partialTick] like the drawn body. Returns
 * false when [player] holds no vine.
 */
fun HumanoidModel<*>.applyVineHangPose(player: Player, partialTick: Float): Boolean {
    val grip = HangPoseScratch.grip
    val t = partialTick.toDouble()
    if (!VineHoldClient.gripPoint(player, t, grip)) return false
    val yaw = Math.toRadians(Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot).toDouble())
    val bx = Mth.lerp(t, player.xo, player.x)
    val by = Mth.lerp(t, player.yo, player.y)
    val bz = Mth.lerp(t, player.zo, player.z)
    aimArmAt(rightArm, bx, by, bz, yaw, grip.x, grip.y + HAND_SPREAD / 2, grip.z)
    aimArmAt(leftArm, bx, by, bz, yaw, grip.x, grip.y - HAND_SPREAD / 2, grip.z)
    // The legs hang still; the walk swing reads the hang's own motion as steps.
    rightLeg.xRot = 0f
    leftLeg.xRot = 0f
    return true
}

/** Turns [arm] so it points from its shoulder to ([tx], [ty], [tz]), for a body with feet at ([bx], [by], [bz]) and [yaw]. */
@Suppress("LongParameterList") // Plain doubles keep the per-frame pose free of allocation.
private fun aimArmAt(arm: ModelPart, bx: Double, by: Double, bz: Double, yaw: Double, tx: Double, ty: Double, tz: Double) {
    // Body right and ahead for a Minecraft yaw: ahead (-sin, cos), right (-cos, -sin) in (x, z).
    val rightX = -cos(yaw); val rightZ = -sin(yaw)
    val aheadX = -sin(yaw); val aheadZ = cos(yaw)
    val side = -arm.x * MODEL_SCALE
    val vx = tx - (bx + rightX * side)
    val vy = ty - (by + (MODEL_FEET_PX - arm.y) * MODEL_SCALE)
    val vz = tz - (bz + rightZ * side)
    // Model axes: +x to the body's left, +y down, -z ahead.
    setArmAim(arm, -(vx * rightX + vz * rightZ), -vy, -(vx * aheadX + vz * aheadZ))
}

/**
 * Sets [arm]'s rotation so its length (model +y) points along ([ux], [uy], [uz]) in model axes. The xRot branch in
 * [-3pi/2, -pi/2] raises the arm forward and up, as vanilla raises arms, so the twist does not flip near the top.
 */
private fun setArmAim(arm: ModelPart, ux: Double, uy: Double, uz: Double) {
    val length = sqrt(ux * ux + uy * uy + uz * uz)
    if (length < MIN_AIM_LENGTH) return
    arm.xRot = (-PI - asin((uz / length).coerceIn(-1.0, 1.0))).toFloat()
    arm.yRot = 0f
    arm.zRot = atan2(ux / length, -uy / length).toFloat()
}
