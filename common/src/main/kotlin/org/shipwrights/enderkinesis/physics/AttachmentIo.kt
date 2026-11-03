package org.shipwrights.enderkinesis.physics

import java.io.DataInput
import java.io.DataOutput

/** Length-prefixed array I/O for [org.valkyrienskies.core.api.attachment.BinaryAttachmentSerializer]s. */
internal object AttachmentIo {

    private val LOG = com.mojang.logging.LogUtils.getLogger()

    fun warnDropped(attachment: Class<*>, cause: Exception) {
        LOG.warn("[EK] Saved {} data is bad; it starts empty until it is rebuilt.", attachment.simpleName, cause)
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
