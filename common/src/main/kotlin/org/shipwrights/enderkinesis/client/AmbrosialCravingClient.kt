package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.world.InteractionHand
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.registry.EKItems

/**
 * Two periodic effect-gated behaviours: hotbar drift (selected slot snaps to a Wey'ye
 * fruit if any is in the hotbar) and forced bite (poke local game mode's `useItem` when
 * mainhand is fruit and the player isn't already using).
 *
 * Hotbar drift sends a [ServerboundSetCarriedItemPacket] so the server sees the same slot
 * the client did — without it, the next server-driven state read snaps the slot back.
 */
object AmbrosialCravingClient {

    /** ~10 seconds between hotbar drifts. Frequent enough to feel
     *  compulsive, slow enough that the player still gets a window to
     *  intentionally swap slots between drifts. */
    private const val SCROLL_INTERVAL_TICKS: Int = 200

    /** ~5 seconds between forced bites. Aligned with the scroll interval
     *  (½×) so a drift roughly always lands a bite at the next forced
     *  interval. */
    private const val FORCE_EAT_INTERVAL_TICKS: Int = 100

    private var tickCounter: Int = 0

    @JvmStatic
    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { _ ->
            val mc = Minecraft.getInstance()
            val player = mc.player ?: return@ClientLevel
            if (!player.hasEffect(EKEffects.ambrosialCravingEffect())) {
                // Keep the counter rolling so a fresh affliction picks
                // up at a sensible phase — but no behaviour fires.
                tickCounter++
                return@ClientLevel
            }
            tickCounter++
            if (tickCounter % SCROLL_INTERVAL_TICKS == 0) {
                scrollToWeyyeFruit(player)
            }
            if (tickCounter % FORCE_EAT_INTERVAL_TICKS == 0) {
                forceEatIfHoldingFruit(mc, player)
            }
        })
    }

    /** Find the lowest-index hotbar slot holding a Wey'ye fruit and
     *  select it (sending the carried-item packet so the server agrees).
     *  No-op if there isn't one. */
    private fun scrollToWeyyeFruit(player: LocalPlayer) {
        val fruit = EKItems.weyyeFruit()
        val inv = player.inventory
        for (slot in 0..8) {
            if (inv.getItem(slot).item === fruit) {
                if (inv.selected != slot) {
                    inv.selected = slot
                    player.connection.send(ServerboundSetCarriedItemPacket(slot))
                }
                return
            }
        }
    }

    /** If the local player has a Wey'ye fruit selected and isn't already
     *  using an item, trigger a `useItem` on the main hand. Vanilla then
     *  starts the eating animation and ticks it to completion on its
     *  own — no per-tick "keep using" logic needed. */
    private fun forceEatIfHoldingFruit(mc: Minecraft, player: LocalPlayer) {
        if (player.isUsingItem) return
        if (player.mainHandItem.item !== EKItems.weyyeFruit()) return
        mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
    }
}
