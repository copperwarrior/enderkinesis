package org.shipwrights.enderkinesis.construction

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.EnderkinesisMod
import java.io.IOException
import java.util.UUID

/**
 * Plan packets. Clients only propose: the server checks every upload, fetch, list and delete again.
 * A client fetches a plan's header once, then single sections by index; the server sends at most
 * [PlanLimits.sendBytesPerTick] per player per tick (and at least one packet).
 */
object PlanNetwork {
    private val LOG = LogUtils.getLogger()

    val UPLOAD_BEGIN: ResourceLocation = EnderkinesisMod.id("plan/upload_begin")
    val UPLOAD_CHUNK: ResourceLocation = EnderkinesisMod.id("plan/upload_chunk")
    val FETCH: ResourceLocation = EnderkinesisMod.id("plan/fetch")
    val FETCH_SECTIONS: ResourceLocation = EnderkinesisMod.id("plan/fetch_sections")
    val LIST: ResourceLocation = EnderkinesisMod.id("plan/list")
    val DELETE: ResourceLocation = EnderkinesisMod.id("plan/delete")

    val UPLOAD_RESULT: ResourceLocation = EnderkinesisMod.id("plan/upload_result")
    val UPLOAD_ACK: ResourceLocation = EnderkinesisMod.id("plan/upload_ack")
    val HEADER: ResourceLocation = EnderkinesisMod.id("plan/header")
    val SECTION: ResourceLocation = EnderkinesisMod.id("plan/section")
    val MISSING: ResourceLocation = EnderkinesisMod.id("plan/missing")

    /** S2C: the header queue is full; the client asks again when the last part of a queued header arrives. */
    val BUSY: ResourceLocation = EnderkinesisMod.id("plan/busy")
    val LIST_RESULT: ResourceLocation = EnderkinesisMod.id("plan/list_result")

    /** S2C on join: the upload packet size, window and largest file the server takes. */
    val UPLOAD_LIMITS: ResourceLocation = EnderkinesisMod.id("plan/upload_limits")

    const val MAX_FILE_NAME = 128

    /** Plans in one list packet. A protocol cap, not the quota, so a server with a larger quota still talks to a client. */
    const val MAX_LISTED_PLANS = 256

    /** Section indices in one fetch packet, and queued per player; the client keeps fewer in flight. */
    const val MAX_FETCH_SECTIONS = 64
    private const val MAX_QUEUED_SECTIONS = 256
    private const val MAX_QUEUED_HEADERS = 8
    private const val HEADER_PART = PlanUploadStream.CHUNK

    private sealed interface Item {
        val ref: PlanRef

        class HeaderPart(override val ref: PlanRef, val index: Int, val total: Int, val bytes: ByteArray) : Item
        class Section(override val ref: PlanRef, val index: Int) : Item
    }

    private class Outbox {
        val items = ArrayDeque<Item>()
        val headers = HashSet<PlanRef>()
        var sections = 0
    }

    /** The last file one drain run looked up. */
    private class DrainCache {
        var ref: PlanRef? = null
        var file: PlanSectionFile? = null
    }

    // Server thread only.
    private val outboxes = HashMap<UUID, Outbox>()

    fun init() {
        ServerBuildConfig.init()
        PlanStorage.init()
        PlanCaptures.init()
        registerReceivers()
        PlayerEvent.PLAYER_JOIN.register(::sendUploadLimits)
        PlayerEvent.PLAYER_QUIT.register { player ->
            PlanUploads.cancel(player.uuid)
            outboxes.remove(player.uuid)
        }
        TickEvent.SERVER_POST.register(::serverTick)
        LifecycleEvent.SERVER_STOPPED.register {
            PlanUploads.clear()
            outboxes.clear()
        }
    }

    /** The budgeted plan work of one server tick. A paused integrated server skips SERVER_POST; see [ClientPlans]. */
    fun serverTick(server: MinecraftServer) {
        PlanUploads.tick(server)
        drain(server)
        PlanCaptures.tick(server)
    }

    private fun registerReceivers() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, UPLOAD_BEGIN) { buf, ctx ->
            val fileName = buf.readUtf(MAX_FILE_NAME)
            val size = buf.readVarLong()
            val hash = buf.readUtf(PlanRef.HASH_LENGTH)
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { PlanUploads.begin(player, fileName, size, hash) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, UPLOAD_CHUNK) { buf, ctx ->
            val sequence = buf.readVarInt()
            val bytes = buf.readByteArray(PlanUploadStream.CHUNK)
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { PlanUploads.chunk(player, sequence, bytes) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, FETCH) { buf, ctx ->
            val owner = buf.readUUID()
            val hash = buf.readUtf(PlanRef.HASH_LENGTH)
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { fetchHeader(player, owner, hash) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, FETCH_SECTIONS) { buf, ctx ->
            val owner = buf.readUUID()
            val hash = buf.readUtf(PlanRef.HASH_LENGTH)
            val count = buf.readVarInt()
            if (count !in 1..MAX_FETCH_SECTIONS) return@registerReceiver
            val indices = IntArray(count) { buf.readVarInt() }
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { fetchSections(player, owner, hash, indices) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, LIST) { _, ctx ->
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { sendList(player) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, DELETE) { buf, ctx ->
            val hash = buf.readUtf(PlanRef.HASH_LENGTH)
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue {
                // A table or job that holds the plan drops it by itself when it cannot read the file.
                if (PlanRef.isHash(hash)) PlanStorage.delete(player.server, player.uuid, hash)
                sendList(player)
            }
        }
    }

    fun sendResult(player: ServerPlayer, result: PlanUploadResult) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writePlanUploadResult(result)
        NetworkManager.sendToPlayer(player, UPLOAD_RESULT, buf)
        if (result.accepted) sendList(player)
    }

    private fun sendUploadLimits(player: ServerPlayer) {
        val limits = ServerBuildConfig.current.plans
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(limits.uploadPacketBytes)
        buf.writeVarInt(limits.uploadWindow)
        buf.writeVarLong(limits.maxFileBytes)
        NetworkManager.sendToPlayer(player, UPLOAD_LIMITS, buf)
    }

    /** Reads an [UPLOAD_LIMITS] packet, or null when a value is out of the protocol range. */
    fun readUploadLimits(buf: FriendlyByteBuf): ClientUploadLimits? {
        val packet = buf.readVarInt()
        val window = buf.readVarInt()
        val maxFileBytes = buf.readVarLong()
        return if (packet in PlanLimits.MIN_PACKET..PlanUploadStream.CHUNK && window > 0 && maxFileBytes > 0) {
            ClientUploadLimits(packet, window, maxFileBytes)
        } else {
            null
        }
    }

    fun sendUploadAck(player: ServerPlayer, chunksWritten: Int) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(chunksWritten)
        NetworkManager.sendToPlayer(player, UPLOAD_ACK, buf)
    }

    fun sendList(player: ServerPlayer) {
        val plans = PlanStorage.list(player.server, player.uuid).take(MAX_LISTED_PLANS)
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(plans.size)
        for (plan in plans) buf.writePlanRef(plan)
        NetworkManager.sendToPlayer(player, LIST_RESULT, buf)
    }

    private fun sendMissing(player: ServerPlayer, owner: UUID, hash: String) = sendPlanKey(player, MISSING, owner, hash)

    private fun sendPlanKey(player: ServerPlayer, id: ResourceLocation, owner: UUID, hash: String) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUUID(owner)
        buf.writeUtf(hash.take(PlanRef.HASH_LENGTH), PlanRef.HASH_LENGTH)
        NetworkManager.sendToPlayer(player, id, buf)
    }

    private fun stored(player: ServerPlayer, owner: UUID, hash: String): PlanRef? =
        if (PlanRef.isHash(hash)) PlanStorage.find(player.server, owner, hash) else null

    /** A fetch names the owner and hash; knowing both is enough, so a shared staff can show another player's plan. */
    private fun fetchHeader(player: ServerPlayer, owner: UUID, hash: String) {
        val ref = stored(player, owner, hash) ?: return sendMissing(player, owner, hash)
        val outbox = outboxes.getOrPut(player.uuid) { Outbox() }
        // The queued copy answers a repeat; a full queue must answer too, or the client waits forever.
        if (ref in outbox.headers) return
        if (outbox.headers.size >= MAX_QUEUED_HEADERS) return sendPlanKey(player, BUSY, owner, hash)
        val bytes = try {
            PlanStorage.file(player.server, ref)?.headerBytes()
        } catch (e: IOException) {
            LOG.error("Cannot read the header of plan {}/{}", owner, hash, e)
            null
        } ?: return sendMissing(player, owner, hash)
        outbox.headers.add(ref)
        val total = (bytes.size + HEADER_PART - 1) / HEADER_PART
        for (index in 0 until total) {
            val part = bytes.copyOfRange(index * HEADER_PART, minOf(bytes.size, (index + 1) * HEADER_PART))
            outbox.items.addLast(Item.HeaderPart(ref, index, total, part))
        }
    }

    private fun fetchSections(player: ServerPlayer, owner: UUID, hash: String, indices: IntArray) {
        val ref = stored(player, owner, hash) ?: return sendMissing(player, owner, hash)
        val outbox = outboxes.getOrPut(player.uuid) { Outbox() }
        for (index in indices) {
            if (outbox.sections >= MAX_QUEUED_SECTIONS) return
            outbox.items.addLast(Item.Section(ref, index))
            outbox.sections++
        }
    }

    private fun drain(server: MinecraftServer) {
        if (outboxes.isEmpty()) return
        val sendBytesPerTick = ServerBuildConfig.current.plans.sendBytesPerTick
        val cache = DrainCache()
        for ((uuid, outbox) in outboxes) {
            val player = server.playerList.getPlayer(uuid) ?: continue
            var budget = sendBytesPerTick
            while (outbox.items.isNotEmpty() && budget > 0) {
                budget -= send(player, outbox, outbox.items.removeFirst(), cache)
            }
        }
    }

    /** Sends one item; returns its byte count. */
    private fun send(player: ServerPlayer, outbox: Outbox, item: Item, cache: DrainCache): Int {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writePlanRef(item.ref)
        return when (item) {
            is Item.HeaderPart -> {
                buf.writeVarInt(item.index)
                buf.writeVarInt(item.total)
                buf.writeByteArray(item.bytes)
                NetworkManager.sendToPlayer(player, HEADER, buf)
                if (item.index == item.total - 1) outbox.headers.remove(item.ref)
                item.bytes.size
            }
            is Item.Section -> {
                outbox.sections--
                // An empty payload answers a bad or empty index, so the client stops waiting for it.
                val payload = sectionPayload(player.server, item, cache)
                buf.writeVarInt(item.index)
                buf.writeByteArray(payload)
                NetworkManager.sendToPlayer(player, SECTION, buf)
                payload.size
            }
        }
    }

    private fun drainFile(server: MinecraftServer, ref: PlanRef, cache: DrainCache): PlanSectionFile? {
        if (ref != cache.ref) {
            cache.ref = ref
            cache.file = PlanStorage.file(server, ref)
        }
        return cache.file
    }

    private fun sectionPayload(server: MinecraftServer, item: Item.Section, cache: DrainCache): ByteArray {
        val file = drainFile(server, item.ref, cache) ?: return EMPTY
        if (item.index !in 0 until file.header.grid.count) return EMPTY
        return try {
            file.payload(item.index) ?: EMPTY
        } catch (e: IOException) {
            LOG.error("Cannot read section {} of plan {}", item.index, item.ref.hash, e)
            EMPTY
        }
    }

    private val EMPTY = ByteArray(0)

    class HeaderPart(val ref: PlanRef, val index: Int, val total: Int, val bytes: ByteArray)

    /** Header parts above this make a header past 2 GB: a broken server. */
    private const val MAX_HEADER_PARTS = Int.MAX_VALUE / HEADER_PART

    /** Reads one [HEADER] part, or null when it is not valid. */
    fun readHeaderPart(buf: FriendlyByteBuf): HeaderPart? {
        val ref = buf.readPlanRef()
        val index = buf.readVarInt()
        val total = buf.readVarInt()
        val bytes = buf.readByteArray(HEADER_PART)
        return if (ref != null && total in 1..MAX_HEADER_PARTS && index in 0 until total) HeaderPart(ref, index, total, bytes) else null
    }

    class SectionPart(val ref: PlanRef, val index: Int, val payload: ByteArray)

    fun readSection(buf: FriendlyByteBuf): SectionPart? {
        val ref = buf.readPlanRef()
        val index = buf.readVarInt()
        val payload = buf.readByteArray(PlanSectionFile.MAX_PAYLOAD)
        return ref?.let { SectionPart(it, index, payload) }
    }

    fun sendUploadBegin(fileName: String, size: Long, hash: String) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUtf(fileName.take(MAX_FILE_NAME), MAX_FILE_NAME)
        buf.writeVarLong(size)
        buf.writeUtf(hash, PlanRef.HASH_LENGTH)
        NetworkManager.sendToServer(UPLOAD_BEGIN, buf)
    }

    fun sendUploadChunk(sequence: Int, bytes: ByteArray) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(sequence)
        buf.writeByteArray(bytes)
        NetworkManager.sendToServer(UPLOAD_CHUNK, buf)
    }

    fun sendFetch(ref: PlanRef) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUUID(ref.owner)
        buf.writeUtf(ref.hash, PlanRef.HASH_LENGTH)
        NetworkManager.sendToServer(FETCH, buf)
    }

    fun sendFetchSections(ref: PlanRef, indices: IntArray, count: Int) {
        require(count in 1..MAX_FETCH_SECTIONS) { "bad section fetch count $count" }
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUUID(ref.owner)
        buf.writeUtf(ref.hash, PlanRef.HASH_LENGTH)
        buf.writeVarInt(count)
        for (i in 0 until count) buf.writeVarInt(indices[i])
        NetworkManager.sendToServer(FETCH_SECTIONS, buf)
    }

    fun sendListRequest() = NetworkManager.sendToServer(LIST, FriendlyByteBuf(Unpooled.buffer()))

    fun sendDelete(ref: PlanRef) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUtf(ref.hash, PlanRef.HASH_LENGTH)
        NetworkManager.sendToServer(DELETE, buf)
    }
}
