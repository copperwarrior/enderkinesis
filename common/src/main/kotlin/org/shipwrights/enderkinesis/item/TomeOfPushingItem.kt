package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Tome of Pushing — the inverse of [TomeOfPullingItem]. Each (send, receive) pair is a
 * Chaining-style rope at zero power; raising the SEND orb's active-face redstone signal
 * pushes the bodies apart. Power N forces at least N blocks of separation. The push is
 * spring-driven (mass-aware) so the bodies separate smoothly rather than being snapped.
 *
 * Refuses world↔world and same-ship at link time — a strut can't separate two world points
 * or push a body away from itself.
 */
class TomeOfPushingItem(properties: Properties) : LinkingTomeItem(properties) {

    override val tomeKind: ResourceLocation = PushingTomeOrbBehavior.tomeKind
    override val langPrefix: String = "item.enderkinesis.tome_of_pushing"

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Tome of Pushing", "The Strut", "tome_of_pushing")

    override fun validateLink(
        level: ServerLevel,
        sendBe: OrbOfLinkingBlockEntity,
        recvBe: OrbOfLinkingBlockEntity,
    ): String? {
        val sendShip = level.getShipManagingPos(sendBe.blockPos)
        val recvShip = level.getShipManagingPos(recvBe.blockPos)
        if (sendShip == null && recvShip == null) return "cant_world_world"
        if (sendShip != null && recvShip != null && sendShip.id == recvShip.id) return "cant_same_ship"
        return null
    }

    companion object {
        val TOME_KIND: ResourceLocation = PushingTomeOrbBehavior.tomeKind

        /** Accent colour the orb-network beam carries (0xRRGGBB). Polished steel —
         *  complements Pulling's iron rust (warm metallic) with a cool metallic. Reads
         *  as 'strut under compression / jack at work'. */
        const val BEAM_COLOR: Int = 0x707888

        fun registerBeamPalette() {
            TomeBeamPalette.register(TOME_KIND, BEAM_COLOR)
            TomeOrbBehaviors.register(TOME_KIND, PushingTomeOrbBehavior)
        }
    }
}
