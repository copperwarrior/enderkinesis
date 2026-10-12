package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingEntitySwimAmountAccessor {

    @Accessor("swimAmount")
    float enderkinesis$getSwimAmount();

    @Accessor("swimAmount")
    void enderkinesis$setSwimAmount(float value);

    @Accessor("swimAmountO")
    float enderkinesis$getSwimAmountO();

    @Accessor("swimAmountO")
    void enderkinesis$setSwimAmountO(float value);
}
