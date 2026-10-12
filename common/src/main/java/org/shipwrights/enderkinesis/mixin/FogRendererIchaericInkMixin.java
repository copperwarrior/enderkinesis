package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.shipwrights.enderkinesis.client.IchaericInkVisuals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ink is no FogType, so the camera in ink gets its own dense purple-black fog. The dimension fog mixins step aside. */
@Mixin(FogRenderer.class)
public abstract class FogRendererIchaericInkMixin {

    @Shadow private static float fogRed;
    @Shadow private static float fogGreen;
    @Shadow private static float fogBlue;

    @Inject(
        method = "setupColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IF)V",
        at = @At("TAIL")
    )
    private static void enderkinesis$inkFogColor(
        Camera camera, float partialTick, ClientLevel level, int renderDistance, float darkenWorldAmount, CallbackInfo ci
    ) {
        if (!IchaericInkVisuals.cameraInInk()) return;
        fogRed = IchaericInkVisuals.RED;
        fogGreen = IchaericInkVisuals.GREEN;
        fogBlue = IchaericInkVisuals.BLUE;
        RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0F);
    }

    @Inject(
        method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
        at = @At("TAIL")
    )
    private static void enderkinesis$inkFogRange(
        Camera camera, FogRenderer.FogMode mode, float farPlaneDistance, boolean nearby, float partialTick, CallbackInfo ci
    ) {
        if (!IchaericInkVisuals.cameraInInk()) return;
        RenderSystem.setShaderFogStart(IchaericInkVisuals.FOG_START);
        RenderSystem.setShaderFogEnd(IchaericInkVisuals.FOG_END);
        RenderSystem.setShaderFogShape(FogShape.SPHERE);
    }

    @WrapOperation(
        method = "levelFogColor()V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogColor(FFF)V")
    )
    private static void enderkinesis$inkFogPush(float r, float g, float b, Operation<Void> original) {
        if (IchaericInkVisuals.cameraInInk()) {
            RenderSystem.setShaderFogColor(IchaericInkVisuals.RED, IchaericInkVisuals.GREEN, IchaericInkVisuals.BLUE, 1.0F);
        } else {
            original.call(r, g, b);
        }
    }
}
