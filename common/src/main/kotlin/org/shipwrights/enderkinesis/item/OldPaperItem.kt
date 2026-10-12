package org.shipwrights.enderkinesis.item

import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.client.OldPaperClient

/** Reads the first [MAX_PAGES] pages of written-book NBT (`title`, `author`, `pages`). */
class OldPaperItem(properties: Properties) : TomeItem(properties) {

    override fun isFoil(stack: ItemStack): Boolean = false

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide) OldPaperClient.open(writtenBook(stack))
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    override fun writtenBook(stack: ItemStack): ItemStack {
        val tag = stack.tag ?: return TomeLore.blank(TITLE)
        val pages = tag.getList("pages", Tag.TAG_STRING.toInt())
        if (pages.isEmpty()) return TomeLore.blank(TITLE)
        val baked = (0 until minOf(pages.size, MAX_PAGES)).map { i ->
            val page = TomeLore.pageComponent(pages.getString(i))
            InkBleed.apply(Component.empty().withStyle(TomeLore.SLOPPY_STYLE).append(page))
        }
        return TomeLore.bake(
            tag.getString("title").ifEmpty { TITLE },
            tag.getString("author").ifEmpty { "Unknown" },
            baked,
        )
    }

    override fun pageCount(stack: ItemStack): Int =
        (stack.tag?.getList("pages", Tag.TAG_STRING.toInt())?.size ?: 0).coerceIn(1, MAX_PAGES)

    companion object {
        const val MAX_PAGES = 2
        private const val TITLE = "Old Paper"
    }
}
