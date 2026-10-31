package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.dimension.EKDamageSources;
import org.shipwrights.enderkinesis.effect.EndingEffects;
import org.shipwrights.enderkinesis.effect.SpellCredit;
import org.shipwrights.enderkinesis.effect.SpellEffects;
import org.shipwrights.enderkinesis.effect.WeightedEffect;
import org.shipwrights.enderkinesis.registry.EKEffects;
import org.shipwrights.enderkinesis.spell.BloodPacts;
import org.shipwrights.enderkinesis.spell.SpellHolds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@code Player} overrides {@code actuallyHurt}, so {@code PlayerSpellEffectsMixin} covers Bloodbound for players. */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySpellEffectsMixin {

    @Inject(method = "getJumpPower", at = @At("RETURN"), cancellable = true)
    private void enderkinesis$weightedJump(CallbackInfoReturnable<Float> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.hasEffect(EKEffects.INSTANCE.getWEIGHTED().get()) || SpellHolds.isHeld(self)) cir.setReturnValue(0f);
    }

    @Inject(method = "canBeAffected", at = @At("RETURN"), cancellable = true)
    private void enderkinesis$bossImmunity(MobEffectInstance instance, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && SpellEffects.bossImmune((LivingEntity) (Object) this, instance.getEffect())) cir.setReturnValue(false);
    }

    @Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"))
    private void enderkinesis$credit(MobEffectInstance instance, Entity source, CallbackInfoReturnable<Boolean> cir) {
        SpellCredit.applied((LivingEntity) (Object) this, instance.getEffect(), source);
    }

    @WrapMethod(method = "onEffectRemoved")
    private void enderkinesis$effectEnding(MobEffectInstance instance, Operation<Void> original) {
        EndingEffects.begin((LivingEntity) (Object) this, instance.getEffect());
        try {
            original.call(instance);
        } finally {
            EndingEffects.end();
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void enderkinesis$loaded(CompoundTag tag, CallbackInfo ci) {
        SpellHolds.loaded((LivingEntity) (Object) this);
    }

    @Shadow
    protected abstract int calculateFallDamage(float distance, float multiplier);

    /** Gravity adds damage equal to the fall damage (see WeightedEffect). */
    @Inject(method = "causeFallDamage", at = @At("RETURN"))
    private void enderkinesis$weightedFall(float distance, float multiplier, DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        WeightedEffect.landed((LivingEntity) (Object) this, calculateFallDamage(distance, multiplier));
    }

    /** Effect damage names its caster for the kill, but must not knock the target back (EKDamageSources.EffectDamage). */
    @WrapOperation(method = "hurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;knockback(DDD)V"))
    private void enderkinesis$effectNoKnockback(LivingEntity self, double strength, double x, double z, Operation<Void> original,
                                                @Local(argsOnly = true) DamageSource source) {
        if (!(source instanceof EKDamageSources.EffectDamage)) original.call(self, strength, x, z);
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
