package org.shipwrights.enderkinesis.item

import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKSounds
import java.util.UUID

/** [PICTURE] (C2S): a view sketch, in chunks under the vanilla payload limit. The server makes the paper. */
object SilverpointNetwork {

    val PICTURE: ResourceLocation = EnderkinesisMod.id("silverpoint/picture")
    private const val CHUNK = 30_000
    private const val MAX_BYTES = 192 * 1024
    private const val MAX_CHUNKS = (MAX_BYTES + CHUNK - 1) / CHUNK
    private const val MIN_TICKS_BETWEEN = 10

    private class Upload(val chunks: Array<ByteArray?>)

    // Server thread only.
    private val uploads = HashMap<UUID, Upload>()
    private val lastPicture = HashMap<UUID, Long>()

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, PICTURE) { buf, ctx ->
            val index = buf.readVarInt()
            val total = buf.readVarInt()
            val bytes = buf.readByteArray(CHUNK)
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { receive(player, index, total, bytes) }
        }
        PlayerEvent.PLAYER_QUIT.register { player ->
            uploads.remove(player.uuid)
            lastPicture.remove(player.uuid)
        }
    }

    fun send(packed: ByteArray) {
        if (packed.size > MAX_BYTES) return
        val total = (packed.size + CHUNK - 1) / CHUNK
        for (index in 0 until total) {
            val buf = FriendlyByteBuf(Unpooled.buffer())
            buf.writeVarInt(index)
            buf.writeVarInt(total)
            buf.writeByteArray(packed.copyOfRange(index * CHUNK, minOf(packed.size, (index + 1) * CHUNK)))
            NetworkManager.sendToServer(PICTURE, buf)
        }
    }

    private fun receive(player: ServerPlayer, index: Int, total: Int, bytes: ByteArray) {
        val holds = player.mainHandItem.item is SilverpointItem || player.offhandItem.item is SilverpointItem
        if (total !in 1..MAX_CHUNKS || index !in 0 until total || !holds) {
            uploads.remove(player.uuid)
            return
        }
        val upload = uploads[player.uuid]?.takeIf { it.chunks.size == total }
            ?: Upload(arrayOfNulls(total)).also { uploads[player.uuid] = it }
        upload.chunks[index] = bytes
        if (upload.chunks.any { it == null }) return
        uploads.remove(player.uuid)

        val now = player.serverLevel().gameTime
        val last = lastPicture[player.uuid]
        if (last != null && now - last in 0 until MIN_TICKS_BETWEEN) return
        val packed = upload.chunks.fold(ByteArray(0)) { all, chunk -> all + checkNotNull(chunk) }
        if (SilverpointPicture.sideOf(packed) == null) return
        lastPicture[player.uuid] = now
        SilverpointItem.give(player, PictureReadout(player.gameProfile.name, packed))
        player.level().playSound(null, player.x, player.y, player.z, EKSounds.SILVERPOINT_TAKE.get(), SoundSource.PLAYERS, 1f, 1f)
    }
}
