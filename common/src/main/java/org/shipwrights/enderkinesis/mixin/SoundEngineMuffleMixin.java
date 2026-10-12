package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import org.shipwrights.enderkinesis.client.SselithMadnessMuffle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
public abstract class SoundEngineMuffleMixin {

    @Shadow @Final private ChannelAccess channelAccess;
    @Shadow private boolean loaded;

    @Inject(method = "tickNonPaused", at = @At("TAIL"))
    private void enderkinesis$tickMuffle(CallbackInfo ci) {
        if (this.loaded) SselithMadnessMuffle.tick(this.channelAccess);
    }

    // The filter belongs to the OpenAL context that destroy() tears down.
    @Inject(method = "destroy", at = @At("HEAD"))
    private void enderkinesis$releaseMuffle(CallbackInfo ci) {
        if (this.loaded) SselithMadnessMuffle.release();
    }
}
