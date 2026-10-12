package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BottleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.shipwrights.enderkinesis.item.IchaericInk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BottleItem.class)
public abstract class BottleItemIchaericInkMixin {

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$fillWithIchaericInk(
        Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir
    ) {
        InteractionResultHolder<ItemStack> result = IchaericInk.fillBottle(level, player, hand);
        if (result != null) cir.setReturnValue(result);
    }
}
