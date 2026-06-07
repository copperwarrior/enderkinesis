package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.world.item.ItemStack;
import org.shipwrights.enderkinesis.item.TomeItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes any {@link TomeItem} readable as a book when it rests on a lectern.
 *
 * The lectern keeps the genuine tome stack (preserving any per-stack state), but vanilla's book
 * screen only renders {@code WRITTEN_BOOK}/{@code WRITABLE_BOOK}. {@code BookAccess.fromItem} is
 * the single chokepoint every book view (the lectern's included) funnels through, so when it's
 * handed a tome we substitute the transient written-book stack the tome itself supplies via
 * {@link TomeItem#writtenBook(ItemStack)} and let vanilla render and paginate it. The recursive
 * {@code fromItem} call is given a real written book, so it doesn't re-enter this branch.
 *
 * {@code BookAccess} is an interface, so this mixin must itself be an interface and the injector a
 * {@code private static} method (Java 17 interface members).
 */
@Mixin(BookViewScreen.BookAccess.class)
public interface TomeLecternBookMixin {

    @Inject(method = "fromItem", at = @At("HEAD"), cancellable = true)
    private static void enderkinesis$tomeAsBook(
        ItemStack stack, CallbackInfoReturnable<BookViewScreen.BookAccess> cir
    ) {
        if (stack.getItem() instanceof TomeItem tome) {
            cir.setReturnValue(BookViewScreen.BookAccess.fromItem(tome.writtenBook(stack)));
        }
    }
}
