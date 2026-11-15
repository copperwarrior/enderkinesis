package org.shipwrights.enderkinesis.construction

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import net.minecraft.SharedConstants
import net.minecraft.Util
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.structure.BoundingBox
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Saves a world box as a plan, a few sections each tick. Columns of sections go bottom-up; a chunk ticket loads
 * the chunks of the next columns, and a column starts only when [net.minecraft.server.level.ServerChunkCache.getChunkNow]
 * has all its chunks. Payloads spill to disk, so memory stays bounded for any box.
 */
object PlanCaptures {
    private val LOG = LogUtils.getLogger()
    private const val KEY = "item.enderkinesis.staff_of_command.build."
    private const val PLAN_KEY = "message.enderkinesis.plan."

    /** Columns with chunk tickets ahead of the one being read, so loads overlap the reads. */
    private const val LOOKAHEAD = 4
    private const val PERCENT = 100L

    // Each column adds its own tickets, so two columns that share a chunk do not remove the other's ticket.
    private val TICKET: TicketType<Long> = TicketType.create("enderkinesis_plan_capture", Comparator.naturalOrder())

    @Suppress("LongParameterList") // One save's fixed inputs, set once at the start.
    private class Capture(
        val owner: UUID,
        val name: String,
        val level: ServerLevel,
        val min: BlockPos,
        val writer: PlanCaptureWriter,
        val data: Path,
        val sanitizer: PlanPaletteSanitizer,
        val serial: Long,
        /** After the plan is stored or found stored; the player is null when offline. */
        val onStored: (ServerPlayer?, PlanRef) -> Unit,
    ) {
        val grid = writer.grid
        val size: Vec3i = writer.size
        val columns = grid.x * grid.z
        var column = 0
        var sy = 0
        var ticketed = 0
        var finishing = false
        var percent = -1
        var strippedData = 0L

        // Block state to (global id shl 1) or 1 when the state has a block entity.
        val codes = IdentityHashMap<BlockState, Int>()
        val cells = IntArray(PLAN_SECTION_CELLS)
        val chunks = arrayOfNulls<LevelChunk>(4)
        val cursor = BlockPos.MutableBlockPos()
        var chunkX = 0
        var chunkZ = 0
    }

    // Server thread only.
    private val captures = HashMap<UUID, Capture>()
    private var serials = 0L

    fun init() {
        PlayerEvent.PLAYER_QUIT.register { cancel(it.uuid) }
        LifecycleEvent.SERVER_STOPPING.register { clear() }
    }

    fun running(owner: UUID): Boolean = owner in captures

    /** Starts to save [box] of [level] for [player]; returns a refusal message key, or null when the save started. */
    fun start(
        player: ServerPlayer, level: ServerLevel, box: BoundingBox, name: String, onStored: (ServerPlayer?, PlanRef) -> Unit,
    ): String? {
        val server = player.server
        val size = Vec3i(box.xSpan, box.ySpan, box.zSpan)
        return when {
            player.uuid in captures -> "capture_running"
            !PlanGrid.fits(size) -> "capture_too_large"
            !PlanStorage.canStore(server, player.uuid) -> "capture_full"
            else -> begin(player, level, box, size, name, onStored)
        }
    }

    @Suppress("LongParameterList") // The checked inputs of start, passed on once.
    private fun begin(
        player: ServerPlayer, level: ServerLevel, box: BoundingBox, size: Vec3i, name: String,
        onStored: (ServerPlayer?, PlanRef) -> Unit,
    ): String? {
        val server = player.server
        val data = try {
            val dir = PlanStorage.uploadDir(server)
            Files.createDirectories(dir)
            Files.createTempFile(dir, player.uuid.toString(), ".capture")
        } catch (e: IOException) {
            LOG.error("Cannot start a plan capture for {}", player.uuid, e)
            return "capture_failed"
        }
        val writer = try {
            PlanCaptureWriter(size, data)
        } catch (e: IOException) {
            LOG.error("Cannot start a plan capture for {}", player.uuid, e)
            deleteQuietly(data)
            return "capture_failed"
        }
        // Read on the server thread, as for an upload.
        val sanitizer = PlanPaletteSanitizer.vanilla(server.fixerUpper, trusted = player.hasPermissions(2))
        val min = BlockPos(box.minX(), box.minY(), box.minZ())
        captures[player.uuid] = Capture(player.uuid, name, level, min, writer, data, sanitizer, serials++, onStored)
        return null
    }

    /** Stops the player's save and deletes its files. False when no save runs. */
    fun cancel(owner: UUID): Boolean {
        val capture = captures.remove(owner) ?: return false
        discard(capture)
        return true
    }

    private fun clear() {
        captures.values.forEach(::discard)
        captures.clear()
    }

    private fun discard(capture: Capture) {
        for (column in capture.column until capture.ticketed) tickets(capture, column, add = false)
        // A running finish reads the data file; its completion deletes it.
        if (!capture.finishing) {
            capture.writer.close()
            deleteQuietly(capture.data)
        }
    }

    /** Called by [PlanNetwork.serverTick]. */
    fun tick(server: MinecraftServer) {
        if (captures.isEmpty()) return
        val reading = captures.values.count { !it.finishing }
        if (reading == 0) return
        val share = maxOf(1, ServerBuildConfig.current.plans.captureSectionsPerTick / reading)
        val cursor = captures.values.iterator()
        while (cursor.hasNext()) {
            val capture = cursor.next()
            if (capture.finishing) continue
            try {
                read(capture, share)
            } catch (e: IOException) {
                LOG.error("Cannot write a plan capture for {}", capture.owner, e)
                cursor.remove()
                discard(capture)
                server.playerList.getPlayer(capture.owner)?.let { tell(it, "capture_failed") }
                continue
            }
            val player = server.playerList.getPlayer(capture.owner) ?: continue
            if (capture.column == capture.columns) finish(server, capture) else progress(player, capture)
        }
    }

    private fun read(capture: Capture, budget: Int) {
        var left = budget
        while (left > 0 && capture.column < capture.columns) {
            while (capture.ticketed < capture.columns && capture.ticketed < capture.column + LOOKAHEAD) {
                tickets(capture, capture.ticketed++, add = true)
            }
            if (!loadColumn(capture)) return
            readSection(capture)
            left--
            if (++capture.sy == capture.grid.y) {
                tickets(capture, capture.column, add = false)
                capture.column++
                capture.sy = 0
            }
        }
    }

    /** Adds or removes the tickets of the at most 2x2 chunks under one column. */
    private fun tickets(capture: Capture, column: Int, add: Boolean) {
        val chunks = capture.level.chunkSource
        val value = (capture.serial shl Int.SIZE_BITS) or column.toLong()
        val x0 = capture.min.x + column % capture.grid.x * PLAN_SECTION_EDGE
        val z0 = capture.min.z + column / capture.grid.x * PLAN_SECTION_EDGE
        val x1 = minOf(x0 + PLAN_SECTION_EDGE, capture.min.x + capture.size.x) - 1
        val z1 = minOf(z0 + PLAN_SECTION_EDGE, capture.min.z + capture.size.z) - 1
        for (cx in (x0 shr 4)..(x1 shr 4)) {
            for (cz in (z0 shr 4)..(z1 shr 4)) {
                val pos = ChunkPos(cx, cz)
                if (add) chunks.addRegionTicket(TICKET, pos, 0, value) else chunks.removeRegionTicket(TICKET, pos, 0, value)
            }
        }
    }

    /** Fills [Capture.chunks] for the current column; false while one of its chunks still loads. */
    private fun loadColumn(capture: Capture): Boolean {
        val source = capture.level.chunkSource
        val x0 = capture.min.x + capture.column % capture.grid.x * PLAN_SECTION_EDGE
        val z0 = capture.min.z + capture.column / capture.grid.x * PLAN_SECTION_EDGE
        val x1 = minOf(x0 + PLAN_SECTION_EDGE, capture.min.x + capture.size.x) - 1
        val z1 = minOf(z0 + PLAN_SECTION_EDGE, capture.min.z + capture.size.z) - 1
        capture.chunkX = x0 shr 4
        capture.chunkZ = z0 shr 4
        capture.chunks.fill(null)
        for (cx in capture.chunkX..(x1 shr 4)) {
            for (cz in capture.chunkZ..(z1 shr 4)) {
                capture.chunks[cx - capture.chunkX + 2 * (cz - capture.chunkZ)] = source.getChunkNow(cx, cz) ?: return false
            }
        }
        return true
    }

    private fun readSection(capture: Capture) {
        val sx = capture.column % capture.grid.x
        val sz = capture.column / capture.grid.x
        val sy = capture.sy
        val size = capture.size
        val cells = capture.cells
        val cursor = capture.cursor
        var last: BlockState? = null
        var lastCode = 0
        for (cell in 0 until PLAN_SECTION_CELLS) {
            val px = sx * PLAN_SECTION_EDGE + (cell and 15)
            val py = sy * PLAN_SECTION_EDGE + (cell ushr 8)
            val pz = sz * PLAN_SECTION_EDGE + (cell ushr 4 and 15)
            if (px >= size.x || py >= size.y || pz >= size.z) {
                cells[cell] = PLAN_KEEP_ID
                continue
            }
            cursor.set(capture.min.x + px, capture.min.y + py, capture.min.z + pz)
            val chunk = capture.chunks[(cursor.x shr 4) - capture.chunkX + 2 * ((cursor.z shr 4) - capture.chunkZ)]
            val state = checkNotNull(chunk) { "capture column chunk is missing" }.getBlockState(cursor)
            if (state !== last) {
                last = state
                lastCode = capture.codes.getOrPut(state) { code(capture, state) }
            }
            cells[cell] = lastCode ushr 1
            if (lastCode and 1 != 0) capture.strippedData++
        }
        capture.writer.add(capture.grid.index(sx, sy, sz), cells)
    }

    /** The same palette rules as an upload: structure void keeps, admin blocks go for non-ops. */
    private fun code(capture: Capture, state: BlockState): Int {
        val mapped = capture.sanitizer.map(NbtUtils.writeBlockState(state), SharedConstants.getCurrentVersion().dataVersion.version)
        val id = if (mapped == null) PLAN_AIR_ID else capture.writer.idOf(mapped)
        // Plans keep block states only, so any block entity data is lost.
        val data = id > PLAN_KEEP_ID && state.hasBlockEntity()
        return (id shl 1) or (if (data) 1 else 0)
    }

    private fun progress(player: ServerPlayer, capture: Capture) {
        val done = capture.column.toLong() * capture.grid.y + capture.sy
        val percent = (done * PERCENT / capture.grid.count).toInt()
        if (percent == capture.percent) return
        capture.percent = percent
        tell(player, "capture_progress", capture.name, percent)
    }

    private fun finish(server: MinecraftServer, capture: Capture) {
        capture.finishing = true
        val target = capture.data.resolveSibling(capture.data.fileName.toString() + ".plan")
        CompletableFuture.supplyAsync({ capture.writer.finish(target) }, Util.backgroundExecutor())
            .whenComplete { hash, error ->
                deleteQuietly(capture.data)
                server.execute { complete(server, capture, target, hash, error) }
            }
    }

    private fun complete(server: MinecraftServer, capture: Capture, target: Path, hash: String?, error: Throwable?) {
        if (captures[capture.owner] !== capture) {
            deleteQuietly(target)
            return
        }
        captures.remove(capture.owner)
        val player = server.playerList.getPlayer(capture.owner)
        if (hash == null || error != null) {
            LOG.error("Cannot finish a plan capture for {}", capture.owner, error)
            deleteQuietly(target)
            player?.let { tell(it, "capture_failed") }
            return
        }
        val issues = ArrayList(capture.sanitizer.issues)
        if (capture.strippedData > 0) issues += PlanIssue(PLAN_KEY + "stripped_block_data", listOf(capture.strippedData.toString()))
        val check = PlanCheck(capture.size, capture.writer.nonAir, issues)
        val stored = PlanStorage.store(server, capture.owner, hash, capture.name, check, target)
        deleteQuietly(target)
        stored.ref?.let { capture.onStored(player, it) }
        if (player == null) return
        when (stored) {
            is PlanStorage.Store.Stored -> tell(player, "capture_saved", stored.ref.name)
            is PlanStorage.Store.Duplicate -> tell(player, "capture_same", stored.ref.name)
            PlanStorage.Store.Full -> tell(player, "capture_full", PlanLimits.QUOTA)
            PlanStorage.Store.Failed -> tell(player, "capture_failed")
        }
        if (stored is PlanStorage.Store.Stored) {
            for (issue in issues.take(PlanUploadResult.MAX_ISSUES)) player.sendSystemMessage(issue.toComponent())
        }
        PlanNetwork.sendList(player)
    }

    private fun tell(player: ServerPlayer, key: String, vararg args: Any) {
        player.displayClientMessage(Component.translatable(KEY + key, *args), true)
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            LOG.warn("Cannot delete plan capture file {}", path, e)
        }
    }
}
