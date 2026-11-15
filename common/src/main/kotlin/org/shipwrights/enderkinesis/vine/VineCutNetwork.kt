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

    fun init() {
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
}
