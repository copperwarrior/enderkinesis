package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.shipwrights.enderkinesis.block.IchaericInkPhysics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityIchaericInkMixin {

    @Shadow protected boolean jumping;
    @Shadow private int noJumpDelay;
    @Shadow protected abstract boolean isAffectedByFluids();
    @Shadow protected abstract int decreaseAirSupply(int air);

    @Unique private int enderkinesis$airBefore;

    // Both loaders' travel take the water branch through isInWater(); ink takes it too without being water.
    @ModifyExpressionValue(
        method = "travel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isInWater()Z")
    )
    private boolean enderkinesis$travelInInkAsInWater(boolean inWater) {
        return inWater || IchaericInkPhysics.INSTANCE.inInk((LivingEntity) (Object) this);
    }

    @Inject(method = "travel", at = @At("TAIL"))
    private void enderkinesis$dragInInk(Vec3 travelVector, CallbackInfo ci) {
        IchaericInkPhysics.drag((LivingEntity) (Object) this);
    }

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void enderkinesis$swimUpInInk(CallbackInfo ci) {
        if (!jumping) return;
        // A lift off the bottom stands in for the jump, as in water; aiStep decrements this once before it jumps.
        if (IchaericInkPhysics.swimUp((LivingEntity) (Object) this, isAffectedByFluids())) noJumpDelay = Math.max(noJumpDelay, 2);
    }

    @Inject(method = "baseTick", at = @At("HEAD"))
    private void enderkinesis$rememberAir(CallbackInfo ci) {
        enderkinesis$airBefore = ((LivingEntity) (Object) this).getAirSupply();
    }

    // Each loader refills air when the eyes are in no fluid it knows; ink overrides that afterwards.
    @Inject(method = "baseTick", at = @At("TAIL"))
    private void enderkinesis$drownInInk(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!IchaericInkPhysics.drowns(self)) return;
        int air = decreaseAirSupply(enderkinesis$airBefore);
        self.setAirSupply(air);
        if (air == -20) {
            self.setAirSupply(0);
            if (!self.level().isClientSide) self.hurt(self.damageSources().drown(), 2.0F);
        }
    }
}
