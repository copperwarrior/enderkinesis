package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Player.class)
public interface PlayerSleepCounterAccessor {
    @Accessor("sleepCounter")
    void setSleepCounter(int sleepCounter);
}
