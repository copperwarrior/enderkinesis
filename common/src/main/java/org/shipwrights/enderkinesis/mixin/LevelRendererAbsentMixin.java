package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.shipwrights.enderkinesis.client.YgannAbyssWatchingEyes;
import org.shipwrights.enderkinesis.client.YgannAbyssWrithingSea;
import org.shipwrights.enderkinesis.client.YgannSky;
import org.shipwrights.enderkinesis.dimension.YgannAbyss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In Ygann's Abyss the sky is already correct. In other dimensions the Abyss sky replaces the dimension sky. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererAbsentMixin {

    @Shadow @Nullable private ClientLevel level;

    @Shadow
    private void renderEndSky(PoseStack poseStack) {
        throw new AssertionError();
    }

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$absentSky(PoseStack poseStack, Matrix4f projection, float partialTick, Camera camera,
                                        boolean isFoggy, Runnable setupFog, CallbackInfo ci) {
        ClientLevel lvl = this.level;
        if (lvl == null || !YgannSky.absentView() || lvl.dimension().equals(YgannAbyss.INSTANCE.getLEVEL_KEY())) return;
        renderEndSky(poseStack);
        YgannAbyssWrithingSea.INSTANCE.renderSky(poseStack, partialTick);
        if (YgannAbyssWatchingEyes.INSTANCE.isShowing(lvl)) YgannAbyssWatchingEyes.INSTANCE.renderSky(poseStack, partialTick);
        ci.cancel();
    }

    @Inject(method = "renderChunkLayer", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$absentTerrain(CallbackInfo ci) {
        if (YgannSky.absentView()) ci.cancel();
    }

    @Inject(method = "renderEntity", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$absentEntities(CallbackInfo ci) {
        if (YgannSky.absentView()) ci.cancel();
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$absentClouds(CallbackInfo ci) {
        if (YgannSky.absentView()) ci.cancel();
    }

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$absentWeather(CallbackInfo ci) {
        if (YgannSky.absentView()) ci.cancel();
    }

    @Inject(method = "renderHitOutline", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$absentOutline(CallbackInfo ci) {
        if (YgannSky.absentView()) ci.cancel();
    }

    @Inject(method = "renderWorldBorder", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$absentBorder(CallbackInfo ci) {
        if (YgannSky.absentView()) ci.cancel();
    }
}
