package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.shipwrights.enderkinesis.duck.RitualShotHolder;
import org.shipwrights.enderkinesis.ritual.Rituals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
public abstract class ProjectileRitualShotMixin implements RitualShotHolder {

    @Unique
    private @Nullable Object enderkinesis$ritualShot;

    @Override
    public @Nullable Object enderkinesis$getRitualShot() {
        return enderkinesis$ritualShot;
    }

    @Override
    public void enderkinesis$setRitualShot(@Nullable Object shot) {
        enderkinesis$ritualShot = shot;
    }

    @Inject(method = "onHit", at = @At("HEAD"))
    private void enderkinesis$ritualShotHit(HitResult result, CallbackInfo ci) {
        if (enderkinesis$ritualShot != null) Rituals.onProjectileHit((Projectile) (Object) this, result);
    }
}
