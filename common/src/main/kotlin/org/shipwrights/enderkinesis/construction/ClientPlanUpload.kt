package org.shipwrights.enderkinesis.construction

import com.mojang.logging.LogUtils
import net.minecraft.Util
import net.minecraft.client.Minecraft
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

/**
 * Client only: streams one local plan file to the server. A worker hashes the file first; then the client sends
 * chunks of the server's packet size while fewer than the server's window wait for the ack, so the server sets the rate.
 */
object ClientPlanUpload {
    private val LOG = LogUtils.getLogger()
    private const val TOO_LARGE = "message.enderkinesis.plan.too_large"

    private class Upload(val file: LocalPlanFile, val size: Long, val limits: ClientUploadLimits) {
        var input: InputStream? = null
        var sent = 0
        var acked = 0
        val total = PlanUploadStream.chunkCount(size, limits.packetBytes).toInt()
    }

    // Client thread only. The server sends its limits on join.
    private var limits = ClientUploadLimits.DEFAULT
    private var upload: Upload? = null
    private var waiting = false

    /** The server's answer to the last upload; null while an upload runs or before the first one. */
    var lastResult: PlanUploadResult? = null
        private set

    /** Starts an upload and cancels the one before. False when the file cannot be read. */
    fun upload(file: LocalPlanFile): Boolean {
        cancel()
        lastResult = null
        val size = try {
            Files.size(file.path)
        } catch (e: IOException) {
            LOG.warn("Cannot read plan file {}", file.path, e)
            return false
        }
        if (size < 1 || PlanUploadStream.chunkCount(size, limits.packetBytes) > Int.MAX_VALUE) return false
        if (limitsSize() && size > limits.maxFileBytes) {
            val max = PlanLimits.megabytes(limits.maxFileBytes).toString()
            lastResult = PlanUploadResult(null, listOf(PlanIssue(TOO_LARGE, listOf(PlanLimits.megabytes(size).toString(), max))))
            return true
        }
        val started = Upload(file, size, limits)
        upload = started
        CompletableFuture.supplyAsync({ ClientPlanFiles.hash(file.path) { false } }, Util.backgroundExecutor()).whenComplete { hash, error ->
            Minecraft.getInstance().execute { begin(started, hash, error) }
        }
        return true
    }

    // An integrated server opened to LAN counts as multiplayer, as on the server side.
    private fun limitsSize(): Boolean = Minecraft.getInstance().singleplayerServer?.isPublished ?: true

    private fun begin(started: Upload, hash: String?, error: Throwable?) {
        if (upload !== started) return
        if (hash == null || error != null) {
            LOG.warn("Cannot hash plan file {}", started.file.path, error)
            cancel()
            return
        }
        started.input = try {
            Files.newInputStream(started.file.path)
        } catch (e: IOException) {
            LOG.warn("Cannot read plan file {}", started.file.path, e)
            cancel()
            return
        }
        PlanNetwork.sendUploadBegin(started.file.name, started.size, hash)
    }

    /** The part sent, from 0 to 1 (1 while the server checks the file), or null when no upload runs. */
    fun progress(): Float? {
        if (waiting) return 1f
        val current = upload ?: return null
        return current.acked.toFloat() / current.total
    }

    fun cancel() {
        upload?.input?.let(::closeQuietly)
        upload = null
        waiting = false
    }

    internal fun tick() {
        val current = upload ?: return
        val input = current.input ?: return
        val packet = current.limits.packetBytes
        while (current.sent < current.total && current.sent - current.acked < current.limits.window) {
            val bytes = try {
                input.readNBytes(minOf(packet.toLong(), current.size - current.sent.toLong() * packet).toInt())
            } catch (e: IOException) {
                LOG.warn("Cannot read plan file {}", current.file.path, e)
                cancel()
                return
            }
            // A file that shrank since the hash: the server would refuse the hash anyway.
            if (bytes.isEmpty()) return cancel()
            PlanNetwork.sendUploadChunk(current.sent, bytes)
            current.sent++
        }
        if (current.sent == current.total) {
            closeQuietly(input)
            current.input = null
        }
    }

    internal fun onAck(acked: Int) {
        val current = upload ?: return
        if (acked in current.acked..current.sent) current.acked = acked
        if (current.acked == current.total) {
            upload = null
            waiting = true
        }
    }

    internal fun onResult(result: PlanUploadResult) {
        cancel()
        lastResult = result
    }

    internal fun onLimits(received: ClientUploadLimits) {
        limits = received
    }

    internal fun onQuit() {
        cancel()
        lastResult = null
        limits = ClientUploadLimits.DEFAULT
    }

    private fun closeQuietly(input: InputStream) {
        try {
            input.close()
        } catch (e: IOException) {
            LOG.debug("Cannot close plan file", e)
        }
    }
}

/** The server's upload limits, as the client needs them. */
data class ClientUploadLimits(val packetBytes: Int, val window: Int, val maxFileBytes: Long) {
    companion object {
        val DEFAULT = BuildConfig.DEFAULT.plans.let { ClientUploadLimits(it.uploadPacketBytes, it.uploadWindow, it.maxFileBytes) }
    }
}
