package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.logging.LogUtils
import dev.architectury.event.EventResult
import dev.architectury.event.events.client.ClientGuiEvent
import dev.architectury.event.events.client.ClientRawInputEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import org.shipwrights.enderkinesis.item.ScalesManager
import org.shipwrights.enderkinesis.item.ScalesNetwork
import org.shipwrights.enderkinesis.registry.EKItems

/** Client-side state machine + HUD for the Staff of Scales.
 *
 *  Mouse-Y delta is *clamped* through [consumePitchDelta] inside
 *  [LocalPlayerScalesTurnMixin][org.shipwrights.enderkinesis.mixin.LocalPlayerScalesTurnMixin]
 *  (a `@ModifyVariable` on the pitch arg). The mixin returns only the portion
 *  of the delta that still fits in the slider's remaining ±[DRAG_HALF_RANGE_PX]
 *  budget, so the camera, the slider, and the network stream all move
 *  together — the camera stops turning at the same instant the slider hits its
 *  end-stop.
 *
 *  Live scale is streamed to the server from [StaffOfScalesItem.onUseTick] (so
 *  it fires exactly when the staff is held, no extra tick listeners). Release
 *  flushes the final value. The drag accumulator is asymmetric because the
 *  staff's limits are asymmetric (1.5× max, 0.25× min): up = linear toward
 *  1.5, down = linear toward 0.25 — both reach their extreme at the same pixel
 *  distance.
 *
 *  The right-side HUD bar is a debug overlay — not part of the spec, kept
 *  for now as a visual readout of the live scale. */
object ScalesClient {

    private val LOG = LogUtils.getLogger()

    /** Pixels of mouse-Y travel to span half the range (centre to either extreme).
     *  This is BOTH the slider range AND the camera-rotation budget — the
     *  mixin clamps the camera pitch delta to fit in this same envelope. */
    private const val DRAG_HALF_RANGE_PX: Double = 800.0

    @Volatile private var activeShipId: Long? = null
    @Volatile private var dragPixels: Double = 0.0
    /** Server-acknowledged scale at session start. The drag is measured FROM here so
     *  small mouse motion doesn't snap an already-scaled ship back to 1.0. */
    @Volatile private var anchorScale: Double = 1.0
    /** Last scale we streamed to the server. Used to skip duplicate packets when
     *  the mouse hasn't moved between ticks. */
    @Volatile private var lastStreamedScale: Double = Double.NaN

    @JvmStatic
    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, ScalesNetwork.STATE) { buf, _ ->
            val shipId = buf.readLong()
            val scale = buf.readDouble()
            onStateFromServer(shipId, scale)
        }
        ClientGuiEvent.RENDER_HUD.register(::renderHud)
        ClientRawInputEvent.MOUSE_CLICKED_PRE.register(
            ClientRawInputEvent.MouseClicked { mc, button, action, _ -> onMouseClicked(mc, button, action) }
        )
    }

    /** Left-press while the staff is in the main hand triggers a server-side
     *  reset tween (1 s lerp back to scale 1.0). The server raycasts itself
     *  for the target; we just fire the packet and swing the arm. Vanilla
     *  click handling is suppressed via [EventResult.interruptFalse] so the
     *  player doesn't accidentally start mining at the same time.
     *
     *  Click button codes (GLFW): 0 = left, 1 = right, 2 = middle.
     *  Click action codes (GLFW): 0 = release, 1 = press. */
    private fun onMouseClicked(mc: Minecraft, button: Int, action: Int): EventResult {
        if (button != 0 || action != 1) return EventResult.pass()
        val player = mc.player ?: return EventResult.pass()
        if (player.mainHandItem.item !== EKItems.STAFF_OF_SCALES.get()) return EventResult.pass()
        // Only fire when no UI is on top — left-click in a menu shouldn't
        // accidentally reset whatever ship the camera is pointing at behind it.
        if (mc.screen != null) return EventResult.pass()
        val buf = FriendlyByteBuf(Unpooled.buffer())
        NetworkManager.sendToServer(ScalesNetwork.RESET, buf)
        player.swing(InteractionHand.MAIN_HAND)
        LOG.info("[Scales] left-click → RESET sent")
        return EventResult.interruptFalse()
    }

    @JvmStatic
    fun beginSession(shipId: Long) {
        activeShipId = shipId
        dragPixels = 0.0
        // NaN gates the live stream until STATE arrives. Without this gate
        // the first onUseTick would send scale=anchorScale=1.0 (default) and
        // teleport-snap an already-resized ship back to 1× before the real
        // anchor lands a few ms later.
        anchorScale = Double.NaN
        lastStreamedScale = Double.NaN
        LOG.info("[Scales] beginSession shipId={}", shipId)
    }

    @JvmStatic
    fun endSession() {
        val shipId = activeShipId
        LOG.info("[Scales] endSession shipId={} dragPixels={} anchorScale={}", shipId, dragPixels, anchorScale)
        if (shipId == null) return
        // Skip the final flush if STATE never arrived — currentScale() would
        // be NaN and the server can't apply that. Stream-tick will have done
        // any in-drag work; releasing before STATE just means no change made.
        if (!anchorScale.isNaN()) {
            sendCommit(shipId, currentScale())
        }
        activeShipId = null
        dragPixels = 0.0
        anchorScale = Double.NaN
        lastStreamedScale = Double.NaN
    }

    private fun sendCommit(shipId: Long, scale: Double) {
        // Server resolves the target via its cached LoadedServerShip from
        // BEGIN — only the scale value goes over the wire.
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeDouble(scale)
        NetworkManager.sendToServer(ScalesNetwork.COMMIT, buf)
        LOG.info("[Scales] sendCommit (cached server-side) shipId={} scale={}", shipId, scale)
    }

    @JvmStatic
    fun isActive(): Boolean {
        if (activeShipId == null) return false
        val player = Minecraft.getInstance().player
        if (player == null || !player.isUsingItem) {
            activeShipId = null
            return false
        }
        return true
    }

    /** Called from [LocalPlayerScalesTurnMixin] as a `@ModifyVariable` on the
     *  pitch arg. Returns the portion of `pitchDelta` that still fits in the
     *  slider's remaining budget; the mixin substitutes this clamped value
     *  back into the vanilla `Entity.turn` call so the camera, the slider, and
     *  the network commit all move from the same (clamped) source.
     *
     *  Vanilla convention: positive pitchDelta = look down. Up = bigger scale,
     *  so dragPixels increases when pitchDelta is negative — handled below by
     *  subtraction. */
    @JvmStatic
    fun consumePitchDelta(pitchDelta: Double): Double {
        if (!isActive()) return pitchDelta
        val oldDrag = dragPixels
        val newDrag = (oldDrag - pitchDelta).coerceIn(-DRAG_HALF_RANGE_PX, DRAG_HALF_RANGE_PX)
        dragPixels = newDrag
        val applied = oldDrag - newDrag
        // 20% sample rate so the log is informative but not overwhelming during drag.
        if ((System.nanoTime() shr 24) and 0x3L == 0L) {
            LOG.info("[Scales] consumePitchDelta raw={} applied={} dragPixels={} scale={}",
                pitchDelta, applied, dragPixels, currentScale())
        }
        return applied
    }

    /** Per-tick streaming hook called from [StaffOfScalesItem.onUseTick] on
     *  the client. Sends the current scale to the server when it has moved at
     *  all since the previous send. */
    @JvmStatic
    fun streamTickIfActive() {
        val shipId = activeShipId ?: return
        if (!isActive()) return
        // Don't stream until the server's STATE has set anchorScale — otherwise
        // we'd send scale=1.0 (the default seed) and snap the ship away from
        // its true current scale during the BEGIN→STATE round-trip.
        if (anchorScale.isNaN()) return
        val scale = currentScale()
        if (!lastStreamedScale.isNaN() && kotlin.math.abs(scale - lastStreamedScale) < 1e-4) return
        sendCommit(shipId, scale)
        lastStreamedScale = scale
    }

    private fun onStateFromServer(shipId: Long, scale: Double) {
        if (activeShipId != shipId) {
            LOG.info("[Scales] onStateFromServer: shipId={} but activeShipId={} (skip)", shipId, activeShipId)
            return
        }
        anchorScale = scale.coerceIn(ScalesManager.MIN_SCALE, ScalesManager.MAX_SCALE)
        LOG.info("[Scales] onStateFromServer shipId={} anchorScale={}", shipId, anchorScale)
    }

    /** Asymmetric linear interpolation. Centre (ratio=0) sits at the anchor scale.
     *  Up half lerps from anchor to MAX_SCALE, down half lerps from anchor to
     *  MIN_SCALE. Final value clamped to absolute limits.
     *
     *  Returns 1.0 while the BEGIN→STATE round-trip is in flight (anchorScale
     *  still NaN) so the HUD has something safe to draw — the live stream
     *  is gated separately and won't send during this window. */
    private fun currentScale(): Double {
        if (anchorScale.isNaN()) return 1.0
        val ratio = dragPixels / DRAG_HALF_RANGE_PX  // -1..1
        val target = if (ratio >= 0.0) {
            anchorScale + ratio * (ScalesManager.MAX_SCALE - anchorScale)
        } else {
            anchorScale + ratio * (anchorScale - ScalesManager.MIN_SCALE)
        }
        return target.coerceIn(ScalesManager.MIN_SCALE, ScalesManager.MAX_SCALE)
    }

    private fun renderHud(gfx: GuiGraphics, partialTick: Float) {
        if (!isActive()) return
        val mc = Minecraft.getInstance()
        val w = gfx.guiWidth()
        val h = gfx.guiHeight()
        val scale = currentScale()

        val barH = 96
        val barW = 6
        val barX = w - 28
        val barY = (h - barH) / 2

        // Track
        gfx.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xC0202020.toInt())
        gfx.fill(barX, barY, barX + barW, barY + barH, 0xFF505050.toInt())
        // 1× reference line at the visual centre — works because MIN < 1 < MAX.
        val ratioOf = { v: Double ->
            // Same asymmetric linear math used for the marker, so the 1× line
            // lands wherever 1.0 corresponds to on the bar's two halves.
            if (v >= 1.0) (v - 1.0) / (ScalesManager.MAX_SCALE - 1.0) * 0.5 + 0.5
            else 0.5 - (1.0 - v) / (1.0 - ScalesManager.MIN_SCALE) * 0.5
        }
        val oneY = barY + ((1.0 - ratioOf(1.0)) * barH).toInt()
        gfx.fill(barX - 2, oneY, barX + barW + 2, oneY + 1, 0xFFE0E0E0.toInt())

        // Marker
        val markerY = barY + ((1.0 - ratioOf(scale)) * barH).toInt()
        gfx.fill(barX - 3, markerY - 1, barX + barW + 3, markerY + 2, 0xFF40C0FF.toInt())

        val text = Component.literal(String.format("%.2f×", scale))
        val tw = mc.font.width(text)
        gfx.drawString(mc.font, text, barX + (barW - tw) / 2, barY - 12, 0xFF40C0FF.toInt(), true)
        val label = Component.translatable("item.enderkinesis.staff_of_scales.label")
        val lw = mc.font.width(label)
        gfx.drawString(mc.font, label, barX + (barW - lw) / 2, barY + barH + 4, 0xFFC0C0C0.toInt(), true)

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
    }
}
