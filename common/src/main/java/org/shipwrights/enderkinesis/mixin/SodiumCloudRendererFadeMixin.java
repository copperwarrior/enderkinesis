package org.shipwrights.enderkinesis.mixin;

import me.jellysquid.mods.sodium.client.render.immediate.CloudRenderer;
import org.shipwrights.enderkinesis.client.WohlonBiomeSkyState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium / Embeddium mirror of {@link LevelRendererCloudsWohlonBiomeMixin}. Their cloud renderer
 * sets its own shader colour (alpha 0.8) after the vanilla hook, so the fade set there is lost.
 * Gated by {@link EnderkinesisMixinPlugin} on the Sodium or the Embeddium marker.
 */
@Mixin(value = CloudRenderer.class, remap = false)
public abstract class SodiumCloudRendererFadeMixin {

    // Does not depend on the order of the two HEAD hooks on LevelRenderer.renderClouds.
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void enderkinesis$wohlonCloudCull(CallbackInfo ci) {
        if (WohlonBiomeSkyState.getCurrentBlend() >= 0.5f) ci.cancel();
    }

    @ModifyArg(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderColor(FFFF)V",
            ordinal = 0,
            remap = false
        ),
        index = 3,
        remap = false
    )
    private float enderkinesis$wohlonCloudFade(float alpha) {
        float blend = WohlonBiomeSkyState.getCurrentBlend();
        if (blend <= 0f) return alpha;
        return alpha * Math.max(0f, 1f - 2f * blend);
    }
}
