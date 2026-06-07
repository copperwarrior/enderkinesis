package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.shipwrights.enderkinesis.block.HeartOfTheWildManager;
import org.shipwrights.enderkinesis.registry.EKBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks {@link LevelChunk#setBlockState} — the single chokepoint every server-side
 * destruction path (mining, TNT, fire, mob griefing, mod-tool breaks) eventually
 * funnels through. Architectury's {@code BlockEvent.BREAK} only covers player mining.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkWildHeartDestroyMixin {

    @Shadow public abstract Level getLevel();

    @Shadow public abstract BlockState getBlockState(BlockPos pos);

    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void enderkinesis$captureWildHeartDestroy(
        BlockPos pos, BlockState newState, boolean isMoving,
        CallbackInfoReturnable<BlockState> cir
    ) {
        if (isMoving) return;
        Level level = getLevel();
        if (level.isClientSide) return;
        if (!(level instanceof ServerLevel server)) return;
        BlockState oldState = getBlockState(pos);
        if (oldState.isAir()) return;
        if (oldState.is(newState.getBlock())) return;
        // Lighting TNT does setBlockState(AIR) but spawns a PrimedTnt — the block didn't
        // go away semantically; don't register it for regrowth.
        if (oldState.is(Blocks.TNT)) return;
        // Skip the two internal bud-lifecycle transitions (any → BUD, BUD → WOOD): manager
        // plants the bud and its scheduled-tick chain matures it, neither should re-enqueue.
        // A player breaking a bud (BUD → AIR) is not matched and DOES re-enqueue — desired.
        if (newState.is(EKBlocks.INSTANCE.getWOGOR_BUD().get())) return;
        if (oldState.is(EKBlocks.INSTANCE.getWOGOR_BUD().get())
            && newState.is(EKBlocks.INSTANCE.getWOGOR_WOOD().get())) return;
        HeartOfTheWildManager.INSTANCE.onBlockDestroyed(server, pos.immutable(), oldState);
    }
}
