package org.shipwrights.enderkinesis.mixin;

import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.shipwrights.enderkinesis.client.BlockShifts;
import org.shipwrights.enderkinesis.spell.AbyssalPockets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sodium-path mirror of {@link RenderChunkRegionPhasedMixin}. */
@Mixin(value = WorldSlice.class, remap = false)
public abstract class WorldSlicePhasedMixin {

    // One chunk-build context owns each slice, so one worker thread uses this scratch at a time.
    @Unique
    private final BlockPos.MutableBlockPos enderkinesis$scratch = new BlockPos.MutableBlockPos();

    // Sodium and Embeddium send getBlockState(BlockPos) and getFluidState(BlockPos) through getBlockState(III).
    // The AP does not remap a mod method's descriptor, so match the args only: BlockState is class_2680 in Fabric prod.
    @Inject(method = "name=/^getBlockState$/ desc=/^\\(III\\)/", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$phased(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
        if (AbyssalPockets.isPhasedClient(BlockPos.asLong(x, y, z)) || BlockShifts.isHidden(enderkinesis$scratch.set(x, y, z))) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }
}
