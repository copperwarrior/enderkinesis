package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.shipwrights.enderkinesis.dimension.SselithMadness
import org.shipwrights.enderkinesis.registry.EKSounds

object SselithMadnessStageFx {

    private const val FADE_IN_MS = 2000f
    private const val HOLD_MS = 300f
    private const val FADE_OUT_MS = 5500f
    private const val FADE_OUT_START_MS = FADE_IN_MS + HOLD_MS
    private const val TOTAL_MS = FADE_OUT_START_MS + FADE_OUT_MS
    private const val STAGE_VOLUME = 0.5f
    private const val STAGE_PITCH_STEP = 0.02f

    /** The level 7 message tells of a distant fluttering. */
    private const val MOTH_FAR_LEVEL = 7
    private const val MOTH_FAR_DELAY_MS = 2500L
    private const val MOTH_FAR_VOLUME = 0.5f
    private const val NO_MOTH_FAR = Long.MIN_VALUE

    private var startMs = Long.MIN_VALUE / 2
    private var mothFarAtMs = NO_MOTH_FAR

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SselithMadness.STAGE_UP) { buf, _ ->
            val level = buf.readVarInt()
            Minecraft.getInstance().execute { onStageUp(level) }
        }
        ClientTickEvent.CLIENT_POST.register { tickMothFar() }
    }

    private fun tickMothFar() {
        if (mothFarAtMs == NO_MOTH_FAR || Util.getMillis() < mothFarAtMs) return
        mothFarAtMs = NO_MOTH_FAR
        playFlat(EKSounds.SSELITH_MOTH_FAR.get(), MOTH_FAR_VOLUME)
    }

    private fun onStageUp(level: Int) {
        if (level !in SselithMadness.FIRST_ANNOUNCED_LEVEL..SselithMadness.MAX_LEVEL) return
        Minecraft.getInstance().gui.setOverlayMessage(
            Component.translatable("message.enderkinesis.sselith_madness.stage.$level"),
            false,
        )
        startMs = Util.getMillis()
        playFlat(EKSounds.SSELITH_MADNESS_STAGE.get(), STAGE_VOLUME, 1f - STAGE_PITCH_STEP * (level - 2))
        if (level == MOTH_FAR_LEVEL) mothFarAtMs = startMs + MOTH_FAR_DELAY_MS
    }

    /** 0..1 envelope of the stage-up effect. [TomeSummonOverlay] and [SselithMadnessMuffle] read it. */
    fun intensity(): Float {
        val t = (Util.getMillis() - startMs).toFloat()
        if (t < 0f || t >= TOTAL_MS) return 0f
        return smoothstep(0f, FADE_IN_MS, t) * (1f - smoothstep(FADE_OUT_START_MS, TOTAL_MS, t))
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
