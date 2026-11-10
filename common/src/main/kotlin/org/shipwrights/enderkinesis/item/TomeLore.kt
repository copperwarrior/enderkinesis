package org.shipwrights.enderkinesis.item

import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.sselith.BookPaginator
import org.shipwrights.enderkinesis.sselith.Sselith
import java.util.Random

/**
 * Helpers for building the transient `WRITTEN_BOOK` stacks tomes hand back from
 * [TomeItem.writtenBook]. Two paths:
 *  - [parseMarkdown] takes a bundled `.md` resource and produces one page per `## Section`, in
 *    order — no auto-pagination, no bold heading; [parsePaginatedMarkdown] also splits a section
 *    that is longer than one page; `# title` and pre-section front-matter are
 *    dropped (the title lives in the book NBT, not on a page). The format the Almanac of
 *    Everywhere's `almanackal_awakening.md` was written against.
 *  - [bake] wraps a list of already-prepared chat-component pages into the throw-away signed book
 *    with the given title/author/generation. Vanilla won't paginate a written book without
 *    title/author, even though the lectern doesn't display them.
 *  - [blank] is a shortcut for tomes that don't have inscribed lore yet — one short page.
 *
 * Nothing in here ever mutates a real tome stack; everything is built fresh for the open view.
 */
object TomeLore {

    private const val DEFAULT_GENERATION = 3   // "tattered" — neutral, harmless

    val SLOPPY_STYLE: Style = Style.EMPTY.withFont(EnderkinesisMod.id("sloppy"))
    val SERIF_STYLE: Style = Style.EMPTY.withFont(EnderkinesisMod.id("serif"))

    /** Book-tag boolean: [BookPaginator] made the page breaks, so the pages fit in the serif font. */
    const val SERIF_PAGES_FLAG = "EnderkinesisSselith"

    /** True for a book stack, or the written book of a [TomeItem] stack, that has [SERIF_PAGES_FLAG]. */
    fun hasSerifPages(stack: ItemStack): Boolean {
        val book = (stack.item as? TomeItem)?.writtenBook(stack) ?: stack
        return book.tag?.getBoolean(SERIF_PAGES_FLAG) == true
    }

    /** A single-page book reading "the pages are blank" — placeholder for tomes without lore yet. */
    fun blank(title: String, author: String = "Unknown"): ItemStack =
        bake(title, author, listOf(Component.literal("(the pages are blank)")))

    /** Build a written-book stack from a Sselith-translated markdown source in the bundled
     *  `assets/enderkinesis/texts/sselith/books/` directory. The source filename is
     *  `<bookName>.md` (English at build time; Sselith-translated by `translateSselith` in
     *  the shipped JAR). Pages are parsed once and cached per [bookName]; each call returns
     *  a fresh stack wrapping the same cached page list. */
    fun fromSselithBook(title: String, author: String, bookName: String): ItemStack {
        val pages = SSELITH_BOOK_CACHE.getOrPut(bookName) { parsePaginatedMarkdown(sselithResource(bookName)) }
        return bake(title, author, pages, serifPages = true).also { it.orCreateTag.putString(SOURCE_KEY, bookName) }
    }

    private fun sselithResource(bookName: String) = "/assets/enderkinesis/texts/sselith/books/$bookName.md"

    private fun englishResource(bookName: String) = "/assets/enderkinesis/texts/english/books/$bookName.md"

    private fun hasEnglishSource(bookName: String) = TomeLore::class.java.getResource(englishResource(bookName)) != null

    /** Book-tag string: the Sselith source that the pages came from. */
    const val SOURCE_KEY = "EnderkinesisSource"

    /** Book-tag boolean: [fromSselithLoreBook] flowed the source into pages without its line breaks. */
    private const val FLOWED_KEY = "EnderkinesisFlowed"

    fun sselithSource(book: ItemStack): String? = book.tag?.getString(SOURCE_KEY)?.ifEmpty { null }

    /**
     * [book] after the Scroll of Unravelling passes stored on [tome]. The passes run on the source text before
     * pagination, so sketch tags and `[page]` lines stay, and the same seed gives the same pages on client and server.
     */
    fun unravelled(book: ItemStack, tome: ItemStack): ItemStack {
        val passes = tome.unravelPasses
        if (passes <= 0) return book
        val source = sselithSource(book) ?: return book
        val seed = tome.unravelSeed
        val key = UnravelKey(source, passes, seed)
        val pages = if (passes >= MAX_UNRAVEL_PASSES && hasEnglishSource(source)) {
            ENGLISH_BOOK_CACHE.getOrPut(source) { parsePaginatedMarkdown(englishResource(source)) }
        } else {
            synchronized(UNRAVELLED) { UNRAVELLED[key] } ?: parsePaginatedMarkdown(sselithResource(source)) { section, text ->
                unravelText(text, passes, seed, section)
            }.also { synchronized(UNRAVELLED) { UNRAVELLED[key] = it } }
        }
        val tag = checkNotNull(book.tag)
        return bake(tag.getString("title"), tag.getString("author"), pages, serifPages = true)
            .also { it.orCreateTag.putString(SOURCE_KEY, source) }
    }

    private fun unravelText(text: String, passes: Int, seed: Long, section: Int): String {
        var out = text
        for (step in 0 until passes) {
            // The last allowed pass reads every word, so a tome at the cap is all English.
            val fraction = if (step == MAX_UNRAVEL_PASSES - 1) 1.0 else ScrollOfUnravelling.HOLY_NUMBER_FRACTION
            out = Sselith.translateToEnglish(out, fraction, Random(31 * (31 * seed + step) + section))
        }
        return out
    }

    private data class UnravelKey(val source: String, val passes: Int, val seed: Long)

    private const val UNRAVELLED_LIMIT = 32

    // Client and server threads both read it. pageCount runs often, and each miss reads the whole book again.
    private val UNRAVELLED = object : LinkedHashMap<UnravelKey, List<Component>>(UNRAVELLED_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UnravelKey, List<Component>>) = size > UNRAVELLED_LIMIT
    }

    /** Cache of parsed Sselith books — the parsed page list is immutable once loaded. */
    private val SSELITH_BOOK_CACHE =
        java.util.concurrent.ConcurrentHashMap<String, List<Component>>()

    private val ENGLISH_BOOK_CACHE =
        java.util.concurrent.ConcurrentHashMap<String, List<Component>>()

    /**
     * The English source pages of a stored-page book, in the layout its Sselith pages had.
     * Null when the book has no source tag or the English text is not bundled.
     */
    fun englishStoredPages(book: ItemStack): List<String>? {
        val source = sselithSource(book) ?: return null
        if (!hasEnglishSource(source)) return null
        return if (book.tag?.getBoolean(FLOWED_KEY) == true) {
            paginateSerifText(englishResource(source))
        } else {
            parsePaginatedMarkdown(englishResource(source)).map { it.string }
        }
    }

    /** Build a written-book stack from a plain-text source in `assets/enderkinesis/texts/loot_books/`.
     *  These sources have no `## ` sections, so [paginatePlainText] cuts the pages. */
    fun fromLootBook(title: String, author: String, bookName: String): ItemStack {
        val pages = LOOT_BOOK_CACHE.getOrPut(bookName) {
            paginatePlainText("/assets/enderkinesis/texts/loot_books/$bookName.md")
        }
        return bake(title, author, pages)
    }

    /** A [SselithBookItem] stack from a Sselith-translated source that has no `## ` sections. */
    fun fromSselithLoreBook(title: String, author: String, bookName: String): ItemStack {
        val pages = SSELITH_LORE_CACHE.getOrPut(bookName) {
            paginateSerifText("/assets/enderkinesis/texts/sselith/books/$bookName.md")
        }
        return SselithBookItem.of(title, author, pages).also {
            val tag = it.orCreateTag
            tag.putString(SOURCE_KEY, bookName)
            tag.putBoolean(FLOWED_KEY, true)
        }
    }

    private val SSELITH_LORE_CACHE =
        java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    private fun paginateSerifText(resource: String): List<String> {
        val text = TomeLore::class.java.getResourceAsStream(resource)?.bufferedReader()?.use { it.readText() }
            ?: return listOf("(the pages are blank)")
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return BookPaginator.paginate(words.joinToString(" ")).ifEmpty { listOf("(the pages are blank)") }
    }

    private val LOOT_BOOK_CACHE =
        java.util.concurrent.ConcurrentHashMap<String, List<Component>>()

    // A vanilla book page shows 14 lines of 114 px. Text past that is cut off, not wrapped to a new page.
    private const val LOOT_PAGE_CHARS = 220

    private fun paginatePlainText(resource: String): List<Component> {
        val text = TomeLore::class.java.getResourceAsStream(resource)?.bufferedReader()?.use { it.readText() }
            ?: return listOf(Component.literal("(the pages are blank)"))
        val pages = mutableListOf<Component>()
        val page = StringBuilder()
        for (word in text.split(Regex("\\s+"))) {
            if (word.isEmpty()) continue
            if (page.isNotEmpty() && page.length + 1 + word.length > LOOT_PAGE_CHARS) {
                pages.add(Component.literal(page.toString()))
                page.setLength(0)
            }
            if (page.isNotEmpty()) page.append(' ')
            page.append(word)
        }
        if (page.isNotEmpty()) pages.add(Component.literal(page.toString()))
        return pages.ifEmpty { listOf(Component.literal("(the pages are blank)")) }
    }

    /** Build a transient signed [Items.WRITTEN_BOOK] stack carrying [pages]. */
    fun bake(
        title: String,
        author: String,
        pages: List<Component>,
        generation: Int = DEFAULT_GENERATION,
        serifPages: Boolean = false,
    ): ItemStack {
        val stack = ItemStack(Items.WRITTEN_BOOK)
        val pageTag = ListTag()
        for (p in pages) pageTag.add(StringTag.valueOf(Component.Serializer.toJson(p)))
        val tag = stack.orCreateTag
        tag.putString("title", title)
        tag.putString("author", author)
        tag.put("pages", pageTag)
        tag.putBoolean("resolved", true)
        tag.putInt("generation", generation)
        if (serifPages) tag.putBoolean(SERIF_PAGES_FLAG, true)
        return stack
    }

    /**
     * Parse the bundled markdown at [resource] into book pages: one page per `## Page` section.
     * Returns a single "(the pages are blank)" page if the resource can't be read.
     */
    fun parseMarkdown(resource: String): List<Component> =
        parseSections(resource)?.map { (sh, sb) ->
            if (sh == null) Component.literal(sb) else Component.literal(sh).append("\n\n").append(Component.literal(sb))
        } ?: listOf(Component.literal("(the pages are blank)"))

    /**
     * As [parseMarkdown], but a section longer than one book page continues on the pages that follow.
     * Translation makes the Sselith text longer than the English source, and the book screen cuts
     * text below line 14.
     */
    fun parsePaginatedMarkdown(resource: String, transform: (Int, String) -> String = { _, text -> text }): List<Component> =
        parseSections(resource)?.withIndex()?.flatMap { (i, section) ->
            val (sh, sb) = section
            paginateWithBreaks(transform(i, if (sh == null) sb else "$sh\n\n$sb")).map { Component.literal(it) }
        } ?: listOf(Component.literal("(the pages are blank)"))

    // A `[page]` line forces a page break; two in a row leave one blank page between them. The tag never prints.
    private val PAGE_BREAK = Regex("""\n*\[page]\n*""")

    private fun paginateWithBreaks(text: String): List<String> {
        val parts = text.split(PAGE_BREAK)
        return parts.flatMapIndexed { i, part ->
            val pages = BookPaginator.paginate(part)
            if (pages.isEmpty() && i in 1 until parts.lastIndex) listOf("") else pages
        }
    }

    /** Heading is null for a file with no `## ` line: its whole text is one body. Text before the first heading is dropped (Almanac subtitle). */
    private fun parseSections(resource: String): List<Pair<String?, String>>? {
        val raw = TomeLore::class.java.getResourceAsStream(resource)?.bufferedReader()?.use { it.readLines() }
            ?: return null

        val sections = mutableListOf<Pair<String?, String>>()
        var heading: String? = null
        val body = StringBuilder()
        // Same text as [body], but blank lines stay paragraph breaks and single line breaks stay line breaks; used when the file has no heading.
        val plain = StringBuilder()

        fun flush() {
            heading?.let { sections.add(it to body.toString().trim()) }
            body.setLength(0)
        }

        for (line in raw) {
            val t = line.trim()
            when {
                t.isEmpty() || t == "---" -> {
                    if (body.isNotEmpty() && body.last() != ' ') body.append(' ')
                    if (plain.isNotEmpty() && !plain.endsWith("\n\n")) plain.append("\n\n")
                }
                t == "[page]" -> if (heading == null) plain.append("\n\n[page]\n\n")
                t.startsWith("## ") -> { flush(); heading = t.removePrefix("## ").trim() }
                t.startsWith("# ") -> { /* title lives in the book NBT, not on a page */ }
                else -> {
                    if (body.isNotEmpty() && body.last() != ' ') body.append(' ')
                    // Markdown emphasis marks; a book page shows plain text.
                    body.append(t.replace("*", "").trim())
                    if (plain.isNotEmpty() && !plain.endsWith("\n")) plain.append('\n')
                    // Spaced poems (tome_of_pushing) place words with leading and repeated spaces.
                    plain.append(line.replace("*", ""))
                }
            }
        }
        if (heading == null) {
            val text = plain.toString().trim('\n')
            if (text.isNotEmpty()) sections.add(null to text)
        } else {
            flush()
        }
        return sections
    }
}
