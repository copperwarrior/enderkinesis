package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.spell.SpellDeaths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntitySpellDeathMixin {

    @ModifyConstant(method = "tickDeath", constant = @Constant(intValue = 20))
    private int enderkinesis$deathTicks(int vanilla) {
        return SpellDeaths.deathTicks((LivingEntity) (Object) this, vanilla);
    }

    @Inject(method = "makePoofParticles", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$noPoof(CallbackInfo ci) {
        if (!SpellDeaths.keepsPoof((LivingEntity) (Object) this)) ci.cancel();
    }
}
