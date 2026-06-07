package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.phys.Vec3;
import org.shipwrights.enderkinesis.duck.SwimmingHolder;
import org.shipwrights.enderkinesis.registry.EKEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

    /** Matches the threshold in {@link EntityMixin}; keeps the swim/underwater fake in
     *  sync with the active-submerged window so it doesn't linger into pure-grace ticks. */
    private static final int ACTIVE_DURATION_THRESHOLD = 45;

    /** Per-tick upward Δv when jump is held. Mirrors vanilla's {@code jumpInLiquid}
     *  (0.04) so the rise speed feels like real water. */
    private static final double SWIM_JUMP_THRUST = 0.04;
    /** Tiny upward Δv when jump isn't held — exactly cancels vanilla water's
     *  {@code getFluidFallingAdjustedMovement} sink (gravity / 16 = 0.005) so a
     *  motionless player floats at the surface instead of slowly drifting down. */
    private static final double SWIM_PASSIVE_BUOYANCY = 0.005;

    @Inject(method = "isUnderWater", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$isUnderWaterInVirtualFluid(CallbackInfoReturnable<Boolean> cir) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (self.isSpectator() || self.getAbilities().flying) return;
        MobEffectInstance instance = self.getEffect(
            EKEffects.INSTANCE.getCREPUSCULAR_FLOATATION().get());
        if (instance == null || instance.getDuration() < ACTIVE_DURATION_THRESHOLD) return;
        cir.setReturnValue(true);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void enderkinesis$applySwimThrust(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (self.isSpectator() || self.getAbilities().flying) return;
        MobEffectInstance instance = self.getEffect(
            EKEffects.INSTANCE.getCREPUSCULAR_FLOATATION().get());
        if (instance == null || instance.getDuration() < ACTIVE_DURATION_THRESHOLD) return;

        boolean jumping = ((SwimmingHolder) (Object) self).enderkinesis$isJumping();
        // Jump → rise; otherwise tiny upward bias cancels vanilla water gravity (no sneak-down: mirror vanilla water).
        double dy = jumping ? SWIM_JUMP_THRUST : SWIM_PASSIVE_BUOYANCY;

        Vec3 mv = self.getDeltaMovement();
        self.setDeltaMovement(mv.x, mv.y + dy, mv.z);
    }
}
