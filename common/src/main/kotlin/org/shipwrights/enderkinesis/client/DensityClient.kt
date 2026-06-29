package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import dev.architectury.event.events.client.ClientGuiEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import org.shipwrights.enderkinesis.item.DensityManager
import org.shipwrights.enderkinesis.item.DensityNetwork

/** Client-side state machine + HUD for the Staff of Density.
 *
 *  Same structure as [ScalesClient]:
 *  - Mouse-Y pitch delta is clamped through [consumePitchDelta] inside
 *    [LocalPlayerDensityTurnMixin][org.shipwrights.enderkinesis.mixin.LocalPlayerDensityTurnMixin]
 *    (`@ModifyVariable` on the pitch arg) — the camera, the slider, and the
 *    network stream all share a single clamped delta, so the camera stops
 *    turning at the same instant the slider hits its end-stop.
 *  - [streamTickIfActive] runs once per game tick from
 *    [org.shipwrights.enderkinesis.item.StaffOfDensityItem.onUseTick] and
 *    pushes the current multiplier to the server when it has changed, so the
 *    mass adjusts live during the drag.
 *  - [endSession] called from `releaseUsing` — flushes the final value and
 *    clears local state.
 *
 *  The right-side HUD bar is a debug overlay — not part of the spec, kept
 *  for now as a visual readout of the live multiplier. */
object DensityClient {

    /** Pixels of mouse-Y travel to span the full 0.5× → 2× range (one side of centre).
     *  At the long-range setting we picked: ~800px from 1× → 2×, same in the other
     *  direction. Multiplier mapping is log-symmetric so 0.5× and 2× sit equally far
     *  from the 1× midpoint. */
    private const val DRAG_HALF_RANGE_PX: Double = 800.0
    private val MUL_LOG_HALF: Double = kotlin.math.ln(DensityManager.MAX_MULTIPLIER)

    @Volatile private var activeShipId: Long? = null
    /** Drag distance from the *anchor* multiplier in pixels. Positive = up = heavier. */
    @Volatile private var dragPixels: Double = 0.0
    /** The multiplier the drag is measured FROM — server-acknowledged on session start.
     *  Initial value 1.0; corrected by [onStateFromServer] when the BEGIN echo arrives. */
    @Volatile private var anchorMultiplier: Double = 1.0
    /** Last multiplier we streamed to the server. Used to skip duplicate packets
     *  when the mouse hasn't moved between ticks. */
    @Volatile private var lastStreamedMultiplier: Double = Double.NaN

    /** Wired during client init. Registers the S2C STATE receiver and the HUD
     *  overlay. The per-tick stream lives in [StaffOfDensityItem.onUseTick]. */
    @JvmStatic
    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, DensityNetwork.STATE) { buf, _ ->
            val shipId = buf.readLong()
            val mul = buf.readDouble()
            onStateFromServer(shipId, mul)
        }
        ClientGuiEvent.RENDER_HUD.register(::renderHud)
    }

    @JvmStatic
    fun beginSession(shipId: Long) {
        activeShipId = shipId
        dragPixels = 0.0
        // NaN gates the live stream until STATE arrives — otherwise the first
        // onUseTick would send mul=1.0 (default seed) and snap an existing
        // multiplier before the real anchor lands.
        anchorMultiplier = Double.NaN
        lastStreamedMultiplier = Double.NaN
    }

    @JvmStatic
    fun endSession() {
        val shipId = activeShipId ?: return
        if (!anchorMultiplier.isNaN()) {
            sendCommit(shipId, currentMultiplier())
        }
        activeShipId = null
        dragPixels = 0.0
        anchorMultiplier = Double.NaN
        lastStreamedMultiplier = Double.NaN
    }

    private fun sendCommit(shipId: Long, mul: Double) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(shipId)
        buf.writeDouble(mul)
        NetworkManager.sendToServer(DensityNetwork.COMMIT, buf)
    }

    @JvmStatic
    fun isActive(): Boolean {
        if (activeShipId == null) return false
        // Auto-end if the player stopped using the staff (e.g. died, switched slot).
        val player = Minecraft.getInstance().player
        if (player == null || !player.isUsingItem) {
            activeShipId = null
            return false
        }
        return true
    }

    /** Called from [LocalPlayerDensityTurnMixin] as a `@ModifyVariable` on the
     *  pitch arg. Returns the portion of `pitchDelta` that still fits in the
     *  slider's remaining budget; the mixin substitutes this clamped value
     *  back into the vanilla `Entity.turn` call so the camera, the slider, and
     *  the network commit all move from the same (clamped) source. Mouse-up =
     *  heavier, so dragPixels increases when pitchDelta is negative. */
    @JvmStatic
    fun consumePitchDelta(pitchDelta: Double): Double {
        if (!isActive()) return pitchDelta
        val oldDrag = dragPixels
        val newDrag = (oldDrag - pitchDelta).coerceIn(-DRAG_HALF_RANGE_PX, DRAG_HALF_RANGE_PX)
        dragPixels = newDrag
        return oldDrag - newDrag
    }

    /** Per-tick streaming hook called from [StaffOfDensityItem.onUseTick] on
     *  the client. Sends the current multiplier to the server when it has
     *  moved at all since the previous send. */
    @JvmStatic
    fun streamTickIfActive() {
        val shipId = activeShipId ?: return
        if (!isActive()) return
        // Gate on STATE arrival — see beginSession comment.
        if (anchorMultiplier.isNaN()) return
        val mul = currentMultiplier()
        if (!lastStreamedMultiplier.isNaN() && kotlin.math.abs(mul - lastStreamedMultiplier) < 1e-4) return
        sendCommit(shipId, mul)
        lastStreamedMultiplier = mul
    }

    private fun onStateFromServer(shipId: Long, multiplier: Double) {
        if (activeShipId != shipId) return
        anchorMultiplier = multiplier.coerceIn(DensityManager.MIN_MULTIPLIER, DensityManager.MAX_MULTIPLIER)
    }

    /** Convert the accumulated drag to a multiplier. Log-symmetric:
     *    drag = +halfRange → multiplier = anchor × MAX  (clamped to MAX overall)
     *    drag = -halfRange → multiplier = anchor / MAX  (clamped to MIN overall)
     *  The clamps preserve user intent — they can't drag past the limits. */
    private fun currentMultiplier(): Double {
        // NaN-safe display value while BEGIN→STATE is in flight; the stream
        // is gated separately so this only feeds the HUD.
        if (anchorMultiplier.isNaN()) return 1.0
        val ratio = dragPixels / DRAG_HALF_RANGE_PX  // -1..1
        val logMul = kotlin.math.ln(anchorMultiplier) + ratio * MUL_LOG_HALF
        return kotlin.math.exp(logMul).coerceIn(DensityManager.MIN_MULTIPLIER, DensityManager.MAX_MULTIPLIER)
    }

    private fun renderHud(gfx: GuiGraphics, partialTick: Float) {
        if (!isActive()) return
        val mc = Minecraft.getInstance()
        val w = gfx.guiWidth()
        val h = gfx.guiHeight()
        val mul = currentMultiplier()

        // Vertical bar on the right side of the screen.
        val barH = 96
        val barW = 6
        val barX = w - 28
        val barY = (h - barH) / 2

        // Track
        gfx.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xC0202020.toInt())
        gfx.fill(barX, barY, barX + barW, barY + barH, 0xFF505050.toInt())
        // 1× midpoint reference line
        val midY = barY + barH / 2
        gfx.fill(barX - 2, midY, barX + barW + 2, midY + 1, 0xFFE0E0E0.toInt())

        // Marker: log-linear position from MIN..MAX, current multiplier in between.
        val ratio = (kotlin.math.ln(mul) - kotlin.math.ln(DensityManager.MIN_MULTIPLIER)) /
            (kotlin.math.ln(DensityManager.MAX_MULTIPLIER) - kotlin.math.ln(DensityManager.MIN_MULTIPLIER))
        // ratio: 0 at bottom (MIN=0.5×), 1 at top (MAX=2×). Invert for Y (top = small Y).
        val markerY = barY + ((1.0 - ratio) * barH).toInt()
        gfx.fill(barX - 3, markerY - 1, barX + barW + 3, markerY + 2, 0xFFFFC040.toInt())

        // Numeric readout above the bar
        val text = Component.literal(String.format("%.2f×", mul))
        val tw = mc.font.width(text)
        gfx.drawString(mc.font, text, barX + (barW - tw) / 2, barY - 12, 0xFFFFC040.toInt(), true)
        // Label below
        val label = Component.translatable("item.enderkinesis.staff_of_density.label")
        val lw = mc.font.width(label)
        gfx.drawString(mc.font, label, barX + (barW - lw) / 2, barY + barH + 4, 0xFFC0C0C0.toInt(), true)

        // Reset color shader (defensive — other HUD elements might rely on default)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
    }
}
