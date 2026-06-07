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
 * Tome of Pulling behavior — a redstone-driven winch. Each (send, receive) pair is a
 * [VSDistanceJoint] like Chaining's rope, but the maximum length contracts as the SEND
 * orb's active-face redstone signal climbs:
 *
 *  - **Power 0** → `max = baseLength` (the rope's full length, equal to the distance at
 *    link time — exactly like a Chaining link, slack inside, taut at the limit).
 *  - **Power 15** → `max = MIN_PULL_LENGTH` (~0.5 b — the bodies converge as close as
 *    possible without zero-length numerical instability).
 *  - **Power n** → `max = baseLength × (1 − n/15)`, floored at `MIN_PULL_LENGTH`.
 *
 * The joint itself is a rigid distance limit (null stiffness / damping, matches
 * Chaining's shape) but the **effective max length is eased over time** toward the
 * power-derived target instead of snapping. Each server tick the per-link `currentMax`
 * moves a fraction [EASE_RATE] of the way toward the target; when it changes by more
 * than [EASE_EPSILON] the joint is rebuilt with the new length. The bodies' motion is
 * therefore smooth as a winch reels in / pays out, rather than yanking on signal edges.
 *
 * Pose-impossible pairings (same ship, world↔world) are refused at the item level.
 */
object PullingTomeOrbBehavior : TomeOrbBehavior {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_pulling")

    private val LOG = LogUtils.getLogger()

    /** Krunch_Classic compliance for the distance limit. Matches VS2's DEFAULT_COMPLIANCE
     *  and Chaining's [ChainingTomeOrbBehavior]. */
    private const val PULL_COMPLIANCE: Double = 1e-10

    /** Floor on the rope's *maximum* length even at full power. Zero-length distance joints
     *  fight numerical noise; 0.5 b is small enough to feel "fully reeled in" without the
     *  solver pushing the bodies through each other. */
    private const val MIN_PULL_LENGTH: Float = 0.5f

    /** Floor on baseLength stored at link time. */
    private const val MIN_BASE_LENGTH: Float = 0.5f

    /** Fraction of remaining distance to target the eased `currentMax` covers per server
     *  tick. 0.15 → exponential approach with τ ≈ 7 ticks; >95 % settled in ~1 s. */
    private const val EASE_RATE: Float = 0.15f

    /** Snap-to-target threshold (blocks) — when `|target − current| < this`, jump current
     *  straight to target so we don't trickle forever on float arithmetic noise. */
    private const val EASE_EPSILON: Float = 0.01f

    /** NBT key for the per-link captured rope length. */
    private const val NBT_BASE_LENGTH = "BaseLength"

    /** NBT key for the per-link eased max length (the value the joint is actually built
     *  with this tick — chases the power-derived target via [EASE_RATE]). */
    private const val NBT_CURRENT_MAX = "CurrentMax"

    override fun onLinked(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        // Capture the rope's base length once, at link time. Persist on the BE before
        // binding so a crash between metadata-write and joint-callback still leaves the
        // length intact for rebind. Initial `currentMax` snaps to the power-derived
        // target (no ease on the *first* bind — easing kicks in on later signal changes).
        val sendCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, sendBe.blockPos)
        val recvCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, receiverPos)
        if (sendCentre == null || recvCentre == null) return
        val baseLength = sendCentre.distanceTo(recvCentre).toFloat().coerceAtLeast(MIN_BASE_LENGTH)
        sendBe.inputSignal = readActiveFaceSignal(level, sendBe)
        val initialMax = effectiveMax(baseLength, sendBe.inputSignal)
        writeLinkData(sendBe, receiverPos, baseLength, initialMax)
        tryBindPull(level, sendBe, receiverPos, initialMax)
    }

    override fun onUnlinking(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        releaseJoint(level, sendBe, receiverPos)
    }

    override fun onNeighborChanged(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity) {
        val sampled = readActiveFaceSignal(level, sendBe)
        if (sampled == sendBe.inputSignal) return       // nothing changed
        sendBe.inputSignal = sampled
        // No immediate rebuild — serverTick eases `currentMax` toward the new target
        // over many ticks for a smooth winch motion.
    }

    override fun onLoad(level: ServerLevel, be: OrbOfLinkingBlockEntity) {
        if (be.outgoingPeers(tomeKind).isNotEmpty()) {
            be.inputSignal = readActiveFaceSignal(level, be)
        }
        // Joint rebind on chunk load handled by serverTick — same as Chaining/Elasticity.
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
                    "[EK pulling] dropping {} → {} (distance > {} blocks, joint may have failed)",
                    sendBe.blockPos, receiverPos, OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE,
                )
                sendBe.removeOutgoingLink(level, tomeKind, receiverPos)
                continue
            }
            val baseLength = storedBaseLength(sendBe, receiverPos) ?: continue
            val current = storedCurrentMax(sendBe, receiverPos) ?: effectiveMax(baseLength, sendBe.inputSignal)
            val target = effectiveMax(baseLength, sendBe.inputSignal)
            val next = easeToward(current, target)
            val jointGone = sendBe.getJointId(tomeKind, receiverPos) < 0 &&
                !sendBe.isJointPending(tomeKind, receiverPos)
            if (next != current) {
                writeLinkData(sendBe, receiverPos, baseLength, next)
                releaseJoint(level, sendBe, receiverPos)
                tryBindPull(level, sendBe, receiverPos, next)
            } else if (jointGone) {
                // Steady-state target, but the joint vanished (chunk-load rebind) — bring
                // it back at the current eased value.
                tryBindPull(level, sendBe, receiverPos, current)
            }
        }
    }

    // -----------------------------------------------------------------------------------------

    private fun storedBaseLength(sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos): Float? =
        sendBe.getLinkMetadata(tomeKind, receiverPos)?.getFloat(NBT_BASE_LENGTH)?.takeIf { it > 0f }

    private fun storedCurrentMax(sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos): Float? =
        sendBe.getLinkMetadata(tomeKind, receiverPos)?.let { tag ->
            if (tag.contains(NBT_CURRENT_MAX)) tag.getFloat(NBT_CURRENT_MAX) else null
        }

    private fun writeLinkData(
        sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos,
        baseLength: Float, currentMax: Float,
    ) {
        val tag = CompoundTag().apply {
            putFloat(NBT_BASE_LENGTH, baseLength)
            putFloat(NBT_CURRENT_MAX, currentMax)
        }
        sendBe.setLinkMetadata(tomeKind, receiverPos, tag)
    }

    /** Read the redstone signal arriving on the orb's active face. */
    private fun readActiveFaceSignal(level: ServerLevel, be: OrbOfLinkingBlockEntity): Int {
        val facing = be.facing
        val supportPos = be.blockPos.relative(facing)
        return level.getSignal(supportPos, facing)
    }

    /** `max = baseLength × (1 - power/15)`, floored at [MIN_PULL_LENGTH]. */
    private fun effectiveMax(baseLength: Float, power: Int): Float {
        val scale = (1.0 - (power.coerceIn(0, 15)) / 15.0).coerceAtLeast(0.0)
        return (baseLength * scale).toFloat().coerceAtLeast(MIN_PULL_LENGTH)
    }

    /** Exponential ease — moves [current] toward [target] by [EASE_RATE] of the remaining
     *  distance. Snaps to target when within [EASE_EPSILON] so we don't drift forever on
     *  float arithmetic noise. */
    private fun easeToward(current: Float, target: Float): Float {
        val diff = target - current
        if (Math.abs(diff) < EASE_EPSILON) return target
        return current + diff * EASE_RATE
    }

    private fun tryBindPull(
        level: ServerLevel,
        sendBe: OrbOfLinkingBlockEntity,
        receiverPos: BlockPos,
        maxLength: Float,
    ) {
        if (sendBe.getJointId(tomeKind, receiverPos) >= 0) return
        if (sendBe.isJointPending(tomeKind, receiverPos)) return
        val sendPos = sendBe.blockPos
        val sendShip: ServerShip? = level.getShipManagingPos(sendPos) as? ServerShip
        val recvShip: ServerShip? = level.getShipManagingPos(receiverPos) as? ServerShip
        if (sendShip == null && recvShip == null) return                  // world↔world — defensive
        if (sendShip != null && recvShip != null && sendShip.id == recvShip.id) return  // same ship

        val pose0Pos = Vector3d(sendPos.x + 0.5, sendPos.y + 0.5, sendPos.z + 0.5)
        val pose1Pos = Vector3d(receiverPos.x + 0.5, receiverPos.y + 0.5, receiverPos.z + 0.5)

        val joint: VSJoint = VSDistanceJoint(
            shipId0 = sendShip?.id,
            pose0 = VSJointPose(pose0Pos, Quaterniond()),
            shipId1 = recvShip?.id,
            pose1 = VSJointPose(pose1Pos, Quaterniond()),
            maxForceTorque = null,
            compliance = PULL_COMPLIANCE,
            minDistance = null,                              // pull-only — slack inside
            maxDistance = maxLength,
            // stiffness/damping null → rigid distance limit at maxLength, matching Chaining.
        )

        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        sendBe.markJointPending(tomeKind, receiverPos)
        gtpa.addJoint(joint, 0) { id: Int ->
            level.executeOrSchedule {
                val stillPending = sendBe.isJointPending(tomeKind, receiverPos)
                val stillLinked = sendBe.hasOutgoingLink(tomeKind, receiverPos)
                if (!stillPending || !stillLinked) {
                    LOG.info("[EK pulling] dropping callback joint id={} (pair gone)", id)
                    gtpa.removeJoint(id)
                    sendBe.clearJointPending(tomeKind, receiverPos)
                } else {
                    sendBe.setJointId(tomeKind, receiverPos, id)
                    LOG.debug(
                        "[EK pulling] pulled {} ↔ {} (max {}) as joint id={}",
                        sendBe.blockPos, receiverPos, maxLength, id,
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
