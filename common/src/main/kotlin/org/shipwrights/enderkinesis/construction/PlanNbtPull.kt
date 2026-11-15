package org.shipwrights.enderkinesis.construction

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import java.io.DataInputStream

/**
 * A pull reader over a raw NBT stream, so a structure file of any size never becomes one tag tree.
 * Skips are iterative with a depth cap: vanilla TagType.skip recurses with no depth limit.
 */
internal class PlanNbtPull(val input: DataInputStream) {
    private val frameElement = IntArray(MAX_DEPTH)
    private val frameLeft = IntArray(MAX_DEPTH)
    private var depth = 0

    fun type(): Int = input.readUnsignedByte()

    fun name(): String = input.readUTF()

    /** Reads a list header: the element type and a checked count. */
    fun listHeader(): ListHeader {
        val element = input.readUnsignedByte()
        val count = input.readInt()
        formatCheck(element in END..LONG_ARRAY && count >= 0) { "bad list header $element x $count" }
        formatCheck(element != END || count == 0) { "list of end tags is not empty" }
        return ListHeader(element, count)
    }

    class ListHeader(val element: Int, val count: Int)

    /** Skips the remaining [count] elements of a list. */
    fun skipElements(element: Int, count: Int) {
        val width = fixedWidth(element)
        if (width > 0) {
            input.skipNBytes(count.toLong() * width)
            return
        }
        for (i in 0 until count) skip(element)
    }

    /** Null when [type] is not a number tag; the value is then still unread. */
    fun number(type: Int): Long? = when (type) {
        BYTE -> input.readByte().toLong()
        SHORT -> input.readShort().toLong()
        INT -> input.readInt().toLong()
        LONG -> input.readLong()
        FLOAT -> input.readFloat().toLong()
        DOUBLE -> input.readDouble().toLong()
        else -> null
    }

    /** A small compound payload (a palette entry) through the vanilla reader, which has its own depth cap. */
    fun compound(maxBytes: Long): CompoundTag = CompoundTag.TYPE.load(input, 1, NbtAccounter(maxBytes))

    /** Reads three ints of an int list into [out]. False when the list has another shape; the list is consumed. */
    fun intTriple(out: IntArray): Boolean {
        val header = listHeader()
        if (header.element != INT || header.count != 3) {
            skipElements(header.element, header.count)
            return false
        }
        out[0] = input.readInt()
        out[1] = input.readInt()
        out[2] = input.readInt()
        return true
    }

    fun skip(type: Int) {
        val base = depth
        open(type)
        while (depth > base) {
            val top = depth - 1
            val element = frameElement[top]
            if (element == COMPOUND_FRAME) {
                val next = input.readUnsignedByte()
                if (next == END) {
                    depth--
                } else {
                    input.skipNBytes(input.readUnsignedShort().toLong())
                    open(next)
                }
            } else if (frameLeft[top] == 0) {
                depth--
            } else {
                frameLeft[top]--
                open(element)
            }
        }
    }

    private fun open(type: Int) {
        val width = fixedWidth(type)
        when {
            width > 0 -> input.skipNBytes(width.toLong())
            type == BYTE_ARRAY -> input.skipNBytes(arrayLength().toLong())
            type == INT_ARRAY -> input.skipNBytes(arrayLength().toLong() * Int.SIZE_BYTES)
            type == LONG_ARRAY -> input.skipNBytes(arrayLength().toLong() * Long.SIZE_BYTES)
            type == STRING -> input.skipNBytes(input.readUnsignedShort().toLong())
            type == LIST -> {
                val header = listHeader()
                val elementWidth = fixedWidth(header.element)
                if (elementWidth > 0) input.skipNBytes(header.count.toLong() * elementWidth) else push(header.element, header.count)
            }
            type == COMPOUND -> push(COMPOUND_FRAME, 0)
            else -> throw PlanFormatException("bad tag type $type")
        }
    }

    private fun arrayLength(): Int = input.readInt().also { formatCheck(it >= 0) { "negative array length $it" } }

    private fun push(element: Int, count: Int) {
        formatCheck(depth < MAX_DEPTH) { "tag nesting is deeper than $MAX_DEPTH" }
        frameElement[depth] = element
        frameLeft[depth] = count
        depth++
    }

    companion object {
        const val END = 0
        const val BYTE = 1
        const val SHORT = 2
        const val INT = 3
        const val LONG = 4
        const val FLOAT = 5
        const val DOUBLE = 6
        const val BYTE_ARRAY = 7
        const val STRING = 8
        const val LIST = 9
        const val COMPOUND = 10
        const val INT_ARRAY = 11
        const val LONG_ARRAY = 12

        // Vanilla's own NBT depth cap.
        private const val MAX_DEPTH = 512
        private const val COMPOUND_FRAME = -1

        private fun fixedWidth(type: Int): Int = when (type) {
            BYTE -> 1
            SHORT -> 2
            INT, FLOAT -> 4
            LONG, DOUBLE -> 8
            else -> 0
        }
    }
}
