package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.shipwrights.enderkinesis.client.WeheloPocketClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// TAIL: the HUD, screens and toasts are in the main target, before Minecraft blits it to the window.
@Mixin(GameRenderer.class)
public abstract class GameRendererWeheloGrayscaleMixin {
    @Inject(method = "render(FJZ)V", at = @At("TAIL"))
    private void enderkinesis$grayscalePocket(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        WeheloPocketClient.renderGrayscale();
    }
}
