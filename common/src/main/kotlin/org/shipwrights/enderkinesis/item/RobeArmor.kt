package org.shipwrights.enderkinesis.item

import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.ItemTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ArmorMaterial
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * The material `name` feeds the worn-armor texture lookup. Textures live at
 * `assets/enderkinesis/textures/models/armor/{name}_layer_{1|2}.png`; on Forge the path
 * is returned via [ScalingRobeArmorItem.getArmorTexture], and Fabric's
 * `TightRobeArmorRenderer` builds the same path explicitly.
 *
 *  - **Defense per type = 0** — [ScalingRobeArmorItem] writes the real values into NBT
 *    every inventory tick based on enchantment score.
 *  - **Enchantment value = 30** — higher than gold (25) and netherite (15). Robes are
 *    designed to soak enchantments.
 *  - **Repair = any-colour wool** — vanilla `minecraft:wool` item tag.
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
        val END_CULT = RobeArmorMaterial("end_cult")
        val SCHOLAR = RobeArmorMaterial("scholar")
        val BLUE_WITCH = RobeArmorMaterial("blue_witch")
    }
}

/**
 * Armour whose ARMOR + ARMOR_TOUGHNESS scale with the wearer's combined enchantment
 * score across every worn piece AND every Curios/Trinkets accessory:
 *
 * ```
 * s = 0.0 → leather − 1   (toughness 0)
 * s = 1.0 → netherite     (toughness 3)
 * s = 1.5 → 1.5× nether   (toughness 4.5, old hard-cap point)
 * s → ∞   → 2× netherite  (asymptote past 1.5; diminishing returns, no hard cap)
 * ```
 *
 * Score = mean per-piece `sum(level/maxLevel)/numEnchants` against a reference set: armour
 * uses the slot-specific [HEAD_MAX]/[CHEST_MAX]/[LEGS_MAX]/[FEET_MAX] map ([FEET_EITHER]
 * picks Depth-Strider-or-Frost-Walker); accessories use the generic [GENERIC_MAX].
 *
 * Accessory inputs come through [AccessoryGatherer] — common stays free of Curios/Trinkets
 * symbols; each loader's compat module registers a gatherer closure under a
 * `Platform.isModLoaded` guard. With no provider, score falls back to the four armour slots.
 *
 * NBT round-trip: 1.20.1's `Item.getDefaultAttributeModifiers` takes only the slot, no
 * per-stack hook — so we write modifiers to the stack's `AttributeModifiers` tag in
 * [inventoryTick], cached against the stored score to skip rewrites when the loadout
 * hasn't changed.
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
        if (entity !is LivingEntity) return
        syncAttributeModifiers(stack, entity)
    }

    /** Forge `IItemExtension.getArmorTexture` shim — invoked by `HumanoidArmorLayer`.
     *  Fabric uses [org.shipwrights.enderkinesis.fabric.client.TightRobeArmorRenderer] which
     *  builds the same path independently. */
    @Suppress("unused")
    fun getArmorTexture(stack: ItemStack, entity: Entity, slot: EquipmentSlot, type: String?): String {
        val layer = if (slot == EquipmentSlot.LEGS) 2 else 1
        return "enderkinesis:textures/models/armor/${material.name}_layer_$layer.png"
    }

    /** Recompute the enchantment score and rewrite the ARMOR/ARMOR_TOUGHNESS modifier
     *  NBT iff the score has shifted from what we last wrote. Cached score lives under
     *  [SCORE_NBT_KEY] so the no-op path is cheap. */
    private fun syncAttributeModifiers(stack: ItemStack, wearer: LivingEntity) {
        val score = combinedEnchantmentScore(wearer)
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
        /**
         * Diminishing-returns rate past the old `1.5 × netherite` cap point. `k = 2` keeps
         * the slope continuous at s = 1.5 — the linear segment between s = 1 and s = 1.5
         * climbs at rate `N` per score unit, and `2 · (0.5 N) = N` matches that exactly.
         */
        private const val DIMINISH_K: Double = 2.0

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

        /**
         * Piecewise:
         *  - s ∈ [0, 1]   : linear (leather − 1) → netherite (slope `N − L + 1`)
         *  - s ∈ [1, 1.5] : linear netherite → 1.5 × netherite (slope `N`, where the old
         *                    cap used to land)
         *  - s > 1.5      : asymptotic approach to `2 × netherite` (diminishing returns).
         *  Slope is continuous at s = 1.5.
         */
        private fun interpolateArmor(type: ArmorItem.Type, score: Double): Double {
            val s = score.coerceAtLeast(0.0)
            val base = (LEATHER_DEFENSE.getValue(type) - 1).toDouble()
            val netherite = NETHERITE_DEFENSE.getValue(type).toDouble()
            val priorCap = 1.5 * netherite
            return when {
                s <= 1.0 -> base + (netherite - base) * s
                s <= 1.5 -> netherite * s
                else -> priorCap + 0.5 * netherite * (1.0 - Math.exp(-DIMINISH_K * (s - 1.5)))
            }
        }

        /** Same shape as [interpolateArmor]: linear 0 → 4.5 over s ∈ [0, 1.5], then
         *  asymptotic approach to 6 past s = 1.5. */
        private fun interpolateToughness(score: Double): Double {
            val s = score.coerceAtLeast(0.0)
            val priorCap = NETHERITE_TOUGHNESS * 1.5
            return if (s <= 1.5) NETHERITE_TOUGHNESS * s
            else priorCap + (NETHERITE_TOUGHNESS * 0.5) * (1.0 - Math.exp(-DIMINISH_K * (s - 1.5)))
        }

        /** Mean per-item score across every worn piece (armor + curios + trinkets),
         *  excluding mainhand + offhand. Each item's score is computed against its slot-
         *  appropriate reference set (or [GENERIC_MAX] for non-armor accessories). */
        private fun combinedEnchantmentScore(wearer: LivingEntity): Double {
            val stacks = ArrayList<ItemStack>(8)
            // Vanilla armor: HEAD, CHEST, LEGS, FEET. Exclude hands explicitly.
            for (slot in EquipmentSlot.values()) {
                if (slot.type == EquipmentSlot.Type.ARMOR) {
                    val s = wearer.getItemBySlot(slot)
                    if (!s.isEmpty) stacks.add(s)
                }
            }
            stacks.addAll(AccessoryGatherer.gather(wearer))
            if (stacks.isEmpty()) return 0.0
            return stacks.sumOf { stackScore(it) } / stacks.size
        }

        /** Score for a single stack — `Σ(level/max) / numEnchants` against either the
         *  slot-specific reference map (armor pieces) or [GENERIC_MAX] (accessories). */
        private fun stackScore(stack: ItemStack): Double {
            val item = stack.item
            val singles: Map<Enchantment, Int>
            val eitherGroups: List<Map<Enchantment, Int>>
            if (item is ArmorItem) {
                singles = maxEnchantsForType(item.type)
                eitherGroups = eitherEnchantsForType(item.type)
            } else {
                singles = GENERIC_MAX
                eitherGroups = emptyList()
            }
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

        /** Generic reference set for curios / trinkets / non-armor worn items. Just the
         *  durability-and-protection trio that meaningfully overlaps every accessory
         *  type — slot-specific enchants like Aqua Affinity don't generalise. */
        private val GENERIC_MAX = mapOf(
            Enchantments.UNBREAKING to 3,
            Enchantments.MENDING to 1,
            Enchantments.ALL_DAMAGE_PROTECTION to 4,
        )

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

/**
 * Registration point for accessory-mod compat — Curios on Forge, Trinkets on Fabric. The
 * common module knows nothing about either API; each platform's compat code does a
 * `Platform.isModLoaded(...)` check at init and calls [register] with a gatherer closure
 * that uses the platform-specific API directly (with the API added as `modCompileOnly`
 * in that module's `build.gradle`).
 *
 * Empty by default — if no platform registers anything, [gather] returns an empty list
 * and the combined enchantment score is computed solely from the four vanilla armor
 * slots. Multiple providers can register (e.g. a single platform with both Curios and
 * another accessory mod loaded); their stacks are concatenated.
 *
 * Suggested usage in a platform module:
 * ```
 * // forge/.../compat/curios/CuriosCompat.kt
 * object CuriosCompat {
 *     fun init() {
 *         if (!Platform.isModLoaded("curios")) return
 *         AccessoryGatherer.register { e ->
 *             val handler = CuriosApi.getCuriosInventory(e).orElse(null) ?: return@register emptyList()
 *             val items = handler.equippedCurios
 *             (0 until items.slots).mapNotNull { items.getStackInSlot(it).takeUnless { s -> s.isEmpty } }
 *         }
 *     }
 * }
 * ```
 */
object AccessoryGatherer {
    private val providers = ArrayList<(LivingEntity) -> List<ItemStack>>()

    fun register(provider: (LivingEntity) -> List<ItemStack>) {
        providers.add(provider)
    }

    fun gather(wearer: LivingEntity): List<ItemStack> {
        if (providers.isEmpty()) return emptyList()
        if (providers.size == 1) return providers[0](wearer)
        return providers.flatMap { it(wearer) }
    }
}
