package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.YgannBarnacleClientWork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererYgannBarnacleFrameMixin {

    @Shadow private Frustum cullingFrustum;
    @Shadow private Frustum capturedFrustum;

    // GameRenderer calls prepareCullFrustum before renderLevel, so cullingFrustum is this frame's at HEAD.
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void enderkinesis$ygannBarnacleBeginFrame(
        PoseStack poseStack, float partialTick, long nanoTime, boolean renderBlockOutline,
        Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix,
        CallbackInfo ci
    ) {
        YgannBarnacleClientWork.beginFrame(capturedFrustum != null ? capturedFrustum : cullingFrustum);
    }
}
