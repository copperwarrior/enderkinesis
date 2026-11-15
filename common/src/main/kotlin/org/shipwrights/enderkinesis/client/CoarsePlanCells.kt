package org.shipwrights.enderkinesis.client

/**
 * A plan of [sizeX] x [sizeY] x [sizeZ] blocks at reduced detail: one cell for each cube of [factor] blocks, at most
 * [maxEdge] cells on each axis. A cell keeps the last value [put] into it.
 */
internal class CoarsePlanCells<T : Any>(val sizeX: Int, val sizeY: Int, val sizeZ: Int, maxEdge: Int) {
    init {
        require(sizeX > 0 && sizeY > 0 && sizeZ > 0) { "empty plan size: $sizeX $sizeY $sizeZ" }
        require(maxEdge > 0) { "coarse edge must be positive: $maxEdge" }
    }

    val factor: Int = coarseFactor(maxOf(sizeX, sizeY, sizeZ), maxEdge)
    val countX: Int = cellsOf(sizeX)
    val countY: Int = cellsOf(sizeY)
    val countZ: Int = cellsOf(sizeZ)
    private val cells = arrayOfNulls<Any>(countX * countY * countZ)

    /** Plan block coordinates. */
    fun put(x: Int, y: Int, z: Int, value: T) {
        require(x in 0 until sizeX && y in 0 until sizeY && z in 0 until sizeZ) { "block outside the plan: $x $y $z" }
        cells[index(x / factor, y / factor, z / factor)] = value
    }

    /** Coarse cell coordinates; null outside the grid or when the cell is empty. */
    @Suppress("UNCHECKED_CAST") // Only put() writes the array, with a T.
    fun get(cx: Int, cy: Int, cz: Int): T? {
        if (cx !in 0 until countX || cy !in 0 until countY || cz !in 0 until countZ) return null
        return cells[index(cx, cy, cz)] as T?
    }

    private fun index(cx: Int, cy: Int, cz: Int): Int = (cy * countZ + cz) * countX + cx

    private fun cellsOf(size: Int): Int = (size + factor - 1) / factor

    companion object {
        /** The least power of two, 2 or more, that puts [longest] blocks in [maxEdge] cells or fewer. */
        fun coarseFactor(longest: Int, maxEdge: Int): Int {
            var factor = 2
            // At most 30 doublings: a factor of 2^30 fits any Int length in one cell.
            while ((longest + factor - 1) / factor > maxEdge && factor < MAX_FACTOR) factor = factor shl 1
            return factor
        }

        private const val MAX_FACTOR = 1 shl 30
    }
}
