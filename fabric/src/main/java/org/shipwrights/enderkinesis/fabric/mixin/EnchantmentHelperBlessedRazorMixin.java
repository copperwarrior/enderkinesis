package org.shipwrights.enderkinesis.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.shipwrights.enderkinesis.item.BlessedRazorItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Fabric only: Forge's table asks Item.canApplyAtEnchantingTable, which BlessedRazorItem answers. */
@Mixin(EnchantmentHelper.class)
public abstract class EnchantmentHelperBlessedRazorMixin {

    @WrapOperation(
        method = "getAvailableEnchantmentResults",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/enchantment/EnchantmentCategory;canEnchant(Lnet/minecraft/world/item/Item;)Z"
        )
    )
    private static boolean enderkinesis$razorTakesSwordEnchantments(
        EnchantmentCategory category,
        Item item,
        Operation<Boolean> original,
        @Local Enchantment enchantment
    ) {
        if (item instanceof BlessedRazorItem && BlessedRazorItem.acceptsSwordEnchantment(enchantment)) {
            return true;
        }
        return original.call(category, item);
    }
}
