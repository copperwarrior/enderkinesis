package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.audio.Channel
import net.minecraft.client.sounds.ChannelAccess
import net.minecraft.util.Mth
import org.lwjgl.openal.AL10
import org.lwjgl.openal.ALC
import org.lwjgl.openal.EXTEfx
import org.shipwrights.enderkinesis.mixin.ChannelAccessor

/** Low-pass muffle on all game audio. It follows the [SselithMadnessStageFx] envelope. */
object SselithMadnessMuffle {

    /** High-frequency gain at full effect. 1 = no muffle. */
    private const val MIN_GAIN_HF = 0.05f

    private var filter = 0
    private var appliedGainHf = 1f

    /** Vanilla drives channels on the sound executor thread, so that thread reads this. */
    @Volatile private var activeFilter = EXTEfx.AL_FILTER_NULL

    @JvmStatic
    fun tick(channels: ChannelAccess) {
        val gainHf = Mth.lerp(SselithMadnessStageFx.intensity(), 1f, MIN_GAIN_HF)
        if (gainHf == appliedGainHf) return
        if (!ALC.getCapabilities().ALC_EXT_EFX) return
        if (filter == 0) {
            filter = EXTEfx.alGenFilters()
            EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS)
        }
        EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, gainHf)
        appliedGainHf = gainHf
        activeFilter = if (gainHf < 1f) filter else EXTEfx.AL_FILTER_NULL
        // A source copies the filter values on attach, so attach again after each change.
        channels.executeOnChannels { stream -> stream.forEach(::attach) }
    }

    @JvmStatic
    fun applyToNew(channel: Channel) {
        if (activeFilter != EXTEfx.AL_FILTER_NULL) attach(channel)
    }

    @JvmStatic
    fun release() {
        activeFilter = EXTEfx.AL_FILTER_NULL
        if (filter != 0) EXTEfx.alDeleteFilters(filter)
        filter = 0
        appliedGainHf = 1f
    }

    private fun attach(channel: Channel) {
        val source = (channel as ChannelAccessor).`enderkinesis$getSource`()
        AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, activeFilter)
    }
}
