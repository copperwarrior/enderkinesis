package org.shipwrights.enderkinesis.vine

import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.mod.common.util.EntityShipCollisionUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val MIN_SIDE_AMOUNT = 1e-3
private const val MIN_HORIZONTAL = 1e-9
/** Probe boxes shrink by this, so a box that only touches a wall face is free. */
private const val PROBE_DEFLATE = 1e-5
/** A ship cuts the probe sweep short by more than this, in blocks, when it blocks the side. */
private const val SHIP_BLOCK_SLACK = 1e-3

/** Body place of the local hold: side hang (B) on a steep rope, under-hang (E) on a level one. Client thread only. */
internal class VineHangPlacer : OrbitBlocker {
    private val linkStart = Vector3d()
    private val linkEnd = Vector3d()
    /** Shown orbit angle; NaN until the first place after a grab. */
    private var orbitAngle = Double.NaN
    /** Shown share of the side hang: 0 under the grip, 1 fully beside the rope. */
    private var amount = 0.0
    /** The feet place from the last [place]. */
    val feet = Vector3d()

    // Inputs of the current place, for isBlocked.
    private var probe: Player? = null
    private var gripX = 0.0
    private var gripY = 0.0
    private var gripZ = 0.0
    private var halfWidth = 0.0
    private var downX = 0.0
    private var downZ = 0.0
    private var tanSlope = 0.0
    private var downReach = 0.0
    private var targetAmount = 0.0

    fun reset() {
        orbitAngle = Double.NaN
        amount = 0.0
    }

    /** Puts the feet place for [player] holding [chainId] at [grip], with the grip point [gripPoint], into [feet]. */
    fun place(player: Player, chainId: Long, grip: Double, gripPoint: Vector3dc): Boolean {
        if (!readRope(chainId, grip)) return false
        halfWidth = player.bbWidth / 2.0
        gripX = gripPoint.x(); gripY = gripPoint.y(); gripZ = gripPoint.z()
        val desired = orbitTargetAngle(player.yRot.toDouble())
        val chosen = if (targetAmount < MIN_SIDE_AMOUNT) {
            desired
        } else {
            probe = player
            try { chooseOrbitAngle(desired, orbitAngle, this) } finally { probe = null }
        }
        // No free side: hang under the grip.
        if (chosen.isNaN()) targetAmount = 0.0
        val angleTarget = if (!chosen.isNaN()) chosen else if (orbitAngle.isNaN()) desired else orbitAngle
        if (orbitAngle.isNaN()) {
            orbitAngle = angleTarget
            amount = targetAmount
        } else {
            orbitAngle = stepOrbitAngle(orbitAngle, angleTarget)
            amount += (targetAmount - amount) * HANG_AMOUNT_EASE
        }
        val side = sideRadius(orbitAngle, downX, downZ, tanSlope, downReach)
        val radius = (if (side.isNaN()) HANG_RADIUS + MAX_SIDE_EXTRA else side) * amount
        val cx = cos(orbitAngle) * radius
        val cz = sin(orbitAngle) * radius
        val drop = min(hangDrop(cx, cz, downX, downZ, tanSlope, downReach), MAX_HANG_DROP)
        feet.set(gripX + cx, gripY - drop - DRAWN_HEIGHT, gripZ + cz)
        return true
    }

    /** Reads the slope, downhill way and end state of the link at [grip], and sets [targetAmount] from them. */
    private fun readRope(chainId: Long, grip: Double): Boolean {
        val count = clientVineCollider.nodeCount(chainId)
        if (count < 2) return false
        val link = gripLink(grip, count)
        if (!clientVineCollider.nodePosition(chainId, link, linkStart)) return false
        if (!clientVineCollider.nodePosition(chainId, link + 1, linkEnd)) return false
        val tx = linkEnd.x - linkStart.x
        val ty = linkEnd.y - linkStart.y
        val tz = linkEnd.z - linkStart.z
        val horizontal = sqrt(tx * tx + tz * tz)
        val downForward = ty <= 0.0
        val sign = if (downForward) 1.0 else -1.0
        if (horizontal > MIN_HORIZONTAL) {
            downX = sign * tx / horizontal
            downZ = sign * tz / horizontal
            tanSlope = abs(ty) / horizontal
        } else {
            downX = 0.0; downZ = 0.0; tanSlope = Double.POSITIVE_INFINITY
        }
        val last = (count - 1).toDouble()
        downReach = (if (downForward) last - grip else grip).coerceAtLeast(0.0) * horizontal
        val endWeight = min(
            endHangWeight(last - grip, link == count - 2 && ty < 0.0),
            endHangWeight(grip, link == 0 && ty > 0.0),
        )
        targetAmount = sideHangWeight(tx, ty, tz) * endWeight
        return true
    }

    override fun isBlocked(angle: Double): Boolean {
        val player = probe ?: return false
        val side = sideRadius(angle, downX, downZ, tanSlope, downReach)
        if (side.isNaN()) return true
        val radius = side * targetAmount
        val cx = cos(angle) * radius
        val cz = sin(angle) * radius
        val drop = hangDrop(cx, cz, downX, downZ, tanSlope, downReach)
        if (drop > MAX_SIDE_DROP) return true
        val feetY = gripY - drop - DRAWN_HEIGHT
        val box = AABB(
            gripX + cx - halfWidth, feetY, gripZ + cz - halfWidth,
            gripX + cx + halfWidth, feetY + player.bbHeight, gripZ + cz + halfWidth,
        ).deflate(PROBE_DEFLATE)
        return !player.level().noCollision(player, box) || shipBlocks(player, box, cx, cz)
    }

    /**
     * True when a VS2 ship stops the sweep of [box] out from under the grip by ([cx], [cz]). level.noCollision does not
     * see ships, so this uses the same ship query as the hang move.
     */
    private fun shipBlocks(player: Player, box: AABB, cx: Double, cz: Double): Boolean {
        val start = box.move(-cx, 0.0, -cz)
        val move = Vec3(cx, 0.0, cz)
        val moved = EntityShipCollisionUtils.adjustEntityMovementForShipCollisions(player, move, start, player.level())
        val dx = moved.x - cx
        val dz = moved.z - cz
        return dx * dx + dz * dz > SHIP_BLOCK_SLACK * SHIP_BLOCK_SLACK
    }
}
