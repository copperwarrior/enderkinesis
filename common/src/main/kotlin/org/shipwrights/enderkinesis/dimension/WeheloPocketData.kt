package org.shipwrights.enderkinesis.dimension

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.saveddata.SavedData
import org.shipwrights.enderkinesis.dimension.SselithChatTeleport.ReturnPoint
import java.util.UUID

internal class WeheloPocketData : SavedData() {

    private val slots: MutableMap<UUID, Int> = mutableMapOf()
    private val returns: MutableMap<UUID, ReturnPoint> = mutableMapOf()
    private val furnished: MutableSet<Int> = mutableSetOf()
    private var nextSlot = 0

    fun slotOf(owner: UUID): Int? = slots[owner]

    /** Null when every slot is taken. */
    fun allocate(owner: UUID, capacity: Int): Int? {
        slots[owner]?.let { return it }
        if (nextSlot >= capacity) return null
        val slot = nextSlot++
        slots[owner] = slot
        setDirty()
        return slot
    }

    fun isFurnished(slot: Int): Boolean = slot in furnished

    fun markFurnished(slot: Int) {
        if (furnished.add(slot)) setDirty()
    }

    fun setReturn(player: UUID, point: ReturnPoint) {
        returns[player] = point
        setDirty()
    }

    fun peekReturn(player: UUID): ReturnPoint? = returns[player]

    fun takeReturn(player: UUID): ReturnPoint? = returns.remove(player)?.also { setDirty() }

    override fun save(tag: CompoundTag): CompoundTag {
        tag.putInt("Next", nextSlot)
        val slotList = ListTag()
        for ((uuid, slot) in slots) {
            val entry = CompoundTag()
            entry.putUUID("UUID", uuid)
            entry.putInt("Slot", slot)
            slotList.add(entry)
        }
        tag.put("Slots", slotList)
        val returnList = ListTag()
        for ((uuid, point) in returns) {
            val entry = point.save(CompoundTag())
            entry.putUUID("UUID", uuid)
            returnList.add(entry)
        }
        tag.put("Returns", returnList)
        tag.putIntArray("Furnished", furnished.toIntArray())
        return tag
    }

    companion object {
        private const val NAME = "enderkinesis_wehelo_pockets"

        fun load(tag: CompoundTag): WeheloPocketData {
            val data = WeheloPocketData()
            data.nextSlot = tag.getInt("Next")
            val slotList = tag.getList("Slots", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until slotList.size) {
                val entry = slotList.getCompound(i)
                data.slots[entry.getUUID("UUID")] = entry.getInt("Slot")
            }
            val returnList = tag.getList("Returns", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until returnList.size) {
                val entry = returnList.getCompound(i)
                ReturnPoint.load(entry)?.let { data.returns[entry.getUUID("UUID")] = it }
            }
            tag.getIntArray("Furnished").forEach { data.furnished += it }
            return data
        }

        fun get(server: MinecraftServer): WeheloPocketData =
            server.overworld().dataStorage.computeIfAbsent(::load, ::WeheloPocketData, NAME)
    }
}
