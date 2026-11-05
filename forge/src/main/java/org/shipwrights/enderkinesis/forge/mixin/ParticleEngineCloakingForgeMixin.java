package org.shipwrights.enderkinesis.forge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.shipwrights.enderkinesis.client.CloakingMixinSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Forge twin of {@link org.shipwrights.enderkinesis.fabric.mixin.ParticleEngineCloakingFabricMixin}. */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineCloakingForgeMixin {
    // Forge's 5-arg render only forwards to this Frustum overload, which holds the Particle.render calls.

    @WrapOperation(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V",
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
