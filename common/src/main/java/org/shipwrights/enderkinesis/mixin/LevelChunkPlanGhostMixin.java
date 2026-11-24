package org.shipwrights.enderkinesis.mixin;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.shipwrights.enderkinesis.client.PlanGhostRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// LevelChunk, not LevelRenderer.blockChanged: VS2 ship assembly and no-flag client setBlock skip blockChanged.
@Mixin(LevelChunk.class)
public abstract class LevelChunkPlanGhostMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void enderkinesis$planGhostBlockChanged(
        BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir
    ) {
        if (cir.getReturnValue() != null && ((LevelChunk) (Object) this).getLevel().isClientSide) {
            PlanGhostRenderer.onBlockChanged(pos);
        }
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void enderkinesis$planGhostChunkLoaded(
        FriendlyByteBuf buf, CompoundTag tag, Consumer<?> consumer, CallbackInfo ci
    ) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        PlanGhostRenderer.onChunkLoaded(chunk.getPos().x, chunk.getPos().z);
    }
}
