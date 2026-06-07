package org.shipwrights.enderkinesis.util

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation

/**
 * Renders text in everyone's favourite galactic alphabet.
 *
 * Vanilla ships the Standard Galactic Alphabet as the `minecraft:alt` font (the one the enchanting
 * table uses), so we just restyle the component with that font rather than transliterating.
 */
object Sga {
    private val ALT_FONT = ResourceLocation("minecraft", "alt")

    fun obfuscate(text: String): MutableComponent =
        Component.literal(text).withStyle(Style.EMPTY.withFont(ALT_FONT))

    fun obfuscate(component: Component): MutableComponent =
        component.copy().withStyle(Style.EMPTY.withFont(ALT_FONT))
}
