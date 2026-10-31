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

@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererPhasedMixin {

    // Forge moves this getBlockState call into its own getOverlayBlock(Player); one INVOKE matches on each loader.
    @WrapOperation(
        method = {"getViewBlockingState", "getOverlayBlock"},
        require = 1,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
    )
    private static BlockState enderkinesis$phasedIsAir(Level level, BlockPos pos, Operation<BlockState> original) {
        if (AbyssalPockets.isPhasedClient(pos)) return Blocks.AIR.defaultBlockState();
        return original.call(level, pos);
    }
}
