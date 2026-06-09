package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wohlon snow vanishes outright (no light gate, no snowball drops). */
@Mixin(SnowLayerBlock.class)
public class SnowLayerBlockWohlonMeltMixin {

    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$disappearInWohlon(
            BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
            CallbackInfo ci) {
        if (!level.getBiome(pos).is(Wohlonnogondonia.BIOME_KEY)) return;
        level.removeBlock(pos, false);
        ci.cancel();
    }
}
