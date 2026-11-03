package org.shipwrights.enderkinesis.spell

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/** Only the Forward cast changes. Glyph order does not matter, but counts must match [recipe] and fill the scroll. */
enum class Signature(val id: String, val style: Style, vararg recipe: Pair<Element, Int>) {
    BLACK_HOLE_SUN("black_hole_sun", Style.RELEASE, Element.GRAVITY to 4, Element.SUNFIRE to 3),
    STARFALL("starfall", Style.RELEASE, Element.GRAVITY to 4, Element.PYRE to 3),
    NORTH_STAR("north_star", Style.RELEASE, Element.GRAVITY to 4, Element.WINTER to 3),
    HUNGERING_MAW("hungering_maw", Style.RELEASE, Element.VOID to 4, Element.GRAVITY to 3),
    NETHER_RIFT("nether_rift", Style.RELEASE, Element.VOID to 4, Element.PYRE to 3),
    YGANNS_GRASP("yganns_grasp", Style.RELEASE, Element.VOID to 3, Element.BLOOD to 2, Element.CORRUPTION to 2),
    AVALANCHE("avalanche", Style.RELEASE, Element.WINTER to 4, Element.GRAVITY to 3),
    STILLPOINT("stillpoint", Style.RELEASE, Element.WINTER to 4, Element.VOID to 3),
    AURORA_VEIL("aurora_veil", Style.RELEASE, Element.WINTER to 3, Element.QUINTESSENCE to 2, Element.CHAOS to 2),
    FIREWEED("fireweed", Style.RELEASE, Element.PYRE to 4, Element.GENESIS to 3),
    BLOODWAVE("bloodwave", Style.RELEASE, Element.BLOOD to 4, Element.VOID to 3),
    GARDEN_SANGUINE("garden_sanguine", Style.HELD, Element.BLOOD to 4, Element.GENESIS to 3),
    ROTDANCE("rotdance", Style.RELEASE, Element.CORRUPTION to 4, Element.CHAOS to 3),
    ACID_RAIN("acid_rain", Style.RELEASE, Element.CORRUPTION to 4, Element.GRAVITY to 3),
    STORM_CLOUD("storm_cloud", Style.RELEASE, Element.CHAOS to 4, Element.WINTER to 3),
    FAESPARK("faespark", Style.HELD, Element.CHAOS to 4, Element.PYRE to 3),
    VAMPIRES_BALLROOM("vampires_ballroom", Style.RELEASE, Element.CHAOS to 4, Element.BLOOD to 3),
    GUST_OF_GLASS("gust_of_glass", Style.RELEASE, Element.CHAOS to 4, Element.QUINTESSENCE to 3),
    ROOTQUAKE("rootquake", Style.RELEASE, Element.GENESIS to 4, Element.GRAVITY to 3),
    SHROOMSPREAD("shroomspread", Style.RELEASE, Element.GENESIS to 4, Element.CORRUPTION to 3),
    PRISM_LANCE("prism_lance", Style.HELD, Element.QUINTESSENCE to 4, Element.WINTER to 3),
    TRANSFUSION("transfusion", Style.HELD, Element.QUINTESSENCE to 4, Element.BLOOD to 3),
    JUDGEMENT_PILLAR("judgement_pillar", Style.RELEASE, Element.SUNFIRE to 4, Element.QUINTESSENCE to 3),
    SUPERNOVA("supernova", Style.CHARGE, Element.SUNFIRE to 5, Element.CHAOS to 2),
    SUPPLICATION_OF_SSELITH("supplication_of_sselith", Style.HELD, Element.SUNFIRE to 3, Element.VOID to 2, Element.PYRE to 2);

    val recipe: Map<Element, Int> = recipe.toMap()

    val glyphs: List<Element> get() = recipe.flatMap { (element, n) -> List(n) { element } }

    val translationKey: String get() = "signature.enderkinesis.$id"

    fun displayName(): MutableComponent = Component.translatable(translationKey)

    enum class Style { RELEASE, CHARGE, HELD }

    companion object {
        fun of(spell: Spell): Signature? {
            if (spell.glyphs.size != Inscription.MAX_GLYPHS) return null
            return entries.firstOrNull { sig ->
                sig.recipe.size == spell.magnitudes.size && sig.recipe.all { (element, n) -> spell.magnitude(element) == n.toFloat() }
            }
        }
    }
}
