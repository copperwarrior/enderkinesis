package org.shipwrights.enderkinesis.client

import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import org.shipwrights.enderkinesis.dimension.SselithParallelView
import org.shipwrights.enderkinesis.item.TomeLore
import org.shipwrights.enderkinesis.sselith.BookshelfPage

object BookshelfPageClient {

    private const val TITLE = "Untitled"
    private const val AUTHOR = "Unknown"

    // The page is a pure function of position and parallel, so the client builds it and the server sends no text.
    fun open(pos: BlockPos) = open(BookshelfPage.Location(pos.x, pos.y, pos.z, SselithParallelView.local))

    fun open(location: BookshelfPage.Location) {
        val text = BookshelfPage.text(location.x, location.y, location.z, location.parallel)
        openPage(text, BookSkin.SSELITH_BOOK)
    }

    fun openScroll(page: String) = openPage(page, BookSkin.SSELITH_SCROLL)

    private fun openPage(page: String, skin: BookSkin) {
        OldPaperClient.open(TomeLore.bake(TITLE, AUTHOR, listOf(Component.literal(page))), skin)
    }
}
