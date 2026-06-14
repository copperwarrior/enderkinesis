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
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.core.internal.joints.VSSpringJoint
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.executeOrSchedule
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Tome of Spring — [VSSpringJoint] per (send, receiver) pair. Rest length is captured at link
 * time and persisted on the SEND BE so chunk reload rebinds at the SAME length, not whatever
 * geometry happens to be at the time. Joints themselves are transient; [serverTick] rebinds.
 * Refused for world↔world and same-ship (need two distinct bodies).
 */
object SpringTomeOrbBehavior : TomeOrbBehavior {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_spring")

    private val LOG = LogUtils.getLogger()

    private const val STIFFNESS: Float = 50_000f

    /** ~1/4 critical damping for a 1-tonne body — visible oscillation before settling. */
    private const val DAMPING: Float = 2_500f

    /** Krunch_Classic constraint compliance floor; Krunch_PhysX ignores. */
    private const val SPRING_COMPLIANCE: Double = 1e-10

    /** NBT key under [OrbOfLinkingBlockEntity.getLinkMetadata] where rest length is stored. */
    private const val NBT_REST_LENGTH = "RestLength"

    /** Floor on rest length — zero-length springs fight numerical noise indefinitely. Two
     *  orbs placed at adjacent block positions (≈ 1 m apart) easily clear this. */
    private const val MIN_REST_LENGTH: Float = 0.5f

    override fun onLinked(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        val sendCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, sendBe.blockPos)
        val recvCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, receiverPos)
        if (sendCentre == null || recvCentre == null) return
        val length = sendCentre.distanceTo(recvCentre).toFloat().coerceAtLeast(MIN_REST_LENGTH)
        val data = CompoundTag().apply { putFloat(NBT_REST_LENGTH, length) }
        sendBe.setLinkMetadata(tomeKind, receiverPos, data)
        tryBindSpring(level, sendBe, receiverPos, length)
    }

    override fun onUnlinking(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        releaseJoint(level, sendBe, receiverPos)
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
                    "[EK spring] dropping {} → {} (distance > {} blocks)",
                    sendBe.blockPos, receiverPos, OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE,
                )
                sendBe.removeOutgoingLink(level, tomeKind, receiverPos)
                continue
            }
            if (sendBe.getJointId(tomeKind, receiverPos) < 0 &&
                !sendBe.isJointPending(tomeKind, receiverPos)) {
                val stored = sendBe.getLinkMetadata(tomeKind, receiverPos)?.getFloat(NBT_REST_LENGTH)
                val length = stored?.takeIf { it > 0f }
                    ?: Math.sqrt(dx * dx + dy * dy + dz * dz).toFloat()
                tryBindSpring(level, sendBe, receiverPos, length)
            }
        }
    }

    // -----------------------------------------------------------------------------------------

    private fun tryBindSpring(
        level: ServerLevel,
        sendBe: OrbOfLinkingBlockEntity,
        receiverPos: BlockPos,
        restLength: Float,
    ) {
        if (sendBe.getJointId(tomeKind, receiverPos) >= 0) return
        if (sendBe.isJointPending(tomeKind, receiverPos)) return
        val sendPos = sendBe.blockPos
        val sendShip: Ship? = level.getShipManagingPos(sendPos)
        val recvShip: Ship? = level.getShipManagingPos(receiverPos)
        if (sendShip == null && recvShip == null) return                  // world↔world — defensive
        if (sendShip != null && recvShip != null && sendShip.id == recvShip.id) return  // same ship

        // Anchor on each body = that orb's block centre. Spring is a scalar-distance constraint
        // along the line between the two anchors; rotation is unconstrained, so identity quats
        // suffice for the pose orientations.
        val pose0Pos = Vector3d(sendPos.x + 0.5, sendPos.y + 0.5, sendPos.z + 0.5)
        val pose1Pos = Vector3d(receiverPos.x + 0.5, receiverPos.y + 0.5, receiverPos.z + 0.5)

        val joint: VSJoint = VSSpringJoint(
            shipId0 = sendShip?.id,
            pose0 = VSJointPose(pose0Pos, Quaterniond()),
            shipId1 = recvShip?.id,
            pose1 = VSJointPose(pose1Pos, Quaterniond()),
            maxForceTorque = null,                              // unbreakable under load
            compliance = SPRING_COMPLIANCE,
            restLength = restLength.coerceAtLeast(MIN_REST_LENGTH),
            stiffness = STIFFNESS,
            damping = DAMPING,
        )

        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        sendBe.markJointPending(tomeKind, receiverPos)
        gtpa.addJoint(joint, 0) { id: Int ->
            level.executeOrSchedule {
                val stillPending = sendBe.isJointPending(tomeKind, receiverPos)
                val stillLinked = sendBe.hasOutgoingLink(tomeKind, receiverPos)
                if (!stillPending || !stillLinked) {
                    LOG.info("[EK spring] dropping callback joint id={} (pair gone)", id)
                    gtpa.removeJoint(id)
                    sendBe.clearJointPending(tomeKind, receiverPos)
                } else {
                    sendBe.setJointId(tomeKind, receiverPos, id)
                    LOG.debug(
                        "[EK spring] sprung {} ↔ {} (rest {}) as joint id={}",
                        sendBe.blockPos, receiverPos, restLength, id,
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
