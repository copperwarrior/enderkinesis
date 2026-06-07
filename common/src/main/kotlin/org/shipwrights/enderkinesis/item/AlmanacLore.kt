package org.shipwrights.enderkinesis.item

import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * The static lore the Almanac of Everywhere reveals when it rests on a lectern. Prose lives in the
 * bundled resource `assets/enderkinesis/texts/almanackal_awakening.md` (a verbatim copy of the
 * repo-root `almanackal_awakening.md`); [TomeLore.parseMarkdown] slices it into pages and
 * [TomeLore.bake] wraps them as a throw-away written book.
 *
 * Nothing here mutates the almanac itself — the lectern keeps the real almanac stack (with its
 * recorded dimensions); this is purely the display copy built client-side on open.
 */
object AlmanacLore {

    private const val RESOURCE = "/assets/enderkinesis/texts/almanackal_awakening.md"
    private const val TITLE = "The Almanackal Awakening"
    private const val AUTHOR = "The Servant"

    private val pages: List<Component> by lazy { TomeLore.parseMarkdown(RESOURCE) }

    fun pageCount(): Int = pages.size

    fun writtenBook(): ItemStack = TomeLore.bake(TITLE, AUTHOR, pages)
}
