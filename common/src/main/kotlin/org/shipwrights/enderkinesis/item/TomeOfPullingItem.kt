package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Tome of Pulling — redstone-driven winch. Acts as a [TomeOfChainingItem]-style rope at
 * power 0 (slack rope, hard at the captured length), but as the SEND orb's active-face
 * redstone signal climbs, the rope's max length contracts proportionally — power 15
 * reels the bodies in as close as the joint solver allows (~0.5 b). The contraction is
 * smooth (mass-aware spring + damping), not yanky.
 *
 * Refuses world↔world and same-ship at link time (a winch can't reel two world points
 * together, or pull a body against itself).
 */
class TomeOfPullingItem(properties: Properties) : LinkingTomeItem(properties) {

    override val tomeKind: ResourceLocation = PullingTomeOrbBehavior.tomeKind
    override val langPrefix: String = "item.enderkinesis.tome_of_pulling"

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Tome of Pulling", "The Capstan", "tome_of_pulling")

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
        val TOME_KIND: ResourceLocation = PullingTomeOrbBehavior.tomeKind

        /** Accent colour the orb-network beam carries (0xRRGGBB). Iron-rust — reads as
         *  a capstan / winch cable, distinct from Chaining's cooler hemp brown. */
        const val BEAM_COLOR: Int = 0xC07050

        fun registerBeamPalette() {
            TomeBeamPalette.register(TOME_KIND, BEAM_COLOR)
            TomeOrbBehaviors.register(TOME_KIND, PullingTomeOrbBehavior)
        }
    }
}
