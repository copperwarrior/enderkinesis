package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.shipwrights.enderkinesis.block.MothClothBlock;
import org.shipwrights.enderkinesis.client.MothClothClientPositions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps {@link MothClothClientPositions} synchronised with per-block
 * changes on the client — every time a chunk's block state flips (either
 * because a player places/breaks the block, or the section-blocks-update
 * packet mutates it), this mixin updates the client-side registry so the
 * overlay pass finds the right positions on the very next frame.
 *
 * <p>The initial chunk-load scan is handled by
 * {@link ClientChunkCacheMothClothScanMixin}.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMothClothTrackingMixin {

    @Inject(
        method = "setBlockState",
        at = @At("HEAD")
    )
    private void enderkinesis$trackMothClothChange(
        BlockPos pos, BlockState newState, boolean moved, CallbackInfoReturnable<BlockState> cir
    ) {
        Level level = ((LevelChunk) (Object) this).getLevel();
        if (!level.isClientSide) return;
        BlockState oldState = ((LevelChunk) (Object) this).getBlockState(pos);
        boolean wasCloth = oldState.getBlock() instanceof MothClothBlock;
        boolean isCloth = newState.getBlock() instanceof MothClothBlock;
        if (wasCloth == isCloth) return;
        if (isCloth) MothClothClientPositions.add(pos);
        else MothClothClientPositions.remove(pos);
    }
}
