package org.shipwrights.enderkinesis.block

import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.IronBarsBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Property
import org.shipwrights.enderkinesis.registry.EKBlocks

/** Maps a destroyed source [BlockState] onto the nearest-matching wogor
 *  variant. Used by both [WohlonnogondoniaSpreader] (direct conversion) and
 *  the heart-driven bud pipeline (resolved at destroy-time, stashed in
 *  [WogorBudTargetData] for the bud to consume at maturation).
 *
 *  Shape families and their wogor counterparts:
 *   - stair → [EKBlocks.WOGOR_WOOD_STAIRS], inherits facing / half / shape
 *   - slab → [EKBlocks.WOGOR_WOOD_SLAB], inherits type (top/bottom/double)
 *   - fence → [EKBlocks.WOGOR_WOOD_FENCE], inherits connection booleans
 *   - wall → [EKBlocks.WOGOR_WOOD_WALL], inherits per-side wall states + up
 *   - iron-bars / glass-pane → [EKBlocks.WOGOR_WOOD_PANE], inherits connection booleans
 *   - button → [EKBlocks.WOGOR_WOOD_BUTTON], geometry-only stub (no props inherited)
 *   - everything else → [EKBlocks.WOGOR_WOOD] (full cube fallback). */
object WogorVariantPicker {

    fun pick(source: BlockState): BlockState {
        val block = source.block
        return when (block) {
            is StairBlock -> copyProps(
                EKBlocks.WOGOR_WOOD_STAIRS.get().defaultBlockState(), source,
                BlockStateProperties.HORIZONTAL_FACING,
                BlockStateProperties.HALF,
                BlockStateProperties.STAIRS_SHAPE,
                BlockStateProperties.WATERLOGGED,
            )
            is SlabBlock -> copyProps(
                EKBlocks.WOGOR_WOOD_SLAB.get().defaultBlockState(), source,
                BlockStateProperties.SLAB_TYPE,
                BlockStateProperties.WATERLOGGED,
            )
            is FenceBlock -> copyProps(
                EKBlocks.WOGOR_WOOD_FENCE.get().defaultBlockState(), source,
                BlockStateProperties.NORTH,
                BlockStateProperties.EAST,
                BlockStateProperties.SOUTH,
                BlockStateProperties.WEST,
                BlockStateProperties.WATERLOGGED,
            )
            is WallBlock -> copyProps(
                EKBlocks.WOGOR_WOOD_WALL.get().defaultBlockState(), source,
                BlockStateProperties.NORTH_WALL,
                BlockStateProperties.EAST_WALL,
                BlockStateProperties.SOUTH_WALL,
                BlockStateProperties.WEST_WALL,
                BlockStateProperties.UP,
                BlockStateProperties.WATERLOGGED,
            )
            is IronBarsBlock -> copyProps(
                EKBlocks.WOGOR_WOOD_PANE.get().defaultBlockState(), source,
                BlockStateProperties.NORTH,
                BlockStateProperties.EAST,
                BlockStateProperties.SOUTH,
                BlockStateProperties.WEST,
                BlockStateProperties.WATERLOGGED,
            )
            is ButtonBlock -> EKBlocks.WOGOR_WOOD_BUTTON.get().defaultBlockState()
            else -> EKBlocks.WOGOR_WOOD.get().defaultBlockState()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun copyProps(initial: BlockState, source: BlockState, vararg props: Property<*>): BlockState {
        var out = initial
        for (prop in props) {
            val typed = prop as Property<Comparable<Any?>>
            if (source.hasProperty(typed) && out.hasProperty(typed)) {
                out = out.setValue(typed, source.getValue(typed))
            }
        }
        return out
    }
}
