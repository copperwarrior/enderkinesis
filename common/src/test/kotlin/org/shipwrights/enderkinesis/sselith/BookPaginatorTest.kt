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
    fun `translated Sselith prose paginates into well-formed pages`() {
        val english = ("The keeper of the great library files every book in its proper place. " +
            "Knowledge is the only power that endures, and the rule of the anchor binds all. ").repeat(8)
        val pages = BookPaginator.paginate(Sselith.translate(english))
        pages.size shouldBeGreaterThan 0
        assertWellFormed(pages)
    }
}
