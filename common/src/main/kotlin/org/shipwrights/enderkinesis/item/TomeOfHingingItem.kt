package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.valkyrienskies.mod.common.getShipManagingPos
import org.shipwrights.enderkinesis.item.TomePulseProfile
import org.shipwrights.enderkinesis.item.TomePulseProfiles

/**
 * Tome of Hinging — links two ship bodies with a rotational hinge. The line between the
 * SEND and RECEIVE orbs becomes the hinge axis; the bodies pivot freely around it.
 * Redstone power on the SEND orb's active face scales the resistance: power 0 = free
 * swing, power 15 = essentially locked.
 *
 * Refuses world↔world (no body to hinge against) and same-ship (a body can't hinge
 * against itself) at link time.
 */
class TomeOfHingingItem(properties: Properties) : LinkingTomeItem(properties) {

    override val tomeKind: ResourceLocation = HingingTomeOrbBehavior.tomeKind
    override val langPrefix: String = "item.enderkinesis.tome_of_hinging"

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Tome of Hinging", "The Pivot", "tome_of_hinging")

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
        val TOME_KIND: ResourceLocation = HingingTomeOrbBehavior.tomeKind

        /** Beam accent — brass / warm machined-metal yellow. Reads as "mechanical hinge",
         *  complementary to Pulling's iron-rust and Pushing's polished-steel. */
        const val BEAM_COLOR: Int = 0xB89968

        fun registerBeamPalette() {
            TomeBeamPalette.register(TOME_KIND, BEAM_COLOR)
            TomeOrbBehaviors.register(TOME_KIND, HingingTomeOrbBehavior)
            TomePulseProfiles.register(
                TOME_KIND,
                TomePulseProfile(
                    progression = 1.5, cohesion = 0.85, frequency = 1.0, reciprocal = true,
                ),
            )
        }
    }
}
