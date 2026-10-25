package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.registry.EKEffects;
import org.shipwrights.enderkinesis.spell.BloodPacts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The Mystic Scroll status effects that reach outside an effect tick: Weighted (no jumping,
 *  double fall damage), Hollowed (no healing) and Bloodbound (redirected damage; players are
 *  covered by {@code PlayerSpellEffectsMixin}, as {@code Player} overrides {@code actuallyHurt}). */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySpellEffectsMixin {

    @Inject(method = "getJumpPower", at = @At("RETURN"), cancellable = true)
    private void enderkinesis$weightedJump(CallbackInfoReturnable<Float> cir) {
        if (((LivingEntity) (Object) this).hasEffect(EKEffects.INSTANCE.getWEIGHTED().get())) cir.setReturnValue(0f);
    }

    @Inject(method = "calculateFallDamage", at = @At("RETURN"), cancellable = true)
    private void enderkinesis$weightedFall(float distance, float multiplier, CallbackInfoReturnable<Integer> cir) {
        if (((LivingEntity) (Object) this).hasEffect(EKEffects.INSTANCE.getWEIGHTED().get())) cir.setReturnValue(cir.getReturnValue() * 2);
    }

    @Inject(method = "heal", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$hollowed(float amount, CallbackInfo ci) {
        if (((LivingEntity) (Object) this).hasEffect(EKEffects.INSTANCE.getHOLLOWED().get())) ci.cancel();
    }

    @ModifyVariable(method = "actuallyHurt", at = @At("HEAD"), argsOnly = true)
    private float enderkinesis$bloodbound(float amount) {
        return BloodPacts.redirect((LivingEntity) (Object) this, amount);
    }
}
