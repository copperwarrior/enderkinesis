package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.shipwrights.enderkinesis.entity.RaisedSkeletonEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WitherBoss.class)
public abstract class WitherBossRaisedUndeadMixin {

    // WitherBoss.hurt drops every hit from a non-player attacker of its own mob type (UNDEAD); raised undead are exempt.
    @WrapOperation(
        method = "hurt",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getMobType()Lnet/minecraft/world/entity/MobType;"
        )
    )
    private MobType enderkinesis$raisedUndeadCanHurt(LivingEntity attacker, Operation<MobType> original) {
        return attacker instanceof RaisedSkeletonEntity ? MobType.UNDEFINED : original.call(attacker);
    }
}
