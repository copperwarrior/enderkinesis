package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.SunErasureRoots;
import org.shipwrights.enderkinesis.ritual.SunErasure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// renderSky draws the sun at 1 - rain level, so the sun fades out with the erasure.
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSunErasureMixin {

    @Shadow private ClientLevel level;

    @ModifyExpressionValue(method = "renderSky", at = @At(
        value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"
    ))
    private float enderkinesis$erasedSunFades(float rain) {
        return SunErasure.darkness(level, rain);
    }

    // Ordinal 1 is the pop after the sun, moon and stars (same on Fabric and Forge 1.20.1): camera pose only.
    @Inject(
        method = "renderSky",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V", ordinal = 1, shift = At.Shift.AFTER)
    )
    private void enderkinesis$drawErasureRoots(
        PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, Camera camera, boolean isFoggy,
        Runnable setupFog, CallbackInfo ci
    ) {
        if (level != null) SunErasureRoots.render(poseStack, level, partialTick);
    }
}
