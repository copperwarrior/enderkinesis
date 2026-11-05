package org.shipwrights.enderkinesis.item

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/** A finished view sketch: ink coverage only, 16 levels, deflated, after a 2-byte side length. */
object SilverpointPicture {

    const val MAX_SIDE = 512
    private const val LEVELS_SHIFT = 4
    private const val LEVEL_SCALE = 17

    class Picture(val side: Int, val alpha: ByteArray)

    fun pack(side: Int, alpha: ByteArray): ByteArray {
        require(side in 1..MAX_SIDE && alpha.size == side * side) { "bad picture $side, ${alpha.size}" }
        val nibbles = ByteArray((alpha.size + 1) / 2)
        for (i in alpha.indices) {
            val level = (alpha[i].toInt() and 0xFF) ushr LEVELS_SHIFT
            val shifted = if (i % 2 == 0) level shl LEVELS_SHIFT else level
            nibbles[i / 2] = (nibbles[i / 2].toInt() or shifted).toByte()
        }
        val out = ByteArrayOutputStream()
        out.write(side ushr 8)
        out.write(side and 0xFF)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            DeflaterOutputStream(out, deflater).use { it.write(nibbles) }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }

    /** Null when [packed] is not a picture; the output size is fixed by the header, so bad data cannot grow it. */
    fun unpack(packed: ByteArray): Picture? {
        val side = sideOf(packed) ?: return null
        val expected = (side * side + 1) / 2
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
        val alpha = ByteArray(side * side) { i ->
            val byte = nibbles[i / 2].toInt()
            val level = if (i % 2 == 0) (byte ushr LEVELS_SHIFT) and 0xF else byte and 0xF
            (level * LEVEL_SCALE).toByte()
        }
        return Picture(side, alpha)
    }

    fun sideOf(packed: ByteArray): Int? {
        if (packed.size < 3) return null
        val side = ((packed[0].toInt() and 0xFF) shl 8) or (packed[1].toInt() and 0xFF)
        return side.takeIf { it in 1..MAX_SIDE }
    }

    fun key(packed: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(packed).take(KEY_BYTES).joinToString("") { "%02x".format(it) }

    private const val KEY_BYTES = 8
}
