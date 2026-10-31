package org.shipwrights.enderkinesis.block

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlockContainer
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluids
import org.shipwrights.enderkinesis.mixin.FlowingFluidCanHoldAccessor
import java.util.PriorityQueue

/** Fills the basin around a Rolling Waters rite with water, one layer at a time from the bottom. */
object WohlonRollingWaters {

    private const val MAX_WATER = 1024
    private const val RADIUS = 24
    private const val DEPTH = 24
    private const val MAX_VISITED = 16384
    private const val LAYER_INTERVAL_TICKS = 10L

    private class Fill(val layers: ArrayDeque<LongArray>, var nextTick: Long)

    private val fills = HashMap<ResourceKey<Level>, MutableList<Fill>>()

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::tick)
        LifecycleEvent.SERVER_STOPPED.register { fills.clear() }
    }

    /** False when the water has no basin to stay in. */
    fun start(level: ServerLevel, center: BlockPos): Boolean {
        val cells = plan(level, center)
        if (cells.isEmpty()) return false
        val layers = ArrayDeque<LongArray>()
        var from = 0
        for (i in 1..cells.size) {
            if (i == cells.size || BlockPos.getY(cells[i]) != BlockPos.getY(cells[from])) {
                layers.addLast(cells.copyOfRange(from, i))
                from = i
            }
        }
        fills.getOrPut(level.dimension()) { mutableListOf() }.add(Fill(layers, level.gameTime))
        return true
    }

    /**
     * Pours from the rite center: the lowest open cell next to the water fills first, as real water does.
     * When that cell is out of bounds the basin spills at its height, so only the layers below it stay.
     */
    private fun plan(level: ServerLevel, center: BlockPos): LongArray {
        val queue = PriorityQueue<Long>(compareBy<Long>({ BlockPos.getY(it) }, { horizontalDistSq(it, center) }))
        val seen = HashSet<Long>()
        val accepted = ArrayList<Long>()
        var spillY = Int.MAX_VALUE
        var fullY = Int.MAX_VALUE
        var units = 0
        queue.add(center.asLong())
        seen.add(center.asLong())
        val pos = BlockPos.MutableBlockPos()
        while (queue.isNotEmpty() && seen.size < MAX_VISITED) {
            val packed = queue.poll()
            val y = BlockPos.getY(packed)
            if (y > fullY) break
            if (!inBounds(packed, center)) {
                spillY = y
                break
            }
            if (fullY != Int.MAX_VALUE) continue
            pos.set(packed)
            val state = level.getBlockState(pos)
            accepted.add(packed)
            if (!isWater(state)) units++
            if (units >= MAX_WATER) fullY = y
            for (dir in Direction.entries) {
                val next = BlockPos.offset(packed, dir)
                if (!seen.add(next)) continue
                pos.set(next)
                if (level.isLoaded(pos) && isOpen(level, pos, level.getBlockState(pos))) queue.add(next)
            }
        }
        if (seen.size >= MAX_VISITED) spillY = minOf(spillY, queue.peek()?.let(BlockPos::getY) ?: Int.MAX_VALUE)
        return accepted.filter { BlockPos.getY(it) < spillY }.sortedWith(compareBy({ BlockPos.getY(it) }, { horizontalDistSq(it, center) })).toLongArray()
    }

    private fun horizontalDistSq(packed: Long, center: BlockPos): Int {
        val dx = BlockPos.getX(packed) - center.x
        val dz = BlockPos.getZ(packed) - center.z
        return dx * dx + dz * dz
    }

    private fun inBounds(packed: Long, center: BlockPos): Boolean =
        horizontalDistSq(packed, center) <= RADIUS * RADIUS && BlockPos.getY(packed) >= center.y - DEPTH

    private fun isWater(state: BlockState): Boolean {
        val fluid = state.fluidState
        return fluid.isSource && fluid.`is`(FluidTags.WATER)
    }

    /** Water may stand here: what is water now, or what vanilla water would flow into or waterlog. */
    private fun isOpen(level: ServerLevel, pos: BlockPos, state: BlockState): Boolean =
        state.fluidState.`is`(FluidTags.WATER) ||
            (Fluids.WATER as FlowingFluidCanHoldAccessor).`enderkinesis$canHoldFluid`(level, pos, state, Fluids.WATER)

    private fun tick(level: ServerLevel) {
        val list = fills[level.dimension()] ?: return
        val iter = list.iterator()
        while (iter.hasNext()) {
            val fill = iter.next()
            if (level.gameTime < fill.nextTick) continue
            val layer = fill.layers.removeFirstOrNull()
            if (layer == null) {
                iter.remove()
                continue
            }
            placeLayer(level, layer)
            fill.nextTick = level.gameTime + LAYER_INTERVAL_TICKS
        }
    }

    private fun placeLayer(level: ServerLevel, layer: LongArray) {
        val pos = BlockPos.MutableBlockPos()
        val source = Fluids.WATER.getSource(false)
        for (packed in layer) {
            pos.set(packed)
            if (!level.isLoaded(pos)) continue
            val state = level.getBlockState(pos)
            if (isWater(state) || !isOpen(level, pos, state)) continue
            val block = state.block
            if (block is LiquidBlockContainer) {
                block.placeLiquid(level, pos, state, source)
                continue
            }
            if (!state.isAir && state.fluidState.isEmpty) level.destroyBlock(pos, true)
            level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3)
        }
    }
}
