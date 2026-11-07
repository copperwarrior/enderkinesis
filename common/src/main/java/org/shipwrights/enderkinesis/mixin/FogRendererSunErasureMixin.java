package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.shipwrights.enderkinesis.ritual.SunErasure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// The fog colour darkens with the sky, so the horizon does not glow under an erased sun.
@Mixin(FogRenderer.class)
public abstract class FogRendererSunErasureMixin {

    @ModifyExpressionValue(method = "setupColor", at = {
        @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),
        @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getThunderLevel(F)F")
    })
    private static float enderkinesis$erasedSunFog(float weather, @Local(argsOnly = true) ClientLevel level) {
        return SunErasure.darkness(level, weather);
    }
}
