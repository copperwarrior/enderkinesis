package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.LecternScreen;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.item.TomeItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets a {@link TomeItem} on a lectern read as a book: the lectern keeps the real tome stack, and
 * the screen gets the written book from {@link TomeItem#writtenBook(ItemStack)}. Forge's Mixin
 * 0.8.5 cannot inject into the {@code BookAccess} interface, so this wraps the screen's call.
 */
@Mixin(LecternScreen.class)
public abstract class TomeLecternBookMixin {

    @WrapOperation(
        method = "bookChanged",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/inventory/BookViewScreen$BookAccess;fromItem(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/client/gui/screens/inventory/BookViewScreen$BookAccess;"
        )
    )
    private BookViewScreen.BookAccess enderkinesis$tomeAsBook(
        ItemStack stack, Operation<BookViewScreen.BookAccess> original
    ) {
        return original.call(stack.getItem() instanceof TomeItem tome ? tome.writtenBook(stack) : stack);
    }
}
