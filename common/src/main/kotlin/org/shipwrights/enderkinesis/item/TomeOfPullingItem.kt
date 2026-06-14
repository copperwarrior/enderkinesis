package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.blockentity.OrbOfLinkingBlockEntity
import org.valkyrienskies.mod.common.getShipManagingPos
import org.shipwrights.enderkinesis.item.TomePulseProfile
import org.shipwrights.enderkinesis.item.TomePulseProfiles

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
            TomePulseProfiles.register(
                TOME_KIND,
                TomePulseProfile(
                    progression = 0.5, cohesion = 0.3, frequency = 0.5, reciprocal = true,
                ),
            )
            // Dynamic scaling: progression, frequency, and cohesion all ramp with the SEND
            // orb's POWER — that's the redstone strength driving the joint's force. A
            // winching pull at full strength visibly moves fast, dense, and tight.
            TomePulseProfiles.registerAdjuster(TOME_KIND) { base, level, sendPos, _ ->
                val state = level.getBlockState(sendPos)
                val power = if (state.hasProperty(org.shipwrights.enderkinesis.block.OrbOfLinkingBlock.POWER))
                    state.getValue(org.shipwrights.enderkinesis.block.OrbOfLinkingBlock.POWER) else 0
                val s = power / 15.0
                base.copy(
                    progression = base.progression + (1.8 - base.progression) * s,
                    cohesion = base.cohesion + (0.9 - base.cohesion) * s,
                    frequency = base.frequency + (2.5 - base.frequency) * s,
                )
            }
        }
    }
}
