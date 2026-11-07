package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.shipwrights.enderkinesis.ritual.Selves;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemEntitySelvesMixin {

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$selvesNoPickup(Player player, CallbackInfo ci) {
        if (Selves.handless(player)) ci.cancel();
    }
}
