package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import org.joml.Quaterniond
import org.joml.Vector3d

/**
 * Per-dimension persistent storage for the VS2 fixed joints that back [BoundRootBlock]
 * welds. Each entry carries everything needed to re-create the [org.valkyrienskies.core.internal.joints.VSFixedJoint]
 * after a world reload: the two anchor block positions, the two ship ids (or null for the
 * world body), and the two `VSJointPose` inputs (position + rotation in each body's frame).
 *
 * VS2 itself doesn't persist joints (they're "transient" — see the orb-binding tomes'
 * `serverTick` rebind loop). We persist the *recipe* for each joint here, then re-issue
 * the `gtpa.addJoint` call at [BindingRootsMerger.restoreSavedJoints] time during
 * `LifecycleEvent.SERVER_LEVEL_LOAD`.
 *
 * **Key invariants.** Stored under `blockAPos.asLong()` only (one record per joint, not two
 * — the `bPos` field carries the partner). Removal looks up by *either* endpoint to find
 * and delete the record, which lets [BindingRootsMerger.onBoundRootBroken] work without
 * caring which side of the joint the player broke.
 */
class BindingRootsSavedData : SavedData() {

    data class JointRecord(
        val blockAPos: BlockPos,
        val blockBPos: BlockPos,
        val shipAId: Long?,
        val shipBId: Long?,
        val pose0Pos: Vector3d,
        val pose0Rot: Quaterniond,
        val pose1Pos: Vector3d,
        val pose1Rot: Quaterniond,
    )

    val records: MutableMap<Long, JointRecord> = mutableMapOf()

    /** All known binding_roots block positions in this level — saved here so that on world
     *  load the merger's in-memory `positions` set can be repopulated. Without this the
     *  scanner has no idea where the roots are after a reload (registerPosition only ever
     *  fired on player-placement, not on chunk-load), and free roots can't form new joints. */
    val anchorPositions: MutableSet<Long> = mutableSetOf()

    fun add(record: JointRecord) {
        records[record.blockAPos.asLong()] = record
        setDirty()
    }

    fun addAnchor(pos: Long) {
        if (anchorPositions.add(pos)) setDirty()
    }

    fun removeAnchor(pos: Long) {
        if (anchorPositions.remove(pos)) setDirty()
    }

    /** Remove the joint record that involves [blockPos] as either endpoint. */
    fun removeInvolving(blockPos: BlockPos): JointRecord? {
        val key = blockPos.asLong()
        val direct = records.remove(key)
        if (direct != null) {
            setDirty()
            return direct
        }
        val partner = records.entries.firstOrNull { it.value.blockBPos.asLong() == key }
        if (partner != null) {
            records.remove(partner.key)
            setDirty()
            return partner.value
        }
        return null
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for ((_, record) in records) {
            val t = CompoundTag()
            t.putLong("a", record.blockAPos.asLong())
            t.putLong("b", record.blockBPos.asLong())
            if (record.shipAId != null) t.putLong("sa", record.shipAId)
            if (record.shipBId != null) t.putLong("sb", record.shipBId)
            putVec3d(t, "p0p", record.pose0Pos)
            putQuat(t, "p0r", record.pose0Rot)
            putVec3d(t, "p1p", record.pose1Pos)
            putQuat(t, "p1r", record.pose1Rot)
            list.add(t)
        }
        tag.put("joints", list)
        tag.putLongArray("anchors", anchorPositions.toLongArray())
        return tag
    }

    companion object {
        private const val NAME = "enderkinesis_binding_roots_joints"

        fun load(tag: CompoundTag): BindingRootsSavedData {
            val data = BindingRootsSavedData()
            val list = tag.getList("joints", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                val t = list.getCompound(i)
                val record = JointRecord(
                    blockAPos = BlockPos.of(t.getLong("a")),
                    blockBPos = BlockPos.of(t.getLong("b")),
                    shipAId = if (t.contains("sa", Tag.TAG_LONG.toInt())) t.getLong("sa") else null,
                    shipBId = if (t.contains("sb", Tag.TAG_LONG.toInt())) t.getLong("sb") else null,
                    pose0Pos = getVec3d(t, "p0p"),
                    pose0Rot = getQuat(t, "p0r"),
                    pose1Pos = getVec3d(t, "p1p"),
                    pose1Rot = getQuat(t, "p1r"),
                )
                data.records[record.blockAPos.asLong()] = record
            }
            for (anchor in tag.getLongArray("anchors")) data.anchorPositions.add(anchor)
            return data
        }

        fun forLevel(level: ServerLevel): BindingRootsSavedData =
            level.dataStorage.computeIfAbsent(::load, ::BindingRootsSavedData, NAME)

        private fun putVec3d(tag: CompoundTag, key: String, v: Vector3d) {
            tag.putDouble("${key}x", v.x)
            tag.putDouble("${key}y", v.y)
            tag.putDouble("${key}z", v.z)
        }

        private fun getVec3d(tag: CompoundTag, key: String): Vector3d =
            Vector3d(tag.getDouble("${key}x"), tag.getDouble("${key}y"), tag.getDouble("${key}z"))

        private fun putQuat(tag: CompoundTag, key: String, q: Quaterniond) {
            tag.putDouble("${key}x", q.x)
            tag.putDouble("${key}y", q.y)
            tag.putDouble("${key}z", q.z)
            tag.putDouble("${key}w", q.w)
        }

        private fun getQuat(tag: CompoundTag, key: String): Quaterniond =
            Quaterniond(
                tag.getDouble("${key}x"),
                tag.getDouble("${key}y"),
                tag.getDouble("${key}z"),
                tag.getDouble("${key}w"),
            )
    }
}
