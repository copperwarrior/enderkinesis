package org.shipwrights.enderkinesis.spell

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.util.Mth

/**
 * An inscribed spell: its glyphs in inscription order. A fusion glyph is one element.
 * Everything else (magnitudes, shape, name) is derived from the glyphs.
 */
class Spell(val glyphs: List<Element>) {

    /** Each element's magnitude: the number of glyphs it has. This is also the order the
     *  elements apply in: Purity first, so its Cleanse never strips what the rest of the spell
     *  applies, then the others in order of first inscription. */
    val magnitudes: Map<Element, Float> =
        glyphs.groupingBy { it }.eachCount().mapValues { it.value.toFloat() }
            .entries.sortedBy { if (it.key == Element.PURITY) 0 else 1 }
            .associate { it.key to it.value }

    /** The element that decides the Forward shape: the strongest (most glyphs), then on a tie
     *  the highest-priority shape ([Shape]'s order), then [NAME_ORDER]. Null for an empty
     *  scroll. */
    val shapeElement: Element? =
        magnitudes.keys.minWithOrNull(
            compareByDescending<Element> { magnitude(it) }.thenBy { it.shape.ordinal }.thenBy { NAME_ORDER.indexOf(it) }
        )

    val shape: Shape? get() = shapeElement?.shape

    /** The signature this scroll casts Forward instead of its shape, if its elements make one. */
    val signature: Signature? by lazy { Signature.of(this) }

    /** How a Forward cast of this scroll is held, for the hand's pose and what it shows while
     *  held: a signature charges as a Bolt does, or is held as a Beam is. */
    val castShape: Shape?
        get() = when (signature?.style) {
            null -> shape
            Signature.Style.HELD -> Shape.BEAM
            else -> Shape.BOLT
        }

    fun magnitude(element: Element): Float = magnitudes[element] ?: 0f

    /** The generated spell name: `[Shape] of [adjectives] [core noun]`. Only the two
     *  strongest other elements are adjectives. */
    fun name(): SpellName? {
        val core = shapeElement ?: return null
        val adjectives = magnitudes.keys.filter { it != core }
            .sortedWith(strongestFirst())
            .take(MAX_ADJECTIVES)
            .map { it to adjectiveTier(magnitude(it)) }
        return SpellName(core.shape, core, adjectives)
    }

    /** The glyphs in inscription order with runs of one element shortened to ×N, each in its
     *  element's colour: "Gravity + Heat ×3 + Gravity". */
    fun glyphLine(): MutableComponent {
        val line = Component.empty()
        glyphRuns().forEachIndexed { i, (element, run) ->
            if (i > 0) line.append(Component.translatable(GLYPH_SEPARATOR_KEY))
            line.append(
                if (run == 1) element.displayName()
                else Component.translatable(GLYPH_RUN_KEY, element.displayName(), run).withStyle { it.withColor(element.color) }
            )
        }
        return line
    }

    /** Consecutive glyphs of one element, as (element, count). */
    fun glyphRuns(): List<Pair<Element, Int>> {
        val runs = ArrayList<Pair<Element, Int>>()
        for (element in glyphs) {
            val last = runs.lastOrNull()
            if (last?.first == element) runs[runs.size - 1] = element to last.second + 1 else runs += element to 1
        }
        return runs
    }

    fun save(): ListTag = ListTag().apply { glyphs.forEach { add(StringTag.valueOf(it.id)) } }

    /** The spell's colour (scroll wraps, runes): the hue of its element colours mixed, as
     *  saturated and as bright as they are on average, so mixed hues don't wash out to grey
     *  and a single element is exactly its own colour (Purity's pale lavender stays pale, not
     *  pushed to a deep blue). */
    fun tint(): Int {
        val elements = magnitudes.keys
        if (elements.isEmpty()) return 0xFFFFFF
        val r = elements.sumOf { (it.color shr 16) and 0xFF } / elements.size
        val g = elements.sumOf { (it.color shr 8) and 0xFF } / elements.size
        val b = elements.sumOf { it.color and 0xFF } / elements.size
        val max = maxOf(r, g, b)
        val chroma = max - minOf(r, g, b)
        // A near-grey average has no hue worth keeping.
        if (max == 0 || chroma.toFloat() / max < MIN_TINT_SATURATION) return (r shl 16) or (g shl 8) or b
        val sector = when (max) {
            r -> ((g - b).toFloat() / chroma).mod(6f)
            g -> (b - r).toFloat() / chroma + 2f
            else -> (r - g).toFloat() / chroma + 4f
        }
        val saturation = elements.map { saturation(it.color) }.average().toFloat()
        val value = elements.map { brightness(it.color) }.average().toFloat()
        return Mth.hsvToRgb(sector / 6f, saturation, value)
    }

    private fun channels(rgb: Int) = intArrayOf((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

    private fun saturation(rgb: Int): Float {
        val c = channels(rgb); val max = c.max()
        return if (max == 0) 0f else (max - c.min()).toFloat() / max
    }

    private fun brightness(rgb: Int): Float = channels(rgb).max() / 255f

    /** The spell's colour where it marks something (beams, runes, glints, glyphs): Purity
     *  pulls it most of the way to white, as the Staff of Aegis burns white. */
    fun markColour(): Int {
        val rgb = tint()
        if (Element.PURITY !in magnitudes) return rgb
        fun white(c: Int) = c + ((255 - c) * PURITY_WHITENESS).toInt()
        return (white((rgb shr 16) and 0xFF) shl 16) or (white((rgb shr 8) and 0xFF) shl 8) or white(rgb and 0xFF)
    }

    private fun strongestFirst(): Comparator<Element> =
        compareByDescending<Element> { magnitude(it) }.thenBy { NAME_ORDER.indexOf(it) }

    companion object {
        const val MAX_ADJECTIVES = 2
        /** NBT key for the glyph list on scrolls and the altar. */
        const val NBT_GLYPHS = "Glyphs"
        private const val MIN_TINT_SATURATION = 0.05f
        private const val PURITY_WHITENESS = 0.8f
        const val GLYPH_RUN_KEY = "spell.enderkinesis.glyph_run"
        const val GLYPH_SEPARATOR_KEY = "spell.enderkinesis.glyph_separator"

        /** Tie-break order for the core noun and the adjectives. */
        val NAME_ORDER: List<Element> = listOf(
            Element.GRAVITY, Element.VOID, Element.GROWTH, Element.PURITY, Element.SUNFIRE,
            Element.CHAOS, Element.HEAT, Element.COLD, Element.POISON, Element.BLOOD, Element.SUNDERING,
        )

        /** Adjective tier from magnitude: 1–2 is tier 1, 3–4 tier 2, 5 and up tier 3. */
        fun adjectiveTier(magnitude: Float): Int = when {
            magnitude >= 5f -> 3
            magnitude >= 3f -> 2
            else -> 1
        }

        /** Reads glyphs saved by [save], skipping any unknown ids. */
        fun load(tag: ListTag): Spell =
            Spell((0 until tag.size).mapNotNull { Element.byId(tag.getString(it)) })

        fun load(tag: CompoundTag, key: String): Spell? =
            if (tag.contains(key, Tag.TAG_LIST.toInt())) load(tag.getList(key, Tag.TAG_STRING.toInt())) else null
    }
}

/** A spell's generated name. [adjectives] are strongest first, with their tiers (1–3). */
data class SpellName(val shape: Shape, val core: Element, val adjectives: List<Pair<Element, Int>>) {

    /** The name as a translatable component. The shape word and "of" take the surrounding
     *  colour; each adjective and the core noun take their element's colour. The format
     *  string decides word order, so other languages can put adjectives after the noun. */
    fun toComponent(): MutableComponent {
        val args = buildList<Any> {
            add(Component.translatable(shape.translationKey))
            adjectives.forEach { (element, tier) ->
                add(Component.translatable(adjectiveKey(element, tier)).withStyle { it.withColor(element.color) })
            }
            add(core.displayName())
        }
        return Component.translatable("$FORMAT_KEY.${adjectives.size}", *args.toTypedArray())
    }

    companion object {
        const val FORMAT_KEY = "spell.enderkinesis.name"

        fun adjectiveKey(element: Element, tier: Int): String = "spell.enderkinesis.adjective.${element.id}.$tier"
    }
}
