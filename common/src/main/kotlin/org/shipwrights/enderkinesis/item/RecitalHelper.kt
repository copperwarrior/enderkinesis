package org.shipwrights.enderkinesis.item

import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKItems

/** Storage layout + helpers for the Staff of Recital.
 *
 *  ## NBT layout
 *  - `RecitalTomes` (ListTag of CompoundTag) — full serialised ItemStacks of
 *    each stored tome, one per kind (no duplicates).
 *  - `RecitalActive` (Int) — index into the list of the currently-selected
 *    tome. -1 / out of range when empty. Server-authoritative; the client's
 *    shift+scroll input is forwarded over [RecitalNetwork.SET_ACTIVE].
 *
 *  ## Why "one of each kind" rather than a bundle-style item count
 *  Tomes carry per-stack state (Wylland's repair durability, Tome of Signal's
 *  beam palette, etc.) that the user expects to round-trip through the staff
 *  unchanged. Storing one of each makes the active-selection model trivial
 *  (one slot per Item type) and avoids the "which copy of the Wylland Tome
 *  did I pull out?" ambiguity. */
object RecitalHelper {

    /** Tag covering every tome that may be loaded into a Staff of Recital. */
    val TOMES_TAG: TagKey<Item> = TagKey.create(Registries.ITEM, EnderkinesisMod.id("staff_of_recital_tomes"))

    private const val TOMES_KEY = "RecitalTomes"
    private const val ACTIVE_KEY = "RecitalActive"

    /** True if `tome` is eligible to be loaded into a recital staff. */
    fun isEligibleTome(tome: ItemStack): Boolean = !tome.isEmpty && tome.`is`(TOMES_TAG)

    /** Read all stored tomes (as a mutable copy — write back via [writeTomes]). */
    fun readTomes(staff: ItemStack): MutableList<ItemStack> {
        val tag = staff.tag ?: return mutableListOf()
        val list = tag.getList(TOMES_KEY, Tag.TAG_COMPOUND.toInt())
        val out = ArrayList<ItemStack>(list.size)
        for (i in 0 until list.size) {
            val s = ItemStack.of(list.getCompound(i))
            if (!s.isEmpty) out.add(s)
        }
        return out
    }

    fun writeTomes(staff: ItemStack, tomes: List<ItemStack>) {
        val tag = staff.orCreateTag
        if (tomes.isEmpty()) {
            tag.remove(TOMES_KEY)
            tag.remove(ACTIVE_KEY)
            if (tag.isEmpty) staff.tag = null
            return
        }
        val list = ListTag()
        for (s in tomes) {
            val c = CompoundTag()
            s.save(c)
            list.add(c)
        }
        tag.put(TOMES_KEY, list)
        // Re-clamp active index so it's never past the end after a remove.
        val active = tag.getInt(ACTIVE_KEY).coerceIn(0, tomes.size - 1)
        tag.putInt(ACTIVE_KEY, active)
    }

    fun getActiveIndex(staff: ItemStack): Int {
        val tag = staff.tag ?: return -1
        if (!tag.contains(TOMES_KEY, Tag.TAG_LIST.toInt())) return -1
        val size = tag.getList(TOMES_KEY, Tag.TAG_COMPOUND.toInt()).size
        if (size == 0) return -1
        return tag.getInt(ACTIVE_KEY).coerceIn(0, size - 1)
    }

    fun setActiveIndex(staff: ItemStack, index: Int) {
        val tag = staff.orCreateTag
        tag.putInt(ACTIVE_KEY, index)
    }

    /** Current active tome stack, or `null` if the staff is empty. */
    fun getActiveTome(staff: ItemStack): ItemStack? {
        val tomes = readTomes(staff)
        if (tomes.isEmpty()) return null
        val idx = getActiveIndex(staff).coerceIn(0, tomes.size - 1)
        return tomes[idx]
    }

    /** Write `updated` back as the active tome's stack (e.g. after a tome's
     *  use modified its own NBT or damage). */
    fun replaceActiveTome(staff: ItemStack, updated: ItemStack) {
        val tomes = readTomes(staff)
        if (tomes.isEmpty()) return
        val idx = getActiveIndex(staff).coerceIn(0, tomes.size - 1)
        tomes[idx] = updated
        writeTomes(staff, tomes)
    }

    /** Add a tome to the staff. Refuses to add a duplicate kind (replaces the
     *  existing entry with the new stack instead, preserving the new one's
     *  NBT — useful when the player puts in a freshly-imbued tome). Returns
     *  true on accept. */
    fun addTome(staff: ItemStack, tome: ItemStack): Boolean {
        if (!isEligibleTome(tome)) return false
        val tomes = readTomes(staff)
        val existing = tomes.indexOfFirst { it.item == tome.item }
        if (existing >= 0) {
            tomes[existing] = tome.copy()
        } else {
            tomes.add(tome.copy())
        }
        writeTomes(staff, tomes)
        return true
    }

    /** Remove the active tome and return it for the caller to give to the
     *  player. Null if the staff is empty. */
    fun removeActiveTome(staff: ItemStack): ItemStack? {
        val tomes = readTomes(staff)
        if (tomes.isEmpty()) return null
        val idx = getActiveIndex(staff).coerceIn(0, tomes.size - 1)
        val removed = tomes.removeAt(idx)
        writeTomes(staff, tomes)
        return removed
    }

    /** Convenience: extract the "effective tome" being held by the player —
     *  either a tagged tome directly, or the active tome inside a recital
     *  staff. Used by tome-aware mixins (e.g. the Wylland swing suppressor)
     *  to recognise the staff as a vehicle for that tome. */
    fun getEffectiveTomeOfType(player: Player, hand: net.minecraft.world.InteractionHand, tomeId: ResourceLocation): ItemStack? {
        val held = player.getItemInHand(hand)
        if (held.isEmpty) return null
        val heldId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.item)
        if (heldId == tomeId) return held
        if (held.item == EKItems.STAFF_OF_RECITAL.get()) {
            val active = getActiveTome(held) ?: return null
            val activeId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(active.item)
            if (activeId == tomeId) return active
        }
        return null
    }

    /** Convenience for the most common case — is the player holding the
     *  Wylland Tome directly OR via a Staff of Recital whose active tome is
     *  Wylland? Both hands checked. */
    @JvmStatic
    fun isHoldingWyllandTome(player: Player): Boolean {
        val wyllandId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
            EKItems.WYLLAND_TOME.get()
        )
        return getEffectiveTomeOfType(player, net.minecraft.world.InteractionHand.MAIN_HAND, wyllandId) != null ||
            getEffectiveTomeOfType(player, net.minecraft.world.InteractionHand.OFF_HAND, wyllandId) != null
    }

    /** Same as [isHoldingWyllandTome] but answers "via the recital staff
     *  specifically" — drives the SGA-particle origin redirect (camera
     *  centre vs. staff tip). */
    @JvmStatic
    fun isHoldingWyllandViaRecitalStaff(player: Player): Boolean {
        val wyllandId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
            EKItems.WYLLAND_TOME.get()
        )
        for (hand in arrayOf(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.InteractionHand.OFF_HAND)) {
            val held = player.getItemInHand(hand)
            if (held.item != EKItems.STAFF_OF_RECITAL.get()) continue
            val active = getActiveTome(held) ?: continue
            if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(active.item) == wyllandId) return true
        }
        return false
    }
}
