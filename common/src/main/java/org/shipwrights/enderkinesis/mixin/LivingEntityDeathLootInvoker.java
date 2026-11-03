package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LivingEntity.class)
public interface LivingEntityDeathLootInvoker {

    @Invoker("dropAllDeathLoot")
    void enderkinesis$dropAllDeathLoot(DamageSource source);
}
