package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.food.FoodData;
import org.shipwrights.enderkinesis.item.AmbrosialCravingScaling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Scales the {@code nutrition} and {@code saturation} args of the
 * inner {@code FoodData.eat(int, float)} call inside
 * {@code FoodData.eat(Item, ItemStack)} by
 * {@link AmbrosialCravingScaling#SCALE} when the thread-local flag is
 * set (i.e. inside a player.eat call whose wearer is afflicted).
 *
 * <p>The nutrition path uses {@code Math.max(0, round(n × scale))} so
 * tiny foods (1- or 2-nutrition snacks) round down to 0 — under
 * Ambrosial Craving the small snacks really are useless.
 */
@Mixin(FoodData.class)
public abstract class FoodDataAmbrosialCravingScaleMixin {

    @ModifyArg(
        method = "eat(Lnet/minecraft/world/item/Item;Lnet/minecraft/world/item/ItemStack;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/food/FoodData;eat(IF)V"
        ),
        index = 0
    )
    private int enderkinesis$scaleNutrition(int nutrition) {
        if (!AmbrosialCravingScaling.active()) return nutrition;
        return Math.max(0, Math.round(nutrition * AmbrosialCravingScaling.SCALE));
    }

    @ModifyArg(
        method = "eat(Lnet/minecraft/world/item/Item;Lnet/minecraft/world/item/ItemStack;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/food/FoodData;eat(IF)V"
        ),
        index = 1
    )
    private float enderkinesis$scaleSaturation(float saturation) {
        if (!AmbrosialCravingScaling.active()) return saturation;
        return saturation * AmbrosialCravingScaling.SCALE;
    }
}
