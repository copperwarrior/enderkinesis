package org.shipwrights.enderkinesis.client

import java.util.Random
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import net.minecraft.util.Mth

/**
 * The movement of one Sureibjin silhouette that walks as a player does: it goes to random points near its
 * anchor, turns its body to its path, and looks at the local player when it stands.
 */
internal class SureibjinEchoWalker(seed: Long) {

    private val rng = Random(seed)
    private var age = 0
    private var placed = false

    private var x = 0.0
    private var z = 0.0
    private var xo = 0.0
    private var zo = 0.0
    private var speed = 0.0
    private var goalX = 0.0
    private var goalZ = 0.0
    private var nextGoalAt = 0

    var bodyYaw = 0f
        private set
    var bodyYawO = 0f
        private set
    var headYaw = 0f
        private set
    var headYawO = 0f
        private set
    var pitch = 0f
        private set
    var pitchO = 0f
        private set
    var animSpeed = 0f
        private set
    var animSpeedO = 0f
        private set
    var animPosition = 0f
        private set

    private var lookYawOffset = 0f
    private var lookPitch = 0f
    private var nextLookAt = 0

    fun x(partialTick: Float): Double = Mth.lerp(partialTick.toDouble(), xo, x)
    fun z(partialTick: Float): Double = Mth.lerp(partialTick.toDouble(), zo, z)

    /** Puts the walker at its anchor when it has no position, or when a teleport moved the anchor far away. */
    fun ensureNear(anchorX: Double, anchorZ: Double, playerX: Double, playerZ: Double) {
        val dx = anchorX - x
        val dz = anchorZ - z
        if (placed && dx * dx + dz * dz < TELEPORT_DISTANCE * TELEPORT_DISTANCE) return
        placed = true
        x = anchorX + goalX
        z = anchorZ + goalZ
        xo = x
        zo = z
        speed = 0.0
        bodyYaw = yawTo(playerX - x, playerZ - z)
        bodyYawO = bodyYaw
        headYaw = bodyYaw
        headYawO = bodyYaw
    }

    fun tick(anchorX: Double, anchorZ: Double, playerX: Double, playerZ: Double, sneaking: Boolean) {
        age++
        xo = x
        zo = z
        bodyYawO = bodyYaw
        headYawO = headYaw
        pitchO = pitch

        if (age >= nextGoalAt) {
            val angle = rng.nextDouble() * Math.PI * 2.0
            val radius = sqrt(rng.nextDouble()) * WANDER_RADIUS
            goalX = cos(angle) * radius
            goalZ = sin(angle) * radius
            nextGoalAt = age + MIN_GOAL_TICKS + rng.nextInt(EXTRA_GOAL_TICKS)
        }

        val dx = anchorX + goalX - x
        val dz = anchorZ + goalZ - z
        val distance = sqrt(dx * dx + dz * dz)
        val wanted = when {
            distance < ARRIVE_DISTANCE -> 0.0
            distance > CATCH_UP_DISTANCE -> CATCH_UP_SPEED
            distance > SPRINT_DISTANCE -> SPRINT_SPEED
            sneaking -> SNEAK_SPEED
            else -> WALK_SPEED
        }
        speed += (wanted - speed) * ACCELERATION
        val step = min(speed, distance)
        val moving = step > MOVING_STEP
        if (moving) {
            x += dx / distance * step
            z += dz / distance * step
        }
        turn(moving, yawTo(dx, dz), yawTo(playerX - x, playerZ - z))

        // The same rule as `LivingEntity.calculateEntityAnimation`.
        animSpeedO = animSpeed
        animSpeed += (min(step.toFloat() * 4.0f, 1.0f) - animSpeed) * 0.4f
        animPosition += animSpeed
    }

    private fun turn(moving: Boolean, pathYaw: Float, playerYaw: Float) {
        if (age >= nextLookAt) {
            // Half of the time the head goes back to the player.
            val glance = rng.nextBoolean()
            lookYawOffset = if (glance) (rng.nextFloat() - 0.5f) * 2.0f * MAX_LOOK_YAW else 0.0f
            lookPitch = if (glance) (rng.nextFloat() - 0.4f) * MAX_LOOK_PITCH else 0.0f
            nextLookAt = age + MIN_LOOK_TICKS + rng.nextInt(EXTRA_LOOK_TICKS)
        }
        val headTarget = if (moving) pathYaw + lookYawOffset * 0.5f else playerYaw + lookYawOffset
        headYaw = Mth.approachDegrees(headYaw, headTarget, HEAD_TURN_RATE)
        if (moving) {
            bodyYaw = Mth.approachDegrees(bodyYaw, pathYaw, BODY_TURN_RATE)
        } else if (kotlin.math.abs(Mth.degreesDifference(bodyYaw, headYaw)) > IDLE_BODY_SLACK) {
            bodyYaw = Mth.approachDegrees(bodyYaw, headYaw, IDLE_BODY_TURN_RATE)
        }
        // Vanilla keeps the head within this angle of the body.
        val headOffset = Mth.clamp(Mth.degreesDifference(bodyYaw, headYaw), -MAX_HEAD_OFFSET, MAX_HEAD_OFFSET)
        headYaw = bodyYaw + headOffset
        pitch += (lookPitch - pitch) * PITCH_RATE
    }

    // MC yaw: 0 is +Z (south), 90 is -X (west).
    private fun yawTo(dx: Double, dz: Double): Float = Math.toDegrees(atan2(-dx, dz)).toFloat()

    private companion object {
        const val WANDER_RADIUS = 6.0
        const val TELEPORT_DISTANCE = 28.0
        const val MIN_GOAL_TICKS = 60
        const val EXTRA_GOAL_TICKS = 240

        // Blocks for each tick. Walk, sprint and sneak are the vanilla player speeds.
        const val WALK_SPEED = 0.216
        const val SPRINT_SPEED = 0.281
        const val SNEAK_SPEED = 0.065
        // Faster than a player can run. It applies only when the local player goes away faster than a sprint.
        const val CATCH_UP_SPEED = 0.45
        const val SPRINT_DISTANCE = 7.0
        const val CATCH_UP_DISTANCE = 14.0
        const val ARRIVE_DISTANCE = 0.3
        const val ACCELERATION = 0.3
        const val MOVING_STEP = 0.01

        const val BODY_TURN_RATE = 20.0f
        const val IDLE_BODY_TURN_RATE = 6.0f
        const val IDLE_BODY_SLACK = 30.0f
        const val HEAD_TURN_RATE = 12.0f
        const val MAX_HEAD_OFFSET = 75.0f
        const val MAX_LOOK_YAW = 50.0f
        const val MAX_LOOK_PITCH = 25.0f
        const val PITCH_RATE = 0.2f
        const val MIN_LOOK_TICKS = 40
        const val EXTRA_LOOK_TICKS = 120
    }
}
