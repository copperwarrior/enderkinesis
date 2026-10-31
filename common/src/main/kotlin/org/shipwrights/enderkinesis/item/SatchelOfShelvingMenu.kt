package org.shipwrights.enderkinesis.item

import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.ContainerSynchronizer
import net.minecraft.world.inventory.DataSlot
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.item.SatchelOfShelvingContents.Companion.MAX_ROWS
import org.shipwrights.enderkinesis.item.SatchelOfShelvingContents.Companion.ROW_SLOTS
import org.shipwrights.enderkinesis.registry.EKMenus
import org.shipwrights.enderkinesis.registry.EKSounds
import java.util.UUID

/**
 * A menu needs the same slot count on both sides, so the satchel shows a fixed window of
 * [WINDOW_ROWS] rows. The server maps the window onto the contents at [scrollRow]; the
 * screen moves the window slots to follow its smooth scroll.
 */
class SatchelOfShelvingMenu private constructor(
    containerId: Int,
    playerInventory: Inventory,
    private val rows: DataSlot,
    private val scroll: DataSlot,
    private val openSlot: DataSlot,
    windowFor: (DataSlot) -> Container,
    private val owner: Owner?,
) : AbstractContainerMenu(EKMenus.SATCHEL_OF_SHELVING.get(), containerId) {

    /** [inventorySlot] is the player inventory index of the satchel, or [NO_SLOT] when it is in an accessory slot. */
    private class Owner(val contents: SatchelOfShelvingContents, val id: UUID, val inventorySlot: Int)

    constructor(containerId: Int, playerInventory: Inventory) : this(
        // The server's values arrive after the first frame; a satchel always has at least 1 row.
        containerId, playerInventory, DataSlot.standalone().apply { set(1) }, DataSlot.standalone(),
        DataSlot.standalone().apply { set(NO_SLOT) }, { SimpleContainer(WINDOW_SLOTS) }, null,
    )

    private constructor(
        containerId: Int, playerInventory: Inventory, owner: Owner,
    ) : this(
        containerId, playerInventory, RowCountSlot(owner.contents), DataSlot.standalone(),
        DataSlot.standalone().apply { set(owner.inventorySlot) },
        { scroll -> SatchelWindow(owner.contents, scroll) }, owner,
    )

    private val window: Container = windowFor(scroll)
    private val player: Player = playerInventory.player
    private var shownRows: Int = rows.get()
    private var shelfSoundTick: Long = Long.MIN_VALUE

    val rowCount: Int get() = rows.get()
    val scrollRow: Int get() = scroll.get()

    /** Player inventory index of the satchel this menu shows, or [NO_SLOT] before the first sync. */
    val openInventorySlot: Int get() = openSlot.get()

    init {
        for (row in 0 until WINDOW_ROWS) {
            for (col in 0 until ROW_SLOTS) {
                addSlot(SatchelSlot(row * ROW_SLOTS + col, SHELF_SLOT_X + col * SLOT_PITCH, VIEW_TOP + row * ROW_HEIGHT))
            }
        }
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(InventorySlot(playerInventory, 9 + row * 9 + col, INVENTORY_SLOT_X + col * SLOT_PITCH, INVENTORY_Y + row * SLOT_PITCH))
            }
        }
        for (col in 0 until 9) addSlot(InventorySlot(playerInventory, col, INVENTORY_SLOT_X + col * SLOT_PITCH, HOTBAR_Y))
        addDataSlot(rows)
        addDataSlot(scroll)
        addDataSlot(openSlot)
    }

    /** Server only. The client asks through [SatchelOfShelvingNetwork]. */
    fun scrollTo(row: Int) {
        scroll.set(row.coerceIn(0, minOf(rowCount - 1, MAX_ROWS - WINDOW_ROWS)))
    }

    // Taking items out can remove rows under the current scroll position.
    override fun broadcastChanges() {
        if (owner != null) {
            // While the cursor holds a stack, moving rows would put a different slot under the drop.
            if (carried.isEmpty) owner.contents.collapseEmptyRows()
            scrollTo(scrollRow)
            playRowSound()
        }
        super.broadcastChanges()
    }

    private fun playRowSound() {
        val rows = rowCount
        if (rows == shownRows) return
        play(if (rows > shownRows) EKSounds.SATCHEL_OF_SHELVING_ROW_ADD else EKSounds.SATCHEL_OF_SHELVING_ROW_REMOVE)
        shownRows = rows
    }

    // SWAP moves the inventory item at [button] directly and skips the slot checks.
    override fun clicked(slotId: Int, button: Int, clickType: ClickType, player: Player) {
        val contents = owner?.contents
        if (contents != null && isRefused(slotId, button, clickType)) play(EKSounds.SATCHEL_OF_SHELVING_REFUSE)
        if (clickType == ClickType.SWAP && button == openInventorySlot) return
        if (contents == null) {
            super.clicked(slotId, button, clickType, player)
            return
        }
        val slot = slots.getOrNull(slotId)
        val before = slot?.item
        val count = contents.itemCount
        super.clicked(slotId, button, clickType, player)
        val after = contents.itemCount
        when {
            after > count -> playShelfSound(EKSounds.SATCHEL_OF_SHELVING_INSERT)
            after < count -> playShelfSound(EKSounds.SATCHEL_OF_SHELVING_EXTRACT)
            // A swap with the cursor keeps the count.
            slot is SatchelSlot && slot.item !== before -> playShelfSound(EKSounds.SATCHEL_OF_SHELVING_INSERT)
            clickType == ClickType.QUICK_MOVE && before?.isEmpty == false -> play(EKSounds.SATCHEL_OF_SHELVING_REFUSE)
        }
    }

    private fun isRefused(slotId: Int, button: Int, clickType: ClickType): Boolean {
        val slot = slots.getOrNull(slotId)
        val holdsOpenSatchel = slot is InventorySlot && slot.holdsOpenSatchel
        return when (clickType) {
            ClickType.PICKUP -> holdsOpenSatchel || slot is SatchelSlot && !carried.isEmpty && !slot.mayPlace(carried)
            ClickType.SWAP -> {
                val swapped = player.inventory.getItem(button)
                holdsOpenSatchel || button == openInventorySlot ||
                    slot is SatchelSlot && !swapped.isEmpty && !slot.mayPlace(swapped)
            }
            ClickType.THROW -> holdsOpenSatchel
            else -> false
        }
    }

    private fun playShelfSound(event: RegistrySupplier<SoundEvent>) {
        val tick = player.level().gameTime
        if (tick == shelfSoundTick) return
        shelfSoundTick = tick
        play(event)
    }

    private fun play(event: RegistrySupplier<SoundEvent>) {
        (player as? ServerPlayer)?.let { playSound(it, event) }
    }

    override fun setSynchronizer(synchronizer: ContainerSynchronizer) {
        super.setSynchronizer(if (owner == null) synchronizer else SatchelOfShelvingSynchronizer(synchronizer, WINDOW_SLOTS))
    }

    override fun stillValid(player: Player): Boolean {
        val owner = owner ?: return true
        if (!player.isAlive) return false
        if (owner.inventorySlot != NO_SLOT) return SatchelOfShelvingItem.satchelId(player.inventory.getItem(owner.inventorySlot)) == owner.id
        return AccessoryGatherer.gather(player).any { SatchelOfShelvingItem.satchelId(it) == owner.id }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots[index]
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()
        val moved = if (index < WINDOW_SLOTS) {
            moveItemStackTo(stack, WINDOW_SLOTS, slots.size, true)
        } else {
            // The client cannot see past the window, so only the server stores; it then syncs the result.
            val owner = owner ?: return ItemStack.EMPTY
            stack.`is`(SatchelOfShelvingItem.STORABLE) && owner.contents.insert(stack)
        }
        if (!moved) return ItemStack.EMPTY
        if (stack.isEmpty) slot.set(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    private inner class SatchelSlot(index: Int, x: Int, y: Int) : Slot(window, index, x, y) {
        override fun mayPlace(stack: ItemStack): Boolean = stack.`is`(SatchelOfShelvingItem.STORABLE)
        // The screen clips a slot that the bag rim cuts; it stays usable.
        override fun isActive(): Boolean =
            scrollRow + containerSlot / ROW_SLOTS < rowCount && y + SLOT_SIZE > VIEW_TOP && y < VIEW_BOTTOM
    }

    /** The open satchel stays where it is until the menu closes. */
    private inner class InventorySlot(inventory: Inventory, index: Int, x: Int, y: Int) : Slot(inventory, index, x, y) {
        val holdsOpenSatchel: Boolean get() = containerSlot == openInventorySlot
        override fun mayPickup(player: Player): Boolean = !holdsOpenSatchel && super.mayPickup(player)
        override fun mayPlace(stack: ItemStack): Boolean = !holdsOpenSatchel && super.mayPlace(stack)
    }

    private class RowCountSlot(private val contents: SatchelOfShelvingContents) : DataSlot() {
        override fun get(): Int = contents.rowCount
        override fun set(value: Int) = Unit
    }

    private class SatchelWindow(
        private val contents: SatchelOfShelvingContents,
        private val scroll: DataSlot,
    ) : Container {
        private fun indexOf(slot: Int): Int = scroll.get() * ROW_SLOTS + slot

        override fun getContainerSize(): Int = WINDOW_SLOTS
        override fun isEmpty(): Boolean = contents.isEmpty
        override fun getItem(slot: Int): ItemStack = contents[indexOf(slot)]

        override fun removeItem(slot: Int, amount: Int): ItemStack {
            val stack = getItem(slot)
            if (stack.isEmpty || amount <= 0) return ItemStack.EMPTY
            val taken = stack.split(amount)
            contents.markChanged()
            return taken
        }

        override fun removeItemNoUpdate(slot: Int): ItemStack {
            val stack = getItem(slot)
            contents[indexOf(slot)] = ItemStack.EMPTY
            return stack
        }

        override fun setItem(slot: Int, stack: ItemStack) {
            contents[indexOf(slot)] = stack
        }

        override fun setChanged() = contents.markChanged()
        override fun stillValid(player: Player): Boolean = true

        override fun clearContent() {
            for (slot in 0 until WINDOW_SLOTS) setItem(slot, ItemStack.EMPTY)
        }
    }

    companion object {
        /** Enough rows to cover the bag opening at any scroll offset. */
        const val WINDOW_ROWS = 6
        const val WINDOW_SLOTS = WINDOW_ROWS * ROW_SLOTS

        /** The part of the bag opening that is clear across all slot columns. */
        const val VIEW_TOP = 15
        const val VIEW_HEIGHT = 100
        const val VIEW_BOTTOM = VIEW_TOP + VIEW_HEIGHT

        const val SHELF_SLOT_X = 43
        const val INVENTORY_SLOT_X = 49
        const val SLOT_PITCH = 18
        const val SLOT_SIZE = 16
        const val ROW_HEIGHT = 22

        /** Slot wells sit this far into each row sprite. */
        const val SLOT_INSET = 4
        const val INVENTORY_Y = 130
        const val HOTBAR_Y = 188

        const val NO_SLOT = -1

        /** [inventorySlot] is where [stack] sits in the player's inventory, or [NO_SLOT] for an accessory slot. */
        fun open(player: ServerPlayer, stack: ItemStack, inventorySlot: Int) {
            val id = SatchelOfShelvingItem.getOrCreateId(stack)
            val owner = Owner(SatchelOfShelvingSavedData.get(player.server).contents(id), id, inventorySlot)
            player.openMenu(SimpleMenuProvider({ containerId, inventory, _ ->
                SatchelOfShelvingMenu(containerId, inventory, owner)
            }, stack.hoverName))
            playSound(player, EKSounds.SATCHEL_OF_SHELVING_OPEN)
        }

        /** Only the player at the satchel hears its menu. */
        private fun playSound(player: ServerPlayer, event: RegistrySupplier<SoundEvent>) {
            player.playNotifySound(event.get(), SoundSource.PLAYERS, 1f, 1f)
        }
    }
}
