package org.shipwrights.enderkinesis.item

import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.WrittenBookItem
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LecternBlock

/**
 * Shared base for the mod's "tome" items — books that can sit on a lectern and render as a written
 * book when opened. Three things travel together here:
 *  - **Lectern placement** via [useOn] (same flow `WrittenBookItem` uses, so the empty-lectern
 *    click works without each tome re-implementing it). Subclasses also need to appear in the
 *    `minecraft:lectern_books` item tag — vanilla's `LecternBlock.tryPlaceBook` consults it.
 *  - **A synthetic written-book stack** used purely for client-side rendering, returned by
 *    [writtenBook]. The lectern keeps the *real* tome stack on disk (preserving any per-stack
 *    state); `BookViewScreen.BookAccess.fromItem` is fed this throw-away copy so vanilla's book
 *    pipeline can paginate it.
 *  - **Page count** for the comparator and the lectern's page-turn clamp, taken from [writtenBook]
 *    by default but overridable for tomes whose page count is cheaper to read than baking the full
 *    book NBT each tick.
 *
 * Two generic mixins (`TomeLecternBookMixin`, `LecternBlockEntityHasBookMixin`) check
 * `instanceof TomeItem` and dispatch to these methods, so every subclass gets the lectern
 * integration for free.
 */
abstract class TomeItem(properties: Properties) : Item(properties) {

    override fun isFoil(stack: ItemStack): Boolean = true

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level
        val pos = context.clickedPos
        val state = level.getBlockState(pos)
        // Accept any LecternBlock subclass (vanilla + Sselith Lectern). The
        // HAS_BOOK / tryPlaceBook paths are inherited from LecternBlock so
        // the Sselith subclass works through the exact same call.
        if (state.block is LecternBlock && !state.getValue(LecternBlock.HAS_BOOK)) {
            return if (LecternBlock.tryPlaceBook(context.player, level, pos, state, context.itemInHand))
                InteractionResult.sidedSuccess(level.isClientSide)
            else InteractionResult.PASS
        }
        return InteractionResult.PASS
    }

    /**
     * The transient `WRITTEN_BOOK` stack a `BookViewScreen` renders when this tome is opened from
     * a lectern. The returned stack is throw-away — vanilla never sees it persisted, it just
     * paginates it; the real tome stack on the lectern is untouched.
     */
    abstract fun writtenBook(stack: ItemStack): ItemStack

    /** Page count for the comparator and the lectern's page-turn clamp. */
    open fun pageCount(stack: ItemStack): Int = WrittenBookItem.getPageCount(writtenBook(stack))
}
