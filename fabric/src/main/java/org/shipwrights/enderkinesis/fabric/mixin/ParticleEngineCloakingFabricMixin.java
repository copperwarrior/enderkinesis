package org.shipwrights.enderkinesis.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.shipwrights.enderkinesis.client.CloakingMixinSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Pass 1 of the cloaked-particle pipeline: capture.
 *
 * <p>For each {@code Particle.render(VertexConsumer, Camera, float)} call inside
 * {@code ParticleEngine.render}, if the particle's world position falls inside a
 * concealed ship, skip the vanilla draw (which would write to main FB) and instead
 * record the particle in {@link org.shipwrights.enderkinesis.client.CloakedParticleBuffer}. Pass 2 — the side-FB flush —
 * runs from {@link org.shipwrights.enderkinesis.mixin.LevelRendererBubblePassMixin} at {@code renderLevel} TAIL, alongside
 * the cloaked-entity flush. That timing is known to land in the side FB correctly
 * (cloaked entities render fine); an earlier attempt that flushed from inside
 * {@code ParticleEngine.render}'s @At("RETURN") issued valid GL draws but produced
 * no visible pixels in the side FB.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineCloakingFabricMixin {

    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V")
    )
    private void enderkinesis$captureIfConcealed(
        Particle particle, VertexConsumer buffer, Camera camera, float partialTick,
        Operation<Void> original
    ) {
        if (!CloakingMixinSupport.captureIfConcealed(particle)) original.call(particle, buffer, camera, partialTick);
    }
}
