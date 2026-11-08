package org.shipwrights.enderkinesis.mixin;

import org.shipwrights.enderkinesis.compat.distanthorizons.DistantHorizonsCompat;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.shipwrights.enderkinesis.client.WohlonAtmosphere;
import org.shipwrights.enderkinesis.client.WohlonBiomeFog;
import org.shipwrights.enderkinesis.client.WohlonBiomeSkyState;
import org.shipwrights.enderkinesis.client.IchaericInkVisuals;
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wohlon biome fog overlay: TAIL setupColor tint, TAIL setupFog range, sunset suppression. Biome-
 * keyed and lerped through {@link WohlonBiomeFog#currentBlend}. Fog alpha stays 1, so the sky and the
 * terrain edge always reach the fog colour; the height density moves the fog start. Biome JSON pins
 * {@code fog_color = sky_color}, so vanilla's setupColor + brightness modulation drives them in
 * lockstep; the {@link WohlonAtmosphere} drift tints both by the same factor.
 */
@Mixin(value = FogRenderer.class, priority = DistantHorizonsCompat.FOG_MIXIN_PRIORITY)
public abstract class FogRendererWohlonBiomeMixin {

    @Shadow private static float fogRed;
    @Shadow private static float fogGreen;
    @Shadow private static float fogBlue;

    // setupColor mixes in the sky colour; untinted here, so the TAIL tint applies to it one time.
    @Inject(
        method = "setupColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IF)V",
        at = @At("HEAD")
    )
    private static void enderkinesis$suppressSkyTint(
        Camera camera, float partialTick, ClientLevel level,
        int renderDistance, float darkenWorldAmount,
        CallbackInfo ci
    ) {
        WohlonAtmosphere.setSkyTintSuppressed(true);
    }

    /** Single sample point — every other injection reads `currentBlend` directly. */
    @Inject(
        method = "setupColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IF)V",
        at = @At("TAIL")
    )
    private static void enderkinesis$updateWohlonBlend(
        Camera camera, float partialTick, ClientLevel level,
        int renderDistance, float darkenWorldAmount,
        CallbackInfo ci
    ) {
        WohlonAtmosphere.setSkyTintSuppressed(false);
        WohlonBiomeFog.sampleBlend(level, camera);
        WohlonAtmosphere.update(level, camera);
        if (enderkinesis$inMurkyWater(camera)) {
            fogRed = WohlonAtmosphere.murkRed();
            fogGreen = WohlonAtmosphere.murkGreen();
            fogBlue = WohlonAtmosphere.murkBlue();
            RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0F);
            return;
        }
        if (WohlonBiomeFog.getCurrentBlend() <= 0f) return;
        if (IchaericInkVisuals.cameraInInk() || enderkinesis$wohlonVanillaFogApplies(camera)) return;
        fogRed *= WohlonAtmosphere.INSTANCE.getTintR();
        fogGreen *= WohlonAtmosphere.INSTANCE.getTintG();
        fogBlue *= WohlonAtmosphere.INSTANCE.getTintB();
        RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0F);
        WohlonAtmosphere.recordAirFog(fogRed, fogGreen, fogBlue);
    }

    @Inject(
        method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
        at = @At("TAIL")
    )
    private static void enderkinesis$wohlonFogRange(
        Camera camera, FogRenderer.FogMode mode, float farPlaneDistance, boolean nearby, float partialTick,
        CallbackInfo ci
    ) {
        if (enderkinesis$applyMurkyWaterFog(camera)) return;
        if (mode != FogRenderer.FogMode.FOG_TERRAIN) return;
        enderkinesis$recordTerrainFog();
        float blend = WohlonBiomeFog.getCurrentBlend();
        if (blend <= 0f) return;
        if (IchaericInkVisuals.cameraInInk() || enderkinesis$wohlonVanillaFogApplies(camera)) return;
        // Vanilla ramps over the last tenth of the render distance, which reads as a wall. Thinner air
        // starts the ramp further out; the fog always reaches full at the end, so terrain never shows an edge.
        float end = farPlaneDistance * WohlonAtmosphere.INSTANCE.getFogEndScale();
        float thin = (1f - WohlonBiomeFog.fogDensityAt(camera.getPosition().y)) / (1f - WohlonBiomeFog.FOG_MIN_DENSITY);
        float start = end * ENDERKINESIS$START_THIN * thin;
        // Distant Horizons can push the fog out past the far plane before we run; blend from vanilla's range then.
        boolean pushedOut = RenderSystem.getShaderFogEnd() > farPlaneDistance;
        float baseStart = pushedOut ? farPlaneDistance - Mth.clamp(farPlaneDistance / 10f, 4f, 64f) : RenderSystem.getShaderFogStart();
        float baseEnd = pushedOut ? farPlaneDistance : RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Mth.lerp(blend, baseStart, start));
        RenderSystem.setShaderFogEnd(Mth.lerp(blend, baseEnd, end));
        enderkinesis$recordTerrainFog();
    }

    private static void enderkinesis$recordTerrainFog() {
        WohlonAtmosphere.setTerrainFogStart(RenderSystem.getShaderFogStart());
        WohlonAtmosphere.setTerrainFogEnd(RenderSystem.getShaderFogEnd());
    }

    // Vanilla water fog reaches 96 blocks; Wohlon water is murky.
    private static boolean enderkinesis$applyMurkyWaterFog(Camera camera) {
        if (!enderkinesis$inMurkyWater(camera)) return false;
        float vision = camera.getEntity() instanceof LocalPlayer player ? player.getWaterVision() : 1f;
        RenderSystem.setShaderFogStart(ENDERKINESIS$MURK_START);
        RenderSystem.setShaderFogEnd(Mth.lerp(vision, ENDERKINESIS$MURK_END_MIN, ENDERKINESIS$MURK_END_MAX));
        return true;
    }

    private static boolean enderkinesis$inMurkyWater(Camera camera) {
        if (camera.getFluidInCamera() != FogType.WATER) return false;
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.getBiome(camera.getBlockPosition()).is(Wohlonnogondonia.BIOME_KEY);
    }

    private static final float ENDERKINESIS$MURK_START = -8f;
    private static final float ENDERKINESIS$MURK_END_MIN = 6f;
    private static final float ENDERKINESIS$MURK_END_MAX = 16f;

    /** Fog start, as a fraction of the end, at the thinnest fog of {@link WohlonBiomeFog#fogDensityAt}. */
    private static final float ENDERKINESIS$START_THIN = 0.6f;

    /** Wohlon's snapped dusk sits inside vanilla's sunset window, so {@code setupColor} would
     *  paint fog ~85% orange every frame. Fade-and-null mirroring
     *  {@link LevelRendererWohlonBiomeSkyMixin#enderkinesis$wohlonSunsetColor}. */
    @WrapOperation(
        method = "setupColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;getSunriseColor(FF)[F"
        )
    )
    private static float[] enderkinesis$suppressWohlonFogSunsetTint(
            DimensionSpecialEffects effects, float timeOfDay, float partialTick, Operation<float[]> original) {
        float blend = WohlonBiomeFog.getCurrentBlend();
        if (blend <= 0f) return original.call(effects, timeOfDay, partialTick);
        if (blend >= 0.5f) return null;
        float[] orig = original.call(effects, timeOfDay, partialTick);
        if (orig == null) return null;
        return new float[] {
            orig[0], orig[1], orig[2],
            orig[3] * (1f - 2f * blend),
        };
    }

    // Fluid, Blindness and Darkness fog are opaque and near; a thin Wohlon alpha shows terrain through them.
    private static boolean enderkinesis$wohlonVanillaFogApplies(Camera camera) {
        if (camera.getFluidInCamera() != FogType.NONE) return true;
        return camera.getEntity() instanceof LivingEntity living
            && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS));
    }

    /**
     * Fog brightness comes from {@code clamp(cos(timeOfDay · 2π) · 2 + 0.5)}. The time of day
     * snaps at blend 0.5, so the brightness had a hard step there. Same output-lerp as
     * {@link LevelSkyDarkenSmoothMixin}: the returned cosine gives the lerped brightness
     * after the vanilla clamp.
     */
    @WrapOperation(
        method = "setupColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;cos(F)F")
    )
    private static float enderkinesis$smoothWohlonFogBrightness(
            float angle, Operation<Float> original, @Local(argsOnly = true) ClientLevel level) {
        float blend = WohlonBiomeFog.getCurrentBlend();
        if (blend <= 0f || level.dimensionType().hasFixedTime()) return original.call(angle);
        float actual = WohlonBiomeSkyState.unsnappedTimeOfDayFromDayTime(level.getDayTime());
        float vanilla = Mth.clamp(Mth.cos(actual * Mth.TWO_PI) * 2.0F + 0.5F, 0.0F, 1.0F);
        float wohlon = Mth.clamp(
            Mth.cos(WohlonBiomeSkyState.getWohlonTimeOfDay() * Mth.TWO_PI) * 2.0F + 0.5F, 0.0F, 1.0F);
        return (Mth.lerp(blend, vanilla, wohlon) - 0.5F) / 2.0F;
    }

}
