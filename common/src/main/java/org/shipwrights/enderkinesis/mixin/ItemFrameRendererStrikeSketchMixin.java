package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.shipwrights.enderkinesis.client.FramedSketches;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemFrameRenderer.class)
public abstract class ItemFrameRendererStrikeSketchMixin {

    @Shadow @Final private static ModelResourceLocation MAP_FRAME_LOCATION;
    @Shadow @Final private static ModelResourceLocation GLOW_MAP_FRAME_LOCATION;

    // The flat map frame: the full frame's rim would cut through paper at map depth.
    @ModifyReturnValue(method = "getFrameModelResourceLoc", at = @At("RETURN"))
    private ModelResourceLocation enderkinesis$strikeReadoutFrame(
        ModelResourceLocation original, ItemFrame entity, ItemStack stack
    ) {
        if (!FramedSketches.INSTANCE.shows(stack)) return original;
        return entity.getType() == EntityType.GLOW_ITEM_FRAME ? GLOW_MAP_FRAME_LOCATION : MAP_FRAME_LOCATION;
    }

    // Forge keeps this call after its RenderItemInFrameEvent, so one target serves both loaders.
    @WrapOperation(
        method = "render(Lnet/minecraft/world/entity/decoration/ItemFrame;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderStatic(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;IILcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;I)V"
        )
    )
    private void enderkinesis$strikeReadoutSketch(
        ItemRenderer renderer, ItemStack stack, ItemDisplayContext context, int light, int overlay,
        PoseStack pose, MultiBufferSource buffers, Level level, int seed, Operation<Void> original,
        @Local(argsOnly = true) ItemFrame entity
    ) {
        if (FramedSketches.INSTANCE.shows(stack)) {
            FramedSketches.INSTANCE.render(stack, pose, buffers, light, entity.getRotation());
        } else {
            original.call(renderer, stack, context, light, overlay, pose, buffers, level, seed);
        }
    }
}
