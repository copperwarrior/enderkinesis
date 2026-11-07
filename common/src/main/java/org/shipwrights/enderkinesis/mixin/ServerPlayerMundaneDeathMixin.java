package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.shipwrights.enderkinesis.item.MundaneIncubation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// After every cancel point (LIVING_DEATH, Forge LivingDeathEvent) and before dropAllDeathLoot, also for spectators.
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMundaneDeathMixin {

    @Inject(
        method = "die",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;removeEntitiesOnShoulder()V")
    )
    private void enderkinesis$endIncubation(DamageSource source, CallbackInfo ci) {
        MundaneIncubation.onDeath((ServerPlayer) (Object) this);
    }
}
