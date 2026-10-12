package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.client.IncantationOfWindsClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Third-person cast pose for the Incantation of Winds: the casting arm
 * raises up-and-forward and tilts on the same oscillation curve the
 * first-person hand uses. Targeting {@code HumanoidModel} (not
 * {@code PlayerModel}) keeps the skin, sleeves, and every armor layer in
 * lockstep — sleeves copy from the arm after super.setupAnim, and armor
 * layers' {@code copyPropertiesTo} runs later still.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelWindsCastPoseMixin {

    @Inject(
        method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
        at = @At("TAIL")
    )
    private void enderkinesis$windsCastArm(
        LivingEntity entity, float limbSwing, float limbSwingAmount,
        float ageInTicks, float netHeadYaw, float headPitch,
        CallbackInfo ci
    ) {
        if (!(entity instanceof Player player)) return;
        InteractionHand hand = player.getUsedItemHand();
        if (!IncantationOfWindsClient.isCastingWinds(player, hand)) return;

        HumanoidModel<?> self = (HumanoidModel<?>) (Object) this;
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND
            ? player.getMainArm()
            : player.getMainArm().getOpposite();
        // ageInTicks = tickCount + partialTick, so the fraction recovers
        // the partial for smooth interpolation.
        float partialTicks = ageInTicks - player.tickCount;
        IncantationOfWindsClient.applyThirdPersonCastPose(
            arm == HumanoidArm.RIGHT ? self.rightArm : self.leftArm,
            player,
            partialTicks
        );
    }
}
