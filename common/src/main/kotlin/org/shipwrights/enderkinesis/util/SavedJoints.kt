package org.shipwrights.enderkinesis.util

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.internal.joints.VSJointPose
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.core.api.util.GameTickOnly

/**
 * Joints made with `serialized()` go into VS-core's persisted joint store, which keeps
 * their ids across a reload and holds a jointed group still until all of it loads.
 */
@OptIn(GameTickOnly::class)
object SavedJoints {

    /**
     * Returns [id] when VS2 restored it between these two bodies (null = world), else -1.
     * A saved joint that keeps one of the two bodies is ours, left by a move or a split
     * that changed the other body, so it is removed.
     */
    fun reclaim(level: ServerLevel, id: Int, shipId0: Long?, shipId1: Long?): Int {
        if (id < 0) return -1
        val joint = level.shipObjectWorld.persistedJointStore.getJoint(id) ?: return -1
        if (joint.shipId0 == shipId0 && joint.shipId1 == shipId1) return id
        if (joint.shipId0 == shipId0 || joint.shipId1 == shipId1) {
            ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId).removeJoint(id)
        }
        return -1
    }

    /** Poses in [tag] for these bodies, or null when none are saved or the bodies changed. */
    fun readPoses(tag: CompoundTag, shipId0: Long?, shipId1: Long?): Pair<VSJointPose, VSJointPose>? {
        if (!tag.contains(NBT_POSE0)) return null
        if (readShip(tag, NBT_SHIP0) != shipId0 || readShip(tag, NBT_SHIP1) != shipId1) return null
        return readPose(tag.getCompound(NBT_POSE0)) to readPose(tag.getCompound(NBT_POSE1))
    }

    fun writePoses(tag: CompoundTag, shipId0: Long?, shipId1: Long?, poses: Pair<VSJointPose, VSJointPose>) {
        writeShip(tag, NBT_SHIP0, shipId0)
        writeShip(tag, NBT_SHIP1, shipId1)
        tag.put(NBT_POSE0, writePose(poses.first))
        tag.put(NBT_POSE1, writePose(poses.second))
    }

    private fun readShip(tag: CompoundTag, key: String): Long? = if (tag.contains(key)) tag.getLong(key) else null

    private fun writeShip(tag: CompoundTag, key: String, id: Long?) {
        if (id == null) tag.remove(key) else tag.putLong(key, id)
    }

    private fun readPose(t: CompoundTag) = VSJointPose(
        Vector3d(t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z")),
        Quaterniond(t.getDouble("QX"), t.getDouble("QY"), t.getDouble("QZ"), t.getDouble("QW")),
    )

    private fun writePose(pose: VSJointPose) = CompoundTag().apply {
        putDouble("X", pose.pos.x()); putDouble("Y", pose.pos.y()); putDouble("Z", pose.pos.z())
        putDouble("QX", pose.rot.x()); putDouble("QY", pose.rot.y())
        putDouble("QZ", pose.rot.z()); putDouble("QW", pose.rot.w())
    }

    private const val NBT_SHIP0 = "JointShip0"
    private const val NBT_SHIP1 = "JointShip1"
    private const val NBT_POSE0 = "JointPose0"
    private const val NBT_POSE1 = "JointPose1"
}
