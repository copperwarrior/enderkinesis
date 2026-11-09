package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.valkyrienskies.core.internal.joints.VSDistanceJoint
import org.valkyrienskies.core.internal.joints.VSJoint
import org.valkyrienskies.core.internal.joints.VSJointPose

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
object ChainingTomeOrbBehavior : LengthJointTomeOrbBehavior(NBT_ROPE_LENGTH, MIN_ROPE_LENGTH) {

    override val tomeKind: ResourceLocation = EnderkinesisMod.id("tome_of_chaining")

    /** Inverse stiffness for the distance limit. Matches VS2's `DEFAULT_COMPLIANCE`. */
    private const val ROPE_COMPLIANCE: Double = 1e-10

    override fun makeJoint(
        sendShipId: Long?, pose0: VSJointPose, recvShipId: Long?, pose1: VSJointPose, length: Float,
    ): VSJoint = VSDistanceJoint(
        shipId0 = sendShipId,
        pose0 = pose0,
        shipId1 = recvShipId,
        pose1 = pose1,
        maxForceTorque = null,                       // unbreakable under load
        compliance = ROPE_COMPLIANCE,
        minDistance = null,                          // fully slack inside the range
        maxDistance = length.coerceAtLeast(MIN_ROPE_LENGTH),
        // stiffness/damping null → rigid distance limit at maxDistance, no spring smoothing.
    )
}

/** NBT key under [OrbOfLinkingBlockEntity.getLinkMetadata] where the rope length is stored. */
private const val NBT_ROPE_LENGTH = "RopeLength"

/** Floor on rope length — zero-length ropes would constantly fight numerical noise. Two
 *  orbs placed at adjacent block positions (≈ 1 m apart) easily clear this. */
private const val MIN_ROPE_LENGTH: Float = 0.5f
