package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/**
 * Tome of Signal — first member of the orb-tome suite. Wires a SEND orb's strongest neighbour
 * redstone level to every linked RECEIVE orb within 64 blocks of world-space (ship-inclusive),
 * mirroring the 0–15 level on each receiver's output. Receivers with multiple incoming Signal
 * links carry the max across all loaded senders.
 *
 * All link gestures (select, link, toggle-off, sneak-drop) come from [LinkingTomeItem]. The
 * propagation logic lives in [SignalTomeOrbBehavior].
 */
class TomeOfSignalItem(properties: Properties) : LinkingTomeItem(properties) {

    override val tomeKind: ResourceLocation = SignalTomeOrbBehavior.tomeKind
    override val langPrefix: String = "item.enderkinesis.tome_of_signal"

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Tome of Signal", "The Linker", "tome_of_signal")

    companion object {
        /** Identifier this tome stamps onto every link in its networks. */
        val TOME_KIND: ResourceLocation = SignalTomeOrbBehavior.tomeKind

        /** Accent colour the orb-network beam carries (0xRRGGBB). 20% of beam glyphs (see
         *  [TomeBeamPalette.ACCENT_CHANCE]) flow this colour — Signal goes red. */
        const val BEAM_COLOR: Int = 0xE53935

        /** Register accent colour and propagation behavior. Called from common mod init. */
        fun registerBeamPalette() {
            TomeBeamPalette.register(TOME_KIND, BEAM_COLOR)
            TomeOrbBehaviors.register(TOME_KIND, SignalTomeOrbBehavior)
        }
    }
}
