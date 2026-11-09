package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.valkyrienskies.core.internal.joints.VSFixedJoint

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
object ElasticityTomeOrbBehavior : JointTomeOrbBehavior() {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_elasticity")


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

    override fun rebind(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos, distance: Double) {
        tryBindSpring(level, sendBe, receiverPos)
    }

    private fun tryBindSpring(level: ServerLevel, sendBe: OrbOfLinkingBlockEntity, receiverPos: BlockPos) {
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
                maxForceTorque = null,                          // unbreakable under load
                compliance = COMPLIANCE,
            )
        }
    }
}
