package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.shipwrights.enderkinesis.sselith.SselithEclipse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// ServerPlayer.die does not call super.die, so the redirect in LivingEntitySselithLightDeathMixin does not apply.
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerSselithLightDeathMixin {

    @Redirect(
        method = "die",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;broadcastEntityEvent(Lnet/minecraft/world/entity/Entity;B)V"
        )
    )
    private void enderkinesis$skipLightDeathBroadcast(Level level, Entity entity, byte event, DamageSource source) {
        if (event == 3 && SselithEclipse.isLightDamageSource(source)) return;
        level.broadcastEntityEvent(entity, event);
    }
}
