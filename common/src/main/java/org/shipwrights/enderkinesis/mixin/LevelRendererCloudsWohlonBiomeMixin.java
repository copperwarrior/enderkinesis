package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.WohlonBiomeSkyState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cloud fade across the Wohlon biome blend: {@code [0, 0.5)} sets ColorModulator alpha to
 * {@code 1−2·blend}; {@code ≥ 0.5} HEAD-cancels {@code renderClouds} outright. Must use
 * ColorModulator (not vertex literal) because the cloud-vertex buffer is cached and would
 * only update on rebuild, missing the dynamic blend.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererCloudsWohlonBiomeMixin {

    @Inject(
        method = "renderClouds",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$wohlonCloudHead(
        PoseStack poseStack, Matrix4f projection, float partialTick,
        double camX, double camY, double camZ,
        CallbackInfo ci
    ) {
        float blend = WohlonBiomeSkyState.getCurrentBlend();
        if (blend >= 0.5f) {
            ci.cancel();
            return;
        }
        if (blend > 0f) {
            float fade = 1f - 2f * blend;
            RenderSystem.setShaderColor(1f, 1f, 1f, fade);
        }
    }

    @Inject(
        method = "renderClouds",
        at = @At("RETURN")
    )
    private void enderkinesis$wohlonCloudReset(
        PoseStack poseStack, Matrix4f projection, float partialTick,
        double camX, double camY, double camZ,
        CallbackInfo ci
    ) {
        // Reset only when we modified the modulator in HEAD; cancel path didn't touch it.
        float blend = WohlonBiomeSkyState.getCurrentBlend();
        if (blend > 0f && blend < 0.5f) {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }
    }
}
