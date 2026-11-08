package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.shipwrights.enderkinesis.sselith.SselithEclipse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// ServerPlayer.die does not call super.die, so the wrap in LivingEntitySselithLightDeathMixin does not apply.
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerSselithLightDeathMixin {

    @WrapOperation(
        method = "die",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;broadcastEntityEvent(Lnet/minecraft/world/entity/Entity;B)V"
        )
    )
    private void enderkinesis$skipLightDeathBroadcast(Level level, Entity entity, byte event, Operation<Void> original,
                                                      @Local(argsOnly = true) DamageSource source) {
        if (event == 3 && SselithEclipse.isLightDamageSource(source)) return;
        original.call(level, entity, event);
    }
}
