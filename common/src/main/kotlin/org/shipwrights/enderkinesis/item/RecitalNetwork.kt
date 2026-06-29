package org.shipwrights.enderkinesis.item

import dev.architectury.networking.NetworkManager
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKItems

/** Network packet for the Staff of Recital. The client owns the input
 *  (shift+scroll) but the server has to mutate the staff stack's NBT so the
 *  selected tome's logic dispatches correctly server-side.
 *
 *  Only one direction: C2S "set active index by delta." Server clamps and
 *  writes the NBT back to whichever hand holds the staff. */
object RecitalNetwork {

    val SET_ACTIVE: ResourceLocation = EnderkinesisMod.id("staff_of_recital/set_active")

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, SET_ACTIVE) { buf, ctx ->
            val delta = buf.readInt()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue {
                // Find a recital staff in either hand and cycle its active index.
                for (hand in arrayOf(InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND)) {
                    val held = player.getItemInHand(hand)
                    if (held.item != EKItems.STAFF_OF_RECITAL.get()) continue
                    val tomes = RecitalHelper.readTomes(held)
                    if (tomes.isEmpty()) continue
                    val current = RecitalHelper.getActiveIndex(held)
                    // Modulo cycle so scrolling past the ends wraps cleanly.
                    val next = (((current + delta) % tomes.size) + tomes.size) % tomes.size
                    RecitalHelper.setActiveIndex(held, next)
                    break
                }
            }
        }
    }
}
