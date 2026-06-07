package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.shipwrights.enderkinesis.client.WyllandTomeClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Locks the local player's view in place when they're holding the
 * Wylland Tome mod key with an active grab. This is the GMod
 * E-while-holding behaviour: pressing the modifier captures mouse
 * motion as explicit rotation of the held object instead of camera
 * motion, so the player can reorient a ship without their view
 * scrolling around with it.
 *
 * {@code turn(double, double)} lives on {@link Entity} (LocalPlayer
 * inherits it), so the mixin targets Entity — guarded by an
 * {@code instanceof LocalPlayer} + identity check so we only ever
 * consume the call for the local client player, never for any other
 * entity vanilla might call {@code turn} on.
 */
@Mixin(Entity.class)
public abstract class LocalPlayerWyllandTomeTurnMixin {

    @Inject(
        method = "turn(DD)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$lockViewWhileRotating(
        double yawDelta, double pitchDelta, CallbackInfo ci
    ) {
        Object self = this;
        if (!(self instanceof LocalPlayer)) return;
        if (Minecraft.getInstance().player != self) return;
        if (WyllandTomeClient.INSTANCE.captureTurnIfRotating(yawDelta, pitchDelta)) {
            ci.cancel();
        }
    }
}
