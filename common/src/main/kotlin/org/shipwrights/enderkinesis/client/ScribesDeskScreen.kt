package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.font.TextFieldHelper
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import org.shipwrights.enderkinesis.block.ScribesDeskNetwork
import org.shipwrights.enderkinesis.item.TomeLore
import org.shipwrights.enderkinesis.sselith.BookPaginator
import org.shipwrights.enderkinesis.sselith.BookshelfPage

/** One book page of text for a Scribe's Desk, with words outside the dictionary drawn in red. */
class ScribesDeskScreen(private val desk: BlockPos) : Screen(Component.translatable(TITLE_KEY)) {

    private var text = ""
    private var frameTick = 0
    private var words: List<String> = emptyList()
    private var spans: List<WordSpan> = emptyList()
    private lateinit var inscribeButton: Button

    private class WordSpan(val start: Int, val end: Int, val known: Boolean)

    private val field = TextFieldHelper(
        { text },
        { text = it; refresh() },
        TextFieldHelper.createClipboardGetter(Minecraft.getInstance()),
        TextFieldHelper.createClipboardSetter(Minecraft.getInstance()),
        ::fits,
    )

    override fun init() {
        inscribeButton = addRenderableWidget(
            Button.builder(Component.translatable(INSCRIBE_KEY)) { inscribe() }
                .bounds(width / 2 - BUTTON_WIDTH - 2, BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(width / 2 + 2, BUTTON_Y, BUTTON_WIDTH, BUTTON_HEIGHT).build(),
        )
        refresh()
    }

    override fun tick() {
        frameTick++
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean =
        field.charTyped(codePoint) || super.charTyped(codePoint, modifiers)

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode != GLFW.GLFW_KEY_ESCAPE && field.keyPressed(keyCode)) return true
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun fits(candidate: String): Boolean =
        candidate.length <= ScribesDeskNetwork.MAX_TEXT_LENGTH &&
            ScribesDeskNetwork.words(candidate).size <= BookshelfPage.WORDS &&
            lines(candidate).size <= BookPaginator.LINES_PER_PAGE

    private fun refresh() {
        words = ScribesDeskNetwork.words(text)
        spans = WORD.findAll(text).map {
            WordSpan(it.range.first, it.range.last + 1, BookshelfPage.isWord(it.value.lowercase()))
        }.toList()
        if (::inscribeButton.isInitialized) inscribeButton.active = ScribesDeskNetwork.isWritable(words)
    }

    /** The (start, end) of each wrapped line, as vanilla's book screen wraps them. */
    private fun lines(value: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        font.splitter.splitLines(value, BookPaginator.PAGE_WIDTH, TomeLore.SERIF_STYLE, true) { _, start, end ->
            out.add(start until end)
        }
        return out
    }

    private fun serif(from: Int, to: Int): Component =
        Component.literal(text.substring(from, to)).withStyle(TomeLore.SERIF_STYLE)

    private fun inscribe() {
        if (!ScribesDeskNetwork.isWritable(words)) return
        ScribesDeskNetwork.sendInscribe(desk, words.joinToString(" "))
        onClose()
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics)
        val left = (width - BOOK_SIZE) / 2
        SKIN.blitBackground(graphics, left, BOOK_TOP)

        val counter = Component.translatable(COUNT_KEY, words.size, BookshelfPage.WORDS)
        val counterLeft = left + COUNTER_RIGHT - font.width(counter)
        graphics.drawString(font, counter, counterLeft, COUNTER_Y + SKIN.contentDy, INK_COLOR, false)

        val textLeft = left + BookViewScreen.PAGE_TEXT_X_OFFSET
        val textTop = BookViewScreen.PAGE_TEXT_Y_OFFSET + SKIN.contentDy
        val layout = lines(text)
        drawWords(graphics, layout, textLeft, textTop)
        drawSelection(graphics, layout, textLeft, textTop)
        drawCursor(graphics, layout, textLeft, textTop)
        drawStatus(graphics)

        super.render(graphics, mouseX, mouseY, partialTick)
    }

    private fun drawWords(graphics: GuiGraphics, layout: List<IntRange>, left: Int, top: Int) {
        val pageLeft = left - BookViewScreen.PAGE_TEXT_X_OFFSET
        for ((row, line) in layout.withIndex()) {
            val y = top + row * LINE_HEIGHT
            var maskRow = y
            while (maskRow < y + LINE_HEIGHT) {
                val maskEnd = SKIN.maskRunEnd(BOOK_TOP, maskRow, y + LINE_HEIGHT)
                SKIN.enableMask(graphics, pageLeft, BOOK_TOP, maskRow, maskEnd)
                drawLine(graphics, line, left, y)
                graphics.disableScissor()
                maskRow = maskEnd
            }
        }
    }

    private fun drawLine(graphics: GuiGraphics, line: IntRange, left: Int, y: Int) {
        val end = line.last + 1
        for (span in spans) {
            val from = maxOf(span.start, line.first)
            val to = minOf(span.end, end)
            if (from >= to) continue
            val x = left + font.width(serif(line.first, from))
            val color = if (span.known) INK_COLOR else UNKNOWN_COLOR
            graphics.drawString(font, serif(from, to), x, y, color, false)
        }
    }

    private fun drawSelection(graphics: GuiGraphics, layout: List<IntRange>, left: Int, top: Int) {
        val from = minOf(field.cursorPos, field.selectionPos)
        val to = maxOf(field.cursorPos, field.selectionPos)
        if (from == to) return
        for ((row, line) in layout.withIndex()) {
            val start = maxOf(from, line.first)
            val end = minOf(to, line.last + 1)
            if (start >= end) continue
            val x1 = left + font.width(serif(line.first, start))
            val x2 = left + font.width(serif(line.first, end))
            val y = top + row * LINE_HEIGHT
            graphics.fill(RenderType.guiTextHighlight(), x1, y - 1, x2, y + LINE_HEIGHT, SELECTION_COLOR)
        }
    }

    private fun drawCursor(graphics: GuiGraphics, layout: List<IntRange>, left: Int, top: Int) {
        if (frameTick / CURSOR_BLINK_TICKS % 2 != 0) return
        val cursor = field.cursorPos
        val row = layout.indexOfLast { it.first <= cursor }.coerceAtLeast(0)
        val lineStart = layout.getOrNull(row)?.first ?: 0
        val x = left + font.width(serif(lineStart, cursor.coerceAtLeast(lineStart)))
        val y = top + row * LINE_HEIGHT
        graphics.fill(x, y - 1, x + 1, y + LINE_HEIGHT, CURSOR_COLOR)
    }

    private fun drawStatus(graphics: GuiGraphics) {
        val key = when {
            spans.any { !it.known } -> UNKNOWN_KEY
            words.isNotEmpty() && BookshelfPage.languageOf(words) == null -> MIXED_KEY
            else -> return
        }
        for ((row, line) in font.split(Component.translatable(key), width - 2 * STATUS_MARGIN).withIndex()) {
            graphics.drawCenteredString(font, line, width / 2, STATUS_Y + row * LINE_HEIGHT, STATUS_COLOR)
        }
    }

    companion object {
        private const val TITLE_KEY = "screen.enderkinesis.scribes_desk.title"
        private const val INSCRIBE_KEY = "screen.enderkinesis.scribes_desk.inscribe"
        private const val COUNT_KEY = "screen.enderkinesis.scribes_desk.words"
        private const val UNKNOWN_KEY = "screen.enderkinesis.scribes_desk.unknown"
        private const val MIXED_KEY = "screen.enderkinesis.scribes_desk.mixed"

        private val WORD = Regex("\\S+")

        // Vanilla book screen geometry (BookViewScreen's book image is 192 square at y = 2).
        private const val BOOK_SIZE = 192
        private const val BOOK_TOP = 2
        private const val COUNTER_RIGHT = 148
        private const val COUNTER_Y = 18
        private const val LINE_HEIGHT = 9
        private val SKIN = BookSkin.SSELITH_SCROLL
        private val BUTTON_Y = 196 + SKIN.buttonDy
        private const val BUTTON_WIDTH = 98
        private const val BUTTON_HEIGHT = 20

        // The scroll has no room below the text. A full-width line keeps the status on a 240-high screen.
        private val STATUS_Y = BUTTON_Y + BUTTON_HEIGHT + 2
        private const val STATUS_MARGIN = 4

        private const val INK_COLOR = 0xFF000000.toInt()
        private const val UNKNOWN_COLOR = 0xFFC00000.toInt()
        private const val STATUS_COLOR = 0xFFFF5555.toInt()
        private const val CURSOR_COLOR = 0xFF000000.toInt()
        private const val SELECTION_COLOR = 0xFF0000FF.toInt()
        private const val CURSOR_BLINK_TICKS = 6
    }
}
