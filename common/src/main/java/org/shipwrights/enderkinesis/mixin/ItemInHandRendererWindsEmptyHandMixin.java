package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.client.IncantationOfWindsClient;
import org.shipwrights.enderkinesis.registry.EKItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person presentation of the Incantation of Winds:
 *
 * <ul>
 *   <li>Bare arm — the item render is cancelled outright and the vanilla
 *       bare-arm renderer is invoked directly (also for the OFF hand,
 *       which vanilla's own empty-stack path never draws).</li>
 *   <li>Cast pose — while the incantation is held down, the arm eases
 *       forward and oscillates gently (bow-full-draw feel).</li>
 *   <li>Hand-space dust — after both hands have drawn (TAIL of
 *       renderHandsWithItems), the mote system renders into the same
 *       pass so the dust depth-tests against the arm geometry.</li>
 * </ul>
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererWindsEmptyHandMixin {

    @Shadow
    protected abstract void renderPlayerArm(
        PoseStack poseStack, MultiBufferSource buffer, int combinedLight,
        float equippedProgress, float swingProgress, HumanoidArm arm
    );

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$windsBareArm(
        AbstractClientPlayer player,
        float partialTicks,
        float pitch,
        InteractionHand hand,
        float swingProgress,
        ItemStack stack,
        float equippedProgress,
        PoseStack poseStack,
        MultiBufferSource buffer,
        int combinedLight,
        CallbackInfo ci
    ) {
        if (stack.isEmpty()
            || stack.getItem() != EKItems.INSTANCE.getINCANTATION_OF_WINDS().get()) {
            return;
        }
        ci.cancel();
        if (player.isScoping() || player.isInvisible()) return;

        poseStack.pushPose();
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND
            ? player.getMainArm()
            : player.getMainArm().getOpposite();
        if (IncantationOfWindsClient.isCastingWinds(player, hand)) {
            // Tilt-then-translate — same composition order as the mote
            // anchor in IncantationOfWindsClient so the dust stays glued.
            poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(
                IncantationOfWindsClient.castTiltDegrees(player, partialTicks)));
            poseStack.translate(
                0.0,
                IncantationOfWindsClient.castOscillation(player, partialTicks),
                -IncantationOfWindsClient.castForwardOffset(player, partialTicks)
            );
        }
        this.renderPlayerArm(poseStack, buffer, combinedLight, equippedProgress, swingProgress, arm);
        poseStack.popPose();
    }

    /** Vanilla zeroes the hand height on every successful right-click use
     *  ({@code Minecraft.startUseItem} → {@code itemUsed}), producing the
     *  full lower-and-raise re-equip dip. The incantation's cast should
     *  hold steady — skip the reset when that hand holds it. */
    @Inject(method = "itemUsed", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$noUseDipForWinds(InteractionHand hand, CallbackInfo ci) {
        net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null
            && player.getItemInHand(hand).getItem()
                == EKItems.INSTANCE.getINCANTATION_OF_WINDS().get()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderHandsWithItems", at = @At("TAIL"))
    private void enderkinesis$windsHandMotes(
        float partialTicks,
        PoseStack poseStack,
        MultiBufferSource.BufferSource buffer,
        LocalPlayer player,
        int combinedLight,
        CallbackInfo ci
    ) {
        IncantationOfWindsClient.renderFirstPersonMotes(partialTicks, poseStack, buffer, player, combinedLight);
    }
}
