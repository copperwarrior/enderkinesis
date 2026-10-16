package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.shipwrights.enderkinesis.physics.LatticeHullVolume;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// The chunk, not Level.setBlock or LevelRenderer.blockChanged: VS2 ship assembly writes to the chunk directly.
@Mixin(LevelChunk.class)
public abstract class LevelChunkLatticeHullVolumeMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void enderkinesis$updateLatticeHullVolume(
        BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> cir
    ) {
        BlockState oldState = cir.getReturnValue();
        if (oldState == null) {
            return;
        }
        boolean isClient = ((LevelChunk) (Object) this).getLevel().isClientSide();
        LatticeHullVolume.onBlockChanged(isClient, pos, oldState, state);
    }
}
