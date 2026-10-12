package org.shipwrights.enderkinesis.fabric.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.MilkBucketItem;
import org.shipwrights.enderkinesis.effect.MadnessEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Fabric only: Forge's milk calls curePotionEffects instead, which MadnessEffect answers. */
@Mixin(MilkBucketItem.class)
public abstract class MilkBucketItemMadnessMixin {

    @Redirect(
        method = "finishUsingItem",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;removeAllEffects()Z")
    )
    private boolean enderkinesis$keepMadness(LivingEntity entity) {
        return MadnessEffect.removeAllExceptMadness(entity);
    }
}
