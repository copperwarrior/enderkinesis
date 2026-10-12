package org.shipwrights.enderkinesis.sselith

import java.math.BigInteger
import java.util.Random
import java.util.concurrent.CompletableFuture

/**
 * Library-of-Babel pages over dictionary words, one library per [Language]. Each library holds every
 * [WORDS]-word sequence that fits on one book page under [BookPaginator]'s layout; [pageAt] and [locate]
 * are a bijection between `0 until total` and those pages.
 *
 * A location is a placeable block position in Sselith and a parallel, read as one address
 * `parallel·POSITIONS + position`. Every page of both libraries sits at exactly one address below
 * [addressCount]; addresses past it are blank. Parallels have no upper bound, so every page has a location.
 */
object BookshelfPage {

    enum class Language { SSELITH, ENGLISH }

    data class Address(val language: Language, val index: BigInteger)

    data class Location(val x: Int, val y: Int, val z: Int, val parallel: BigInteger)

    // Uniform word draws fill a page at 16-24 words (median 20). A count just under the median rarely meets
    // the fit limit, which keeps every word near its fair share instead of letting narrow words crowd the page.
    const val WORDS = 18

    // Placeable blocks: vanilla's largest world border (WorldBorder.MAX_SIZE = 59,999,968) and the build
    // height in data/enderkinesis/dimension_type/sselith_repertory.json. Change these with that file.
    const val MIN_XZ = -29_999_984
    const val MAX_XZ = 29_999_983
    const val MIN_Y = -128
    const val MAX_Y = 127

    private const val LINES = BookPaginator.LINES_PER_PAGE
    private const val WIDTH = BookPaginator.PAGE_WIDTH
    private const val GAP = BookPaginator.SPACE_WIDTH
    private const val NO_STATE = -1
    private const val MAX_COPRIME_STEPS = 10_000
    private const val MAX_EUCLID_FRAMES = 4_096
    private const val MAX_FILL_ATTEMPTS = 64

    // Both widths are even, which the language checkerboard in [pageAddress] relies on.
    private val SPAN_XZ: BigInteger = BigInteger.valueOf(MAX_XZ.toLong() - MIN_XZ + 1)
    private val ROW: BigInteger = SPAN_XZ * SPAN_XZ
    private val POSITIONS: BigInteger = ROW * BigInteger.valueOf(MAX_Y.toLong() - MIN_Y + 1)
    private val HALF_POSITIONS: BigInteger = POSITIONS.shiftRight(1)

    private class Libraries(val sselith: Library, val english: Library)

    // Shared by the client and the integrated server in one JVM, so each side counts its own hold.
    private val tablesLock = Any()
    private var tableUsers = 0
    private var tables: CompletableFuture<Libraries>? = null

    /** Start building the tables off-thread and keep them until every [acquire] has its [release]. */
    fun acquire() {
        synchronized(tablesLock) {
            tableUsers++
            if (tables == null) tables = CompletableFuture.supplyAsync(::buildLibraries)
        }
    }

    fun release() {
        synchronized(tablesLock) {
            tableUsers = (tableUsers - 1).coerceAtLeast(0)
            if (tableUsers == 0) tables = null
        }
    }

    /** The tables; only Sselith holds them, so use outside Sselith is a bug. */
    private fun libraries(): Libraries {
        val pending = synchronized(tablesLock) { tables }
        return checkNotNull(pending) { "Bookshelf page tables are only loaded while Sselith is" }.join()
    }

    private fun buildLibraries() = Libraries(
        Library(vocabulary(Language.SSELITH), 0x5E117BL),
        Library(vocabulary(Language.ENGLISH), 0xE4611L),
    )

    // Just the word lists, not the tables, so the Scribe's Desk can check words anywhere.
    private val vocabularies: Map<Language, Set<String>> by lazy {
        mapOf(Language.SSELITH to pageWords(Sselith.inflectedForms), Language.ENGLISH to pageWords(Sselith.englishWords))
    }

    /** The words a page of [language] can hold: plain lowercase dictionary forms no wider than one line. */
    fun vocabulary(language: Language): Set<String> = vocabularies.getValue(language)

    /** The one language whose vocabulary holds every word of [words], or null when none does. */
    fun languageOf(words: List<String>): Language? =
        Language.entries.firstOrNull { language -> words.all { it in vocabulary(language) } }

    fun isWord(word: String): Boolean = Language.entries.any { word in vocabulary(it) }

    private fun pageWords(forms: Collection<String>): Set<String> = forms.asSequence()
        .map { it.lowercase() }
        .filter { w -> w.isNotEmpty() && w.all { it in 'a'..'z' } && BookPaginator.stringWidth(w) <= WIDTH }
        .toSet()

    /**
     * A location whose page starts with [words]: the words, then filler drawn from [seed] until the page is full,
     * so one phrase and seed always lead to the same place. Null when the words share no language or no
     * filler fits in [MAX_FILL_ATTEMPTS] draws.
     */
    fun locationStartingWith(words: List<String>, seed: Long): Location? {
        if (words.isEmpty() || words.size > WORDS) return null
        val language = languageOf(words) ?: return null
        val lib = library(language)
        val rng = Random(seed)
        repeat(MAX_FILL_ATTEMPTS) {
            val page = words + List(WORDS - words.size) { lib.randomWord(rng) }
            lib.indexOfWords(page)?.let { return location(Address(language, it)) }
        }
        return null
    }

    private val sselith: Library get() = libraries().sselith
    private val english: Library get() = libraries().english

    private fun library(language: Language): Library = when (language) {
        Language.SSELITH -> sselith
        Language.ENGLISH -> english
    }

    fun total(language: Language): BigInteger = library(language).total

    /** One address per page of both libraries. */
    val addressCount: BigInteger get() = sselith.total + english.total

    /** The highest parallel that still holds a page at some position. */
    val lastParallel: BigInteger get() = (addressCount - BigInteger.ONE) / POSITIONS

    fun isPlaceable(x: Int, y: Int, z: Int): Boolean = x in MIN_XZ..MAX_XZ && z in MIN_XZ..MAX_XZ && y in MIN_Y..MAX_Y

    /** The page at ([x], [y], [z]) on [parallel]; blank past [addressCount] or outside the placeable space. */
    fun text(x: Int, y: Int, z: Int, parallel: BigInteger): String {
        require(parallel.signum() >= 0) { "Parallel must not be negative" }
        if (!isPlaceable(x, y, z)) return ""
        val (language, index) = pageAddress(parallel * POSITIONS + position(x, y, z)) ?: return ""
        return library(language).pageAt(index)
    }

    /** The one location of each library page that reads [page]; empty when no library holds it. */
    fun locations(page: String): List<Location> = locate(page).map(::location)

    /** The pages laid out from exactly [words], each with its one location. */
    fun exactPages(words: List<String>): List<Pair<String, Location>> = Language.entries.mapNotNull { language ->
        val lib = library(language)
        lib.indexOfWords(words)?.let { index -> lib.pageAt(index) to location(Address(language, index)) }
    }

    fun pageAt(language: Language, index: BigInteger): String = library(language).pageAt(index)

    /** Every address that holds [page]; empty when no library can produce it. */
    fun locate(page: String): List<Address> = Language.entries.mapNotNull { language ->
        library(language).locate(page)?.let { Address(language, it) }
    }

    /** Words of [phrase] that are in no library; empty when one library holds every word. */
    fun unknownWords(phrase: List<String>): List<String> {
        if (languageOf(phrase) != null) return emptyList()
        return phrase.filterNot(::isWord)
    }

    private fun position(x: Int, y: Int, z: Int): BigInteger =
        BigInteger.valueOf(y.toLong() - MIN_Y) * ROW +
            BigInteger.valueOf(z.toLong() - MIN_XZ) * SPAN_XZ + BigInteger.valueOf(x.toLong() - MIN_XZ)

    /** (y + z + parallel) mod 2 for the address pair `g / 2`; the x parity is g's own low bit. */
    private fun checkerParity(pair: BigInteger): Boolean {
        val (parallel, half) = pair.divideAndRemainder(HALF_POSITIONS)
        // An even span keeps (y, z) whole in half / (span / 2).
        val row = half / SPAN_XZ.shiftRight(1)
        val (y, z) = row.divideAndRemainder(SPAN_XZ)
        return y.testBit(0) != z.testBit(0) != parallel.testBit(0)
    }

    /** Pages per language in the alternating region: the smaller library's size. */
    private val pairedPages: BigInteger get() = sselith.total.min(english.total)

    /** The language whose pages outlast the other's and fill the addresses after the alternating region. */
    private val largerLanguage: Language
        get() = if (sselith.total >= english.total) Language.SSELITH else Language.ENGLISH

    /**
     * The page at address [g]. While the smaller library lasts, the language is the checkerboard
     * (x + y + z + parallel) mod 2, so every neighbor and successive parallel alternates; the larger library's
     * remainder follows.
     */
    private fun pageAddress(g: BigInteger): Address? {
        if (g >= addressCount) return null
        val paired = pairedPages
        val pair = g.shiftRight(1)
        if (pair >= paired) return Address(largerLanguage, library(largerLanguage).scramble(g - paired))
        val language = if (g.testBit(0) != checkerParity(pair)) Language.ENGLISH else Language.SSELITH
        return Address(language, library(language).scramble(pair))
    }

    private fun location(address: Address): Location {
        val pair = library(address.language).unscramble(address.index)
        val paired = pairedPages
        val g = when {
            pair >= paired -> pair + paired
            address.language == Language.ENGLISH -> pair.shiftLeft(1).let { if (checkerParity(pair)) it else it.setBit(0) }
            else -> pair.shiftLeft(1).let { if (checkerParity(pair)) it.setBit(0) else it }
        }
        val (parallel, position) = g.divideAndRemainder(POSITIONS)
        val (yRow, zx) = position.divideAndRemainder(ROW)
        val (zRow, x) = zx.divideAndRemainder(SPAN_XZ)
        return Location(x.toInt() + MIN_XZ, yRow.toInt() + MIN_Y, zRow.toInt() + MIN_XZ, parallel)
    }

    /** A search for positions whose page starts with [phrase], or null when no library has such a page. */
    fun search(phrase: List<String>): PhraseSearch? {
        val targets = Language.entries.mapNotNull { language ->
            val lib = library(language)
            lib.prefixRange(phrase)?.let { (start, size) -> PhraseSearch.Target(language, lib, start, size) }
        }
        return if (targets.isEmpty()) null else PhraseSearch(targets)
    }

    class PhraseSearch internal constructor(private val targets: List<Target>) {

        internal class Target(val language: Language, val lib: Library, val start: BigInteger, val size: BigInteger)

        /**
         * The lowest parallel of [from] or more at which ([x], [y], [z]) shows a page that starts with the
         * phrase, or null when none does before the languages stop alternating (around parallel 10^53).
         */
        fun lowestParallel(x: Int, y: Int, z: Int, from: BigInteger = BigInteger.ZERO): BigInteger? {
            require(from.signum() >= 0) { "Parallel must not be negative" }
            if (!isPlaceable(x, y, z)) return null
            return targets.mapNotNull { lowestParallel(x, y, z, from, it) }.minOrNull()
        }

        private fun lowestParallel(x: Int, y: Int, z: Int, from: BigInteger, target: Target): BigInteger? {
            val lib = target.lib
            // The language is (x + y + z + p) mod 2, so the parallel's parity is fixed: p = 2q + c.
            val odd = ((x + y + z) and 1) == 1
            val c = if (odd != (target.language == Language.ENGLISH)) BigInteger.ONE else BigInteger.ZERO
            val minQ = (from - c + BigInteger.ONE).shiftRight(1).max(BigInteger.ZERO)
            // pair = (p·POSITIONS + position) / 2 = q·POSITIONS + c·POSITIONS/2 + position/2: affine in q.
            val base = c * HALF_POSITIONS + position(x, y, z).shiftRight(1)
            val step = (lib.multiplier * POSITIONS).mod(lib.total)
            val k = firstHit(step, lib.scramble(base + minQ * POSITIONS), lib.total, target.start, target.size)
                ?: return null
            val q = minQ + k
            if (q * POSITIONS + base >= pairedPages) return null
            return q.shiftLeft(1) + c
        }
    }

    internal class Library(words: Set<String>, constantSeed: Long) {
        // Words grouped by pixel width. Sorted, so one mod version always produces the same library.
        private val buckets: List<List<String>>
        private val widths: IntArray
        private val bucketOfWidth = IntArray(WIDTH + 1) { NO_STATE }
        private val positionInBucket = HashMap<String, Int>()
        private val allWords: List<String>

        // ways[r][s] = sequences of exactly r more words that fit after layout state s.
        private val ways: Array<Array<BigInteger>>
        val total: BigInteger
        val multiplier: BigInteger
        private val inverse: BigInteger
        private val shift: BigInteger

        init {
            val byWidth = words.groupBy { BookPaginator.stringWidth(it) }.toSortedMap()
            check(byWidth.isNotEmpty()) { "Dictionary produced no page words" }
            buckets = byWidth.values.map { it.sorted() }
            allWords = buckets.flatten()
            widths = byWidth.keys.toIntArray()
            buckets.forEachIndexed { b, words ->
                bucketOfWidth[widths[b]] = b
                words.forEachIndexed { i, w -> positionInBucket[w] = i }
            }

            val states = LINES * (WIDTH + 1)
            ways = Array(WORDS + 1) { Array(states) { BigInteger.ZERO } }
            ways[0].fill(BigInteger.ONE)
            for (r in 1..WORDS) {
                for (s in 0 until states) {
                    var sum = BigInteger.ZERO
                    for (b in buckets.indices) {
                        val n = next(s, b)
                        if (n != NO_STATE) sum += ways[r - 1][n] * bucketSize(b)
                    }
                    ways[r][s] = sum
                }
            }
            total = ways[WORDS][0]
            check(total.signum() > 0) { "No $WORDS-word page fits" }

            // java.util.Random output is fixed by its spec, so every client derives the same constants.
            val rng = Random(constantSeed)
            multiplier = coprimeTo(total, rng)
            inverse = multiplier.modInverse(total)
            shift = randomBelow(total, rng)
        }

        fun randomWord(rng: Random): String = allWords[rng.nextInt(allWords.size)]

        /** Spreads consecutive addresses across the library, so neighbors read unrelated pages. */
        fun scramble(index: BigInteger): BigInteger = (multiplier * index + shift).mod(total)

        fun unscramble(index: BigInteger): BigInteger = (inverse * (index - shift)).mod(total)

        fun pageAt(index: BigInteger): String {
            require(index.signum() >= 0 && index < total) { "Page index out of range" }
            var rank = index
            var state = 0
            val sb = StringBuilder()
            for (left in WORDS - 1 downTo 0) {
                var placed = false
                for (b in buckets.indices) {
                    val n = next(state, b)
                    if (n == NO_STATE) continue
                    val block = ways[left][n] * bucketSize(b)
                    if (rank < block) {
                        val (word, rest) = rank.divideAndRemainder(ways[left][n])
                        if (sb.isNotEmpty()) sb.append(if (lineOf(n) == lineOf(state)) ' ' else '\n')
                        sb.append(buckets[b][word.toInt()])
                        rank = rest
                        state = n
                        placed = true
                        break
                    }
                    rank -= block
                }
                check(placed) { "Page rank table is inconsistent" }
            }
            return sb.toString()
        }

        fun locate(page: String): BigInteger? =
            // A text with the right words but other line breaks is not a page; the round trip rejects it.
            indexOfWords(page.split(' ', '\n'))?.takeIf { pageAt(it) == page }

        /** The index of the page that lays out [words], or null when they are not [WORDS] library words that fit. */
        fun indexOfWords(words: List<String>): BigInteger? =
            if (words.size == WORDS) prefixRange(words)?.first else null

        /** The (first index, count) of the pages that start with [words], or null when no page does. */
        fun prefixRange(words: List<String>): Pair<BigInteger, BigInteger>? {
            if (words.isEmpty() || words.size > WORDS) return null
            var rank = BigInteger.ZERO
            var state = 0
            for ((placed, word) in words.withIndex()) {
                val b = bucketOfWidth.getOrNull(BookPaginator.stringWidth(word)) ?: return null
                if (b == NO_STATE) return null
                val i = positionInBucket[word] ?: return null
                val left = WORDS - placed - 1
                for (e in 0 until b) {
                    val n = next(state, e)
                    if (n != NO_STATE) rank += ways[left][n] * bucketSize(e)
                }
                val n = next(state, b)
                if (n == NO_STATE) return null
                rank += ways[left][n] * BigInteger.valueOf(i.toLong())
                state = n
            }
            val size = ways[WORDS - words.size][state]
            return if (size.signum() == 0) null else rank to size
        }

        private fun bucketSize(b: Int): BigInteger = BigInteger.valueOf(buckets[b].size.toLong())

        /** Greedy word-wrap, the same as [BookPaginator]: the state after adding a word from bucket [b]. */
        private fun next(state: Int, b: Int): Int {
            val line = lineOf(state)
            val x = state % (WIDTH + 1)
            val w = widths[b]
            return when {
                x == 0 -> stateOf(line, w)
                x + GAP + w <= WIDTH -> stateOf(line, x + GAP + w)
                line + 1 < LINES -> stateOf(line + 1, w)
                else -> NO_STATE
            }
        }

        private fun lineOf(state: Int): Int = state / (WIDTH + 1)

        private fun stateOf(line: Int, x: Int): Int = line * (WIDTH + 1) + x
    }

    private fun randomBelow(bound: BigInteger, rng: Random): BigInteger = BigInteger(bound.bitLength(), rng).mod(bound)

    /** A multiplier that keeps `x -> a·x + b (mod modulus)` a bijection. */
    private fun coprimeTo(modulus: BigInteger, rng: Random): BigInteger {
        if (modulus <= BigInteger.ONE) return BigInteger.ONE
        var a = randomBelow(modulus, rng)
        var steps = 0
        while (a.gcd(modulus) != BigInteger.ONE) {
            check(++steps < MAX_COPRIME_STEPS) { "No multiplier coprime to the page count" }
            a = (a + BigInteger.ONE).mod(modulus)
        }
        return a
    }

    /** The lowest `k >= 0` with `(a·k + b) mod m` in `[start, start + size)`, or null when no k gives one. */
    internal fun firstHit(a: BigInteger, b: BigInteger, m: BigInteger, start: BigInteger, size: BigInteger): BigInteger? {
        require(size.signum() > 0) { "Empty target range" }
        if (size >= m) return BigInteger.ZERO
        val lo = (start - b).mod(m)
        val hi = (start + size - BigInteger.ONE - b).mod(m)
        // A range that wraps past m holds 0, and k = 0 maps to 0.
        return if (lo > hi) BigInteger.ZERO else firstMultipleIn(a, m, lo, hi)
    }

    /**
     * The lowest `x >= 0` with `l <= a·x mod m <= r`, for `0 <= l <= r < m`, or null when there is none (only
     * possible when a shares a factor with m). Each step swaps (a, m) for (m mod a, a) as in Euclid's algorithm,
     * so the frame count is bounded by the bit length of [m].
     */
    internal fun firstMultipleIn(a: BigInteger, m: BigInteger, l: BigInteger, r: BigInteger): BigInteger? {
        val frames = ArrayList<Array<BigInteger>>()
        var fa = a
        var fm = m
        var fl = l
        var fr = r
        var solution: BigInteger
        while (true) {
            if (fl.signum() == 0) { solution = BigInteger.ZERO; break }
            fa = fa.mod(fm)
            if (fa.signum() == 0) return null
            val direct = ceilDiv(fl, fa)
            if (fa * direct <= fr) { solution = direct; break }
            check(frames.size < MAX_EUCLID_FRAMES) { "Range search did not converge" }
            frames.add(arrayOf(fa, fm, fl, fr))
            // a·x - m·y in [l, r] means m·y mod a in [-r mod a, -l mod a]; no wrap, as no multiple of a is in [l, r].
            val nextA = fm.mod(fa)
            val nextL = (-fr).mod(fa)
            val nextR = (-fl).mod(fa)
            fm = fa
            fa = nextA
            fl = nextL
            fr = nextR
        }
        for (frame in frames.asReversed()) {
            val (pa, pm, pl, pr) = frame
            val x = ceilDiv(pl + pm * solution, pa)
            if (pa * x - pm * solution > pr) return null
            solution = x
        }
        return solution
    }

    private fun ceilDiv(n: BigInteger, d: BigInteger): BigInteger = (n + d - BigInteger.ONE) / d
}
