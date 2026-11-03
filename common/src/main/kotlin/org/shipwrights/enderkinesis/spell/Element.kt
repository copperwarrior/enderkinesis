package org.shipwrights.enderkinesis.spell

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor

/** Sundering comes only from the Staff of Sundering and can never be inscribed.
 *  [color] must be easy to read on the dark tooltip background. */
enum class Element(val id: String, val color: Int) {
    PYRE("pyre", 0xF08A3A),
    WINTER("winter", 0x5FB4DC),
    GENESIS("genesis", 0x78BB5C),
    QUINTESSENCE("quintessence", 0xB7AEE2),
    GRAVITY("gravity", 0xA69ECD),
    CHAOS("chaos", 0xEA6A9A),
    BLOOD("blood", 0xE3555F),
    SUNFIRE("sunfire", 0xE6B53C),
    CORRUPTION("corruption", 0x94C25A),
    VOID("void", 0xB77FD4),
    SUNDERING("sundering", 0xC03A2B);

    val opposite: Element?
        get() = when (this) {
            PYRE -> WINTER
            WINTER -> PYRE
            GENESIS -> QUINTESSENCE
            QUINTESSENCE -> GENESIS
            GRAVITY -> CHAOS
            CHAOS -> GRAVITY
            else -> null
        }

    val components: List<Element>
        get() = when (this) {
            SUNFIRE -> listOf(PYRE, QUINTESSENCE)
            CORRUPTION -> listOf(WINTER, GENESIS)
            VOID -> listOf(GRAVITY, BLOOD)
            else -> listOf(this)
        }

    val isFusion: Boolean get() = components.size > 1

    val shape: Shape
        get() = when (this) {
            GRAVITY, VOID -> Shape.BOLT
            GENESIS, QUINTESSENCE, SUNFIRE, SUNDERING -> Shape.BEAM
            CHAOS -> Shape.CHAIN
            PYRE, WINTER, CORRUPTION, BLOOD -> Shape.SPRAY
        }

    val translationKey: String get() = "element.enderkinesis.$id"

    fun displayName(): MutableComponent =
        Component.translatable(translationKey).withStyle { it.withColor(TextColor.fromRgb(color)) }

    companion object {
        private val BY_ID: Map<String, Element> = entries.associateBy { it.id }

        fun byId(id: String): Element? = BY_ID[id]

        fun fusionOf(a: Element, b: Element): Element? =
            entries.firstOrNull { it.isFusion && it.components.containsAll(listOf(a, b)) && a != b }
    }
}

/** Entry order is the tie-break: between elements of equal strength, the shape listed first wins. */
enum class Shape(val id: String) {
    BOLT("bolt"),
    BEAM("beam"),
    CHAIN("chain"),
    SPRAY("spray");

    val translationKey: String get() = "spell.enderkinesis.shape.$id"
}
