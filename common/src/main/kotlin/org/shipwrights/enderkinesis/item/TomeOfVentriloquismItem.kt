package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.item.TomePulseProfile
import org.shipwrights.enderkinesis.item.TomePulseProfiles

/**
 * Tome of Ventriloquism — mirrors sounds heard within
 * [VentriloquismNetwork.CAPTURE_RADIUS] blocks of a SEND orb to every linked RECEIVE orb at
 * the same relative offset. Only the BLOCKS / HOSTILE / NEUTRAL / PLAYERS / RECORDS / VOICE
 * channels are captured; MASTER / MUSIC / WEATHER / AMBIENT are passed through unmodified.
 *
 * All link gestures (select, link, toggle-off, sneak-drop) come from [LinkingTomeItem]; the
 * registration bookkeeping lives in [VentriloquismTomeOrbBehavior]; the per-sound interception
 * lives in `ServerLevelVentriloquismSoundMixin`.
 */
class TomeOfVentriloquismItem(properties: Properties) : LinkingTomeItem(properties) {

    override val tomeKind: ResourceLocation = VentriloquismTomeOrbBehavior.tomeKind
    override val langPrefix: String = "item.enderkinesis.tome_of_ventriloquism"

    override fun writtenBook(stack: ItemStack): ItemStack =
        TomeLore.fromSselithBook("Tome of Ventriloquism", "The Linker", "tome_of_ventriloquism")

    companion object {
        val TOME_KIND: ResourceLocation = VentriloquismTomeOrbBehavior.tomeKind

        /** Accent colour the orb-network beam carries. Cool indigo — distinct from Signal red,
         *  Transportation purple, Vacuum grey, Disintegration crimson. */
        const val BEAM_COLOR: Int = 0x4A5DC9

        fun registerBeamPalette() {
            TomeBeamPalette.register(TOME_KIND, BEAM_COLOR)
            TomeOrbBehaviors.register(TOME_KIND, VentriloquismTomeOrbBehavior)
            TomePulseProfiles.register(
                TOME_KIND,
                TomePulseProfile(
                    progression = 0.5, cohesion = 0.2, frequency = 0.4, reciprocal = false,
                ),
            )
        }
    }
}
