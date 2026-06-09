package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LevelRenderer;
import org.shipwrights.enderkinesis.client.WohlonBiomeSkyState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Wohlon-biome sky overlays. V-shape cross-fade: vanilla 0→0.5, Wohlon 0.5→1. At blend=0.5
 * {@link WohlonBiomeSkyState#blendTimeOfDay} hard-snaps time-of-day — celestials must be at
 * zero alpha at exactly that moment to hide the rotation step.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererWohlonBiomeSkyMixin {

    @Redirect(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/DimensionSpecialEffects;getSunriseColor(FF)[F"
        )
    )
    private float[] enderkinesis$wohlonSunsetColor(DimensionSpecialEffects effects, float timeOfDay, float partialTick) {
        float blend = WohlonBiomeSkyState.getCurrentBlend();
        if (blend <= 0f) return effects.getSunriseColor(timeOfDay, partialTick);
        if (blend >= 0.5f) return null;
        float[] orig = effects.getSunriseColor(timeOfDay, partialTick);
        if (orig == null) return null;
        return new float[] {
            orig[0], orig[1], orig[2],
            orig[3] * (1f - 2f * blend),
        };
    }

    /** Slice from getRainLevel (alpha source) to getStarBrightness (next setShaderColor block)
     *  to hit only the sun/moon call. */
    @ModifyArg(
        method = "renderSky",
        slice = @Slice(
            from = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),
            to = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getStarBrightness(F)F")
        ),
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderColor(FFFF)V"),
        index = 3
    )
    private float enderkinesis$crossfadeSunMoonAlpha(float originalAlpha) {
        float blend = WohlonBiomeSkyState.getCurrentBlend();
        if (blend <= 0f) return originalAlpha;
        if (blend >= 0.5f) {
            // Wohlon fade-in: alpha ramps 0 → 1 across blend 0.5 → 1.0
            return (2f * blend - 1f) * originalAlpha;
        }
        // Vanilla fade-out: alpha ramps 1 → 0 across blend 0 → 0.5
        return (1f - 2f * blend) * originalAlpha;
    }

    @ModifyArg(
        method = "renderSky",
        slice = @Slice(
            from = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getStarBrightness(F)F"),
            to = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FogRenderer;setupNoFog()V")
        ),
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderColor(FFFF)V"),
        index = 3
    )
    private float enderkinesis$crossfadeStarAlpha(float originalAlpha) {
        float blend = WohlonBiomeSkyState.getCurrentBlend();
        if (blend <= 0f) return originalAlpha;
        if (blend >= 0.5f) {
            return (2f * blend - 1f) * originalAlpha;
        }
        return (1f - 2f * blend) * originalAlpha;
    }
}
