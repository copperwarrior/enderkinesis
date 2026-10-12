package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.shipwrights.enderkinesis.client.SselithVoidFog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererSselithVoidFogMixin {

    @Inject(method = "blockChanged", at = @At("HEAD"))
    private void enderkinesis$markVoidFogDirty(
        BlockGetter level, BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci
    ) {
        SselithVoidFog.onBlockChanged(pos, oldState, newState);
    }
}
