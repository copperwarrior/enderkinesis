package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.ContainerHelper
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.block.OarscribeBlock
import org.shipwrights.enderkinesis.block.OarscribeFrame
import org.shipwrights.enderkinesis.client.OarscribeGlyphs
import org.shipwrights.enderkinesis.client.OarscribeSounds
import org.shipwrights.enderkinesis.physics.OarscribeRail
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.core.api.VsBeta

/**
 * Holds one penfeather and a tank of Ichaeric ink. Hoppers can load the penfeather and pipes can fill the tank,
 * but neither can empty them. Ink amounts are in droplets, [INK_PER_BUCKET] to a bucket, on both loaders.
 */
@OptIn(GameTickOnly::class, VsBeta::class)
class OarscribeBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.OARSCRIBE.get(), pos, state), WorldlyContainer {

    private val items: NonNullList<ItemStack> = NonNullList.withSize(1, ItemStack.EMPTY)

    var ink = 0L
        private set

    /** Pen damage owed, below one point. */
    private var wear = 0.0

    /** Client thread only. The world position of the last glyph, while the trail is live. */
    var trailLive = false
    var trailX = 0.0
    var trailY = 0.0
    var trailZ = 0.0

    /** Client thread only. Null until the first client tick, so a chunk load does not play the start sound. */
    var heardWriting: Boolean? = null

    val hasPenfeather: Boolean get() = !items[PENFEATHER].isEmpty

    val inkSpace: Long get() = INK_CAPACITY - ink

    /** Takes one penfeather from [stack]; returns true when it did. */
    fun loadPenfeather(stack: ItemStack): Boolean {
        if (hasPenfeather || !canPlaceItem(PENFEATHER, stack)) return false
        items[PENFEATHER] = stack.split(1)
        setChanged()
        return true
    }

    /** Adds up to [amount] droplets of ink; returns the droplets that fit. */
    fun fillInk(amount: Long): Long {
        require(amount >= 0) { "Negative ink amount $amount" }
        val accepted = minOf(amount, inkSpace)
        if (accepted > 0) {
            ink += accepted
            setChanged()
        }
        return accepted
    }

    /** For loader fluid transactions, which roll back without a block update. */
    fun restoreInk(amount: Long) {
        ink = amount.coerceIn(0L, INK_CAPACITY)
    }

    fun tick(level: Level, pos: BlockPos, state: BlockState) {
        if (level.isClientSide) {
            OarscribeGlyphs.tick(this, level, pos, state)
            OarscribeSounds.tick(this, level, pos, state)
        } else {
            serverTick(level as ServerLevel, pos, state)
        }
    }

    private fun serverTick(level: ServerLevel, pos: BlockPos, state: BlockState) {
        val ship = level.getLoadedShipManagingPos(pos)
        if (ship == null) {
            showWriting(level, pos, state, false)
            return
        }
        val power = level.getBestNeighborSignal(pos)
        val writing = power > 0 && hasPenfeather && ink >= INK_PER_TICK
        if (writing) {
            ink -= INK_PER_TICK
            // No comparator output and no penfeather change, so only the chunk needs to be marked dirty.
            level.blockEntityChanged(pos)
            val rail = ship.getOrPutAttachment(OarscribeRail::class.java) { OarscribeRail() }
            rail.drive(pos.asLong(), OarscribeFrame.right(state), power)
            wearPen(level, pos, rail.strain)
        } else {
            releaseFrom(ship)
        }
        showWriting(level, pos, state, writing)
    }

    private fun showWriting(level: ServerLevel, pos: BlockPos, state: BlockState, writing: Boolean) {
        if (state.getValue(OarscribeBlock.WRITING) != writing) {
            level.setBlock(pos, state.setValue(OarscribeBlock.WRITING, writing), Block.UPDATE_CLIENTS)
        }
    }

    /** The harder the rail pulls against its force limit, the faster the pen wears. */
    private fun wearPen(level: ServerLevel, pos: BlockPos, strain: Double) {
        wear += MIN_WEAR_PER_TICK + (MAX_WEAR_PER_TICK - MIN_WEAR_PER_TICK) * strain.coerceIn(0.0, 1.0)
        if (wear < 1.0) return
        wear -= 1.0
        val pen = items[PENFEATHER]
        if (pen.hurt(1, level.random, null)) {
            items[PENFEATHER] = ItemStack.EMPTY
            level.playSound(null, pos, EKSounds.OARSCRIBE_PEN_BREAK.get(), SoundSource.BLOCKS, 1f, 1f)
        }
        setChanged()
    }

    override fun setRemoved() {
        (level as? ServerLevel)?.let { releaseFrom(it.getLoadedShipManagingPos(worldPosition)) }
        super.setRemoved()
    }

    private fun releaseFrom(ship: LoadedServerShip?) {
        ship?.getAttachment(OarscribeRail::class.java)?.release(worldPosition.asLong())
    }

    override fun setChanged() {
        super.setChanged()
        val level = level ?: return
        if (level.isClientSide) return
        val state = level.getBlockState(worldPosition)
        if (state.block !is OarscribeBlock) return
        val shown = state.setValue(OarscribeBlock.PENFEATHER, hasPenfeather)
        if (shown != state) level.setBlock(worldPosition, shown, Block.UPDATE_ALL)
    }

    override fun getContainerSize(): Int = 1
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
        if (stack.count > 1) stack.count = 1
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

    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean =
        slot == PENFEATHER && items[slot].isEmpty && stack.`is`(EKItems.PENFEATHER_OF_THE_ACCURSED_OWL.get())

    override fun load(tag: CompoundTag) {
        super.load(tag)
        items.replaceAll { ItemStack.EMPTY }
        ContainerHelper.loadAllItems(tag, items)
        ink = tag.getLong(INK_KEY).coerceIn(0L, INK_CAPACITY)
        wear = tag.getDouble(WEAR_KEY)
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        ContainerHelper.saveAllItems(tag, items)
        tag.putLong(INK_KEY, ink)
        tag.putDouble(WEAR_KEY, wear)
    }

    companion object {
        const val PENFEATHER = 0
        private val ALL_SLOTS = intArrayOf(PENFEATHER)
        private const val MAX_USE_DISTANCE_SQR = 64.0
        private const val INK_KEY = "Ink"
        private const val WEAR_KEY = "PenWear"

        const val PENFEATHER_DURABILITY = 1000

        /** One point each 30 s with no strain, and one each 0.5 s at the force limit. */
        private const val MIN_WEAR_PER_TICK = 1.0 / 600.0
        private const val MAX_WEAR_PER_TICK = 1.0 / 10.0

        /** Fabric droplets. Forge millibuckets are 81 droplets each. */
        const val INK_PER_BUCKET = 81_000L
        const val INK_PER_BOTTLE = INK_PER_BUCKET / 3
        const val INK_CAPACITY = INK_PER_BUCKET

        /** 30 s of writing for each bottle. */
        private const val INK_PER_TICK = INK_PER_BOTTLE / 600
    }
}
