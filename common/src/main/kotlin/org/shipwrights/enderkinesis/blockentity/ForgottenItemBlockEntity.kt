package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKBlockEntities

// Not a Container: hoppers and other automation must find no inventory here.
class ForgottenItemBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.FORGOTTEN_ITEM.get(), pos, state) {

    var item: ItemStack = ItemStack.EMPTY
        private set

    var yaw: Float = 0f
        private set

    private var lootTable: ResourceLocation? = null
    private var lootSeed: Long = 0L

    fun put(stack: ItemStack, placerYaw: Float) {
        item = stack
        yaw = placerYaw
        lootTable = null
        setChanged()
    }

    fun setLootTable(table: ResourceLocation, seed: Long, itemYaw: Float) {
        lootTable = table
        lootSeed = seed
        yaw = itemYaw
        setChanged()
    }

    // Server thread only. The slot holds one stack, so one rolled stack is kept.
    private fun unpackLootTable() {
        val table = lootTable ?: return
        val server = level as? ServerLevel ?: return
        lootTable = null
        val params = LootParams.Builder(server)
            .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(blockPos))
            .create(LootContextParamSets.CHEST)
        val stacks = server.server.lootData.getLootTable(table).getRandomItems(params, lootSeed)
        stacks.removeIf { it.isEmpty }
        if (stacks.isNotEmpty()) item = stacks[Math.floorMod(lootSeed, stacks.size.toLong()).toInt()]
        setChanged()
    }

    fun take(): ItemStack {
        unpackLootTable()
        val out = item
        item = ItemStack.EMPTY
        setChanged()
        return out
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        item = if (tag.contains(NBT_ITEM, Tag.TAG_COMPOUND.toInt())) {
            ItemStack.of(tag.getCompound(NBT_ITEM))
        } else {
            ItemStack.EMPTY
        }
        yaw = tag.getFloat(NBT_YAW)
        lootTable = if (tag.contains(NBT_LOOT_TABLE, Tag.TAG_STRING.toInt())) {
            ResourceLocation.tryParse(tag.getString(NBT_LOOT_TABLE))
        } else {
            null
        }
        lootSeed = tag.getLong(NBT_LOOT_SEED)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (!item.isEmpty) tag.put(NBT_ITEM, item.save(CompoundTag()))
        tag.putFloat(NBT_YAW, yaw)
        lootTable?.let {
            tag.putString(NBT_LOOT_TABLE, it.toString())
            tag.putLong(NBT_LOOT_SEED, lootSeed)
        }
    }

    override fun getUpdateTag(): CompoundTag {
        // The client draws the item, so the roll must occur before the first sync.
        unpackLootTable()
        return saveWithoutMetadata()
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)

    companion object {
        private const val NBT_ITEM = "Item"
        private const val NBT_YAW = "Yaw"
        private const val NBT_LOOT_TABLE = "LootTable"
        private const val NBT_LOOT_SEED = "LootTableSeed"
    }
}
