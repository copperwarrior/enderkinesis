package org.shipwrights.enderkinesis.entity

import net.minecraft.world.phys.Vec3

internal sealed class ConstriliarJumpState {

    object Idle : ConstriliarJumpState()

    /** Spring compression before take-off. [landing] is the path node the crouch
     *  commits to; a replan that drops it cancels the jump. */
    data class Crouch(
        val landing: ConstriliarSurfaceNavigator.Node,
        val target: Vec3,
        val ticks: Int = 0,
    ) : ConstriliarJumpState()

    /** Ballistic flight; the hover stays off for [suppressTicks] so it does not fight
     *  the arc. */
    data class Flight(val suppressTicks: Int, val ticks: Int = 0) : ConstriliarJumpState()
}
