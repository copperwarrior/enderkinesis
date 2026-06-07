package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.WrittenBookItem;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import org.shipwrights.enderkinesis.item.TomeItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets any {@link TomeItem} live on a lectern.
 *
 * Vanilla {@code LecternBlockEntity.hasBook()} is hardcoded to {@code WRITABLE_BOOK}/
 * {@code WRITTEN_BOOK}. The lectern's container {@code stillValid} is
 * {@code ... && hasBook()}, so a tome on the lectern makes the open menu invalid and the server
 * force-closes it the next tick — the read screen flashes for a single frame. Treating any tome
 * as a book here keeps the menu valid. It only gates a boolean; the stored stack is never
 * transformed, so the tome's per-stack state is untouched. Reading is rendered by
 * {@link TomeLecternBookMixin}.
 *
 * <p>Separately, {@code LecternBlockEntity} derives {@code pageCount} from
 * {@code WrittenBookItem.getPageCount}, which is 0 for a tome (no {@code pages} NBT on the real
 * stack). With a 0 page count {@code setPage} clamps every turn back to 0, so the server-driven
 * page buttons do nothing and the redstone comparator can't scale. Redirecting that lookup to
 * {@link TomeItem#pageCount(ItemStack)} makes page-turning and the comparator behave exactly like
 * a vanilla book lectern.
 */
@Mixin(LecternBlockEntity.class)
public class LecternBlockEntityHasBookMixin {

    @Inject(method = "hasBook", at = @At("RETURN"), cancellable = true)
    private void enderkinesis$tomeCountsAsBook(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            ItemStack book = ((LecternBlockEntity) (Object) this).getBook();
            if (book.getItem() instanceof TomeItem) {
                cir.setReturnValue(true);
            }
        }
    }

    @Redirect(
        method = {
            "setBook(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;)V",
            "load(Lnet/minecraft/nbt/CompoundTag;)V"
        },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/WrittenBookItem;getPageCount(Lnet/minecraft/world/item/ItemStack;)I"
        )
    )
    private int enderkinesis$tomePageCount(ItemStack book) {
        if (book.getItem() instanceof TomeItem tome) {
            return tome.pageCount(book);
        }
        return WrittenBookItem.getPageCount(book);
    }
}
