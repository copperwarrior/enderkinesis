package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.dimension.SureibjinExit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Forge's LIVING_HURT (LivingAttackEvent) fires before difficulty scaling. Here both loaders see the final damage.
@Mixin(Player.class)
public abstract class PlayerSureibjinWakeMixin {

    @WrapOperation(
        method = "actuallyHurt",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setHealth(F)V")
    )
    private void enderkinesis$wakeInsteadOfLethalHit(Player self, float health, Operation<Void> original) {
        if (health <= 0f && self instanceof ServerPlayer player && SureibjinExit.wakeInsteadOfLethalHit(player)) {
            return;
        }
        original.call(self, health);
    }
}
