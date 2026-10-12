package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.client.StaffGemGlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemRenderer.class)
public abstract class ItemRendererStaffGemMixin {

    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderModelLists(Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/item/ItemStack;IILcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"
        )
    )
    private void enderkinesis$glowStaffGem(
        ItemRenderer renderer,
        BakedModel model, ItemStack stack, int packedLight, int packedOverlay,
        PoseStack poseStack, VertexConsumer vertexConsumer,
        Operation<Void> original,
        @Local(argsOnly = true) ItemDisplayContext displayContext,
        @Local(argsOnly = true) MultiBufferSource buffers
    ) {
        StaffGemGlow.Parts parts = displayContext == ItemDisplayContext.GUI
            ? null
            : StaffGemGlow.partsOf(stack, model);
        if (parts == null) {
            original.call(renderer, model, stack, packedLight, packedOverlay, poseStack, vertexConsumer);
            return;
        }
        original.call(renderer, parts.getBody(), stack, packedLight, packedOverlay, poseStack, vertexConsumer);
        poseStack.pushPose();
        parts.spin(stack, poseStack);
        VertexConsumer gemConsumer = parts.emitHalo(stack, poseStack, buffers, vertexConsumer);
        original.call(renderer, parts.getGem(), stack, LightTexture.FULL_BRIGHT, packedOverlay, poseStack, gemConsumer);
        poseStack.popPose();
    }
}
