package org.shipwrights.enderkinesis.block

import net.minecraft.core.Vec3i
import net.minecraft.util.StringRepresentable
import net.minecraft.world.phys.Vec3

/** The neighbour a guardian spike grows from: one of 6 faces, 12 edges or 8 corners of the cell. */
enum class SpikeAttachment(val dx: Int, val dy: Int, val dz: Int) : StringRepresentable {
    DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0),
    DOWN_NORTH(0, -1, -1), DOWN_SOUTH(0, -1, 1), DOWN_WEST(-1, -1, 0), DOWN_EAST(1, -1, 0),
    UP_NORTH(0, 1, -1), UP_SOUTH(0, 1, 1), UP_WEST(-1, 1, 0), UP_EAST(1, 1, 0),
    NORTH_WEST(-1, 0, -1), NORTH_EAST(1, 0, -1), SOUTH_WEST(-1, 0, 1), SOUTH_EAST(1, 0, 1),
    DOWN_NORTH_WEST(-1, -1, -1), DOWN_NORTH_EAST(1, -1, -1), DOWN_SOUTH_WEST(-1, -1, 1), DOWN_SOUTH_EAST(1, -1, 1),
    UP_NORTH_WEST(-1, 1, -1), UP_NORTH_EAST(1, 1, -1), UP_SOUTH_WEST(-1, 1, 1), UP_SOUTH_EAST(1, 1, 1);

    private val serialized = name.lowercase()

    /** 1 for a face, 2 for an edge, 3 for a corner. */
    val order: Int = Math.abs(dx) + Math.abs(dy) + Math.abs(dz)

    /** Cell-local point where the spike base sits: the shared face centre, edge midpoint or corner. */
    val base: Vec3 = Vec3(anchor(dx), anchor(dy), anchor(dz))

    /** Unit vector from the base to the tip, away from the support. */
    val direction: Vec3 = Vec3(-dx.toDouble(), -dy.toDouble(), -dz.toDouble()).normalize()

    /**
     * Offsets of the other cells that share the edge or corner (empty for a face). They must be open,
     * or the spike grows out of a seam with its plate hidden.
     */
    val flanks: List<Vec3i> = buildList {
        for (mx in 0..1) for (my in 0..1) for (mz in 0..1) {
            val v = Vec3i(dx * mx, dy * my, dz * mz)
            val kept = Math.abs(v.x) + Math.abs(v.y) + Math.abs(v.z)
            if (kept in 1 until order && v !in this) add(v)
        }
    }

    /** The step toward the support on axis 0 (x), 1 (y) or 2 (z). */
    fun step(axis: Int): Int = when (axis) {
        0 -> dx
        1 -> dy
        else -> dz
    }

    /** True if this spike leans only toward [corner]'s sides, so it can share a cell with [corner]'s other spikes. */
    fun fitsCorner(corner: SpikeAttachment): Boolean =
        (dx == 0 || dx == corner.dx) && (dy == 0 || dy == corner.dy) && (dz == 0 || dz == corner.dz)

    /** Cell-local point at [fraction] of the spike length from the base. */
    fun along(fraction: Double): Vec3 = base.add(direction.scale(LENGTH * fraction))

    override fun getSerializedName(): String = serialized

    companion object {
        /** Spike length in blocks: the 9 px element in guardian_spike.json. */
        const val LENGTH = 9.0 / 16.0

        private val BY_STEP: Array<SpikeAttachment?> = arrayOfNulls<SpikeAttachment>(27).also { table ->
            for (a in entries) table[(a.dx + 1) * 9 + (a.dy + 1) * 3 + (a.dz + 1)] = a
        }

        fun of(dx: Int, dy: Int, dz: Int): SpikeAttachment? =
            if (dx !in -1..1 || dy !in -1..1 || dz !in -1..1) null else BY_STEP[(dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)]

        val CORNERS: List<SpikeAttachment> = entries.filter { it.order == 3 }
    }
}

private fun anchor(d: Int): Double = when (d) {
    -1 -> 0.0
    1 -> 1.0
    else -> 0.5
}
