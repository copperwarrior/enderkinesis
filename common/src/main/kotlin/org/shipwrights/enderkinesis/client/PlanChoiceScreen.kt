package org.shipwrights.enderkinesis.client

import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.core.Vec3i
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.construction.ClientPlanFiles
import org.shipwrights.enderkinesis.construction.ClientPlanUpload
import org.shipwrights.enderkinesis.construction.ClientPlans
import org.shipwrights.enderkinesis.construction.LocalPlanFile
import org.shipwrights.enderkinesis.construction.LocalPlanSections
import org.shipwrights.enderkinesis.construction.PlanSections
import org.shipwrights.enderkinesis.construction.PlanRef
import org.shipwrights.enderkinesis.item.SketchSpec
import org.shipwrights.enderkinesis.item.SketchSubject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An open sketchbook of plans: the player's stored plans, then the local plan files, each as a silverpoint sketch.
 * A local pick uploads first; the server confirms each pick. Only the open spread loads and bakes its sketches.
 */
class PlanChoiceScreen(
    private val stored: List<PlanRef>,
    private val onPick: (PlanRef) -> Unit,
    private val onCancel: () -> Unit = {},
    // The size of the ship the table stands on; null off a ship, which hides the capture button.
    private val shipSize: Vec3i? = null,
    private val onCapture: (String) -> Unit = {},
) : Screen(Component.translatable(TITLE_KEY)) {

    private sealed interface Entry {
        val name: String

        class Stored(val ref: PlanRef) : Entry {
            override val name: String get() = ref.name
        }

        class Local(val file: LocalPlanFile) : Entry {
            override val name: String get() = file.name
        }
    }

    private class Preview {
        var sections: PlanSections? = null
        var local: LocalPlanSections? = null
        var loading: CompletableFuture<LocalPlanSections?>? = null
        val cancelled = AtomicBoolean()
        var failed = false
        var sketch: StrikeSketch? = null

        // Labels cut to the cell width; made again when the width or the plan size changes.
        var name = ""
        var detail = ""
        var labelWidth = -1
        var labelSections: PlanSections? = null

        fun close() {
            sketch?.close()
            sketch = null
        }

        /** Also stops a local file read that is still running. */
        fun drop() {
            close()
            cancelled.set(true)
            local?.close()
            // A read that ends after the screen closed releases its file too.
            loading?.thenAccept { it?.close() }
        }
    }

    private val storedEntries = stored.map { Entry.Stored(it) }.toMutableList()
    private val localEntries = ClientPlanFiles.list().map { Entry.Local(it) }
    private val storedHashes = stored.mapTo(HashSet()) { it.hash }

    // A local file stays hidden until it is hashed; one equal to a stored plan never shows (the stored entry is the plan).
    private var shownLocals = emptyList<Entry.Local>()
    private var entries: List<Entry> = storedEntries.toList()
    private val previews = HashMap<Entry, Preview>()
    private var spread = 0
    private var sketchSize = 0
    private var uploading = false
    private var status: List<Component> = emptyList()

    // A stored plan whose delete mark was clicked once; the next click on the same mark deletes it.
    private var armed: Entry.Stored? = null

    // Spread geometry, set in init().
    private var spreadLeft = 0
    private var spreadTop = 0
    private var spreadWidth = 0
    private var spreadHeight = 0
    private var cellWidth = 0
    private var cellHeight = 0

    private val spreadCount: Int get() = maxOf(1, (entries.size + PER_SPREAD - 1) / PER_SPREAD)

    override fun init() {
        spread = spread.coerceIn(0, spreadCount - 1)
        spreadWidth = minOf(width - 2 * MARGIN, MAX_SPREAD_WIDTH)
        spreadHeight = height - SPREAD_TOP - FOOTER - STATUS_ROOM
        spreadLeft = (width - spreadWidth) / 2
        spreadTop = SPREAD_TOP
        cellWidth = (spreadWidth / 2 - PAD * (COLUMNS + 1)) / COLUMNS
        cellHeight = (spreadHeight - PAD * (ROWS + 1)) / ROWS
        val size = minOf(cellWidth, cellHeight - LABEL_HEIGHT).coerceIn(MIN_SKETCH, MAX_SKETCH)
        if (size != sketchSize) {
            previews.values.forEach(Preview::close)
            sketchSize = size
        }

        val footerY = height - FOOTER + FOOTER_GAP
        val left = width / 2 - FOOTER_WIDTH / 2
        addRenderableWidget(
            Button.builder(Component.literal("<")) { turn(-1) }.bounds(left, footerY, SMALL_WIDTH, BUTTON_HEIGHT).build(),
        ).active = spread > 0
        addRenderableWidget(
            Button.builder(Component.literal(">")) { turn(1) }
                .bounds(left + SMALL_WIDTH + GAP, footerY, SMALL_WIDTH, BUTTON_HEIGHT).build(),
        ).active = spread < spreadCount - 1
        addRenderableWidget(
            Button.builder(Component.translatable(FOLDER_KEY)) { Util.getPlatform().openFile(ClientPlanFiles.folder().toFile()) }
                .bounds(left + 2 * (SMALL_WIDTH + GAP), footerY, FOLDER_WIDTH, BUTTON_HEIGHT).build(),
        )
        shipSize?.let { size ->
            addRenderableWidget(
                Button.builder(Component.translatable(DraftingTableScreen.CAPTURE_KEY)) {
                    Minecraft.getInstance().setScreen(CaptureNameScreen(size, onCapture))
                }.bounds(left + 2 * (SMALL_WIDTH + GAP) + FOLDER_WIDTH + GAP, footerY, CAPTURE_WIDTH, BUTTON_HEIGHT).build(),
            )
        }
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(left + FOOTER_WIDTH - CANCEL_WIDTH, footerY, CANCEL_WIDTH, BUTTON_HEIGHT).build(),
        )
    }

    private fun turn(delta: Int) {
        spread = (spread + delta).coerceIn(0, spreadCount - 1)
        disarm()
        dropClosedPreviews()
        rebuildWidgets()
    }

    // Only the open spread keeps plan sections and sketch textures.
    private fun dropClosedPreviews() {
        val open = openEntries().toSet()
        val iterator = previews.entries.iterator()
        while (iterator.hasNext()) {
            val (entry, preview) = iterator.next()
            if (entry in open) continue
            preview.drop()
            iterator.remove()
        }
    }

    private fun openEntries(): List<Entry> {
        val first = spread * PER_SPREAD
        return entries.subList(minOf(first, entries.size), minOf(first + PER_SPREAD, entries.size))
    }

    private fun refreshLocals() {
        val shown = localEntries.filter { ClientPlanFiles.hashIfKnown(it.file)?.let { hash -> hash !in storedHashes } == true }
        if (shown.size == shownLocals.size) return
        shownLocals = shown
        entries = storedEntries + shown
        dropClosedPreviews()
        spread = spread.coerceIn(0, spreadCount - 1)
        rebuildWidgets()
    }

    override fun tick() {
        refreshLocals()
        for (entry in openEntries()) poll(entry, previews.getOrPut(entry, ::Preview))
        if (!uploading) return
        val result = ClientPlanUpload.lastResult ?: return
        uploading = false
        val ref = result.ref
        if (result.accepted && ref != null) {
            picked(ref)
            return
        }
        status = result.issues.map { it.toComponent() }
    }

    private fun poll(entry: Entry, preview: Preview) {
        if (preview.sections != null || preview.failed) return
        when (entry) {
            is Entry.Stored -> {
                preview.sections = ClientPlans.sections(entry.ref)
                preview.failed = ClientPlans.status(entry.ref) == ClientPlans.Status.MISSING
            }
            is Entry.Local -> {
                val loading = preview.loading
                if (loading == null) {
                    val cancelled = preview.cancelled
                    preview.loading = CompletableFuture.supplyAsync(
                        { entry.file.openSections { cancelled.get() } }, Util.backgroundExecutor(),
                    )
                } else if (loading.isDone) {
                    preview.loading = null
                    preview.local = loading.getNow(null)
                    preview.sections = preview.local?.sections
                    preview.failed = preview.sections == null
                }
            }
        }
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (super.mouseClicked(mouseX, mouseY, button)) return true
        if (uploading) return false
        val x = mouseX.toInt()
        val y = mouseY.toInt()
        val marked = deleteMarkAt(x, y)
        if (marked != null) {
            if (armed === marked) delete(marked) else arm(marked)
            return true
        }
        disarm()
        val entry = entryAt(x, y) ?: return false
        pick(entry)
        return true
    }

    /** The stored plan whose delete mark is at ([x], [y]); only a hovered cell shows its mark. */
    private fun deleteMarkAt(x: Int, y: Int): Entry.Stored? {
        val open = openEntries()
        for (slot in open.indices) {
            val entry = open[slot] as? Entry.Stored ?: continue
            if (inDeleteMark(slot, x, y)) return entry
        }
        return null
    }

    private fun inDeleteMark(slot: Int, x: Int, y: Int): Boolean {
        val markX = slotX(slot) + cellWidth - MARK_SIZE
        val markY = slotY(slot)
        return x in markX until markX + MARK_SIZE && y in markY until markY + MARK_SIZE
    }

    private fun arm(entry: Entry.Stored) {
        armed = entry
        status = listOf(Component.translatable(DELETE_CONFIRM_KEY, entry.name))
    }

    private fun disarm() {
        if (armed == null) return
        armed = null
        status = emptyList()
    }

    /** The server checks the owner again; the plan leaves this list at once. */
    private fun delete(entry: Entry.Stored) {
        ClientPlans.delete(entry.ref)
        armed = null
        storedEntries.remove(entry)
        entries = storedEntries + shownLocals
        previews.remove(entry)?.drop()
        status = listOf(Component.translatable(DELETED_KEY, entry.name))
        spread = spread.coerceIn(0, spreadCount - 1)
        rebuildWidgets()
    }

    private fun entryAt(x: Int, y: Int): Entry? {
        val open = openEntries()
        for (slot in open.indices) {
            val cx = slotX(slot)
            val cy = slotY(slot)
            if (x in cx until cx + cellWidth && y in cy until cy + cellHeight) return open[slot]
        }
        return null
    }

    private fun pick(entry: Entry) {
        when (entry) {
            is Entry.Stored -> picked(entry.ref)
            is Entry.Local -> if (ClientPlanUpload.upload(entry.file)) {
                uploading = true
                status = emptyList()
            } else {
                status = listOf(Component.translatable(UPLOAD_FAILED_KEY, entry.file.name))
            }
        }
    }

    private fun slotX(slot: Int): Int {
        val page = slot / PER_PAGE
        val column = slot % PER_PAGE % COLUMNS
        return spreadLeft + page * (spreadWidth / 2) + PAD + column * (cellWidth + PAD)
    }

    private fun slotY(slot: Int): Int = spreadTop + PAD + slot % PER_PAGE / COLUMNS * (cellHeight + PAD)

    override fun render(gfx: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(gfx)
        drawSpread(gfx)
        gfx.drawCenteredString(font, title, width / 2, TITLE_Y, TEXT_COLOR)
        if (entries.isEmpty()) {
            gfx.drawCenteredString(font, Component.translatable(EMPTY_KEY), width / 2, spreadTop + spreadHeight / 2, INK)
        }
        var budget = BAKES_PER_FRAME
        val open = openEntries()
        for (slot in open.indices) {
            val entry = open[slot]
            val preview = previews.getOrPut(entry, ::Preview)
            if (drawCell(gfx, slot, entry, preview, budget > 0, mouseX, mouseY)) budget--
        }
        super.render(gfx, mouseX, mouseY, partialTick)
        drawStatus(gfx)
    }

    /** Plain fills for the two pages and the spine; no texture. */
    private fun drawSpread(gfx: GuiGraphics) {
        val right = spreadLeft + spreadWidth
        val bottom = spreadTop + spreadHeight
        gfx.fill(spreadLeft - EDGE, spreadTop - EDGE, right + EDGE, bottom + EDGE, COVER)
        gfx.fill(spreadLeft, spreadTop, right, bottom, PAPER)
        val spine = spreadLeft + spreadWidth / 2
        gfx.fill(spine - 1, spreadTop, spine + 1, bottom, SPINE)
    }

    /** Returns true when this call spent the frame's bake time; a sketch that waits for sections lets the next one bake. */
    @Suppress("LongParameterList") // A cell needs its slot, entry, preview, bake permission and the mouse.
    private fun drawCell(
        gfx: GuiGraphics, slot: Int, entry: Entry, preview: Preview, mayBake: Boolean, mouseX: Int, mouseY: Int,
    ): Boolean {
        val x = slotX(slot)
        val y = slotY(slot)
        val hovered = !uploading && mouseX in x until x + cellWidth && mouseY in y until y + cellHeight
        if (hovered) gfx.fill(x - 1, y - 1, x + cellWidth + 1, y + cellHeight + 1, HOVER)
        if (entry is Entry.Stored && (hovered || armed === entry)) drawDeleteMark(gfx, slot, entry, mouseX, mouseY)
        val sketchX = x + (cellWidth - sketchSize) / 2
        val sketchY = y + (cellHeight - LABEL_HEIGHT - sketchSize) / 2
        val sections = preview.sections
        var bakedNow = false
        if (sections != null && mayBake && preview.sketch?.ready != true) {
            val sketch = preview.sketch ?: StrikeSketch(spec(entry), SketchScene.ofPlan(sections)).also { preview.sketch = it }
            // A bake draws into its own targets, so queued GUI quads go out first.
            gfx.flush()
            sketch.advance(System.nanoTime() + BAKE_NANOS)
            bakedNow = !sketch.waiting
        }
        val sketch = preview.sketch
        if (sketch != null && sketch.ready) {
            sketch.render(gfx, sketchX, sketchY)
        } else {
            val key = if (preview.failed) NO_SKETCH_KEY else SKETCHING_KEY
            gfx.drawCenteredString(font, Component.translatable(key), x + cellWidth / 2, sketchY + sketchSize / 2, FAINT_INK)
        }
        val labelY = y + cellHeight - LABEL_HEIGHT
        if (preview.labelWidth != cellWidth || preview.labelSections !== sections) {
            preview.name = font.plainSubstrByWidth(entry.name, cellWidth)
            preview.detail = font.plainSubstrByWidth(detailLine(entry, sections).string, cellWidth)
            preview.labelWidth = cellWidth
            preview.labelSections = sections
        }
        gfx.drawString(font, preview.name, x + (cellWidth - font.width(preview.name)) / 2, labelY, INK, false)
        gfx.drawString(font, preview.detail, x + (cellWidth - font.width(preview.detail)) / 2, labelY + LINE, FAINT_INK, false)
        return bakedNow
    }

    /** A small mark in the top right corner of a stored plan; red once it is armed. */
    private fun drawDeleteMark(gfx: GuiGraphics, slot: Int, entry: Entry.Stored, mouseX: Int, mouseY: Int) {
        val markX = slotX(slot) + cellWidth - MARK_SIZE
        val markY = slotY(slot)
        val over = inDeleteMark(slot, mouseX, mouseY)
        gfx.fill(markX, markY, markX + MARK_SIZE, markY + MARK_SIZE, if (armed === entry) ARMED else if (over) MARK_HOVER else MARK)
        gfx.drawCenteredString(font, MARK_TEXT, markX + MARK_SIZE / 2, markY + (MARK_SIZE - font.lineHeight) / 2 + 1, TEXT_COLOR)
        if (over) gfx.renderTooltip(font, Component.translatable(DELETE_KEY), mouseX, mouseY)
    }

    private fun detailLine(entry: Entry, sections: PlanSections?): Component = when (entry) {
        is Entry.Stored -> Component.translatable(SIZE_KEY, entry.ref.size.x, entry.ref.size.y, entry.ref.size.z)
        is Entry.Local -> sections?.size?.let { Component.translatable(LOCAL_SIZE_KEY, it.x, it.y, it.z) }
            ?: Component.translatable(LOCAL_KEY)
    }

    private fun spec(entry: Entry): SketchSpec = SketchSpec(SKETCH_SUBJECT, entry.name.hashCode(), sketchSize, notes = true)

    private fun drawStatus(gfx: GuiGraphics) {
        var y = spreadTop + spreadHeight + EDGE + STATUS_GAP
        val progress = ClientPlanUpload.progress()
        if (uploading && progress != null) {
            val percent = (progress * PERCENT).toInt()
            gfx.drawCenteredString(font, Component.translatable(UPLOADING_KEY, percent), width / 2, y, TEXT_COLOR)
            y += LINE
        }
        for (line in status.take(MAX_STATUS_LINES)) {
            gfx.drawCenteredString(font, line, width / 2, y, ERROR_COLOR)
            y += LINE
        }
    }

    private var chosen = false

    private fun picked(ref: PlanRef) {
        chosen = true
        onClose()
        onPick(ref)
    }

    override fun onClose() {
        super.onClose()
        if (!chosen) onCancel()
    }

    // A paused integrated server does not tick, so an upload or a section fetch from this screen would wait.
    override fun isPauseScreen(): Boolean = false

    override fun removed() {
        previews.values.forEach(Preview::drop)
        previews.clear()
    }

    private companion object {
        const val TITLE_KEY = "screen.enderkinesis.plan_choice"
        const val SIZE_KEY = "screen.enderkinesis.plan_choice.stored"
        const val LOCAL_KEY = "screen.enderkinesis.plan_choice.local"
        const val LOCAL_SIZE_KEY = "screen.enderkinesis.plan_choice.local_size"
        const val FOLDER_KEY = "screen.enderkinesis.plan_choice.folder"
        const val EMPTY_KEY = "screen.enderkinesis.plan_choice.empty"
        const val UPLOADING_KEY = "screen.enderkinesis.plan_choice.uploading"
        const val UPLOAD_FAILED_KEY = "screen.enderkinesis.plan_choice.upload_failed"
        const val SKETCHING_KEY = "screen.enderkinesis.plan_choice.sketching"
        const val NO_SKETCH_KEY = "screen.enderkinesis.plan_choice.no_sketch"
        const val DELETE_KEY = "screen.enderkinesis.plan_choice.delete"
        const val DELETE_CONFIRM_KEY = "screen.enderkinesis.plan_choice.delete_confirm"
        const val DELETED_KEY = "screen.enderkinesis.plan_choice.deleted"
        val MARK_TEXT: Component = Component.literal("x")

        // The scene is given to each sketch, so this subject only fills the spec.
        val SKETCH_SUBJECT = SketchSubject.Structure(EnderkinesisMod.id("plan"))

        const val COLUMNS = 2
        const val ROWS = 2
        const val PER_PAGE = COLUMNS * ROWS
        const val PER_SPREAD = PER_PAGE * 2
        // One sketch bakes at a time, a slice of each frame, so a huge plan never costs one long frame.
        const val BAKES_PER_FRAME = 1
        const val BAKE_NANOS = 4_000_000L
        const val MIN_SKETCH = 32
        const val MAX_SKETCH = 96
        const val MAX_SPREAD_WIDTH = 360
        const val MARGIN = 16
        const val PAD = 6
        const val EDGE = 3
        const val LINE = 10
        const val LABEL_HEIGHT = 2 * LINE
        const val TITLE_Y = 8
        const val SPREAD_TOP = 24
        const val STATUS_ROOM = 26
        const val STATUS_GAP = 2
        const val MAX_STATUS_LINES = 2
        const val FOOTER = 30
        const val FOOTER_GAP = 5
        const val FOOTER_WIDTH = 320
        const val BUTTON_HEIGHT = 20
        const val SMALL_WIDTH = 20
        const val FOLDER_WIDTH = 100
        const val CAPTURE_WIDTH = 100
        const val CANCEL_WIDTH = 64
        const val GAP = 4
        const val PERCENT = 100f
        const val TEXT_COLOR = 0xFFFFFF
        const val ERROR_COLOR = 0xFF5555
        const val COVER = 0xFF3A2E24.toInt()
        const val PAPER = 0xFFEDE6D6.toInt()
        const val SPINE = 0x33000000
        const val HOVER = 0x22000000
        const val INK = 0xFF3A3A3A.toInt()
        const val FAINT_INK = 0xFF7A7A7A.toInt()
        const val MARK_SIZE = 10
        const val MARK = 0x88000000.toInt()
        const val MARK_HOVER = 0xCC000000.toInt()
        const val ARMED = 0xFFB03030.toInt()
    }
}
