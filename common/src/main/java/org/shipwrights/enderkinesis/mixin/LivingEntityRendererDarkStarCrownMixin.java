package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.client.DarkStarCrownRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Only the model and layer buffers are tinted; the name tag keeps the caller's buffer.
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererDarkStarCrownMixin {

    @WrapOperation(
        method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V")
    )
    private void enderkinesis$silhouetteModel(
        EntityModel<?> model, PoseStack poseStack, VertexConsumer consumer,
        int light, int overlay, float r, float g, float b, float a,
        Operation<Void> original,
        @Local(argsOnly = true) LivingEntity entity
    ) {
        original.call(model, poseStack, DarkStarCrownRenderer.tintModel(entity, consumer), light, overlay, r, g, b, a);
    }

    @WrapOperation(
        method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V")
    )
    private void enderkinesis$silhouetteLayer(
        RenderLayer<?, ?> layer, PoseStack poseStack, MultiBufferSource buffer, int light, Entity entity,
        float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch,
        Operation<Void> original
    ) {
        original.call(layer, poseStack, DarkStarCrownRenderer.tintLayers(entity, buffer), light, entity,
            limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch);
    }

    @Inject(
        method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("TAIL")
    )
    private void enderkinesis$crownStars(
        LivingEntity entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int light,
        CallbackInfo ci
    ) {
        DarkStarCrownRenderer.renderStars(entity, partialTick, poseStack, buffer);
    }
}
