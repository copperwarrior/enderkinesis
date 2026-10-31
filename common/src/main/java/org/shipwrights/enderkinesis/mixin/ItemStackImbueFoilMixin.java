package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.spell.Imbues;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
public abstract class ItemStackImbueFoilMixin {

    @Inject(method = "hasFoil", at = @At("RETURN"), cancellable = true)
    private void enderkinesis$imbued(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) return;
        ItemStack self = (ItemStack) (Object) this;
        if (self.hasTag() && self.getTag().contains(Imbues.NBT)) cir.setReturnValue(true);
    }
}
