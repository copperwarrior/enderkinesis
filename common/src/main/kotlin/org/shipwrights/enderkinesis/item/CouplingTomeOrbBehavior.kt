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
import org.valkyrienskies.core.internal.joints.VSDistanceJoint
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.executeOrSchedule
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Tome of Coupling — [VSDistanceJoint] with min == max so bodies are locked to an exact
 * orb-to-orb distance (rigid axle). Distinct from Chaining (max only, rope-like), Binding
 * (full 6-DOF weld), and Spring (soft oscillation). Length is captured at link time and
 * persisted on the SEND BE so chunk reload rebinds at the same length.
 */
object CouplingTomeOrbBehavior : TomeOrbBehavior {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_coupling")

    private val LOG = LogUtils.getLogger()

    /** Inverse stiffness floor for stable rigid distance constraints. */
    private const val COUPLING_COMPLIANCE: Double = 1e-10

    /** NBT key under [OrbOfLinkingBlockEntity.getLinkMetadata] for the captured length. */
    private const val NBT_COUPLING_LENGTH = "CouplingLength"

    /** Floor on coupling length — zero-length couplings fight numerical noise indefinitely.
     *  Two orbs placed at adjacent block positions easily clear this. */
    private const val MIN_COUPLING_LENGTH: Float = 0.5f

    override fun onLinked(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        val sendCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, sendBe.blockPos)
        val recvCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, receiverPos)
        if (sendCentre == null || recvCentre == null) return
        val length = sendCentre.distanceTo(recvCentre).toFloat().coerceAtLeast(MIN_COUPLING_LENGTH)
        val data = CompoundTag().apply { putFloat(NBT_COUPLING_LENGTH, length) }
        sendBe.setLinkMetadata(tomeKind, receiverPos, data)
        tryBindCoupling(level, sendBe, receiverPos, length)
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
                    "[EK coupling] dropping {} → {} (distance > {} blocks)",
                    sendBe.blockPos, receiverPos, OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE,
                )
                sendBe.removeOutgoingLink(level, tomeKind, receiverPos)
                continue
            }
            if (sendBe.getJointId(tomeKind, receiverPos) < 0 &&
                !sendBe.isJointPending(tomeKind, receiverPos)) {
                val stored = sendBe.getLinkMetadata(tomeKind, receiverPos)?.getFloat(NBT_COUPLING_LENGTH)
                val length = stored?.takeIf { it > 0f }
                    ?: Math.sqrt(dx * dx + dy * dy + dz * dz).toFloat()
                tryBindCoupling(level, sendBe, receiverPos, length)
            }
        }
    }

    // -----------------------------------------------------------------------------------------

    private fun tryBindCoupling(
        level: ServerLevel,
        sendBe: OrbOfLinkingBlockEntity,
        receiverPos: BlockPos,
        length: Float,
    ) {
        if (sendBe.getJointId(tomeKind, receiverPos) >= 0) return
        if (sendBe.isJointPending(tomeKind, receiverPos)) return
        val sendPos = sendBe.blockPos
        val sendShip: Ship? = level.getShipManagingPos(sendPos)
        val recvShip: Ship? = level.getShipManagingPos(receiverPos)
        if (sendShip == null && recvShip == null) return                 // world↔world — defensive
        if (sendShip != null && recvShip != null && sendShip.id == recvShip.id) return  // same ship

        // Anchor on each body = that orb's block centre. Distance joint constrains scalar
        // separation only; orientation is irrelevant, identity quats suffice.
        val pose0Pos = Vector3d(sendPos.x + 0.5, sendPos.y + 0.5, sendPos.z + 0.5)
        val pose1Pos = Vector3d(receiverPos.x + 0.5, receiverPos.y + 0.5, receiverPos.z + 0.5)
        val clamped = length.coerceAtLeast(MIN_COUPLING_LENGTH)

        val joint: VSJoint = VSDistanceJoint(
            shipId0 = sendShip?.id,
            pose0 = VSJointPose(pose0Pos, Quaterniond()),
            shipId1 = recvShip?.id,
            pose1 = VSJointPose(pose1Pos, Quaterniond()),
            maxForceTorque = null,                       // unbreakable under load
            compliance = COUPLING_COMPLIANCE,
            minDistance = clamped,                       // ← strong: both bounds locked to length
            maxDistance = clamped,                       // ← (rope-like Chaining sets only max)
            tolerance = null,
            stiffness = null,                            // rigid limit, no spring smoothing
            damping = null,
        )

        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        sendBe.markJointPending(tomeKind, receiverPos)
        gtpa.addJoint(joint, 0) { id: Int ->
            level.executeOrSchedule {
                val stillPending = sendBe.isJointPending(tomeKind, receiverPos)
                val stillLinked = sendBe.hasOutgoingLink(tomeKind, receiverPos)
                if (!stillPending || !stillLinked) {
                    LOG.info("[EK coupling] dropping callback joint id={} (pair gone)", id)
                    gtpa.removeJoint(id)
                    sendBe.clearJointPending(tomeKind, receiverPos)
                } else {
                    sendBe.setJointId(tomeKind, receiverPos, id)
                    LOG.debug(
                        "[EK coupling] coupled {} ↔ {} (len {}) as joint id={}",
                        sendBe.blockPos, receiverPos, clamped, id,
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
