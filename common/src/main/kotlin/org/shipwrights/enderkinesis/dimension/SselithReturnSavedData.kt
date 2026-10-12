package org.shipwrights.enderkinesis.dimension

import java.util.UUID
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
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
            val entry = CompoundTag()
            entry.putUUID("UUID", uuid)
            entry.putString("Dim", point.dimension.location().toString())
            entry.putDouble("X", point.x)
            entry.putDouble("Y", point.y)
            entry.putDouble("Z", point.z)
            entry.putFloat("YRot", point.yRot)
            entry.putFloat("XRot", point.xRot)
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
                val dim = ResourceLocation.tryParse(entry.getString("Dim")) ?: continue
                data.points[entry.getUUID("UUID")] = SselithChatTeleport.ReturnPoint(
                    dimension = ResourceKey.create(Registries.DIMENSION, dim),
                    x = entry.getDouble("X"),
                    y = entry.getDouble("Y"),
                    z = entry.getDouble("Z"),
                    yRot = entry.getFloat("YRot"),
                    xRot = entry.getFloat("XRot"),
                )
            }
            return data
        }

        // The overworld storage is available from all dimensions.
        fun get(server: MinecraftServer): SselithReturnSavedData =
            server.overworld().dataStorage.computeIfAbsent(::load, ::SselithReturnSavedData, NAME)
    }
}
