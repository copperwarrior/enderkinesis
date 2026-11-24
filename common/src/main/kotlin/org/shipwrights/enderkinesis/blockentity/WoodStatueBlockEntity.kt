package org.shipwrights.enderkinesis.blockentity

import com.mojang.authlib.GameProfile
import net.minecraft.core.BlockPos
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import org.shipwrights.enderkinesis.registry.EKBlockEntities

class WoodStatueBlockEntity(pos: BlockPos, state: BlockState) :
    BaseContainerBlockEntity(EKBlockEntities.WOOD_STATUE.get(), pos, state) {

    private val items: NonNullList<ItemStack> = NonNullList.withSize(SIZE, ItemStack.EMPTY)

    var owner: GameProfile? = null
        private set

    /** Body yaw in the block's own frame (the ship's frame on a ship). */
    var bodyYaw: Float = 0f
        private set
    var headYaw: Float = 0f
        private set
    var headPitch: Float = 0f
        private set

    /** Client cache of the resolved skin, so the renderer resolves it once. */
    var skin: ResourceLocation? = null
    var slimSkin: Boolean = false

    private val renderBox: AABB = AABB(pos).expandTowards(0.0, 1.0, 0.0)

    fun carve(profile: GameProfile, bodyYaw: Float, headYaw: Float, headPitch: Float) {
        owner = profile
        this.bodyYaw = bodyYaw
        this.headYaw = headYaw
        this.headPitch = headPitch
        setChanged()
    }

    /** Takes each stack into the same slot. Vanishing-cursed stacks stay, so vanilla still destroys them. */
    fun takeInventory(inventory: Inventory) {
        val count = minOf(inventory.containerSize, SIZE)
        for (slot in 0 until count) {
            val stack = inventory.getItem(slot)
            if (stack.isEmpty || EnchantmentHelper.hasVanishingCurse(stack)) continue
            items[slot] = stack
            inventory.setItem(slot, ItemStack.EMPTY)
        }
        setChanged()
    }

    override fun getContainerSize(): Int = SIZE

    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack = items[slot]

    override fun removeItem(slot: Int, amount: Int): ItemStack =
        ContainerHelper.removeItem(items, slot, amount).also { if (!it.isEmpty) setChanged() }

    override fun removeItemNoUpdate(slot: Int): ItemStack = ContainerHelper.takeItem(items, slot)

    override fun setItem(slot: Int, stack: ItemStack) {
        items[slot] = stack
        if (stack.count > maxStackSize) stack.count = maxStackSize
        setChanged()
    }

    override fun stillValid(player: Player): Boolean = Container.stillValidBlockEntity(this, player)

    override fun clearContent() = items.clear()

    override fun getDefaultName(): Component =
        Component.translatable("container.enderkinesis.wood_statue", owner?.name ?: "")

    override fun createMenu(containerId: Int, inventory: Inventory): AbstractContainerMenu =
        ChestMenu(MenuType.GENERIC_9x5, containerId, inventory, this, ROWS)

    override fun load(tag: CompoundTag) {
        super.load(tag)
        items.clear()
        ContainerHelper.loadAllItems(tag, items)
        loadLook(tag)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        ContainerHelper.saveAllItems(tag, items)
        saveLook(tag)
    }

    private fun loadLook(tag: CompoundTag) {
        owner = if (tag.contains(OWNER_KEY)) NbtUtils.readGameProfile(tag.getCompound(OWNER_KEY)) else null
        bodyYaw = tag.getFloat(BODY_YAW_KEY)
        headYaw = tag.getFloat(HEAD_YAW_KEY)
        headPitch = tag.getFloat(HEAD_PITCH_KEY)
    }

    private fun saveLook(tag: CompoundTag) {
        owner?.let { tag.put(OWNER_KEY, NbtUtils.writeGameProfile(CompoundTag(), it)) }
        tag.putFloat(BODY_YAW_KEY, bodyYaw)
        tag.putFloat(HEAD_YAW_KEY, headYaw)
        tag.putFloat(HEAD_PITCH_KEY, headPitch)
    }

    // The client needs the look only, not the items.
    override fun getUpdateTag(): CompoundTag = CompoundTag().also(::saveLook)

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    // The figure reaches into the upper block; see INFINITE_RENDER_BOX for why Forge needs this.
    @Suppress("unused")
    fun getRenderBoundingBox(): AABB = renderBox

    companion object {
        private const val ROWS = 5

        /** 36 main + 4 armor + 1 offhand fit in a 9x5 chest view. */
        const val SIZE = 9 * ROWS

        private const val OWNER_KEY = "Owner"
        private const val BODY_YAW_KEY = "BodyYaw"
        private const val HEAD_YAW_KEY = "HeadYaw"
        private const val HEAD_PITCH_KEY = "HeadPitch"
    }
}
