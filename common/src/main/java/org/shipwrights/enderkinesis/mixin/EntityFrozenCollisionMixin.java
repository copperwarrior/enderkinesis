package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.spell.SpellVisuals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A mob frozen solid has physical presence: other entities collide with it and can stand
 *  on it, as on a boat. */
@Mixin(Entity.class)
public abstract class EntityFrozenCollisionMixin {

    @Inject(method = "canBeCollidedWith", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$frozenSolid(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof LivingEntity living && SpellVisuals.isFrozen(living)) cir.setReturnValue(true);
    }
}
