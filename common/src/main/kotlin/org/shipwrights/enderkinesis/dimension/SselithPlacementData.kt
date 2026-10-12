package org.shipwrights.enderkinesis.dimension

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import java.math.BigInteger
import net.minecraft.nbt.ByteArrayTag
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.saveddata.SavedData

/** Which kind of player edit a tracked position records. */
enum class SselithEditKind {
    /** A player put a block here; it is (as far as we know) still there. */
    PLACED,

    /** A player broke the block that was here; the position is now empty. */
    REMOVED,
    ;

    val opposite: SselithEditKind
        get() = if (this == PLACED) REMOVED else PLACED
}

/**
 * Persistent record of every position in Sselith a player has edited —
 * both blocks they placed and blocks they broke.
 *
 * **Storage shape.** Chunk-bucketed: `chunkPos → set of packed entries`.
 * Each entry is one `int`: 1 bit edit kind, 4 bits local X, 4 bits local
 * Z, 23 bits signed Y. Two reasons over one flat set of [BlockPos.asLong]:
 *   - **Memory.** 4 bytes per position in a primitive [IntOpenHashSet]
 *     rather than 8 (boxing avoided entirely). At the [MAX_RECORDS]
 *     ceiling that's ~8 MB of payload instead of ~32 MB boxed.
 *   - **Locality.** O(1) access to a single chunk's edits, which is what
 *     the random-tick driver walks every tick.
 *
 * **Sampling.** [snapshot] hands out a flat array per chunk so the tick
 * driver can pick a uniformly random entry in O(1); it's built on demand
 * and invalidated whenever that chunk's set changes.
 *
 * **Persistence.** One [net.minecraft.nbt.IntArrayTag] per chunk — NBT's
 * most compact list form, ~4 bytes per tracked position on disk.
 *
 * Server-thread only; no synchronisation.
 */
class SselithPlacementData : SavedData() {

    private val byChunk: Long2ObjectOpenHashMap<IntOpenHashSet> = Long2ObjectOpenHashMap()

    /** Flat per-chunk copies for O(1) random sampling — see [snapshot]. */
    private val snapshots: Long2ObjectOpenHashMap<IntArray> = Long2ObjectOpenHashMap()

    /** Parallel of each edit, keyed by its packed position bits. Absent means parallel zero, which most edits are. */
    private val parallelByChunk: Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<BigInteger>> = Long2ObjectOpenHashMap()

    /** Running total, maintained incrementally so [count] stays O(1). */
    var count: Int = 0
        private set

    /** Records [pos] as a player edit of [kind], replacing any opposite-kind
     *  record there (place-over-broken and break-of-placed both flip the
     *  same position) and tagging it with [parallel]. Returns false when that exact record already existed. */
    fun mark(pos: BlockPos, kind: SselithEditKind, parallel: BigInteger): Boolean {
        val key = ChunkPos.asLong(pos.x shr 4, pos.z shr 4)
        val packed = pack(pos, kind)
        val existing = byChunk.get(key)
        if (existing != null && existing.contains(packed)) {
            if (tagParallel(key, packed, parallel)) setDirty()
            return false
        }
        val flipped = existing?.remove(pack(pos, kind.opposite)) == true
        if (!flipped && count >= MAX_RECORDS) evictOne()
        // Look up again: eviction can drop this chunk's bucket.
        val set = byChunk.get(key)
            ?: IntOpenHashSet(INITIAL_CHUNK_CAPACITY).also { byChunk.put(key, it) }
        set.add(packed)
        tagParallel(key, packed, parallel)
        if (!flipped) count++
        snapshots.remove(key)
        setDirty()
        return true
    }

    /** Removes one record to keep [count] at [MAX_RECORDS]. A REMOVED record
     *  goes first, because a PLACED record also answers ownership queries. */
    private fun evictOne() {
        var fallbackKey = 0L
        var fallbackSet: IntOpenHashSet? = null
        var fallbackPacked = 0
        var scanned = 0
        val chunks = byChunk.long2ObjectEntrySet().fastIterator()
        while (chunks.hasNext() && scanned < EVICTION_SCAN_LIMIT) {
            val entry = chunks.next()
            val entries = entry.value.iterator()
            while (entries.hasNext() && scanned < EVICTION_SCAN_LIMIT) {
                val packed = entries.nextInt()
                scanned++
                if (unpackKind(packed) == SselithEditKind.REMOVED) {
                    removeEntry(entry.longKey, entry.value, packed)
                    return
                }
                if (fallbackSet == null) {
                    fallbackKey = entry.longKey
                    fallbackSet = entry.value
                    fallbackPacked = packed
                }
            }
        }
        fallbackSet?.let { removeEntry(fallbackKey, it, fallbackPacked) }
    }

    /** Returns true when the stored parallel changed. */
    private fun tagParallel(key: Long, packed: Int, parallel: BigInteger): Boolean {
        val posBits = packed and POSITION_MASK
        if (parallel.signum() == 0) return untagParallel(key, posBits)
        val tags = parallelByChunk.get(key) ?: Int2ObjectOpenHashMap<BigInteger>().also { parallelByChunk.put(key, it) }
        return tags.put(posBits, parallel) != parallel
    }

    private fun untagParallel(key: Long, posBits: Int): Boolean {
        val tags = parallelByChunk.get(key) ?: return false
        val removed = tags.remove(posBits) != null
        if (tags.isEmpty()) parallelByChunk.remove(key)
        return removed
    }

    /** The parallel tagged on a packed entry from [snapshot] in chunk [chunkKey]. */
    fun parallelOf(chunkKey: Long, packed: Int): BigInteger =
        parallelByChunk.get(chunkKey)?.get(packed and POSITION_MASK) ?: BigInteger.ZERO

    private fun removeEntry(key: Long, set: IntOpenHashSet, packed: Int) {
        if (!set.remove(packed)) return
        untagParallel(key, packed and POSITION_MASK)
        count--
        if (set.isEmpty()) byChunk.remove(key)
        snapshots.remove(key)
    }

    /** Drops any record at [pos] regardless of kind. Returns false when it
     *  wasn't tracked. */
    fun forget(pos: BlockPos): Boolean {
        val key = ChunkPos.asLong(pos.x shr 4, pos.z shr 4)
        val set = byChunk.get(key) ?: return false
        var removed = false
        for (kind in SselithEditKind.entries) {
            if (set.remove(pack(pos, kind))) {
                count--
                removed = true
            }
        }
        if (!removed) return false
        untagParallel(key, pack(pos, SselithEditKind.PLACED))
        // Drop empty buckets so a built-then-levelled world doesn't leak an
        // entry per chunk ever touched.
        if (set.isEmpty()) byChunk.remove(key)
        snapshots.remove(key)
        setDirty()
        return true
    }

    /** The kind of edit recorded at [pos], or null when untracked. */
    fun kindAt(pos: BlockPos): SselithEditKind? {
        val set = byChunk.get(ChunkPos.asLong(pos.x shr 4, pos.z shr 4)) ?: return null
        for (kind in SselithEditKind.entries) {
            if (set.contains(pack(pos, kind))) return kind
        }
        return null
    }

    fun countInChunk(chunkKey: Long): Int = byChunk.get(chunkKey)?.size ?: 0

    /** Flat array of this chunk's packed entries for random sampling, or
     *  null when the chunk holds none. Cached until the chunk changes; the
     *  cache is capped and simply rebuilt on demand when cleared. */
    fun snapshot(chunkKey: Long): IntArray? {
        val set = byChunk.get(chunkKey) ?: return null
        if (set.isEmpty()) return null
        snapshots.get(chunkKey)?.let { return it }
        if (snapshots.size >= SNAPSHOT_CACHE_LIMIT) snapshots.clear()
        return set.toIntArray().also { snapshots.put(chunkKey, it) }
    }

    /** Visits every tracked position in one chunk. The [BlockPos] handed to
     *  [action] is a shared mutable instance — copy it to retain. */
    fun forEachInChunk(
        chunkX: Int,
        chunkZ: Int,
        action: (BlockPos.MutableBlockPos, SselithEditKind) -> Unit,
    ) {
        val set = byChunk.get(ChunkPos.asLong(chunkX, chunkZ)) ?: return
        val scratch = BlockPos.MutableBlockPos()
        val originX = chunkX shl 4
        val originZ = chunkZ shl 4
        val iter = set.iterator()
        while (iter.hasNext()) {
            val packed = iter.nextInt()
            scratch.set(originX or localX(packed), unpackY(packed), originZ or localZ(packed))
            action(scratch, unpackKind(packed))
        }
    }

    /** Chunk keys ([ChunkPos.asLong]) holding at least one tracked position. */
    fun trackedChunks(): LongArray = byChunk.keys.toLongArray()

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        val parallelTable = ListTag()
        val parallelIndex = HashMap<BigInteger, Int>()
        val iter = byChunk.long2ObjectEntrySet().fastIterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val set = entry.value
            if (set.isEmpty()) continue
            val chunkTag = CompoundTag()
            chunkTag.putLong(KEY_CHUNK, entry.longKey)
            chunkTag.putIntArray(KEY_POSITIONS, set.toIntArray())
            val tags = parallelByChunk.get(entry.longKey)
            if (tags != null && tags.isNotEmpty()) {
                // Pairs of (position bits, index into the parallel table).
                val pairs = IntArrayList(tags.size * 2)
                for (tagged in tags.int2ObjectEntrySet()) {
                    pairs.add(tagged.intKey)
                    pairs.add(parallelIndex.getOrPut(tagged.value) {
                        parallelTable.add(ByteArrayTag(tagged.value.toByteArray()))
                        parallelTable.size - 1
                    })
                }
                chunkTag.putIntArray(KEY_PARALLELS, pairs.toIntArray())
            }
            list.add(chunkTag)
        }
        tag.put(KEY_CHUNKS, list)
        tag.put(KEY_PARALLEL_TABLE, parallelTable)
        return tag
    }

    companion object {
        const val NAME = "enderkinesis_sselith_placements"

        private const val KEY_CHUNKS = "chunks"
        private const val KEY_CHUNK = "c"
        private const val KEY_POSITIONS = "p"
        private const val KEY_PARALLELS = "q"
        private const val KEY_PARALLEL_TABLE = "parallels"

        /** Packed entry without its kind bit. */
        private const val POSITION_MASK = 0x7FFFFFFF

        /** Most chunks hold a handful of edits; buckets grow as needed. */
        private const val INITIAL_CHUNK_CAPACITY = 16

        /** Hard cap on tracked positions. A REMOVED record never clears by
         *  itself, so without a cap every block broken in Sselith stays forever. */
        private const val MAX_RECORDS = 1_000_000

        /** Bounds the eviction search for a REMOVED record. */
        private const val EVICTION_SCAN_LIMIT = 4096

        /** Sampling snapshots are a pure cache — bounded, rebuilt on demand. */
        private const val SNAPSHOT_CACHE_LIMIT = 4096

        fun load(tag: CompoundTag): SselithPlacementData {
            val data = SselithPlacementData()
            val table = tag.getList(KEY_PARALLEL_TABLE, Tag.TAG_BYTE_ARRAY.toInt())
            val parallels = Array(table.size) { BigInteger((table[it] as ByteArrayTag).asByteArray) }
            val list = tag.getList(KEY_CHUNKS, Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                val chunkTag = list.getCompound(i)
                val positions = chunkTag.getIntArray(KEY_POSITIONS)
                if (positions.isEmpty()) continue
                val set = IntOpenHashSet(positions.size)
                for (packed in positions) set.add(packed)
                val key = chunkTag.getLong(KEY_CHUNK)
                data.byChunk.put(key, set)
                data.count += set.size
                val pairs = chunkTag.getIntArray(KEY_PARALLELS)
                for (p in 0 until pairs.size / 2) {
                    val parallel = parallels.getOrNull(pairs[p * 2 + 1]) ?: continue
                    data.tagParallel(key, pairs[p * 2], parallel)
                }
            }
            return data
        }

        /** Packing: [31] kind, [30:27] local X, [26:23] local Z, [22:0]
         *  signed Y. 23 bits of Y is ±4M — any conceivable dimension height,
         *  so this survives a Sselith height change. */
        private fun pack(pos: BlockPos, kind: SselithEditKind): Int {
            val kindBit = if (kind == SselithEditKind.REMOVED) 1 shl 31 else 0
            return kindBit or
                ((pos.x and 15) shl 27) or
                ((pos.z and 15) shl 23) or
                (pos.y and 0x7FFFFF)
        }

        private fun localX(packed: Int): Int = (packed ushr 27) and 15

        private fun localZ(packed: Int): Int = (packed ushr 23) and 15

        /** Sign-extends the 23-bit Y field. */
        private fun unpackY(packed: Int): Int = (packed shl 9) shr 9

        private fun unpackKind(packed: Int): SselithEditKind =
            if (packed < 0) SselithEditKind.REMOVED else SselithEditKind.PLACED

        @JvmStatic
        fun decodeX(chunkX: Int, packed: Int): Int = (chunkX shl 4) or localX(packed)

        @JvmStatic
        fun decodeZ(chunkZ: Int, packed: Int): Int = (chunkZ shl 4) or localZ(packed)

        @JvmStatic
        fun decodeY(packed: Int): Int = unpackY(packed)

        @JvmStatic
        fun decodeKind(packed: Int): SselithEditKind = unpackKind(packed)
    }
}
