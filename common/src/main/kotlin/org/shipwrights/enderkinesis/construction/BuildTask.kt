package org.shipwrights.enderkinesis.construction

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

enum class TaskKind {
    /** Put a plan block from the worker's held stack. */
    PLACE,

    /** Set the right block to the plan state, at no cost. */
    FIX,

    /** CLEAR: cut a world block into the worker's held stack. */
    CUT,

    /** CLEAR: scoop a fluid source with a bucket from a supply. */
    SCOOP,
}

/** [state] is the plan state for PLACE and FIX. [cost] is set for PLACE only. */
class BuildTask(
    val kind: TaskKind,
    val cell: Long,
    val pos: BlockPos,
    val state: BlockState,
    val cost: PlanCost.Items?,
)

enum class PlaceResult { PLACED, NOT_NOW, BLOCKED }

enum class CutResult {
    CUT,
    NOT_NOW,

    /** The yield does not fit with what the worker holds: unload first. */
    UNLOAD,

    /** Nothing to cut any more, or the cut is refused. */
    DROPPED,
}

/**
 * What drives a [BuildJob]: one block stack of one type plus pickup slots for other items. The job reserves
 * cells by [workerId] only, so a worker must call [BuildJob.releaseAll] when it stops or unloads.
 */
internal interface BuildWorker {
    val workerId: UUID

    /** Last debug-log reason of this worker. */
    val buildNote: ReasonNote

    fun heldBlock(): Block?

    fun heldCount(): Int

    /** How many more of [block] the worker may hold now; 0 when it holds another block type. */
    fun capacity(block: Block): Int

    /** Puts blocks from [stack] in the held stack and returns how many it took; [stack] is not changed. */
    fun hold(stack: ItemStack): Int

    fun takeMany(count: Int)

    /** Puts what fits of [stacks] in the pickup slots and returns what is left over; [stacks] are not changed. */
    fun holdPickups(stacks: List<ItemStack>): List<ItemStack>

    /** True when the worker can act on the block at [packedPos] (a world or shipyard pos) from where it is now. */
    fun reaches(packedPos: Long): Boolean

    /** False when the worker cannot hold [block] even when it is empty: such a CLEAR cell is BLOCKED. */
    fun canEverHold(block: Block): Boolean

    /** False when a block placed at [packedPos] would go into a block of the body that carries the worker, or
     *  close the carrier's last way out of the plan: such a cell waits (DEFERRED). */
    fun keepsCarrierFree(packedPos: Long): Boolean = true

    /** True while a redstone miner goes for the block at [packedPos]: a job CUT leaves it to the miner. */
    fun minedElsewhere(packedPos: Long): Boolean = false

    /** True while a fetch of [item] gave this worker nothing and neither the supplies nor its hand changed since:
     *  such cells wait (FETCH_REFUSED) and are not picked again at once. */
    fun refuses(item: Item): Boolean = false

    /** True while the worker has given up on out-of-reach cell [packedPos] as its next far cell: its trips there
     *  ended with the cell still out of reach and the job made no progress since. A later far cell goes first. */
    fun skipsFar(packedPos: Long): Boolean = false

    /** The tool this worker breaks with, or EMPTY for a bare hand. The user's rule: a held tool is used. */
    fun tool(): ItemStack = ItemStack.EMPTY

    /** Wears the tool for one break of [state] at [pos]; true when it broke. */
    fun wearTool(level: ServerLevel, pos: BlockPos, state: BlockState): Boolean = false

    /** True when the worker has no tool and may take one from a supply (one tool per Clawhod, in one hand). */
    fun mayFetchTool(): Boolean = false

    /** True when a tool holder of the job reaches [packedPos] and will cut [state] in a pass: a bare hand leaves it. */
    fun toolHolderTakes(packedPos: Long, state: BlockState): Boolean = false

    /** True when another hand holds the tool type for [state] and reaches [packedPos]: a fallback tool leaves it. */
    fun matchHolderTakes(packedPos: Long, state: BlockState): Boolean = false

    /** True when this worker's tool cuts [state] in its current pass; else the cut waits for its pass. */
    fun takesCut(state: BlockState): Boolean = true
}

/**
 * One worker's pick: what it can carry now and the tasks the job hands it. One per worker, reused, so a
 * pick allocates only its tasks.
 */
internal class WorkerPick(var pattern: BuildPattern) {
    val tasks = ArrayList<BuildTask>()

    /** The supply to fetch from first: items for PLACE, a bucket for SCOOP. Null when nothing is fetched. */
    var supply: BlockPos? = null
    var fetchItem: Item? = null
    var fetchCount = 0

    var held: Block? = null
        private set

    /** Held blocks no task of this pick has claimed yet. */
    var heldLeft = 0

    /** The world block of the last cut while the worker still holds its yield. */
    var lastCut: Block? = null

    /** Every section in work ended a pass since this worker's last find with what it holds now. */
    var searchedAll = false

    /** Pass ends seen since the last find; it spans pick calls, as one call's scan budget ends early on big sections. */
    private var passEnds = 0

    /** Why cells gave nothing since the last find or window swap. */
    internal val tally = PickTally()

    /** The last full [TALLY_WINDOW] calls of [tally], so a log line does not flap on a partial scan. */
    internal val window = PickTally()
    private var calls = 0

    private val capacities = Reference2IntOpenHashMap<Block>().apply { defaultReturnValue(-1) }

    /** The first open cell the search found out of reach, or [NO_POS]. It stays until a find or [clearFar]. */
    var farCell = NO_POS
        private set
    var farState: BlockState? = null
        private set

    /** True when [farCell] is BUILD work ([farState] is the plan state); false for CLEAR ([farState] is the world). */
    var farBuild = false
        private set

    /** An out-of-reach supply that has what an open cell needs, or null. */
    var farSupply: BlockPos? = null
        private set
    var farItem: Item? = null
        private set

    fun noteFar(packedPos: Long, state: BlockState, build: Boolean) {
        if (farCell != NO_POS) return
        farCell = packedPos
        farState = state
        farBuild = build
    }

    fun noteFarSupply(pos: BlockPos, item: Item) {
        if (farSupply != null) return
        farSupply = pos
        farItem = item
    }

    fun clearFar() {
        farCell = NO_POS
        farState = null
        farBuild = false
        farSupply = null
        farItem = null
    }

    fun begin(worker: BuildWorker) {
        tasks.clear()
        supply = null
        fetchItem = null
        fetchCount = 0
        searchedAll = false
        capacities.clear()
        val nowHeld = worker.heldBlock()
        if (nowHeld != held) {
            passEnds = 0
            clearTallies()
            clearFar()
        } else if (++calls >= TALLY_WINDOW) {
            window.copyFrom(tally)
            tally.clear()
            calls = 0
        }
        held = nowHeld
        heldLeft = if (held == null) 0 else worker.heldCount()
        if (held == null) lastCut = null
    }

    /** Returns true. */
    fun found(): Boolean {
        passEnds = 0
        clearTallies()
        clearFar()
        return true
    }

    private fun clearTallies() {
        tally.clear()
        window.clear()
        calls = 0
    }

    /** The tally a log line reads: the last full window, or the running one before the first swap. */
    internal fun reportTally(): PickTally = if (window.valid) window else tally

    /** Counts a pass end; true (and [searchedAll] set) once every one of [sections] ended a pass. */
    fun passEnded(sections: Int): Boolean {
        if (passEnds <= sections) passEnds++
        if (passEnds <= sections) return false
        searchedAll = true
        return true
    }

    /** A pick asks [BuildWorker.capacity] once per block type. */
    fun capacity(worker: BuildWorker, block: Block): Int {
        val known = capacities.getInt(block)
        if (known >= 0) return known
        return worker.capacity(block).also { capacities.put(block, it) }
    }

    private companion object {
        const val TALLY_WINDOW = 100
    }
}
