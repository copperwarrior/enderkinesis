package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.dimension.SselithParallelView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Players on other parallels are unpushable on the client, as a Cataloger is. Player motion is client-side, so this is enough. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityParallelPushMixin {

    @Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$noPushAcrossParallels(CallbackInfoReturnable<Boolean> cir) {
        if (SselithParallelView.INSTANCE.isOtherParallel((Entity) (Object) this)) cir.setReturnValue(false);
    }
}
