package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.shipwrights.enderkinesis.client.IchaericInkVisuals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererIchaericInkMixin {

    @Inject(method = "renderScreenEffect", at = @At("TAIL"))
    private static void enderkinesis$inkOverlay(Minecraft minecraft, PoseStack poseStack, CallbackInfo ci) {
        IchaericInkVisuals.renderOverlay(poseStack);
    }
}
