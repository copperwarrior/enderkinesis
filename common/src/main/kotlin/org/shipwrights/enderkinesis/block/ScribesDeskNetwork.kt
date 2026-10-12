package org.shipwrights.enderkinesis.block

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.blockentity.ScribesDeskBlockEntity
import org.shipwrights.enderkinesis.item.EideticBookmarkItem
import org.shipwrights.enderkinesis.sselith.BookshelfPage

/**
 *  - [OPEN] (S2C): a loaded desk was used; open the writing screen for it.
 *  - [INSCRIBE] (C2S): the text to write. The server checks everything again before it spends materials.
 */
object ScribesDeskNetwork {

    val OPEN: ResourceLocation = EnderkinesisMod.id("scribes_desk/open")
    val INSCRIBE: ResourceLocation = EnderkinesisMod.id("scribes_desk/inscribe")
    private const val MAX_DESK_DISTANCE_SQ = 8.0 * 8.0

    const val MAX_TEXT_LENGTH = 1_024
    const val INSCRIBED_KEY = "message.enderkinesis.scribes_desk.inscribed"

    private val WHITESPACE = Regex("\\s+")

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, INSCRIBE) { buf, ctx ->
            val pos = buf.readBlockPos()
            val text = buf.readUtf(MAX_TEXT_LENGTH)
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue { inscribe(player, pos, text) }
        }
    }

    fun open(player: ServerPlayer, pos: BlockPos) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeBlockPos(pos)
        NetworkManager.sendToPlayer(player, OPEN, buf)
    }

    fun sendInscribe(pos: BlockPos, text: String) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeBlockPos(pos)
        buf.writeUtf(text, MAX_TEXT_LENGTH)
        NetworkManager.sendToServer(INSCRIBE, buf)
    }

    fun words(text: String): List<String> = text.lowercase().split(WHITESPACE).filter { it.isNotEmpty() }

    /** Text the desk may write: 1 to [BookshelfPage.WORDS] words, all from one language. */
    fun isWritable(words: List<String>): Boolean =
        words.isNotEmpty() && words.size <= BookshelfPage.WORDS && BookshelfPage.languageOf(words) != null

    private fun inscribe(player: ServerPlayer, pos: BlockPos, text: String) {
        // The position is from the client. A block entity read at a far position loads the chunk.
        if (player.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) > MAX_DESK_DISTANCE_SQ) return
        val desk = player.level().getBlockEntity(pos) as? ScribesDeskBlockEntity ?: return
        if (!desk.stillValid(player) || !desk.isReady) return
        val words = words(text)
        if (!isWritable(words)) return
        desk.consumeForInscription()
        val bookmark = EideticBookmarkItem.inscribed(words.joinToString(" "))
        if (!player.inventory.add(bookmark)) player.drop(bookmark, false)
        player.displayClientMessage(Component.translatable(INSCRIBED_KEY), true)
    }
}
