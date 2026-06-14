package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.item.TomePulseProfile
import org.shipwrights.enderkinesis.item.TomePulseProfiles

/**
 * Tome of Vacuum — moves item stacks between linked orbs like the Tome of Transportation,
 * but the source is **dropped item entities** in a 5×5×3 region around the SEND orb (3
 * blocks deep along the active-face axis, 5 in the other two) instead of a container at
 * the active face. Useful for collecting mob drops, breakable-block drops, or player-tossed
 * items into a remote storage network without needing a hopper / chest at the pickup point.
 *
 * RECEIVE behaviour is unchanged from Transportation: the stack pushes into the receiver's
 * active-face container, or drops as an item entity if there's no container. Bounce-back
 * also works — a stack that can't fit at the receiver flies back to the SEND, drops at the
 * orb, and waits for the vacuum's next sweep to pick it up again.
 */
class TomeOfVacuumItem(properties: Properties) : LinkingTomeItem(properties) {

    override val tomeKind: ResourceLocation = VacuumTomeOrbBehavior.tomeKind
    override val langPrefix: String = "item.enderkinesis.tome_of_vacuum"

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Tome of Vacuum", "The Gatherer", "tome_of_vacuum")

    companion object {
        val TOME_KIND: ResourceLocation = VacuumTomeOrbBehavior.tomeKind

        /** Accent colour the orb-network beam carries (0xRRGGBB). Storm gray — reads as
         *  industrial suction, distinct from every other tome in the palette. */
        const val BEAM_COLOR: Int = 0x8090A0

        fun registerBeamPalette() {
            TomeBeamPalette.register(TOME_KIND, BEAM_COLOR)
            TomeOrbBehaviors.register(TOME_KIND, VacuumTomeOrbBehavior)
            TomePulseProfiles.register(
                TOME_KIND,
                TomePulseProfile(
                    progression = 1.0, cohesion = 0.25, frequency = 1.0, reciprocal = false,
                ),
            )
        }
    }
}
