package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.shipwrights.enderkinesis.client.SselithMenuWalk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the Sselith-Madness menu-walk (see {@link SselithMenuWalk}).
 *
 * <p>Injected right after {@code Input.tick} inside {@code aiStep}: that call has
 * just (re)zeroed the movement impulses because a screen is open, so this is the
 * moment to overwrite them with synthesised "walk" input. {@code aiStep} then
 * copies the impulses into the player's {@code xxa}/{@code zza} and {@code travel}
 * applies the motion. The controller no-ops unless the player has Sselith Madness
 * level ≥ 4 and a control-taking screen is open, so normal play is untouched.
 */
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
    }
}
