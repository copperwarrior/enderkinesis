package org.shipwrights.enderkinesis.vine

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.SwordItem
import org.shipwrights.enderkinesis.EnderkinesisMod

/** C2S: a sword click aimed at a vine. The server picks the vine again, so reach and line of sight hold. */
object VineCutNetwork {
    val SWORD_CUT: ResourceLocation = EnderkinesisMod.id("woggak_vine/sword_cut")
    /** C2S: the client's drawn-rope pick, sent just before a use, sword cut or reel; the server checks it in pickWoggakVine. */
    val AIM: ResourceLocation = EnderkinesisMod.id("woggak_vine/aim")

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, AIM) { buf, ctx ->
            val hit = buf.readBoolean()
            val chain = if (hit) buf.readLong() else 0L
            val link = if (hit) buf.readVarInt() else 0
            val along = if (hit) buf.readDouble() else 0.0
            ctx.queue { (ctx.player as? ServerPlayer)?.takeVineAim(hit, chain, link, along) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SWORD_CUT) { _, ctx ->
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue {
                if (player.isSpectator || !player.isAlive) return@queue
                if (player.mainHandItem.item !is SwordItem) return@queue
                cutWoggakVine(player, InteractionHand.MAIN_HAND)
            }
        }
    }

    fun sendCut() = NetworkManager.sendToServer(SWORD_CUT, FriendlyByteBuf(Unpooled.buffer()))

    /** Sends the pick at [along] of link [link] of vine [chain], or no vine when [chain] is null. */
    fun sendAim(chain: Long?, link: Int = 0, along: Double = 0.0) =
        NetworkManager.sendToServer(AIM, FriendlyByteBuf(Unpooled.buffer()).apply {
            writeBoolean(chain != null)
            if (chain != null) {
                writeLong(chain)
                writeVarInt(link)
                writeDouble(along)
            }
        })
}
