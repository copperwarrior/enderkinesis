package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Inscription
import org.shipwrights.enderkinesis.spell.Spell

/**
 * The Altar of Illumination's scroll: the paper it was made from and the glyphs inscribed on
 * it so far. The stored ink is the block's `ink` state, so the basin's fill is a block model.
 *
 * Glyphs can never be taken back out. The only way to undo one is to add its opposite.
 */
// Not a Container: hoppers and other automation must find no inventory here.
class AltarOfIlluminationBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.ALTAR_OF_ILLUMINATION.get(), pos, state) {

    /** The paper the blank scroll was made from, or empty when there's no scroll. */
    var blank: ItemStack = ItemStack.EMPTY
        private set

    var glyphs: List<Element> = emptyList()
        private set

    val hasScroll: Boolean get() = !blank.isEmpty

    fun placeBlank(paper: ItemStack) {
        blank = paper.copyWithCount(1)
        glyphs = emptyList()
        changed()
    }

    /** Resolves [shard] against the glyphs; null when the scroll is full. */
    fun inscribe(shard: Element): Inscription.Outcome? {
        val result = Inscription.inscribe(glyphs, shard) ?: return null
        glyphs = result.glyphs
        changed()
        return result.outcome
    }

    /** Finishes the scroll, clearing the altar; the block spends the ink. Call only with at
     *  least one glyph. */
    fun finish(): ItemStack {
        check(glyphs.isNotEmpty())
        val scroll = ItemStack(EKItems.MYSTIC_SCROLL.get())
        scroll.orCreateTag.put(Spell.NBT_GLYPHS, Spell(glyphs).save())
        blank = ItemStack.EMPTY
        glyphs = emptyList()
        changed()
        return scroll
    }

    /** The paper, if nothing was inscribed: inscribed shards are lost with the scroll. */
    fun drops(): List<ItemStack> = if (hasScroll && glyphs.isEmpty()) listOf(blank.copy()) else emptyList()

    private fun changed() {
        setChanged()
        level?.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun load(tag: CompoundTag) {
        super.load(tag)
        blank = if (tag.contains(NBT_BLANK, Tag.TAG_COMPOUND.toInt())) ItemStack.of(tag.getCompound(NBT_BLANK)) else ItemStack.EMPTY
        glyphs = Spell.load(tag, Spell.NBT_GLYPHS)?.glyphs ?: emptyList()
    }

    override fun saveAdditional(tag: CompoundTag) {
        super.saveAdditional(tag)
        if (!blank.isEmpty) tag.put(NBT_BLANK, blank.save(CompoundTag()))
        tag.put(Spell.NBT_GLYPHS, Spell(glyphs).save())
    }

    override fun getUpdateTag(): CompoundTag = saveWithoutMetadata()

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    companion object {
        private const val NBT_BLANK = "Blank"
    }
}
