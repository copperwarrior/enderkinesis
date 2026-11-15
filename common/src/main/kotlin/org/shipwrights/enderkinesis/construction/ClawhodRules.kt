package org.shipwrights.enderkinesis.construction

import net.minecraft.world.item.ItemStack

/** The last fetch that gave a hand nothing. It holds while the supply epoch and what the hand holds stay the same,
 *  so a refused item waits for a real change instead of a new pick at once. */
internal class FetchRefusal {
    private var item: Any? = null
    private var epoch = 0
    private var held: Any? = null
    private var count = 0

    fun refuse(item: Any, epoch: Int, held: Any?, count: Int) {
        require(count >= 0) { "Held count must not be negative: $count" }
        this.item = item
        this.epoch = epoch
        this.held = held
        this.count = count
    }

    fun refuses(item: Any, epoch: Int, held: Any?, count: Int): Boolean =
        this.item === item && this.epoch == epoch && this.held === held && this.count == count

    fun clear() {
        item = null
        held = null
    }
}

/** How many of [stack] fit in [slots]: room on stacks of the same item and tags, then empty slots. */
internal fun pickupRoom(slots: List<ItemStack>, stack: ItemStack): Int {
    if (stack.isEmpty) return 0
    var room = 0
    for (slot in slots) {
        room += when {
            slot.isEmpty -> stack.maxStackSize
            ItemStack.isSameItemSameTags(slot, stack) -> (slot.maxStackSize - slot.count).coerceAtLeast(0)
            else -> 0
        }
    }
    return room
}

/** Puts up to [limit] of [stack] in [slots], same-item stacks first; [stack] is not changed. Returns the count. */
internal fun pickupInsert(slots: MutableList<ItemStack>, stack: ItemStack, limit: Int): Int {
    require(limit >= 0) { "Limit must not be negative: $limit" }
    var left = minOf(limit, stack.count)
    for (i in slots.indices) {
        if (left == 0) break
        val slot = slots[i]
        if (slot.isEmpty || !ItemStack.isSameItemSameTags(slot, stack)) continue
        val add = minOf(left, slot.maxStackSize - slot.count)
        if (add <= 0) continue
        slot.grow(add)
        left -= add
    }
    for (i in slots.indices) {
        if (left == 0) break
        if (!slots[i].isEmpty) continue
        val add = minOf(left, stack.maxStackSize)
        slots[i] = stack.copyWithCount(add)
        left -= add
    }
    return minOf(limit, stack.count) - left
}
