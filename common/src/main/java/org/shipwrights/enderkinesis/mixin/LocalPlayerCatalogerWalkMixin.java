package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.shipwrights.enderkinesis.client.SselithMenuWalk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Injected after {@code Input.tick} (which zeros impulses while a screen is open) so we can
 *  overwrite them with synthesised walk input. {@code aiStep} then copies to xxa/zza. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerCatalogerWalkMixin {

    @Inject(
        method = "aiStep",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/Input;tick(ZF)V",
            shift = At.Shift.AFTER
        )
    )
    private void enderkinesis$sselithMenuWalk(CallbackInfo ci) {
        SselithMenuWalk.INSTANCE.onAiStep((LocalPlayer) (Object) this);
        org.shipwrights.enderkinesis.client.PlayerTomeSummonClient.INSTANCE
            .onAiStep((LocalPlayer) (Object) this);
    }
}
