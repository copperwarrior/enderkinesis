package org.shipwrights.enderkinesis.forge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.client.AntleredCrownRenderer;
import org.shipwrights.enderkinesis.registry.EKItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Fabric draws the crown from an ArmorRenderer; vanilla's layer draws only an ArmorItem.
@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerAntleredCrownForgeMixin {

    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$renderAntleredCrown(
        PoseStack poseStack, MultiBufferSource buffer, LivingEntity entity,
        EquipmentSlot slot, int light, HumanoidModel<?> armorModel, CallbackInfo ci
    ) {
        if (slot != EquipmentSlot.HEAD) return;
        ItemStack stack = entity.getItemBySlot(slot);
        if (!stack.is(EKItems.INSTANCE.getANTLERED_CROWN().get())) return;
        HumanoidModel<?> parent = (HumanoidModel<?>) ((RenderLayer<?, ?>) (Object) this).getParentModel();
        AntleredCrownRenderer.INSTANCE.render(poseStack, buffer, light, stack, parent.head);
        ci.cancel();
    }
}
