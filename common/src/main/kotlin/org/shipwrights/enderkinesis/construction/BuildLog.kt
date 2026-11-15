package org.shipwrights.enderkinesis.construction

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.spell.SpellPlatform
import java.util.UUID

// Debug logs for "why did a build stop". Server thread only. A line prints when a worker's (or a job's)
// reason changes; grep the log for the fixed prefix.

/** Why a cell gave a worker nothing in a pick. The order is the priority of the reported reason. */
internal enum class WhyNot {
    NO_SUPPLY, NO_SUPPLY_IN_REACH, NO_CAPACITY, CLEAR_REFUSED, BUILD_REFUSED, PARTNER_BLOCKED, WATER_IN_HOT,
    CANNOT_STAND, NO_SUPPORT, ENTITY_IN_CELL, CARRIER_IN_WAY, NO_CLAWHOD_BLOCK, HOLDING_OTHER, OCCUPIED, OUT_OF_REACH, NOT_LOADED, RESERVED,
    FETCH_REFUSED, FOR_TOOL_HOLDER, OTHER_TOOL_PASS,
}

/** The [WhyNot] reasons with a count above 0 in [counts], as a bit set; never 0, so a stall with no counted
 *  reason (no cell in the pass) still differs from "no stall". */
internal fun whyMask(counts: IntArray): Int {
    require(counts.size < Int.SIZE_BITS) { "Too many reasons for a bit set: ${counts.size}" }
    var mask = 1 shl counts.size
    for (i in counts.indices) if (counts[i] > 0) mask = mask or (1 shl i)
    return mask
}

/** "NAME=count" for each [WhyNot] with a count in [counts], largest first, or "none". */
internal fun whySummary(counts: IntArray): String {
    val names = WhyNot.entries
    val order = counts.indices.filter { counts[it] > 0 }.sortedByDescending { counts[it] }
    return if (order.isEmpty()) "none" else order.joinToString(", ") { "${names[it].name}=${counts[it]}" }
}

/** Counts, per [WhyNot], the cells that gave nothing, with the first cell of each kind. Allocates once. */
internal class PickTally {
    private val counts = IntArray(WhyNot.entries.size)
    private val firstPos = LongArray(counts.size)
    private val firstState = arrayOfNulls<BlockState>(counts.size)
    var missingItem: Item? = null
        private set

    /** True once [copyFrom] filled it. */
    var valid = false
        private set

    fun add(why: WhyNot, packedPos: Long, state: BlockState? = null) {
        val i = why.ordinal
        val n = counts[i]
        if (n == 0) {
            firstPos[i] = packedPos
            firstState[i] = state
        }
        if (n < Int.MAX_VALUE) counts[i] = n + 1
    }

    fun missing(item: Item, packedPos: Long) {
        if (missingItem == null) missingItem = item
        add(WhyNot.NO_SUPPLY, packedPos)
    }

    fun clear() {
        counts.fill(0)
        firstState.fill(null)
        missingItem = null
        valid = false
    }

    fun copyFrom(other: PickTally) {
        other.counts.copyInto(counts)
        other.firstPos.copyInto(firstPos)
        other.firstState.copyInto(firstState)
        missingItem = other.missingItem
        valid = true
    }

    /** The highest-priority reason with a count, or null when no cell gave anything. */
    fun top(): WhyNot? {
        for (i in counts.indices) if (counts[i] > 0) return REASONS[i]
        return null
    }

    fun stateOf(why: WhyNot): BlockState? = firstState[why.ordinal]

    fun has(why: WhyNot): Boolean = counts[why.ordinal] > 0

    fun firstPosOf(why: WhyNot): BlockPos = BlockPos.of(firstPos[why.ordinal])

    fun summary(): String {
        val out = StringBuilder()
        for (i in counts.indices) {
            if (counts[i] == 0) continue
            if (out.isNotEmpty()) out.append(", ")
            out.append(REASONS[i].name).append('=').append(counts[i])
                .append(" (first ").append(BlockPos.of(firstPos[i]).toShortString()).append(')')
        }
        return out.toString()
    }

    private companion object {
        val REASONS: List<WhyNot> = WhyNot.entries
    }
}

/** Remembers the last reason of one worker or job, so a line prints only when it changes. One small object each. */
internal class ReasonNote {
    private var reason: Enum<*>? = null
    private var arg = 0

    fun changed(next: Enum<*>, nextArg: Int = 0): Boolean {
        if (reason == next && arg == nextArg) return false
        reason = next
        arg = nextArg
        return true
    }

    fun clear() {
        reason = null
        arg = 0
    }
}

/** Wait and stop reasons that a worker finds when it acts on a cell. */
internal enum class WorkerWhy {
    PLACE_WAIT_CHUNK, PLACE_NOT_REPLACEABLE, PLACE_PARTNER_BLOCKED, PLACE_REFUSED, CANNOT_STAND, HELD_MISMATCH,
    ENTITY_IN_CELL, SET_BLOCK_FAILED, SECTION_RETIRED, FIX_REFUSED, CUT_WAIT_CHUNK, CUT_NOTHING, CUT_REFUSED,
    CUT_NO_CAPACITY, SCOOP_FAILED, TOOL_BROKE,
}

/** Reasons of a Clawhod worker and of the carrier it asks to move. */
internal enum class ClawhodWhy {
    JOINED, LEFT, JOB_GONE, WORK_TAKEN, NO_REACHABLE_WORK, NOTHING_OPEN, NO_ACTIVE_SECTION, ASK_MOVE,
    ASK_MOVE_SUPPLY, ASK_CLIMB, NO_WORK_POSITION, CARRIER_SITTING, CARRIER_MOVING, CARRIER_BUSY, CARRIER_STUCK, ENTITY_BLOCKS, CARRIER_STEP_ASIDE, NO_SUPPLY_IN_REACH,
    CARRIER_IN_WAY, CARRIER_TRAPPED, CARRIER_TRAPPED_MOVE, STOCK_UP, SPOT_TAKEN, SUPPLY_EMPTIED, SWAP,
    UNLOAD, SUPPLY_FULL, FETCH_MISSED, FETCH_REFUSED, TASK_OUT_OF_REACH, RESERVATION_LOST, CUT_UNLOAD,
    SUPPLY_REFUSED, RELAY, RELAY_GIVE, RELAY_RETURN, RELAY_BREAK, RELAY_BACKOFF, TOOL_FETCH, TOOL_PASS, REST_PASS, HOLD_FOR_FAR, HOLD_ENDS,
    FAR_GIVEN_UP,
}

/** Reasons of a whole job. */
internal enum class JobWhy {
    OWNER_OFFLINE, SECTION_UNLOADED, CLEAR_HELD_BACK, SECTION_STALLED, PLAN_MISSING, PLAN_CHANGED,
    SECTION_COMPLETE, CLEAR_ENDED, JOB_FINISHED, JOB_CANCELLED, SUPPLY_DROPPED,
    JOB_ENDED,
}

internal object BuildLog {
    private val LOG = LogUtils.getLogger()
    private const val PREFIX = "[ConstriliarBuild]"
    private const val SHORT = 8

    fun short(id: UUID): String = id.toString().substring(0, SHORT)

    fun job(job: BuildJob, reason: Enum<*>, detail: String) {
        LOG.info("{} job={} phase={} reason={} {}", PREFIX, short(job.id), phase(job), reason.name, detail)
    }

    fun worker(job: BuildJob?, jobId: UUID?, worker: UUID, step: String, reason: Enum<*>, detail: String) {
        LOG.info(
            "{} job={} worker={} phase={} step={} reason={} {}",
            PREFIX, jobId?.let { short(it) } ?: "-", short(worker), job?.let { phase(it) } ?: "-", step, reason.name, detail,
        )
    }

    fun phase(job: BuildJob): String = if (job.clearing) "CLEAR" else "BUILD"

    fun itemId(item: Item?): Int = item?.let { BuiltInRegistries.ITEM.getId(it) } ?: 0

    fun itemName(item: Item): String = BuiltInRegistries.ITEM.getKey(item).toString()

    fun blockName(state: BlockState): String = BuiltInRegistries.BLOCK.getKey(state.block).toString()

    /** What each linked supply holds of [item]. Built only when a line prints. */
    fun supplyReport(level: ServerLevel, supplies: List<BlockPos>, item: Item): String {
        if (supplies.isEmpty()) return "supplies=none linked"
        return supplies.joinToString(prefix = "supplies=[", postfix = "]") { pos ->
            val state = when {
                !level.isLoaded(pos) -> "unloaded"
                else -> HopperBlockEntity.getContainerAt(level, pos)?.let { "has ${it.countOf(item)}" } ?: "no container"
            }
            "${pos.toShortString()}: $state"
        }
    }

    /** Which permission check refused, in the order [mayEdit] and [mayBuild] run them. */
    fun permission(level: ServerLevel, actor: ServerPlayer, pos: BlockPos, place: BlockState?, brk: BlockState?): String = when {
        !actor.abilities.mayBuild -> "owner ${actor.gameProfile.name} has abilities.mayBuild=false (adventure or spectator)"
        !level.mayInteract(actor, pos) -> "level.mayInteract refused (spawn protection or world border)"
        brk != null && !SpellPlatform.mayBreak(level, pos, brk, actor) -> "break event refused (claim mod)"
        place != null && !SpellPlatform.mayPlace(level, pos, place, actor) -> "place event refused (claim mod)"
        else -> "no permission check refused; the block cannot survive there"
    }
}

/** Why the CLEAR rules refuse a cut at [pos], in the order [BuildJob.clearRefused] runs the checks. */
internal fun clearWhy(level: ServerLevel, actor: ServerPlayer, pos: BlockPos, world: BlockState): String = when {
    !mayEdit(level, actor, pos) || !SpellPlatform.mayBreak(level, pos, world, actor) ->
        BuildLog.permission(level, actor, pos, null, world)
    world.getDestroySpeed(level, pos) < 0f -> "unbreakable block"
    level.getBlockEntity(pos)?.holdsContents() == true -> "block entity holds items"
    else -> "other half of a two-part block is outside the plan box or in structure void"
}

/** The detail of an idle line for the top reason of a finished search. Built only when a line prints. */
internal fun idleDetail(level: ServerLevel, job: BuildJob, worker: BuildWorker, tally: PickTally, top: WhyNot): String {
    val pos = tally.firstPosOf(top)
    val state = tally.stateOf(top)
    val what = when (top) {
        WhyNot.NO_SUPPLY -> tally.missingItem?.let { "waiting for supply of ${BuildLog.itemName(it)}; ${BuildLog.supplyReport(level, job.supplies, it)}" }
            ?: "waiting for supply"
        WhyNot.NO_SUPPLY_IN_REACH -> "a supply out of reach has the item; the worker must move nearer"
        WhyNot.OUT_OF_REACH -> "cell is out of the worker's reach"
        WhyNot.NO_CAPACITY -> "worker takes no ${state?.let { BuildLog.blockName(it) }} (full, or holds ${worker.heldBlock()})"
        WhyNot.CLEAR_REFUSED -> state?.let { clearWhy(level, job.actor(level), pos, it) } ?: "cut refused"
        WhyNot.BUILD_REFUSED -> BuildLog.permission(level, job.actor(level), pos, state, null)
        WhyNot.PARTNER_BLOCKED -> "other half of a two-part block is blocked, not replaceable or unloaded"
        WhyNot.WATER_IN_HOT -> "water block in an ultra-warm dimension"
        WhyNot.CANNOT_STAND -> "block cannot stand there yet; waits for support (DEFERRED)"
        WhyNot.NO_SUPPORT -> "no solid block touches the cell yet; waits for the build to reach it (DEFERRED)"
        WhyNot.ENTITY_IN_CELL -> "an entity stands in the cell; it waits until the entity leaves (DEFERRED)"
        WhyNot.CARRIER_IN_WAY -> "the block would go into the carrier's body, or wall the carrier in (DEFERRED)"
        WhyNot.NO_CLAWHOD_BLOCK -> "plan block has no block item the worker can hold"
        WhyNot.HOLDING_OTHER -> "worker holds ${worker.heldBlock()} x${worker.heldCount()}, not what the open cells need"
        WhyNot.OCCUPIED -> "a block that is not replaceable stands in the cell (${state?.let { BuildLog.blockName(it) }}); waits for CLEAR"
        WhyNot.NOT_LOADED -> "chunk of the cell is not loaded"
        WhyNot.RESERVED -> "cell is reserved by another worker"
        WhyNot.OTHER_TOOL_PASS -> "the held tool is not for ${state?.let { BuildLog.blockName(it) }}; it waits for that tool's pass"
        WhyNot.FOR_TOOL_HOLDER -> "a worker with a tool reaches the cell; bare hands leave the cut to it"
        WhyNot.FETCH_REFUSED -> "the last fetch of ${state?.let { BuildLog.blockName(it) }} gave the hand nothing; waits until the supplies or the hand change"
    }
    return "cell=${pos.toShortString()} $what | counts: ${tally.summary()} | ${job.summary()}"
}

/** Phase, progress and the sections in work, for the lines that name an idle worker. */
internal fun BuildJob.summary(): String {
    val sections = active.joinToString(prefix = "[", postfix = "]") {
        "${it.index}(rem=${it.remaining},missing=${it.missing}${if (it.stalled) ",stalled" else ""})"
    }
    return "done=${progress.completeCount}/${grid.count} active=$sections supplies=${supplies.size}"
}

/** Prints a worker line when [reason] or [arg] differs from the last one in this note. */
internal inline fun ReasonNote.report(
    job: BuildJob?, jobId: UUID?, worker: UUID, step: String, reason: Enum<*>, arg: Int = 0, detail: () -> String,
) {
    if (changed(reason, arg)) BuildLog.worker(job, jobId, worker, step, reason, detail())
}
