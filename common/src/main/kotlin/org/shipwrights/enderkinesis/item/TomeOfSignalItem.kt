package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.item.TomePulseProfile
import org.shipwrights.enderkinesis.item.TomePulseProfiles

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
            TomePulseProfiles.register(
                TOME_KIND,
                TomePulseProfile(
                    progression = 0.5, cohesion = 0.85, frequency = 0.5, reciprocal = false,
                ),
            )
            // Dynamic scaling: progression and frequency ramp with the RECEIVE end's POWER
            // (Signal mirrors SEND power across the link, so receiver POWER is the visible
            // "signal arrived" reading). Cohesion stays tight at every signal level —
            // Signal's pulses always read as a thin, focused red wire.
            TomePulseProfiles.registerAdjuster(TOME_KIND) { base, level, _, recvPos ->
                val state = level.getBlockState(recvPos)
                val power = if (state.hasProperty(org.shipwrights.enderkinesis.block.OrbOfLinkingBlock.POWER))
                    state.getValue(org.shipwrights.enderkinesis.block.OrbOfLinkingBlock.POWER) else 0
                val s = power / 15.0
                base.copy(
                    progression = base.progression + (1.8 - base.progression) * s,
                    frequency = base.frequency + (2.5 - base.frequency) * s,
                )
            }
        }
    }
}
