package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.shipwrights.enderkinesis.client.BlockShifts;
import org.shipwrights.enderkinesis.spell.AbyssalPockets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The chunk mesher sees a block phased by an Abyssal Pocket, or one a spell is drawing itself
 *  for a moment ({@link BlockShifts}), as air: it isn't drawn, and its neighbours draw their
 *  faces into the hole. The block itself stays in the client's world, so block updates from the
 *  server can't bring it back early. */
@Mixin(RenderChunkRegion.class)
public abstract class RenderChunkRegionPhasedMixin {

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$phased(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (AbyssalPockets.isPhasedClient(pos) || BlockShifts.isHidden(pos)) cir.setReturnValue(Blocks.AIR.defaultBlockState());
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$phasedFluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (AbyssalPockets.isPhasedClient(pos)) cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
    }
}
