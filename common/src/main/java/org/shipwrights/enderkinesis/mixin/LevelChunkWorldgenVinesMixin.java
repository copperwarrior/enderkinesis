package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.jetbrains.annotations.Nullable;
import org.shipwrights.enderkinesis.vine.VineChunksKt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class LevelChunkWorldgenVinesMixin {

    // A chunk generated in this session becomes full here without a disk load, so LOAD_DATA never sees its vines.
    @Inject(
        method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
        at = @At("RETURN")
    )
    private void enderkinesis$adoptWorldgenVines(
        ServerLevel level, ProtoChunk proto, @Nullable LevelChunk.PostLoadProcessor postLoad, CallbackInfo ci
    ) {
        VineChunksKt.adoptWorldgenVines(level, proto);
    }
}
