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

    // The pose stays STANDING while hanging (PlayerVineHangPoseMixin), so the renderer never sets crouching itself.
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void enderkinesis$vineSlideCrouch(
        LivingEntity entity, float limbSwing, float limbSwingAmount,
        float ageInTicks, float netHeadYaw, float headPitch,
        CallbackInfo ci
    ) {
        if (!(entity instanceof Player player)) return;
        float partialTick = ageInTicks - player.tickCount;
        if (partialTick < 0f || partialTick > 1f) return;
        VineHangPoseKt.applyVineSlideCrouch((HumanoidModel<?>) (Object) this, player, partialTick);
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void enderkinesis$vineHangPose(
        LivingEntity entity, float limbSwing, float limbSwingAmount,
        float ageInTicks, float netHeadYaw, float headPitch,
        CallbackInfo ci
    ) {
        if (!(entity instanceof Player player)) return;
        // ageInTicks = tickCount + partialTick in the world pass. The first-person hand (PlayerRenderer.renderHand)
        // passes 0, so the partial tick goes out of [0, 1]: keep the vanilla still hand there.
        float partialTick = ageInTicks - player.tickCount;
        if (partialTick < 0f || partialTick > 1f) return;
        VineHangPoseKt.applyVineHangPose((HumanoidModel<?>) (Object) this, player, partialTick);
    }
}
