package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.GasFieldClient;
import org.shipwrights.enderkinesis.client.WohlonWaterMurk;
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

    // Both renderLevel branches call this; Sodium replaces the body, not the call. The murk copy must keep the water
    // surface out. The layer draws one time only: the volumes use copies taken around it.
    @WrapOperation(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V"
        )
    )
    private void enderkinesis$beforeTranslucent(
        LevelRenderer instance, RenderType type, PoseStack poseStack, double camX, double camY, double camZ,
        Matrix4f projection, Operation<Void> original
    ) {
        if (type != RenderType.translucent()) {
            original.call(instance, type, poseStack, camX, camY, camZ, projection);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        Camera camera = mc.gameRenderer.getMainCamera();
        WohlonWaterMurk.beforeTranslucent(main);
        boolean split = VolumeDepth.INSTANCE.beforeTranslucent(main);
        original.call(instance, type, poseStack, camX, camY, camZ, projection);
        if (split) {
            // Before the murk, so main holds only the translucent terrain over the gas.
            VolumeDepth.INSTANCE.afterTranslucent(main);
            try {
                GasFieldClient.render(poseStack, projection, camera);
            } finally {
                VolumeDepth.INSTANCE.endBehind(main);
            }
        }
        WohlonWaterMurk.afterTranslucent(poseStack, projection, camera);
    }

    // Clouds draw after particles, which write depth: the copy takes only the texels the clouds made nearer.
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
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        boolean clouds = VolumeDepth.INSTANCE.beforeClouds(main);
        original.call(instance, poseStack, projection, partialTick, camX, camY, camZ);
        if (clouds) VolumeDepth.INSTANCE.afterClouds(main);
    }
}
