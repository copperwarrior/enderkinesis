package org.shipwrights.enderkinesis.forge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.client.CrownUntestedArmorRenderer;
import org.shipwrights.enderkinesis.registry.EKItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerCrownUntestedForgeMixin {

    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("unchecked")
    private void enderkinesis$renderCrownUntested(
        PoseStack poseStack, MultiBufferSource buffer, LivingEntity entity,
        EquipmentSlot slot, int light, HumanoidModel<?> armorModel, CallbackInfo ci
    ) {
        if (slot != EquipmentSlot.HEAD) return;
        ItemStack stack = entity.getItemBySlot(slot);
        if (!stack.is(EKItems.INSTANCE.getCROWN_UNTESTED().get())) return;
        HumanoidModel<LivingEntity> parent =
            (HumanoidModel<LivingEntity>) ((RenderLayer<?, ?>) (Object) this).getParentModel();
        CrownUntestedArmorRenderer.INSTANCE.render(poseStack, buffer, stack, slot, light, parent);
        ci.cancel();
    }
}
