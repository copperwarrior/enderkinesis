package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.SatchelOfShelvingContents.Companion.ROW_SLOTS
import org.shipwrights.enderkinesis.item.SatchelOfShelvingMenu
import org.shipwrights.enderkinesis.item.SatchelOfShelvingMenu.Companion.ROW_HEIGHT
import org.shipwrights.enderkinesis.item.SatchelOfShelvingMenu.Companion.SHELF_SLOT_X
import org.shipwrights.enderkinesis.item.SatchelOfShelvingMenu.Companion.SLOT_INSET
import org.shipwrights.enderkinesis.item.SatchelOfShelvingMenu.Companion.VIEW_BOTTOM
import org.shipwrights.enderkinesis.item.SatchelOfShelvingMenu.Companion.VIEW_HEIGHT
import org.shipwrights.enderkinesis.item.SatchelOfShelvingMenu.Companion.VIEW_TOP
import org.shipwrights.enderkinesis.item.SatchelOfShelvingMenu.Companion.WINDOW_SLOTS
import org.shipwrights.enderkinesis.item.SatchelOfShelvingNetwork
import org.shipwrights.enderkinesis.mixin.SlotPositionAccessor
import org.shipwrights.enderkinesis.registry.EKSounds
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

class SatchelOfShelvingScreen(menu: SatchelOfShelvingMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<SatchelOfShelvingMenu>(menu, inventory, title), SlotClipping {

    private var draggingThumb = false

    /** Pixel offset of the shelf; it eases toward [targetScroll]. */
    private var scroll = 0f
    private var targetScroll = 0f
    private var lastFrameNanos = Util.getNanos()

    /** The first row the server was asked to put in the slot window. */
    private var requestedRow = 0

    /** The bag opening in absolute screen coordinates; set again on each resize. */
    private var viewClip = ScreenRectangle(0, 0, 0, 0)

    private val backdrop = SatchelOfShelvingBackdrop()

    private var skyLoop: SoundInstance? = null
    private var scrollSoundRow = 0
    // Not Long.MIN_VALUE: tick - MIN_VALUE overflows to a negative gap.
    private var scrollSoundTick = -SCROLL_SOUND_TICKS.toLong()
    private var scrollEndSoundTick = -SCROLL_SOUND_TICKS.toLong()

    init {
        imageWidth = GUI_WIDTH
        imageHeight = GUI_HEIGHT
    }

    override fun init() {
        super.init()
        viewClip = ScreenRectangle(leftPos + SHELF_X, topPos + VIEW_TOP, SHELF_WIDTH, VIEW_HEIGHT)
        // init() runs again on each resize.
        if (skyLoop == null) {
            val loop = uiSound(EKSounds.SATCHEL_OF_SHELVING_SKY_LOOP.get(), true)
            Minecraft.getInstance().soundManager.play(loop)
            skyLoop = loop
        }
    }

    // Also called when the server closes the menu.
    override fun removed() {
        super.removed()
        val sounds = Minecraft.getInstance().soundManager
        skyLoop?.let(sounds::stop)
        skyLoop = null
        sounds.play(uiSound(EKSounds.SATCHEL_OF_SHELVING_CLOSE.get(), false))
    }

    override fun slotClip(slot: Slot): ScreenRectangle? = if (slot.index < WINDOW_SLOTS) viewClip else null

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        stepScroll()
        playScrollSound()
        followScroll()
        renderBackground(graphics)
        super.render(graphics, mouseX, mouseY, partialTick)
        renderTooltip(graphics, mouseX, mouseY)
    }

    // The frame art has no space for the title or the inventory label.
    override fun renderLabels(graphics: GuiGraphics, mouseX: Int, mouseY: Int) = Unit

    override fun renderBg(graphics: GuiGraphics, partialTick: Float, mouseX: Int, mouseY: Int) {
        RenderSystem.enableBlend()
        renderBackdrop(graphics, mouseX, mouseY, partialTick)
        graphics.enableScissor(viewClip.left(), viewClip.top(), viewClip.right(), viewClip.bottom())
        renderShelf(graphics)
        renderScrollbar(graphics)
        graphics.disableScissor()
        // After the shelf, so the rim covers the corners of the opening.
        blit(graphics, 0, 0, FRAME_U, FRAME_V, GUI_WIDTH, GUI_HEIGHT)
        RenderSystem.disableBlend()
    }

    // The frame, drawn after, cuts the backdrop to the shape of the sheet's mask sprite.
    private fun renderBackdrop(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        backdrop.render(graphics, leftPos + MASK_X, topPos + MASK_Y, MASK_WIDTH, MASK_HEIGHT, scroll, mouseX, mouseY, partialTick)
    }

    private fun renderShelf(graphics: GuiGraphics) {
        val top = shelfTop()
        val rows = menu.rowCount
        val bodyTop = top + ROW_TOP_HEIGHT
        val first = Math.floorDiv(VIEW_TOP - bodyTop, ROW_HEIGHT).coerceIn(0, rows - 1)
        val last = Math.floorDiv(VIEW_BOTTOM - bodyTop, ROW_HEIGHT).coerceIn(0, rows - 1)
        if (first == 0) blit(graphics, SHELF_X, top, ROW_U, ROW_TOP_V, ROW_WIDTH, ROW_TOP_HEIGHT)
        for (row in first..last) {
            blit(graphics, SHELF_X, bodyTop + row * ROW_HEIGHT, ROW_U, ROW_BODY_V, ROW_WIDTH, ROW_HEIGHT)
        }
        if (last == rows - 1) blit(graphics, SHELF_X, bodyTop + rows * ROW_HEIGHT, ROW_U, ROW_BOTTOM_V, ROW_WIDTH, ROW_BOTTOM_HEIGHT)
    }

    /** The track does not scroll, but its caps follow the shelf ends so the two read as one piece. */
    private fun renderScrollbar(graphics: GuiGraphics) {
        val top = trackTop()
        val bottom = trackBottom()
        blit(graphics, TRACK_X, top, TRACK_U, TRACK_TOP_V, TRACK_WIDTH, TRACK_CAP_HEIGHT)
        var y = top + TRACK_CAP_HEIGHT
        while (y < bottom - TRACK_CAP_HEIGHT) {
            val height = minOf(TRACK_TILE_HEIGHT, bottom - TRACK_CAP_HEIGHT - y)
            blit(graphics, TRACK_X, y, TRACK_U, TRACK_BODY_V, TRACK_WIDTH, height)
            y += height
        }
        blit(graphics, TRACK_X, bottom - TRACK_CAP_HEIGHT, TRACK_U, TRACK_BOTTOM_V, TRACK_WIDTH, TRACK_CAP_HEIGHT)

        val maxScroll = maxScroll()
        val thumbY = if (maxScroll == 0) top + TRACK_CAP_HEIGHT else THUMB_TOP + (THUMB_TRAVEL * scroll / maxScroll).roundToInt()
        val thumbU = if (maxScroll == 0) THUMB_DISABLED_U else THUMB_U
        blit(graphics, TRACK_X + THUMB_INSET, thumbY, thumbU, THUMB_V, THUMB_WIDTH, THUMB_HEIGHT)
    }

    private fun trackTop(): Int = maxOf(shelfTop(), VIEW_TOP - TRACK_CAP_HEIGHT)

    private fun trackBottom(): Int = minOf(shelfTop() + shelfHeight(), VIEW_BOTTOM + TRACK_CAP_HEIGHT)

    private fun stepScroll() {
        val now = Util.getNanos()
        val seconds = (now - lastFrameNanos) / 1.0e9f
        lastFrameNanos = now
        val max = maxScroll().toFloat()
        targetScroll = targetScroll.coerceIn(0f, max)
        scroll = if (draggingThumb) targetScroll else scroll + (targetScroll - scroll) * (1f - exp(-EASE_RATE * seconds))
        if (abs(targetScroll - scroll) < SNAP_DISTANCE) scroll = targetScroll
        scroll = scroll.coerceIn(0f, max)
    }

    private fun playScrollSound() {
        val row = floor(scroll / ROW_HEIGHT).toInt()
        if (row == scrollSoundRow) return
        scrollSoundRow = row
        val tick = Minecraft.getInstance().level?.gameTime ?: return
        if (tick - scrollSoundTick < SCROLL_SOUND_TICKS) return
        scrollSoundTick = tick
        Minecraft.getInstance().soundManager.play(uiSound(EKSounds.SATCHEL_OF_SHELVING_SCROLL.get(), false))
    }

    private fun playScrollEndSound() {
        val tick = Minecraft.getInstance().level?.gameTime ?: return
        if (tick - scrollEndSoundTick < SCROLL_SOUND_TICKS) return
        scrollEndSoundTick = tick
        Minecraft.getInstance().soundManager.play(uiSound(EKSounds.SATCHEL_OF_SHELVING_SCROLL_END.get(), false))
    }

    private fun uiSound(event: SoundEvent, looping: Boolean): SoundInstance = SimpleSoundInstance(
        event.location, SoundSource.PLAYERS, 1f, 1f, SoundInstance.createUnseededRandom(), looping, 0,
        SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true,
    )

    /** Asks the server for the rows under the opening, then puts the window slots where the server's rows are. */
    private fun followScroll() {
        val top = shelfTop()
        val wantedRow = Math.floorDiv(VIEW_TOP - top - ROW_TOP_HEIGHT, ROW_HEIGHT).coerceAtLeast(0)
        if (wantedRow != requestedRow) {
            requestedRow = wantedRow
            SatchelOfShelvingNetwork.sendScroll(menu.containerId, wantedRow)
        }
        val windowTop = top + ROW_TOP_HEIGHT + menu.scrollRow * ROW_HEIGHT + SLOT_INSET
        for (index in 0 until WINDOW_SLOTS) {
            (menu.slots[index] as SlotPositionAccessor).`enderkinesis$setY`(windowTop + index / ROW_SLOTS * ROW_HEIGHT)
        }
    }

    /** A shelf shorter than the opening sits in its center; a taller one scrolls. */
    private fun shelfTop(): Int {
        val centering = maxOf(0, VIEW_HEIGHT - shelfHeight()) / 2f
        return floor(VIEW_TOP + centering - scroll).toInt()
    }

    private fun shelfHeight(): Int = ROW_TOP_HEIGHT + menu.rowCount * ROW_HEIGHT + ROW_BOTTOM_HEIGHT

    private fun maxScroll(): Int = maxOf(0, shelfHeight() - VIEW_HEIGHT)

    // A slot the rim cuts only takes the mouse where it shows.
    override fun isHovering(x: Int, y: Int, width: Int, height: Int, mouseX: Double, mouseY: Double): Boolean {
        if (!super.isHovering(x, y, width, height, mouseX, mouseY)) return false
        if (y + height <= VIEW_TOP || y >= VIEW_BOTTOM) return true
        val viewY = mouseY - topPos
        return viewY >= VIEW_TOP && viewY < VIEW_BOTTOM
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        if (delta == 0.0) return super.mouseScrolled(mouseX, mouseY, delta)
        val max = maxScroll().toFloat()
        val next = targetScroll - Mth.sign(delta) * ROW_HEIGHT
        if (next < 0f && targetScroll <= 0f || next > max && targetScroll >= max) playScrollEndSound()
        if (max == 0f) return super.mouseScrolled(mouseX, mouseY, delta)
        targetScroll = next.coerceIn(0f, max)
        return true
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && maxScroll() > 0 && isOverTrack(mouseX, mouseY)) {
            draggingThumb = true
            scrollToMouse(mouseY)
            return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        if (!draggingThumb) return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
        scrollToMouse(mouseY)
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0) draggingThumb = false
        return super.mouseReleased(mouseX, mouseY, button)
    }

    private fun scrollToMouse(mouseY: Double) {
        val thumbCenter = mouseY - topPos - THUMB_TOP - THUMB_HEIGHT / 2.0
        val fraction = (thumbCenter / THUMB_TRAVEL).coerceIn(0.0, 1.0)
        targetScroll = (fraction * maxScroll()).toFloat()
    }

    private fun isOverTrack(mouseX: Double, mouseY: Double): Boolean {
        val x = mouseX - leftPos - TRACK_X
        val y = mouseY - topPos
        return x >= 0 && x < TRACK_WIDTH && y >= maxOf(trackTop(), VIEW_TOP) && y < minOf(trackBottom(), VIEW_BOTTOM)
    }

    private fun blit(graphics: GuiGraphics, x: Int, y: Int, u: Int, v: Int, width: Int, height: Int) {
        graphics.blit(TEXTURE, leftPos + x, topPos + y, u.toFloat(), v.toFloat(), width, height, SHEET_WIDTH, SHEET_HEIGHT)
    }

    private companion object {
        val TEXTURE = EnderkinesisMod.id("textures/gui/satchel_of_shelving.png")
        const val SHEET_WIDTH = 272
        const val SHEET_HEIGHT = 384

        const val FRAME_U = 7
        const val FRAME_V = 10
        const val GUI_WIDTH = 258
        const val GUI_HEIGHT = 212

        const val MASK_X = 26 - FRAME_U
        const val MASK_Y = 24 - FRAME_V
        const val MASK_WIDTH = 220
        const val MASK_HEIGHT = 101

        const val ROW_U = 48
        const val ROW_WIDTH = 172
        const val ROW_TOP_V = 340
        const val ROW_TOP_HEIGHT = 3
        const val ROW_BODY_V = 344
        const val ROW_BOTTOM_V = 367
        const val ROW_BOTTOM_HEIGHT = 4

        /** The slot wells start 8 px into the row sprite. */
        const val SHELF_X = SHELF_SLOT_X - 8

        const val TRACK_U = 223
        const val TRACK_X = SHELF_X + ROW_WIDTH
        const val TRACK_WIDTH = 15
        const val TRACK_TOP_V = 346
        const val TRACK_BODY_V = 351
        const val TRACK_BOTTOM_V = 367
        const val TRACK_CAP_HEIGHT = 4
        const val TRACK_TILE_HEIGHT = 15

        /** Shelf and track together, centered in the opening. */
        const val SHELF_WIDTH = ROW_WIDTH + TRACK_WIDTH

        const val THUMB_U = 239
        const val THUMB_DISABLED_U = 249
        const val THUMB_V = 351
        const val THUMB_INSET = 2
        const val THUMB_WIDTH = 9
        const val THUMB_HEIGHT = 15

        /** The thumb stays inside the caps the shelf ends show at full scroll. */
        const val THUMB_TOP = VIEW_TOP + TRACK_CAP_HEIGHT
        const val THUMB_TRAVEL = VIEW_HEIGHT - 2 * TRACK_CAP_HEIGHT - THUMB_HEIGHT

        const val SCROLL_SOUND_TICKS = 2
        const val EASE_RATE = 18f
        const val SNAP_DISTANCE = 0.05f
    }
}

/** Item-model property `enderkinesis:open`: 1 for the satchel stack an open satchel screen shows. */
object SatchelOpenProperty {
    val ID = EnderkinesisMod.id("open")

    fun value(stack: ItemStack): Float {
        val minecraft = Minecraft.getInstance()
        val screen = minecraft.screen as? SatchelOfShelvingScreen ?: return 0f
        val inventory = minecraft.player?.inventory ?: return 0f
        val slot = screen.menu.openInventorySlot
        return if (slot != SatchelOfShelvingMenu.NO_SLOT && inventory.getItem(slot) === stack) 1f else 0f
    }
}
