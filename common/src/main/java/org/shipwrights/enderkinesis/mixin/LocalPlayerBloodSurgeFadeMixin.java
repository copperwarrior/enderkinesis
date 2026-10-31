package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.shipwrights.enderkinesis.client.BloodSurgeFadeClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The health the server sends: Blood Surge's hearts fading are set without the hurt tilt and red
 *  flash ({@link BloodSurgeFadeClient}); anything else, vanilla's way. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerBloodSurgeFadeMixin {

    @Inject(method = "hurtTo", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$quietBloodSurgeFade(float health, CallbackInfo ci) {
        if (BloodSurgeFadeClient.quietFade((LocalPlayer) (Object) this, health)) ci.cancel();
    }
}
