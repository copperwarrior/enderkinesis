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
 * Tome of Chaining behavior — builds a loose [VSDistanceJoint] (rope-style) between each
 * (send, receiver) pair. The joint is a one-sided distance limit:
 *  - `minDistance = null` → bodies can come arbitrarily close (rope can fully relax).
 *  - `maxDistance = ropeLength` → bodies can never separate further than the rope's length.
 *
 *  Pose-impossible pairings are refused at the item level — same-ship has no body to constrain
 *  against itself, and world↔world has no body at all.
 *
 *  **Rope length** is captured at the moment of linking — the current world-space distance
 *  between the two orbs (with ship transforms applied). Once set, the length is persisted on
 *  the SEND BE's [OrbOfLinkingBlockEntity.getLinkMetadata] scratchpad so the joint can be
 *  rebound to the same length after a chunk reload (joints are transient; the length isn't).
 *  If you want a longer chain, place the orbs farther apart.
 *
 *  The joint is rigid at the limit (compliance only — no spring smoothing), mirroring VMod's
 *  Rope tool. VS2's `DEFAULT_COMPLIANCE` (1e-10) is the practical floor for stable rigid
 *  joints in Krunch_Classic; PhysX ignores compliance entirely.
 */
object ChainingTomeOrbBehavior : TomeOrbBehavior {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_chaining")

    private val LOG = LogUtils.getLogger()

    /** Inverse stiffness for the distance limit. Matches VS2's `DEFAULT_COMPLIANCE`. */
    private const val ROPE_COMPLIANCE: Double = 1e-10

    /** NBT key under [OrbOfLinkingBlockEntity.getLinkMetadata] where the rope length is stored. */
    private const val NBT_ROPE_LENGTH = "RopeLength"

    override fun onLinked(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        val sendCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, sendBe.blockPos)
        val recvCentre = OrbOfLinkingBlockEntity.orbWorldCenter(level, receiverPos)
        if (sendCentre == null || recvCentre == null) return
        val length = sendCentre.distanceTo(recvCentre).toFloat().coerceAtLeast(MIN_ROPE_LENGTH)
        val data = CompoundTag().apply { putFloat(NBT_ROPE_LENGTH, length) }
        sendBe.setLinkMetadata(tomeKind, receiverPos, data)
        tryBindRope(level, sendBe, receiverPos, length)
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
                    "[EK chaining] dropping {} → {} (distance > {} blocks, joint may have failed)",
                    sendBe.blockPos, receiverPos, OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE,
                )
                sendBe.removeOutgoingLink(level, tomeKind, receiverPos)
                continue
            }
            if (sendBe.getJointId(tomeKind, receiverPos) < 0 &&
                !sendBe.isJointPending(tomeKind, receiverPos)) {
                val storedLength = sendBe.getLinkMetadata(tomeKind, receiverPos)?.getFloat(NBT_ROPE_LENGTH)
                val length = storedLength?.takeIf { it > 0f }
                    ?: Math.sqrt(dx * dx + dy * dy + dz * dz).toFloat()
                tryBindRope(level, sendBe, receiverPos, length)
            }
        }
    }

    // -----------------------------------------------------------------------------------------

    private fun tryBindRope(
        level: ServerLevel,
        sendBe: OrbOfLinkingBlockEntity,
        receiverPos: BlockPos,
        ropeLength: Float,
    ) {
        if (sendBe.getJointId(tomeKind, receiverPos) >= 0) return
        if (sendBe.isJointPending(tomeKind, receiverPos)) return
        val sendPos = sendBe.blockPos
        val sendShip: Ship? = level.getShipManagingPos(sendPos)
        val recvShip: Ship? = level.getShipManagingPos(receiverPos)
        if (sendShip == null && recvShip == null) return                 // world↔world — defensive
        if (sendShip != null && recvShip != null && sendShip.id == recvShip.id) return  // same ship

        // Pose anchor on each body = that orb's block centre. Rotation is irrelevant for a
        // distance joint (it constrains only scalar separation), so identity quats suffice.
        val pose0Pos = Vector3d(sendPos.x + 0.5, sendPos.y + 0.5, sendPos.z + 0.5)
        val pose1Pos = Vector3d(receiverPos.x + 0.5, receiverPos.y + 0.5, receiverPos.z + 0.5)

        val joint: VSJoint = VSDistanceJoint(
            shipId0 = sendShip?.id,
            pose0 = VSJointPose(pose0Pos, Quaterniond()),
            shipId1 = recvShip?.id,
            pose1 = VSJointPose(pose1Pos, Quaterniond()),
            maxForceTorque = null,                       // unbreakable under load
            compliance = ROPE_COMPLIANCE,
            minDistance = null,                          // fully slack inside the range
            maxDistance = ropeLength.coerceAtLeast(MIN_ROPE_LENGTH),
            // stiffness/damping null → rigid distance limit at maxDistance, no spring smoothing.
        )

        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        sendBe.markJointPending(tomeKind, receiverPos)
        gtpa.addJoint(joint, 0) { id: Int ->
            level.executeOrSchedule {
                val stillPending = sendBe.isJointPending(tomeKind, receiverPos)
                val stillLinked = sendBe.hasOutgoingLink(tomeKind, receiverPos)
                if (!stillPending || !stillLinked) {
                    LOG.info("[EK chaining] dropping callback joint id={} (pair gone)", id)
                    gtpa.removeJoint(id)
                    sendBe.clearJointPending(tomeKind, receiverPos)
                } else {
                    sendBe.setJointId(tomeKind, receiverPos, id)
                    LOG.debug(
                        "[EK chaining] roped {} ↔ {} (len {}) as joint id={}",
                        sendBe.blockPos, receiverPos, ropeLength, id,
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

    /** Floor on rope length — zero-length ropes would constantly fight numerical noise. Two
     *  orbs placed at adjacent block positions (≈ 1 m apart) easily clear this. */
    private const val MIN_ROPE_LENGTH: Float = 0.5f
}
