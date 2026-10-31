package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.client.SpellHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererSpellHandMixin {

    @Shadow
    private void renderPlayerArm(PoseStack pose, MultiBufferSource buffers, int light, float equipProgress, float swingProgress, HumanoidArm arm) {}

    /** Render thread only. */
    @Unique
    private boolean enderkinesis$itemStill;

    /** Render thread only. */
    @Unique
    private boolean enderkinesis$armHidden;

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$spellHand(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swingProgress,
                                       ItemStack stack, float equipProgress, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        // Vanilla draws no hand while the player looks through a spyglass.
        if (player.isScoping()) return;
        enderkinesis$itemStill = SpellHand.holdsItemStill(player, hand, partialTick);
        if (!SpellHand.takesOver(player, hand, stack, partialTick)) return;
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        pose.pushPose();
        SpellHand.moveArm(pose, arm);
        enderkinesis$armHidden = SpellHand.hidesArm(player);
        renderPlayerArm(pose, buffers, light, SpellHand.equipProgress(equipProgress), SpellHand.swingProgress(swingProgress), arm);
        enderkinesis$armHidden = false;
        pose.popPose();
        SpellHand.render(player, arm, buffers, light, partialTick);
        ci.cancel();
    }

    /** A scroll hand has its own motion for each use, so stop the vanilla dip on item use. */
    @Inject(method = "itemUsed", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$noUseDipForScroll(InteractionHand hand, CallbackInfo ci) {
        LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null && SpellHand.keepsHeight(player, hand)) ci.cancel();
    }

    @Inject(method = "renderArmWithItem", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0, shift = At.Shift.AFTER))
    private void enderkinesis$moveItem(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swingProgress,
                                       ItemStack stack, float equipProgress, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (enderkinesis$itemStill) SpellHand.moveItem(pose, player, hand, partialTick);
    }

    /** Do not swing or re-equip the item while the imbue changes it. */
    @ModifyVariable(method = "renderArmWithItem", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0), argsOnly = true, ordinal = 2)
    private float enderkinesis$stillSwing(float swingProgress) {
        return enderkinesis$itemStill ? 0f : swingProgress;
    }

    @ModifyVariable(method = "renderArmWithItem", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0), argsOnly = true, ordinal = 3)
    private float enderkinesis$stillEquip(float equipProgress) {
        return enderkinesis$itemStill ? 0f : equipProgress;
    }

    /** Capture the arm pose, so the spell sits ahead of the fist. */
    @WrapOperation(method = "renderPlayerArm", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/player/PlayerRenderer;renderRightHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;)V"))
    private void enderkinesis$rightArm(PlayerRenderer renderer, PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, Operation<Void> original) {
        SpellHand.capture(pose);
        if (!enderkinesis$armHidden) original.call(renderer, pose, buffers, light, player);
    }

    @WrapOperation(method = "renderPlayerArm", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/player/PlayerRenderer;renderLeftHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;)V"))
    private void enderkinesis$leftArm(PlayerRenderer renderer, PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, Operation<Void> original) {
        SpellHand.capture(pose);
        if (!enderkinesis$armHidden) original.call(renderer, pose, buffers, light, player);
    }
}
