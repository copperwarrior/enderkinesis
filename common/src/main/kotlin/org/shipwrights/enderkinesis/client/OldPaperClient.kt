package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.world.item.ItemStack

// Separate object so a dedicated server never loads client classes from OldPaperItem.
object OldPaperClient {
    fun open(book: ItemStack, skin: BookSkin = BookSkin.OLD_PAPER) {
        val screen = BookViewScreen(BookViewScreen.BookAccess.fromItem(book))
        (screen as BookSkinHolder).enderkinesisSetSkin(skin)
        Minecraft.getInstance().setScreen(screen)
    }

    fun openSselithBook(book: ItemStack) = open(book, BookSkin.SSELITH_BOOK)
}
