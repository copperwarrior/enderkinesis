package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import org.shipwrights.enderkinesis.ritual.SelvesBodies;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobSelvesMixin {

    @ModifyReturnValue(method = "isSunBurnTick", at = @At("RETURN"))
    private boolean enderkinesis$selvesBodySun(boolean original) {
        return original && !SelvesBodies.losesTrait((Mob) (Object) this);
    }

    // Before the add, so the old body's discard right after finds no record to drop.
    @WrapOperation(
        method = "convertTo",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z")
    )
    private boolean enderkinesis$selvesBodyConvert(Level level, Entity converted, Operation<Boolean> original) {
        SelvesBodies.onConverting((Mob) (Object) this, converted);
        return original.call(level, converted);
    }

    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$selvesBodyReclaimed(CallbackInfo ci) {
        if (SelvesBodies.reclaimed((Mob) (Object) this)) ci.cancel();
    }
}
