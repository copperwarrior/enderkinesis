package org.shipwrights.enderkinesis.item

import net.minecraft.ChatFormatting
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.client.OldPaperClient
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.sselith.BookPaginator

/** Reads written-book NBT (`title`, `author`, `pages`). The pages must have serif page breaks. */
class SselithBookItem(properties: Properties) : TomeItem(properties) {

    override fun isFoil(stack: ItemStack): Boolean = false

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (level.isClientSide) OldPaperClient.openSselithBook(writtenBook(stack))
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    override fun writtenBook(stack: ItemStack): ItemStack {
        val tag = stack.tag ?: return TomeLore.blank(TITLE)
        val pages = tag.getList(PAGES, Tag.TAG_STRING.toInt())
        if (pages.isEmpty()) return TomeLore.blank(TITLE)
        val baked = (0 until minOf(pages.size, BookPaginator.MAX_PAGES)).map { i ->
            Component.empty().withStyle(TomeLore.SERIF_STYLE).append(TomeLore.pageComponent(pages.getString(i)))
        }
        return TomeLore.bake(
            tag.getString(TITLE_KEY).ifEmpty { TITLE },
            tag.getString(AUTHOR).ifEmpty { "Unknown" },
            baked,
            serifPages = true,
        )
    }

    override fun pageCount(stack: ItemStack): Int =
        (stack.tag?.getList(PAGES, Tag.TAG_STRING.toInt())?.size ?: 0).coerceIn(1, BookPaginator.MAX_PAGES)

    override fun getName(stack: ItemStack): Component {
        val title = stack.tag?.getString(TITLE_KEY).orEmpty()
        return if (title.isEmpty()) super.getName(stack) else Component.literal(title)
    }

    override fun appendHoverText(stack: ItemStack, level: Level?, tooltip: MutableList<Component>, flag: TooltipFlag) {
        val author = stack.tag?.getString(AUTHOR).orEmpty()
        if (author.isNotEmpty()) {
            tooltip.add(Component.translatable("book.byAuthor", author).withStyle(ChatFormatting.GRAY))
        }
    }

    companion object {
        const val TITLE_KEY = "title"
        const val AUTHOR = "author"
        const val PAGES = "pages"
        private const val TITLE = "Sselith Book"

        /** [pages] are plain text with serif page breaks, as [BookPaginator.paginate] gives. */
        fun of(title: String, author: String, pages: List<String>): ItemStack {
            val stack = ItemStack(EKItems.SSELITH_BOOK.get())
            val tag = stack.orCreateTag
            tag.putString(TITLE_KEY, title)
            tag.putString(AUTHOR, author)
            val list = ListTag()
            for (page in pages) list.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal(page))))
            tag.put(PAGES, list)
            return stack
        }
    }
}
