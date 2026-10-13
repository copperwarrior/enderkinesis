package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WalkAnimationState.class)
public interface WalkAnimationStateAccessor {

    @Accessor("speedOld")
    float enderkinesis$getSpeedOld();

    @Accessor("speedOld")
    void enderkinesis$setSpeedOld(float value);

    @Accessor("speed")
    float enderkinesis$getSpeed();

    @Accessor("speed")
    void enderkinesis$setSpeed(float value);

    @Accessor("position")
    float enderkinesis$getPosition();

    @Accessor("position")
    void enderkinesis$setPosition(float value);
}
