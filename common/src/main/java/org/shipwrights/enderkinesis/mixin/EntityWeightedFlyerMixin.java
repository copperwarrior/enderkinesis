package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.shipwrights.enderkinesis.effect.WeightedEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Injects on the move, because the AI and travel of every flyer end there (see WeightedEffect). */
@Mixin(Entity.class)
public abstract class EntityWeightedFlyerMixin {

    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
    private Vec3 enderkinesis$weightedFlyerFalls(Vec3 motion, @Local(argsOnly = true) MoverType type) {
        return WeightedEffect.flyerMotion((Entity) (Object) this, type, motion);
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void enderkinesis$weightedFlyerLanded(MoverType type, Vec3 motion, CallbackInfo ci) {
        WeightedEffect.flyerMoved((Entity) (Object) this);
    }
}
