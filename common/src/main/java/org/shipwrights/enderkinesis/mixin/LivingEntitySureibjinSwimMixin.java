package org.shipwrights.enderkinesis.mixin;

import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.Fluid;
import org.shipwrights.enderkinesis.dimension.Sureibjin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In Sureibjin the swim-up boost from {@link LivingEntity#jumpInLiquid}
 * is reduced from vanilla's +0.04 Y per tick to +0.003 Y
 * ({@link Sureibjin#applyWeakSwim}), which sits just under the in-water gravity pull of 0.005 Y
 * per tick — so holding jump in water slowly sinks the player instead of
 * lifting them, reinforcing the dimension's drown-to-wake mechanic.
 *
 * <p>The disruption only kicks in when the entity is FULLY SUBMERGED
 * ({@link LivingEntity#isUnderWater}, i.e. eyes underwater). Wading
 * through shallow water with feet wet still uses vanilla swim, so the
 * player can wake themselves by jumping out of the shallows — the trap
 * only closes once they're actually drowning.
 *
 * <p>Sureibjin has natural mob spawning disabled, so this effectively
 * only affects players. Both the client {@code LocalPlayer.aiStep} and
 * the server {@code ServerPlayer.aiStep} call into this method, so the
 * mixin is registered in the common (server+client) list.
 *
 * <p>Forge {@code aiStep} calls {@code jumpInFluid} and never this method. The Forge module has
 * {@code LivingEntitySureibjinSwimForgeMixin} for that path.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySureibjinSwimMixin {

    @Inject(method = "jumpInLiquid", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$weakenSwimInSureibjin(
        TagKey<Fluid> fluidTag, CallbackInfo ci
    ) {
        if (Sureibjin.applyWeakSwim((LivingEntity) (Object) this)) ci.cancel();
    }
}
