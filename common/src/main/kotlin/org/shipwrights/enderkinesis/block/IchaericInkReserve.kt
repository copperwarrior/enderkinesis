package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData

/** Bottles already taken from each Ichaeric Ink source in a level; a source holds [BOTTLES_PER_SOURCE]. */
class IchaericInkReserve : SavedData() {

    private val taken = HashMap<Long, Int>()

    fun isPartlyDrained(pos: BlockPos): Boolean = pos.asLong() in taken

    /** Record one bottle taken from the source at [pos]; true when that empties it. */
    fun takeBottle(pos: BlockPos): Boolean {
        val count = (taken[pos.asLong()] ?: 0) + 1
        if (count >= BOTTLES_PER_SOURCE) taken.remove(pos.asLong()) else taken[pos.asLong()] = count
        setDirty()
        return count >= BOTTLES_PER_SOURCE
    }

    fun forget(pos: BlockPos) {
        if (taken.remove(pos.asLong()) != null) setDirty()
    }

    override fun save(tag: CompoundTag): CompoundTag {
        tag.putLongArray(POSITIONS, taken.keys.toLongArray())
        tag.putIntArray(COUNTS, taken.values.toIntArray())
        return tag
    }

    companion object {
        // A bottle is 1/3 bucket, and a source is one bucket.
        const val BOTTLES_PER_SOURCE = 3

        private const val DATA_NAME = "enderkinesis_ichaeric_ink_reserve"
        private const val POSITIONS = "Positions"
        private const val COUNTS = "Counts"

        fun of(level: ServerLevel): IchaericInkReserve =
            level.dataStorage.computeIfAbsent(::load, ::IchaericInkReserve, DATA_NAME)

        private fun load(tag: CompoundTag): IchaericInkReserve {
            val reserve = IchaericInkReserve()
            val positions = tag.getLongArray(POSITIONS)
            val counts = tag.getIntArray(COUNTS)
            for (i in 0 until minOf(positions.size, counts.size)) reserve.taken[positions[i]] = counts[i]
            return reserve
        }
    }
}
