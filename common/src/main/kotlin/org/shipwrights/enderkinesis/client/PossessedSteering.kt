package org.shipwrights.enderkinesis.client

import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

/** Input and camera writes for a client-side walk that holds the local player's body. Call after `Input.tick`. */
object PossessedSteering {

    // 12°/tick ≈ 240°/s. The camera lerps yRotO→yRot linearly, so a capped step has no kink at each tick edge.
    private const val MAX_TURN_PER_TICK = 12f

    fun walkForward(player: LocalPlayer, impulse: Float, jump: Boolean = false) {
        val input = player.input
        input.forwardImpulse = impulse
        input.leftImpulse = 0f
        input.up = true
        input.down = false
        input.left = false
        input.right = false
        input.jumping = jump
        input.shiftKeyDown = false
    }

    fun standStill(player: LocalPlayer) {
        val input = player.input
        input.forwardImpulse = 0f
        input.leftImpulse = 0f
        input.up = false
        input.down = false
        input.left = false
        input.right = false
        input.jumping = false
        input.shiftKeyDown = false
    }

    fun lookAtPoint(player: LocalPlayer, point: Vec3, factor: Float) {
        val dx = point.x - player.x
        val dy = point.y - player.eyeY
        val dz = point.z - player.z
        val horiz = Math.sqrt(dx * dx + dz * dz)
        val yaw = (Mth.atan2(dz, dx) * (180.0 / Math.PI)).toFloat() - 90f
        val pitch = (-(Mth.atan2(dy, horiz) * (180.0 / Math.PI))).toFloat()
        applyYaw(player, ease(player.yRot, yaw, factor))
        applyPitch(player, ease(player.xRot, pitch, factor))
    }

    // Vanilla updates the *O fields only from mouse turns, server lerps and teleports. With a screen open and
    // no mouse movement they go stale, and the partial-tick lerp sweeps the whole gap each frame (camera stutter).
    fun applyYaw(player: LocalPlayer, yaw: Float) {
        player.yRotO = player.yRot
        player.yHeadRotO = player.yHeadRot
        player.yBodyRotO = player.yBodyRot
        player.yRot = yaw
        player.yHeadRot = yaw
        player.yBodyRot = yaw
    }

    fun applyPitch(player: LocalPlayer, pitch: Float) {
        player.xRotO = player.xRot
        player.xRot = pitch
    }

    /** Eases toward an angle: a constant capped turn for most of the way, then an exponential landing. */
    fun ease(from: Float, to: Float, factor: Float): Float {
        val step = (Mth.wrapDegrees(to - from) * factor).coerceIn(-MAX_TURN_PER_TICK, MAX_TURN_PER_TICK)
        return from + step
    }
}
