package org.shipwrights.enderkinesis.construction

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Writes the chunks of one upload straight to [file] and hashes them on the way, so no upload is held in memory.
 * Chunks must come in sequence order, hold at most [maxChunk] bytes each, and fill exactly [size] bytes.
 */
class PlanUploadStream(
    val name: String,
    val size: Long,
    val hash: String,
    val file: Path,
    private val maxChunk: Int = CHUNK,
) : AutoCloseable {
    enum class Step { MORE, DONE, BAD_SEQUENCE, BAD_CHUNK, TOO_MANY_BYTES }

    private val out: OutputStream
    private val digest = MessageDigest.getInstance("SHA-256")
    private var received = 0L
    private var nextSequence = 0

    init {
        require(maxChunk in 1..CHUNK) { "bad upload chunk size $maxChunk" }
        require(size > 0 && chunkCount(size, maxChunk) <= Int.MAX_VALUE) { "bad upload size $size" }
        require(PlanRef.isHash(hash)) { "bad upload hash: $hash" }
        out = BufferedOutputStream(Files.newOutputStream(file), BUFFER)
    }

    val done: Boolean get() = received == size

    /** Chunks written so far; the server sends it back as the upload ack. */
    val chunksWritten: Int get() = nextSequence

    fun accept(sequence: Int, chunk: ByteArray): Step {
        if (done || sequence != nextSequence) return Step.BAD_SEQUENCE
        if (chunk.isEmpty() || chunk.size > maxChunk) return Step.BAD_CHUNK
        if (chunk.size > size - received) return Step.TOO_MANY_BYTES
        out.write(chunk)
        digest.update(chunk)
        received += chunk.size
        nextSequence++
        if (done) out.close()
        return if (done) Step.DONE else Step.MORE
    }

    /** True when the whole file arrived and its SHA-256 is [hash]. Call once, after [Step.DONE]. */
    fun hashMatches(): Boolean {
        check(done) { "upload is not complete: $received of $size bytes" }
        return hex(digest.digest()) == hash
    }

    override fun close() = out.close()

    companion object {
        // Vanilla serverbound custom payloads stop at 32767 bytes; the rest is room for the header.
        const val CHUNK = 30_000
        private const val BUFFER = 64 * 1024

        fun chunkCount(size: Long, chunk: Int = CHUNK): Long = (size + chunk - 1) / chunk

        fun hex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it) }

        fun sha256Hex(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))
    }
}
