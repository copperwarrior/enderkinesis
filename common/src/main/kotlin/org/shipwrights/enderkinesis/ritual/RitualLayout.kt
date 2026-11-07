package org.shipwrights.enderkinesis.ritual

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Property

/** One template block, as an offset from the center block. A world block in [alternative] also matches, with no property check. */
class LayoutBlock(val offset: BlockPos, val state: BlockState, private val alternative: TagKey<Block>?) {
    private val checked: List<Property<*>> = state.properties.filter { it !in IGNORED }

    fun matches(world: BlockState): Boolean {
        val same = world.`is`(state.block)
        if (!same && (alternative == null || !world.`is`(alternative))) return false
        if (same && checked.any { world.getValue(it) != state.getValue(it) }) return false
        return !world.hasProperty(BlockStateProperties.LIT) || world.getValue(BlockStateProperties.LIT)
    }

    companion object {
        // The builder saves the template unlit; matching asks for lit blocks instead.
        private val IGNORED = setOf<Property<*>>(BlockStateProperties.LIT, BlockStateProperties.WATERLOGGED)
    }
}

/** A structure template with air as "any block", in the four rotations about its center block. */
class RitualLayout(val rotations: List<List<LayoutBlock>>) {

    /** The first rotation index whose blocks all match around [center], or -1. The center block is not checked. */
    fun match(level: ServerLevel, center: BlockPos): Int {
        val cursor = BlockPos.MutableBlockPos()
        return rotations.indexOfFirst { blocks ->
            blocks.all { it.matches(level.getBlockState(cursor.setWithOffset(center, it.offset))) }
        }
    }

    companion object {
        private val ROTATIONS = listOf(Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90)

        /** Reads a structure-block .nbt; returns null unless exactly one block passes [isCenter]. */
        fun fromTemplate(tag: CompoundTag, isCenter: (BlockState) -> Boolean, alternatives: Map<Block, TagKey<Block>>): RitualLayout? {
            val blockLookup = BuiltInRegistries.BLOCK.asLookup()
            val palette = tag.getList("palette", Tag.TAG_COMPOUND.toInt()).map { NbtUtils.readBlockState(blockLookup, it as CompoundTag) }
            val placed = tag.getList("blocks", Tag.TAG_COMPOUND.toInt()).mapNotNull { element ->
                val block = element as CompoundTag
                val pos = block.getList("pos", Tag.TAG_INT.toInt())
                val state = palette.getOrNull(block.getInt("state")) ?: return null
                if (state.isAir) null else BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)) to state
            }
            val center = placed.singleOrNull { isCenter(it.second) }?.first ?: return null
            val base = placed.filter { it.first != center }.map { (pos, state) -> pos.subtract(center) to state }
            return RitualLayout(
                ROTATIONS.map { rotation ->
                    base.map { (offset, state) -> LayoutBlock(offset.rotate(rotation), state.rotate(rotation), alternatives[state.block]) }
                },
            )
        }
    }
}
