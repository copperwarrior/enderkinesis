package org.shipwrights.enderkinesis.physics

import org.joml.Matrix4d
import org.joml.Matrix4dc
import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3dc

/** The deck's motion between two poses as one world-to-world map: a world point fixed to the deck then is here now. */
internal fun deckMotion(shipToWorldNow: Matrix4dc, worldToShipThen: Matrix4dc, dest: Matrix4d): Matrix4d =
    shipToWorldNow.mul(worldToShipThen, dest)

/** The deck's turn between two unit rotations, in world axes: dest · then = now. */
internal fun deckTurn(rotationNow: Quaterniondc, rotationThen: Quaterniondc, dest: Quaterniond): Quaterniond {
    dest.set(rotationThen).conjugate()
    return rotationNow.mul(dest, dest)
}

/** MC yaw change (degrees) that keeps a heading fixed to a deck that made [turn], from its twist about [up].
 *  MC yaw turns clockwise seen from above, JOML counter-clockwise, so the sign flips. */
internal fun deckTwistYawDegrees(turn: Quaterniondc, up: Vector3dc): Float {
    val along = (turn.x() * up.x() + turn.y() * up.y() + turn.z() * up.z()) / up.length()
    // q and -q are the same turn; take w >= 0 so the twist stays in [-pi, pi].
    val twist = if (turn.w() < 0.0) 2.0 * Math.atan2(-along, -turn.w()) else 2.0 * Math.atan2(along, turn.w())
    return (-Math.toDegrees(twist)).toFloat()
}
