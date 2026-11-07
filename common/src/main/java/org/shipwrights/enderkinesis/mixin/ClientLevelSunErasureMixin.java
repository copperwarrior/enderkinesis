package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.ClientLevel;
import org.shipwrights.enderkinesis.ritual.SunErasure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Lightmap sky brightness goes to 0; sky and cloud colour darken as by thunder. Rain particles read the level elsewhere.
@Mixin(ClientLevel.class)
public abstract class ClientLevelSunErasureMixin {

    @ModifyReturnValue(method = "getSkyDarken", at = @At("RETURN"))
    private float enderkinesis$erasedSunDimsLight(float brightness) {
        return SunErasure.skyBrightness((ClientLevel) (Object) this, brightness);
    }

    @ModifyExpressionValue(method = {"getSkyColor", "getCloudColor"}, at = {
        @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"),
        @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getThunderLevel(F)F")
    })
    private float enderkinesis$erasedSunDims(float weather) {
        return SunErasure.darkness((ClientLevel) (Object) this, weather);
    }
}
