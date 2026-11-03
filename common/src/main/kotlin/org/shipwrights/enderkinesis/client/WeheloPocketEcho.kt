package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.audio.Channel
import net.minecraft.client.Minecraft
import net.minecraft.client.sounds.ChannelAccess
import org.lwjgl.openal.AL10
import org.lwjgl.openal.AL11
import org.lwjgl.openal.ALC
import org.lwjgl.openal.EXTEfx
import org.shipwrights.enderkinesis.dimension.WeheloPocket
import org.shipwrights.enderkinesis.mixin.ChannelAccessor

/** An echoing reverb on all game audio while the camera is in a Wehelo pocket. */
object WeheloPocketEcho {

    private val LOG = org.slf4j.LoggerFactory.getLogger("EnderkinesisWeheloPocketEcho")

    private var effect = 0
    private var slot = 0
    private var failed = false
    private var appliedActive = false

    /** Vanilla drives channels on the sound executor thread, so that thread reads this. */
    @Volatile private var activeSlot = EXTEfx.AL_EFFECTSLOT_NULL

    @JvmStatic
    fun tick(channels: ChannelAccess) {
        val active = Minecraft.getInstance().level?.dimension() == WeheloPocket.LEVEL_KEY
        if (active == appliedActive) return
        if (active && slot == 0 && !create()) return
        appliedActive = active
        activeSlot = if (active) slot else EXTEfx.AL_EFFECTSLOT_NULL
        channels.executeOnChannels { stream -> stream.forEach(::attach) }
    }

    /** Sources are pooled, so a new sound always sets its send: else a reused source keeps the echo outside. */
    @JvmStatic
    fun applyToNew(channel: Channel) {
        if (slot != 0) attach(channel)
    }

    @JvmStatic
    fun release() {
        activeSlot = EXTEfx.AL_EFFECTSLOT_NULL
        if (slot != 0) EXTEfx.alDeleteAuxiliaryEffectSlots(slot)
        if (effect != 0) EXTEfx.alDeleteEffects(effect)
        slot = 0
        effect = 0
        failed = false
        appliedActive = false
    }

    private fun create(): Boolean {
        if (failed || !ALC.getCapabilities().ALC_EXT_EFX) return false
        AL10.alGetError()
        effect = EXTEfx.alGenEffects()
        EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_EAXREVERB)
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            LOG.warn("OpenAL has no EAX reverb; Wehelo pockets stay dry")
            EXTEfx.alDeleteEffects(effect)
            effect = 0
            failed = true
            return false
        }
        fun set(param: Int, value: Float) = EXTEfx.alEffectf(effect, param, value)
        set(EXTEfx.AL_EAXREVERB_DENSITY, 1.0f)
        // Low diffusion keeps the reflections apart, so they read as echoes.
        set(EXTEfx.AL_EAXREVERB_DIFFUSION, DIFFUSION)
        set(EXTEfx.AL_EAXREVERB_GAIN, GAIN)
        set(EXTEfx.AL_EAXREVERB_GAINHF, GAIN_HF)
        set(EXTEfx.AL_EAXREVERB_DECAY_TIME, DECAY_SECONDS)
        set(EXTEfx.AL_EAXREVERB_DECAY_HFRATIO, DECAY_HF_RATIO)
        set(EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN, REFLECTIONS_GAIN)
        set(EXTEfx.AL_EAXREVERB_REFLECTIONS_DELAY, REFLECTIONS_DELAY)
        set(EXTEfx.AL_EAXREVERB_LATE_REVERB_GAIN, LATE_GAIN)
        set(EXTEfx.AL_EAXREVERB_LATE_REVERB_DELAY, LATE_DELAY)
        set(EXTEfx.AL_EAXREVERB_ECHO_TIME, ECHO_TIME)
        set(EXTEfx.AL_EAXREVERB_ECHO_DEPTH, ECHO_DEPTH)
        slot = EXTEfx.alGenAuxiliaryEffectSlots()
        // The slot copies the effect values on attach, so set them all first.
        EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect)
        return true
    }

    private fun attach(channel: Channel) {
        val source = (channel as ChannelAccessor).`enderkinesis$getSource`()
        AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, activeSlot, 0, EXTEfx.AL_FILTER_NULL)
    }

    private const val DIFFUSION = 0.45f
    private const val GAIN = 0.6f
    private const val GAIN_HF = 0.75f
    private const val DECAY_SECONDS = 7.0f
    private const val DECAY_HF_RATIO = 0.55f
    private const val REFLECTIONS_GAIN = 1.2f
    /** Seconds; at most 0.3. */
    private const val REFLECTIONS_DELAY = 0.12f
    private const val LATE_GAIN = 1.6f
    /** Seconds; at most 0.1. */
    private const val LATE_DELAY = 0.1f
    /** Seconds; 0.075 to 0.25. The longest gives the most distinct repeats. */
    private const val ECHO_TIME = 0.25f
    private const val ECHO_DEPTH = 0.7f
}
