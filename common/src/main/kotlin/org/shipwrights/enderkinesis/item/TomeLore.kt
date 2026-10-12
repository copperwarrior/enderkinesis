package org.shipwrights.enderkinesis.item

import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.sselith.BookPaginator

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

    /** A stored page as a component. Plain text is not valid JSON, so it becomes a literal page. */
    fun pageComponent(raw: String): Component =
        runCatching { Component.Serializer.fromJson(raw) }.getOrNull() ?: Component.literal(raw)

    /** A single-page book reading "the pages are blank" — placeholder for tomes without lore yet. */
    fun blank(title: String, author: String = "Unknown"): ItemStack =
        bake(title, author, listOf(Component.literal("(the pages are blank)")))

    /** Build a written-book stack from a Sselith-translated markdown source in the bundled
     *  `assets/enderkinesis/texts/sselith/books/` directory. The source filename is
     *  `<bookName>.md` (English at build time; Sselith-translated by `translateSselith` in
     *  the shipped JAR). Pages are parsed once and cached per [bookName]; each call returns
     *  a fresh stack wrapping the same cached page list. */
    fun fromSselithBook(title: String, author: String, bookName: String): ItemStack {
        val pages = SSELITH_BOOK_CACHE.getOrPut(bookName) {
            parsePaginatedMarkdown("/assets/enderkinesis/texts/sselith/books/$bookName.md")
        }
        return bake(title, author, pages, serifPages = true)
    }

    /** Cache of parsed Sselith books — the parsed page list is immutable once loaded. */
    private val SSELITH_BOOK_CACHE =
        java.util.concurrent.ConcurrentHashMap<String, List<Component>>()

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
        return SselithBookItem.of(title, author, pages)
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
            Component.literal(sh).append("\n\n").append(Component.literal(sb))
        } ?: listOf(Component.literal("(the pages are blank)"))

    /**
     * As [parseMarkdown], but a section longer than one book page continues on the pages that follow.
     * Translation makes the Sselith text longer than the English source, and the book screen cuts
     * text below line 14.
     */
    fun parsePaginatedMarkdown(resource: String): List<Component> =
        parseSections(resource)?.flatMap { (sh, sb) ->
            BookPaginator.paginate("$sh\n\n$sb").map { Component.literal(it) }
        } ?: listOf(Component.literal("(the pages are blank)"))

    private fun parseSections(resource: String): List<Pair<String, String>>? {
        val raw = TomeLore::class.java.getResourceAsStream(resource)?.bufferedReader()?.use { it.readLines() }
            ?: return null

        val sections = mutableListOf<Pair<String, String>>()
        var heading: String? = null
        val body = StringBuilder()

        fun flush() {
            heading?.let { sections.add(it to body.toString().trim()) }
            body.setLength(0)
        }

        for (line in raw) {
            val t = line.trim()
            when {
                t.isEmpty() || t == "---" -> if (body.isNotEmpty() && body.last() != ' ') body.append(' ')
                t.startsWith("## ") -> { flush(); heading = t.removePrefix("## ").trim() }
                t.startsWith("# ") -> { /* title lives in the book NBT, not on a page */ }
                heading == null -> { /* pre-section subtitle/front-matter: dropped */ }
                else -> {
                    if (body.isNotEmpty() && body.last() != ' ') body.append(' ')
                    // Markdown emphasis marks; a book page shows plain text.
                    body.append(t.replace("*", "").trim())
                }
            }
        }
        flush()
        return sections
    }
}
