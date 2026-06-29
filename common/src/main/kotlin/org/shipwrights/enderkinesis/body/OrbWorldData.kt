package org.shipwrights.enderkinesis.body

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import org.joml.Vector3d
import org.joml.Vector3dc

/**
 * Per-server-level [SavedData] storing the Orb of Potential identity
 * table: which VS Body ids in this dimension are orbs, plus each
 * body's anchor world position (used by [OrbGravityCanceller]'s
 * pull-back spring).
 *
 *  Stored as `enderkinesis_orbs.dat` under the level's data folder.
 */
class OrbWorldData : SavedData() {

    private val data: MutableMap<Long, Vector3d> = HashMap()

    fun snapshot(): Map<Long, Vector3d> = data.toMap()

    fun put(bodyId: Long, anchor: Vector3dc) {
        data[bodyId] = Vector3d(anchor)
        setDirty()
    }

    fun remove(bodyId: Long) {
        if (data.remove(bodyId) != null) setDirty()
    }

    fun contains(bodyId: Long): Boolean = data.containsKey(bodyId)

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for ((id, anchor) in data) {
            val node = CompoundTag()
            node.putLong("Id", id)
            node.putDouble("Ax", anchor.x)
            node.putDouble("Ay", anchor.y)
            node.putDouble("Az", anchor.z)
            list.add(node)
        }
        tag.put("Orbs", list)
        return tag
    }

    companion object {
        private const val SAVE_KEY = "enderkinesis_orbs"

        @JvmStatic
        fun get(level: ServerLevel): OrbWorldData {
            return level.dataStorage.computeIfAbsent(::load, ::OrbWorldData, SAVE_KEY)
        }

        @JvmStatic
        fun load(tag: CompoundTag): OrbWorldData {
            val out = OrbWorldData()
            val list = tag.getList("Orbs", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                val node = list.getCompound(i)
                val id = node.getLong("Id")
                out.data[id] = Vector3d(
                    node.getDouble("Ax"),
                    node.getDouble("Ay"),
                    node.getDouble("Az"),
                )
            }
            return out
        }
    }
}
