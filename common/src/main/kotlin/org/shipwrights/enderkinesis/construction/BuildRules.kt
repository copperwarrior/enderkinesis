package org.shipwrights.enderkinesis.construction

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.FluidTags
import net.minecraft.world.Container
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.shipwrights.enderkinesis.spell.SpellPlatform
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.isBlockInShipyard

internal fun BlockState.isReplaceable(): Boolean = isAir || canBeReplaced()

/** Build tuning from the server config. */
internal fun buildTuning(): BuildTuning = ServerBuildConfig.current.build

/**
 * Spawn protection, the world border and adventure mode as for the owner's own hand, then the loader's
 * place event for claim mods. Fabric has no place event, so there only the first checks apply.
 */
internal fun mayBuild(level: ServerLevel, actor: ServerPlayer, pos: BlockPos, state: BlockState): Boolean =
    mayEdit(level, actor, pos) && SpellPlatform.mayPlace(level, pos, state, actor)

internal fun mayEdit(level: ServerLevel, actor: ServerPlayer, pos: BlockPos): Boolean =
    actor.abilities.mayBuild && level.mayInteract(actor, pos)

// Create's schematicannon rule: water blocks such as kelp boil away in an ultra-warm dimension, so they are not built.
internal fun waterInHotLevel(level: ServerLevel, state: BlockState): Boolean =
    level.dimensionType().ultraWarm() && state.fluidState.`is`(FluidTags.WATER)

internal fun canStand(level: ServerLevel, state: BlockState, pos: BlockPos): Boolean {
    if (!state.canSurvive(level, pos)) return false
    if (state.block !is FallingBlock) return true
    return !FallingBlock.isFree(level.getBlockState(pos.below()))
}

/** Bit for [Direction] [d] in an [attachMask]. */
internal fun attachBit(d: Direction): Int = 1 shl d.get3DDataValue()

/** [attachMask] bit: the block names no support of its own (a floor skull, a candle); see [attachMask]. */
internal const val ATTACH_UNNAMED = 1 shl 6

/**
 * The plan neighbours [state] hangs from or sticks to, as [attachBit]s, from its own properties: attach face, hanging,
 * facing, a vertical axis. 0 for a block with a sturdy face, which stands on its own. [ATTACH_UNNAMED] for a block
 * that has none of these: it waits for a hanging block over it, or a sturdy one under it.
 */
internal fun attachMask(state: BlockState): Int {
    for (d in DIRECTIONS) if (state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, d)) return 0
    return when {
        state.hasProperty(BlockStateProperties.ATTACH_FACE) -> when (state.getValue(BlockStateProperties.ATTACH_FACE)) {
            AttachFace.FLOOR -> attachBit(Direction.DOWN)
            AttachFace.CEILING -> attachBit(Direction.UP)
            else -> attachBit(state.getValue(BlockStateProperties.HORIZONTAL_FACING).opposite)
        }
        state.hasProperty(BlockStateProperties.HANGING) ->
            attachBit(if (state.getValue(BlockStateProperties.HANGING)) Direction.UP else Direction.DOWN)
        state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ->
            attachBit(state.getValue(BlockStateProperties.HORIZONTAL_FACING).opposite)
        state.hasProperty(BlockStateProperties.FACING) -> attachBit(state.getValue(BlockStateProperties.FACING).opposite)
        // A chain hangs: it waits for the block over it, never for the one under it, so a hanging line has no loop.
        state.hasProperty(BlockStateProperties.AXIS) && state.getValue(BlockStateProperties.AXIS) == Direction.Axis.Y ->
            attachBit(Direction.UP)
        else -> ATTACH_UNNAMED
    }
}

private val DIRECTIONS = Direction.values()

internal fun Container.countOf(item: Item): Int {
    var total = 0
    for (slot in 0 until containerSize) {
        val stack = getItem(slot)
        if (stack.`is`(item)) total += stack.count
    }
    return total
}

/** Takes [count] of [item] out of the container; false (and nothing taken) when it has fewer. */
internal fun Container.takeItems(item: Item, count: Int): Boolean {
    if (countOf(item) < count) return false
    var left = count
    for (slot in 0 until containerSize) {
        if (left == 0) break
        val stack = getItem(slot)
        if (!stack.`is`(item)) continue
        left -= removeItem(slot, minOf(left, stack.count)).count
    }
    setChanged()
    return left == 0
}

/** True when a slot already holds [stack]'s item and tags: the user's rule puts like with like. */
internal fun Container.holdsSame(stack: ItemStack): Boolean {
    for (slot in 0 until containerSize) if (ItemStack.isSameItemSameTags(getItem(slot), stack)) return true
    return false
}

/** How many of [stack] the container could take now, at most the stack's count. */
internal fun Container.roomFor(stack: ItemStack): Int {
    var room = 0
    for (slot in 0 until containerSize) {
        if (room >= stack.count) break
        if (!canPlaceItem(slot, stack)) continue
        val here = getItem(slot)
        val limit = minOf(maxStackSize, stack.maxStackSize)
        room += when {
            here.isEmpty -> limit
            ItemStack.isSameItemSameTags(here, stack) -> (limit - here.count).coerceAtLeast(0)
            else -> 0
        }
    }
    return minOf(room, stack.count)
}

/** A linked supply that is gone: its chunk is loaded and no container is there. An unloaded chunk proves nothing. */
internal fun isStaleSupply(level: ServerLevel, pos: BlockPos): Boolean = staleSupplyWhy(level, pos) != null

/**
 * The job's supplies after a table scan finds [linked]: ones not found go, kept ones keep their order,
 * new ones follow in link order, with no repeat and at most [max].
 */
internal fun <T> syncedSupplies(current: List<T>, linked: List<T>, max: Int): List<T> {
    require(max >= 0) { "max must not be negative: $max" }
    val wanted = LinkedHashSet(linked)
    val out = LinkedHashSet<T>()
    for (s in current) if (s in wanted) out.add(s)
    out.addAll(wanted)
    return out.take(max)
}

/** Why the linked supply at [pos] is gone, for the log, or null while it can still be a supply. */
internal fun staleSupplyWhy(level: ServerLevel, pos: BlockPos): String? = when {
    // VS2 getShipManagingPos reads allShips, so an unloaded ship still has its cells; only a deleted ship has none.
    level.isBlockInShipyard(pos) && level.getShipManagingPos(pos) == null -> "its ship is gone"
    level.isLoaded(pos) && HopperBlockEntity.getContainerAt(level, pos) == null -> "no container there now"
    else -> null
}
