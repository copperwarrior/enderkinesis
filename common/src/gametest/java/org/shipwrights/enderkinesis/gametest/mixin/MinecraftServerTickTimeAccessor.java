package org.shipwrights.enderkinesis.gametest.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecraftServer.class)
public interface MinecraftServerTickTimeAccessor {
    @Accessor("nextTickTime")
    long enderkinesis$getNextTickTime();
}
