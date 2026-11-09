package org.shipwrights.enderkinesis.mixin;

import java.util.List;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.shipwrights.enderkinesis.client.volume.VolumePerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Forge's debug overlay calls getSystemInformation too, so one inject serves both loaders.
@Mixin(DebugScreenOverlay.class)
public abstract class DebugScreenOverlayVolumePerfMixin {

    @Inject(method = "getSystemInformation", at = @At("RETURN"))
    private void enderkinesis$volumePerfLines(CallbackInfoReturnable<List<String>> cir) {
        List<String> lines = cir.getReturnValue();
        lines.add("");
        VolumePerf.INSTANCE.debugLines(lines);
    }
}
