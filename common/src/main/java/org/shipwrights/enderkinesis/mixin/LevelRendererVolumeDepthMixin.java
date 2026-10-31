package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.volume.VolumeDepth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererVolumeDepthMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void enderkinesis$volumeDepthBeginFrame(CallbackInfo ci) {
        VolumeDepth.INSTANCE.beginFrame();
    }

    // Anchored on the profiler string: Forge adds a Frustum parameter to ParticleEngine.render.
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
            args = "ldc=particles"
        )
    )
    private void enderkinesis$volumeDepthCapture(CallbackInfo ci) {
        VolumeDepth.INSTANCE.capture(Minecraft.getInstance().getMainRenderTarget());
    }

    // Clouds draw after particles, so the copy gets their depth from a second, depth-only draw.
    @WrapOperation(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderClouds(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;FDDD)V"
        )
    )
    private void enderkinesis$volumeDepthClouds(
        LevelRenderer instance, PoseStack poseStack, Matrix4f projection,
        float partialTick, double camX, double camY, double camZ,
        Operation<Void> original
    ) {
        original.call(instance, poseStack, projection, partialTick, camX, camY, camZ);
        if (VolumeDepth.INSTANCE.beginCloudReplay()) {
            try {
                original.call(instance, poseStack, projection, partialTick, camX, camY, camZ);
            } finally {
                VolumeDepth.INSTANCE.endCloudReplay();
            }
        }
    }
}
