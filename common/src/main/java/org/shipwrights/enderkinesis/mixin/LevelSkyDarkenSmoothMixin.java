package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import org.shipwrights.enderkinesis.client.WohlonBiomeSkyState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Smooths the time-of-day brightness curves across the Wohlon biome blend without touching
 * the snapped {@code getTimeOfDay} that sun/moon/star matrices need.
 *
 * **Two paths.** {@code getSkyDarken} feeds fog brightness; {@code getSkyColor} has its own
 * inline cos/clamp for the sky disc. Both must be patched separately — fixing only
 * {@code getSkyDarken} leaves the sky disc snapping at night.
 *
 * **Output-lerp, not input-lerp.** Both formulas clamp `cos(t·2π)·2 + k` to [0,1], saturating
 * at the ceiling over ~`t ∈ [0.27, 0.73]`. Lerping the input time leaves the result clamped
 * at 0 for 93% of the blend journey and then leaps to non-zero — the V-shape snap. Computing
 * each endpoint's brightness independently then lerping in pure scalar space avoids it.
 */
@Mixin(ClientLevel.class)
public abstract class LevelSkyDarkenSmoothMixin {

    /** Vanilla's `t · 2π` factor. */
    private static final float ENDERKINESIS$TAU = 6.2831855F;

    @Inject(method = "getSkyDarken(F)F", at = @At("RETURN"), cancellable = true)
    private void enderkinesis$smoothSkyDarken(float partialTick, CallbackInfoReturnable<Float> cir) {
        float blend = WohlonBiomeSkyState.getCurrentBlend();
        if (blend <= 0f) return;

        ClientLevel self = (ClientLevel) (Object) this;
        float actualTime = WohlonBiomeSkyState.unsnappedTimeOfDayFromDayTime(self.getDayTime());
        float wohlonTime = WohlonBiomeSkyState.getWohlonTimeOfDay();

        float vanillaSkyDarken = enderkinesis$computeSkyDarkenAt(actualTime, partialTick, self);
        float wohlonSkyDarken = enderkinesis$computeSkyDarkenAt(wohlonTime, partialTick, self);

        cir.setReturnValue(vanillaSkyDarken + (wohlonSkyDarken - vanillaSkyDarken) * blend);
    }

    /** Only one Mth.clamp call exists in getSkyColor (verified against 1.20.1 bytecode), so
     *  the redirect doesn't need a slice/ordinal. */
    @Redirect(
        method = "getSkyColor(Lnet/minecraft/world/phys/Vec3;F)Lnet/minecraft/world/phys/Vec3;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/Mth;clamp(FFF)F"
        )
    )
    private float enderkinesis$smoothSkyColorBrightness(float value, float min, float max) {
        float blend = WohlonBiomeSkyState.getCurrentBlend();
        if (blend <= 0f) return Mth.clamp(value, min, max);

        ClientLevel self = (ClientLevel) (Object) this;
        float actualTime = WohlonBiomeSkyState.unsnappedTimeOfDayFromDayTime(self.getDayTime());
        float wohlonTime = WohlonBiomeSkyState.getWohlonTimeOfDay();

        float vanillaF7 = enderkinesis$computeSkyColorF7At(actualTime);
        float wohlonF7 = enderkinesis$computeSkyColorF7At(wohlonTime);

        return vanillaF7 + (wohlonF7 - vanillaF7) * blend;
    }

    private static float enderkinesis$computeSkyDarkenAt(float time, float partialTick, ClientLevel level) {
        float f1 = 1.0F - (Mth.cos(time * ENDERKINESIS$TAU) * 2.0F + 0.2F);
        f1 = Mth.clamp(f1, 0.0F, 1.0F);
        f1 = 1.0F - f1;
        f1 = f1 * (1.0F - level.getRainLevel(partialTick) * 5.0F / 16.0F);
        f1 = f1 * (1.0F - level.getThunderLevel(partialTick) * 5.0F / 16.0F);
        return f1;
    }

    /** Note the 0.5 vs getSkyDarken's 0.2 — that's why sky-disc and fog brightness curves
     *  differ even at the same time-of-day. */
    private static float enderkinesis$computeSkyColorF7At(float time) {
        return Mth.clamp(Mth.cos(time * ENDERKINESIS$TAU) * 2.0F + 0.5F, 0.0F, 1.0F);
    }
}
