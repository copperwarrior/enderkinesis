package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.shipwrights.enderkinesis.item.RecitalHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the local player's arm-swing while holding the Wylland Tome — it's a grab
 * tool, not a melee weapon.
 *
 * <p>Cancelling at {@code LocalPlayer.swing} (rather than at the click edge in
 * {@link MinecraftWyllandTomeSwingMixin} alone) catches the held-attack-key path:
 * {@code continueAttack → MultiPlayerGameMode.startDestroyBlock} does NOT consult
 * {@code Item.canAttackBlock} on its first tick, so the swing fires anyway. Cancelling
 * at {@code swing} also catches server-driven {@code ClientboundAnimatePacket}s in one
 * place. {@link LocalPlayer}-only so other players holding tomes still animate.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerWyllandTomeNoSwingMixin {

    @Inject(
        method = "swing(Lnet/minecraft/world/InteractionHand;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$skipSwingWhileHoldingTome(
        InteractionHand hand, CallbackInfo ci
    ) {
        Object self = this;
        if (Minecraft.getInstance().player != self) return;
        LocalPlayer p = (LocalPlayer) self;
        // Recognise both the bare tome AND the Staff of Recital with the
        // tome active — the staff proxies the same left-click gesture.
        if (RecitalHelper.isHoldingWyllandTome(p)) {
            ci.cancel();
        }
    }
}
