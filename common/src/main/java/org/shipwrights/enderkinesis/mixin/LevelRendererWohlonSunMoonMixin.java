package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.shipwrights.enderkinesis.client.WohlonBiomeSkyState;
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Swaps the sun and moon textures inside
 * {@link LevelRenderer#renderSky LevelRenderer.renderSky} for
 * {@code enderkinesis:textures/wogor_eye.png} while the live dimension is
 * {@link Wohlonnogondonia}.
 *
 * <p>Vanilla binds the sun/moon textures via two
 * {@code RenderSystem.setShaderTexture(int, ResourceLocation)} calls inside
 * {@code renderSky}; this mixin {@link Redirect}s every such call from that
 * method, lets non-sun/moon textures (e.g. {@code WHITE_LOCATION} that
 * vanilla also binds in the same method) pass through untouched, and rewrites
 * the sun + moon paths to our Wogor eye. {@code DimensionSpecialEffects} has
 * no hook for the texture binding, hence the mixin.
 *
 * <p>The {@link WohlonnogondoniaDimensionEffects} (registered separately
 * client-side) is what configures the surrounding sky pass — most importantly
 * it returns {@code null} from {@code getSunriseColor} so vanilla's warm
 * yellow horizon ring is suppressed and only the biome's cool fog tint
 * shades the sky.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererWohlonSunMoonMixin {

    @Shadow @Nullable private ClientLevel level;

    private static final ResourceLocation ENDERKINESIS$WOGOR_EYE =
        new ResourceLocation("enderkinesis", "textures/wogor_eye.png");

    private static final ResourceLocation ENDERKINESIS$VANILLA_SUN =
        new ResourceLocation("textures/environment/sun.png");
    private static final ResourceLocation ENDERKINESIS$VANILLA_MOON =
        new ResourceLocation("textures/environment/moon_phases.png");

    @Redirect(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V"
        )
    )
    private void enderkinesis$swapWohlonSunMoon(int slot, ResourceLocation texture) {
        ClientLevel lvl = this.level;
        if (lvl != null && enderkinesis$wohlonSkyActive(lvl)
            && (texture.equals(ENDERKINESIS$VANILLA_SUN) || texture.equals(ENDERKINESIS$VANILLA_MOON))) {
            RenderSystem.setShaderTexture(slot, ENDERKINESIS$WOGOR_EYE);
            return;
        }
        RenderSystem.setShaderTexture(slot, texture);
    }

    /**
     * Force moon phase to 0 in Wohlon. Vanilla samples a `0.25 × 0.5`
     * tile of the {@code moon_phases.png} atlas keyed off the live moon
     * phase index — `j = i % 4`, `k = i / 4 % 2`. By making `i = 0` we
     * pin the tile to the top-left of the atlas, which is the corner the
     * single-image `wogor_eye.png` is laid against once the divisors are
     * also rewritten (see below).
     */
    @Redirect(
        method = "renderSky",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMoonPhase()I"
        )
    )
    private int enderkinesis$wohlonMoonPhase(ClientLevel lvl) {
        if (enderkinesis$wohlonSkyActive(lvl)) {
            return 0;
        }
        return lvl.getMoonPhase();
    }

    private boolean enderkinesis$wohlonSkyActive(ClientLevel lvl) {
        if (lvl.dimension().equals(Wohlonnogondonia.INSTANCE.getLEVEL_KEY())) return true;
        return WohlonBiomeSkyState.shouldRenderWohlonSky();
    }

    /**
     * Replace the moon-UV divisors {@code 4.0F} (X) and {@code 2.0F} (Y)
     * with {@code 1.0F} in Wohlon, so the per-tile UV span
     * `(j+1)/divisor - j/divisor` collapses to a full `[0, 1]`. Combined
     * with {@link #enderkinesis$wohlonMoonPhase} forcing `j = k = 0`,
     * the moon quad now samples the whole {@code wogor_eye.png} image
     * instead of the top-left 1/8 of it.
     *
     * <p>The slice is anchored between the {@code MOON_LOCATION} field
     * reference (which marks the start of the moon section in
     * {@code renderSky}) and the moon's {@code BufferUploader.drawWithShader}
     * call, so we never touch {@code 4.0F}/{@code 2.0F} constants used
     * by neighbouring sun/star rendering.
     */
    @ModifyConstant(
        method = "renderSky",
        constant = { @Constant(floatValue = 4.0F), @Constant(floatValue = 2.0F) },
        // Slice extends from the MOON_LOCATION field reference to the end
        // of renderSky. An explicit `to` doesn't help here — the only
        // candidates (drawWithShader calls) appear earlier in the method
        // too, for the sky gradient and sun, so Mixin rejected those as
        // negative-size slices. Star rendering after the moon doesn't
        // use 4.0F / 2.0F float constants in renderSky itself (they live
        // inside other methods like renderStars), so the unbounded slice
        // only matches the moon UV divisors.
        slice = @Slice(
            from = @At(
                value = "FIELD",
                target = "Lnet/minecraft/client/renderer/LevelRenderer;MOON_LOCATION:Lnet/minecraft/resources/ResourceLocation;"
            )
        )
    )
    private float enderkinesis$wohlonMoonUVDivisor(float orig) {
        ClientLevel lvl = this.level;
        if (lvl != null && enderkinesis$wohlonSkyActive(lvl)) {
            return 1.0F;
        }
        return orig;
    }

    static {
        // Keep WOGOR_EYE from being dropped — only the reflectively-invoked redirect path reads it.
        @SuppressWarnings("unused")
        ResourceLocation keep = ENDERKINESIS$WOGOR_EYE;
    }
}
