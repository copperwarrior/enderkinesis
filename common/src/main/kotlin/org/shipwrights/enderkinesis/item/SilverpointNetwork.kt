package org.shipwrights.enderkinesis.item

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKSounds
import java.util.UUID

/**
 * The viewfinder's picture flow. On release the client sends [BEGIN] with a new id, and the server gives the paper at
 * once. The client bakes the picture and sends it in [PICTURE] chunks under the vanilla payload limit; the server keeps
 * it in [SilverpointPictureStore]. Clients get pictures they do not have with [FETCH], answered by [DATA].
 */
object SilverpointNetwork {

    val BEGIN: ResourceLocation = EnderkinesisMod.id("silverpoint/begin")
    val PICTURE: ResourceLocation = EnderkinesisMod.id("silverpoint/picture")
    val FETCH: ResourceLocation = EnderkinesisMod.id("silverpoint/fetch")
    val DATA: ResourceLocation = EnderkinesisMod.id("silverpoint/data")

    private const val ID_LENGTH = 16
    private const val CHUNK = 30_000
    // A colour picture is two bytes a pixel before deflate, so it needs more room than a grey one.
    private const val MAX_BYTES = 384 * 1024
    private const val MAX_CHUNKS = (MAX_BYTES + CHUNK - 1) / CHUNK

    // Each viewfinder release allows one paper; the item cooldown spaces releases.
    private const val ALLOW_TICKS = 600L
    private const val MAX_ALLOWED = 2

    // A paper's picture must arrive within this time of its paper; the client bakes it in a few frames.
    private const val UPLOAD_TICKS = 1200L
    // Pictures go out in CHUNK parts through a per-player queue, at most this many bytes a tick (about 640 KB/s).
    private const val SEND_BYTES_PER_TICK = 32 * 1024
    // A picture sent to a player this recently is not sent again; the client asks again after 5 s.
    private const val RESEND_TICKS = 80L
    private const val MAX_QUEUED_PICTURES = 32

    private class Allowance(var count: Int, var expires: Long)

    private class Upload(val owner: UUID, val expires: Long) {
        var chunks: Array<ByteArray?>? = null
    }

    private class Part(val id: String, val index: Int, val total: Int, val bytes: ByteArray)

    private class Outbox {
        val parts = ArrayDeque<Part>()
        val queued = HashSet<String>()
        val sentAt = HashMap<String, Long>()
    }

    // Server thread only.
    private val allowances = HashMap<UUID, Allowance>()
    private val uploads = HashMap<String, Upload>()
    private val waiting = HashMap<String, MutableSet<UUID>>()
    private val outboxes = HashMap<UUID, Outbox>()

    fun init() {
        SilverpointPictureStore.init()
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, BEGIN) { buf, ctx ->
            val id = buf.readUtf(ID_LENGTH)
            val colour = buf.readBoolean()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { begin(player, id, colour) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, PICTURE) { buf, ctx ->
            val id = buf.readUtf(ID_LENGTH)
            val index = buf.readVarInt()
            val total = buf.readVarInt()
            val bytes = buf.readByteArray(CHUNK)
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { receive(player, id, index, total, bytes) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, FETCH) { buf, ctx ->
            val id = buf.readUtf(ID_LENGTH)
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { fetch(player, id) }
        }
        PlayerEvent.PLAYER_QUIT.register { player ->
            allowances.remove(player.uuid)
            outboxes.remove(player.uuid)
        }
        TickEvent.SERVER_POST.register { server -> drain(server) }
        LifecycleEvent.SERVER_STOPPED.register { outboxes.clear() }
    }

    /** A viewfinder release on the server: the paper it begins may come in the next [ALLOW_TICKS]. */
    fun allow(player: ServerPlayer) {
        val now = player.serverLevel().gameTime
        val held = allowances[player.uuid]?.takeIf { it.expires >= now }?.count ?: 0
        allowances[player.uuid] = Allowance(minOf(MAX_ALLOWED, held + 1), now + ALLOW_TICKS)
    }

    fun sendBegin(id: String, colour: Boolean) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUtf(id, ID_LENGTH)
        buf.writeBoolean(colour)
        NetworkManager.sendToServer(BEGIN, buf)
    }

    fun sendPicture(id: String, packed: ByteArray) {
        if (packed.size > MAX_BYTES) return
        val total = (packed.size + CHUNK - 1) / CHUNK
        for (index in 0 until total) {
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeUtf(id, ID_LENGTH)
            buf.writeVarInt(index)
            buf.writeVarInt(total)
            buf.writeByteArray(packed.copyOfRange(index * CHUNK, minOf(packed.size, (index + 1) * CHUNK)))
            NetworkManager.sendToServer(PICTURE, buf)
        }
    }

    fun sendFetch(id: String) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUtf(id, ID_LENGTH)
        NetworkManager.sendToServer(FETCH, buf)
    }

    private fun begin(player: ServerPlayer, id: String, colour: Boolean) {
        val now = player.serverLevel().gameTime
        uploads.values.removeIf { it.expires < now }
        waiting.keys.removeIf { it !in uploads }
        val allowance = allowances[player.uuid]?.takeIf { it.expires >= now && it.count > 0 } ?: return
        if (!SilverpointPicture.isId(id) || id in uploads || SilverpointPictureStore.contains(id)) return
        if (--allowance.count == 0) allowances.remove(player.uuid)
        uploads[id] = Upload(player.uuid, now + UPLOAD_TICKS)
        SilverpointItem.give(player, PictureReadout.stored(player.gameProfile.name, id, colour))
        player.level().playSound(null, player.x, player.y, player.z, EKSounds.SILVERPOINT_TAKE.get(), SoundSource.PLAYERS, 1f, 1f)
    }

    private fun receive(player: ServerPlayer, id: String, index: Int, total: Int, bytes: ByteArray) {
        val upload = uploads[id]?.takeIf { it.owner == player.uuid } ?: return
        if (total !in 1..MAX_CHUNKS || index !in 0 until total) {
            uploads.remove(id)
            return
        }
        val chunks = upload.chunks?.takeIf { it.size == total } ?: arrayOfNulls<ByteArray>(total).also { upload.chunks = it }
        chunks[index] = bytes
        if (chunks.any { it == null }) return
        uploads.remove(id)
        val packed = join(chunks)
        if (SilverpointPicture.sideOf(packed) == null) return
        SilverpointPictureStore.put(id, packed)
        val players = player.server.playerList
        waiting.remove(id)?.forEach { uuid -> players.getPlayer(uuid)?.let { sendData(it, id, packed) } }
    }

    private fun fetch(player: ServerPlayer, id: String) {
        if (!SilverpointPicture.isId(id)) return
        val packed = SilverpointPictureStore.get(id)
        when {
            packed != null -> sendData(player, id, packed)
            id in uploads -> waiting.getOrPut(id) { HashSet() }.add(player.uuid)
        }
    }

    /** Queues [packed] for [player] in parts, unless it is queued already or was sent within [RESEND_TICKS]. */
    private fun sendData(player: ServerPlayer, id: String, packed: ByteArray) {
        val outbox = outboxes.getOrPut(player.uuid) { Outbox() }
        val now = player.serverLevel().gameTime
        outbox.sentAt.values.removeIf { now - it >= RESEND_TICKS }
        if (id in outbox.queued || id in outbox.sentAt || outbox.queued.size >= MAX_QUEUED_PICTURES) return
        outbox.queued.add(id)
        val total = (packed.size + CHUNK - 1) / CHUNK
        for (index in 0 until total) {
            outbox.parts.addLast(Part(id, index, total, packed.copyOfRange(index * CHUNK, minOf(packed.size, (index + 1) * CHUNK))))
        }
    }

    /** Sends each player's queued parts, up to [SEND_BYTES_PER_TICK] (at least one part) a tick. */
    private fun drain(server: MinecraftServer) {
        if (outboxes.isEmpty()) return
        for ((uuid, outbox) in outboxes) {
            if (outbox.parts.isEmpty()) continue
            val player = server.playerList.getPlayer(uuid) ?: continue
            var budget = SEND_BYTES_PER_TICK
            while (outbox.parts.isNotEmpty() && (budget == SEND_BYTES_PER_TICK || budget >= outbox.parts.first().bytes.size)) {
                val part = outbox.parts.removeFirst()
                budget -= part.bytes.size
                val buf = FriendlyByteBuf(Unpooled.buffer())
                buf.writeUtf(part.id, ID_LENGTH)
                buf.writeVarInt(part.index)
                buf.writeVarInt(part.total)
                buf.writeByteArray(part.bytes)
                NetworkManager.sendToPlayer(player, DATA, buf)
                if (part.index == part.total - 1) {
                    outbox.queued.remove(part.id)
                    outbox.sentAt[part.id] = player.serverLevel().gameTime
                }
            }
        }
    }

    class DataPart(val id: String, val index: Int, val total: Int, val bytes: ByteArray)

    /** Reads one [DATA] part: the id, the part index and count, and the bytes. */
    fun readData(buf: FriendlyByteBuf): DataPart? {
        val id = buf.readUtf(ID_LENGTH)
        val index = buf.readVarInt()
        val total = buf.readVarInt()
        val bytes = buf.readByteArray(CHUNK)
        return if (total in 1..MAX_CHUNKS && index in 0 until total) DataPart(id, index, total, bytes) else null
    }

    /** Joins the parts of one picture, in index order. */
    fun join(parts: Array<ByteArray?>): ByteArray {
        val out = ByteArray(parts.sumOf { checkNotNull(it).size })
        var at = 0
        for (part in parts) {
            checkNotNull(part).copyInto(out, at)
            at += part.size
        }
        return out
    }
}
