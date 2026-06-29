package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.registry.EKItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Companion to [[LivingEntityWeyyeFruitUninterruptibleMixin]].
//
// LocalPlayer overrides stopUsingItem to *additionally* set a private
// `startedUsingItem` field to false — and `LocalPlayer.isUsingItem()`
// reads that field, not the synced flag. Cancelling stopUsingItem only
// at the LivingEntity level wouldn't stop the override from running its
// `startedUsingItem = false` write after the (cancelled) super call,
// which would then make the client-side use flatline even with useItem
// and useItemRemaining still set.
//
// Cancel the whole LocalPlayer.stopUsingItem at HEAD so the override
// doesn't run at all — same guard, same exception for completion (ticks
// = 0).
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerWeyyeFruitUninterruptibleMixin {

    @Inject(method = "stopUsingItem", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$keepEatingWeyye(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        if (!self.isUsingItem()) return;
        ItemStack useStack = self.getUseItem();
        if (!useStack.is(EKItems.weyyeFruit())) return;
        if (self.getUseItemRemainingTicks() <= 0) return;
        ci.cancel();
    }
}
