package org.shipwrights.enderkinesis.item

import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Helpers for building the transient `WRITTEN_BOOK` stacks tomes hand back from
 * [TomeItem.writtenBook]. Two paths:
 *  - [parseMarkdown] takes a bundled `.md` resource and produces one page per `## Section`, in
 *    order — no auto-pagination, no bold heading; `# title` and pre-section front-matter are
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
            parseMarkdown("/assets/enderkinesis/texts/sselith/books/$bookName.md")
        }
        return bake(title, author, pages)
    }

    /** Cache of parsed Sselith books — the parsed page list is immutable once loaded. */
    private val SSELITH_BOOK_CACHE =
        java.util.concurrent.ConcurrentHashMap<String, List<Component>>()

    /** Build a transient signed [Items.WRITTEN_BOOK] stack carrying [pages]. */
    fun bake(
        title: String,
        author: String,
        pages: List<Component>,
        generation: Int = DEFAULT_GENERATION,
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
        return stack
    }

    /**
     * Parse the bundled markdown at [resource] into book pages: one page per `## Page` section.
     * Returns a single "(the pages are blank)" page if the resource can't be read.
     */
    fun parseMarkdown(resource: String): List<Component> {
        val raw = TomeLore::class.java.getResourceAsStream(resource)?.bufferedReader()?.use { it.readLines() }
            ?: return listOf(Component.literal("(the pages are blank)"))

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
                    body.append(t.trim('*').trim())
                }
            }
        }
        flush()

        return sections.map { (sh, sb) ->
            Component.literal(sh).append("\n\n").append(Component.literal(sb))
        }
    }
}
