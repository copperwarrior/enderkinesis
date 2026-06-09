package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.FogRenderer;
import org.shipwrights.enderkinesis.client.WohlonBiomeFog;
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
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogColor(FFF)V"
        )
    )
    private static void enderkinesis$applyWohlonFogAlpha(
            float r, float g, float b, Operation<Void> original) {
        float blend = WohlonBiomeFog.getCurrentBlend();
        if (blend <= 0f) {
            // Pass through so other @WrapOperations (Sselith dim) can chain.
            original.call(r, g, b);
            return;
        }
        // Lerp FROM vanilla's implicit 1.0 toward the Wohlon target. Naive `blend * density`
        // would crash alpha to near-zero at blend just above 0 (vanilla pre-blend was 1.0).
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
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

    // No setupFog override: Wohlon character is colour+alpha only. Vanilla terrain shape is
    // already CYLINDER, so no shape override needed either.
}
