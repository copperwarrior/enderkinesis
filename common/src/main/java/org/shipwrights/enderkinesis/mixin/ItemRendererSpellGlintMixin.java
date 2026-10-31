package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.client.SpellGlint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemRenderer.class)
public abstract class ItemRendererSpellGlintMixin {
    /** Set by the hasFoil() wrap; the render wrap reads it right after. */
    @Unique private ItemStack enderkinesis$glintStack;
    @Unique private int enderkinesis$glintColour = SpellGlint.NONE;

    @WrapOperation(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderModelLists(Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/item/ItemStack;IILcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V")
    )
    private void enderkinesis$spellGlint(
        ItemRenderer self, BakedModel model, ItemStack stack, int light, int overlay, PoseStack pose, VertexConsumer consumer,
        Operation<Void> original, @Local(argsOnly = true) MultiBufferSource buffers
    ) {
        original.call(self, model, stack, light, overlay, pose, consumer);
        int rgb = stack == enderkinesis$glintStack ? enderkinesis$glintColour : SpellGlint.colourOf(stack);
        enderkinesis$glintStack = null;
        if (rgb != SpellGlint.NONE) original.call(self, model, stack, light, overlay, pose, SpellGlint.buffer(buffers, rgb));
    }

    @WrapOperation(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;hasFoil()Z")
    )
    private boolean enderkinesis$noVanillaGlint(ItemStack stack, Operation<Boolean> original) {
        int rgb = SpellGlint.colourOf(stack);
        enderkinesis$glintStack = stack;
        enderkinesis$glintColour = rgb;
        return rgb == SpellGlint.NONE && original.call(stack);
    }
}
