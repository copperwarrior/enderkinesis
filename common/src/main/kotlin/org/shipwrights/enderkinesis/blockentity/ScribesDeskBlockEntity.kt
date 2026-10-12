package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.ContainerHelper
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.block.ScribesDeskBlock
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKItems

/** Holds one stack each of paper, penfeather and Ichaeric ink. Hoppers can load it but never empty it. */
class ScribesDeskBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.SCRIBES_DESK.get(), pos, state), WorldlyContainer {

    private val items: NonNullList<ItemStack> = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)

    val isReady: Boolean get() = items.none { it.isEmpty }

    /** The slot that accepts [stack], or null when the desk takes no such item. */
    fun slotFor(stack: ItemStack): Int? = (0 until SLOT_COUNT).firstOrNull { stack.`is`(slotItem(it)) }

    /** Move as much of [stack] into [slot] as fits; returns true when anything moved. */
    fun load(slot: Int, stack: ItemStack): Boolean {
        val current = items[slot]
        val room = stack.maxStackSize - current.count
        if (room <= 0) return false
        val moved = stack.split(room)
        if (current.isEmpty) items[slot] = moved else current.grow(moved.count)
        setChanged()
        return true
    }

    fun consumeForInscription() {
        items[PAPER].shrink(1)
        items[INK].shrink(1)
        setChanged()
    }

    override fun setChanged() {
        super.setChanged()
        val level = level ?: return
        if (level.isClientSide) return
        val state = level.getBlockState(worldPosition)
        if (state.block !is ScribesDeskBlock) return
        val shown = state.setValue(ScribesDeskBlock.PAPER, !items[PAPER].isEmpty)
            .setValue(ScribesDeskBlock.PENFEATHER, !items[PENFEATHER].isEmpty)
            .setValue(ScribesDeskBlock.INK, !items[INK].isEmpty)
        if (shown != state) level.setBlock(worldPosition, shown, Block.UPDATE_ALL)
    }

    override fun getContainerSize(): Int = SLOT_COUNT
    override fun isEmpty(): Boolean = items.all { it.isEmpty }
    override fun getItem(slot: Int): ItemStack = items[slot]

    override fun removeItem(slot: Int, amount: Int): ItemStack {
        val out = ContainerHelper.removeItem(items, slot, amount)
        if (!out.isEmpty) setChanged()
        return out
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack = ContainerHelper.takeItem(items, slot)

    override fun setItem(slot: Int, stack: ItemStack) {
        items[slot] = stack
        if (stack.count > stack.maxStackSize) stack.count = stack.maxStackSize
        setChanged()
    }

    override fun stillValid(player: Player): Boolean =
        level?.getBlockEntity(blockPos) === this &&
            player.distanceToSqr(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5) <= MAX_USE_DISTANCE_SQR

    override fun clearContent() {
        items.replaceAll { ItemStack.EMPTY }
        setChanged()
    }

    override fun getSlotsForFace(side: Direction): IntArray = ALL_SLOTS

    override fun canPlaceItemThroughFace(slot: Int, stack: ItemStack, direction: Direction?): Boolean =
        canPlaceItem(slot, stack)

    override fun canTakeItemThroughFace(slot: Int, stack: ItemStack, direction: Direction): Boolean = false

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = stack.`is`(slotItem(slot))

    override fun load(tag: CompoundTag) {
        super.load(tag)
        items.replaceAll { ItemStack.EMPTY }
        ContainerHelper.loadAllItems(tag, items)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        ContainerHelper.saveAllItems(tag, items)
    }

    companion object {
        const val PAPER = 0
        const val PENFEATHER = 1
        const val INK = 2
        private const val SLOT_COUNT = 3
        private val ALL_SLOTS = intArrayOf(PAPER, PENFEATHER, INK)
        const val MAX_USE_DISTANCE_SQR = 64.0

        private fun slotItem(slot: Int): Item = when (slot) {
            PAPER -> Items.PAPER
            PENFEATHER -> EKItems.PENFEATHER_OF_THE_ACCURSED_OWL.get()
            else -> EKItems.ICHAERIC_INK_BOTTLE.get()
        }
    }
}
