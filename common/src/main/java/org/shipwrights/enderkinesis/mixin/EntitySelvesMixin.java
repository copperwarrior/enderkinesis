package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.ritual.Selves;
import org.shipwrights.enderkinesis.ritual.SelvesBodies;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntitySelvesMixin {

    @ModifyReturnValue(method = "fireImmune", at = @At("RETURN"))
    private boolean enderkinesis$selvesFireImmune(boolean original) {
        Entity self = (Entity) (Object) this;
        if (self instanceof Player player) return Selves.fireImmune(player, original);
        return original && !SelvesBodies.losesTrait(self);
    }

    @ModifyReturnValue(method = "isNoGravity", at = @At("RETURN"))
    private boolean enderkinesis$selvesGravity(boolean original) {
        return original && !SelvesBodies.losesTrait((Entity) (Object) this);
    }

    // Wrapped at the call site: many mobs override getDimensions without calling up.
    @WrapOperation(
        method = "refreshDimensions",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;")
    )
    private EntityDimensions enderkinesis$selvesBodySize(Entity self, Pose pose, Operation<EntityDimensions> original) {
        return SelvesBodies.dimensions(self, original.call(self, pose));
    }

    @WrapOperation(
        method = "refreshDimensions",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getEyeHeight(Lnet/minecraft/world/entity/Pose;Lnet/minecraft/world/entity/EntityDimensions;)F")
    )
    private float enderkinesis$selvesBodyEyes(Entity self, Pose pose, EntityDimensions size, Operation<Float> original) {
        return SelvesBodies.eyeHeight(self, original.call(self, pose, size));
    }

    @Inject(method = "load", at = @At("TAIL"))
    private void enderkinesis$selvesBodyLoad(CompoundTag tag, CallbackInfo ci) {
        SelvesBodies.onLoad((Entity) (Object) this);
    }

    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void enderkinesis$selvesBodyRemoved(Entity.RemovalReason reason, CallbackInfo ci) {
        SelvesBodies.onRemoved((Entity) (Object) this, reason);
    }
}
