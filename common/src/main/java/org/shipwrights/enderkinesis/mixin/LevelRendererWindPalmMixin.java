package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.WindPalmCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererWindPalmMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void enderkinesis$windPalmBegin(
        PoseStack poseStack, float partialTick, long nanoTime, boolean renderBlockOutline,
        Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix,
        CallbackInfo ci
    ) {
        WindPalmCapture.beginLevel(poseStack.last().pose());
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void enderkinesis$windPalmEnd(CallbackInfo ci) {
        WindPalmCapture.endLevel();
    }
}
