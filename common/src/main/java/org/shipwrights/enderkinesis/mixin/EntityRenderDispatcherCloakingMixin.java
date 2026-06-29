package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.client.CloakBufferSource;
import org.shipwrights.enderkinesis.client.CloakingMixinSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Redirect cloaked entities into [CloakBufferSource] so they end up in the side FB
 * (the refraction layer), instead of in the main FB.
 *
 * <p>Wrapping the {@code EntityRenderer.render(...)} call inside
 * {@code EntityRenderDispatcher.render(...)} lets us swap the {@code MultiBufferSource}
 * argument per entity. Vanilla's batched source still gets every non-cloaked entity;
 * cloaked entities accumulate in our separate source, which is flushed to the side FB
 * later (see [LevelRendererBubblePassMixin]).
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherCloakingMixin {

    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
        )
    )
    private void enderkinesis$redirectIfConcealed(
        EntityRenderer<Entity> renderer,
        Entity entity, float yaw, float partialTick, PoseStack poseStack,
        MultiBufferSource buffer, int packedLight,
        Operation<Void> original
    ) {
        if (CloakingMixinSupport.isEntityConcealed(entity)) {
            // Vertex data lands in CloakBufferSource → flushed to side FB later.
            original.call(renderer, entity, yaw, partialTick, poseStack,
                CloakBufferSource.get(), packedLight);
        } else {
            original.call(renderer, entity, yaw, partialTick, poseStack,
                buffer, packedLight);
        }
    }
}
