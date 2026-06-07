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
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.core.internal.joints.VSRevoluteJoint
import org.valkyrienskies.core.internal.joints.VSRevoluteJoint.VSRevoluteDriveVelocity
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.executeOrSchedule
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Tome of Hinging behavior — builds a [VSRevoluteJoint] (1-DOF hinge) between the bodies
 * carrying the SEND and RECEIVE orbs. The orb-to-orb world line *is* the hinge axis:
 * bodies pivot freely around that line, with the two non-hinge translational/rotational
 * degrees of freedom locked solid by the joint.
 *
 *  - **Power 0** → free hinge: `driveFreeSpin = true`, no drive velocity, no force limit.
 *    Bodies swing freely around the axis like an unlocked door.
 *  - **Power N** → resistance hinge: `driveVelocity = 0` (drive tries to hold angular
 *    velocity at zero), `driveForceLimit = MAX_RESISTANCE_TORQUE × N/15`, `driveFreeSpin =
 *    false`. The drive applies a counter-torque proportional to power, up to the configured
 *    force limit — power 15 essentially locks the hinge.
 *
 * Re-binding (chunk load, signal change) recaptures the orb positions and rebuilds the axis
 * from scratch, same convention as [BindingTomeOrbBehavior] — joints are at rest in the
 * bodies' current configuration so reload doesn't snap anything.
 *
 * Pose-impossible pairings (same ship, world↔world) are refused at the item level; the
 * tryBind path defends against them anyway.
 */
object HingingTomeOrbBehavior : TomeOrbBehavior {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_hinging")

    private val LOG = LogUtils.getLogger()

    /** Compliance for the *non-rotational* DOFs of the hinge (3 translational, 2 of 3
     *  rotational). Matches Binding's near-rigid value so the hinge anchor doesn't drift. */
    private const val HINGE_COMPLIANCE: Double = 1e-10

    /** Maximum torque (N·m) the drive can apply to resist rotation, reached at redstone
     *  power 15. Below this, the drive tries to hold angular velocity at zero; bodies push
     *  past the resistance once they overcome this torque. Picked to feel "locked" for
     *  ship-scale moments of inertia at full power — adjust if too easy/hard to budge. */
    private const val MAX_RESISTANCE_TORQUE: Float = 1.0e6f

    /** PhysX revolute-joint convention: local X axis = rotation axis. Both pose rotations
     *  orient body-local +X along the captured world axis so the hinge spins around the
     *  orb-to-orb line in world space. */
    private val LOCAL_X = Vector3d(1.0, 0.0, 0.0)

    override fun onLinked(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        sendBe.inputSignal = readActiveFaceSignal(level, sendBe)
        tryBindHinge(level, sendBe, receiverPos, sendBe.inputSignal)
    }

    override fun onUnlinking(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        releaseJoint(level, sendBe, receiverPos)
    }

    override fun onNeighborChanged(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity) {
        val sampled = readActiveFaceSignal(level, sendBe)
        if (sampled == sendBe.inputSignal) return
        sendBe.inputSignal = sampled
        for (receiverPos in sendBe.outgoingPeers(tomeKind)) {
            releaseJoint(level, sendBe, receiverPos)
            tryBindHinge(level, sendBe, receiverPos, sampled)
        }
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
                    "[EK hinging] dropping {} → {} (distance > {} blocks)",
                    sendBe.blockPos, receiverPos, OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE,
                )
                sendBe.removeOutgoingLink(level, tomeKind, receiverPos)
                continue
            }
            if (sendBe.getJointId(tomeKind, receiverPos) < 0 &&
                !sendBe.isJointPending(tomeKind, receiverPos)) {
                tryBindHinge(level, sendBe, receiverPos, sendBe.inputSignal)
            }
        }
    }

    // -----------------------------------------------------------------------------------------

    private fun readActiveFaceSignal(level: ServerLevel, be: OrbOfLinkingBlockEntity): Int {
        val facing = be.facing
        val supportPos = be.blockPos.relative(facing)
        return level.getSignal(supportPos, facing)
    }

    private fun tryBindHinge(
        level: ServerLevel,
        sendBe: OrbOfLinkingBlockEntity,
        receiverPos: BlockPos,
        power: Int,
    ) {
        if (sendBe.getJointId(tomeKind, receiverPos) >= 0) return
        if (sendBe.isJointPending(tomeKind, receiverPos)) return
        val sendPos = sendBe.blockPos
        val sendShip: Ship? = level.getShipManagingPos(sendPos)
        val recvShip: Ship? = level.getShipManagingPos(receiverPos)
        if (sendShip == null && recvShip == null) return                  // world↔world — defensive
        if (sendShip != null && recvShip != null && sendShip.id == recvShip.id) return  // same ship

        // World-space anchor + axis at bind time. The axis is the orb-to-orb world line;
        // refuse degenerate cases (orbs overlapping somehow) to avoid NaN poses.
        val sendLocalPos = Vector3d(sendPos.x + 0.5, sendPos.y + 0.5, sendPos.z + 0.5)
        val recvLocalPos = Vector3d(receiverPos.x + 0.5, receiverPos.y + 0.5, receiverPos.z + 0.5)
        val sendWorld = Vector3d(sendLocalPos)
        sendShip?.shipToWorld?.transformPosition(sendWorld)
        val recvWorld = Vector3d(recvLocalPos)
        recvShip?.shipToWorld?.transformPosition(recvWorld)
        val worldAxis = Vector3d(recvWorld).sub(sendWorld)
        if (worldAxis.lengthSquared() < 1e-12) return                     // orbs coincide
        worldAxis.normalize()

        // Build ONE world-space hinge orientation that rotates LOCAL_X to worldAxis, then
        // transform it into each body's local frame. Per VS2's TestHingeBlock (and
        // SpawnShipCommand's revolute setup), both pose orientations must express the
        // *same* world-space joint frame — not just align on the X (rotation) axis but
        // also agree on the Y/Z secondary axes. Computing pose0Rot and pose1Rot
        // independently via `rotationTo(LOCAL_X, axisInLocalN)` (the previous approach)
        // matches on X but produces inconsistent Y/Z, so the constraint snaps the bodies
        // into alignment on bind — the "doesn't feel right" hinge motion. Sharing one
        // world orientation through both bodies' inverse-rotations puts pose0World ==
        // pose1World == worldOrientation, so the joint is at rest at bind time and only
        // free rotation around worldAxis is permitted afterwards.
        val worldOrientation = Quaterniond().rotationTo(LOCAL_X, worldAxis)

        val pose0Rot = Quaterniond()
        if (sendShip != null) {
            val w2sRot = Quaterniond()
            sendShip.transform.shipToWorldRotation.invert(w2sRot)
            pose0Rot.set(w2sRot).mul(worldOrientation)
        } else {
            pose0Rot.set(worldOrientation)
        }

        val pose1Pos = Vector3d(sendWorld)
        recvShip?.worldToShip?.transformPosition(pose1Pos)
        val pose1Rot = Quaterniond()
        if (recvShip != null) {
            val w2sRot = Quaterniond()
            recvShip.transform.shipToWorldRotation.invert(w2sRot)
            pose1Rot.set(w2sRot).mul(worldOrientation)
        } else {
            pose1Rot.set(worldOrientation)
        }

        // Drive: power 0 → free spin; power N → drive holds angular velocity at 0 with
        // force limit proportional to power.
        val clampedPower = power.coerceIn(0, 15)
        val driveVelocity: VSRevoluteDriveVelocity?
        val driveForceLimit: Float?
        val driveFreeSpin: Boolean
        if (clampedPower == 0) {
            driveVelocity = null
            driveForceLimit = null
            driveFreeSpin = true
        } else {
            driveVelocity = VSRevoluteDriveVelocity(velocity = 0.0f, autoWake = true)
            driveForceLimit = MAX_RESISTANCE_TORQUE * (clampedPower / 15.0f)
            driveFreeSpin = false
        }

        val joint: VSJoint = VSRevoluteJoint(
            shipId0 = (sendShip as? ServerShip)?.id,
            pose0 = VSJointPose(sendLocalPos, pose0Rot),
            shipId1 = (recvShip as? ServerShip)?.id,
            pose1 = VSJointPose(pose1Pos, pose1Rot),
            maxForceTorque = null,                            // unbreakable under load
            compliance = HINGE_COMPLIANCE,
            angularLimitPair = null,                          // no hard angular limits
            driveVelocity = driveVelocity,
            driveForceLimit = driveForceLimit,
            driveGearRatio = null,
            driveFreeSpin = driveFreeSpin,
        )

        val gtpa = ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId)
        sendBe.markJointPending(tomeKind, receiverPos)
        gtpa.addJoint(joint, 0) { id: Int ->
            level.executeOrSchedule {
                val stillPending = sendBe.isJointPending(tomeKind, receiverPos)
                val stillLinked = sendBe.hasOutgoingLink(tomeKind, receiverPos)
                if (!stillPending || !stillLinked) {
                    LOG.info("[EK hinging] dropping callback joint id={} (pair gone)", id)
                    gtpa.removeJoint(id)
                    sendBe.clearJointPending(tomeKind, receiverPos)
                } else {
                    sendBe.setJointId(tomeKind, receiverPos, id)
                    LOG.debug(
                        "[EK hinging] hinged {} ↔ {} (power {}, force {}) as joint id={}",
                        sendBe.blockPos, receiverPos, clampedPower, driveForceLimit, id,
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
