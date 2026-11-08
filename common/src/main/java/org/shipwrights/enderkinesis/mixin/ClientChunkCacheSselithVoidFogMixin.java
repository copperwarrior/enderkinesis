package org.shipwrights.enderkinesis.mixin;

import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.shipwrights.enderkinesis.client.SselithVoidFog;
import org.shipwrights.enderkinesis.client.WohlonWaterMurk;
import org.shipwrights.enderkinesis.spell.AbyssalPockets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheSselithVoidFogMixin {

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void enderkinesis$voidFogChunkLoaded(
        int x, int z, FriendlyByteBuf buf, CompoundTag tag,
        Consumer<?> consumer, CallbackInfoReturnable<?> cir
    ) {
        SselithVoidFog.markChunkDirty(x, z);
        WohlonWaterMurk.markChunkDirty(x, z);
    }

    @Inject(method = "drop", at = @At("TAIL"))
    private void enderkinesis$voidFogChunkDropped(int x, int z, CallbackInfo ci) {
        SselithVoidFog.markChunkDirty(x, z);
        WohlonWaterMurk.markChunkDirty(x, z);
        AbyssalPockets.INSTANCE.dropClientChunk(x, z);
    }

    // The Wohlon spreader resends biomes without the chunk; the water murk keys on the biome.
    @Inject(method = "replaceBiomes", at = @At("TAIL"))
    private void enderkinesis$waterMurkBiomesReplaced(int x, int z, FriendlyByteBuf buf, CallbackInfo ci) {
        WohlonWaterMurk.markChunkDirty(x, z);
    }
}
