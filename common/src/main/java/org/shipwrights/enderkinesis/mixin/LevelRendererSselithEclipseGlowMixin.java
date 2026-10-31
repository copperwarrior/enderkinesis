package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.shipwrights.enderkinesis.client.SselithEclipseGlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Sodium overwrites setSectionDirty; a later priority puts this inject into its body.
@Mixin(value = LevelRenderer.class, priority = 1100)
public abstract class LevelRendererSselithEclipseGlowMixin {

    // Block changes, light packets and client relights all reach this method.
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void enderkinesis$markEclipseGlowDirty(int x, int y, int z, boolean playerChanged, CallbackInfo ci) {
        SselithEclipseGlow.markSectionDirty(x, y, z);
    }
}
