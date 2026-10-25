package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.spell.Spell

/**
 * Mystic Scroll, a finished spell from the Altar of Illumination. Every scroll is this one
 * item, always named "Mystic Scroll"; its spell name is generated from its glyphs and shown
 * in the tooltip, with the exact glyphs while Shift is held. Never stacks.
 */
class MysticScrollItem(properties: Properties) : Item(properties) {

    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val spell = spellOf(stack) ?: return
        val name = spell.name() ?: return
        tooltip += name.toComponent().withStyle(ChatFormatting.ITALIC)
        // Tooltips are only built on the client, so the client-only Screen class is safe here.
        if (Screen.hasShiftDown()) tooltip += spell.glyphLine()
    }

    companion object {
        /** Wrap colour for a scroll with no spell. */
        const val BLANK_WRAP_COLOR = 0xFFFFFF

        fun spellOf(stack: ItemStack): Spell? =
            stack.tag?.let { Spell.load(it, Spell.NBT_GLYPHS) }?.takeIf { it.glyphs.isNotEmpty() }

        /** The wrap layer's tint: the average of the spell's element colours, pushed to full
         *  saturation so mixed hues don't wash out to grey. */
        fun wrapColor(stack: ItemStack): Int {
            val elements = spellOf(stack)?.magnitudes?.keys ?: return BLANK_WRAP_COLOR
            val r = elements.sumOf { (it.color shr 16) and 0xFF } / elements.size
            val g = elements.sumOf { (it.color shr 8) and 0xFF } / elements.size
            val b = elements.sumOf { it.color and 0xFF } / elements.size
            val max = maxOf(r, g, b)
            val chroma = max - minOf(r, g, b)
            // A near-grey average has no hue worth saturating.
            if (max == 0 || chroma.toFloat() / max < MIN_SATURATION) return (r shl 16) or (g shl 8) or b
            val sector = when (max) {
                r -> ((g - b).toFloat() / chroma).mod(6f)
                g -> (b - r).toFloat() / chroma + 2f
                else -> (r - g).toFloat() / chroma + 4f
            }
            return Mth.hsvToRgb(sector / 6f, 1f, max / 255f)
        }

        private const val MIN_SATURATION = 0.05f
    }
}
