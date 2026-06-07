package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/**
 * Tome of Disintegration — fires a destructive beam from the SEND orb toward each linked
 * RECEIVE orb. Mines the first breakable block in the ray (Create-drill style, one block
 * at a time scaled by hardness), damages [net.minecraft.world.entity.LivingEntity]s along
 * the unblocked segment, and vacuums dropped items in the beam AABB. Drops flow through
 * the standard Transportation-style flight to the receiver's active-face container.
 *
 * No link constraints — works world↔world, world↔ship, ship↔ship, even same-ship (the
 * beam doesn't care about the bodies).
 */
class TomeOfDisintegrationItem(properties: Properties) : LinkingTomeItem(properties) {

    override val tomeKind: ResourceLocation = DisintegrationTomeOrbBehavior.tomeKind
    override val langPrefix: String = "item.enderkinesis.tome_of_disintegration"

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Tome of Disintegration", "The Lance", "tome_of_disintegration")

    companion object {
        val TOME_KIND: ResourceLocation = DisintegrationTomeOrbBehavior.tomeKind

        /** Beam accent — angry red. Reads as "this is going to hurt." */
        const val BEAM_COLOR: Int = 0xCC2020

        fun registerBeamPalette() {
            TomeBeamPalette.register(TOME_KIND, BEAM_COLOR)
            TomeOrbBehaviors.register(TOME_KIND, DisintegrationTomeOrbBehavior)
        }
    }
}
