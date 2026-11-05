package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import org.shipwrights.enderkinesis.item.BlessedRazorItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** WEAPON only accepts SwordItem, and the razor must not get sword durability or mining speed. */
@Mixin(Enchantment.class)
public abstract class EnchantmentBlessedRazorMixin {

    @Inject(method = "canEnchant", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$razorTakesSwordEnchantments(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack.getItem() instanceof BlessedRazorItem
            && BlessedRazorItem.acceptsSwordEnchantment((Enchantment) (Object) this)) {
            cir.setReturnValue(true);
        }
    }
}
