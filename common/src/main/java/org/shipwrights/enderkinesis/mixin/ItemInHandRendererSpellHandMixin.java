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

/** A Mystic Scroll in first person: the open hand, moving with what you're doing, holds the spell
 *  ({@link SpellHand}) instead of the scroll. And an item being imbued from the off hand moves to
 *  meet it. Vanilla's own rules still hold: nothing while looking through a spyglass, and no arm
 *  for an invisible player. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererSpellHandMixin {

    @Shadow
    private void renderPlayerArm(PoseStack pose, MultiBufferSource buffers, int light, float equipProgress, float swingProgress, HumanoidArm arm) {}

    /** Whether the item this call draws is held still for an imbue (render thread only). */
    @Unique
    private boolean enderkinesis$itemStill;

    /** Whether the arm being drawn for the spell is left out, only its pose kept (render thread only). */
    @Unique
    private boolean enderkinesis$armHidden;

    @Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$spellHand(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swingProgress,
                                       ItemStack stack, float equipProgress, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        // Vanilla draws nothing in either hand while looking through a spyglass.
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

    /** Vanilla lowers and raises the hand on every click that uses an item. A scroll's hand has
     *  its own motion for each use, so its height is left alone. */
    @Inject(method = "itemUsed", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$noUseDipForScroll(InteractionHand hand, CallbackInfo ci) {
        LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null && SpellHand.keepsHeight(player, hand)) ci.cancel();
    }

    /** An item being imbued: moved to meet the off hand. */
    @Inject(method = "renderArmWithItem", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0, shift = At.Shift.AFTER))
    private void enderkinesis$moveItem(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swingProgress,
                                       ItemStack stack, float equipProgress, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (enderkinesis$itemStill) SpellHand.moveItem(pose, player, hand, partialTick);
    }

    /** ...and neither swung by the click nor dropped to re-equip as the imbue changes it. */
    @ModifyVariable(method = "renderArmWithItem", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0), argsOnly = true, ordinal = 2)
    private float enderkinesis$stillSwing(float swingProgress) {
        return enderkinesis$itemStill ? 0f : swingProgress;
    }

    @ModifyVariable(method = "renderArmWithItem", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", ordinal = 0), argsOnly = true, ordinal = 3)
    private float enderkinesis$stillEquip(float equipProgress) {
        return enderkinesis$itemStill ? 0f : equipProgress;
    }

    /** The arm's pose as its model is drawn, so the spell sits ahead of the fist; the arm itself
     *  left out when it's hidden. */
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
