package org.shipwrights.enderkinesis.mixin;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.shipwrights.enderkinesis.spell.AbyssalPockets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pocket phases go only to a pocket's chunk viewers, so a player who starts to view the chunk gets them here. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapPocketResendMixin {

    @Shadow @Final ServerLevel level;

    @Inject(method = "playerLoadedChunk", at = @At("TAIL"))
    private void enderkinesis$resendPockets(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packet,
                                            LevelChunk chunk, CallbackInfo ci) {
        AbyssalPockets.sendPhasedInChunk(player, level, chunk.getPos());
    }
}
