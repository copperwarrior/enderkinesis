package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.SselithRepertorySky;
import org.shipwrights.enderkinesis.dimension.SselithRepertory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replace the vanilla sky with {@link SselithRepertorySky} for the Sselith Repertory
 * dimension. {@code effects: minecraft:the_end} in the dimension JSON is a fallback
 * dark backdrop in case the mixin doesn't apply.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSselithRepertorySkyMixin {

    @Shadow @Nullable private ClientLevel level;

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$drawSselithSky(
        PoseStack poseStack, Matrix4f projectionMatrix, float partialTick,
        Camera camera, boolean isFoggy, Runnable setupFog,
        CallbackInfo ci
    ) {
        ClientLevel lvl = this.level;
        if (lvl == null) return;
        if (!lvl.dimension().equals(SselithRepertory.INSTANCE.getLEVEL_KEY())) return;
        SselithRepertorySky.INSTANCE.renderSky(poseStack, projectionMatrix, partialTick, camera, isFoggy, setupFog);
        ci.cancel();
    }
}
