package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.client.BookSkin;
import org.shipwrights.enderkinesis.client.BookSkinHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LecternScreen.class)
public abstract class LecternScreenSselithSkinMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void enderkinesis$skinSselithLectern(CallbackInfo ci) {
        ((BookSkinHolder) (Object) this).enderkinesisSetSkin(BookSkin.forLectern(ItemStack.EMPTY));
    }

    // The slot sync gives the book after the constructor, so the font decision comes here.
    @Inject(method = "bookChanged()V", at = @At("TAIL"))
    private void enderkinesis$skinForBook(CallbackInfo ci) {
        ItemStack book = ((LecternScreen) (Object) this).getMenu().getBook();
        ((BookSkinHolder) (Object) this).enderkinesisSetSkin(BookSkin.forLectern(book));
    }
}
