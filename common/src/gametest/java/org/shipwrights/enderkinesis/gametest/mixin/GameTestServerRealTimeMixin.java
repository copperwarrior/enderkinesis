package org.shipwrights.enderkinesis.gametest.mixin;

import net.minecraft.Util;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GameTestServer.waitUntilNextTick only runs the queued tasks, so the game test server ticks as fast as it can. VS2
 * physics runs on wall-clock time on its own thread, so the test server must keep vanilla's 50 ms tick to be valid.
 */
@Mixin(GameTestServer.class)
public abstract class GameTestServerRealTimeMixin {
    @Inject(method = "waitUntilNextTick", at = @At("TAIL"))
    private void enderkinesis$waitForTickTime(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        long next = ((MinecraftServerTickTimeAccessor) server).enderkinesis$getNextTickTime();
        // The same wait as MinecraftServer.waitUntilNextTick: queued tasks still run until the tick time.
        server.managedBlock(() -> Util.getMillis() >= next);
    }
}
