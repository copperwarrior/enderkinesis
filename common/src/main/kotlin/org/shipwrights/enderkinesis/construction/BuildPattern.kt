package org.shipwrights.enderkinesis.construction

import it.unimi.dsi.fastutil.ints.IntArrays
import it.unimi.dsi.fastutil.ints.IntComparator

/**
 * The order of the columns in one layer, after the MineColonies blueprint iterators (Structurize
 * `StructureIterators`). Every worker has one; layer-first order and support rules stay above it.
 * Keys use plan-local x and z, so a pattern spans the whole plan, not one section.
 */
enum class BuildPattern {
    /** x first, then z. */
    SCAN,

    /** Rings from the plan edge inward, each ring clockwise. */
    INWARD_CIRCLE,

    /** A Hilbert curve over the plan footprint. */
    HILBERT,

    /** Columns in a fixed random order per job. */
    RANDOM;

    /** Smaller keys go first. [x] and [z] are plan-local; [sizeX] and [sizeZ] are the plan size. */
    fun key(x: Int, z: Int, sizeX: Int, sizeZ: Int, seed: Long): Long {
        require(x in 0 until sizeX && z in 0 until sizeZ) { "column ($x, $z) is outside the plan $sizeX x $sizeZ" }
        return when (this) {
            SCAN -> z.toLong() * sizeX + x
            INWARD_CIRCLE -> ringKey(x, z, sizeX, sizeZ)
            HILBERT -> hilbertKey(x, z, maxOf(sizeX, sizeZ))
            RANDOM -> mix64(seed xor ((x.toLong() shl 32) or (z.toLong() and 0xffffffffL))) ushr 1
        }
    }

    companion object {
        private val ALL = entries.toTypedArray()

        fun byId(id: Int): BuildPattern = ALL[Math.floorMod(id, ALL.size)]
    }
}

private fun ringKey(x: Int, z: Int, sizeX: Int, sizeZ: Int): Long {
    val ring = minOf(minOf(x, z), minOf(sizeX - 1 - x, sizeZ - 1 - z))
    val maxX = sizeX - 1 - ring
    val maxZ = sizeZ - 1 - ring
    val w = maxX - ring
    val h = maxZ - ring
    val along = when {
        z == ring -> x - ring
        x == maxX -> w + (z - ring)
        z == maxZ -> w + h + (maxX - x)
        else -> 2 * w + h + (maxZ - z)
    }
    return (ring.toLong() shl 32) or along.toLong()
}

// Classic xy-to-distance on the smallest power-of-two square that holds the footprint.
private fun hilbertKey(x: Int, z: Int, edge: Int): Long {
    var n = 1
    while (n < edge) n = n shl 1
    var d = 0L
    var px = x
    var py = z
    var s = n / 2
    while (s > 0) {
        val rx = if (px and s > 0) 1 else 0
        val ry = if (py and s > 0) 1 else 0
        d += s.toLong() * s * ((3 * rx) xor ry)
        if (ry == 0) {
            if (rx == 1) {
                px = n - 1 - px
                py = n - 1 - py
            }
            val t = px
            px = py
            py = t
        }
        s = s shr 1
    }
    return d
}

private fun mix64(value: Long): Long {
    var z = value + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}

/**
 * Rank 0..255 of each 16 x 16 column of the section at plan-local [originX], [originZ], by `lz * 16 + lx`.
 * Columns outside the plan rank last. Plan-wide keys can be large, so a section uses only their order.
 */
fun columnRanks(pattern: BuildPattern, originX: Int, originZ: Int, sizeX: Int, sizeZ: Int, seed: Long): IntArray {
    val keys = LongArray(SECTION_COLUMNS) { col ->
        val x = originX + (col and 15)
        val z = originZ + (col shr 4)
        if (x < sizeX && z < sizeZ) pattern.key(x, z, sizeX, sizeZ, seed) else Long.MAX_VALUE
    }
    val byKey = IntArray(SECTION_COLUMNS) { it }
    IntArrays.quickSort(byKey, IntComparator { a, b -> keys[a].compareTo(keys[b]).takeIf { it != 0 } ?: a.compareTo(b) })
    val rank = IntArray(SECTION_COLUMNS)
    for (r in 0 until SECTION_COLUMNS) rank[byKey[r]] = r
    return rank
}

const val SECTION_COLUMNS = PLAN_SECTION_EDGE * PLAN_SECTION_EDGE
