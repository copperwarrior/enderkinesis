package org.shipwrights.enderkinesis.client

import java.lang.ref.WeakReference
import java.util.WeakHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.entity.WillOTheWispEntity
import org.shipwrights.enderkinesis.registry.EKSounds

private const val BASE_VOLUME = 1.0f

/** Client thread only. */
private val hoverLoops = WeakHashMap<WillOTheWispEntity, WispHoverLoop>()

/** Call each client tick. Starts the hover loop once per wisp. */
fun WillOTheWispEntity.tickWispLoop() {
    if (isSilent || isDeadOrDying) return
    val sounds = Minecraft.getInstance().soundManager
    val existing = hoverLoops[this]
    // A level change stops all sounds and does not call stop() on the instance.
    if (existing != null && !existing.isStopped && sounds.isActive(existing)) return
    hoverLoops[this] = WispHoverLoop(this).also(sounds::play)
}

// The loop holds its wisp weakly; a strong value would keep the WeakHashMap key and its old level alive.
private class WispHoverLoop(wisp: WillOTheWispEntity) : AbstractTickableSoundInstance(
    EKSounds.WISP_HOVER_LOOP.get(),
    SoundSource.AMBIENT,
    SoundInstance.createUnseededRandom(),
) {
    private val ref = WeakReference(wisp)

    init {
        looping = true
        delay = 0
        volume = 0f
        moveTo(wisp)
    }

    // The loop starts at volume 0 and fades in; SoundEngine skips a silent start otherwise.
    override fun canStartSilent(): Boolean = true

    override fun canPlaySound(): Boolean = ref.get()?.isSilent == false

    override fun tick() {
        val wisp = ref.get()
        if (wisp == null || wisp.isRemoved || wisp.isDeadOrDying) {
            stop()
            return
        }
        moveTo(wisp)
        volume = wisp.fade(0f) * BASE_VOLUME
    }

    private fun moveTo(wisp: WillOTheWispEntity) {
        x = wisp.x
        y = wisp.y
        z = wisp.z
    }
}
