package org.shipwrights.enderkinesis.spell

object Inscription {
    /** Annihilated shards are gone and take no room. */
    const val MAX_GLYPHS = 7

    sealed interface Outcome {
        /** The shard annihilated with [hit]; [left] is what remains of a broken fusion. */
        data class Annihilated(val hit: Element, val left: Element?) : Outcome
        data class Fused(val into: Element) : Outcome
        data object Inscribed : Outcome
    }

    /** [index] is the glyph the shard met, in the old glyphs. It is -1 when the shard went at the end. */
    class Result(val glyphs: List<Element>, val outcome: Outcome, val index: Int = -1)

    /** Returns null when the scroll is full and the shard would add a glyph. */
    fun inscribe(glyphs: List<Element>, shard: Element): Result? {
        require(!shard.isFusion && shard != Element.SUNDERING) { "$shard can't be inscribed" }
        val result = resolve(glyphs, shard)
        return if (result.glyphs.size > MAX_GLYPHS) null else result
    }

    /** [inscribe] without the glyph limit, for beams that meet. The newest matching glyph is hit.
     *  Remaining glyphs are not checked again, so two plain fusion partners can stay side by side. */
    fun resolve(glyphs: List<Element>, shard: Element): Result {
        val opposite = shard.opposite
        if (opposite != null) {
            val i = glyphs.indexOfLast { opposite in it.components }
            if (i >= 0) {
                val hit = glyphs[i]
                val left = hit.components.firstOrNull { it != opposite }
                val out = glyphs.toMutableList()
                if (left != null) out[i] = left else out.removeAt(i)
                return Result(out, Outcome.Annihilated(hit, left), i)
            }
        }
        val partner = glyphs.indexOfLast { !it.isFusion && Element.fusionOf(it, shard) != null }
        if (partner >= 0) {
            val fusion = Element.fusionOf(glyphs[partner], shard)!!
            val out = glyphs.toMutableList()
            out[partner] = fusion
            return Result(out, Outcome.Fused(fusion), partner)
        }
        return Result(glyphs + shard, Outcome.Inscribed)
    }
}
