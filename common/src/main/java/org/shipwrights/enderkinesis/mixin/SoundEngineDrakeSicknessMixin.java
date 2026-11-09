package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.sounds.SoundEngine;
import org.shipwrights.enderkinesis.client.DrakeSicknessClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Ticking sounds read their pitch here each tick too, so a sound that is playing changes with the sickness. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineDrakeSicknessMixin {
    @ModifyReturnValue(method = "calculatePitch", at = @At("RETURN"))
    private float enderkinesis$drakeSicknessPitch(float pitch) {
        return DrakeSicknessClient.pitch(pitch);
    }
}
