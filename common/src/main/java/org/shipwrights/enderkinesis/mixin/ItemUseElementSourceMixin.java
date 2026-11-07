package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.context.UseOnContext;
import org.shipwrights.enderkinesis.spell.Element;
import org.shipwrights.enderkinesis.spell.ElementalEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({BoneMealItem.class, HoeItem.class})
public abstract class ItemUseElementSourceMixin {

    @Inject(method = "useOn", at = @At("RETURN"))
    private void enderkinesis$genesisDeed(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (context.getPlayer() != null && cir.getReturnValue().consumesAction()) {
            ElementalEnvironment.deed(context.getPlayer(), Element.GENESIS);
        }
    }
}
