package org.shipwrights.enderkinesis.barnacle

import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import java.lang.ref.WeakReference

/** One thread only. */
class YgannBarnacleHullLog(val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val keys = LongArray(capacity)
    private val chunkFlags = BooleanArray(capacity)
    private var levelRef: WeakReference<Level>? = null
    private var epoch = 0L

    fun blockChanged(level: Level, packedPos: Long) = record(level, packedPos, false)

    fun chunkChanged(level: Level, chunkX: Int, chunkZ: Int) = record(level, ChunkPos.asLong(chunkX, chunkZ), true)

    /** The present epoch for [level]. A new level skips the epoch past the ring, so older snapshots do a full check. */
    fun stamp(level: Level): Long {
        if (levelRef?.get() !== level) {
            levelRef = WeakReference(level)
            epoch += capacity + 1L
        }
        return epoch
    }

    fun keyAt(epoch: Long): Long = keys[slot(epoch)]

    fun isChunkAt(epoch: Long): Boolean = chunkFlags[slot(epoch)]

    private fun record(level: Level, key: Long, chunk: Boolean) {
        val e = stamp(level)
        val i = slot(e)
        keys[i] = key
        chunkFlags[i] = chunk
        epoch = e + 1
    }

    private fun slot(epoch: Long): Int = Math.floorMod(epoch, capacity.toLong()).toInt()
}
