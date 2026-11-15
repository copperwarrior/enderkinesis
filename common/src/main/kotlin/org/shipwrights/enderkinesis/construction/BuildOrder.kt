package org.shipwrights.enderkinesis.construction

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue
import it.unimi.dsi.fastutil.ints.IntArrays
import it.unimi.dsi.fastutil.ints.IntComparator
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.AbstractBannerBlock
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.BambooStalkBlock
import net.minecraft.world.level.block.BasePressurePlateBlock
import net.minecraft.world.level.block.BaseRailBlock
import net.minecraft.world.level.block.BellBlock
import net.minecraft.world.level.block.BushBlock
import net.minecraft.world.level.block.CactusBlock
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.CocoaBlock
import net.minecraft.world.level.block.DiodeBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.GrowingPlantBlock
import net.minecraft.world.level.block.HangingRootsBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.LanternBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.MultifaceBlock
import net.minecraft.world.level.block.PointedDripstoneBlock
import net.minecraft.world.level.block.RedStoneWireBlock
import net.minecraft.world.level.block.ScaffoldingBlock
import net.minecraft.world.level.block.SignBlock
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.SugarCaneBlock
import net.minecraft.world.level.block.TorchBlock
import net.minecraft.world.level.block.TripWireHookBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate

fun PlanAnchor.placeSettings(): StructurePlaceSettings =
    StructurePlaceSettings().setRotation(rotation).setMirror(mirror).setRotationPivot(BlockPos.ZERO)

/** The ghost and the build both use this, so a cell is drawn where it is built. Same as [placeSettings]. */
fun PlanAnchor.cellWorldPos(local: BlockPos): BlockPos =
    pos.offset(StructureTemplate.transform(local, mirror, rotation, BlockPos.ZERO))

fun PlanAnchor.cellWorldState(state: BlockState): BlockState = state.mirror(mirror).rotate(rotation)

object CellKind {
    const val SKIP: Byte = 0
    const val SOLID: Byte = 1
    /** Placed after every solid of the section: gravity blocks, and leaves that decay without a log near. */
    const val WEAK: Byte = 2
    const val ATTACHED: Byte = 3
}

/** Order phase only: every task still checks `canSurvive` when it is placed. */
fun BlockState.cellKind(): Byte {
    if (planCost() !is PlanCost.Items) return CellKind.SKIP
    val block = block
    return when {
        block is FallingBlock -> CellKind.WEAK
        block is LeavesBlock && !getValue(LeavesBlock.PERSISTENT) -> CellKind.WEAK
        block is TorchBlock || block is FaceAttachedHorizontalDirectionalBlock || block is BaseRailBlock ||
            block is CarpetBlock || block is VineBlock || block is LadderBlock || block is SignBlock ||
            block is LanternBlock || block is BushBlock || block is DoorBlock || block is RedStoneWireBlock ||
            block is DiodeBlock || block is BasePressurePlateBlock || block is AbstractBannerBlock ||
            block is CandleBlock || block is SnowLayerBlock || block is CactusBlock || block is SugarCaneBlock ||
            block is BambooStalkBlock || block is AmethystClusterBlock || block is PointedDripstoneBlock ||
            block is CocoaBlock || block is TripWireHookBlock || block is MultifaceBlock || block is BellBlock ||
            block is GrowingPlantBlock || block is HangingRootsBlock || block is ScaffoldingBlock -> CellKind.ATTACHED
        else -> CellKind.SOLID
    }
}

/**
 * Sliced build order over packed world positions, after the MineColonies builder's phases: solids, then weak
 * blocks, then attached blocks. Solids finish one layer before the next starts. In a layer, solids grow
 * sideways from cells on the world or on a solid below, then the floating rest follows. Inside a layer each
 * list goes by [rank], the worker's [BuildPattern] column rank of each cell.
 */
class BuildOrderCore(
    private val positions: LongArray,
    private val kinds: ByteArray,
    private val rank: IntArray,
    private val support: Support,
) {
    fun interface Support {
        /** True when the world block at [packed] is solid and is not a plan cell. */
        fun supports(packed: Long): Boolean
    }

    private val n = positions.size
    private val cellAt = Long2IntOpenHashMap(n).apply { defaultReturnValue(-1) }
    private val minY: Int
    private val layerStart: IntArray
    private val byLayer = IntArray(n)
    private val placed = BooleanArray(n)
    private val queue = IntArrayFIFOQueue()
    private val out = IntArray(n)
    private var outSize = 0
    private var phase = Phase.INDEX
    private var cursor = 0
    private var layer = 0

    private val byRank = IntComparator { a, b -> if (rank[a] != rank[b]) rank[a].compareTo(rank[b]) else a.compareTo(b) }

    init {
        require(kinds.size == n) { "kinds (${kinds.size}) and positions ($n) differ" }
        require(rank.size == n) { "rank (${rank.size}) and positions ($n) differ" }
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (p in positions) {
            val y = BlockPos.getY(p)
            if (y < lo) lo = y
            if (y > hi) hi = y
        }
        minY = if (n == 0) 0 else lo
        layerStart = IntArray(if (n == 0) 1 else hi - lo + 2)
    }

    val isDone: Boolean get() = phase == Phase.DONE

    /** Cell indices in build order; valid once [isDone]. Skipped cells are not in it. */
    fun result(): IntArray {
        check(isDone) { "build order is not finished" }
        return out.copyOf(outSize)
    }

    /** Does at most about [budget] cell visits; true once the order is complete. */
    fun step(budget: Int): Boolean {
        require(budget > 0) { "budget must be positive" }
        var left = budget
        while (left > 0 && phase != Phase.DONE) {
            left -= when (phase) {
                Phase.INDEX -> stepIndex(left)
                Phase.BUCKET -> stepBucket(left)
                Phase.SORT -> stepSort()
                Phase.SEEDS -> stepSeeds(left)
                Phase.GROW -> stepGrow(left)
                Phase.FLOATING -> stepFloating(left)
                Phase.WEAK -> stepScan(left, CellKind.WEAK, Phase.ATTACHED)
                Phase.ATTACHED -> stepScan(left, CellKind.ATTACHED, Phase.DONE)
                Phase.DONE -> 0
            }.coerceAtLeast(1)
        }
        return isDone
    }

    private fun stepIndex(budget: Int): Int {
        val end = minOf(n, cursor + budget)
        for (i in cursor until end) {
            if (kinds[i] != CellKind.SKIP) cellAt.put(positions[i], i)
            layerStart[BlockPos.getY(positions[i]) - minY + 1]++
        }
        val did = end - cursor
        cursor = end
        if (cursor == n) {
            for (l in 1 until layerStart.size) layerStart[l] += layerStart[l - 1]
            nextPhase(Phase.BUCKET)
        }
        return did
    }

    // Counting sort by layer; layerStart[l] moves to the end of layer l and back again below.
    private fun stepBucket(budget: Int): Int {
        val end = minOf(n, cursor + budget)
        for (i in cursor until end) {
            val l = BlockPos.getY(positions[i]) - minY
            byLayer[layerStart[l]++] = i
        }
        val did = end - cursor
        cursor = end
        if (cursor == n) {
            for (l in layerStart.size - 1 downTo 1) layerStart[l] = layerStart[l - 1]
            layerStart[0] = 0
            layer = 0
            nextPhase(Phase.SORT)
        }
        return did
    }

    private fun stepSort(): Int {
        if (layer >= layerStart.size - 1) {
            layer = 0
            startLayer(Phase.SEEDS)
            return 1
        }
        val from = layerStart[layer]
        val to = layerStart[layer + 1]
        IntArrays.quickSort(byLayer, from, to, byRank)
        layer++
        return to - from
    }

    private fun startLayer(next: Phase) {
        phase = next
        cursor = if (layer < layerStart.size - 1) layerStart[layer] else n
    }

    private fun layerEnd(): Int = if (layer < layerStart.size - 1) layerStart[layer + 1] else n

    private fun stepSeeds(budget: Int): Int {
        val end = minOf(layerEnd(), cursor + budget)
        for (k in cursor until end) {
            val i = byLayer[k]
            if (kinds[i] == CellKind.SOLID && supported(positions[i])) emit(i)
        }
        val did = end - cursor
        cursor = end
        if (cursor == layerEnd()) phase = Phase.GROW
        return did
    }

    private fun supported(p: Long): Boolean =
        isSupport(BlockPos.offset(p, 0, -1, 0)) || isSupport(BlockPos.offset(p, 1, 0, 0)) ||
            isSupport(BlockPos.offset(p, -1, 0, 0)) || isSupport(BlockPos.offset(p, 0, 0, 1)) ||
            isSupport(BlockPos.offset(p, 0, 0, -1))

    // A solid plan cell below counts once it is in the order: its layer is complete by then.
    private fun isSupport(p: Long): Boolean {
        val j = cellAt.get(p)
        return if (j < 0) support.supports(p) else placed[j] && kinds[j] == CellKind.SOLID
    }

    private fun stepGrow(budget: Int): Int {
        var did = 0
        while (did < budget && !queue.isEmpty) {
            val p = positions[queue.dequeueInt()]
            growTo(BlockPos.offset(p, 1, 0, 0))
            growTo(BlockPos.offset(p, -1, 0, 0))
            growTo(BlockPos.offset(p, 0, 0, 1))
            growTo(BlockPos.offset(p, 0, 0, -1))
            did++
        }
        if (queue.isEmpty) startLayer(Phase.FLOATING)
        return did
    }

    private fun growTo(p: Long) {
        val j = cellAt.get(p)
        if (j >= 0 && kinds[j] == CellKind.SOLID) emit(j)
    }

    private fun stepFloating(budget: Int): Int {
        val end = minOf(layerEnd(), cursor + budget)
        for (k in cursor until end) {
            val i = byLayer[k]
            if (kinds[i] == CellKind.SOLID && !placed[i]) {
                placed[i] = true
                out[outSize++] = i
            }
        }
        val did = end - cursor
        cursor = end
        if (cursor == layerEnd()) {
            layer++
            if (layer < layerStart.size - 1) startLayer(Phase.SEEDS) else nextPhase(Phase.WEAK)
        }
        return did
    }

    private fun stepScan(budget: Int, kind: Byte, next: Phase): Int {
        val end = minOf(n, cursor + budget)
        for (k in cursor until end) {
            val i = byLayer[k]
            if (kinds[i] == kind && !placed[i]) {
                placed[i] = true
                out[outSize++] = i
            }
        }
        val did = end - cursor
        cursor = end
        if (cursor == n) nextPhase(next)
        return did
    }

    private fun emit(i: Int) {
        if (placed[i]) return
        placed[i] = true
        out[outSize++] = i
        queue.enqueue(i)
    }

    private fun nextPhase(next: Phase) {
        phase = next
        cursor = 0
    }

    private enum class Phase { INDEX, BUCKET, SORT, SEEDS, GROW, FLOATING, WEAK, ATTACHED, DONE }
}
