package org.shipwrights.enderkinesis.mixin;

import java.util.List;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.shipwrights.enderkinesis.client.GpuPassTimers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayGpuTimersMixin {

    @Inject(method = "getGameInformation", at = @At("RETURN"))
    private void enderkinesis$gpuPassTimers(CallbackInfoReturnable<List<String>> cir) {
        GpuPassTimers.INSTANCE.appendDebugLines(cir.getReturnValue());
    }
}
