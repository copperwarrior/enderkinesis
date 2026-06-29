package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.registry.EKItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Wey'ye fruit is uninterruptible. All interruption paths end in
// LivingEntity.stopUsingItem (which clears the using flag, useItem,
// useItemRemaining), so the cleanest guarantee is to cancel stopUsingItem
// at HEAD while a Wey'ye bite is mid-progress. Paths covered:
//
//   - releaseUsingItem chain (right-click letup → MultiPlayerGameMode →
//     RELEASE_USE_ITEM packet → LivingEntity.releaseUsingItem →
//     stopUsingItem)
//   - handleSetCarriedItem (server slot swap)
//   - handlePlayerAction(SWAP_OFFHAND) (server F-key)
//   - Player.drop (Q key)
//   - updatingUsingItem mismatch (held stack stops matching useItem)
//   - completeUsingItem mismatch path (releaseUsingItem at completion
//     time, ticks already 0 → guard lets it through)
//
// Guard: useItemRemainingTicks > 0. The normal completion path
// (updatingUsingItem decrements to 0 → completeUsingItem →
// finishUsingItem → stopUsingItem) hits stopUsingItem with ticks already
// at 0, so it falls through and state clears cleanly.
//
// Snap-back in updatingUsingItem stays — it keeps the held slot pointing
// at the Wey'ye so the matched branch runs each tick and ticks
// decrement to completion. Without it, the mismatch branch fires every
// tick and ticks would never reach 0.
//
// LocalPlayer.stopUsingItem has its own override that flips a separate
// `startedUsingItem` field — covered by [[LocalPlayerWeyyeFruitUninterruptibleMixin]].
@Mixin(LivingEntity.class)
public abstract class LivingEntityWeyyeFruitUninterruptibleMixin {

    @Inject(method = "updatingUsingItem", at = @At("HEAD"))
    private void enderkinesis$snapBackToWeyye(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.isUsingItem()) return;
        ItemStack useStack = self.getUseItem();
        if (!useStack.is(EKItems.weyyeFruit())) return;
        if (!(self instanceof Player player)) return;
        InteractionHand hand = self.getUsedItemHand();
        if (hand != InteractionHand.MAIN_HAND) return;
        ItemStack heldStack = self.getItemInHand(hand);
        if (heldStack.is(EKItems.weyyeFruit())) return;
        Inventory inv = player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inv.getItem(slot).is(EKItems.weyyeFruit())) {
                inv.selected = slot;
                return;
            }
        }
    }

    @Inject(method = "stopUsingItem", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$keepEatingWeyye(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.isUsingItem()) return;
        if (!(self instanceof Player)) return;
        ItemStack useStack = self.getUseItem();
        if (!useStack.is(EKItems.weyyeFruit())) return;
        if (self.getUseItemRemainingTicks() <= 0) return;
        ci.cancel();
    }
}
