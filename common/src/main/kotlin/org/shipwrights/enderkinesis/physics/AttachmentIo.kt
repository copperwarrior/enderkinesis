package org.shipwrights.enderkinesis.physics

import com.fasterxml.jackson.databind.JsonNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.IOException

/**
 * Binary attachment saves that go through vs-core's Jackson path. Do not use BinaryAttachmentSerializer:
 * vs-core casts the saved node to BinaryNode, but a reloaded ship gives a base64 TextNode, and the cast crashes.
 */
internal object AttachmentIo {

    private val LOG = com.mojang.logging.LogUtils.getLogger()

    fun warnDropped(attachment: Class<*>, cause: Exception) {
        LOG.warn("[EK] Saved {} data is bad; it starts empty until it is rebuilt.", attachment.simpleName, cause)
    }

    fun encode(write: (DataOutput) -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(write)
        return bytes.toByteArray()
    }

    /** Never throws: a failed attachment makes every later setAttachment on that ship throw. */
    fun <T> decode(node: JsonNode, attachment: Class<*>, empty: () -> T, read: (DataInput) -> T): T {
        val bytes = try {
            node.binaryValue()
        } catch (e: IOException) {
            warnDropped(attachment, e)
            null
        } ?: return empty()
        return read(DataInputStream(ByteArrayInputStream(bytes)))
    }

    /** Upper bound on a stored array, so a corrupt length cannot allocate gigabytes. */
    private const val MAX_ARRAY_LENGTH = 1 shl 24

    fun writeIntArray(output: DataOutput, values: IntArray) {
        output.writeInt(values.size)
        for (v in values) output.writeInt(v)
    }

    fun readIntArray(input: DataInput): IntArray {
        val size = input.readInt()
        require(size in 0..MAX_ARRAY_LENGTH) { "array length $size out of range" }
        return IntArray(size) { input.readInt() }
    }

    fun writeLongArray(output: DataOutput, values: LongArray) {
        output.writeInt(values.size)
        for (v in values) output.writeLong(v)
    }

    fun readLongArray(input: DataInput): LongArray {
        val size = input.readInt()
        require(size in 0..MAX_ARRAY_LENGTH) { "array length $size out of range" }
        return LongArray(size) { input.readLong() }
    }
}
