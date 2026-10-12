package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.client.OwlsMarkClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Owl's Mark reuses vanilla Darkness's lightmap terms with the pulse held at its peak. */
@Mixin(LightTexture.class)
public abstract class LightTextureOwlsMarkMixin {

    @Inject(method = "getDarknessGamma", at = @At("RETURN"), cancellable = true)
    private void enderkinesis$owlsMarkGamma(float partialTick, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(Math.max(cir.getReturnValueF(), OwlsMarkClient.factor(partialTick)));
    }

    @Inject(method = "calculateDarknessScale", at = @At("RETURN"), cancellable = true)
    private void enderkinesis$owlsMarkScale(
        LivingEntity entity, float gamma, float partialTick, CallbackInfoReturnable<Float> cir
    ) {
        cir.setReturnValue(Math.max(cir.getReturnValueF(), OwlsMarkClient.darknessScale(partialTick)));
    }
}
