package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.item.SselithBookItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuSselithBookMixin {

    // method_17410 is the clickMenuButton lambda that swaps a book for an enchanted book; Forge SRG is m_39475_.
    @WrapOperation(
        method = {"getEnchantmentList", "method_17410"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;is(Lnet/minecraft/world/item/Item;)Z"
        )
    )
    private boolean enderkinesis$sselithBookIsBook(ItemStack stack, Item item, Operation<Boolean> original) {
        return original.call(stack, item) || SselithBookItem.countsAsBook(stack, item);
    }
}
