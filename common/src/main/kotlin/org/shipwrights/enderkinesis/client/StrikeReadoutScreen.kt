package org.shipwrights.enderkinesis.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.network.chat.Component
import org.shipwrights.enderkinesis.item.TomeLore
import org.shipwrights.enderkinesis.mixin.BookViewScreenPageAccessor

/** The sketch page is an inline sketch tag; this screen adds the block or entity name over the properties. */
class StrikeReadoutScreen(
    access: BookAccess,
    private val subject: Component,
    private val titlePage: Int,
) : BookViewScreen(access) {

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(graphics, mouseX, mouseY, partialTick)
        if ((this as BookViewScreenPageAccessor).`enderkinesis$getCurrentPage`() == titlePage) {
            drawTitle(graphics, (width - BOOK_SIZE) / 2 + BookSkin.STRIKE_READOUT.pageCentreX)
        }
    }

    private fun drawTitle(graphics: GuiGraphics, centre: Int) {
        val name = Component.empty().withStyle(TomeLore.SLOPPY_STYLE).append(subject)
        val nameWidth = font.width(name)
        val scale = minOf(1f, TEXT_WIDTH.toFloat() / nameWidth)
        graphics.pose().pushPose()
        graphics.pose().translate(centre.toFloat(), (BOOK_TOP + TITLE_Y).toFloat(), 0f)
        graphics.pose().scale(scale, scale, 1f)
        graphics.drawString(font, name, -nameWidth / 2, 0, INK, false)
        graphics.pose().popPose()
    }

    private companion object {
        // Vanilla BookViewScreen layout.
        const val BOOK_SIZE = 192
        const val BOOK_TOP = 2
        const val TEXT_WIDTH = 114
        const val INK = 0x000000

        // The row of the page number, which this skin hides.
        const val TITLE_Y = 16
    }
}
