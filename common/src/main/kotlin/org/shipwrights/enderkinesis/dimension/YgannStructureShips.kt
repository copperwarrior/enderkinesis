package org.shipwrights.enderkinesis.dimension

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData

/**
 * VS2 ship ids of the generated Ygann Abyss structures, with their [YgannDiscoveries] kind.
 * This is not a VS2 attachment: an attachment can be set only on a loaded ship, and assembly
 * returns a ship that VS2 loads later, or not until a player comes near.
 */
internal class YgannStructureShips : SavedData() {

    // Server thread only.
    private val kinds = Long2ObjectOpenHashMap<String>()

    fun kindOf(shipId: Long): String? = kinds.get(shipId)

    fun mark(shipId: Long, kind: String) {
        if (kinds.put(shipId, kind) != kind) setDirty()
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for (entry in kinds.long2ObjectEntrySet()) {
            val ship = CompoundTag()
            ship.putLong("Id", entry.longKey)
            ship.putString("Kind", entry.value)
            list.add(ship)
        }
        tag.put("Ships", list)
        return tag
    }

    companion object {
        private const val NAME = "enderkinesis_ygann_structure_ships"

        fun load(tag: CompoundTag): YgannStructureShips {
            val data = YgannStructureShips()
            val list = tag.getList("Ships", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                val ship = list.getCompound(i)
                data.kinds.put(ship.getLong("Id"), ship.getString("Kind"))
            }
            return data
        }

        fun get(server: MinecraftServer): YgannStructureShips =
            server.overworld().dataStorage.computeIfAbsent(::load, ::YgannStructureShips, NAME)
    }
}
