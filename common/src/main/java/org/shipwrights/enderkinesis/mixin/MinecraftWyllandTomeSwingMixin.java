package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.shipwrights.enderkinesis.item.RecitalHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses the vanilla left-click swing/hit animation while the player
 * holds a Wylland Tome. {@code canAttackBlock=false} stops block damage
 * and our {@code PlayerEvent.ATTACK_ENTITY} listener stops entity damage,
 * but the swing animation itself is still triggered by
 * {@link Minecraft#startAttack()} — left-click on the tome is the grab
 * gesture, the player shouldn't see their hand flail every time they
 * press it.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftWyllandTomeSwingMixin {

    @Shadow public LocalPlayer player;

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$skipTomeSwing(CallbackInfoReturnable<Boolean> cir) {
        if (player == null) return;
        // Accept either: holding the Wylland Tome directly, OR holding a
        // Staff of Recital whose active tome is the Wylland Tome — the staff
        // proxies the tome's left-click grab gesture too.
        if (!RecitalHelper.isHoldingWyllandTome(player)) return;
        // Returning false matches startAttack's "nothing happened" return,
        // skipping block break, entity attack, AND the swing animation.
        cir.setReturnValue(false);
    }
}
