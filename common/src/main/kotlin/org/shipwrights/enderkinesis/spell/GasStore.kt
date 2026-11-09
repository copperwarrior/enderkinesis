package org.shipwrights.enderkinesis.spell

import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData

private const val NAME = "enderkinesis_gas"

/** One chunk of gas of one kind. Flows are packed int8 x3, as on the wire. */
class GasChunkRecord(val cells: Int2FloatOpenHashMap, val flows: Int2IntOpenHashMap)

/**
 * The gas of one dimension that is saved. [frozen] holds the chunks that are unloaded, for each kind, so they do not
 * change. The saved data is [frozen] plus the live chunks that [GasField] reports at save time.
 */
class GasStore(private val level: ServerLevel) : SavedData() {
    private val perKind = Array(GasKind.entries.size) { Long2ObjectOpenHashMap<GasChunkRecord>() }

    fun frozen(kind: GasKind): Long2ObjectOpenHashMap<GasChunkRecord> = perKind[kind.ordinal]

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for (kind in GasKind.entries) {
            val it = frozen(kind).long2ObjectEntrySet().fastIterator()
            while (it.hasNext()) {
                val e = it.next()
                list.add(write(kind, e.longKey, e.value.cells, e.value.flows))
            }
        }
        GasField.forEachLive(level) { kind, key, cells, flows -> list.add(write(kind, key, cells, flows)) }
        tag.put(CHUNKS, list)
        return tag
    }

    fun read(tag: CompoundTag) {
        val list = tag.getList(CHUNKS, Tag.TAG_COMPOUND.toInt())
        for (n in 0 until minOf(list.size, MAX_CHUNKS)) {
            val c = list.getCompound(n)
            val kind = GasKind.entries.firstOrNull { it.name == c.getString(KIND) } ?: continue
            val cellIdx = c.getIntArray(CELL_INDEX); val cellAmt = c.getIntArray(CELL_AMOUNT)
            val flowIdx = c.getIntArray(FLOW_INDEX); val flowPacked = c.getIntArray(FLOW_PACKED)
            val cells = Int2FloatOpenHashMap()
            for (i in 0 until minOf(cellIdx.size, cellAmt.size, MAX_CHUNK_CELLS)) {
                val a = Float.fromBits(cellAmt[i])
                if (a > 0f && a <= GasField.MAX_AMOUNT && cellIdx[i] >= 0) cells.put(cellIdx[i], a)
            }
            if (cells.isEmpty()) continue
            val flows = Int2IntOpenHashMap()
            for (i in 0 until minOf(flowIdx.size, flowPacked.size, MAX_CHUNK_CELLS)) flows.put(flowIdx[i], flowPacked[i])
            frozen(kind).put(c.getLong(KEY), GasChunkRecord(cells, flows))
        }
    }

    private fun write(kind: GasKind, key: Long, cells: Int2FloatOpenHashMap, flows: Int2IntOpenHashMap?): CompoundTag {
        val c = CompoundTag()
        c.putString(KIND, kind.name)
        c.putLong(KEY, key)
        val n = minOf(cells.size, MAX_CHUNK_CELLS)
        val cellIdx = IntArray(n); val cellAmt = IntArray(n)
        var i = 0
        val it = cells.int2FloatEntrySet().fastIterator()
        while (it.hasNext() && i < n) {
            val e = it.next()
            cellIdx[i] = e.intKey; cellAmt[i] = e.floatValue.toRawBits(); i++
        }
        c.putIntArray(CELL_INDEX, cellIdx); c.putIntArray(CELL_AMOUNT, cellAmt)
        val m = minOf(flows?.size ?: 0, MAX_CHUNK_CELLS)
        val flowIdx = IntArray(m); val flowPacked = IntArray(m)
        var j = 0
        val f = flows?.int2IntEntrySet()?.fastIterator()
        while (f != null && f.hasNext() && j < m) {
            val e = f.next()
            flowIdx[j] = e.intKey; flowPacked[j] = e.intValue; j++
        }
        c.putIntArray(FLOW_INDEX, flowIdx); c.putIntArray(FLOW_PACKED, flowPacked)
        return c
    }

    private companion object {
        const val CHUNKS = "chunks"
        const val KIND = "kind"
        const val KEY = "k"
        const val CELL_INDEX = "ci"
        const val CELL_AMOUNT = "ca"
        const val FLOW_INDEX = "fi"
        const val FLOW_PACKED = "fp"
        const val MAX_CHUNKS = 1 shl 20
        const val MAX_CHUNK_CELLS = 1 shl 17
    }
}

fun ServerLevel.gasStore(): GasStore =
    dataStorage.computeIfAbsent({ tag -> GasStore(this).also { it.read(tag) } }, { GasStore(this) }, NAME)
