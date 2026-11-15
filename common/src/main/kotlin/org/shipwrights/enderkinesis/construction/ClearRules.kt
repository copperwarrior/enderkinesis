package org.shipwrights.enderkinesis.construction

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BubbleColumnBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BeehiveBlockEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BrushableBlockEntity
import net.minecraft.world.level.block.entity.CampfireBlockEntity
import net.minecraft.world.level.block.entity.LecternBlockEntity
import net.minecraft.world.level.block.entity.SpawnerBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate

/**
 * True when the world block counts as the plan block. After Structurize `BlockUtils.areBlockStatesEqual`
 * and the MineColonies builder rule: any dirt-tag block counts as dirt, grass and dirt count as each other,
 * and every kind of air is air. Waterlogging is ignored (Create's rule), and so are power and LIT.
 */
fun planMatches(plan: BlockState, world: BlockState): Boolean = planMatches(plan, world, ::isDirtTagged)

// Only a data load binds block tags, so a unit test gives its own dirt check.
internal fun planMatches(plan: BlockState, world: BlockState, isDirt: (BlockState) -> Boolean): Boolean {
    if (plan.isAir) return world.isAir
    if (world.forPlanMatch() == plan.forPlanMatch()) return true
    val p = plan.block
    val w = world.block
    return (p == Blocks.DIRT && isDirt(world)) ||
        (p == Blocks.GRASS_BLOCK && w == Blocks.DIRT)
}

private fun isDirtTagged(state: BlockState): Boolean = state.`is`(BlockTags.DIRT)

/**
 * The right block in the wrong state, fixed in place at no cost. A change that alters the item count
 * (slab type, candles) or a two-part block is not a fix.
 */
fun fixableInPlace(plan: BlockState, world: BlockState): Boolean =
    !plan.isAir && world.block == plan.block && world.forPlanMatch() != plan.forPlanMatch() &&
        !plan.isFirstHalf() && !plan.isSecondHalf() &&
        plan.planCost() is PlanCost.Items && world.planCost() == plan.planCost()

/** The plan state with the world's water kept, so a fix never removes a fluid. */
fun fixedState(plan: BlockState, world: BlockState): BlockState =
    if (plan.hasProperty(BlockStateProperties.WATERLOGGED) && world.hasProperty(BlockStateProperties.WATERLOGGED)) {
        plan.setValue(BlockStateProperties.WATERLOGGED, world.getValue(BlockStateProperties.WATERLOGGED))
    } else {
        plan
    }

enum class ClearKind {
    /** Nothing to remove here. */
    NONE,

    /** Cut the world block into the clawhod. */
    CUT,

    /** Scoop a fluid source with a bucket. */
    SCOOP,
}

/**
 * What CLEAR does at a cell. [plan] is null where the plan has air. A plan block goes over a
 * replaceable world block (fluids too, Create's rule), so only plan air clears fluids. A plan
 * block that v1 never builds keeps the world block. Flowing fluid cannot be scooped and dries
 * once its sources are gone.
 */
fun clearKind(plan: BlockState?, world: BlockState): ClearKind {
    if (plan != null && !plan.isAir) {
        return when {
            plan.planCost() is PlanCost.Refused -> ClearKind.NONE
            planMatches(plan, world) || world.isReplaceable() || fixableInPlace(plan, world) -> ClearKind.NONE
            else -> ClearKind.CUT
        }
    }
    if (world.isAir) return ClearKind.NONE
    if (world.block is LiquidBlock || world.block is BubbleColumnBlock) {
        return if (world.fluidState.isSource) ClearKind.SCOOP else ClearKind.NONE
    }
    return ClearKind.CUT
}

/**
 * True when a block entity holds things a cut would spill or lose: items, a book, bees, a mob
 * spawner, or archaeology loot. A worker never cuts such a block.
 */
fun BlockEntity.holdsContents(): Boolean = when (this) {
    is Container -> !isEmpty
    is LecternBlockEntity -> hasBook()
    is CampfireBlockEntity -> items.any { !it.isEmpty }
    is BeehiveBlockEntity -> !isEmpty
    is SpawnerBlockEntity, is BrushableBlockEntity -> true
    else -> false
}

/** The other half of a door, tall plant or bed, or null. */
fun BlockState.otherHalf(pos: BlockPos): BlockPos? = when {
    block is BedBlock -> {
        val facing = getValue(BedBlock.FACING)
        pos.relative(if (getValue(BedBlock.PART) == BedPart.FOOT) facing else facing.opposite)
    }
    hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) ->
        pos.relative(if (getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) Direction.UP else Direction.DOWN)
    else -> null
}

/** Plan-local cell of a world position: the inverse of [cellWorldPos]. */
fun PlanAnchor.cellLocalPos(world: BlockPos): BlockPos {
    val back = when (rotation) {
        Rotation.CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90
        Rotation.COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90
        else -> rotation
    }
    val unrotated = StructureTemplate.transform(world.subtract(pos), Mirror.NONE, back, BlockPos.ZERO)
    return StructureTemplate.transform(unrotated, mirror, Rotation.NONE, BlockPos.ZERO)
}

/**
 * [cellLocalPos] for x and z with no allocation, packed as x in the high and z in the low 32 bits. Vanilla
 * StructureTemplate.transform mirrors first, then turns; this undoes the turn, then the mirror.
 */
internal fun planLocal(anchor: PlanAnchor, x: Int, z: Int): Long {
    val dx = x - anchor.pos.x
    val dz = z - anchor.pos.z
    var lx: Int
    var lz: Int
    when (anchor.rotation) {
        // The reverse turn of CLOCKWISE_90 is COUNTERCLOCKWISE_90: (x, z) -> (z, -x), and so on.
        Rotation.CLOCKWISE_90 -> { lx = dz; lz = -dx }
        Rotation.COUNTERCLOCKWISE_90 -> { lx = -dz; lz = dx }
        Rotation.CLOCKWISE_180 -> { lx = -dx; lz = -dz }
        else -> { lx = dx; lz = dz }
    }
    when (anchor.mirror) {
        Mirror.LEFT_RIGHT -> lz = -lz
        Mirror.FRONT_BACK -> lx = -lx
        else -> Unit
    }
    return (lx.toLong() shl 32) or (lz.toLong() and 0xffffffffL)
}

internal fun planLocalX(packed: Long): Int = (packed shr 32).toInt()

internal fun planLocalZ(packed: Long): Int = packed.toInt()

/** What a cut yields: [carry] goes in the clawhod, [rest] drops at the cell. */
class CutYield(val carry: ItemStack, val rest: List<ItemStack>)

/**
 * Splits loot drops. The clawhod carries one stack of one plain block item, so the first such item
 * is merged and carried; other drops (gems, sticks, stacks with data) try the pickup slots, then fall
 * where a player's break would drop them. An item whose block gives back another item is not carried, so nothing changes
 * when the clawhod empties.
 */
fun splitYield(drops: List<ItemStack>): CutYield {
    val carryItem = drops.firstOrNull { it.isCarriable() }?.item ?: return CutYield(ItemStack.EMPTY, drops)
    var count = 0
    val rest = ArrayList<ItemStack>()
    for (stack in drops) {
        if (stack.item == carryItem && !stack.hasTag()) count += stack.count else rest.add(stack)
    }
    val max = ItemStack(carryItem).maxStackSize
    if (count > max) {
        rest.add(ItemStack(carryItem, count - max))
        count = max
    }
    return CutYield(ItemStack(carryItem, count), rest)
}

private fun ItemStack.isCarriable(): Boolean = clawhodBlockOf(this)?.asItem() == item
