package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.shipwrights.enderkinesis.sselith.SselithEclipse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sselith Eclipse Light Damage death effects:
 *
 * <ul>
 *   <li><b>Non-player entities</b>: HEAD-cancel {@link LivingEntity#die} — no
 *       drops, no animation, just a dust burst + immediate discard.</li>
 *   <li><b>All entities (players included)</b>: suppress the visual side-effects
 *       of fatal light damage —
 *       <ul>
 *         <li>Red-flash hurt animation (the broadcastDamageEvent in hurt())</li>
 *         <li>Fall-over animation (the broadcastEntityEvent(byte=3) in die())</li>
 *         <li>DYING pose set in die()</li>
 *       </ul>
 *       Players still go through the full death-and-respawn flow internally; only
 *       the client-visible animation packets are skipped. The dust burst at the
 *       body comes from the LIVING_DEATH handler in {@link SselithEclipse}.</li>
 * </ul>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySselithLightDeathMixin {

    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$lightDeathToDust(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player) return;
        if (!SselithEclipse.isLightDamageSource(source)) return;
        if (!(self.level() instanceof ServerLevel)) return;
        SselithEclipse.spawnLightDeathDust(self);
        self.discard();
        ci.cancel();
    }

    @Redirect(
        method = "hurt",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;broadcastDamageEvent(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;)V"
        )
    )
    private void enderkinesis$skipLightHurtBroadcast(Level level, Entity entity, DamageSource source) {
        if (SselithEclipse.isLightDamageSource(source)) return;
        level.broadcastDamageEvent(entity, source);
    }

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

    @Redirect(
        method = "die",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;setPose(Lnet/minecraft/world/entity/Pose;)V"
        )
    )
    private void enderkinesis$skipLightDeathPose(LivingEntity self, Pose pose, DamageSource source) {
        if (pose == Pose.DYING && SselithEclipse.isLightDamageSource(source)) return;
        self.setPose(pose);
    }
}
