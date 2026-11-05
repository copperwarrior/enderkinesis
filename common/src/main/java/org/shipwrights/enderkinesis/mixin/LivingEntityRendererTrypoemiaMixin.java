package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.shipwrights.enderkinesis.client.TrypoemiaRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererTrypoemiaMixin {

    @WrapOperation(
        method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V")
    )
    @SuppressWarnings("unchecked")
    private void enderkinesis$trypoemiaBody(
        EntityModel<?> model, PoseStack poseStack, VertexConsumer consumer,
        int light, int overlay, float r, float g, float b, float a,
        Operation<Void> original,
        @Local(argsOnly = true) LivingEntity entity,
        @Local(argsOnly = true) MultiBufferSource buffers
    ) {
        VertexConsumer body = TrypoemiaRender.begin(
            entity, consumer, buffers, ((EntityRenderer<Entity>) (Object) this).getTextureLocation(entity));
        try {
            original.call(model, poseStack, body, light, overlay, r, g, b, a);
        } finally {
            if (body != consumer) TrypoemiaRender.end();
        }
    }
}
