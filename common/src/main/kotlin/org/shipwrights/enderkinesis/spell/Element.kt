package org.shipwrights.enderkinesis.spell

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor

/**
 * The spell elements. The first seven come from Mystic Gem shards and form three opposing
 * pairs plus Blood. Sunfire, Poison and Void exist only as altar fusions; Sundering only
 * comes from the Staff of Sundering and can never be inscribed.
 *
 * [color] is the element's tooltip colour, chosen to read on the dark tooltip background.
 */
enum class Element(val id: String, val color: Int) {
    HEAT("heat", 0xF08A3A),
    COLD("cold", 0x5FB4DC),
    GROWTH("growth", 0x78BB5C),
    PURITY("purity", 0xB7AEE2),
    GRAVITY("gravity", 0xA69ECD),
    CHAOS("chaos", 0xEA6A9A),
    BLOOD("blood", 0xE3555F),
    SUNFIRE("sunfire", 0xE6B53C),
    POISON("poison", 0x94C25A),
    VOID("void", 0xB77FD4),
    SUNDERING("sundering", 0xC03A2B);

    /** The element this one annihilates with, or null for Blood, the fusions and Sundering. */
    val opposite: Element?
        get() = when (this) {
            HEAT -> COLD
            COLD -> HEAT
            GROWTH -> PURITY
            PURITY -> GROWTH
            GRAVITY -> CHAOS
            CHAOS -> GRAVITY
            else -> null
        }

    val translationKey: String get() = "element.enderkinesis.$id"

    /** The element's name in its own colour. */
    fun displayName(): MutableComponent =
        Component.translatable(translationKey).withStyle { it.withColor(TextColor.fromRgb(color)) }
}
