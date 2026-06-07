package org.shipwrights.enderkinesis.dimension

import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

/**
 * NBT-backed view of an Almanac of Everywhere's data: every dimension it has visited plus the
 * currently selected destination index.
 */
object AlmanacData {
    private const val VISITED = "VisitedDimensions"
    private const val SELECTED = "SelectedIndex"

    fun visited(stack: ItemStack): List<ResourceLocation> {
        val tag = stack.tag ?: return emptyList()
        val list = tag.getList(VISITED, Tag.TAG_STRING.toInt())
        return list.mapNotNull { ResourceLocation.tryParse(it.asString) }
    }

    /** Records [dimension] from a normal player flow (carrying the almanac, item-entity crossing
     *  a portal, creative-populate). Returns true if it was newly added.
     *
     *  **Hidden dimensions:** [YgannAbyss.ID] and [SselithRepertory.ID] are silently refused
     *  here — both are off-limits as Almanac / Ender Astrolabe destinations. The only
     *  legitimate way an Almanac can gain a Ygann's Abyss entry is via [addEntryTrusted]
     *  from the structure processor that seeds the lectern inside the Roaming End Ship;
     *  Sselith's Repertory is never a valid destination, period. [DimensionFilter] also
     *  denies both ids at the *selectable* layer — defence in depth so a legacy save
     *  that already recorded one of them can't pick it from the cycle. */
    fun addVisited(stack: ItemStack, dimension: ResourceLocation): Boolean {
        if (dimension == YgannAbyss.ID) return false
        if (dimension == SselithRepertory.ID) return false
        return addEntryTrusted(stack, dimension)
    }

    /** Append [dimension] to the visited list without any hidden-dimension filtering. Used by
     *  trusted seeding paths (currently the lectern almanac in the Roaming End Ship). Returns
     *  true if newly added. */
    fun addEntryTrusted(stack: ItemStack, dimension: ResourceLocation): Boolean {
        val tag = stack.orCreateTag
        val list = tag.getList(VISITED, Tag.TAG_STRING.toInt())
        val id = dimension.toString()
        if (list.any { it.asString == id }) return false
        list.add(StringTag.valueOf(id))
        tag.put(VISITED, list)
        return true
    }

    fun selectedIndex(stack: ItemStack): Int = stack.tag?.getInt(SELECTED) ?: 0

    fun setSelectedIndex(stack: ItemStack, index: Int) {
        stack.orCreateTag.putInt(SELECTED, index)
    }

    /** Visited dimensions that also pass the data-driven [DimensionFilter]. */
    fun selectable(stack: ItemStack): List<ResourceLocation> =
        visited(stack).filter(DimensionFilter::isAllowed)

    /** Advances the selection within [selectable] and returns the new destination, if any. */
    fun cycle(stack: ItemStack): ResourceLocation? {
        val options = selectable(stack)
        if (options.isEmpty()) return null
        val next = (selectedIndex(stack) + 1) % options.size
        setSelectedIndex(stack, next)
        return options[next]
    }

    fun current(stack: ItemStack): ResourceLocation? {
        val options = selectable(stack)
        if (options.isEmpty()) return null
        return options[selectedIndex(stack).coerceIn(0, options.size - 1)]
    }
}
