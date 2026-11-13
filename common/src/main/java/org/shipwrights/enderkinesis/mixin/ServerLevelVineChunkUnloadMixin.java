package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.shipwrights.enderkinesis.vine.VineChunksKt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelVineChunkUnloadMixin {

    // ChunkMap.scheduleUnload saves the chunk, with its vines, just before this call.
    @Inject(method = "unload", at = @At("HEAD"))
    private void enderkinesis$unloadVines(LevelChunk chunk, CallbackInfo ci) {
        VineChunksKt.unloadVineChunk((ServerLevel) (Object) this, chunk.getPos().toLong());
    }
}
