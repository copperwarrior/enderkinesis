package org.shipwrights.enderkinesis.sselith

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BookPaginatorTest {

    /** The core invariant: no produced line overflows the page width and
     *  no page exceeds the line cap. */
    private fun assertWellFormed(pages: List<String>) {
        for (page in pages) {
            val lines = page.split('\n')
            lines.size shouldBeLessThanOrEqual BookPaginator.LINES_PER_PAGE
            for (line in lines) {
                BookPaginator.stringWidth(line) shouldBeLessThanOrEqual BookPaginator.PAGE_WIDTH
            }
        }
    }

    @Test
    fun `blank text paginates to nothing`() {
        BookPaginator.paginate("") shouldHaveSize 0
        BookPaginator.paginate("   \n\n  ") shouldHaveSize 0
    }

    @Test
    fun `a long paragraph wraps and stays within page bounds`() {
        val text = ("zhaelk regarnkh leamorgh serkhegh maerkharn ".repeat(60)).trim()
        val pages = BookPaginator.paginate(text)
        pages.size shouldBeGreaterThan 1
        assertWellFormed(pages)
    }

    @Test
    fun `explicit newlines are preserved as hard breaks`() {
        val pages = BookPaginator.paginate("Zhaelk\nRegarnkh\nLeamorgh")
        pages shouldHaveSize 1
        pages[0].split('\n') shouldHaveSize 3
        assertWellFormed(pages)
    }

    @Test
    fun `a single over-long token is hard-split to fit`() {
        val giant = "z".repeat(200)
        val pages = BookPaginator.paginate(giant)
        assertWellFormed(pages)
    }

    @Test
    fun `an over-long compound breaks after a hyphen and the line continues`() {
        val pages = BookPaginator.paginate("za vraestmorocht-schest-kelkargh-skarn-moroch, za")
        assertWellFormed(pages)
        val lines = pages.single().split('\n')
        lines.dropLast(1).forEach { it.last() shouldBe '-' }
        lines.last() shouldBe "skarn-moroch, za"
    }

    @Test
    fun `a full page comes before a short last page`() {
        val text = (1..BookPaginator.LINES_PER_PAGE * 2 + 2).joinToString("\n") { "zhaelk$it" }
        val pages = BookPaginator.paginate(text)
        assertWellFormed(pages)
        pages.map { it.split('\n').size } shouldBe listOf(14, 14, 2)
        pages.joinToString("\n") shouldBe text
    }

    @Test
    fun `unwrap gives back the text of each section`() {
        val first = "Zhaelk Tesk\n\n" + "za vraestmorocht-schest-kelkargh-skarn-moroch, okht zhol velkt ".repeat(12).trim()
        val second = "Regarnkh\n\n" + "zhaelk regarnkh leamorgh ".repeat(40).trim()
        val pages = BookPaginator.paginate(first) + BookPaginator.paginate(second)
        BookPaginator.unwrap(pages) shouldBe listOf(first, second)
    }

    @Test
    fun `an em dash has its default font width`() {
        BookPaginator.serifCharWidth('\u2014') shouldBe 9
    }

    @Test
    fun `translated Sselith prose paginates into well-formed pages`() {
        val english = ("The keeper of the great library files every book in its proper place. " +
            "Knowledge is the only power that endures, and the rule of the anchor binds all. ").repeat(8)
        val pages = BookPaginator.paginate(Sselith.translate(english))
        pages.size shouldBeGreaterThan 0
        assertWellFormed(pages)
    }
}
