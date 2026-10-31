package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.shipwrights.enderkinesis.entity.RaisedSkeletonEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Projectile.class)
public abstract class ProjectileRaisedSkeletonMixin {

    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$passThroughPackmates(Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (RaisedSkeletonEntity.isPackmate(((Projectile) (Object) this).getOwner(), target)) {
            cir.setReturnValue(false);
        }
    }
}
