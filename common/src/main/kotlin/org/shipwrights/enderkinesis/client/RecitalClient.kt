package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import dev.architectury.event.events.client.ClientGuiEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import org.shipwrights.enderkinesis.item.RecitalHelper
import org.shipwrights.enderkinesis.item.RecitalNetwork
import org.shipwrights.enderkinesis.registry.EKItems

/** Client side of the Staff of Recital: capture shift+scroll while the staff
 *  is held, send the active-index delta to the server, and render a small
 *  icon overlay near the hotbar showing the newly-selected tome (fades out
 *  ~1.2s after the last cycle). */
object RecitalClient {

    private const val OVERLAY_DURATION_MS: Long = 1200L

    @Volatile private var lastCycleAtMs: Long = 0L
    @Volatile private var lastSelectedItem: net.minecraft.world.item.Item? = null

    @JvmStatic
    fun init() {
        ClientGuiEvent.RENDER_HUD.register(::renderHud)
    }

    /** Called from [LocalPlayerRecitalScrollMixin][org.shipwrights.enderkinesis.mixin.MouseHandlerRecitalScrollMixin]
     *  when the player scrolls while shift is held AND a staff is in either
     *  hand. `verticalScroll` follows GLFW's convention: positive = scroll up.
     *  We map up = next, down = previous. Returns true if we consumed the
     *  scroll (mixin cancels vanilla hotbar swap in that case). */
    @JvmStatic
    fun tryConsumeScroll(verticalScroll: Double): Boolean {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        // Find the staff in either hand.
        val (hand, staff) = findStaff(player) ?: return false
        val tomes = RecitalHelper.readTomes(staff)
        if (tomes.size < 2) return false  // nothing to cycle through

        val delta = if (verticalScroll > 0) -1 else 1  // up = previous in scroll → previous tome
        // Compute the next index purely client-side for the overlay; server
        // does the same math authoritatively when the packet arrives.
        val current = RecitalHelper.getActiveIndex(staff).coerceIn(0, tomes.size - 1)
        val next = (((current + delta) % tomes.size) + tomes.size) % tomes.size
        lastSelectedItem = tomes[next].item
        lastCycleAtMs = System.currentTimeMillis()

        // Send to server — only the server's NBT mutation is authoritative;
        // the held stack is server-owned.
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeInt(delta)
        NetworkManager.sendToServer(RecitalNetwork.SET_ACTIVE, buf)

        // Cheap cycle-feedback sound. Avoid the bow-loose click (too loud) —
        // the bundle sound is the closest fit.
        mc.level?.playLocalSound(
            player.x, player.y, player.z,
            SoundEvents.UI_BUTTON_CLICK.value(),
            net.minecraft.sounds.SoundSource.PLAYERS,
            0.5f, 1.6f, false,
        )
        return true
    }

    private fun findStaff(player: net.minecraft.world.entity.player.Player): Pair<InteractionHand, net.minecraft.world.item.ItemStack>? {
        for (hand in arrayOf(InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND)) {
            val held = player.getItemInHand(hand)
            if (held.item == EKItems.STAFF_OF_RECITAL.get()) return hand to held
        }
        return null
    }

    private fun renderHud(gfx: GuiGraphics, partialTick: Float) {
        val item = lastSelectedItem ?: return
        val elapsed = System.currentTimeMillis() - lastCycleAtMs
        if (elapsed >= OVERLAY_DURATION_MS) {
            lastSelectedItem = null
            return
        }
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        // Only render while the staff itself is held — pulling it out of hand
        // clears the overlay.
        if (player.mainHandItem.item != EKItems.STAFF_OF_RECITAL.get() &&
            player.offhandItem.item != EKItems.STAFF_OF_RECITAL.get()) {
            lastSelectedItem = null
            return
        }

        // Fade out over the last 300ms.
        val fadeStart = OVERLAY_DURATION_MS - 300L
        val alpha = if (elapsed < fadeStart) 1f
            else 1f - ((elapsed - fadeStart).toFloat() / 300f).coerceIn(0f, 1f)

        val w = gfx.guiWidth()
        val h = gfx.guiHeight()
        // Position: just above the hotbar centre.
        val iconX = w / 2 - 8
        val iconY = h - 22 - 24

        RenderSystem.enableBlend()
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha)
        val stack = net.minecraft.world.item.ItemStack(item)
        gfx.renderItem(stack, iconX, iconY)
        // Label under the icon
        val name = stack.hoverName
        val tw = mc.font.width(name)
        val labelColor = ((alpha * 255).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
        gfx.drawString(mc.font, name, w / 2 - tw / 2, iconY + 18, labelColor, true)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        RenderSystem.disableBlend()
    }
}
