package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.shipwrights.enderkinesis.client.CommandWheelClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shift+scroll while holding the Staff of Command cycles its selected
 * commandable family. Same shape as {@link MouseHandlerRecitalScrollMixin};
 * consuming cancels vanilla's hotbar-cycle.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerCommandScrollMixin {

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$captureCommandShiftScroll(
        long window, double dx, double dy, CallbackInfo ci
    ) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!player.isShiftKeyDown()) return;
        if (CommandWheelClient.tryConsumeScroll(dy)) {
            ci.cancel();
        }
    }
}
