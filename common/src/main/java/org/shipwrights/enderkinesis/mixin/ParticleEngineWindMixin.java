package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.shipwrights.enderkinesis.client.IncantationConeVolume;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public abstract class ParticleEngineWindMixin {

    @Inject(method = "tickParticle", at = @At("HEAD"))
    private void enderkinesis$windPush(Particle particle, CallbackInfo ci) {
        IncantationConeVolume.pushParticle(particle);
    }
}
