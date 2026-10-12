package org.shipwrights.enderkinesis.forge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.client.TightRobeArmorRenderer;
import org.shipwrights.enderkinesis.item.ScalingRobeArmorItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands each robe piece to the common {@link TightRobeArmorRenderer}, the same way Fabric
 * API's ArmorRenderer hook does. Forge's own hook, {@code getHumanoidArmorModel}, can only
 * swap the model; the robes need their own passes (hood, pearls, coat tails, witch hat).
 */
@Mixin(HumanoidArmorLayer.class)
public abstract class HumanoidArmorLayerRobeForgeMixin {

    @Inject(method = "renderArmorPiece", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("unchecked")
    private void enderkinesis$renderRobe(
        PoseStack poseStack, MultiBufferSource buffer, LivingEntity entity,
        EquipmentSlot slot, int light, HumanoidModel<?> armorModel, CallbackInfo ci
    ) {
        ItemStack stack = entity.getItemBySlot(slot);
        if (!(stack.getItem() instanceof ScalingRobeArmorItem robe)) return;
        // Vanilla draws a piece only in its own slot.
        if (robe.getEquipmentSlot() != slot) return;
        HumanoidModel<LivingEntity> parent =
            (HumanoidModel<LivingEntity>) ((RenderLayer<?, ?>) (Object) this).getParentModel();
        TightRobeArmorRenderer.getINSTANCE().render(poseStack, buffer, stack, entity, slot, light, parent);
        ci.cancel();
    }
}
