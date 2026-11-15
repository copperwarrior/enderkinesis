package org.shipwrights.enderkinesis.construction

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.LongArrayTag
import net.minecraft.nbt.Tag
import java.util.BitSet

const val SECTION_WORDS = PLAN_SECTION_CELLS / Long.SIZE_BITS

/** Index of a cell inside its plan-local 16^3 section; the same order as vanilla paletted containers. */
fun planCellLocal(lx: Int, ly: Int, lz: Int): Int = (ly shl 8) or (lz shl 4) or lx

fun planCellLocalX(local: Int): Int = local and 15
fun planCellLocalY(local: Int): Int = local shr 8
fun planCellLocalZ(local: Int): Int = (local shr 4) and 15

/**
 * Endless walk over the grid: bottom layer first (top layer first when [topDown]), and in a layer by square
 * rings out from section (0, 0), the one that holds the anchor. After the last layer it starts again. O(1) memory.
 */
class SectionSweep(private val grid: PlanGrid, private val topDown: Boolean = false) {
    private val firstY = if (topDown) grid.y - 1 else 0
    private var sy = firstY
    private var ring = 0
    private var k = 0

    /** True when the last [next] call started a new round at the first layer. */
    var wrapped: Boolean = false
        private set

    /** The next section index. Amortised O(1). */
    fun next(): Int {
        wrapped = false
        val rings = maxOf(grid.x, grid.z)
        // Ring 0 of every layer holds (0, sy, 0), so at most rings + 1 turns reach a section.
        repeat(rings + 2) {
            val r = ring
            val aLen = if (r < grid.x) minOf(r, grid.z - 1) + 1 else 0
            val bLen = if (r < grid.z) minOf(r, grid.x) else 0
            if (k < aLen) return grid.index(r, sy, k++)
            if (k < aLen + bLen) return grid.index(k++ - aLen, sy, r)
            k = 0
            ring++
            if (ring >= rings) {
                ring = 0
                sy += if (topDown) -1 else 1
                if (sy !in 0 until grid.y) {
                    sy = firstY
                    wrapped = true
                }
            }
        }
        error("section sweep found no section in ${rings + 2} turns")
    }
}

/** Done cells per section. A complete section is one bit; a partial one keeps 4096. A plan-air section completes after CLEAR. */
class SectionProgress(val grid: PlanGrid) {
    val complete = BitSet(grid.count)
    private val partial = Int2ObjectOpenHashMap<LongArray>()

    var completeCount: Int = 0
        private set

    fun isComplete(section: Int): Boolean = complete[section]

    /** Done bits by [planCellLocal] for a section that is not complete; made on first use. */
    fun doneBits(section: Int): LongArray = partial.computeIfAbsent(section) { LongArray(SECTION_WORDS) }

    fun partialOrNull(section: Int): LongArray? = partial.get(section)

    fun partialSections(): IntArray = partial.keys.toIntArray()

    fun markComplete(section: Int) {
        partial.remove(section)
        if (!complete[section]) {
            complete.set(section)
            completeCount++
        }
    }

    fun save(): CompoundTag = CompoundTag().also { tag ->
        tag.put(COMPLETE, LongArrayTag(complete.toLongArray()))
        val list = ListTag()
        for (entry in partial.int2ObjectEntrySet()) {
            if (entry.value.all { it == 0L }) continue
            list.add(CompoundTag().also {
                it.putInt(INDEX, entry.intKey)
                it.put(BITS, LongArrayTag(entry.value))
            })
        }
        tag.put(PARTIAL, list)
    }

    fun load(tag: CompoundTag) {
        val bits = BitSet.valueOf(tag.getLongArray(COMPLETE))
        var i = bits.nextSetBit(0)
        while (i in 0 until grid.count) {
            markComplete(i)
            i = bits.nextSetBit(i + 1)
        }
        val list = tag.getList(PARTIAL, Tag.TAG_COMPOUND.toInt())
        for (j in 0 until list.size) {
            val entry = list.getCompound(j)
            val index = entry.getInt(INDEX)
            val words = entry.getLongArray(BITS)
            if (index !in 0 until grid.count || complete[index] || words.size != SECTION_WORDS) continue
            partial.put(index, words)
        }
    }

    private companion object {
        const val COMPLETE = "Complete"
        const val PARTIAL = "Partial"
        const val INDEX = "I"
        const val BITS = "B"
    }
}

internal fun LongArray.bit(i: Int): Boolean = (this[i ushr 6] ushr (i and 63)) and 1L != 0L

internal fun LongArray.setBit(i: Int) {
    this[i ushr 6] = this[i ushr 6] or (1L shl (i and 63))
}
