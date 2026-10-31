package org.shipwrights.enderkinesis.mixin;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.shipwrights.enderkinesis.client.YgannBarnacleClientWork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// LevelChunk, not LevelRenderer.blockChanged: a client setBlock without the re-render flag skips blockChanged.
@Mixin(LevelChunk.class)
public abstract class LevelChunkYgannBarnacleHullMixin {

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void enderkinesis$ygannBarnacleHullBlockChanged(
        BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir
    ) {
        Level level = ((LevelChunk) (Object) this).getLevel();
        if (level.isClientSide) YgannBarnacleClientWork.onBlockChanged(level, pos);
    }

    // Not ClientChunkCache.replaceWithPacketData: VS2 cancels that method at HEAD for shipyard chunks.
    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void enderkinesis$ygannBarnacleHullChunkLoaded(
        FriendlyByteBuf buf, CompoundTag tag, Consumer<?> consumer, CallbackInfo ci
    ) {
        LevelChunk self = (LevelChunk) (Object) this;
        YgannBarnacleClientWork.onChunkChanged(self.getLevel(), self.getPos().x, self.getPos().z);
    }
}
