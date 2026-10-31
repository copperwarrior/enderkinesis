package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.item.SselithBookItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Item.getMaxStackSize has no stack parameter on Fabric, so the per-stack limit goes here.
@Mixin(ItemStack.class)
public abstract class ItemStackSselithBookStackMixin {

    @ModifyReturnValue(method = "getMaxStackSize", at = @At("RETURN"))
    private int enderkinesis$writtenSselithBookStack(int original) {
        return SselithBookItem.maxStackSize((ItemStack) (Object) this, original);
    }
}
