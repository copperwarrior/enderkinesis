package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.SureibjinSky;
import org.shipwrights.enderkinesis.dimension.Sureibjin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replace the vanilla sky with {@link SureibjinSky} for the Sureibjin
 * dimension. The dimension's effects entry is set to
 * {@code enderkinesis:sureibjin} which resolves to a SkyType.NONE
 * {@link net.minecraft.client.renderer.DimensionSpecialEffects} as a
 * fallback in case this mixin doesn't apply.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererSureibjinSkyMixin {

    @Shadow @Nullable private ClientLevel level;

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$drawSureibjinSky(
        PoseStack poseStack, Matrix4f projectionMatrix, float partialTick,
        Camera camera, boolean isFoggy, Runnable setupFog,
        CallbackInfo ci
    ) {
        ClientLevel lvl = this.level;
        if (lvl == null) return;
        if (!lvl.dimension().equals(Sureibjin.INSTANCE.getLEVEL_KEY())) return;
        SureibjinSky.renderSky(poseStack, projectionMatrix, partialTick, camera, isFoggy, setupFog);
        ci.cancel();
    }
}
