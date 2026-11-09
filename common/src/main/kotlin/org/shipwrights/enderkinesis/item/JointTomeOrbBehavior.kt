package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import org.joml.Quaterniond
import org.joml.Vector3d
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipManagingPos

/** A tome that holds one VS2 joint for each link. Joints are transient, so [serverTick] makes a missing one again. */
abstract class JointTomeOrbBehavior : TomeOrbBehavior {

    /** Makes the joint again after it went missing, for example after a chunk load. [distance] is the orb distance now. */
    protected abstract fun rebind(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos, distance: Double)

    override fun onUnlinking(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        releaseJoint(level, sendBe, receiverPos)
    }

    override fun serverTick(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity) {
        val receivers = sendBe.outgoingPeers(tomeKind)
        if (receivers.isEmpty()) return
        val sendCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, sendBe.blockPos) ?: return
        val maxSq = OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE * OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE
        for (receiverPos in receivers) {
            // An unloaded peer keeps its link; the joint comes back when the peer loads.
            OrbOfLinkingBlockEntity.loadedAt(level, receiverPos) ?: continue
            val recvCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, receiverPos) ?: continue
            val dx = recvCentre.x - sendCentre.x
            val dy = recvCentre.y - sendCentre.y
            val dz = recvCentre.z - sendCentre.z
            if (dx * dx + dy * dy + dz * dz > maxSq) {
                sendBe.dropOverDistance(level, tomeKind, receiverPos)
                continue
            }
            if (sendBe.getJointId(tomeKind, receiverPos) < 0 && !sendBe.isJointPending(tomeKind, receiverPos)) {
                rebind(level, sendBe, receiverPos, Math.sqrt(dx * dx + dy * dy + dz * dz))
            }
        }
    }

    /**
     * Adds the joint from [makeJoint], unless the link already has one or is waiting for one. A joint needs two
     * different bodies, so world to world and same-ship links get none. A null from [makeJoint] also adds none.
     */
    protected fun bind(
        level: ServerLevel,
        sendBe: OrbOfLinkingBlockEntity,
        receiverPos: BlockPos,
        makeJoint: (sendShip: Ship?, recvShip: Ship?) -> VSJoint?,
    ) {
        if (sendBe.getJointId(tomeKind, receiverPos) >= 0) return
        if (sendBe.isJointPending(tomeKind, receiverPos)) return
        val sendShip: Ship? = level.getShipManagingPos(sendBe.blockPos)
        val recvShip: Ship? = level.getShipManagingPos(receiverPos)
        if (sendShip == null && recvShip == null) return
        if (sendShip != null && recvShip != null && sendShip.id == recvShip.id) return
        val joint = makeJoint(sendShip, recvShip) ?: return
        sendBe.addLinkJoint(level, tomeKind, receiverPos, joint)
    }

    protected fun releaseJoint(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        val id = sendBe.clearJointId(tomeKind, receiverPos)
        sendBe.clearJointPending(tomeKind, receiverPos)
        if (id < 0) return
        ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId).removeJoint(id)
    }
}

/**
 * A joint tome whose joint keeps the orb distance from link time. The length is saved in the link metadata
 * under [lengthKey], so a rebind after a chunk load uses the same length.
 */
abstract class LengthJointTomeOrbBehavior(
    private val lengthKey: String,
    private val minLength: Float,
) : JointTomeOrbBehavior() {

    /** The joint between the two orb block centres, as [pose0] on the send body and [pose1] on the receive body. */
    protected abstract fun makeJoint(
        sendShipId: Long?, pose0: VSJointPose, recvShipId: Long?, pose1: VSJointPose, length: Float,
    ): VSJoint

    override fun onLinked(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        val sendCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, sendBe.blockPos)
        val recvCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, receiverPos)
        if (sendCentre == null || recvCentre == null) return
        val length = sendCentre.distanceTo(recvCentre).toFloat().coerceAtLeast(minLength)
        sendBe.setLinkMetadata(tomeKind, receiverPos, CompoundTag().apply { putFloat(lengthKey, length) })
        bindLength(level, sendBe, receiverPos, length)
    }

    override fun rebind(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos, distance: Double) {
        val stored = sendBe.getLinkMetadata(tomeKind, receiverPos)?.getFloat(lengthKey)
        bindLength(level, sendBe, receiverPos, stored?.takeIf { it > 0f } ?: distance.toFloat())
    }

    private fun bindLength(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos, length: Float) {
        val sendPos = sendBe.blockPos
        bind(level, sendBe, receiverPos) { sendShip, recvShip ->
            // A distance joint holds only the separation, so the pose rotations do not matter.
            makeJoint(
                sendShip?.id, VSJointPose(Vector3d(sendPos.x + 0.5, sendPos.y + 0.5, sendPos.z + 0.5), Quaterniond()),
                recvShip?.id, VSJointPose(Vector3d(receiverPos.x + 0.5, receiverPos.y + 0.5, receiverPos.z + 0.5), Quaterniond()),
                length,
            )
        }
    }
}
