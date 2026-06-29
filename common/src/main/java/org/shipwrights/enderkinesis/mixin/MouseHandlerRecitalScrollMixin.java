package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.shipwrights.enderkinesis.client.RecitalClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercept scroll wheel input while the player is sneaking AND holding a
 * Staff of Recital, forward it to {@link RecitalClient#tryConsumeScroll}.
 * Returning consumed cancels vanilla's hotbar-cycle so the player doesn't
 * also shuffle their hotbar while changing the active tome.
 *
 * <p>Targets {@code MouseHandler#onScroll(long window, double dx, double dy)} —
 * the GLFW scroll callback. Sneaking is the gate so non-sneaking scroll still
 * swaps the hotbar normally even when the staff is held.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerRecitalScrollMixin {

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$captureRecitalShiftScroll(
        long window, double dx, double dy, CallbackInfo ci
    ) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!player.isShiftKeyDown()) return;
        if (RecitalClient.tryConsumeScroll(dy)) {
            ci.cancel();
        }
    }
}
