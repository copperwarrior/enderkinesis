package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.item.StrikeReadout

// Separate object so a dedicated server never loads client classes from OldPaperItem.
object OldPaperClient {
    fun open(book: ItemStack, skin: BookSkin = BookSkin.OLD_PAPER) {
        val screen = BookViewScreen(BookViewScreen.BookAccess.fromItem(book))
        (screen as BookSkinHolder).enderkinesisSetSkin(skin)
        Minecraft.getInstance().setScreen(screen)
    }

    fun openSselithBook(book: ItemStack) = open(book, BookSkin.SSELITH_BOOK)

    fun openStrikeReadout(book: ItemStack, readout: StrikeReadout) {
        SketchPictures.hold(readout)
        val screen = StrikeReadoutScreen(BookViewScreen.BookAccess.fromItem(book), readout.title, readout.propertiesPage)
        (screen as BookSkinHolder).enderkinesisSetSkin(BookSkin.STRIKE_READOUT)
        Minecraft.getInstance().setScreen(screen)
    }
}
