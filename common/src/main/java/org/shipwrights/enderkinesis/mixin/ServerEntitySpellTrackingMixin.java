package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.spell.SpellNetwork;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Architectury has no start-tracking event, so this sends the spell state after the entity ({@link SpellNetwork#onStartTracking}). */
@Mixin(ServerEntity.class)
public abstract class ServerEntitySpellTrackingMixin {

    @Shadow @Final private Entity entity;

    @Inject(method = "addPairing", at = @At("TAIL"))
    private void enderkinesis$sendSpellState(ServerPlayer player, CallbackInfo ci) {
        SpellNetwork.onStartTracking(player, entity);
    }
}
