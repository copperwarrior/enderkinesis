package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.core.internal.joints.VSSpringJoint

/**
 * Tome of Spring — [VSSpringJoint] per (send, receiver) pair. Rest length is captured at link
 * time and persisted on the SEND BE so chunk reload rebinds at the SAME length, not whatever
 * geometry happens to be at the time. Joints themselves are transient; [serverTick] rebinds.
 * Refused for world↔world and same-ship (need two distinct bodies).
 */
object SpringTomeOrbBehavior : LengthJointTomeOrbBehavior(NBT_REST_LENGTH, MIN_REST_LENGTH) {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_spring")

    private const val STIFFNESS: Float = 50_000f

    /** ~1/4 critical damping for a 1-tonne body — visible oscillation before settling. */
    private const val DAMPING: Float = 2_500f

    /** Krunch_Classic constraint compliance floor; Krunch_PhysX ignores. */
    private const val SPRING_COMPLIANCE: Double = 1e-10

    override fun makeJoint(
        sendShipId: Long?, pose0: VSJointPose, recvShipId: Long?, pose1: VSJointPose, length: Float,
    ): VSJoint = VSSpringJoint(
        shipId0 = sendShipId,
        pose0 = pose0,
        shipId1 = recvShipId,
        pose1 = pose1,
        maxForceTorque = null,                              // unbreakable under load
        compliance = SPRING_COMPLIANCE,
        restLength = length.coerceAtLeast(MIN_REST_LENGTH),
        stiffness = STIFFNESS,
        damping = DAMPING,
    )
}

/** NBT key under [OrbOfLinkingBlockEntity.getLinkMetadata] where rest length is stored. */
private const val NBT_REST_LENGTH = "RestLength"

/** Floor on rest length — zero-length springs fight numerical noise indefinitely. Two
 *  orbs placed at adjacent block positions (≈ 1 m apart) easily clear this. */
private const val MIN_REST_LENGTH: Float = 0.5f
