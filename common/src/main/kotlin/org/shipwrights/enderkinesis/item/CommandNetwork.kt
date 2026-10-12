package org.shipwrights.enderkinesis.item

import dev.architectury.networking.NetworkManager
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKItems

/** C2S packets for the Staff of Command's client-driven inputs.
 *
 *  - [CYCLE_TYPE] — shift+scroll delta; the server cycles the staff NBT's
 *    selected wheel key through [StaffOfCommandItem.availableKeys] (client
 *    HUD reads it back off the synced stack).
 *  - [AIM] — left-click "Aim To"; the server re-derives the wielder's gaze
 *    from its own player state (nothing to trust in the payload) and
 *    dispatches [StaffOfCommandItem.dispatch] with `aim = true`. */
object CommandNetwork {

    val CYCLE_TYPE: ResourceLocation = EnderkinesisMod.id("staff_of_command/cycle_type")
    val AIM: ResourceLocation = EnderkinesisMod.id("staff_of_command/aim")

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, CYCLE_TYPE) { buf, ctx ->
            val delta = buf.readInt()
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue {
                val staff = findStaff(player) ?: return@queue
                StaffOfCommandItem.cycleSelected(staff, delta)
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, AIM) { _, ctx ->
            val player = ctx.player as? ServerPlayer ?: return@registerReceiver
            ctx.queue {
                // Aim is a main-hand gesture — the off-hand stack is the
                // command's context parameter, mirroring the right-click path.
                val staff = player.mainHandItem
                if (staff.item != EKItems.STAFF_OF_COMMAND.get()) return@queue
                val selectedKey = StaffOfCommandItem.getSelectedKey(staff)
                val fired = StaffOfCommandItem.dispatch(
                    player, player.serverLevel(), staff, player.offhandItem, aim = true,
                )
                // Devices whose aim shows its OWN actionbar status (the
                // astrolabe's destination line) keep it — the generic
                // "Aimed N" overlay would land after theirs and clobber it.
                if (fired > 0 && selectedKey !in StaffOfCommandItem.SELF_MESSAGING_KEYS) {
                    player.displayClientMessage(
                        Component.translatable("item.enderkinesis.staff_of_command.aimed", fired), true,
                    )
                }
            }
        }
    }

    private fun findStaff(player: ServerPlayer): net.minecraft.world.item.ItemStack? {
        for (hand in arrayOf(InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND)) {
            val held = player.getItemInHand(hand)
            if (held.item == EKItems.STAFF_OF_COMMAND.get()) return held
        }
        return null
    }
}
