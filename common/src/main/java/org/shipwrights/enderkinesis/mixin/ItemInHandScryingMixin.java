package org.shipwrights.enderkinesis.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.shipwrights.enderkinesis.client.ScryingClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppress the first-person hand + held item while a scrying view is active. The hand
 * would otherwise float in screen space at the remote camera's vantage — the player's
 * arm in the corner of the view they're "looking through" — which reads as an obvious
 * cheat-style overlay rather than a remote camera feed.
 *
 * <p>HEAD-inject {@code ItemInHandRenderer#renderHandsWithItems} and cancel it when
 * {@link ScryingClient#isActive()} reports a view. The method does all the per-hand
 * setup + draw inline, so cancelling at HEAD skips the whole pass cleanly. No effect
 * for non-scrying players (the active gate is one volatile read).
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandScryingMixin {

    @Inject(
        method = "renderHandsWithItems",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$hideHandWhileScrying(
        float partialTicks, PoseStack poseStack, MultiBufferSource.BufferSource buffer,
        LocalPlayer player, int packedLight, CallbackInfo ci
    ) {
        if (ScryingClient.isActive()) {
            ci.cancel();
        }
    }
}
