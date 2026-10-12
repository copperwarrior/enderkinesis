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

    /** Usable text width of a book page, in pixels. */
    const val PAGE_WIDTH = 114

    /** Lines that fit in a page's text area before it clips. */
    const val LINES_PER_PAGE = 14

    /** Vanilla's hard cap on pages in a written / writable book. */
    const val MAX_PAGES = 100

    internal const val SPACE_WIDTH = 4

    const val FORMAT_MARK = '\u00a7'

    /**
     * Re-flow [text] (newlines are honoured as hard breaks — headings,
     * list items, blank lines) into a list of page strings, each at most
     * [LINES_PER_PAGE] lines and [PAGE_WIDTH] px wide. Blank text yields
     * an empty list.
     */
    fun paginate(text: String, charWidth: (Char) -> Int = ::serifCharWidth): List<String> {
        val lines = ArrayList<String>()
        for (rawLine in text.split('\n')) wrapLine(rawLine, lines, charWidth)

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

    /**
     * The inverse of [paginate]: the text of [pages] without the line wraps, as one string for each section.
     * A page with a blank second line starts a section, because a section heading has a blank line after it.
     */
    fun unwrap(pages: List<String>): List<String> {
        val sections = ArrayList<StringBuilder>()
        for (page in pages) {
            val lines = page.split('\n')
            if (sections.isEmpty() || lines.size > 1 && lines[1].isBlank()) sections.add(StringBuilder())
            for (line in lines) appendUnwrapped(sections.last(), line)
        }
        return sections.map { it.toString() }
    }

    private fun appendUnwrapped(text: StringBuilder, line: String) {
        when {
            line.isBlank() -> if (text.isNotEmpty() && !text.endsWith('\n')) text.append("\n\n")
            // A line ends with a hyphen only where [pieces] divided a token.
            text.isEmpty() || text.endsWith('\n') || text.endsWith('-') -> text.append(line)
            else -> text.append(' ').append(line)
        }
    }

    /** Greedy word-wrap of one source line into [out]. A blank source
     *  line is preserved as one blank line (paragraph spacing). */
    private fun wrapLine(line: String, out: MutableList<String>, charWidth: (Char) -> Int) {
        if (line.isBlank()) { out.add(""); return }
        val current = StringBuilder()
        var currentWidth = 0
        // Each token after the first has one space before it, so an empty token is a repeated space.
        var spaces = 0
        for ((index, word) in line.split(' ').withIndex()) {
            if (index > 0) spaces++
            if (word.isEmpty()) continue
            for (piece in pieces(word, charWidth)) {
                val wWidth = visibleWidth(piece, charWidth)
                if (currentWidth + spaces * SPACE_WIDTH + wWidth > PAGE_WIDTH) {
                    // The spaces fill the rest of this line and one space is the break. The others go to the next line.
                    val onNextLine = spaces - (PAGE_WIDTH - currentWidth) / SPACE_WIDTH - 1
                    out.add(current.toString())
                    current.setLength(0); currentWidth = 0
                    spaces = onNextLine.coerceIn(0, (PAGE_WIDTH - wWidth) / SPACE_WIDTH)
                }
                repeat(spaces) { current.append(' ') }
                current.append(piece)
                currentWidth += spaces * SPACE_WIDTH + wWidth
                spaces = 0
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
    }

    /** [word], or its parts when it is wider than a line. A part ends after a hyphen where possible. */
    private fun pieces(word: String, charWidth: (Char) -> Int): List<String> {
        if (visibleWidth(word, charWidth) <= PAGE_WIDTH) return listOf(word)
        val out = ArrayList<String>()
        var start = 0
        while (start < word.length) {
            val hyphen = word.indexOf('-', start)
            val end = if (hyphen < 0) word.length else hyphen + 1
            val piece = word.substring(start, end)
            if (visibleWidth(piece, charWidth) <= PAGE_WIDTH) out.add(piece) else hardSplit(piece, out, charWidth)
            start = end
        }
        return out
    }

    /** The width of [word] without its `§` format codes, which the book screen does not draw. */
    private fun visibleWidth(word: String, charWidth: (Char) -> Int): Int {
        var width = 0
        var i = 0
        while (i < word.length) {
            if (word[i] == FORMAT_MARK && i + 1 < word.length) {
                i += 2
                continue
            }
            width += charWidth(word[i])
            i++
        }
        return width
    }

    /** Break an over-long token into page-width character chunks. */
    private fun hardSplit(word: String, out: MutableList<String>, charWidth: (Char) -> Int) {
        val cur = StringBuilder()
        var w = 0
        for ((i, c) in word.withIndex()) {
            val isCode = c == FORMAT_MARK && i + 1 < word.length || i > 0 && word[i - 1] == FORMAT_MARK
            val cw = if (isCode) 0 else charWidth(c)
            if (w + cw > PAGE_WIDTH && cur.isNotEmpty()) {
                out.add(cur.toString()); cur.setLength(0); w = 0
            }
            cur.append(c); w += cw
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
    }

    /** Pixel width of a rendered line in the serif font (sum of glyph
     *  advances). Exposed for tests that assert the page-fit invariant. */
    internal fun stringWidth(s: String): Int = s.sumOf(::serifCharWidth)

    /** Default-font advance width of [c] in pixels (glyph width + 1 px
     *  spacing), matching vanilla's ASCII layout. Unknown glyphs fall
     *  back to the common 6 px advance. */
    private fun defaultCharWidth(c: Char): Int = when (c) {
        '!', ',', '.', ':', ';', '|', 'i' -> 2
        '\'', 'l', '`' -> 3
        ' ', 't', 'I', '[', ']' -> 4
        '"', '(', ')', '*', '<', '>', '{', '}', 'f', 'k' -> 5
        '@', '~' -> 7
        '\u2014' -> 9
        else -> 6
    }

    /**
     * Advance widths of `textures/font/serif.png` (glyph width + 1). Keep this table equal to the sheet.
     * No serif glyph is narrower than its default glyph, so a page that fits in the serif font also fits
     * in the default font. A book has one pagination for the two fonts, because a Sselith lectern shows
     * it in serif and the hand shows it in the default font.
     */
    fun serifCharWidth(c: Char): Int = when (c) {
        'i' -> 2
        'l' -> 3
        'I', 'f', 'k', 't' -> 5
        '4', '@', 'B', 'D', 'E', 'F', 'J', 'L', 'P', 'm', 'n' -> 7
        'A', 'H', 'K', 'M', 'N', 'R', 'W' -> 8
        // The serif font has digits, letters and `@` only. All other characters come from the default font.
        else -> if (c.isLetterOrDigit() && c <= 'z') 6 else defaultCharWidth(c)
    }

    /** Advance widths of `textures/font/sloppy.png` (glyph width + 1). Keep this table equal to the sheet. */
    fun sloppyCharWidth(c: Char): Int = when (c) {
        '!', ',', ':', ';', 'i', '|' -> 2
        '\'', '.' -> 3
        '"', '(', ')', '*', '[', '`', '{', '}' -> 4
        '>', 'I', 'S', ']', 'b', 'f', 'k', 'p', 't' -> 5
        '&', '-', '5', '=', '@', 'H', 'V', 'X', 'Y', 'Z', 'x', 'y', 'z' -> 7
        '4', '~' -> 8
        // The sloppy font has ASCII glyphs only. All other characters come from the default font.
        else -> if (c in '!'..'~') 6 else defaultCharWidth(c)
    }
}
