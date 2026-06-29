package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.item.AmbrosialCravingScaling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Scales the duration of every food-borne {@link MobEffectInstance}
 * applied via {@code LivingEntity.addEatEffect} by
 * {@link AmbrosialCravingScaling#SCALE} when the thread-local flag is
 * set. We rebuild the instance with the shortened duration rather than
 * mutating it in place because {@code MobEffectInstance} doesn't expose
 * a duration setter on the call path.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityAddEatEffectAmbrosialCravingScaleMixin {

    @ModifyArg(
        method = "addEatEffect(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z"
        )
    )
    private MobEffectInstance enderkinesis$scaleEffectDuration(MobEffectInstance original) {
        if (!AmbrosialCravingScaling.active()) return original;
        int scaled = Math.max(1, Math.round(original.getDuration() * AmbrosialCravingScaling.SCALE));
        return new MobEffectInstance(
            original.getEffect(),
            scaled,
            original.getAmplifier(),
            original.isAmbient(),
            original.isVisible(),
            original.showIcon()
        );
    }
}
