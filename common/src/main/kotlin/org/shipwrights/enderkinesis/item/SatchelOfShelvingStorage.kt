package org.shipwrights.enderkinesis.item

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/** Items keep their place inside a row; empty rows between filled rows collapse. */
class SatchelOfShelvingContents(private val onChanged: () -> Unit) {

    private val stacks = ArrayList<ItemStack>()

    val isEmpty: Boolean get() = stacks.isEmpty()

    /** Each row with an item; an empty row shows below only once the last row is full. */
    val rowCount: Int
        get() {
            if (stacks.isEmpty()) return 1
            val filledRows = (stacks.size - 1) / ROW_SLOTS + 1
            val lastRowFull = stacks.size % ROW_SLOTS == 0 && isFullRange(stacks.size - ROW_SLOTS, stacks.size)
            return minOf(if (lastRowFull) filledRows + 1 else filledRows, MAX_ROWS)
        }

    val itemCount: Int get() = stacks.sumOf { it.count }

    operator fun get(index: Int): ItemStack = if (index < stacks.size) stacks[index] else ItemStack.EMPTY

    operator fun set(index: Int, stack: ItemStack) {
        place(index, stack)
        markChanged()
    }

    /** Call after a stack in place changed its count. */
    fun markChanged() {
        trim()
        onChanged()
    }

    /** Moves as much of [stack] as fits: first onto equal stacks, then into the first empty indices. */
    fun insert(stack: ItemStack): Boolean {
        val before = stack.count
        for (existing in stacks) {
            if (stack.isEmpty) break
            if (!ItemStack.isSameItemSameTags(existing, stack)) continue
            val moved = minOf(stack.count, existing.maxStackSize - existing.count)
            if (moved <= 0) continue
            existing.grow(moved)
            stack.shrink(moved)
        }
        while (!stack.isEmpty) {
            val free = stacks.indexOfFirst { it.isEmpty }.takeIf { it >= 0 } ?: stacks.size
            if (free >= CAPACITY) break
            place(free, stack.split(stack.maxStackSize))
        }
        if (stack.count == before) return false
        markChanged()
        return true
    }

    /** Hands each stack to [transfer], which returns what it did not take. Returns true if something moved. */
    fun moveAll(transfer: (ItemStack) -> ItemStack): Boolean {
        var moved = false
        for (index in stacks.indices) {
            val stack = stacks[index]
            if (stack.isEmpty) continue
            val count = stack.count
            val rest = transfer(stack)
            if (rest !== stack || rest.count != count) moved = true
            stacks[index] = rest
        }
        if (!moved) return false
        markChanged()
        collapseEmptyRows()
        return true
    }

    /** Removes empty rows that sit above filled rows; the rows below move up and keep their order. */
    fun collapseEmptyRows() {
        val rows = (stacks.size + ROW_SLOTS - 1) / ROW_SLOTS
        var write = 0
        for (read in 0 until rows) {
            val start = read * ROW_SLOTS
            val end = minOf(start + ROW_SLOTS, stacks.size)
            if (isEmptyRange(start, end)) continue
            if (write != read) {
                for (index in start until end) {
                    stacks[write * ROW_SLOTS + index - start] = stacks[index]
                    stacks[index] = ItemStack.EMPTY
                }
            }
            write++
        }
        if (write != rows) markChanged()
    }

    private fun isEmptyRange(start: Int, end: Int): Boolean {
        for (index in start until end) if (!stacks[index].isEmpty) return false
        return true
    }

    private fun isFullRange(start: Int, end: Int): Boolean {
        for (index in start until end) if (stacks[index].isEmpty) return false
        return true
    }

    fun save(): ListTag {
        val list = ListTag()
        stacks.forEachIndexed { index, stack ->
            if (!stack.isEmpty) list.add(stack.save(CompoundTag()).apply { putInt(INDEX_KEY, index) })
        }
        return list
    }

    fun load(list: ListTag) {
        for (entry in list) {
            val tag = entry as? CompoundTag ?: continue
            val index = tag.getInt(INDEX_KEY)
            if (index in 0 until CAPACITY) place(index, ItemStack.of(tag))
        }
        trim()
    }

    private fun trim() {
        while (stacks.isNotEmpty() && stacks.last().isEmpty) stacks.removeAt(stacks.lastIndex)
    }

    private fun place(index: Int, stack: ItemStack) {
        require(index in 0 until CAPACITY) { "Satchel index $index is outside 0 until $CAPACITY" }
        while (stacks.size <= index) stacks.add(ItemStack.EMPTY)
        stacks[index] = stack
    }

    companion object {
        const val ROW_SLOTS = 9

        /** A short DataSlot syncs the row count, so it must stay under 32768. */
        const val MAX_ROWS = 4096
        const val CAPACITY = MAX_ROWS * ROW_SLOTS
        private const val INDEX_KEY = "Index"
    }
}

/** Keeps the contents off the item stack: large written-book NBT on a worn item goes to every tracking client. */
class SatchelOfShelvingSavedData : SavedData() {

    private val satchels = HashMap<UUID, SatchelOfShelvingContents>()

    fun contents(id: UUID): SatchelOfShelvingContents =
        satchels.getOrPut(id) { SatchelOfShelvingContents(::setDirty) }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for ((id, contents) in satchels) {
            if (contents.isEmpty) continue
            list.add(CompoundTag().apply {
                putUUID(ID_KEY, id)
                put(ITEMS_KEY, contents.save())
            })
        }
        tag.put(SATCHELS_KEY, list)
        return tag
    }

    companion object {
        private const val NAME = "enderkinesis_satchels_of_shelving"
        private const val SATCHELS_KEY = "Satchels"
        private const val ID_KEY = "Id"
        private const val ITEMS_KEY = "Items"

        fun get(server: MinecraftServer): SatchelOfShelvingSavedData =
            server.overworld().dataStorage.computeIfAbsent(::load, ::SatchelOfShelvingSavedData, NAME)

        private fun load(tag: CompoundTag): SatchelOfShelvingSavedData {
            val data = SatchelOfShelvingSavedData()
            for (entry in tag.getList(SATCHELS_KEY, Tag.TAG_COMPOUND.toInt())) {
                val satchel = entry as? CompoundTag ?: continue
                if (!satchel.hasUUID(ID_KEY)) continue
                data.contents(satchel.getUUID(ID_KEY)).load(satchel.getList(ITEMS_KEY, Tag.TAG_COMPOUND.toInt()))
            }
            return data
        }
    }
}
