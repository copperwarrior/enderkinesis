package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/**
 * Tome of Transportation — moves items between linked orbs at 1 block per tick.
 *
 * The tome is the wiring tool; it doesn't itself carry items. Each SEND orb pulls one stack
 * per tick from the container at its active face and dispatches it to the next receiver in
 * round-robin order. The stack visibly flies along the link as a ghost item entity (no
 * gravity, no pickup) trailing purple sparkles, then on arrival pushes into the container at
 * the RECEIVE orb's active face — or drops as a regular item entity if there's no container.
 *
 * All link gestures inherit from [LinkingTomeItem]; the dispatch / delivery logic lives in
 * [TransportationTomeOrbBehavior]. No tome-specific link validation — both world↔ship,
 * ship↔ship, and world↔world all work, as long as each end has a container (or you're OK
 * with items dropping as entities at the receiver).
 */
class TomeOfTransportationItem(properties: Properties) : LinkingTomeItem(properties) {

    override val tomeKind: ResourceLocation = TransportationTomeOrbBehavior.tomeKind
    override val langPrefix: String = "item.enderkinesis.tome_of_transportation"

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Tome of Transportation", "The Porter", "tome_of_transportation")

    companion object {
        val TOME_KIND: ResourceLocation = TransportationTomeOrbBehavior.tomeKind

        /** Accent colour the orb-network beam carries (0xRRGGBB). Vibrant magic-purple to
         *  match the WITCH-particle trail and read as "ender freight". */
        const val BEAM_COLOR: Int = 0xB554FF

        fun registerBeamPalette() {
            TomeBeamPalette.register(TOME_KIND, BEAM_COLOR)
            TomeOrbBehaviors.register(TOME_KIND, TransportationTomeOrbBehavior)
        }
    }
}
