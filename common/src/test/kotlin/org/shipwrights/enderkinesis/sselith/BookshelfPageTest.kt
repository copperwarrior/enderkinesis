package org.shipwrights.enderkinesis.sselith

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.math.BigInteger
import java.util.Random
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.shipwrights.enderkinesis.sselith.BookshelfPage.Address
import org.shipwrights.enderkinesis.sselith.BookshelfPage.Language
import org.shipwrights.enderkinesis.sselith.BookshelfPage.Location

class BookshelfPageTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun holdTables() = BookshelfPage.acquire()

        @JvmStatic
        @AfterAll
        fun releaseTables() = BookshelfPage.release()
    }

    private val positions = (0 until 12).map { Triple(it, 64, -it) } + listOf(
        Triple(BookshelfPage.MIN_XZ, BookshelfPage.MIN_Y, BookshelfPage.MIN_XZ),
        Triple(BookshelfPage.MAX_XZ, BookshelfPage.MAX_Y, BookshelfPage.MAX_XZ),
        Triple(BookshelfPage.MAX_XZ, BookshelfPage.MIN_Y, BookshelfPage.MIN_XZ),
        Triple(-1, 0, 1),
    )
    private val parallels = listOf(BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO, BigInteger.TEN.pow(40))
    private val here = Triple(1203, 40, -877)

    private fun firstWord(page: String) = page.split(' ', '\n').first()

    @Test
    fun `every page fits on one book page`() {
        for ((x, y, z) in positions) for (parallel in parallels) {
            val page = BookshelfPage.text(x, y, z, parallel)
            val lines = page.split('\n')
            lines.size shouldBeLessThanOrEqual BookPaginator.LINES_PER_PAGE
            for (line in lines) BookPaginator.stringWidth(line) shouldBeLessThanOrEqual BookPaginator.PAGE_WIDTH
            page.split(' ', '\n').size shouldBe BookshelfPage.WORDS
        }
    }

    @Test
    fun `locate inverts pageAt in both libraries`() {
        for (language in Language.entries) {
            val total = BookshelfPage.total(language)
            val indices = listOf(BigInteger.ZERO, BigInteger.ONE, total / BigInteger.TWO, total - BigInteger.ONE)
            for (index in indices) {
                BookshelfPage.locate(BookshelfPage.pageAt(language, index)) shouldContain Address(language, index)
            }
        }
    }

    @Test
    fun `every location holds a page whose only location is that one`() {
        for ((x, y, z) in positions) for (parallel in parallels) {
            val page = BookshelfPage.text(x, y, z, parallel)
            BookshelfPage.locations(page) shouldBe listOf(Location(x, y, z, parallel))
        }
    }

    @Test
    fun `every library page has exactly one placeable location, which shows it`() {
        val rng = Random(5)
        for (language in Language.entries) {
            val total = BookshelfPage.total(language)
            repeat(50) {
                val index = BigInteger(total.bitLength(), rng).mod(total)
                val page = BookshelfPage.pageAt(language, index)
                val found = BookshelfPage.locations(page)
                found.size shouldBe 1
                val (x, y, z, parallel) = found[0]
                BookshelfPage.isPlaceable(x, y, z) shouldBe true
                BookshelfPage.text(x, y, z, parallel) shouldBe page
            }
        }
    }

    @Test
    fun `parallels past the last and positions outside the placeable space are blank`() {
        BookshelfPage.text(0, 0, 0, BookshelfPage.lastParallel + BigInteger.ONE) shouldBe ""
        BookshelfPage.text(0, BookshelfPage.MAX_Y + 1, 0, BigInteger.ZERO) shouldBe ""
        BookshelfPage.text(BookshelfPage.MAX_XZ + 1, 0, 0, BigInteger.ZERO) shouldBe ""
    }

    @Test
    fun `firstMultipleIn matches brute force`() {
        val rng = Random(3)
        val big = { n: Int -> BigInteger.valueOf(n.toLong()) }
        repeat(4_000) {
            val m = 2 + rng.nextInt(500)
            val a = rng.nextInt(m)
            val l = rng.nextInt(m)
            val r = l + rng.nextInt(m - l)
            val expected = (0 until m).firstOrNull { (a.toLong() * it % m) in l..r }?.let(big)
            BookshelfPage.firstMultipleIn(big(a), big(m), big(l), big(r)) shouldBe expected
        }
    }

    @Test
    fun `search finds the lowest parallel whose page starts with the phrase`() {
        val (x, y, z) = here
        val search = BookshelfPage.search(listOf("salt")).shouldNotBeNull()
        val lowest = search.lowestParallel(x, y, z).shouldNotBeNull()
        firstWord(BookshelfPage.text(x, y, z, lowest)) shouldBe "salt"
        for (p in 0 until lowest.toInt()) {
            firstWord(BookshelfPage.text(x, y, z, BigInteger.valueOf(p.toLong()))) shouldNotBe "salt"
        }

        val twoWords = BookshelfPage.search(listOf("zhol", "olm")).shouldNotBeNull()
        val page = BookshelfPage.text(x, y, z, twoWords.lowestParallel(x, y, z).shouldNotBeNull())
        page.split(' ', '\n').take(2) shouldBe listOf("zhol", "olm")
    }

    @Test
    fun `search starts from the given parallel`() {
        val (x, y, z) = here
        val search = BookshelfPage.search(listOf("salt")).shouldNotBeNull()
        val first = search.lowestParallel(x, y, z).shouldNotBeNull()
        for (from in listOf(first, first + BigInteger.ONE, first + BigInteger.TWO, BigInteger.TEN.pow(30))) {
            val found = search.lowestParallel(x, y, z, from).shouldNotBeNull()
            (found >= from) shouldBe true
            firstWord(BookshelfPage.text(x, y, z, found)) shouldBe "salt"
            if (from <= first) found shouldBe first
        }
        val next = search.lowestParallel(x, y, z, first + BigInteger.ONE).shouldNotBeNull()
        var p = first + BigInteger.ONE
        while (p < next) {
            firstWord(BookshelfPage.text(x, y, z, p)) shouldNotBe "salt"
            p += BigInteger.ONE
        }
    }

    @Test
    fun `a written phrase leads to one fixed location whose page starts with it`() {
        for (phrase in listOf(listOf("salt"), listOf("zhol", "olm", "isk"), listOf("big", "salt", "duel"))) {
            val seed = phrase.joinToString(" ").hashCode().toLong()
            val location = BookshelfPage.locationStartingWith(phrase, seed).shouldNotBeNull()
            BookshelfPage.locationStartingWith(phrase, seed) shouldBe location
            val (x, y, z, parallel) = location
            BookshelfPage.isPlaceable(x, y, z) shouldBe true
            BookshelfPage.text(x, y, z, parallel).split(' ', '\n').take(phrase.size) shouldBe phrase
        }
        BookshelfPage.locationStartingWith(listOf("salt", "zhol"), 1L).shouldBeNull()
    }

    @Test
    fun `vocabulary checks need no tables and keep languages apart`() {
        BookshelfPage.isWord("salt") shouldBe true
        BookshelfPage.isWord("zhol") shouldBe true
        BookshelfPage.isWord("hello-world") shouldBe false
        BookshelfPage.languageOf(listOf("salt", "duel")) shouldBe Language.ENGLISH
        BookshelfPage.languageOf(listOf("zhol", "olm")) shouldBe Language.SSELITH
        BookshelfPage.languageOf(listOf("salt", "zhol")).shouldBeNull()
    }

    @Test
    fun `search rejects phrases no page starts with`() {
        BookshelfPage.search(listOf("hello-world")).shouldBeNull()
        BookshelfPage.search(List(BookshelfPage.WORDS + 1) { "salt" }).shouldBeNull()
    }

    @Test
    fun `every neighbor and the next parallel use the other language`() {
        fun language(x: Int, y: Int, z: Int, p: Int) =
            BookshelfPage.locate(BookshelfPage.text(x, y, z, BigInteger.valueOf(p.toLong()))).first().language
        val origin = language(4, 10, 4, 0)
        for (other in listOf(language(5, 10, 4, 0), language(4, 11, 4, 0), language(4, 10, 5, 0), language(4, 10, 4, 1))) {
            other shouldNotBe origin
        }
        BookshelfPage.text(1, 0, 0, BigInteger.ZERO) shouldNotBe BookshelfPage.text(3, 0, 0, BigInteger.ZERO)
        BookshelfPage.text(1, 0, 0, BigInteger.ZERO) shouldNotBe BookshelfPage.text(1, 0, 0, BigInteger.TWO)
    }

    @Test
    fun `text outside the library is rejected`() {
        BookshelfPage.locate("").shouldBeEmpty()
        BookshelfPage.locate("hello-world").shouldBeEmpty()
        val page = BookshelfPage.text(7, 0, 7, BigInteger.ZERO)
        BookshelfPage.locate(page.replace('\n', ' ')).shouldBeEmpty()
    }
}
