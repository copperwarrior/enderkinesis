package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.ritual.Selves;
import org.shipwrights.enderkinesis.ritual.SelvesBodies;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntitySelvesMixin {

    @ModifyReturnValue(method = "canBreatheUnderwater", at = @At("RETURN"))
    private boolean enderkinesis$selvesGills(boolean original) {
        return Selves.breathesWater((LivingEntity) (Object) this, original);
    }

    // At the drowning check, because water mobs override canBreatheUnderwater.
    @ModifyExpressionValue(
        method = "baseTick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;canBreatheUnderwater()Z")
    )
    private boolean enderkinesis$selvesBodyDrowns(boolean original) {
        return original && !SelvesBodies.losesTrait((LivingEntity) (Object) this);
    }

    @ModifyReturnValue(method = "onClimbable", at = @At("RETURN"))
    private boolean enderkinesis$selvesClimb(boolean original) {
        return Selves.climbs((LivingEntity) (Object) this, original);
    }

    @ModifyReturnValue(method = "isSensitiveToWater", at = @At("RETURN"))
    private boolean enderkinesis$selvesBodyWater(boolean original) {
        return original && !SelvesBodies.losesTrait((LivingEntity) (Object) this);
    }

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$selvesBodyLoot(DamageSource source, CallbackInfo ci) {
        if (SelvesBodies.dropLoot((LivingEntity) (Object) this)) ci.cancel();
    }
}
