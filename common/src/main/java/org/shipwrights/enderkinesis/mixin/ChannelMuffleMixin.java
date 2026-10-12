package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.audio.Channel;
import org.shipwrights.enderkinesis.client.SselithMadnessMuffle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Channel.class)
public abstract class ChannelMuffleMixin {

    @Inject(method = "play", at = @At("HEAD"))
    private void enderkinesis$muffleNewSound(CallbackInfo ci) {
        SselithMadnessMuffle.applyToNew((Channel) (Object) this);
    }
}
