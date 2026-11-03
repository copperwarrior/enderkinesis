package org.shipwrights.enderkinesis.dimension

import java.util.UUID
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData

internal class SselithReturnSavedData : SavedData() {

    private val points: MutableMap<UUID, SselithChatTeleport.ReturnPoint> = mutableMapOf()

    fun put(uuid: UUID, point: SselithChatTeleport.ReturnPoint) {
        points[uuid] = point
        setDirty()
    }

    fun peek(uuid: UUID): SselithChatTeleport.ReturnPoint? = points[uuid]

    fun take(uuid: UUID): SselithChatTeleport.ReturnPoint? {
        val point = points.remove(uuid) ?: return null
        setDirty()
        return point
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for ((uuid, point) in points) {
            val entry = point.save(CompoundTag())
            entry.putUUID("UUID", uuid)
            list.add(entry)
        }
        tag.put("Points", list)
        return tag
    }

    companion object {
        private const val NAME = "enderkinesis_sselith_returns"

        fun load(tag: CompoundTag): SselithReturnSavedData {
            val data = SselithReturnSavedData()
            val list = tag.getList("Points", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                val entry = list.getCompound(i)
                val point = SselithChatTeleport.ReturnPoint.load(entry) ?: continue
                data.points[entry.getUUID("UUID")] = point
            }
            return data
        }

        // The overworld storage is available from all dimensions.
        fun get(server: MinecraftServer): SselithReturnSavedData =
            server.overworld().dataStorage.computeIfAbsent(::load, ::SselithReturnSavedData, NAME)
    }
}
