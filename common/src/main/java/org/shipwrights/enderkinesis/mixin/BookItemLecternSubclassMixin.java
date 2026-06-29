package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.item.WritableBookItem;
import net.minecraft.world.item.WrittenBookItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets the Sselith Lectern (and any other LecternBlock subclass we add later)
 * accept books placed by right-clicking with a writable or written book in hand.
 *
 * Vanilla {@link WritableBookItem#useOn} and {@link WrittenBookItem#useOn}
 * gate the {@code LecternBlock.tryPlaceBook} call on
 * {@code state.is(Blocks.LECTERN)} — the exact vanilla block. Subclasses
 * inherit every method, but that identity check rejects them, so without
 * this redirect right-clicking a book on a Sselith Lectern just silently
 * does nothing. We broaden the check to accept any LecternBlock instance.
 *
 * Two {@code @Mixin} targets so a single redirect covers both item classes;
 * the {@code @At INVOKE} pattern matches only the lectern check (the only
 * {@code BlockState#is(Block)} call in either {@code useOn}).
 */
@Mixin({ WritableBookItem.class, WrittenBookItem.class })
public class BookItemLecternSubclassMixin {

    @Redirect(
        method = "useOn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/world/level/block/Block;)Z"
        )
    )
    private boolean enderkinesis$acceptLecternSubclass(BlockState state, Block vanilla) {
        return state.is(vanilla) || state.getBlock() instanceof LecternBlock;
    }
}
