package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wohlon ice melts to source water regardless of local light (vanilla gates on block-light > 11 - lightBlock). */
@Mixin(IceBlock.class)
public class IceBlockWohlonMeltMixin {

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$meltInWohlon(
            BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
            CallbackInfo ci) {
        if (!level.getBiome(pos).is(Wohlonnogondonia.BIOME_KEY)) return;
        // Inline IceBlock.melt's non-ultraWarm branch — Wohlon is always overworld.
        level.setBlockAndUpdate(pos, Blocks.WATER.defaultBlockState());
        level.neighborChanged(pos, Blocks.WATER, pos);
        ci.cancel();
    }
}
