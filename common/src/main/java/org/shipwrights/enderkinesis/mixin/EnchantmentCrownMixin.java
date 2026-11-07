package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import org.shipwrights.enderkinesis.item.CrownItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Enchantment.class)
public abstract class EnchantmentCrownMixin {

    @Inject(method = "canEnchant", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$crownTakesHelmetEnchantments(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack.getItem() instanceof CrownItem
            && CrownItem.acceptsHelmetEnchantment((Enchantment) (Object) this)) {
            cir.setReturnValue(true);
        }
    }
}
