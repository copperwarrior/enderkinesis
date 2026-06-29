package org.shipwrights.enderkinesis.item

import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.food.FoodProperties
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.registry.EKEffects

/** Wey'ye Fruit — a single bite fully restores hunger AND saturation,
 *  is edible even on a full bar, and irreversibly inflicts
 *  [org.shipwrights.enderkinesis.effect.AmbrosialCravingEffect].
 *
 *  Food properties: 20 nutrition + 0.5 saturation modifier means the
 *  vanilla `min(currentSat + 20 × 0.5 × 2, foodLevel)` math caps
 *  saturation at the (now full) food level — i.e. both bars top out.
 *  `alwaysEat()` keeps the right-click usable when the bar is already
 *  full, which is the whole point — the player can't avoid the curse by
 *  staying fed.
 *
 *  Ambrosial Craving is applied **directly here** rather than via the
 *  vanilla food-effect list because the food-effect path is gated by a
 *  probability roll and runs through the same `addEatEffect` mixin
 *  that scales all other food durations × 0.2 — both of which we want
 *  to side-step for the curse itself. Direct `addEffect` lands the
 *  permanent flag deterministically. */
class WeyyeFruitItem(properties: Properties) : Item(
    properties.food(
        FoodProperties.Builder()
            .nutrition(20)
            .saturationMod(0.5f)
            .alwaysEat()
            // Brief sweetener buffs. The Player.eat mixin exempts the
            // fruit itself from the 0.2× duration scaling, so these
            // land at full length even on the n-th bite.
            .effect(MobEffectInstance(MobEffects.ABSORPTION, 1200, 0), 1.0f)
            .effect(MobEffectInstance(MobEffects.MOVEMENT_SPEED, 1200, 0), 1.0f)
            .build()
    )
) {
    override fun finishUsingItem(stack: ItemStack, level: Level, entity: LivingEntity): ItemStack {
        if (entity is Player && !level.isClientSide) {
            // Permanent debuff, no particles, HUD icon shown.
            entity.addEffect(
                MobEffectInstance(
                    EKEffects.ambrosialCravingEffect(),
                    Int.MAX_VALUE,
                    0,
                    /* ambient = */ false,
                    /* visible = */ false,
                    /* showIcon = */ true,
                )
            )
        }
        return super.finishUsingItem(stack, level, entity)
    }
}
