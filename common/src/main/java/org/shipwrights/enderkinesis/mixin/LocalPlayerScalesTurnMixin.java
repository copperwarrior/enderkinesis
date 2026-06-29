package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.shipwrights.enderkinesis.client.ScalesClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Locks the local player's view in place while a Staff-of-Scales drag is
 * active. The pitch delta is still forwarded to
 * {@link ScalesClient#consumePitchDelta} so the slider moves with the mouse —
 * we just drop the camera rotation. Same shape as
 * {@link LocalPlayerWyllandTomeTurnMixin}.
 */
@Mixin(Entity.class)
public abstract class LocalPlayerScalesTurnMixin {

    @Inject(method = "turn(DD)V", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$freezeViewForScalesDrag(
        double yawDelta, double pitchDelta, CallbackInfo ci
    ) {
        Object self = this;
        if (!(self instanceof LocalPlayer)) return;
        if (Minecraft.getInstance().player != self) return;
        if (!ScalesClient.isActive()) return;
        ScalesClient.consumePitchDelta(pitchDelta);
        ci.cancel();
    }
}
