package org.shipwrights.enderkinesis.construction

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.state.BlockState
import java.util.BitSet

/** The one call into plan storage: the build reads plans only through it. */
internal fun buildPlan(server: MinecraftServer, ref: PlanRef): PlanSections? = PlanStorage.sections(server, ref)

/** Packs a section index and a [planCellLocal] into one task key. */
internal fun cellKey(section: Int, local: Int): Long = (section.toLong() shl LOCAL_BITS) or local.toLong()

internal fun cellKeySection(key: Long): Int = (key ushr LOCAL_BITS).toInt()

internal fun cellKeyLocal(key: Long): Int = (key and LOCAL_MASK).toInt()

private const val LOCAL_BITS = 12
private const val LOCAL_MASK = (1L shl LOCAL_BITS) - 1
internal const val NO_POS = Long.MIN_VALUE

/**
 * One 16^3 plan section in work. Every cell of the plan box in it gets a CLEAR look (top layer first), except
 * structure void, which the build never touches; a null [section] is all void. The plan's blocks then build in their order. Done bits of plan blocks live in [done], the job's
 * [SectionProgress] entry, so a parked section loses nothing. CLEAR keeps no saved state: the world shows it.
 */
internal class BuildSection(
    val index: Int,
    val done: LongArray,
    section: PlanSection?,
    private val origin: BlockPos,
    anchor: PlanAnchor,
    plan: PlanSections,
    private val seed: Long,
) {
    private val sizeX = plan.size.x
    private val sizeZ = plan.size.z

    // Plan blocks, by cell index c.
    val local: IntArray
    val positions: LongArray
    val states: Array<BlockState>
    val kinds: ByteArray
    /** Partner cell in this section, or -1. */
    val partner: IntArray
    /** World position of the second half that `setPlacedBy` makes, or [NO_POS]. */
    val partnerPos: LongArray

    private val cellOf = IntArray(PLAN_SECTION_CELLS) { -1 }

    /** World position of every plan-box cell by [planCellLocal]; [NO_POS] outside the plan size and at structure void. */
    val boxPos = LongArray(PLAN_SECTION_CELLS) { NO_POS }
    private val boxCount: Int

    /** By [planCellLocal]. */
    val status = ByteArray(PLAN_SECTION_CELLS)
    val dirty = BitSet(PLAN_SECTION_CELLS)

    private val passes = arrayOfNulls<PatternPass>(BuildPattern.entries.size)

    var remaining = 0
        private set
    var reserved = 0
    /** CUT and SCOOP reservations, by local; BUILD waits for them. */
    val clearHeld = BitSet(PLAN_SECTION_CELLS)
    /** Cells now [CellStatus.MISSING] or [CellStatus.NO_BUCKET]. */
    var missing = 0
        private set
    /** The last full CLEAR look found no cell that differs from the plan. */
    var clearClean = false
    /** The last full CLEAR look left nothing open to cut or scoop and no worker out on it. */
    var clearDone = false
    /** Grows on every cut, scoop, fix and place, so a pass can tell it did nothing. */
    var progressMark = 0L
        private set
    /** The last full pass did nothing: the job may park this section for another. */
    var stalled = false

    /** Why cells of this section gave nothing in the current pass, per [WhyNot] ordinal. */
    val passWhy = IntArray(WhyNot.entries.size)

    /** The reasons printed in this stall episode (union of [whyMask]s), and the [progressMark] it began at. */
    var stallReasons = 0
    var stallMark = -1L

    val isComplete: Boolean get() = clearClean && remaining == 0

    init {
        val n = section?.nonAir ?: 0
        local = IntArray(n)
        positions = LongArray(n)
        kinds = ByteArray(n)
        partner = IntArray(n) { -1 }
        partnerPos = LongArray(n) { NO_POS }
        val localStates = arrayOfNulls<BlockState>(n)
        var i = 0
        section?.forEachNonAir { x, y, z, state ->
            if (i < n) {
                local[i] = planCellLocal(x, y, z)
                localStates[i] = state
                i++
            }
        }
        check(i == n) { "section $index lists $i blocks, header says $n" }
        var box = 0
        val cell = BlockPos.MutableBlockPos()
        for (l in 0 until PLAN_SECTION_CELLS) {
            cell.set(origin.x + planCellLocalX(l), origin.y + planCellLocalY(l), origin.z + planCellLocalZ(l))
            if (cell.x >= plan.size.x || cell.y >= plan.size.y || cell.z >= plan.size.z) continue
            if (section == null || section.isKeep(l)) continue
            boxPos[l] = anchor.cellWorldPos(cell).asLong()
            box++
        }
        boxCount = box
        for (c in 0 until n) {
            cellOf[local[c]] = c
            positions[c] = boxPos[local[c]]
        }
        states = Array(n) { anchor.cellWorldState(checkNotNull(localStates[it])).withoutWater().withoutPower() }
        for (c in 0 until n) {
            kinds[c] = states[c].cellKind()
            status[local[c]] = when {
                done.bit(local[c]) -> CellStatus.DONE
                states[c].planCost() is PlanCost.Refused -> CellStatus.BLOCKED
                else -> CellStatus.TODO
            }
            if (kinds[c] != CellKind.SKIP && !done.bit(local[c])) remaining++
        }
        linkPartners(localStates, anchor, plan)
    }

    // A partner across the section edge is looked up in the plan; setPlacedBy writes it there all the same.
    private fun linkPartners(localStates: Array<BlockState?>, anchor: PlanAnchor, plan: PlanSections) {
        for (c in local.indices) {
            val state = checkNotNull(localStates[c])
            if (!state.isFirstHalf()) continue
            val step = if (state.block is BedBlock) state.getValue(BedBlock.FACING) else Direction.UP
            val x = planCellLocalX(local[c]) + step.stepX
            val y = planCellLocalY(local[c]) + step.stepY
            val z = planCellLocalZ(local[c]) + step.stepZ
            val inside = x in 0..15 && y in 0..15 && z in 0..15
            val other = if (inside) {
                val j = cellOf[planCellLocal(x, y, z)]
                if (j >= 0) localStates[j] else null
            } else {
                plan.stateAt(origin.x + x, origin.y + y, origin.z + z)
            }
            if (other == null || !other.isSecondHalf()) continue
            partnerPos[c] = anchor.cellWorldPos(BlockPos(origin.x + x, origin.y + y, origin.z + z)).asLong()
            if (inside) {
                val j = cellOf[planCellLocal(x, y, z)]
                partner[c] = j
                partner[j] = c
            }
        }
    }

    /** The walk of one [BuildPattern] over this section, made on first use. */
    fun pass(pattern: BuildPattern): PatternPass =
        passes[pattern.ordinal] ?: PatternPass(pattern).also { passes[pattern.ordinal] = it }

    /** Runs the build-order slices that passes asked for; returns the cell visits used. */
    fun prepare(level: ServerLevel, budget: Int): Int {
        var left = budget
        for (p in passes) {
            if (p == null || !p.wantOrder || p.order != null || left <= 0) continue
            val core = p.core ?: startCore(level, p).also { p.core = it }
            if (core.step(left)) {
                p.order = core.result()
                p.core = null
            }
            left = 0
        }
        return budget - left
    }

    // Cells of other sections are not in this core, so a finished section below counts as world support.
    private fun startCore(level: ServerLevel, p: PatternPass): BuildOrderCore {
        val probe = BlockPos.MutableBlockPos()
        val rank = IntArray(local.size) { p.columnRank[planCellLocal(planCellLocalX(local[it]), 0, planCellLocalZ(local[it]))] }
        return BuildOrderCore(positions, kinds, rank) { packed ->
            probe.set(packed)
            level.isLoaded(probe) && level.getBlockState(probe).isSolidRender(level, probe)
        }
    }

    /** The plan block cell at a [planCellLocal], or -1 where the plan has air. */
    fun cellAt(local: Int): Int = cellOf[local]

    fun isDone(cell: Int): Boolean = done.bit(local[cell])

    /** True when the cell was not done before. */
    fun markDone(cell: Int): Boolean {
        if (isDone(cell)) return false
        done.setBit(local[cell])
        if (kinds[cell] != CellKind.SKIP) remaining--
        setStatus(cell, CellStatus.DONE)
        val j = partner[cell]
        if (j >= 0) done.setBit(local[j])
        progressMark++
        return true
    }

    /** A cut, scoop or fix changed the world. */
    fun progressed() {
        progressMark++
    }

    fun status(cell: Int): Byte = status[local[cell]]

    fun setStatus(cell: Int, value: Byte) {
        setLocalStatus(local[cell], value)
        val j = partner[cell]
        if (j >= 0) setLocalStatus(local[j], value)
    }

    fun setLocalStatus(l: Int, value: Byte) {
        val old = status[l]
        if (old == value) return
        status[l] = value
        if (old.isWaiting()) missing--
        if (value.isWaiting()) missing++
        if (CellStatus.toClient(old) != CellStatus.toClient(value)) dirty.set(l)
    }

    /** A linked supply changed: every pass looks again at the waiting cells it already left behind. */
    fun wakeMissing() {
        if (missing == 0) return
        for (p in passes) if (p != null) p.wake = 0
    }

    /**
     * End of one pass. A pass that changed nothing, with no worker out, turns deferred cells into
     * blocked ones and lets the job park the section.
     */
    fun endPass(p: PatternPass) {
        stalled = progressMark == p.passMark && reserved == 0
        if (stalled) {
            for (c in local.indices) if (status(c) == CellStatus.DEFERRED) setOne(c, CellStatus.BLOCKED)
        }
        p.restart(progressMark)
    }

    private fun setOne(cell: Int, value: Byte) = setLocalStatus(local[cell], value)

    /**
     * One pattern's walk: the CLEAR list (plan-box locals, top layer first, then by column rank), then
     * the build order. BUILD starts only after a CLEAR run that left no clearing open.
     */
    inner class PatternPass(pattern: BuildPattern) {
        val columnRank = columnRanks(pattern, origin.x, origin.z, sizeX, sizeZ, seed)
        val clear: IntArray = clearOrder(columnRank)
        var core: BuildOrderCore? = null
        var order: IntArray? = null
        var wantOrder = false
        var cursor = 0
        /** Position of a look back for waiting cells after a supply change, or -1. */
        var wake = -1
        var inBuild = false
        /** Cells this run found different from the plan, and those of them still open to a worker. */
        var clearSeen = 0
        var clearOpen = 0
        var passMark = 0L

        val length: Int get() = clear.size + (order?.size ?: 0)

        /** The local at a walk position. */
        fun localAt(i: Int): Int = if (i < clear.size) clear[i] else local[checkNotNull(order)[i - clear.size]]

        fun restart(mark: Long) {
            cursor = 0
            inBuild = false
            clearSeen = 0
            clearOpen = 0
            passMark = mark
        }
    }

    private fun clearOrder(columnRank: IntArray): IntArray {
        val keys = IntArray(boxCount)
        var k = 0
        for (l in 0 until PLAN_SECTION_CELLS) {
            if (boxPos[l] == NO_POS) continue
            val top = PLAN_SECTION_EDGE - 1 - planCellLocalY(l)
            val column = planCellLocal(planCellLocalX(l), 0, planCellLocalZ(l))
            keys[k++] = (top shl 20) or (columnRank[column] shl 12) or l
        }
        keys.sort()
        for (i in keys.indices) keys[i] = keys[i] and LOCAL_MASK.toInt()
        return keys
    }
}

private fun Byte.isWaiting(): Boolean = this == CellStatus.MISSING || this == CellStatus.NO_BUCKET
