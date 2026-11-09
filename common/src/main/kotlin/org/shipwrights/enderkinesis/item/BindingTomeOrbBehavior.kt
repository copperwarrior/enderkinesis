package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.valkyrienskies.core.internal.joints.VSFixedJoint

/**
 * Tome of Binding behavior — builds a [VSFixedJoint] between each (send, receiver) pair so the
 * two bodies become rigidly welded at their current relative pose.
 *
 *  - **World ↔ world**: no joint (the world body can't be welded to itself). The tome refuses
 *    these links at right-click time; the behavior is also defensive about it.
 *  - **World ↔ ship**: joint with `shipId0 = null` (world side) and `shipId1 = ship.id`
 *    (ship side). The ship is pinned in world space at the moment of linking; it can no
 *    longer translate or rotate relative to the world.
 *  - **Ship ↔ ship**: joint between the two ships' bodies. Each ship holds its current
 *    position and orientation relative to the other; pushing one drags the other.
 *
 * Pose convention picked to avoid the "welded orbs snap together" artefact of a naïve
 * `VSFixedJoint`:
 *  - `pose0` anchors at the **send orb's block centre** in body 0's local frame, with identity
 *    rotation relative to body 0.
 *  - `pose1` anchors at a point in body 1's local frame that **currently maps to the send
 *    orb's world position**, with a rotation that makes the two anchor frames coincide. So at
 *    link time the joint is already at rest — bodies don't move, the weld just freezes the
 *    relationship from that moment on.
 *
 * Joints are transient (not VS-serialised). Each [serverTick] on a SEND orb checks every
 * receiver: if the live joint is missing (chunk reload, peer just came back into range), it
 * rebinds. If the world-space distance between the two orbs exceeds
 * [OrbOfLinkingBlockEntity.MAX_LINK_DISTANCE] at runtime, the pair is dropped — matching the
 * generic 64-block rule the orb suite enforces.
 */
object BindingTomeOrbBehavior : JointTomeOrbBehavior() {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_binding")


    /** Inverse stiffness used for every weld. Matches VS2's `DEFAULT_COMPLIANCE` (the
     *  documented "typical" value for stable rigid joints). Krunch_PhysX ignores compliance
     *  entirely; Krunch_Classic uses 1e-10 as the practical floor before solver oscillation. */
    private const val WELD_COMPLIANCE: Double = 1e-10

    override fun onLinked(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        tryBindJoint(level, sendBe, receiverPos)
    }

    override fun rebind(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos, distance: Double) {
        tryBindJoint(level, sendBe, receiverPos)
    }

    /** Build the [VSFixedJoint] between [sendBe] and the orb at [receiverPos]. Refuses
     *  defensively if the pose is non-viable (same ship, world↔world); the tome's right-click
     *  flow should have rejected it already, but the BE's onLinked still calls us so we guard. */
    private fun tryBindJoint(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
        val sendPos = sendBe.blockPos
        bind(level, sendBe, receiverPos) { sendShip, recvShip ->
            val (pose0, pose1) = sendBe.linkJointPoses(tomeKind, receiverPos, sendShip?.id, recvShip?.id) {
                TomeJointPoses.weldAtRest(sendPos, sendShip, recvShip)
            } ?: return@bind null
            VSFixedJoint(
                shipId0 = sendShip?.id,
                pose0 = pose0,
                shipId1 = recvShip?.id,
                pose1 = pose1,
                maxForceTorque = null,                              // unbreakable under load — only the tome dismantles it
                compliance = WELD_COMPLIANCE,
            )
        }
    }
}
