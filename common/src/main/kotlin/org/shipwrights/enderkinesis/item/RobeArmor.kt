package org.shipwrights.enderkinesis.item

import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.ItemTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ArmorMaterial
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * Per-realm armor material. The material `name` doubles as the worn-armor texture key —
 * vanilla `HumanoidArmorLayer` builds the path as
 * `(minecraft:)textures/models/armor/{name}_layer_{1|2}.png`, so we ship pre-coloured
 * placeholder PNGs at those exact paths under the mod's `assets/minecraft/...` overlay.
 * No DyeableArmorItem / runtime tint involved.
 *
 *  - **Defense per type = 0** — [ScalingRobeArmorItem] writes ARMOR / ARMOR_TOUGHNESS
 *    attribute modifiers into the stack's NBT every inventory tick based on the current
 *    enchantment score, so the material's static defense would just stack on top.
 *  - **Enchantment value = 30** — higher than gold (25) and netherite (15). The whole
 *    armour-scaling design relies on robes soaking enchantments well.
 *  - **Repair = any-colour wool** — per spec; uses the vanilla `minecraft:wool` item tag.
 */
class RobeArmorMaterial(private val materialName: String) : ArmorMaterial {
    override fun getDurabilityForType(type: ArmorItem.Type): Int = when (type) {
        ArmorItem.Type.HELMET -> 110
        ArmorItem.Type.CHESTPLATE -> 160
        ArmorItem.Type.LEGGINGS -> 150
        ArmorItem.Type.BOOTS -> 130
    }
    override fun getDefenseForType(type: ArmorItem.Type): Int = 0
    override fun getEnchantmentValue(): Int = 30
    override fun getEquipSound(): SoundEvent = SoundEvents.ARMOR_EQUIP_LEATHER
    override fun getRepairIngredient(): Ingredient = Ingredient.of(ItemTags.WOOL)
    override fun getName(): String = materialName
    override fun getToughness(): Float = 0f
    override fun getKnockbackResistance(): Float = 0f

    companion object {
        /** Ygann End Cult — name keys the `end_cult_layer_{1,2}.png` worn-armor textures. */
        val END_CULT = RobeArmorMaterial("end_cult")
        /** Sselith Scholar. */
        val SCHOLAR = RobeArmorMaterial("scholar")
        /** Wohlon Blue Witch. */
        val BLUE_WITCH = RobeArmorMaterial("blue_witch")
    }
}

/**
 * Armour whose ARMOR and ARMOR_TOUGHNESS attribute modifiers are derived from the stack's
 * enchantments. The score walks linearly:
 *
 * ```
 * score 0.0 → "just below leather" (LEATHER_DEFENSE[type] − 1, toughness 0)
 * score 1.0 → "netherite tier"     (NETHERITE_DEFENSE[type], toughness 3)
 * score 1.5 → 1.5× netherite       (NETHERITE_DEFENSE[type] × 1.5, toughness 4.5)
 * score >1.5 → capped at the 1.5× values
 * ```
 *
 * **Why the NBT round-trip:** vanilla 1.20.1's `Item.getDefaultAttributeModifiers` takes
 * only the slot, not the stack — there's no clean per-stack hook. Writing the modifiers
 * to the stack's `AttributeModifiers` NBT makes `ItemStack.getAttributeModifiers` return
 * our dynamic values; we keep the NBT in sync via [inventoryTick], cached against a
 * stored score so we only rewrite when the enchantment loadout actually changes.
 */
class ScalingRobeArmorItem(
    material: ArmorMaterial,
    type: ArmorItem.Type,
    properties: Properties,
) : ArmorItem(material, type, properties) {

    override fun inventoryTick(
        stack: ItemStack, level: Level, entity: Entity, slot: Int, isSelected: Boolean,
    ) {
        // Server-only. The client receives the NBT changes via the stack sync from the
        // server side; running on both would just race.
        if (level.isClientSide) return
        syncAttributeModifiers(stack)
    }

    /** Recompute the enchantment score and rewrite the ARMOR/ARMOR_TOUGHNESS modifier
     *  NBT iff the score has shifted from what we last wrote. Cached score lives under
     *  [SCORE_NBT_KEY] so the no-op path is cheap. */
    private fun syncAttributeModifiers(stack: ItemStack) {
        val score = enchantmentScore(this.type, stack)
        val tag = stack.tag
        // No-op if we've already written modifiers for an equivalent score (the small
        // epsilon absorbs floating-point drift). Without this check every inventory tick
        // would rewrite the same NBT and force a spurious equipment-change re-sync.
        if (tag != null &&
            tag.contains("AttributeModifiers") &&
            tag.contains(SCORE_NBT_KEY) &&
            Math.abs(tag.getDouble(SCORE_NBT_KEY) - score) < SCORE_EPSILON
        ) return

        val armor = interpolateArmor(this.type, score)
        val toughness = interpolateToughness(score)
        val uuid = ARMOR_UUID_PER_TYPE.getValue(this.type)
        val slotKey = this.equipmentSlot

        // Wipe prior AttributeModifiers entry so we don't accumulate duplicates each time
        // the score changes — addAttributeModifier appends rather than replaces.
        stack.orCreateTag.remove("AttributeModifiers")
        stack.addAttributeModifier(
            Attributes.ARMOR,
            AttributeModifier(uuid, "Robe armor", armor, AttributeModifier.Operation.ADDITION),
            slotKey,
        )
        stack.addAttributeModifier(
            Attributes.ARMOR_TOUGHNESS,
            AttributeModifier(uuid, "Robe toughness", toughness, AttributeModifier.Operation.ADDITION),
            slotKey,
        )
        stack.orCreateTag.putDouble(SCORE_NBT_KEY, score)
    }

    companion object {
        /** NBT key for the last-written enchantment score. */
        private const val SCORE_NBT_KEY = "EkRobeScore"
        /** Score deltas under this don't trigger a NBT rewrite — kills idle thrash. */
        private const val SCORE_EPSILON = 0.001
        /** Hard cap on the input score; anything above maps to the same final stats. */
        private const val MAX_SCORE: Double = 1.5

        /** Vanilla's per-type armour-modifier UUIDs. Reusing them means the AttributeMap
         *  diff/merge path treats our modifiers as the standard armour slot. */
        private val ARMOR_UUID_PER_TYPE: Map<ArmorItem.Type, UUID> = mapOf(
            ArmorItem.Type.HELMET to UUID.fromString("845DB27C-C624-495F-8C9F-6020A9A58B6B"),
            ArmorItem.Type.CHESTPLATE to UUID.fromString("D8499B04-0E66-4726-AB29-64469D734E0D"),
            ArmorItem.Type.LEGGINGS to UUID.fromString("9F3D476D-C118-4544-8365-64846904B48E"),
            ArmorItem.Type.BOOTS to UUID.fromString("2AD3F246-FEE1-4E67-B886-69FD380BB150"),
        )

        // Per-type tier anchor points.
        private val LEATHER_DEFENSE: Map<ArmorItem.Type, Int> = mapOf(
            ArmorItem.Type.HELMET to 1,
            ArmorItem.Type.CHESTPLATE to 3,
            ArmorItem.Type.LEGGINGS to 2,
            ArmorItem.Type.BOOTS to 1,
        )
        private val NETHERITE_DEFENSE: Map<ArmorItem.Type, Int> = mapOf(
            ArmorItem.Type.HELMET to 3,
            ArmorItem.Type.CHESTPLATE to 8,
            ArmorItem.Type.LEGGINGS to 6,
            ArmorItem.Type.BOOTS to 3,
        )
        private const val NETHERITE_TOUGHNESS: Double = 3.0

        /** Piecewise linear: 0 → leather − 1, 1 → netherite, 1.5 → 1.5× netherite. */
        private fun interpolateArmor(type: ArmorItem.Type, score: Double): Double {
            val capped = score.coerceIn(0.0, MAX_SCORE)
            val base = (LEATHER_DEFENSE.getValue(type) - 1).toDouble()
            val netherite = NETHERITE_DEFENSE.getValue(type).toDouble()
            val ceiling = netherite * 1.5
            return if (capped <= 1.0) base + (netherite - base) * capped
            else netherite + (ceiling - netherite) * (capped - 1.0) / 0.5
        }

        /** Same curve as armour but anchored at toughness 0 / 3 / 4.5. */
        private fun interpolateToughness(score: Double): Double {
            val capped = score.coerceIn(0.0, MAX_SCORE)
            val ceiling = NETHERITE_TOUGHNESS * 1.5
            return if (capped <= 1.0) NETHERITE_TOUGHNESS * capped
            else NETHERITE_TOUGHNESS + (ceiling - NETHERITE_TOUGHNESS) * (capped - 1.0) / 0.5
        }

        /** Mean contribution across the type's reference enchantment set. Each
         *  contribution is `actualLevel / maxLevel` (uncapped — overlevel via /enchant
         *  pushes the score past 1.0 toward the 1.5× ceiling). Either-groups contribute
         *  the maximum of the two members so Depth-Strider-or-Frost-Walker counts as one
         *  slot in the average rather than two. */
        private fun enchantmentScore(type: ArmorItem.Type, stack: ItemStack): Double {
            val singles = maxEnchantsForType(type)
            val eitherGroups = eitherEnchantsForType(type)
            val totalUnits = singles.size + eitherGroups.size
            if (totalUnits == 0) return 0.0
            val singleSum = singles.entries.sumOf { (ench, max) ->
                EnchantmentHelper.getItemEnchantmentLevel(ench, stack).toDouble() / max.toDouble()
            }
            val eitherSum = eitherGroups.sumOf { group ->
                group.maxOf { (ench, max) ->
                    EnchantmentHelper.getItemEnchantmentLevel(ench, stack).toDouble() / max.toDouble()
                }
            }
            return (singleSum + eitherSum) / totalUnits.toDouble()
        }

        private fun maxEnchantsForType(type: ArmorItem.Type) = when (type) {
            ArmorItem.Type.HELMET -> HEAD_MAX
            ArmorItem.Type.CHESTPLATE -> CHEST_MAX
            ArmorItem.Type.LEGGINGS -> LEGS_MAX
            ArmorItem.Type.BOOTS -> FEET_MAX
        }

        private fun eitherEnchantsForType(type: ArmorItem.Type) = when (type) {
            ArmorItem.Type.BOOTS -> FEET_EITHER
            else -> emptyList()
        }

        // Per-slot reference enchantment sets per the user's spec.
        private val HEAD_MAX = mapOf(
            Enchantments.UNBREAKING to 3,
            Enchantments.RESPIRATION to 3,
            Enchantments.MENDING to 1,
            Enchantments.ALL_DAMAGE_PROTECTION to 4,
            Enchantments.AQUA_AFFINITY to 1,
            Enchantments.THORNS to 3,
        )
        private val CHEST_MAX = mapOf(
            Enchantments.UNBREAKING to 3,
            Enchantments.ALL_DAMAGE_PROTECTION to 4,
            Enchantments.MENDING to 1,
            Enchantments.THORNS to 3,
        )
        private val LEGS_MAX = mapOf(
            Enchantments.UNBREAKING to 3,
            Enchantments.ALL_DAMAGE_PROTECTION to 4,
            Enchantments.MENDING to 1,
            Enchantments.THORNS to 3,
        )
        private val FEET_MAX = mapOf(
            Enchantments.UNBREAKING to 3,
            Enchantments.ALL_DAMAGE_PROTECTION to 4,
            Enchantments.MENDING to 1,
            Enchantments.THORNS to 3,
            Enchantments.FALL_PROTECTION to 4,
            Enchantments.SOUL_SPEED to 3,
        )
        private val FEET_EITHER = listOf(
            mapOf(Enchantments.DEPTH_STRIDER to 3, Enchantments.FROST_WALKER to 2),
        )
    }
}
