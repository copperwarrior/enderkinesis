package org.shipwrights.enderkinesis.item

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.EnchantedBookItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.Enchantments

/** Enchantment levels read from an enchanted book held in the wielder's
 *  offhand. Every enchantment is treated as **uncapped** — the level read
 *  from the book's `StoredEnchantments` NBT is used as-is rather than
 *  clamped to vanilla's `maxLevel`, so Sharpness 10 on a book scales the
 *  same as you'd expect from a hypothetical Sharpness 10 weapon.
 *
 *  All effects are no-ops at level 0 (no book / no relevant enchantment).
 *  A single `NONE` instance is used as the "no offhand enchants" sentinel
 *  to avoid building a fresh empty record every tick. */
data class SunderingEnchants(
    /** **Quick Charge** — multiplies the ramp's effective elapsed time by
     *  `1 + level × 0.25`. Level 1 = 25 % faster ramp, level 4 = 2× speed,
     *  level 8 = 3× speed. */
    val quickCharge: Int,
    /** **Channeling** — spawns a lightning bolt at the beam's terminating
     *  block every `400 / level` ticks while the staff is at full ramp.
     *  Level 1 = every 20 s, level 5 = every 4 s. Needs a block hit; runs
     *  in clear weather too (we summon the bolt directly). */
    val channeling: Int,
    /** **Impaling** — `+level × 2.5` bonus damage per chunk against
     *  aquatic mobs (`MobType.WATER` or `isInWaterOrRain`). */
    val impaling: Int,
    /** **Infinity** — multiplies effective range by `(1 + level)` and
     *  divides every final damage chunk by `(1 + level)`. Level 1 → 2×
     *  range, ½ damage. Level 5 → 6× range, ⅙ damage. Sweep, channel
     *  lightning damage, and direct damage all scale together. */
    val infinity: Int,
    /** **Flame** — per-tick chance `level × 0.5 %` of igniting one random
     *  air block in a `(stage)`-block cubic radius around the beam's
     *  terminating block (stage 1 → ±1 block, stage 4 → ±4 blocks). Uses
     *  vanilla `BaseFireBlock.getState` so the placement honours dimension
     *  fire rules. */
    val flame: Int,
    /** **Punch** — adds `level × 0.5` knockback strength to every entity
     *  the beam damages, pushing them away from the segment origin.
     *  Stacks with [knockback] (same mechanism). */
    val punch: Int,
    /** **Power** — flat damage bonus `level × 0.025 hp/tick` (≈ `level × 0.5 hp/s`).
     *  Small enough to not eclipse the base damage at sensible levels. */
    val power: Int,
    /** **Fortune** — applied to the loot roll on every block the beam
     *  mines. Stacks with [silkTouch] in the usual vanilla "silk wins"
     *  way (i.e. mostly ignored when [silkTouch] > 0). */
    val fortune: Int,
    /** **Silk Touch** — `> 0` disables mining entirely. The beam still
     *  damages mobs and lights fire; blocks at the polyline terminus
     *  stay intact. */
    val silkTouch: Int,
    /** **Efficiency** — multiplies the mining speed multiplier by
     *  `1 + level × 0.1`. Smaller than vanilla's `level² + 1` because
     *  the staff's ramp already drives the speed curve. */
    val efficiency: Int,
    /** **Sweeping Edge** — every damage chunk also damages mobs within
     *  `1 + 0.5 × level` blocks of the beam's terminating point for
     *  `0.3 × level` of the main damage. */
    val sweepingEdge: Int,
    /** **Looting** — drives [DisintegrationLootingOverride] for the
     *  duration of each `hurt` call so kills roll vanilla looting drops
     *  even though the damage source has no killer entity. */
    val looting: Int,
    /** **Fire Aspect** — adds `level × 4` seconds to the fire ticks
     *  applied to each mob the beam touches. */
    val fireAspect: Int,
    /** **Knockback** — identical to [punch]. Stacks (sum of the two
     *  drives the knockback strength). */
    val knockback: Int,
    /** **Bane of Arthropods** — `+level × 2.5` bonus damage per chunk
     *  against arthropod mobs (`MobType.ARTHROPOD`). */
    val baneOfArthropods: Int,
    /** **Smite** — `+level × 2.5` bonus damage per chunk against undead
     *  mobs (`MobType.UNDEAD`). */
    val smite: Int,
) {
    companion object {
        /** Singleton "no offhand enchants" instance — handed back by
         *  [fromOffhand] when there's nothing to read, so the per-tick
         *  read path doesn't allocate a fresh record. */
        @JvmStatic
        val NONE: SunderingEnchants = SunderingEnchants(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )

        /** Read enchant levels from an enchanted book held in [player]'s
         *  offhand. Returns [NONE] if the offhand is empty or isn't a
         *  book. Levels are **uncapped** — whatever the book's NBT
         *  stores is what we use. */
        @JvmStatic
        fun fromOffhand(player: Player): SunderingEnchants {
            val offhand = player.offhandItem
            if (offhand.isEmpty || offhand.item !is EnchantedBookItem) return NONE
            return fromBook(offhand)
        }

        private fun fromBook(book: ItemStack): SunderingEnchants {
            val list = EnchantedBookItem.getEnchantments(book)
            if (list.isEmpty()) return NONE
            // Cache the levels in a small map keyed by the enchantment's
            // canonical ID — the book NBT stores `id` as a namespaced
            // string ("minecraft:quick_charge"), so we compare strings
            // rather than poking `Enchantment` instances.
            val levels = HashMap<String, Int>(list.size)
            for (i in 0 until list.size) {
                val tag = list.getCompound(i)
                val id = tag.getString("id")
                if (id.isNotEmpty()) levels[id] = tag.getInt("lvl")
            }
            return SunderingEnchants(
                quickCharge = levels[idOf(Enchantments.QUICK_CHARGE)] ?: 0,
                channeling = levels[idOf(Enchantments.CHANNELING)] ?: 0,
                impaling = levels[idOf(Enchantments.IMPALING)] ?: 0,
                infinity = levels[idOf(Enchantments.INFINITY_ARROWS)] ?: 0,
                flame = levels[idOf(Enchantments.FLAMING_ARROWS)] ?: 0,
                punch = levels[idOf(Enchantments.PUNCH_ARROWS)] ?: 0,
                power = levels[idOf(Enchantments.POWER_ARROWS)] ?: 0,
                fortune = levels[idOf(Enchantments.BLOCK_FORTUNE)] ?: 0,
                silkTouch = levels[idOf(Enchantments.SILK_TOUCH)] ?: 0,
                efficiency = levels[idOf(Enchantments.BLOCK_EFFICIENCY)] ?: 0,
                sweepingEdge = levels[idOf(Enchantments.SWEEPING_EDGE)] ?: 0,
                looting = levels[idOf(Enchantments.MOB_LOOTING)] ?: 0,
                fireAspect = levels[idOf(Enchantments.FIRE_ASPECT)] ?: 0,
                knockback = levels[idOf(Enchantments.KNOCKBACK)] ?: 0,
                baneOfArthropods = levels[idOf(Enchantments.BANE_OF_ARTHROPODS)] ?: 0,
                smite = levels[idOf(Enchantments.SMITE)] ?: 0,
            )
        }

        private fun idOf(enchantment: Enchantment): String =
            BuiltInRegistries.ENCHANTMENT.getKey(enchantment)?.toString() ?: ""
    }
}
