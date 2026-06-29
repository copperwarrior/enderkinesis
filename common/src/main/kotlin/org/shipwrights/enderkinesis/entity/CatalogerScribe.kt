package org.shipwrights.enderkinesis.entity

import net.minecraft.core.BlockPos
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LecternBlock
import net.minecraft.world.level.block.entity.LecternBlockEntity
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.sselith.BookPaginator
import org.shipwrights.enderkinesis.sselith.Sselith
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Cataloger's one act: when it finishes staring at a lectern, it
 * files the book — rewriting whatever is there into Sselith. A book-and-
 * quill (`writable_book`, raw-string pages) or a signed book
 * (`written_book`, JSON-component pages) on the lectern is read, its
 * text translated by [Sselith], re-paginated by [BookPaginator] so the
 * longer Sselith words still fall on correct pages, and written back to
 * the lectern in the same book type.
 *
 * The rewrite is marked with [SSELITH_FLAG] so a Cataloger that returns
 * to the same lectern doesn't re-translate already-Sselith text (which
 * would otherwise reflow and re-sentence-case it every visit).
 *
 * Server-side only — Catalogers and their goals tick on the server, and
 * the lectern's book lives in its [LecternBlockEntity] there.
 */
object CatalogerScribe {

    /** Book-tag boolean stamped on text the Cataloger has already filed. */
    private const val SSELITH_FLAG = "EnderkinesisSselith"

    /**
     * Translate the book on the lectern at [pos], if any. Returns true if
     * a book was actually rewritten. Safe to call on any [pos] — non-
     * lecterns, empty lecterns, non-book items and already-filed books
     * are all no-ops.
     */
    fun fileLectern(level: ServerLevel, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        // Both vanilla `Blocks.LECTERN` and our Sselith lectern (which
        // extends LecternBlock) qualify; instance check accepts every
        // current and future LecternBlock subclass uniformly.
        if (state.block !is LecternBlock) return false
        val be = level.getBlockEntity(pos) as? LecternBlockEntity ?: return false

        val book = be.book
        if (book.isEmpty) return false
        val written = book.`is`(Items.WRITTEN_BOOK)
        val writable = book.`is`(Items.WRITABLE_BOOK)
        if (!written && !writable) return false

        val tag = book.tag ?: return false
        if (tag.getBoolean(SSELITH_FLAG)) return false

        val srcPages = tag.getList("pages", Tag.TAG_STRING.toInt())
        if (srcPages.isEmpty()) return false

        // Flatten every page to plain English. Pages join with a newline
        // so explicit line structure (headings, list items the author
        // typed) survives into the re-flow.
        val english = buildString {
            for (i in 0 until srcPages.size) {
                if (i > 0) append('\n')
                val raw = srcPages.getString(i)
                append(if (written) pageText(raw) else raw)
            }
        }
        if (english.isBlank()) return false

        val pages = BookPaginator.paginate(Sselith.translate(english))
        if (pages.isEmpty()) return false

        val replacement =
            if (written) writtenBook(tag, pages) else writableBook(pages)

        be.setBook(replacement)
        be.setChanged()
        // Refresh the BE for clients and the comparator output (the page
        // reset in setBook changes the signal) — mirrors vanilla's
        // LecternBlockEntity.bookChanged update.
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL)
        // Refresh the comparator from whatever specific lectern block this
        // is (vanilla or Sselith) — the comparator signal depends on the
        // BE's page count, which doesn't care about block identity, but
        // `updateNeighbourForOutputSignal` does pin to a specific Block.
        level.updateNeighbourForOutputSignal(pos, state.block)
        // The Cataloger's quill on the page — vanilla has no dedicated
        // writing sound, so the cartography-table scratch (pitched down a
        // touch, jittered) reads as scratching ink onto the book.
        level.playSound(
            null, pos, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.BLOCKS,
            1.0f, 0.7f + level.random.nextFloat() * 0.2f,
        )
        spawnGlyphColumn(level, pos)
        return true
    }

    /** A cylindrical column of rising sga glyphs lifting off the lectern
     *  — the runes of the freshly-filed Sselith text leaving the page.
     *  Each glyph is sent individually (count 0) so it lands exactly on
     *  the cylinder wall; the rising drift lives in the particle itself.
     *  A one-shot burst seeded across the column height so the cylinder
     *  reads immediately, then continues to lift. */
    private fun spawnGlyphColumn(level: ServerLevel, pos: BlockPos) {
        val cx = pos.x + 0.5
        val cz = pos.z + 0.5
        val baseY = pos.y + 1.0  // top of the lectern, where the book sits
        val rng = level.random
        for (i in 0 until GLYPH_COUNT) {
            val angle = i.toDouble() / GLYPH_COUNT * (PI * 2.0) + (rng.nextDouble() - 0.5) * 0.25
            val r = GLYPH_RADIUS + (rng.nextDouble() - 0.5) * 0.08
            val px = cx + cos(angle) * r
            val pz = cz + sin(angle) * r
            val py = baseY + rng.nextDouble() * GLYPH_HEIGHT
            level.sendParticles(EKParticles.sselithGlyph(), px, py, pz, 0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    /** Plain display text of a written-book page (a JSON component),
     *  falling back to the raw string if it won't parse. */
    private fun pageText(json: String): String =
        try {
            Component.Serializer.fromJson(json)?.string ?: json
        } catch (e: Exception) {
            json
        }

    /** A signed Sselith book: translated title, original author, pages
     *  as JSON literal components. Carries [SSELITH_FLAG]. */
    private fun writtenBook(src: net.minecraft.nbt.CompoundTag, pages: List<String>): ItemStack {
        val stack = ItemStack(Items.WRITTEN_BOOK)
        val tag = stack.orCreateTag
        tag.putString("title", Sselith.translate(src.getString("title")).take(MAX_TITLE_LEN))
        tag.putString("author", src.getString("author"))
        if (src.contains("generation", Tag.TAG_INT.toInt())) {
            tag.putInt("generation", src.getInt("generation"))
        }
        val list = ListTag()
        for (page in pages) list.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(page))))
        tag.put("pages", list)
        tag.putBoolean("resolved", true)
        tag.putBoolean(SSELITH_FLAG, true)
        return stack
    }

    /** A Sselith book-and-quill: translated raw-string pages, no title /
     *  author (vanilla writable books have none). Carries [SSELITH_FLAG]. */
    private fun writableBook(pages: List<String>): ItemStack {
        val stack = ItemStack(Items.WRITABLE_BOOK)
        val tag = stack.orCreateTag
        val list = ListTag()
        for (page in pages) list.add(StringTag.valueOf(page))
        tag.put("pages", list)
        tag.putBoolean(SSELITH_FLAG, true)
        return stack
    }

    /** Vanilla signed-book title length cap. */
    private const val MAX_TITLE_LEN = 32

    /** Glyphs in the rising column. One packet each, so kept modest. */
    private const val GLYPH_COUNT = 26
    /** Cylinder wall radius around the lectern centre, in blocks. */
    private const val GLYPH_RADIUS = 0.45
    /** Initial seed height of the column above the lectern, in blocks. */
    private const val GLYPH_HEIGHT = 1.6
}
