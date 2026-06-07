package org.shipwrights.enderkinesis.mixin;

import java.util.Set;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Re-sync active mob-effects to the client after cross-dimension teleport. Vanilla's
 * {@link net.minecraft.world.entity.player.Player#changeDimension} re-sends
 * {@link ClientboundUpdateMobEffectPacket} per active effect at its end — but
 * {@code ServerPlayer.teleportTo} (used by {@code /tp}, {@code /execute in dim run tp},
 * and our {@code SafeTeleporter}) doesn't, so its respawn packet rebuilds
 * {@code LocalPlayer} with an empty effects map and effects silently vanish from the
 * client view.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerCrossDimEffectSyncMixin {

    /** {@code public void teleportTo(ServerLevel, double, double, double, float, float)} —
     *  the older overload used by {@code SafeTeleporter} and various mod teleporters. */
    @Inject(
        method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V",
        at = @At("RETURN")
    )
    private void enderkinesis$resyncEffectsAfterTeleportV(
        ServerLevel level, double x, double y, double z, float yRot, float xRot,
        CallbackInfo ci
    ) {
        enderkinesis$resyncEffects();
    }

    /** {@code public boolean teleportTo(ServerLevel, double, double, double, Set, float, float)} —
     *  the newer overload used by {@code /tp}, {@code /execute in dim run tp}, and most
     *  command-style teleports introduced in 1.19+. */
    @Inject(
        method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",
        at = @At("RETURN")
    )
    private void enderkinesis$resyncEffectsAfterTeleportZ(
        ServerLevel level, double x, double y, double z, Set<RelativeMovement> relative,
        float yRot, float xRot,
        CallbackInfoReturnable<Boolean> cir
    ) {
        enderkinesis$resyncEffects();
    }

    @Unique
    private void enderkinesis$resyncEffects() {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (self.getActiveEffects().isEmpty()) return;
        for (MobEffectInstance effect : self.getActiveEffects()) {
            self.connection.send(new ClientboundUpdateMobEffectPacket(self.getId(), effect));
        }
    }
}
