package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.food.FoodProperties
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.dimension.SselithMadness

class WaybreadItem(properties: Properties) : Item(properties.food(BASE_FOOD)) {

    // Fabric has no food hook that reads the eater, thus vanilla gives the base food and this adds the remainder.
    override fun finishUsingItem(stack: ItemStack, level: Level, entity: LivingEntity): ItemStack {
        val result = super.finishUsingItem(stack, level, entity)
        if (entity is ServerPlayer) {
            val madness = SselithMadness.level(entity).toFloat() / SselithMadness.MAX_LEVEL
            val nutrition = Math.round(Mth.lerp(madness, HALF_BREAD_NUTRITION, GOLDEN_CARROT_NUTRITION))
            val saturation = Mth.lerp(madness, HALF_BREAD_SATURATION, GOLDEN_CARROT_SATURATION)
            val food = entity.foodData
            food.eat(nutrition - BASE_NUTRITION, 0f)
            food.setSaturation(minOf(food.saturationLevel + saturation - HALF_BREAD_SATURATION, food.foodLevel.toFloat()))
            if (SselithMadness.level(entity) > MIN_CURED_LEVEL && entity.random.nextFloat() < CURE_CHANCE) {
                SselithMadness.addLevel(entity, -1)
            }
        }
        return result
    }

    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip.add(Component.translatable("item.enderkinesis.waybread.tooltip").withStyle(ChatFormatting.GRAY))
    }

    private companion object {
        // Bread is 5 nutrition and 6 saturation. A golden carrot is 6 nutrition and 14.4 saturation.
        const val HALF_BREAD_NUTRITION = 2.5f
        const val HALF_BREAD_SATURATION = 3.0f
        const val GOLDEN_CARROT_NUTRITION = 6.0f
        const val GOLDEN_CARROT_SATURATION = 14.4f
        const val BASE_NUTRITION = 3
        const val CURE_CHANCE = 0.25f
        const val MIN_CURED_LEVEL = 2

        val BASE_FOOD: FoodProperties = FoodProperties.Builder()
            .nutrition(BASE_NUTRITION)
            .saturationMod(HALF_BREAD_SATURATION / (2f * BASE_NUTRITION))
            .build()
    }
}
