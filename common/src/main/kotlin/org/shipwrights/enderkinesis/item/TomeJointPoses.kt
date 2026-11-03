package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.internal.joints.VSJointPose

internal object TomeJointPoses {

    /** pose0 = the send orb centre in body 0; pose1 = the same world point and rotation in
     *  body 1, so the weld is at rest when it is made and the bodies do not snap. */
    fun weldAtRest(sendPos: BlockPos, sendShip: Ship?, recvShip: Ship?): Pair<VSJointPose, VSJointPose> {
        val pose0Pos = Vector3d(sendPos.x + 0.5, sendPos.y + 0.5, sendPos.z + 0.5)
        val sendWorld = Vector3d(pose0Pos)
        sendShip?.shipToWorld?.transformPosition(sendWorld)

        val pose1Pos = Vector3d(sendWorld)
        recvShip?.worldToShip?.transformPosition(pose1Pos)

        // body1_rot * pose1_rot = body0_rot, as pose0_rot is identity; a world body has identity.
        val pose1Rot = Quaterniond()
        if (sendShip != null) pose1Rot.set(sendShip.transform.shipToWorldRotation)
        if (recvShip != null) {
            val w2s = Quaterniond()
            recvShip.transform.shipToWorldRotation.invert(w2s)
            pose1Rot.premul(w2s)
        }
        return VSJointPose(pose0Pos, Quaterniond()) to VSJointPose(pose1Pos, pose1Rot)
    }
}
