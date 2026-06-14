package org.shipwrights.enderkinesis.item

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * Ledger of Hunting Pincers — same lectern-readable [TomeItem] shape as
 * [LedgerOfWatchingEyesItem], but tracks [net.minecraft.world.entity.LivingEntity]
 * targets (mobs, players, animals — anything in the LivingEntity tree) instead of ships.
 *
 * Mirrors the Watching Eyes flow: while the ledger sits in an eyeroscope's slot, the BE
 * appends a sighting entry for every fresh [LivingEntity] that enters its scan cube; the
 * book viewer renders those entries on demand via [writtenBook]. The eyeroscope also
 * steers itself toward the latest-loaded tracked entity, same as the ship-tracking mode.
 *
 * One twist over the ship ledger: the eyeroscope BE remembers the UUID of the player who
 * dropped the ledger in, and skips that UUID when scanning — so the holder doesn't end up
 * as a tracked target.
 */
class LedgerOfHuntingPincersItem(properties: Properties) : TomeItem(properties) {

    override fun writtenBook(stack: ItemStack): ItemStack {
        val list = stack.tag?.getList(TRACKED_ENTITIES_TAG, Tag.TAG_COMPOUND.toInt())
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
        val type = entry.getString("type")
        if (type.isNotEmpty()) sb.append(type).append('\n')
        val name = entry.getString("name")
        if (name.isNotEmpty()) sb.append('"').append(name).append("\"\n")
        sb.append(entry.getDouble("x").toInt()).append(", ")
            .append(entry.getDouble("y").toInt()).append(", ")
            .append(entry.getDouble("z").toInt()).append('\n')
        sb.append("tick ").append(entry.getLong("tick")).append("\n\n")
    }

    companion object {
        /** NBT key for the sighting list. Same shape as the Watching Eyes' list but each
         *  entry holds an entity UUID + type / display name instead of a ship id + slug. */
        const val TRACKED_ENTITIES_TAG: String = "tracked_entities"

        const val TITLE: String = "Ledger of Hunting Pincers"
        const val AUTHOR: String = "The Stalker"

        /** Sightings per rendered page — same budget as the Watching Eyes ledger. */
        const val ENTRIES_PER_PAGE: Int = 4
    }
}
