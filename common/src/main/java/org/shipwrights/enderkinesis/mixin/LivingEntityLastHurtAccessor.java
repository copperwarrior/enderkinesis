package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntityLastHurtAccessor {
    @Accessor("lastHurt")
    float enderkinesis$getLastHurt();

    @Accessor("lastHurt")
    void enderkinesis$setLastHurt(float value);
}
