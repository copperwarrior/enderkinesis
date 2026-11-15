package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.shipwrights.enderkinesis.block.WohlonMossCarpetBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Level.setBlock calls this for every block change; it is the only vanilla hook that reaches diagonal blocks.
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateMossDrapeMixin {

    @Inject(method = "updateIndirectNeighbourShapes(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;II)V",
        at = @At("HEAD"))
    private void enderkinesis$refreshMossDrapes(LevelAccessor level, BlockPos pos, int flags, int recursionLeft, CallbackInfo ci) {
        if (!level.isClientSide()) WohlonMossCarpetBlock.Companion.refreshAround(level, pos);
    }
}
