package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.Vec3i
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import org.shipwrights.enderkinesis.construction.PlanRef

/** Names a plan capture of a box of [size]; [onSave] sends the name, and the server reads the box again. */
class CaptureNameScreen(private val size: Vec3i, private val onSave: (String) -> Unit) : Screen(Component.translatable(TITLE_KEY)) {
    private lateinit var name: EditBox
    private lateinit var save: Button

    override fun init() {
        val left = width / 2 - FIELD_WIDTH / 2
        val top = height / 2 - ROW
        name = EditBox(font, left, top, FIELD_WIDTH, ROW - 4, Component.translatable(NAME_KEY))
        name.setMaxLength(PlanRef.MAX_NAME)
        name.setHint(Component.translatable(NAME_KEY))
        name.setResponder { save.active = it.isNotBlank() }
        addRenderableWidget(name)
        save = Button.builder(Component.translatable(SAVE_KEY)) { submit() }
            .bounds(left, top + ROW + 4, FIELD_WIDTH / 2 - 2, ROW - 4).build()
        save.active = false
        addRenderableWidget(save)
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(left + FIELD_WIDTH / 2 + 2, top + ROW + 4, FIELD_WIDTH / 2 - 2, ROW - 4).build(),
        )
        setInitialFocus(name)
    }

    // A resize runs init() again and makes a new, empty EditBox.
    override fun resize(minecraft: Minecraft, width: Int, height: Int) {
        val typed = name.value
        super.resize(minecraft, width, height)
        name.value = typed
    }

    private fun submit() {
        if (name.value.isBlank()) return
        val chosen = name.value.trim()
        onClose()
        onSave(chosen)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submit()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun render(gfx: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(gfx)
        val top = height / 2 - ROW
        gfx.drawCenteredString(font, title, width / 2, top - ROW * 2, TEXT)
        gfx.drawCenteredString(font, Component.translatable(SIZE_KEY, size.x, size.y, size.z), width / 2, top - ROW, FAINT)
        super.render(gfx, mouseX, mouseY, partialTick)
    }

    // Same as the plan screen: the world does not stop while the player types.
    override fun isPauseScreen(): Boolean = false

    private companion object {
        const val TITLE_KEY = "screen.enderkinesis.capture_name"
        const val NAME_KEY = "screen.enderkinesis.capture_name.name"
        const val SIZE_KEY = "screen.enderkinesis.capture_name.size"
        const val SAVE_KEY = "screen.enderkinesis.capture_name.save"
        const val FIELD_WIDTH = 200
        const val ROW = 24
        const val TEXT = 0xFFFFFF
        const val FAINT = 0xA0A0A0
    }
}
