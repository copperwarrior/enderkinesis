package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.logging.LogUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.inventory.PageButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.block.SselithLecternBlock
import org.shipwrights.enderkinesis.item.TomeLore
import java.io.IOException
import java.util.Optional

/** Implemented on `BookViewScreen` by `BookViewScreenSkinMixin`. */
interface BookSkinHolder {
    fun enderkinesisSkin(): BookSkin?
    fun enderkinesisSetSkin(skin: BookSkin?)
}

// Texture x of the fray on each of the 14 text lines of `old_paper_ui.png`: the first clean column after
// the left fray, and the first column of the right fray. Keep the two tables equal to the texture.
private val OLD_PAPER_FRAY_LEFT = intArrayOf(31, 33, 40, 25, 25, 23, 25, 23, 37, 24, 32, 35, 26, 39)
private val OLD_PAPER_FRAY_RIGHT = intArrayOf(157, 148, 157, 161, 156, 153, 154, 147, 159, 157, 146, 145, 148, 158)

/**
 * A replacement for vanilla's `book.png`. Each texture keeps the vanilla page arrows at the vanilla UVs.
 * [dx] and [dy] move the page image, [contentDy] moves the text and page arrows, and [buttonDy] moves
 * the other buttons, all from the vanilla positions.
 */
enum class BookSkin(
    file: String,
    private val dx: Int,
    private val dy: Int = 0,
    val contentDy: Int = 0,
    val buttonDy: Int = 0,
    private val roller: Boolean = false,
    private val frayLeft: IntArray? = null,
    private val frayRight: IntArray? = null,
    private val maskText: Boolean = false,
    private val textStyle: Style? = null,
    val stainsPage: Boolean = false,
) {
    OLD_PAPER(
        "old_paper_ui", 2, frayLeft = OLD_PAPER_FRAY_LEFT, frayRight = OLD_PAPER_FRAY_RIGHT, stainsPage = true,
    ),
    SSELITH_BOOK("sselith_book_ui", 0, textStyle = TomeLore.SERIF_STYLE),

    // For a book with no serif page breaks: the serif font is wider, so its pages would overflow.
    SSELITH_LECTERN("sselith_book_ui", 0),

    // The roller is the upper half of the top rod, so the page and its contents move down to give it room.
    SSELITH_SCROLL(
        "sselith_scroll_ui", 2, dy = 32, contentDy = 30, buttonDy = 13, roller = true, maskText = true,
        textStyle = TomeLore.SERIF_STYLE,
    );

    val texture: ResourceLocation = EnderkinesisMod.id("textures/gui/$file.png")

    /** [left] and [top] are the vanilla book image origin. */
    fun blitBackground(graphics: GuiGraphics, left: Int, top: Int) {
        graphics.blit(texture, left + dx, top + dy, 0, 0, MAIN_SIZE, MAIN_SIZE)
        if (roller) {
            val x = left + dx + ROLLER_X
            graphics.blit(texture, x, top + dy - ROLLER_HEIGHT, ROLLER_U, ROLLER_V, ROLLER_WIDTH, ROLLER_HEIGHT)
        }
    }

    val masksText: Boolean get() = maskText

    /** [page] with the font of this skin as the base style. A run with its own font keeps that font. */
    fun styled(page: FormattedText): FormattedText {
        val base = textStyle ?: return page
        return object : FormattedText {
            override fun <T> visit(consumer: FormattedText.ContentConsumer<T>): Optional<T> = page.visit(consumer)

            override fun <T> visit(consumer: FormattedText.StyledContentConsumer<T>, style: Style): Optional<T> =
                page.visit(consumer, base.applyTo(style))
        }
    }

    // The longest run of clean page columns for each texture row: its first column and the column after it.
    // A transparent pixel and a dark edge or tear pixel are not clean.
    private val rowSpans: Array<IntArray>? by lazy { if (maskText) readRowSpans() else null }

    private fun readRowSpans(): Array<IntArray>? = try {
        Minecraft.getInstance().resourceManager.open(texture).use { NativeImage.read(it) }.use { image ->
            val first = IntArray(MAIN_SIZE)
            val end = IntArray(MAIN_SIZE)
            for (y in 0 until MAIN_SIZE) {
                var runStart = 0
                for (x in 0..MAIN_SIZE) {
                    if (x < MAIN_SIZE && isCleanPage(image.getPixelRGBA(x, y))) continue
                    if (x - runStart > end[y] - first[y]) {
                        first[y] = runStart
                        end[y] = x
                    }
                    runStart = x + 1
                }
            }
            arrayOf(first, end)
        }
    } catch (e: IOException) {
        LOG.warn("No text mask for {}", texture, e)
        null
    }

    // NativeImage pixels are ABGR.
    private fun isCleanPage(pixel: Int): Boolean {
        if (pixel ushr ALPHA_SHIFT == 0) return false
        val red = pixel and CHANNEL_MASK
        val green = pixel shr GREEN_SHIFT and CHANNEL_MASK
        val blue = pixel shr BLUE_SHIFT and CHANNEL_MASK
        return RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * blue >= CLEAN_LUMINANCE
    }

    /**
     * The end of the run of screen rows, from [row] to [limit], that have the same clean page columns.
     * [top] is the vanilla book image origin.
     */
    fun maskRunEnd(top: Int, row: Int, limit: Int): Int {
        val spans = rowSpans ?: return limit
        var end = row + 1
        while (end < limit && sameSpan(spans, row - top - dy, end - top - dy)) end++
        return end
    }

    private fun sameSpan(spans: Array<IntArray>, a: Int, b: Int): Boolean =
        spanAt(spans[0], a) == spanAt(spans[0], b) && spanAt(spans[1], a) == spanAt(spans[1], b)

    private fun spanAt(columns: IntArray, textureRow: Int): Int =
        if (textureRow in columns.indices) columns[textureRow] else 0

    /**
     * Clips to the clean page columns of screen rows [row] until [end], thus text does not show over a gap.
     * GuiGraphics scissor ignores the pose, so the rows are screen rows. Call `disableScissor` after the draw.
     */
    fun enableMask(graphics: GuiGraphics, left: Int, top: Int, row: Int, end: Int) {
        val spans = rowSpans
        if (spans == null) {
            graphics.enableScissor(0, row, graphics.guiWidth(), end)
            return
        }
        val x = left + dx
        val textureRow = row - top - dy
        graphics.enableScissor(x + spanAt(spans[0], textureRow), row, x + spanAt(spans[1], textureRow), end)
    }

    /**
     * The x of a text line that vanilla draws at [textX]. The line moves right, off the left fray. Then it
     * moves back left if it touches the right fray, but the left fray has priority.
     */
    fun lineLeft(textX: Int, textY: Int, lineWidth: Int): Int {
        val line = (textY - TEXT_Y) / LINE_HEIGHT
        if (frayLeft == null || frayRight == null || line !in frayLeft.indices) return textX
        val textureLeft = textX - TEXT_X + dx
        val min = textureLeft + frayLeft[line] + FRAY_PADDING
        val max = textureLeft + frayRight[line] - FRAY_PADDING - lineWidth
        return maxOf(min, minOf(maxOf(textX, min), max))
    }

    fun shiftWidgets(widgets: List<GuiEventListener>) {
        if (contentDy == 0 && buttonDy == 0) return
        for (widget in widgets) {
            if (widget is AbstractWidget) widget.y += if (widget is PageButton) contentDy else buttonDy
        }
    }

    companion object {
        private val LOG = LogUtils.getLogger()
        private const val MAIN_SIZE = 192

        // Vanilla BookViewScreen text origin and line pitch.
        const val TEXT_X = 36
        const val TEXT_Y = 32
        const val LINE_HEIGHT = 9
        private const val FRAY_PADDING = 2
        private const val ALPHA_SHIFT = 24
        private const val BLUE_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val CHANNEL_MASK = 0xFF

        // Luminance in tenths. The scroll page tones are 228 or more, and its edge and tear tones are 163 or less.
        private const val RED_WEIGHT = 3
        private const val GREEN_WEIGHT = 6
        private const val BLUE_WEIGHT = 1
        private const val CLEAN_LUMINANCE = 1800

        private const val ROLLER_U = 73
        private const val ROLLER_V = 222
        private const val ROLLER_WIDTH = 165
        private const val ROLLER_HEIGHT = 34
        private const val ROLLER_X = 9

        fun of(screen: Screen?): BookSkin? = (screen as? BookSkinHolder)?.enderkinesisSkin()

        @JvmStatic
        fun arrowTexture(vanilla: ResourceLocation): ResourceLocation =
            of(Minecraft.getInstance().screen)?.texture ?: vanilla

        // LecternMenu has no block position, so the crosshair target identifies the lectern.
        @JvmStatic
        fun forLectern(book: ItemStack): BookSkin? {
            val minecraft = Minecraft.getInstance()
            val hit = minecraft.hitResult as? BlockHitResult ?: return null
            val block = minecraft.level?.getBlockState(hit.blockPos)?.block
            if (block !is SselithLecternBlock) return null
            return if (TomeLore.hasSerifPages(book)) SSELITH_BOOK else SSELITH_LECTERN
        }
    }
}
