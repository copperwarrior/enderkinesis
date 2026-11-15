package org.shipwrights.enderkinesis.construction

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.state.BlockState

const val PLAN_SECTION_EDGE = 16
const val PLAN_SECTION_CELLS = PLAN_SECTION_EDGE * PLAN_SECTION_EDGE * PLAN_SECTION_EDGE

/**
 * The section grid of a plan. Index order is x fastest, then z, then y, so ascending indices go bottom-up.
 * A cell (x, y, z) sits in section (x >> 4, y >> 4, z >> 4) at local (x & 15, y & 15, z & 15).
 */
class PlanGrid(val size: Vec3i) {
    val x: Int = sections(size.x)
    val y: Int = sections(size.y)
    val z: Int = sections(size.z)
    val count: Int

    init {
        require(size.x > 0 && size.y > 0 && size.z > 0) { "plan size must be positive: $size" }
        val total = x.toLong() * y * z
        require(total <= Int.MAX_VALUE) { "plan section grid does not fit an int: $size" }
        count = total.toInt()
    }

    fun contains(sx: Int, sy: Int, sz: Int): Boolean = sx in 0 until x && sy in 0 until y && sz in 0 until z

    fun index(sx: Int, sy: Int, sz: Int): Int {
        require(contains(sx, sy, sz)) { "section ($sx, $sy, $sz) is outside the grid $x x $y x $z" }
        return (sy * z + sz) * x + sx
    }

    fun sectionX(index: Int): Int = index % x
    fun sectionY(index: Int): Int = index / (x * z)
    fun sectionZ(index: Int): Int = index / x % z

    /** The plan-local position of cell (0, 0, 0) of section [index]. */
    fun origin(index: Int): BlockPos =
        BlockPos(sectionX(index) * PLAN_SECTION_EDGE, sectionY(index) * PLAN_SECTION_EDGE, sectionZ(index) * PLAN_SECTION_EDGE)

    override fun equals(other: Any?): Boolean = other is PlanGrid && other.size == size
    override fun hashCode(): Int = size.hashCode()

    companion object {
        /** True when a [PlanGrid] of [size] can be made. */
        fun fits(size: Vec3i): Boolean =
            size.x > 0 && size.y > 0 && size.z > 0 &&
                sections(size.x).toLong() * sections(size.y) * sections(size.z) <= Int.MAX_VALUE

        private fun sections(cells: Int): Int = (cells + PLAN_SECTION_EDGE - 1) / PLAN_SECTION_EDGE
    }
}

enum class PlanSectionState {
    /** Client only: the section is not here yet. Each miss asks for it (see [PlanSections.sectionAt]). */
    LOADING,

    /** Every cell is structure void: [PlanSections.sectionAt] stays null. Known from the header, with no load. */
    EMPTY,
    READY,
}

/**
 * One 16^3 section. Cells outside the plan size are structure void. Index 0 of [palette] is air and 1 is
 * structure void; neither is a block, so both read as null.
 */
class PlanSection(@PublishedApi internal val data: PlanSectionData, @PublishedApi internal val palette: Array<BlockState>) {
    val nonAir: Int get() = data.nonAir

    /** Null when the cell has no block. Local coordinates, 0..15. */
    fun stateAt(x: Int, y: Int, z: Int): BlockState? {
        val id = data.idAt(x, y, z)
        return if (id <= PLAN_KEEP_ID) null else palette[id]
    }

    /** Structure void: leave the world cell alone. By [cellIndex]. */
    fun isKeep(cell: Int): Boolean = data.isKeep(cell)

    /** Local coordinates, in index order (x fastest, then z, then y). Inline, so the hot path does not allocate. */
    inline fun forEachNonAir(action: (x: Int, y: Int, z: Int, state: BlockState) -> Unit) {
        data.forEachNonAir { x, y, z, id -> action(x, y, z, palette[id]) }
    }
}

/**
 * A plan read section by section. Memory and work stay bounded whatever the plan size.
 * Server: `PlanStorage.sections(server, ref)`; a miss reads one section from disk (a few KB) on the calling thread,
 * so a server state is never [PlanSectionState.LOADING]. Client: `ClientPlans.sections(ref)`.
 * Local file (sketchbook): `LocalPlanFile.openSections()` on a worker thread.
 */
interface PlanSections {
    val size: Vec3i
    val grid: PlanGrid

    /** Blocks to place in the whole plan. */
    val nonAir: Long

    /** Count of non-empty sections, and their indices in ascending (bottom-up) order through [nonEmptyIndex]. */
    val nonEmptyCount: Int
    fun nonEmptyIndex(i: Int): Int

    fun state(index: Int): PlanSectionState

    /**
     * Null when the section is empty, or (client) while it loads. On the client each miss asks for the section;
     * misses in one client tick go to the server in call order, so ask nearest the camera first. A miss is not kept:
     * ask again in a later frame while you still want the section.
     */
    fun sectionAt(index: Int): PlanSection?

    fun state(sx: Int, sy: Int, sz: Int): PlanSectionState = state(grid.index(sx, sy, sz))
    fun sectionAt(sx: Int, sy: Int, sz: Int): PlanSection? = sectionAt(grid.index(sx, sy, sz))
}

/** Null when the cell is air or structure void, or (client) its section is not loaded. Plan-local coordinates. */
fun PlanSections.stateAt(x: Int, y: Int, z: Int): BlockState? {
    if (x !in 0 until size.x || y !in 0 until size.y || z !in 0 until size.z) return null
    val section = sectionAt(x shr 4, y shr 4, z shr 4) ?: return null
    return section.stateAt(x and 15, y and 15, z and 15)
}

/** True when the cell is structure void. Server only: a client section that is not loaded reads as void. */
fun PlanSections.isKeep(x: Int, y: Int, z: Int): Boolean {
    if (x !in 0 until size.x || y !in 0 until size.y || z !in 0 until size.z) return false
    val section = sectionAt(x shr 4, y shr 4, z shr 4) ?: return true
    return section.isKeep(cellIndex(x and 15, y and 15, z and 15))
}
