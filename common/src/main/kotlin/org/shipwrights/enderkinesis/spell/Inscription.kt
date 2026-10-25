package org.shipwrights.enderkinesis.spell

/**
 * Resolves a shard added at the Altar of Illumination against the glyphs already on the
 * scroll. Every shard resolves immediately, in the order it was added:
 *
 * 1. Opposites first. If any glyph has the shard's opposite, counting the components inside
 *    fusions, the two annihilate. A fusion hit on one component breaks, and its other
 *    component stays as a plain glyph in the fusion's place.
 * 2. Otherwise, if a plain glyph is the shard's fusion partner, they fuse in that glyph's
 *    place.
 * 3. Otherwise the shard becomes a plain glyph at the end.
 *
 * When several glyphs qualify, the most recently inscribed one is hit. Glyphs left over are
 * never rechecked, which is how two plain fusion partners can end up side by side: a
 * fusion broken by an opposite leaves its component next to an existing partner.
 */
object Inscription {
    /** The most glyphs a scroll holds. Annihilated shards are gone and take no room. */
    const val MAX_GLYPHS = 7

    sealed interface Outcome {
        /** The shard annihilated with [hit]; [left] is what remains of a broken fusion. */
        data class Annihilated(val hit: Element, val left: Element?) : Outcome
        data class Fused(val into: Element) : Outcome
        data object Inscribed : Outcome
    }

    class Result(val glyphs: List<Element>, val outcome: Outcome)

    /** Resolves [shard] against [glyphs], oldest first. Returns null when the scroll is full
     *  and the shard would become another glyph. */
    fun inscribe(glyphs: List<Element>, shard: Element): Result? {
        require(!shard.isFusion && shard != Element.SUNDERING) { "$shard can't be inscribed" }
        val result = resolve(glyphs, shard)
        return if (result.glyphs.size > MAX_GLYPHS) null else result
    }

    /** [inscribe] without the glyph limit, for beams meeting: their glyphs resolve as at
     *  the altar, but nothing caps them. [shard] must be a shard element. */
    fun resolve(glyphs: List<Element>, shard: Element): Result {
        val opposite = shard.opposite
        if (opposite != null) {
            val i = glyphs.indexOfLast { opposite in it.components }
            if (i >= 0) {
                val hit = glyphs[i]
                val left = hit.components.firstOrNull { it != opposite }
                val out = glyphs.toMutableList()
                if (left != null) out[i] = left else out.removeAt(i)
                return Result(out, Outcome.Annihilated(hit, left))
            }
        }
        val partner = glyphs.indexOfLast { !it.isFusion && Element.fusionOf(it, shard) != null }
        if (partner >= 0) {
            val fusion = Element.fusionOf(glyphs[partner], shard)!!
            val out = glyphs.toMutableList()
            out[partner] = fusion
            return Result(out, Outcome.Fused(fusion))
        }
        return Result(glyphs + shard, Outcome.Inscribed)
    }
}
