package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.InteractionHand;
import org.shipwrights.enderkinesis.registry.EKItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Relaxes the third-person arm pose to EMPTY while the Incantation of
 * Winds is held — without this the arm takes the bent item-holding pose
 * around an invisible item, which reads as "holding nothing weirdly"
 * instead of a genuinely empty hand.
 */
@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererWindsArmPoseMixin {

    @Inject(method = "getArmPose", at = @At("HEAD"), cancellable = true)
    private static void enderkinesis$emptyPoseForWinds(
        AbstractClientPlayer player,
        InteractionHand hand,
        CallbackInfoReturnable<HumanoidModel.ArmPose> cir
    ) {
        if (player.getItemInHand(hand).getItem()
            == EKItems.INSTANCE.getINCANTATION_OF_WINDS().get()) {
            cir.setReturnValue(HumanoidModel.ArmPose.EMPTY);
        }
    }
}
