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
 * Redirect the {@code level.getTimeOfDay(partialTick)} call inside
 * {@link LevelRenderer#renderSky} so the displayed sun arc lerps
 * toward Wohlon's fixed dusk pose (game-time ≈ 12815) when the
 * player is inside a Wohlon biome patch in any non-Wohlon
 * dimension. Rendering-only: the returned value never feeds
 * server simulation, only the sky-renderer matrix.
 *
 * Blend factor lives in
 * {@link WohlonBiomeSkyState#currentBlend}, updated each client
 * tick from a 3×3 biome sample around the player. With blend = 0
 * we return the level's actual time-of-day unchanged (this mixin
 * is a no-op in non-Wohlon-biome areas).
 *
 * Sky color and fog already blend biome-aware via vanilla's
 * `BiomeColors` pipeline, so we don't need to touch those — just
 * the sun's parameterised rotation.
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
        float[] orig = effects.getSunriseColor(timeOfDay, partialTick);
        if (!WohlonBiomeSkyState.shouldRenderWohlonSky()) return orig;
        if (orig == null) return null;
        // Wohlon biome `fog_color` = #395873 = (57, 88, 115).
        return new float[] {
            57f / 255f,
            88f / 255f,
            115f / 255f,
            orig[3],
        };
    }

    /**
     * Crossfade sun/moon alpha across the Wohlon biome blend: vanilla fades out 0→0.5,
     * Wohlon fades in 0.5→1.0. {@link Slice} is anchored between {@code getRainLevel}
     * (which produces the alpha) and {@code getStarBrightness} (which starts the stars
     * block with its own {@code setShaderColor}), so we hit only the sun/moon's call.
     */
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
        float blend = WohlonBiomeSkyState.currentBlend;
        if (blend <= 0f) return originalAlpha;
        if (blend >= 0.5f) {
            // Wohlon fade-in: alpha ramps 0 → 1 across blend 0.5 → 1.0
            return (2f * blend - 1f) * originalAlpha;
        }
        // Vanilla fade-out: alpha ramps 1 → 0 across blend 0 → 0.5
        return (1f - 2f * blend) * originalAlpha;
    }
}
