package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.shipwrights.enderkinesis.dimension.SselithBlockTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feeds block removals to {@link SselithBlockTracker}. Hooks
 * {@link LevelChunk#setBlockState} — the single chokepoint every
 * server-side destruction path funnels through (mining, TNT, fire, fluids,
 * pistons, mob griefing, mod tools); Architectury's {@code BlockEvent.BREAK}
 * covers only player mining.
 *
 * <p>This fires for every block write in every dimension, so the tracker's
 * guard is ordered cheapest-first: an identity compare against the cached
 * Sselith level, then a null check, then one primitive hash lookup.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkSselithPlacementMixin {

    @Shadow public abstract Level getLevel();

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void enderkinesis$trackSselithRemoval(
        BlockPos pos, BlockState newState, boolean isMoving,
        CallbackInfoReturnable<BlockState> cir
    ) {
        Level level = getLevel();
        if (level.isClientSide) return;
        SselithBlockTracker.INSTANCE.onBlockChanged(level, pos, newState);
    }
}
