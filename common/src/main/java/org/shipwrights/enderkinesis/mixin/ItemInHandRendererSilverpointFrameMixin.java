package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.client.SilverpointHands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the Silverpoint viewfinder is held, both first-person hands are bare and frame the view. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererSilverpointFrameMixin {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$silverpointFrame(
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
        if (!SilverpointHands.INSTANCE.framing(player)) return;
        ci.cancel();
        if (player.isInvisible()) return;
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        SilverpointHands.render(player, arm, partialTicks, poseStack, buffer, combinedLight);
    }
}
