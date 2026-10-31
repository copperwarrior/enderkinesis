package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.shipwrights.enderkinesis.item.SselithBookItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EnchantmentHelper.class)
public abstract class EnchantmentHelperSselithBookMixin {

    @WrapOperation(
        method = {"getAvailableEnchantmentResults", "enchantItem"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;is(Lnet/minecraft/world/item/Item;)Z"
        )
    )
    private static boolean enderkinesis$sselithBookIsBook(ItemStack stack, Item item, Operation<Boolean> original) {
        return original.call(stack, item) || SselithBookItem.countsAsBook(stack, item);
    }
}
