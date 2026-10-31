package org.shipwrights.enderkinesis.item

import com.google.common.io.ByteStreams
import com.google.common.io.CountingOutputStream
import net.minecraft.core.NonNullList
import net.minecraft.nbt.NbtIo
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerSynchronizer
import net.minecraft.world.item.ItemStack
import java.io.DataOutputStream

/**
 * A full sync puts every slot in one packet, and a window of large written books can pass the
 * 2 MiB frame limit and disconnect the player. Satchel stacks past [PACKET_BUDGET] go out after
 * it as single-slot packets, so the client gets the same contents in the same tick.
 */
internal class SatchelOfShelvingSynchronizer(
    private val delegate: ContainerSynchronizer,
    private val satchelSlots: Int,
) : ContainerSynchronizer by delegate {

    override fun sendInitialData(menu: AbstractContainerMenu, items: NonNullList<ItemStack>, carried: ItemStack, data: IntArray) {
        var budget = PACKET_BUDGET - encodedSize(carried)
        for (index in satchelSlots until items.size) budget -= encodedSize(items[index])

        val bulk = NonNullList.withSize(items.size, ItemStack.EMPTY)
        val heldBack = ArrayList<Int>()
        for (index in items.indices) {
            val stack = items[index]
            if (index >= satchelSlots) {
                bulk[index] = stack
                continue
            }
            if (stack.isEmpty) continue
            val size = encodedSize(stack)
            if (size <= budget) {
                bulk[index] = stack
                budget -= size
            } else {
                heldBack.add(index)
            }
        }
        delegate.sendInitialData(menu, bulk, carried, data)
        for (index in heldBack) delegate.sendSlotChange(menu, index, items[index])
    }

    companion object {
        /** Under the 2 MiB frame limit with margin, as dense text does not compress. */
        private const val PACKET_BUDGET = 1L shl 20

        /** Item id, count, and tag marker; small next to any tag that matters here. */
        private const val ITEM_OVERHEAD = 8L

        private fun encodedSize(stack: ItemStack): Long {
            if (stack.isEmpty) return 1L
            val tag = stack.tag ?: return ITEM_OVERHEAD
            val counter = CountingOutputStream(ByteStreams.nullOutputStream())
            DataOutputStream(counter).use { NbtIo.write(tag, it) }
            return ITEM_OVERHEAD + counter.count
        }
    }
}
