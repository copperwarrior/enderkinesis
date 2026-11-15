package org.shipwrights.enderkinesis.construction

import com.mojang.logging.LogUtils
import net.minecraft.Util
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Server side of the streamed upload. Chunks go to a temp file at [PlanLimits.uploadBytesPerTick] per player;
 * the client keeps at most [PlanLimits.uploadWindow] chunks unacknowledged. A new begin or a logout cancels the player's upload.
 */
object PlanUploads {
    private val LOG = LogUtils.getLogger()
    private const val KEY = "message.enderkinesis.plan."

    private class Upload(val stream: PlanUploadStream, val limits: PlanLimits) {
        val pending = ArrayDeque<ByteArray>()
        var nextReceive = 0
        var acked = 0
        val cancelled = AtomicBoolean()
        var checking = false
    }

    // Server thread only. A player's entry is replaced on each begin, so a stale check finds another object and stops.
    private val uploads = HashMap<UUID, Upload>()

    fun cancel(owner: UUID) {
        uploads.remove(owner)?.let(::discard)
    }

    fun clear() {
        uploads.values.forEach(::discard)
        uploads.clear()
    }

    private fun discard(upload: Upload) {
        upload.cancelled.set(true)
        upload.stream.close()
        // A running check deletes the file itself when it stops.
        if (!upload.checking) deleteQuietly(upload.stream.file)
    }

    fun begin(player: ServerPlayer, fileName: String, size: Long, hash: String) {
        cancel(player.uuid)
        val server = player.server
        val limits = ServerBuildConfig.current.plans
        if (!PlanRef.isHash(hash)) return refuseUpload(player, "bad_file")
        // An integrated server opened to LAN counts as multiplayer.
        when (limits.uploadRefusal(size, limitSize = !server.isSingleplayer || server.isPublished)) {
            null -> Unit
            "too_large" -> return refuseUpload(player, "too_large", PlanLimits.megabytes(size), limits.maxFileMegabytes)
            else -> return refuseUpload(player, "bad_file")
        }
        PlanStorage.find(server, player.uuid, hash)?.let { return PlanNetwork.sendResult(player, PlanUploadResult(it, emptyList())) }
        if (!PlanStorage.canStore(server, player.uuid)) return refuseUpload(player, "full", PlanLimits.QUOTA)
        val stream = try {
            val dir = PlanStorage.uploadDir(server)
            Files.createDirectories(dir)
            val part = Files.createTempFile(dir, player.uuid.toString(), ".part")
            PlanUploadStream(cleanPlanName(fileName), size, hash, part, limits.uploadPacketBytes)
        } catch (e: IOException) {
            LOG.error("Cannot start a plan upload for {}", player.uuid, e)
            return refuseUpload(player, "save_failed")
        }
        uploads[player.uuid] = Upload(stream, limits)
    }

    /** Netty hands chunks to the server thread; they wait here until [tick] writes them inside the byte budget. */
    fun chunk(player: ServerPlayer, sequence: Int, bytes: ByteArray) {
        val upload = uploads[player.uuid] ?: return
        if (upload.checking) return
        // An oversized chunk is refused here, so it never waits in memory and a write never passes the tick budget.
        if (bytes.isEmpty() || bytes.size > upload.limits.uploadPacketBytes || sequence != upload.nextReceive ||
            upload.nextReceive - upload.stream.chunksWritten >= upload.limits.uploadWindow
        ) {
            cancel(player.uuid)
            return refuseUpload(player, "bad_transfer")
        }
        upload.nextReceive++
        upload.pending.addLast(bytes)
    }

    fun tick(server: MinecraftServer) {
        if (uploads.isEmpty()) return
        // A copy: a finished or broken upload leaves the map while this loop runs.
        for ((owner, upload) in uploads.entries.toList()) {
            if (upload.checking) continue
            val player = server.playerList.getPlayer(owner) ?: continue
            if (!write(player, upload)) continue
            if (upload.stream.chunksWritten != upload.acked) {
                upload.acked = upload.stream.chunksWritten
                PlanNetwork.sendUploadAck(player, upload.acked)
            }
        }
    }

    /** False when the upload ended (broken, or complete and handed to the check). */
    private fun write(player: ServerPlayer, upload: Upload): Boolean {
        var budget = upload.limits.uploadBytesPerTick
        var first = true
        // At least one chunk per tick, so a budget below one packet cannot stall the upload.
        while (upload.pending.isNotEmpty() && (first || budget >= upload.pending.first().size)) {
            first = false
            val bytes = upload.pending.removeFirst()
            budget -= bytes.size
            val step = try {
                upload.stream.accept(upload.stream.chunksWritten, bytes)
            } catch (e: IOException) {
                LOG.error("Cannot write a plan upload for {}", player.uuid, e)
                cancel(player.uuid)
                refuseUpload(player, "save_failed")
                return false
            }
            when (step) {
                PlanUploadStream.Step.MORE -> Unit
                PlanUploadStream.Step.DONE -> {
                    startCheck(player, upload)
                    return false
                }
                else -> {
                    cancel(player.uuid)
                    refuseUpload(player, "bad_transfer")
                    return false
                }
            }
        }
        return true
    }

    private fun startCheck(player: ServerPlayer, upload: Upload) {
        val server = player.server
        val owner = player.uuid
        upload.checking = true
        // Read on the server thread: the check runs on a worker.
        val trusted = player.hasPermissions(2)
        val converted = upload.stream.file.resolveSibling(upload.stream.file.fileName.toString() + ".plan")
        val scratch = PlanStorage.uploadDir(server)
        CompletableFuture.supplyAsync({ checkUpload(server, upload, converted, scratch, trusted) }, Util.backgroundExecutor())
            .whenComplete { check, error ->
                deleteQuietly(upload.stream.file)
                server.execute { finishCheck(server, owner, upload, converted, check, error) }
            }
    }

    private fun finishCheck(server: MinecraftServer, owner: UUID, upload: Upload, converted: Path, check: PlanCheck?, error: Throwable?) {
        if (uploads[owner] !== upload) {
            deleteQuietly(converted)
            return
        }
        uploads.remove(owner)
        val player = server.playerList.getPlayer(owner)
        if (error != null || check == null) {
            LOG.warn("Plan check failed for {}", owner, error)
            deleteQuietly(converted)
            player?.let { refuseUpload(it, "bad_file") }
            return
        }
        if (!check.accepted) {
            deleteQuietly(converted)
            player?.let { PlanNetwork.sendResult(it, PlanUploadResult(null, check.issues)) }
            return
        }
        val stored = PlanStorage.store(server, owner, upload.stream.hash, upload.stream.name, check, converted)
        deleteQuietly(converted)
        val issues = when (stored) {
            PlanStorage.Store.Full -> listOf(PlanIssue(KEY + "full", listOf(PlanLimits.QUOTA.toString())))
            PlanStorage.Store.Failed -> listOf(PlanIssue(KEY + "save_failed"))
            else -> check.issues
        }
        player?.let { PlanNetwork.sendResult(it, PlanUploadResult(stored.ref, issues)) }
    }

    /** Worker thread: no level or player access, only the frozen registries and the data fixer. */
    private fun checkUpload(server: MinecraftServer, upload: Upload, converted: Path, scratch: Path, trusted: Boolean): PlanCheck {
        if (!upload.stream.hashMatches()) return refusedPlan("hash_mismatch")
        return convertPlanFile(upload.stream.file, converted, scratch, server.fixerUpper, trusted) { upload.cancelled.get() }
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            LOG.warn("Cannot delete plan upload file {}", path, e)
        }
    }

    private fun refuseUpload(player: ServerPlayer, reason: String, vararg args: Any) =
        PlanNetwork.sendResult(player, PlanUploadResult(null, listOf(PlanIssue(KEY + reason, args.map(Any::toString)))))
}
