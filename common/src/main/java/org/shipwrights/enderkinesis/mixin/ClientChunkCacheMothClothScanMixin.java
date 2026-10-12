package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;
import org.shipwrights.enderkinesis.client.MothClothClientPositions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Populates {@link MothClothClientPositions} when a chunk arrives on the
 * client — otherwise the overlay pass would only see moth cloth blocks
 * that were placed live during the current session, and every pre-existing
 * block on a loaded save would be invisible-glinting until touched.
 *
 * <p>Also removes positions when a chunk unloads so the registry doesn't
 * grow unbounded as the player travels.
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMothClothScanMixin {

    @Inject(
        method = "replaceWithPacketData",
        at = @At("RETURN")
    )
    private void enderkinesis$scanOnLoad(
        int chunkX, int chunkZ, FriendlyByteBuf buf, CompoundTag tag,
        java.util.function.Consumer<net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
        CallbackInfoReturnable<LevelChunk> cir
    ) {
        LevelChunk chunk = cir.getReturnValue();
        if (chunk == null) return;
        MothClothClientPositions.scanChunk(chunk);
    }

    @Inject(
        method = "drop",
        at = @At("HEAD")
    )
    private void enderkinesis$forgetOnUnload(
        int chunkX, int chunkZ,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci
    ) {
        MothClothClientPositions.forgetChunk(chunkX, chunkZ);
    }
}
