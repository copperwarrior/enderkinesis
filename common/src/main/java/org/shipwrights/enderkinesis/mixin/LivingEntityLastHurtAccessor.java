package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The damage a hit during i-frames must beat, kept round a streamed spell pulse (SpellContext.hurt). */
@Mixin(LivingEntity.class)
public interface LivingEntityLastHurtAccessor {
    @Accessor("lastHurt")
    float enderkinesis$getLastHurt();

    @Accessor("lastHurt")
    void enderkinesis$setLastHurt(float value);
}
