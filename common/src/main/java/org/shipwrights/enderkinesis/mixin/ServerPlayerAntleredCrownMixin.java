package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.shipwrights.enderkinesis.duck.AntleredCrownHolder;
import org.shipwrights.enderkinesis.item.AntleredCrown;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerAntleredCrownMixin {

    // After every cancel point (LIVING_DEATH, Forge LivingDeathEvent) and before dropAllDeathLoot.
    @Inject(
        method = "die",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;removeEntitiesOnShoulder()V")
    )
    private void enderkinesis$petrify(DamageSource source, CallbackInfo ci) {
        AntleredCrown.petrifyIfCrowned((ServerPlayer) (Object) this);
    }

    // keepEverything is the End-exit copy; after a death the new body starts with no wood.
    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void enderkinesis$keepWood(ServerPlayer old, boolean keepEverything, CallbackInfo ci) {
        if (!keepEverything) return;
        ((AntleredCrownHolder) (Object) this).enderkinesis$woodHearts()
            .copyFrom(((AntleredCrownHolder) old).enderkinesis$woodHearts());
    }
}
