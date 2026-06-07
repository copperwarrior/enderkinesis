package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.WohlonBiomeSkyState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hard cloud cutoff at 50% Wohlon-biome blend — matches the 0.5-threshold harsh swap
 * the rest of the Wohlon sky overlay uses. {@code @Inject(at = HEAD, cancellable = true)}
 * fires before any body code, so this single hook works under both vanilla and Sodium's
 * {@code @Overwrite} of {@code renderClouds} — no separate Sodium fallback needed.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererCloudsWohlonBiomeMixin {

    @Inject(
        method = "renderClouds",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$skipCloudsAboveWohlonThreshold(
        PoseStack poseStack, Matrix4f projection, float partialTick,
        double camX, double camY, double camZ,
        CallbackInfo ci
    ) {
        if (WohlonBiomeSkyState.currentBlend >= 0.5f) {
            ci.cancel();
        }
    }
}
