package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.client.SpellInHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A Mystic Scroll held out in the world (anyone's, yours in third person) is drawn as the spell
 *  it carries, as the first-person hand shows it ({@link SpellInHand}), in place of the scroll. */
@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerSpellMixin {

    @WrapOperation(
        method = "renderArmWithItem",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    )
    private void enderkinesis$spellInHand(
        ItemInHandRenderer renderer, LivingEntity entity, ItemStack stack, ItemDisplayContext context, boolean leftHand,
        PoseStack pose, MultiBufferSource buffers, int light, Operation<Void> original
    ) {
        if (SpellInHand.draw(entity, stack, leftHand ? HumanoidArm.LEFT : HumanoidArm.RIGHT, pose, buffers, light)) return;
        original.call(renderer, entity, stack, context, leftHand, pose, buffers, light);
    }
}
