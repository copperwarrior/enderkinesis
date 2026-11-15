package org.shipwrights.enderkinesis.construction

import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Bad plan data: a refused upload or a damaged plan file. */
class PlanFormatException(message: String) : IOException(message)

internal fun formatCheck(ok: Boolean, message: () -> String) {
    if (!ok) throw PlanFormatException(message())
}

/** Global palette id of plan air: the build clears the world cell. */
const val PLAN_AIR_ID = 0

/** Global palette id of structure void (vanilla's meaning): the build never touches the world cell. */
const val PLAN_KEEP_ID = 1

private const val STRUCTURE_VOID = "minecraft:structure_void"

/** The palette tag at [PLAN_KEEP_ID]. */
fun planKeepTag(): CompoundTag = CompoundTag().also { it.putString("Name", STRUCTURE_VOID) }

/**
 * The cells of one section as global palette ids ([PLAN_AIR_ID], [PLAN_KEEP_ID] or a block), packed like vanilla
 * SimpleBitStorage: [bits] per cell, no cell spans two longs. [locals] maps a packed value to a global id; locals[0] is 0.
 */
class PlanSectionData internal constructor(
    @PublishedApi internal val locals: IntArray,
    @PublishedApi internal val bits: Int,
    @PublishedApi internal val packed: LongArray,
    val nonAir: Int,
) {
    @PublishedApi internal val perLong: Int = Long.SIZE_BITS / bits

    @PublishedApi internal val mask: Long = (1L shl bits) - 1

    fun idAt(x: Int, y: Int, z: Int): Int = locals[local(cellIndex(x, y, z))]

    /** By [cellIndex]. */
    fun isKeep(cell: Int): Boolean = locals[local(cell)] == PLAN_KEEP_ID

    @PublishedApi
    internal fun local(cell: Int): Int = (packed[cell / perLong] ushr (cell % perLong * bits) and mask).toInt()

    inline fun forEachNonAir(action: (x: Int, y: Int, z: Int, id: Int) -> Unit) {
        for (cell in 0 until PLAN_SECTION_CELLS) {
            val id = locals[local(cell)]
            if (id > PLAN_KEEP_ID) action(cell and 15, cell ushr 8, cell ushr 4 and 15, id)
        }
    }

    companion object {
        /** From global ids in cell order, or null when every cell is [PLAN_KEEP_ID]. */
        fun of(ids: IntArray): PlanSectionData? {
            require(ids.size == PLAN_SECTION_CELLS) { "a section has $PLAN_SECTION_CELLS cells, not ${ids.size}" }
            val toLocal = HashMap<Int, Int>()
            toLocal[0] = 0
            var nonAir = 0
            var stored = false
            for (id in ids) {
                require(id >= 0) { "negative palette id $id" }
                if (id > PLAN_KEEP_ID) nonAir++
                if (id != PLAN_KEEP_ID) stored = true
                toLocal.getOrPut(id) { toLocal.size }
            }
            if (!stored) return null
            val locals = IntArray(toLocal.size)
            for ((global, local) in toLocal) locals[local] = global
            val bits = bitsFor(locals.size)
            val perLong = Long.SIZE_BITS / bits
            val packed = LongArray((PLAN_SECTION_CELLS + perLong - 1) / perLong)
            for (cell in ids.indices) {
                val local = toLocal.getValue(ids[cell]).toLong()
                packed[cell / perLong] = packed[cell / perLong] or (local shl (cell % perLong * bits))
            }
            return PlanSectionData(locals, bits, packed, nonAir)
        }

        internal fun bitsFor(count: Int): Int = maxOf(1, Int.SIZE_BITS - Integer.numberOfLeadingZeros(count - 1))
    }
}

/** Cell index inside a section: x fastest, then z, then y (the vanilla section order). */
fun cellIndex(x: Int, y: Int, z: Int): Int = (y shl 8) or (z shl 4) or x

private const val MAX_BITS = 32

/** The deflated payload of one section, as the plan file stores it and the S2C fetch sends it. */
fun PlanSectionData.encode(): ByteArray {
    val raw = ByteArrayOutputStream()
    DataOutputStream(raw).use { out ->
        out.writeVarInt(locals.size - 1)
        for (i in 1 until locals.size) out.writeVarInt(locals[i])
        out.writeByte(bits)
        for (word in packed) out.writeLong(word)
    }
    val deflater = Deflater(Deflater.BEST_SPEED)
    try {
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}

private const val BUFFER = 8192

/** The largest raw payload: 4096 locals as 5-byte varints, the bits byte, and 4096 longs at 32 bits each. */
private const val MAX_RAW = 5 + PLAN_SECTION_CELLS * 5 + 1 + PLAN_SECTION_CELLS / 2 * Long.SIZE_BYTES

/** Validates every value, so a damaged file or a hostile server cannot index out of the palette. */
fun decodePlanSection(payload: ByteArray, paletteSize: Int): PlanSectionData {
    val raw = inflate(payload)
    val input = DataInputStream(ByteArrayInputStream(raw))
    val count = input.readVarInt() + 1
    formatCheck(count in 1..PLAN_SECTION_CELLS) { "bad section palette size $count" }
    val locals = IntArray(count)
    for (i in 1 until count) {
        locals[i] = input.readVarInt()
        formatCheck(locals[i] in 1 until paletteSize) { "section palette id ${locals[i]} is outside 1..${paletteSize - 1}" }
    }
    val bits = input.readUnsignedByte()
    formatCheck(bits in PlanSectionData.bitsFor(count)..MAX_BITS) { "bad section bits $bits for $count states" }
    val perLong = Long.SIZE_BITS / bits
    val packed = LongArray((PLAN_SECTION_CELLS + perLong - 1) / perLong) { input.readLong() }
    formatCheck(input.available() == 0) { "section payload has extra bytes" }
    val data = PlanSectionData(locals, bits, packed, 0)
    var nonAir = 0
    var stored = false
    for (cell in 0 until PLAN_SECTION_CELLS) {
        val local = data.local(cell)
        formatCheck(local < count) { "section cell value $local is outside the section palette" }
        if (locals[local] > PLAN_KEEP_ID) nonAir++
        if (locals[local] != PLAN_KEEP_ID) stored = true
    }
    formatCheck(stored) { "a stored section holds only structure void" }
    return PlanSectionData(locals, bits, packed, nonAir)
}

private fun inflate(payload: ByteArray): ByteArray {
    val inflater = Inflater()
    try {
        inflater.setInput(payload)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw PlanFormatException("section payload is cut short")
            out.write(buffer, 0, n)
            formatCheck(out.size() <= MAX_RAW) { "section payload expands past $MAX_RAW bytes" }
        }
        formatCheck(inflater.remaining == 0) { "section payload has extra bytes" }
        return out.toByteArray()
    } catch (e: DataFormatException) {
        throw PlanFormatException("section payload is not deflate data: ${e.message}")
    } finally {
        inflater.end()
    }
}

/**
 * The start of a plan file, and all a client needs before it asks for sections. Global palette entry 0 is air and 1 is
 * structure void. [nonEmpty] holds the indices of the stored sections in ascending order; the others are all void.
 */
class PlanHeader(val size: Vec3i, val nonAir: Long, val palette: List<CompoundTag>, val nonEmpty: IntArray) {
    val grid = PlanGrid(size)

    init {
        require(palette.size > PLAN_KEEP_ID) { "a plan palette holds air at 0 and structure void at 1" }
        for (i in 1 until nonEmpty.size) require(nonEmpty[i] > nonEmpty[i - 1]) { "section indices must ascend" }
        require(nonEmpty.isEmpty() || nonEmpty.first() >= 0 && nonEmpty.last() < grid.count) { "section index outside the grid" }
    }

    /** The rank of section [index] among the stored sections, or a negative value when it is empty. */
    fun rank(index: Int): Int = java.util.Arrays.binarySearch(nonEmpty, index)

    fun write(out: DataOutput) {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(size.x)
        out.writeInt(size.y)
        out.writeInt(size.z)
        out.writeLong(nonAir)
        out.writeInt(palette.size)
        for (tag in palette) NbtIo.write(tag, out)
        out.writeInt(nonEmpty.size)
        for (index in nonEmpty) out.writeInt(index)
    }

    /** The header byte count. The section table follows it: per stored section, a long file offset and an int length. */
    fun byteLength(): Long = ByteArrayOutputStream().also { write(DataOutputStream(it)) }.size().toLong()

    companion object {
        const val MAGIC = 0x454B5053
        const val VERSION = 2
        const val TABLE_ENTRY = Long.SIZE_BYTES + Int.SIZE_BYTES
        private const val MAX_ENTRY_BYTES = 64L * 1024

        fun read(input: DataInput): PlanHeader {
            formatCheck(input.readInt() == MAGIC) { "not a plan file" }
            formatCheck(input.readInt() == VERSION) { "unknown plan file version" }
            val size = Vec3i(input.readInt(), input.readInt(), input.readInt())
            formatCheck(PlanGrid.fits(size)) { "bad plan size $size" }
            val nonAir = input.readLong()
            val paletteSize = input.readInt()
            formatCheck(paletteSize > PLAN_KEEP_ID && nonAir >= 0) { "bad plan header" }
            // Each entry costs bytes in the stream, so a hostile count runs out of data before it runs out of memory.
            val palette = ArrayList<CompoundTag>(minOf(paletteSize, BUFFER))
            repeat(paletteSize) { palette += NbtIo.read(input, NbtAccounter(MAX_ENTRY_BYTES)) }
            val count = input.readInt()
            val grid = PlanGrid(size)
            formatCheck(count in 0..grid.count) { "bad stored section count $count" }
            // Grows as entries arrive, for the same reason as the palette.
            var nonEmpty = IntArray(minOf(count, BUFFER))
            var last = -1
            for (i in 0 until count) {
                if (i == nonEmpty.size) nonEmpty = nonEmpty.copyOf(minOf(count.toLong(), i * 2L).toInt())
                nonEmpty[i] = input.readInt()
                formatCheck(nonEmpty[i] > last && nonEmpty[i] < grid.count) { "bad stored section index ${nonEmpty[i]}" }
                last = nonEmpty[i]
            }
            return PlanHeader(size, nonAir, palette, nonEmpty)
        }
    }
}

internal fun DataOutput.writeVarInt(value: Int) {
    var v = value
    while (v and 0x7F.inv() != 0) {
        writeByte(v and 0x7F or 0x80)
        v = v ushr 7
    }
    writeByte(v)
}

internal fun DataInput.readVarInt(): Int {
    var value = 0
    var shift = 0
    while (true) {
        val b = readUnsignedByte()
        value = value or (b and 0x7F shl shift)
        if (b and 0x80 == 0) return value
        shift += 7
        formatCheck(shift < Int.SIZE_BITS) { "varint is too long" }
    }
}
