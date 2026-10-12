package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.shipwrights.enderkinesis.client.WohlonBiomeFog;
import org.shipwrights.enderkinesis.client.WohlonBiomeSkyState;
import org.shipwrights.enderkinesis.client.IchaericInkVisuals;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wohlon biome fog overlay — mirrors {@link FogRendererSselithRepertoryMixin}'s shape
 * (TAIL setupColor, @WrapOperation on shader fog colour, sunset suppression) but is biome-
 * keyed and lerped through {@link WohlonBiomeFog#currentBlend}. Fog colour itself is NOT
 * lerped here — biome JSON pins {@code fog_color = sky_color}, so vanilla's setupColor +
 * brightness modulation drives them in lockstep.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererWohlonBiomeMixin {

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
        WohlonBiomeFog.sampleBlend(level, camera);
    }

    /** 3-arg setShaderFogColor sets alpha=1 implicitly; route through 4-arg with our height-driven alpha. */
    @WrapOperation(
        method = "levelFogColor()V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogColor(FFF)V",
            remap = false
        )
    )
    private static void enderkinesis$applyWohlonFogAlpha(
            float r, float g, float b, Operation<Void> original) {
        float blend = WohlonBiomeFog.getCurrentBlend();
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (blend <= 0f || IchaericInkVisuals.cameraInInk() || enderkinesis$vanillaFogApplies(camera)) {
            // Pass through so other @WrapOperations (Sselith dim) can chain.
            original.call(r, g, b);
            return;
        }
        // Lerp FROM vanilla's implicit 1.0 toward the Wohlon target. Naive `blend * density`
        // would crash alpha to near-zero at blend just above 0 (vanilla pre-blend was 1.0).
        float density = WohlonBiomeFog.fogDensityAt(camera.getPosition().y);
        float alpha = 1f - blend * (1f - density);
        RenderSystem.setShaderFogColor(r, g, b, alpha);
    }

    /** Wohlon's snapped dusk sits inside vanilla's sunset window, so {@code setupColor} would
     *  paint fog ~85% orange every frame. Fade-and-null mirroring
     *  {@link LevelRendererWohlonBiomeSkyMixin#enderkinesis$wohlonSunsetColor}. */
    @Redirect(
        method = "setupColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;getSunriseColor(FF)[F"
        )
    )
    private static float[] enderkinesis$suppressWohlonFogSunsetTint(
            DimensionSpecialEffects effects, float timeOfDay, float partialTick) {
        float blend = WohlonBiomeFog.getCurrentBlend();
        if (blend <= 0f) return effects.getSunriseColor(timeOfDay, partialTick);
        if (blend >= 0.5f) return null;
        float[] orig = effects.getSunriseColor(timeOfDay, partialTick);
        if (orig == null) return null;
        return new float[] {
            orig[0], orig[1], orig[2],
            orig[3] * (1f - 2f * blend),
        };
    }

    // Fluid, Blindness and Darkness fog are opaque and near; a thin Wohlon alpha shows terrain through them.
    private static boolean enderkinesis$vanillaFogApplies(Camera camera) {
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

    // No setupFog override: Wohlon character is colour+alpha only. Vanilla terrain shape is
    // already CYLINDER, so no shape override needed either.
}
