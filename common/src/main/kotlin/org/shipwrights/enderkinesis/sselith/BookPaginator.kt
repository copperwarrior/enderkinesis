package org.shipwrights.enderkinesis.sselith

/**
 * Splits a block of plain text into vanilla book pages.
 *
 * A written-book page renders a fixed area — about **114 px wide** and
 * **14 lines tall** — and the book screen does *not* scroll within a
 * page, so text that overflows is simply clipped. When the Cataloger
 * re-writes a lectern's book in Sselith the translated words are a
 * different length than the English source, so the original page breaks
 * no longer fit; the whole text has to be re-flowed.
 *
 * Vanilla's real wrapper ([net.minecraft.client.gui.Font]/`StringSplitter`)
 * is client-only and needs GL-loaded glyph metrics, so it can't run on
 * the server where the Cataloger lives. Instead we reproduce the default
 * bitmap font's glyph **advance widths** (the same integer pixel widths
 * vanilla lays out with for ASCII) and greedily word-wrap to the page
 * width, then pack [LINES_PER_PAGE] lines per page. For Sselith — almost
 * entirely lowercase `a–z`, hyphens and spaces — this matches the
 * client's wrapping closely.
 */
object BookPaginator {

    /** Usable text width of a book page, in default-font pixels. */
    const val PAGE_WIDTH = 114

    /** Lines that fit in a page's text area before it clips. */
    const val LINES_PER_PAGE = 14

    /** Vanilla's hard cap on pages in a written / writable book. */
    const val MAX_PAGES = 100

    private const val SPACE_WIDTH = 4

    /**
     * Re-flow [text] (newlines are honoured as hard breaks — headings,
     * list items, blank lines) into a list of page strings, each at most
     * [LINES_PER_PAGE] lines and [PAGE_WIDTH] px wide. Blank text yields
     * an empty list.
     */
    fun paginate(text: String): List<String> {
        val lines = ArrayList<String>()
        for (rawLine in text.split('\n')) wrapLine(rawLine, lines)

        val pages = ArrayList<String>()
        var i = 0
        while (i < lines.size && pages.size < MAX_PAGES) {
            // Never start a page with blank padding lines.
            while (i < lines.size && lines[i].isBlank()) i++
            val pageLines = ArrayList<String>(LINES_PER_PAGE)
            while (i < lines.size && pageLines.size < LINES_PER_PAGE) {
                pageLines.add(lines[i]); i++
            }
            // Trim trailing blank lines so the page ends cleanly.
            while (pageLines.isNotEmpty() && pageLines.last().isBlank()) {
                pageLines.removeAt(pageLines.size - 1)
            }
            if (pageLines.isNotEmpty()) pages.add(pageLines.joinToString("\n"))
        }
        return pages
    }

    /** Greedy word-wrap of one source line into [out]. A blank source
     *  line is preserved as one blank line (paragraph spacing). */
    private fun wrapLine(line: String, out: MutableList<String>) {
        if (line.isBlank()) { out.add(""); return }
        val current = StringBuilder()
        var currentWidth = 0
        for (word in line.split(' ')) {
            if (word.isEmpty()) continue
            val wWidth = stringWidth(word)
            if (wWidth > PAGE_WIDTH) {
                // A single token longer than a whole line: flush, then
                // hard-break it on character boundaries so it still fits.
                if (current.isNotEmpty()) {
                    out.add(current.toString()); current.setLength(0); currentWidth = 0
                }
                hardSplit(word, out)
                continue
            }
            val gap = if (current.isEmpty()) 0 else SPACE_WIDTH
            if (currentWidth + gap + wWidth <= PAGE_WIDTH) {
                if (current.isNotEmpty()) { current.append(' '); currentWidth += SPACE_WIDTH }
                current.append(word); currentWidth += wWidth
            } else {
                out.add(current.toString())
                current.setLength(0); current.append(word); currentWidth = wWidth
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
    }

    /** Break an over-long token into page-width character chunks. */
    private fun hardSplit(word: String, out: MutableList<String>) {
        val cur = StringBuilder()
        var w = 0
        for (c in word) {
            val cw = charWidth(c)
            if (w + cw > PAGE_WIDTH && cur.isNotEmpty()) {
                out.add(cur.toString()); cur.setLength(0); w = 0
            }
            cur.append(c); w += cw
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
    }

    /** Pixel width of a rendered line in the default font (sum of glyph
     *  advances). Exposed for tests that assert the page-fit invariant. */
    internal fun stringWidth(s: String): Int = s.sumOf { charWidth(it) }

    /** Default-font advance width of [c] in pixels (glyph width + 1 px
     *  spacing), matching vanilla's ASCII layout. Unknown glyphs fall
     *  back to the common 6 px advance. */
    private fun charWidth(c: Char): Int = when (c) {
        '!', ',', '.', ':', ';', '|', 'i' -> 2
        '\'', 'l', '`' -> 3
        ' ', 't', 'I', '[', ']' -> 4
        '"', '(', ')', '*', '<', '>', '{', '}', 'f', 'k' -> 5
        '@', '~' -> 7
        else -> 6
    }
}
