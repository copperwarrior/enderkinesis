package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import org.shipwrights.enderkinesis.spell.ElementalEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

// ServerPlayer overrides both teleportTo methods and does not call super.
@Mixin({Entity.class, ServerPlayer.class})
public abstract class EntityTeleportElementSourceMixin {

    @Inject(method = "teleportTo(DDD)V", at = @At("HEAD"))
    private void enderkinesis$chaosDeed(double x, double y, double z, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        ElementalEnvironment.teleported(self, self.level(), x, y, z);
    }

    @Inject(
        method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",
        at = @At("HEAD")
    )
    private void enderkinesis$chaosDeedTo(
        ServerLevel level, double x, double y, double z, Set<RelativeMovement> relative, float yRot, float xRot,
        CallbackInfoReturnable<Boolean> cir
    ) {
        ElementalEnvironment.teleported((Entity) (Object) this, level, x, y, z);
    }
}
