package org.shipwrights.enderkinesis.spell

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor

/** Sundering comes only from the Staff of Sundering and can never be inscribed.
 *  [color] must be easy to read on the dark tooltip background. */
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

    val components: List<Element>
        get() = when (this) {
            SUNFIRE -> listOf(HEAT, PURITY)
            POISON -> listOf(COLD, GROWTH)
            VOID -> listOf(GRAVITY, BLOOD)
            else -> listOf(this)
        }

    val isFusion: Boolean get() = components.size > 1

    val shape: Shape
        get() = when (this) {
            GRAVITY, VOID -> Shape.BOLT
            GROWTH, PURITY, SUNFIRE, SUNDERING -> Shape.BEAM
            CHAOS -> Shape.CHAIN
            HEAT, COLD, POISON, BLOOD -> Shape.SPRAY
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
