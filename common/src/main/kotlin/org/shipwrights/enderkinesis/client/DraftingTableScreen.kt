package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Mirror
import org.lwjgl.glfw.GLFW
import org.shipwrights.enderkinesis.construction.TablePlacement
import org.shipwrights.enderkinesis.construction.TableView
import org.shipwrights.enderkinesis.item.cycled
import org.shipwrights.enderkinesis.item.turned
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * The placement screen of one drafting table. The ghost follows [pending] at once; the server gets each button press
 * and each field on Enter, checks it, and sends the table state back.
 */
class DraftingTableScreen(private var view: TableView) : Screen(Component.translatable(TITLE_KEY)) {

    val table: BlockPos get() = view.pos
    private var pending: TablePlacement = view.placement
    private val fields = arrayOfNulls<EditBox>(AXES.size)
    private var onShip = false

    fun update(next: TableView) {
        view = next
        pending = next.placement
        // rebuildWidgets() runs init(), which calls showPreview().
        rebuildWidgets()
    }

    override fun init() {
        onShip = minecraft?.level?.getShipManagingPos(view.pos) != null
        val labelWidth = if (onShip) SHIP_LABEL else LABEL
        val left = width / 2 - PANEL_WIDTH / 2
        var y = TOP + 2 * ROW
        for ((i, axis) in AXES.withIndex()) {
            addRenderableWidget(Button.builder(Component.literal("-")) { shift(axis, -1) }.bounds(left + labelWidth, y, SMALL, BUTTON).build())
            val field = EditBox(font, left + labelWidth + SMALL + GAP, y, FIELD, BUTTON, Component.translatable(axisKey(axis, onShip)))
            field.setMaxLength(FIELD_CHARS)
            field.value = axis.choose(pending.offset.x, pending.offset.y, pending.offset.z).toString()
            field.setFilter { it.isEmpty() || it == "-" || it.toIntOrNull() != null }
            field.setResponder { typed(axis, it) }
            fields[i] = addRenderableWidget(field)
            addRenderableWidget(
                Button.builder(Component.literal("+")) { shift(axis, 1) }.bounds(left + labelWidth + SMALL + FIELD + 2 * GAP, y, SMALL, BUTTON).build(),
            )
            y += ROW
        }
        val half = PANEL_WIDTH / 2 - GAP / 2
        val hasPlan = view.plan != null
        addRenderableWidget(
            Button.builder(Component.translatable(TURN_KEY)) {
                send(pending.copy(rotation = pending.rotation.turned(clockwise = true)))
            }.bounds(left, y, half, BUTTON).build(),
        ).active = hasPlan
        addRenderableWidget(
            Button.builder(Component.translatable(MIRROR_KEY, mirrorName(pending.mirror))) {
                send(pending.copy(mirror = pending.mirror.cycled(forward = true)))
            }.bounds(left + half + GAP, y, half, BUTTON).build(),
        ).active = hasPlan
        y += ROW
        addRenderableWidget(
            Button.builder(Component.translatable(PLANS_KEY)) { DraftingTableClient.askSketchbook(table) }.bounds(left, y, half, BUTTON).build(),
        )
        view.shipSize?.let { size ->
            addRenderableWidget(
                Button.builder(Component.translatable(CAPTURE_KEY)) { captureShip(size) }
                    .bounds(left + half + GAP, y, half, BUTTON).build(),
            )
        }
        y += ROW
        // The user's rule: Build is one switch; on starts the job, off stops it.
        val building = view.building
        val buildLabel = Component.translatable(BUILD_KEY, Component.translatable(if (building) ON_KEY else OFF_KEY))
        addRenderableWidget(
            Button.builder(buildLabel) { if (building) DraftingTableClient.stop(table) else DraftingTableClient.build(table) }
                .bounds(left, y, PANEL_WIDTH, BUTTON).build(),
        ).active = hasPlan || building
        y += ROW
        val onlyBuildable = Component.translatable(BUILDABLE_KEY, Component.translatable(if (view.buildableOnly) ON_KEY else OFF_KEY))
        addRenderableWidget(
            Button.builder(onlyBuildable) { DraftingTableClient.toggleBuildable(table) }.bounds(left, y, PANEL_WIDTH, BUTTON).build(),
        ).active = hasPlan
        y += ROW
        addRenderableWidget(
            Button.builder(Component.translatable(RECRUIT_KEY)) { DraftingTableClient.recruit(table) }.bounds(left, y, PANEL_WIDTH, BUTTON).build(),
        ).active = hasPlan
        showPreview()
    }

    private fun captureShip(size: Vec3i) {
        val pos = table
        Minecraft.getInstance().setScreen(CaptureNameScreen(size) { DraftingTableClient.captureShip(pos, it) })
    }

    private fun shift(axis: Direction.Axis, delta: Int) {
        send(pending.copy(offset = pending.offset.relative(axis, delta)))
    }

    /** A field change moves the ghost now; the server gets it on Enter. */
    private fun typed(axis: Direction.Axis, text: String) {
        val value = text.toIntOrNull() ?: return
        val o = pending.offset
        val next = when (axis) {
            Direction.Axis.X -> Vec3i(value, o.y, o.z)
            Direction.Axis.Y -> Vec3i(o.x, value, o.z)
            Direction.Axis.Z -> Vec3i(o.x, o.y, value)
        }
        if (next == o) return
        pending = pending.copy(offset = next)
        showPreview()
    }

    private fun send(next: TablePlacement) {
        pending = next
        showPreview()
        DraftingTableClient.place(table, next)
    }

    private fun showPreview() {
        val plan = view.plan
        val dimension = Minecraft.getInstance().level?.dimension() ?: return
        DraftingTableClient.showPreview(plan?.let { DraftingTableClient.Preview(table, it, pending.anchor(dimension, table, it.size)) })
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && fields.any { it?.isFocused == true }) {
            send(pending)
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun render(gfx: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val left = width / 2 - PANEL_WIDTH / 2
        val plan = view.plan
        val heading = if (plan == null) {
            Component.translatable(NO_PLAN_KEY)
        } else {
            Component.translatable(PLAN_KEY, plan.name, plan.size.x, plan.size.y, plan.size.z)
        }
        gfx.drawCenteredString(font, title, width / 2, TOP, TEXT)
        gfx.drawCenteredString(font, heading, width / 2, TOP + ROW, FAINT)
        var y = TOP + 2 * ROW
        for (axis in AXES) {
            gfx.drawString(font, Component.translatable(axisKey(axis, onShip)), left, y + TEXT_DROP, TEXT)
            y += ROW
        }
        y += 3 * ROW + GAP
        val turn = Component.translatable(ROTATION_KEY, pending.rotation.ordinal * DEGREES)
        gfx.drawCenteredString(font, turn, width / 2, y, FAINT)
        y += LINE
        if (plan != null && !pending.inReach(plan.size)) {
            gfx.drawCenteredString(font, Component.translatable(TOO_FAR_KEY), width / 2, y, WARN)
            y += LINE
        }
        val state = Component.translatable(if (view.building) BUILDING_KEY else IDLE_KEY, view.supplies, view.crew)
        gfx.drawCenteredString(font, state, width / 2, y, FAINT)
        y += LINE
        if (view.note.string.isNotEmpty()) gfx.drawCenteredString(font, view.note, width / 2, y, TEXT)
        super.render(gfx, mouseX, mouseY, partialTick)
    }

    // The world goes on behind the screen, so the ghost and the build stay live.
    override fun isPauseScreen(): Boolean = false

    override fun removed() {
        DraftingTableClient.showPreview(null)
    }

    internal companion object {
        val AXES = listOf(Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z)

        fun axisKey(axis: Direction.Axis, onShip: Boolean) =
            "screen.enderkinesis.drafting_table.${if (onShip) "ship_" else ""}${axis.getName()}"

        fun mirrorName(mirror: Mirror): Component =
            Component.translatable("screen.enderkinesis.drafting_table.mirror.${mirror.name.lowercase()}")

        const val TITLE_KEY = "block.enderkinesis.drafting_table"
        const val PLAN_KEY = "screen.enderkinesis.drafting_table.plan"
        const val NO_PLAN_KEY = "screen.enderkinesis.drafting_table.no_plan"
        const val TURN_KEY = "screen.enderkinesis.drafting_table.turn"
        const val MIRROR_KEY = "screen.enderkinesis.drafting_table.mirror"
        const val ROTATION_KEY = "screen.enderkinesis.drafting_table.rotation"
        const val PLANS_KEY = "screen.enderkinesis.drafting_table.plans"
        const val CAPTURE_KEY = "screen.enderkinesis.drafting_table.capture_ship"
        const val RECRUIT_KEY = "screen.enderkinesis.drafting_table.recruit"
        const val BUILDABLE_KEY = "screen.enderkinesis.drafting_table.buildable_only"
        const val BUILD_KEY = "screen.enderkinesis.drafting_table.build"
        const val ON_KEY = "screen.enderkinesis.drafting_table.on"
        const val OFF_KEY = "screen.enderkinesis.drafting_table.off"
        const val TOO_FAR_KEY = "screen.enderkinesis.drafting_table.too_far"
        const val BUILDING_KEY = "screen.enderkinesis.drafting_table.building"
        const val IDLE_KEY = "screen.enderkinesis.drafting_table.idle"
        const val PANEL_WIDTH = 220
        const val LABEL = 20
        const val SHIP_LABEL = 44
        const val SMALL = 20
        const val FIELD = 60
        const val FIELD_CHARS = 6
        const val BUTTON = 20
        const val ROW = 24
        const val LINE = 11
        const val GAP = 4
        const val TOP = 20
        const val TEXT_DROP = 6
        const val DEGREES = 90
        const val TEXT = 0xFFFFFF
        const val FAINT = 0xA0A0A0
        const val WARN = 0xFF5555
    }
}
