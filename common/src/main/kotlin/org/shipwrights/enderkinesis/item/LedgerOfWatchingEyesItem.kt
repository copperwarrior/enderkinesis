package org.shipwrights.enderkinesis.item

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * Ledger of Watching Eyes — slot-companion for the Eyeroscope. Same shape as the other
 * lectern-readable tomes ([AlmanacOfEverywhereItem], [GuideToStratusItem], [WyllandTomeItem]):
 * extends [TomeItem] so the empty-lectern-click placement and the
 * `LecternBlockEntityHasBookMixin` book-view path both work without extra wiring, and
 * provides a fresh transient [Items.WRITTEN_BOOK] via [writtenBook] each time the lectern
 * opens it.
 *
 * Sightings live in the stack's NBT as the [TRACKED_SHIPS_TAG] list. The eyeroscope
 * appends new entries in
 * [org.shipwrights.enderkinesis.blockentity.EyeroscopeBlockEntity.tickLedger]; [writtenBook]
 * reformats them into pages on demand. Page count for the lectern's comparator and
 * page-turn clamp falls out of the synthesised book.
 */
class LedgerOfWatchingEyesItem(properties: Properties) : TomeItem(properties) {

    override fun writtenBook(stack: ItemStack): ItemStack {
        val list = stack.tag?.getList(TRACKED_SHIPS_TAG, Tag.TAG_COMPOUND.toInt())
        if (list == null || list.isEmpty()) {
            return TomeLore.blank(TITLE, AUTHOR)
        }
        return TomeLore.bake(TITLE, AUTHOR, buildPages(list))
    }

    private fun buildPages(list: ListTag): List<Component> {
        val pages = mutableListOf<Component>()
        val sb = StringBuilder()
        var onPage = 0
        for (i in 0 until list.size) {
            val entry = list.getCompound(i)
            appendSighting(sb, entry)
            onPage++
            if (onPage >= ENTRIES_PER_PAGE) {
                pages.add(Component.literal(sb.toString()))
                sb.setLength(0)
                onPage = 0
            }
        }
        if (sb.isNotEmpty()) pages.add(Component.literal(sb.toString()))
        return pages
    }

    private fun appendSighting(sb: StringBuilder, entry: CompoundTag) {
        sb.append("Ship #").append(entry.getLong("id")).append('\n')
        val slug = entry.getString("slug")
        if (slug.isNotEmpty()) sb.append('"').append(slug).append("\"\n")
        sb.append(entry.getDouble("x").toInt()).append(", ")
            .append(entry.getDouble("y").toInt()).append(", ")
            .append(entry.getDouble("z").toInt()).append('\n')
        sb.append("tick ").append(entry.getLong("tick")).append("\n\n")
    }

    companion object {
        /** NBT key for the sighting list. Lives on the stack itself (the eyeroscope mutates
         *  it in-place); reformatted into pages on demand by [writtenBook]. */
        const val TRACKED_SHIPS_TAG: String = "tracked_ships"

        const val TITLE: String = "Ledger of Watching Eyes"
        const val AUTHOR: String = "The Beheld"

        /** Sightings per rendered page — fits the book viewer's per-page line budget with
         *  room for each entry's three-line block. */
        const val ENTRIES_PER_PAGE: Int = 4
    }
}
