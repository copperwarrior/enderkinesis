package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.entity.ShilleagkDeerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prey eaten by a predator dies with no drops; the vanilla loot path has no per-kill switch. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityEatenNoLootMixin {
    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$skipEatenLoot(DamageSource source, CallbackInfo ci) {
        if (((LivingEntity) (Object) this).getTags().contains(ShilleagkDeerEntity.EATEN_TAG)) ci.cancel();
    }
}
