package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.shipwrights.enderkinesis.spell.AbyssalPockets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A block phased by an Abyssal Pocket can be walked into, so the camera inside it doesn't get
 *  the in-block overlay: to that check it's air. */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererPhasedMixin {

    @WrapOperation(
        method = "getViewBlockingState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
    )
    private static BlockState enderkinesis$phasedIsAir(Level level, BlockPos pos, Operation<BlockState> original) {
        if (AbyssalPockets.isPhasedClient(pos)) return Blocks.AIR.defaultBlockState();
        return original.call(level, pos);
    }
}
