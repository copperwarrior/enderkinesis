package org.shipwrights.enderkinesis.item

import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ArmorItem
import net.minecraft.world.item.ArmorMaterials
import net.minecraft.world.item.FishingRodItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ProjectileWeaponItem
import net.minecraft.world.item.Items
import net.minecraft.world.item.ShieldItem
import net.minecraft.world.item.Tiers
import net.minecraft.world.item.TieredItem
import java.util.WeakHashMap

/** The Grand Staff of Decay rots what its targets carry, and feeds what rots to a casting player. Server thread only. */
object DecaySpoilage {

    private const val GEAR_WEAR = 1
    private const val SATURATION_MODIFIER = 0.5f
    private const val SPOILED_FOOD_SHARE = 0.5f

    // FoodData takes whole points; parts of a point wait here until they add to one.
    private val owed = WeakHashMap<Player, Float>()

    /** One food item of [target] rots, and its wooden and leather gear wears. Returns the nutrition the rot took. */
    fun spoil(target: LivingEntity): Float {
        for (slot in EquipmentSlot.entries) {
            val stack = target.getItemBySlot(slot)
            if (rotsAsGear(stack)) stack.hurtAndBreak(GEAR_WEAR, target) { it.broadcastBreakEvent(slot) }
        }
        return if (target is Player) spoilFood(target) * SPOILED_FOOD_SHARE else 0f
    }

    private fun rotsAsGear(stack: ItemStack): Boolean {
        if (!stack.isDamageableItem) return false
        val item = stack.item
        return (item is TieredItem && item.tier == Tiers.WOOD) ||
            (item is ArmorItem && item.material == ArmorMaterials.LEATHER) ||
            item is ShieldItem || item is ProjectileWeaponItem || item is FishingRodItem
    }

    private fun spoilFood(player: Player): Float {
        val inventory = player.inventory
        val size = inventory.containerSize
        // Start at a random slot, so the rot does not always take the first stack.
        val start = player.random.nextInt(size)
        for (i in 0 until size) {
            val slot = (start + i) % size
            val stack = inventory.getItem(slot)
            val food = stack.item.foodProperties ?: continue
            // Food with effects is already rotten, or magic.
            if (food.effects.isNotEmpty()) continue
            val rotten = if (stack.`is`(Items.POTATO) || stack.`is`(Items.BAKED_POTATO)) Items.POISONOUS_POTATO else Items.ROTTEN_FLESH
            stack.shrink(1)
            inventory.placeItemBackInInventory(ItemStack(rotten))
            return food.nutrition.toFloat()
        }
        return 0f
    }

    fun feed(caster: LivingEntity, nutrition: Float) {
        if (caster !is Player || nutrition <= 0f) return
        val total = (owed[caster] ?: 0f) + nutrition
        val whole = total.toInt()
        owed[caster] = total - whole
        if (whole > 0) caster.foodData.eat(whole, SATURATION_MODIFIER)
    }
}
