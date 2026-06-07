package org.shipwrights.enderkinesis.item

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import org.joml.Quaterniond
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.internal.joints.VSDistanceJoint
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.executeOrSchedule
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Tome of Pushing behavior — the redstone-driven opposite of [PullingTomeOrbBehavior]. Each
 * (send, receive) pair is a [VSDistanceJoint] whose **minimum** distance is set by the SEND
 * orb's active-face signal, in blocks: power 8 → bodies must stay at least 8 b apart, power
 * 15 → at least 15 b. The upper limit floats up if needed so the constraint stays internally
 * consistent (`max = max(baseLength, min + 1)`).
 *
 *  - **Power 0** → `min = 0`, `max = baseLength`. Equivalent to a Chaining rope: slack
 *    inside, taut at the captured length. No pushing force.
 *  - **Power N** → `min = N b`, `max = max(baseLength, N + 1) b`. Bodies are forced at
 *    least N blocks apart, with a 1-block slack window.
 *
 * The joint itself is a rigid distance pair (null stiffness / damping, matches Chaining's
 * shape) but the **effective min is eased over time** toward the power-derived target,
 * the same way [PullingTomeOrbBehavior] eases its max. The eased `currentMin` chases the
 * target each tick at [EASE_RATE]; the joint is rebuilt when it changes by more than
 * [EASE_EPSILON]. Bodies are pushed apart smoothly instead of yanked on signal edges.
 *
 * Pose-impossible pairings (same ship, world↔world) are refused at the item level.
 */
object PushingTomeOrbBehavior : TomeOrbBehavior {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_pushing")

    private val LOG = LogUtils.getLogger()

    /** Krunch_Classic compliance for the distance limits. Matches VS2's DEFAULT_COMPLIANCE
     *  and Chaining's [ChainingTomeOrbBehavior]. */
    private const val PUSH_COMPLIANCE: Double = 1e-10

    /** Floor on the rope's base length captured at link time. */
    private const val MIN_BASE_LENGTH: Float = 0.5f

    /** Slack window above the push distance: `max - min` stays ≥ this so the solver always
     *  sees a non-degenerate range. */
    private const val MAX_OVER_MIN_SLACK: Float = 1.0f

    /** Fraction of remaining distance to target the eased `currentMin` covers per server
     *  tick. 0.15 → exponential approach with τ ≈ 7 ticks; >95 % settled in ~1 s. Matches
     *  the Pulling tome's rate for consistent feel across the two redstone joints. */
    private const val EASE_RATE: Float = 0.15f

    /** Snap-to-target threshold (blocks). */
    private const val EASE_EPSILON: Float = 0.01f

    /** NBT key for the per-link captured rope length. */
    private const val NBT_BASE_LENGTH = "BaseLength"

    /** NBT key for the per-link eased min distance. */
    private const val NBT_CURRENT_MIN = "CurrentMin"

    override fun onLinked(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        val sendCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, sendBe.blockPos)
        val recvCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, receiverPos)
        if (sendCentre == null || recvCentre == null) return
        val baseLength = sendCentre.distanceTo(recvCentre).toFloat().coerceAtLeast(MIN_BASE_LENGTH)
        sendBe.inputSignal = readActiveFaceSignal(level, sendBe)
        val initialMin = targetMin(sendBe.inputSignal)
        writeLinkData(sendBe, receiverPos, baseLength, initialMin)
        tryBindPush(level, sendBe, receiverPos, baseLength, initialMin)
    }

    override fun onUnlinking(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        releaseJoint(level, sendBe, receiverPos)
    }

    override fun onNeighborChanged(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity) {
        val sampled = readActiveFaceSignal(level, sendBe)
        if (sampled == sendBe.inputSignal) return
        sendBe.inputSignal = sampled
        // No immediate rebuild — serverTick eases `currentMin` toward the new target.
    }

    override fun onLoad(level: ServerLevel, be: OrbOfLinkingBlockEntity) {
        if (be.outgoingPeers(tomeKind).isNotEmpty()) {
            be.inputSignal = readActiveFaceSignal(level, be)
        }
    }

    override fun serverTick(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity) {
        val receivers = sendBe.outgoingPeers(tomeKind)
        if (receivers.isEmpty()) return
        val sendCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, sendBe.blockPos) ?: return
        val maxSq = OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE * OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE
        for (receiverPos in receivers) {
            val recvBe = level.getBlockEntity(receiverPos) as? OrbOfLinkingBlockEntity ?: continue
            val recvCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, receiverPos) ?: continue
            val dx = recvCentre.x - sendCentre.x
            val dy = recvCentre.y - sendCentre.y
            val dz = recvCentre.z - sendCentre.z
            if (dx * dx + dy * dy + dz * dz > maxSq) {
                LOG.info(
                    "[EK pushing] dropping {} → {} (distance > {} blocks, joint may have failed)",
                    sendBe.blockPos, receiverPos, OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE,
                )
                sendBe.removeOutgoingLink(level, tomeKind, receiverPos)
                continue
            }
            val baseLength = storedBaseLength(sendBe, receiverPos) ?: continue
            val current = storedCurrentMin(sendBe, receiverPos) ?: targetMin(sendBe.inputSignal)
            val target = targetMin(sendBe.inputSignal)
            val next = easeToward(current, target)
            val jointGone = sendBe.getJointId(tomeKind, receiverPos) < 0 &&
                !sendBe.isJointPending(tomeKind, receiverPos)
            if (next != current) {
                writeLinkData(sendBe, receiverPos, baseLength, next)
                releaseJoint(level, sendBe, receiverPos)
                tryBindPush(level, sendBe, receiverPos, baseLength, next)
            } else if (jointGone) {
                tryBindPush(level, sendBe, receiverPos, baseLength, current)
            }
        }
    }

    // -----------------------------------------------------------------------------------------

    private fun storedBaseLength(sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos): Float? =
        sendBe.getLinkMetadata(tomeKind, receiverPos)?.getFloat(NBT_BASE_LENGTH)?.takeIf { it > 0f }

    private fun storedCurrentMin(sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos): Float? =
        sendBe.getLinkMetadata(tomeKind, receiverPos)?.let { tag ->
            if (tag.contains(NBT_CURRENT_MIN)) tag.getFloat(NBT_CURRENT_MIN) else null
        }

    private fun writeLinkData(
        sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos,
        baseLength: Float, currentMin: Float,
    ) {
        val tag = CompoundTag().apply {
            putFloat(NBT_BASE_LENGTH, baseLength)
            putFloat(NBT_CURRENT_MIN, currentMin)
        }
        sendBe.setLinkMetadata(tomeKind, receiverPos, tag)
    }

    private fun readActiveFaceSignal(level: ServerLevel, be: OrbOfLinkingBlockEntity): Int {
        val facing = be.facing
        val supportPos = be.blockPos.relative(facing)
        return level.getSignal(supportPos, facing)
    }

    /** Power-derived target for the minimum distance — one block per signal level. */
    private fun targetMin(power: Int): Float = power.coerceIn(0, 15).toFloat()

    /** Exponential ease toward [target] by [EASE_RATE] of the remaining distance; snap
     *  within [EASE_EPSILON]. */
    private fun easeToward(current: Float, target: Float): Float {
        val diff = target - current
        if (Math.abs(diff) < EASE_EPSILON) return target
        return current + diff * EASE_RATE
    }

    private fun tryBindPush(
        level: ServerLevel,
        sendBe: OrbOfLinkingBlockEntity,
        receiverPos: BlockPos,
        baseLength: Float,
        minDist: Float,
    ) {
        if (sendBe.getJointId(tomeKind, receiverPos) >= 0) return
        if (sendBe.isJointPending(tomeKind, receiverPos)) return
        val sendPos = sendBe.blockPos
        val sendShip: ServerShip? = level.getShipManagingPos(sendPos) as? ServerShip
        val recvShip: ServerShip? = level.getShipManagingPos(receiverPos) as? ServerShip
        if (sendShip == null && recvShip == null) return                  // world↔world — defensive
        if (sendShip != null && recvShip != null && sendShip.id == recvShip.id) return  // same ship

        // Derive max from the eased min. Preserves the rope's captured cap when push is
        // shorter than the cap; expands the cap with a slack window when push exceeds it.
        val maxDist = Math.max(baseLength, minDist + MAX_OVER_MIN_SLACK)

        val pose0Pos = Vector3d(sendPos.x + 0.5, sendPos.y + 0.5, sendPos.z + 0.5)
        val pose1Pos = Vector3d(receiverPos.x + 0.5, receiverPos.y + 0.5, receiverPos.z + 0.5)

        val joint: VSJoint = VSDistanceJoint(
            shipId0 = sendShip?.id,
            pose0 = VSJointPose(pose0Pos, Quaterniond()),
            shipId1 = recvShip?.id,
            pose1 = VSJointPose(pose1Pos, Quaterniond()),
            maxForceTorque = null,
            compliance = PUSH_COMPLIANCE,
            minDistance = minDist,
            maxDistance = maxDist,
            // stiffness/damping null → rigid distance limits, matching Chaining.
        )

        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        sendBe.markJointPending(tomeKind, receiverPos)
        gtpa.addJoint(joint, 0) { id: Int ->
            level.executeOrSchedule {
                val stillPending = sendBe.isJointPending(tomeKind, receiverPos)
                val stillLinked = sendBe.hasOutgoingLink(tomeKind, receiverPos)
                if (!stillPending || !stillLinked) {
                    LOG.info("[EK pushing] dropping callback joint id={} (pair gone)", id)
                    gtpa.removeJoint(id)
                    sendBe.clearJointPending(tomeKind, receiverPos)
                } else {
                    sendBe.setJointId(tomeKind, receiverPos, id)
                    LOG.debug(
                        "[EK pushing] pushed {} ↔ {} (min {}, max {}) as joint id={}",
                        sendBe.blockPos, receiverPos, minDist, maxDist, id,
                    )
                }
            }
        }
    }

    private fun releaseJoint(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        val id = sendBe.clearJointId(tomeKind, receiverPos)
        sendBe.clearJointPending(tomeKind, receiverPos)
        if (id < 0) return
        ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId).removeJoint(id)
    }
}
