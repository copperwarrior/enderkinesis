package org.shipwrights.enderkinesis.item

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import org.joml.Quaterniond
import org.joml.Vector3d
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.core.internal.joints.VSFixedJoint
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.executeOrSchedule
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Tome of Elasticity behavior — a **6-DOF spring** ("Binding, but bouncy") between two orbs.
 *
 * Build is identical to [BindingTomeOrbBehavior]: a [VSFixedJoint] with the pose convention
 * that preserves the bodies' current relative pose at link time, so welding sprung does not
 * snap the bodies together. The only difference is [COMPLIANCE] — instead of the near-rigid
 * 1e-10 the Binding tome passes, we hand Krunch_Classic a much larger compliance, which it
 * interprets as 1/stiffness for the joint's internal spring. Translation AND rotation are
 * sprung, so pushing or twisting one body produces restoring force on both axes.
 *
 * Tried the proper [org.valkyrienskies.core.internal.joints.VSSpringJoint] once it landed
 * upstream, but it's a 1-DOF linear spring (rest length along the anchor line only) and
 * loses the "sprung weld" feel — bodies could rotate freely around the anchor. Back to the
 * compliance-on-FixedJoint approach.
 *
 * Krunch_Classic supplies implicit damping in the constraint solver, so the spring eventually
 * settles even though [VSFixedJoint] exposes no explicit damping parameter. Krunch_PhysX
 * ignores compliance entirely and treats every fixed joint as rigid — this tome essentially
 * degrades to Binding under PhysX.
 *
 * Pose-impossible cases (same-ship, world↔world) are refused at the item level.
 */
object ElasticityTomeOrbBehavior : TomeOrbBehavior {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_elasticity")

    private val LOG = LogUtils.getLogger()

    /** Spring compliance (1/stiffness). Lower = stiffer. Range reference:
     *   - 1e-10: near-rigid (Binding's value — essentially a weld; the practical floor —
     *           Krunch_Classic treats anything below this as identical to a fixed weld)
     *   - 1e-9 : stiff spring, slight visible give
     *   - 1e-8 : moderately stiff
     *   - 1e-7 : soft / floppy
     *  Tune directly if too soft / stiff. */
    private const val COMPLIANCE: Double = 1e-10

    override fun onLinked(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        tryBindSpring(level, sendBe, receiverPos)
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
                    "[EK elasticity] dropping {} → {} (distance > {} blocks)",
                    sendBe.blockPos, receiverPos, OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE,
                )
                sendBe.removeOutgoingLink(level, tomeKind, receiverPos)
                continue
            }
            if (sendBe.getJointId(tomeKind, receiverPos) < 0 &&
                !sendBe.isJointPending(tomeKind, receiverPos)) {
                tryBindSpring(level, sendBe, receiverPos)
            }
        }
    }

    // -----------------------------------------------------------------------------------------

    private fun tryBindSpring(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        if (sendBe.getJointId(tomeKind, receiverPos) >= 0) return
        if (sendBe.isJointPending(tomeKind, receiverPos)) return
        val sendPos = sendBe.blockPos
        // Server-level overloads of getShipManagingPos return ServerShip?, which exposes
        // `inertiaData` — needed for the mass-aware compliance below.
        val sendShip: ServerShip? = level.getShipManagingPos(sendPos) as? ServerShip
        val recvShip: ServerShip? = level.getShipManagingPos(receiverPos) as? ServerShip
        if (sendShip == null && recvShip == null) return                 // world↔world — defensive
        if (sendShip != null && recvShip != null && sendShip.id == recvShip.id) return  // same ship

        // Same preserve-current-relative-pose convention as Binding — pose0 anchors the send
        // orb in body 0's frame, pose1 anchors the *same world point* in body 1's frame with
        // a matching rotation. Spring is at rest at link time; bodies don't snap.
        val pose0Pos = Vector3d(sendPos.x + 0.5, sendPos.y + 0.5, sendPos.z + 0.5)
        val pose0Rot = Quaterniond()
        val sendWorld = Vector3d(pose0Pos)
        sendShip?.shipToWorld?.transformPosition(sendWorld)

        val pose1Pos = Vector3d(sendWorld)
        recvShip?.worldToShip?.transformPosition(pose1Pos)

        val pose1Rot = Quaterniond()
        if (sendShip != null) pose1Rot.mul(sendShip.transform.shipToWorldRotation)
        if (recvShip != null) {
            val w2s = Quaterniond()
            recvShip.transform.shipToWorldRotation.invert(w2s)
            val combined = Quaterniond(w2s).mul(pose1Rot)
            pose1Rot.set(combined)
        }

        val joint: VSJoint = VSFixedJoint(
            shipId0 = sendShip?.id,
            pose0 = VSJointPose(pose0Pos, pose0Rot),
            shipId1 = recvShip?.id,
            pose1 = VSJointPose(pose1Pos, pose1Rot),
            maxForceTorque = null,                          // unbreakable under load
            compliance = COMPLIANCE,
        )

        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        sendBe.markJointPending(tomeKind, receiverPos)
        gtpa.addJoint(joint, 0) { id: Int ->
            level.executeOrSchedule {
                val stillPending = sendBe.isJointPending(tomeKind, receiverPos)
                val stillLinked = sendBe.hasOutgoingLink(tomeKind, receiverPos)
                if (!stillPending || !stillLinked) {
                    LOG.info("[EK elasticity] dropping callback joint id={} (pair gone)", id)
                    gtpa.removeJoint(id)
                    sendBe.clearJointPending(tomeKind, receiverPos)
                } else {
                    sendBe.setJointId(tomeKind, receiverPos, id)
                    LOG.debug(
                        "[EK elasticity] sprung {} ↔ {} (compliance {}) as joint id={}",
                        sendBe.blockPos, receiverPos, COMPLIANCE, id,
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
