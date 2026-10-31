package org.shipwrights.enderkinesis.spell

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

/**
 * A signature spell: one exact mix of elements on a full scroll that casts something of its own
 * instead of its shape. Only the Forward cast changes; an Area cast, a rune or an imbue made from
 * the same scroll works as its elements always do. The glyphs may be in any order, but the counts
 * must match [recipe] exactly and fill the scroll.
 *
 * [style] is how the Forward cast is held:
 * - [Style.RELEASE]: held to charge, as a Bolt is; the spell leaves the hand on release, its power
 *   from how long it was charged.
 * - [Style.CHARGE]: the spell is there in the hand while held, growing, and goes off on release.
 * - [Style.HELD]: the spell lasts while held and ends on release, as a Beam does.
 */
enum class Signature(val id: String, val style: Style, vararg recipe: Pair<Element, Int>) {
    BLACK_HOLE_SUN("black_hole_sun", Style.RELEASE, Element.GRAVITY to 4, Element.SUNFIRE to 3),
    STARFALL("starfall", Style.RELEASE, Element.GRAVITY to 4, Element.HEAT to 3),
    NORTH_STAR("north_star", Style.RELEASE, Element.GRAVITY to 4, Element.COLD to 3),
    HUNGERING_MAW("hungering_maw", Style.RELEASE, Element.VOID to 4, Element.GRAVITY to 3),
    NETHER_RIFT("nether_rift", Style.RELEASE, Element.VOID to 4, Element.HEAT to 3),
    YGANNS_GRASP("yganns_grasp", Style.RELEASE, Element.VOID to 3, Element.BLOOD to 2, Element.POISON to 2),
    AVALANCHE("avalanche", Style.RELEASE, Element.COLD to 4, Element.GRAVITY to 3),
    STILLPOINT("stillpoint", Style.RELEASE, Element.COLD to 4, Element.VOID to 3),
    AURORA_VEIL("aurora_veil", Style.RELEASE, Element.COLD to 3, Element.PURITY to 2, Element.CHAOS to 2),
    FIREWEED("fireweed", Style.RELEASE, Element.HEAT to 4, Element.GROWTH to 3),
    BLOODWAVE("bloodwave", Style.RELEASE, Element.BLOOD to 4, Element.VOID to 3),
    GARDEN_SANGUINE("garden_sanguine", Style.HELD, Element.BLOOD to 4, Element.GROWTH to 3),
    ROTDANCE("rotdance", Style.RELEASE, Element.POISON to 4, Element.CHAOS to 3),
    ACID_RAIN("acid_rain", Style.RELEASE, Element.POISON to 4, Element.GRAVITY to 3),
    STORM_CLOUD("storm_cloud", Style.RELEASE, Element.CHAOS to 4, Element.COLD to 3),
    FAESPARK("faespark", Style.HELD, Element.CHAOS to 4, Element.HEAT to 3),
    VAMPIRES_BALLROOM("vampires_ballroom", Style.RELEASE, Element.CHAOS to 4, Element.BLOOD to 3),
    GUST_OF_GLASS("gust_of_glass", Style.RELEASE, Element.CHAOS to 4, Element.PURITY to 3),
    ROOTQUAKE("rootquake", Style.RELEASE, Element.GROWTH to 4, Element.GRAVITY to 3),
    SHROOMSPREAD("shroomspread", Style.RELEASE, Element.GROWTH to 4, Element.POISON to 3),
    PRISM_LANCE("prism_lance", Style.HELD, Element.PURITY to 4, Element.COLD to 3),
    TRANSFUSION("transfusion", Style.HELD, Element.PURITY to 4, Element.BLOOD to 3),
    JUDGEMENT_PILLAR("judgement_pillar", Style.RELEASE, Element.SUNFIRE to 4, Element.PURITY to 3),
    SUPERNOVA("supernova", Style.CHARGE, Element.SUNFIRE to 5, Element.CHAOS to 2),
    SUPPLICATION_OF_SSELITH("supplication_of_sselith", Style.HELD, Element.SUNFIRE to 3, Element.VOID to 2, Element.HEAT to 2);

    val recipe: Map<Element, Int> = recipe.toMap()

    /** A full scroll's glyphs for this signature, in recipe order. */
    val glyphs: List<Element> get() = recipe.flatMap { (element, n) -> List(n) { element } }

    val translationKey: String get() = "signature.enderkinesis.$id"

    fun displayName(): MutableComponent = Component.translatable(translationKey)

    enum class Style { RELEASE, CHARGE, HELD }

    companion object {
        /** The signature [spell] casts, or null when its elements match none. */
        fun of(spell: Spell): Signature? {
            if (spell.glyphs.size != Inscription.MAX_GLYPHS) return null
            return entries.firstOrNull { sig ->
                sig.recipe.size == spell.magnitudes.size && sig.recipe.all { (element, n) -> spell.magnitude(element) == n.toFloat() }
            }
        }
    }
}
