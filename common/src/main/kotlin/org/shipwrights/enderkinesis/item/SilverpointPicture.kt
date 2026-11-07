package org.shipwrights.enderkinesis.item

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ThreadLocalRandom
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/**
 * A finished view sketch, deflated after a 2-byte side length. A grey picture holds ink coverage only, 16 levels.
 * A colour picture (the header's top bit) holds each pixel as red, green, blue and alpha at 16 levels each.
 */
object SilverpointPicture {

    const val MAX_SIDE = 512
    private const val LEVELS_SHIFT = 4
    private const val LEVEL_SCALE = 17
    private const val COLOUR_FLAG = 0x8000
    private const val SIDE_MASK = 0x7FFF

    /** [abgr] is NativeImage order, and only in a colour picture. */
    class Picture(val side: Int, val alpha: ByteArray, val abgr: IntArray?)

    fun pack(side: Int, alpha: ByteArray): ByteArray {
        require(side in 1..MAX_SIDE && alpha.size == side * side) { "bad picture $side, ${alpha.size}" }
        val nibbles = ByteArray((alpha.size + 1) / 2)
        for (i in alpha.indices) {
            val level = (alpha[i].toInt() and 0xFF) ushr LEVELS_SHIFT
            val shifted = if (i % 2 == 0) level shl LEVELS_SHIFT else level
            nibbles[i / 2] = (nibbles[i / 2].toInt() or shifted).toByte()
        }
        return deflated(side, nibbles)
    }

    /** [abgr] in NativeImage order; a clear pixel keeps no colour, so the paper deflates well. */
    fun packColour(side: Int, abgr: IntArray): ByteArray {
        require(side in 1..MAX_SIDE && abgr.size == side * side) { "bad picture $side, ${abgr.size}" }
        val bytes = ByteArray(abgr.size * 2)
        for (i in abgr.indices) {
            val pixel = abgr[i]
            val a = (pixel ushr 24) ushr LEVELS_SHIFT
            if (a == 0) continue
            val r = (pixel and 0xFF) ushr LEVELS_SHIFT
            val g = ((pixel ushr 8) and 0xFF) ushr LEVELS_SHIFT
            val b = ((pixel ushr 16) and 0xFF) ushr LEVELS_SHIFT
            bytes[i * 2] = ((r shl LEVELS_SHIFT) or g).toByte()
            bytes[i * 2 + 1] = ((b shl LEVELS_SHIFT) or a).toByte()
        }
        return deflated(side or COLOUR_FLAG, bytes)
    }

    private fun deflated(header: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header ushr 8)
        out.write(header and 0xFF)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            DeflaterOutputStream(out, deflater).use { it.write(body) }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }

    /** Null when [packed] is not a picture; the output size is fixed by the header, so bad data cannot grow it. */
    fun unpack(packed: ByteArray): Picture? {
        val side = sideOf(packed) ?: return null
        val colour = isColour(packed)
        val expected = if (colour) side * side * 2 else (side * side + 1) / 2
        val nibbles = ByteArray(expected)
        val inflater = Inflater()
        try {
            inflater.setInput(packed, 2, packed.size - 2)
            var n = 0
            while (n < expected) {
                val read = inflater.inflate(nibbles, n, expected - n)
                if (read == 0) break
                n += read
            }
            if (n < expected) return null
        } catch (_: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
        if (colour) return colourPicture(side, nibbles)
        val alpha = ByteArray(side * side) { i ->
            val byte = nibbles[i / 2].toInt()
            val level = if (i % 2 == 0) (byte ushr LEVELS_SHIFT) and 0xF else byte and 0xF
            (level * LEVEL_SCALE).toByte()
        }
        return Picture(side, alpha, null)
    }

    private fun colourPicture(side: Int, bytes: ByteArray): Picture {
        val abgr = IntArray(side * side)
        val alpha = ByteArray(side * side)
        for (i in abgr.indices) {
            val rg = bytes[i * 2].toInt()
            val ba = bytes[i * 2 + 1].toInt()
            val r = ((rg ushr LEVELS_SHIFT) and 0xF) * LEVEL_SCALE
            val g = (rg and 0xF) * LEVEL_SCALE
            val b = ((ba ushr LEVELS_SHIFT) and 0xF) * LEVEL_SCALE
            val a = (ba and 0xF) * LEVEL_SCALE
            alpha[i] = a.toByte()
            abgr[i] = (a shl 24) or (b shl 16) or (g shl 8) or r
        }
        return Picture(side, alpha, abgr)
    }

    fun sideOf(packed: ByteArray): Int? {
        if (packed.size < 3) return null
        val side = header(packed) and SIDE_MASK
        return side.takeIf { it in 1..MAX_SIDE }
    }

    fun isColour(packed: ByteArray): Boolean = packed.size >= 2 && (header(packed) and COLOUR_FLAG) != 0

    private fun header(packed: ByteArray): Int = ((packed[0].toInt() and 0xFF) shl 8) or (packed[1].toInt() and 0xFF)

    fun key(packed: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(packed).take(KEY_BYTES).joinToString("") { "%02x".format(it) }

    private const val KEY_BYTES = 8

    /** A new picture's id in the store: random, in the same form as [key], so sketch tags read both. */
    fun newId(): String = (0 until KEY_BYTES).joinToString("") { "%02x".format(ThreadLocalRandom.current().nextInt(256)) }

    fun isId(id: String): Boolean = id.length == KEY_BYTES * 2 && id.all { it in '0'..'9' || it in 'a'..'f' }
}
