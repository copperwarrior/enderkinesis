package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.BlockHitResult
import org.shipwrights.enderkinesis.block.GuardianSpikeBase
import org.shipwrights.enderkinesis.block.GuardianSpikeBlock
import org.shipwrights.enderkinesis.block.GuardianSpikeOuterBlock
import org.shipwrights.enderkinesis.block.SpikeAttachment
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * A click near the edge or corner of a face grows the spike into the crease against the neighbouring
 * wall if there is one, else off the clicked block's outer edge or corner, else flat on the face.
 * Face and crease spikes join the spikes already in their cell when they share a corner.
 */
class GuardianSpikeItem(block: Block, props: Properties) : BlockItem(block, props) {

    private class Plan(val pos: BlockPos, val state: BlockState)

    private val cluster: GuardianSpikeBlock get() = block as GuardianSpikeBlock

    override fun registerBlocks(map: MutableMap<Block, Item>, item: Item) {
        super.registerBlocks(map, item)
        map[EKBlocks.GUARDIAN_SPIKE_OUTER.get()] = item
    }

    override fun updatePlacementContext(ctx: BlockPlaceContext): BlockPlaceContext =
        plan(ctx)?.let { contextAt(ctx, it) } ?: ctx

    // VS2's MixinBlockItem calls this with place()'s original context, not the updated one, while
    // placeBlock still uses the updated one. Plan from the click, which both contexts share.
    override fun getPlacementState(ctx: BlockPlaceContext): BlockState? {
        val plan = plan(ctx)
        val target = plan?.let { contextAt(ctx, it) } ?: return super.getPlacementState(ctx)
        return plan.state.takeIf { canPlace(target, it) }
    }

    private fun contextAt(ctx: BlockPlaceContext, plan: Plan): BlockPlaceContext? {
        if (plan.pos == ctx.clickedPos) return ctx
        val moved = BlockPlaceContext(ctx.level, ctx.player, ctx.hand, ctx.itemInHand, BlockHitResult(ctx.clickLocation, ctx.clickedFace, plan.pos, false))
        // A solid target makes the context step one more cell along the face, so require the exact target.
        return moved.takeIf { it.clickedPos == plan.pos && it.canPlace() }
    }

    private fun plan(ctx: BlockPlaceContext): Plan? {
        val level = ctx.level
        val face = ctx.clickedFace
        val hit = ctx.clickLocation
        val support = BlockPos.containing(hit.x - face.stepX * HIT_INSET, hit.y - face.stepY * HIT_INSET, hit.z - face.stepZ * HIT_INSET)
        val supportState = level.getBlockState(support)
        // Not ctx.replacingClickedOnBlock(): a moved context reports true for its open target.
        if (supportState.canBeReplaced() || supportState.block is GuardianSpikeBase) return null
        val cell = support.relative(face)
        val local = doubleArrayOf(hit.x - support.x, hit.y - support.y, hit.z - support.z)
        val toward = Direction.Axis.entries.filter { it != face.axis }.mapNotNull { axis ->
            val l = local[axis.ordinal]
            when {
                l < EDGE_MARGIN -> Direction.fromAxisAndDirection(axis, Direction.AxisDirection.NEGATIVE)
                l > 1.0 - EDGE_MARGIN -> Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE)
                else -> null
            }
        }
        if (toward.isEmpty()) return inCell(level, cell, intArrayOf(-face.stepX, -face.stepY, -face.stepZ))
        return crease(level, face, cell, toward) ?: outer(level, support, cell, toward)
    }

    /** The click cell, against every neighbouring wall the click leans toward. */
    private fun crease(level: Level, face: Direction, cell: BlockPos, toward: List<Direction>): Plan? {
        val walls = toward.filter { dir ->
            val wall = cell.relative(dir)
            level.getBlockState(wall).isFaceSturdy(level, wall, dir.opposite)
        }
        if (walls.isEmpty()) return null
        val d = intArrayOf(-face.stepX, -face.stepY, -face.stepZ)
        for (w in walls) { d[0] += w.stepX; d[1] += w.stepY; d[2] += w.stepZ }
        return inCell(level, cell, d)
    }

    /** The open cell past the clicked block's edge or corner, if the edge or corner sticks out. */
    private fun outer(level: Level, support: BlockPos, cell: BlockPos, toward: List<Direction>): Plan? {
        var target = cell
        for (dir in toward) target = target.relative(dir)
        if (!level.getBlockState(target).canBeReplaced()) return null
        val attach = SpikeAttachment.of(support.x - target.x, support.y - target.y, support.z - target.z) ?: return null
        val sealed = attach.flanks.any {
            val flank = target.offset(it)
            !level.getBlockState(flank).getCollisionShape(level, flank).isEmpty
        }
        if (sealed) return null
        val state = EKBlocks.GUARDIAN_SPIKE_OUTER.get().defaultBlockState()
            .setValue(GuardianSpikeOuterBlock.ATTACH, attach)
            .setValue(GuardianSpikeBase.WATERLOGGED, isWater(level, target))
        return if (state.canSurvive(level, target)) Plan(target, state) else null
    }

    /** A face or crease spike, joined to the spikes already in [pos]. */
    private fun inCell(level: Level, pos: BlockPos, d: IntArray): Plan? {
        val attach = SpikeAttachment.of(d[0], d[1], d[2]) ?: return null
        val state = cluster.withSpike(level.getBlockState(pos), attach, isWater(level, pos)) ?: return null
        return if (state.canSurvive(level, pos)) Plan(pos, state) else null
    }

    private fun isWater(level: Level, pos: BlockPos): Boolean = level.getFluidState(pos).type === Fluids.WATER

    private companion object {
        /** Matches the plate's 4 px half-width, so the click zone is where the folded plate would land. */
        const val EDGE_MARGIN = 0.25
        const val HIT_INSET = 1.0e-3
    }
}
