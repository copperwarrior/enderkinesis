package org.shipwrights.enderkinesis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.shipwrights.enderkinesis.spell.AbyssalPockets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.mod.common.render.batched.ShipShadeRemovingGetter;

/** The batched ship mesher culls faces through this view, so a phased block must read as air here too. */
@Mixin(value = ShipShadeRemovingGetter.class, remap = false)
public abstract class ShipShadeRemovingGetterPhasedMixin {

    // The class overrides interface methods, so the production name is the intermediary or SRG one; match all three.
    @Inject(method = "name=/^(getBlockState|method_8320|m_8055_)$/ desc=/^\\(L[^;]+;\\)L/", at = @At("HEAD"), cancellable = true, remap = false)
    private void enderkinesis$phased(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (AbyssalPockets.isPhasedClient(pos)) cir.setReturnValue(Blocks.AIR.defaultBlockState());
    }

    @Inject(method = "name=/^(getFluidState|method_8316|m_6425_)$/ desc=/^\\(L[^;]+;\\)L/", at = @At("HEAD"), cancellable = true, remap = false)
    private void enderkinesis$phasedFluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (AbyssalPockets.isPhasedClient(pos)) cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
    }
}
