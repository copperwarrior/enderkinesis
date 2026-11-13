package org.shipwrights.enderkinesis.mixin;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.shipwrights.enderkinesis.vine.VineChunksKt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Architectury has no chunk watch event on 1.20.1; ChunkMap sends and forgets every chunk through these two calls.
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerVineChunkTrackMixin {

    @Inject(method = "trackChunk", at = @At("TAIL"))
    private void enderkinesis$sendVines(ChunkPos pos, Packet<?> packet, CallbackInfo ci) {
        VineChunksKt.sendVineChunk((ServerPlayer) (Object) this, pos.toLong());
    }

    @Inject(method = "untrackChunk", at = @At("HEAD"))
    private void enderkinesis$forgetVines(ChunkPos pos, CallbackInfo ci) {
        VineChunksKt.forgetVineChunk((ServerPlayer) (Object) this, pos.toLong());
    }
}
