package org.shipwrights.enderkinesis.construction

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.item.DiggerItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.PickaxeItem
import net.minecraft.world.item.ShearsItem
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.ceil

/** The user's rule: only a Clawhod hand with one of these breaks blocks. */
fun isToolItem(item: Item): Boolean = item is DiggerItem || item is ShearsItem

fun isClawhodTool(stack: ItemStack): Boolean = !stack.isEmpty && isToolItem(stack.item)

/** The tool in [slots], or [ItemStack.EMPTY]. */
internal fun handTool(slots: List<ItemStack>): ItemStack {
    for (slot in slots) if (isClawhodTool(slot)) return slot
    return ItemStack.EMPTY
}

/** The tool type for the block. */
internal const val TOOL_MATCH = 2

/** A pickaxe on a block it is not the tool for: the user's fallback. */
internal const val TOOL_FALLBACK = 1

/** An instant block (grass, flowers): no tool breaks it faster than a bare hand, so it never waits for one. */
internal fun needsNoTool(state: BlockState): Boolean = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) == 0f

/** How well [tool] suits [state]: [TOOL_MATCH], [TOOL_FALLBACK], or 0 when a hand does not take it for the block. */
internal fun toolRank(tool: ItemStack, state: BlockState): Int = when {
    !isClawhodTool(tool) -> 0
    tool.getDestroySpeed(state) > 1f || tool.isCorrectToolForDrops(state) -> TOOL_MATCH
    tool.item is PickaxeItem -> TOOL_FALLBACK
    else -> 0
}

/** Vanilla Player.getDestroySpeed without effects: Efficiency adds level² + 1 only where the tool is fast. */
internal fun digSpeed(base: Float, efficiency: Int): Float {
    require(base >= 0f) { "Speed must not be negative: $base" }
    require(efficiency >= 0) { "Efficiency must not be negative: $efficiency" }
    return if (base > 1f && efficiency > 0) base + efficiency * efficiency + 1 else base
}

/** Vanilla bare-hand and tool time: one tick per 1/30 of the hardness over the speed, 1/100 when nothing drops. */
private const val HARVEST_DIVISOR = 30.0
private const val NO_HARVEST_DIVISOR = 100.0

/**
 * Ticks a player on the ground, out of water, with no effects takes to break a block of [hardness] at dig
 * [speed]: the BlockState.getDestroyProgress formula. -1 for an unbreakable block.
 */
internal fun breakTicks(hardness: Float, speed: Float, canHarvest: Boolean): Int {
    require(speed >= 0f) { "Speed must not be negative: $speed" }
    if (hardness < 0f || speed == 0f && hardness > 0f) return -1
    if (hardness == 0f) return 1
    return maxOf(1, ceil(hardness * (if (canHarvest) HARVEST_DIVISOR else NO_HARVEST_DIVISOR) / speed).toInt())
}

/** [breakTicks] for [tool] (empty for a bare hand) on the block [state] at [pos]. */
internal fun breakTicksWith(level: ServerLevel, pos: BlockPos, state: BlockState, tool: ItemStack): Int {
    val efficiency = if (tool.isEmpty) 0 else EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_EFFICIENCY, tool)
    val speed = if (tool.isEmpty) 1f else digSpeed(tool.getDestroySpeed(state), efficiency)
    val canHarvest = !state.requiresCorrectToolForDrops() || !tool.isEmpty && tool.isCorrectToolForDrops(state)
    return breakTicks(state.getDestroySpeed(level, pos), speed, canHarvest)
}

/**
 * One break of [state] at [pos] wears [tool] as a player's would (DiggerItem.mineBlock: not on an instant block;
 * Unbreaking applies). True when the tool broke; it is then empty.
 */
internal fun wearToolOnce(level: ServerLevel, pos: BlockPos, state: BlockState, tool: ItemStack): Boolean {
    if (!isClawhodTool(tool) || !tool.isDamageableItem || state.getDestroySpeed(level, pos) == 0f) return false
    // A null player: a real one would get the break animation on its own main hand.
    if (!tool.hurt(1, level.random, null)) return false
    tool.shrink(1)
    return true
}

/** The best tool for [state] in [container] by [toolRank], or null. Items in [skip] are spoken for. */
internal fun Container.bestToolFor(state: BlockState, skip: (Item) -> Boolean = { false }): Item? {
    var best: Item? = null
    var bestRank = 0
    for (slot in 0 until containerSize) {
        val stack = getItem(slot)
        val rank = toolRank(stack, state)
        if (rank > bestRank && !skip(stack.item)) {
            best = stack.item
            bestRank = rank
        }
    }
    return best
}

/** Takes one whole stack of the tool [item] (damage and enchantments kept) out of the container, or EMPTY. */
internal fun Container.takeTool(item: Item): ItemStack {
    for (slot in 0 until containerSize) {
        val stack = getItem(slot)
        if (!stack.`is`(item)) continue
        val out = removeItem(slot, 1)
        setChanged()
        return out
    }
    return ItemStack.EMPTY
}

/**
 * One hand's tool passes for build-job cuts (redstone mining goes closest first instead): a pass breaks only the
 * blocks its tool is the type for and notes a few others; the next pass takes a tool for one of those; a rest pass
 * breaks whatever is left.
 */
internal class ToolPasses(private val samples: Int = PASS_SAMPLES) {
    private val others = arrayOfNulls<BlockState>(samples)

    var otherCount = 0
        private set

    var restPass = false
        private set

    /** True when [tool] breaks [state] in this pass; else [state] is noted for the next pass. */
    fun takes(tool: ItemStack, state: BlockState): Boolean {
        if (restPass || needsNoTool(state) || toolRank(tool, state) == TOOL_MATCH) return true
        if (otherCount < samples && !noted(state)) others[otherCount++] = state
        return false
    }

    private fun noted(state: BlockState): Boolean {
        for (i in 0 until otherCount) if (others[i]?.block === state.block) return true
        return false
    }

    fun other(index: Int): BlockState? = if (index < otherCount) others[index] else null

    /** The first noted block that [find] gives a result for, with that result, or null. */
    inline fun <T> firstFor(find: (BlockState) -> T?): T? {
        for (i in 0 until otherCount) {
            val found = other(i)?.let(find)
            if (found != null) return found
        }
        return null
    }

    fun clearOthers() {
        others.fill(null)
        otherCount = 0
    }

    /** A new tool: the next pass starts. */
    fun startPass() {
        restPass = false
        clearOthers()
    }

    /** No tool in reach is for the blocks left: the rest pass breaks them all. */
    fun startRest() {
        restPass = true
        clearOthers()
    }

    private companion object {
        const val PASS_SAMPLES = 4
    }
}

/** In a build job's rest pass a tool hand cuts only with a pickaxe or a tool for the block; bare hands take the rest. */
internal fun restCuts(tool: ItemStack, state: BlockState): Boolean = tool.item is PickaxeItem || toolRank(tool, state) > 0

/** A tool in a chest: where, and which. */
internal class ToolSource(val pos: BlockPos, val item: Item)

/**
 * The best tool for [state] in [chests] that pass [usable] (loaded, in reach, permission), by [toolRank]; only the
 * block's tool type with [matchOnly]. [skip] drops tools spoken for or refused. Null when there is none.
 */
internal fun bestToolIn(
    level: ServerLevel, chests: Iterable<BlockPos>, state: BlockState, matchOnly: Boolean,
    usable: (BlockPos) -> Boolean, skip: (BlockPos, Item) -> Boolean = { _, _ -> false },
): ToolSource? {
    var best: ToolSource? = null
    var bestRank = 0
    for (pos in chests) {
        if (!level.isLoaded(pos) || !usable(pos)) continue
        val container = HopperBlockEntity.getContainerAt(level, pos) ?: continue
        val item = container.bestToolFor(state) { skip(pos, it) } ?: continue
        val rank = toolRank(item.defaultInstance, state)
        if (rank > bestRank && (!matchOnly || rank == TOOL_MATCH)) {
            best = ToolSource(pos, item)
            bestRank = rank
        }
    }
    return best
}
