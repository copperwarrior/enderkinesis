package org.shipwrights.enderkinesis.entity

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.Vec3

private val LONG_JUMP_ANGLES = intArrayOf(65, 70, 75, 80)
private const val GRAVITY = 0.08
private const val LANDING_PULL_BACK = 0.5
private const val LAUNCH_SCALE = 0.95
private const val ARC_STEP_FRACTION = 0.9
private const val MAX_ARC_SAMPLES = 200
private const val MIN_HORIZONTAL = 1.0e-3

/**
 * Vanilla LongJumpToRandomPos: the launch velocity for the first allowed angle whose arc to the point is clear, or null.
 * The arc has no air drag, so the jumper must discard friction in the air, as vanilla does.
 */
fun Mob.longJumpVelocity(tx: Double, ty: Double, tz: Double, maxVelocity: Double): Vec3? {
    for (angle in LONG_JUMP_ANGLES) {
        longJumpVelocityAt(angle, tx, ty, tz, maxVelocity)?.let { return it }
    }
    return null
}

private fun Mob.longJumpVelocityAt(angleDegrees: Int, tx: Double, ty: Double, tz: Double, maxVelocity: Double): Vec3? {
    val horizontal = sqrt((tx - x) * (tx - x) + (tz - z) * (tz - z))
    if (horizontal < MIN_HORIZONTAL) return null
    // Vanilla aims half a block short, so the mob lands inside the spot.
    val dx = (tx - x) * (1.0 - LANDING_PULL_BACK / horizontal)
    val dz = (tz - z) * (1.0 - LANDING_PULL_BACK / horizontal)
    val dy = ty - y
    val angle = Math.toRadians(angleDegrees.toDouble())
    val heading = atan2(dz, dx)
    val distSq = dx * dx + dz * dz
    val dist = sqrt(distSq)
    val cosAngle = cos(angle)
    val sinAngle = sin(angle)
    val speedSq = distSq * GRAVITY / (dist * sin(2.0 * angle) - 2.0 * dy * cosAngle * cosAngle)
    if (speedSq < 0.0) return null
    val speed = sqrt(speedSq)
    if (speed > maxVelocity) return null
    val horizontalSpeed = speed * cosAngle
    val steps = ceil(dist / horizontalSpeed).toInt() * 2
    if (steps > MAX_ARC_SAMPLES) return null
    val headingCos = cos(heading)
    val headingSin = sin(heading)
    var travelled = 0.0
    var prevX = 0.0
    var prevY = 0.0
    var prevZ = 0.0
    for (j in 0 until steps - 1) {
        travelled += dist / steps
        val rise = sinAngle / cosAngle * travelled - travelled * travelled * GRAVITY / (2.0 * speedSq * cosAngle * cosAngle)
        val px = x + travelled * headingCos
        val py = y + rise
        val pz = z + travelled * headingSin
        if (j > 0 && !isClearTransition(prevX, prevY, prevZ, px, py, pz)) return null
        prevX = px
        prevY = py
        prevZ = pz
    }
    return Vec3(horizontalSpeed * headingCos, speed * sinAngle, horizontalSpeed * headingSin).scale(LAUNCH_SCALE)
}

private fun Mob.isClearTransition(fx: Double, fy: Double, fz: Double, tx: Double, ty: Double, tz: Double): Boolean {
    val dims = getDimensions(pose)
    val length = sqrt((tx - fx) * (tx - fx) + (ty - fy) * (ty - fy) + (tz - fz) * (tz - fz))
    if (length < MIN_HORIZONTAL) return true
    val step = minOf(dims.width, dims.height) * ARC_STEP_FRACTION
    val count = ceil(length / step).toInt()
    for (j in 1..count) {
        val t = if (j == count) 1.0 else j * step / length
        val box = dims.makeBoundingBox(fx + (tx - fx) * t, fy + (ty - fy) * t, fz + (tz - fz) * t)
        if (!level().noCollision(this, box)) return false
    }
    return true
}
