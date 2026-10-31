package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import org.shipwrights.enderkinesis.client.YgannSky;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherAbsentMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$absent(CallbackInfo ci) {
        if (YgannSky.absentView()) ci.cancel();
    }
}
