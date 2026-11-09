package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.valkyrienskies.core.internal.joints.VSDistanceJoint
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointPose

/**
 * Tome of Coupling — [VSDistanceJoint] with min == max so bodies are locked to an exact
 * orb-to-orb distance (rigid axle). Distinct from Chaining (max only, rope-like), Binding
 * (full 6-DOF weld), and Spring (soft oscillation). Length is captured at link time and
 * persisted on the SEND BE so chunk reload rebinds at the same length.
 */
object CouplingTomeOrbBehavior : LengthJointTomeOrbBehavior(NBT_COUPLING_LENGTH, MIN_COUPLING_LENGTH) {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_coupling")

    /** Inverse stiffness floor for stable rigid distance constraints. */
    private const val COUPLING_COMPLIANCE: Double = 1e-10

    override fun makeJoint(
        sendShipId: Long?, pose0: VSJointPose, recvShipId: Long?, pose1: VSJointPose, length: Float,
    ): VSJoint {
        val clamped = length.coerceAtLeast(MIN_COUPLING_LENGTH)
        return VSDistanceJoint(
            shipId0 = sendShipId,
            pose0 = pose0,
            shipId1 = recvShipId,
            pose1 = pose1,
            maxForceTorque = null,                       // unbreakable under load
            compliance = COUPLING_COMPLIANCE,
            minDistance = clamped,                       // ← strong: both bounds locked to length
            maxDistance = clamped,                       // ← (rope-like Chaining sets only max)
            tolerance = null,
            stiffness = null,                            // rigid limit, no spring smoothing
            damping = null,
        )
    }
}

/** NBT key under [OrbOfLinkingBlockEntity.getLinkMetadata] for the captured length. */
private const val NBT_COUPLING_LENGTH = "CouplingLength"

/** Floor on coupling length — zero-length couplings fight numerical noise indefinitely.
 *  Two orbs placed at adjacent block positions easily clear this. */
private const val MIN_COUPLING_LENGTH: Float = 0.5f
