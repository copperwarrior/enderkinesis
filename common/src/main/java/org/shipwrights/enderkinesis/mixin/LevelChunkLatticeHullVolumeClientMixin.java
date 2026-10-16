package org.shipwrights.enderkinesis.mixin;

import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;
import org.shipwrights.enderkinesis.physics.LatticeHullVolume;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Not ClientChunkCache.replaceWithPacketData: VS2 cancels that method at HEAD for shipyard chunks
// and fills the chunk itself, so a RETURN hook there never runs for a ship.
@Mixin(LevelChunk.class)
public abstract class LevelChunkLatticeHullVolumeClientMixin {

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void enderkinesis$latticeHullVolumeChunkLoaded(
        FriendlyByteBuf buf, CompoundTag tag, Consumer<?> consumer, CallbackInfo ci
    ) {
        LevelChunk self = (LevelChunk) (Object) this;
        LatticeHullVolume.onClientChunkChanged(self.getPos().x, self.getPos().z);
    }
}
