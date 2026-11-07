package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.shipwrights.enderkinesis.client.SelvesClient;
import org.shipwrights.enderkinesis.ritual.Selves;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
public abstract class PlayerRendererSelvesMixin {

    @Inject(
        method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD"), cancellable = true
    )
    private void enderkinesis$selvesForm(
        AbstractClientPlayer player, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo ci
    ) {
        if (SelvesClient.render(player, yaw, partialTick, pose, buffers, light)) ci.cancel();
    }

    @Inject(method = {"renderRightHand", "renderLeftHand"}, at = @At("HEAD"), cancellable = true)
    private void enderkinesis$selvesNoHand(
        PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, CallbackInfo ci
    ) {
        if (Selves.handless(player)) ci.cancel();
    }
}
