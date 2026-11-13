package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.client.VineHangPoseKt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// HumanoidModel, not PlayerModel: the sleeves copy the arms after super.setupAnim, and armor layers copy later still.
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelVineHangPoseMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void enderkinesis$vineHangPose(
        LivingEntity entity, float limbSwing, float limbSwingAmount,
        float ageInTicks, float netHeadYaw, float headPitch,
        CallbackInfo ci
    ) {
        if (!(entity instanceof Player player)) return;
        // ageInTicks = tickCount + partialTick.
        VineHangPoseKt.applyVineHangPose((HumanoidModel<?>) (Object) this, player, ageInTicks - player.tickCount);
    }
}
