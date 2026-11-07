package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.dimension.WeheloPocket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Only a removal that destroys the entity; an unloaded chunk or a dimension change keeps its way back.
@Mixin(Entity.class)
public abstract class EntityWeheloReturnCleanupMixin {

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void enderkinesis$forgetWeheloReturn(Entity.RemovalReason reason, CallbackInfo ci) {
        if (reason.shouldDestroy()) WeheloPocket.forget((Entity) (Object) this);
    }
}
