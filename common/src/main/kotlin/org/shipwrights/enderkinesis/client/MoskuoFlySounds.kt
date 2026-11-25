package org.shipwrights.enderkinesis.client

import java.lang.ref.WeakReference
import java.util.WeakHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import org.shipwrights.enderkinesis.entity.MoskuoEntity
import org.shipwrights.enderkinesis.registry.EKSounds

private const val MIN_VOLUME = 0.6f
private const val MAX_VOLUME = 1.0f
private const val MIN_PITCH = 0.9f
private const val MAX_PITCH = 1.1f
private const val FULL_SPEED = 0.3f

/** Client thread only. */
private val flyLoops = WeakHashMap<MoskuoEntity, MoskuoFlyLoop>()

/** Call each client tick. Starts the fly loop, or swaps it to the fat loop when [MoskuoEntity.isFed] changes. */
fun MoskuoEntity.tickFlyLoop() {
    if (isPerched || isSilent || !isAlive) return
    val sounds = Minecraft.getInstance().soundManager
    val existing = flyLoops[this]
    // A level change stops all sounds and does not call stop() on the instance.
    if (existing != null && existing.fat == isFed && !existing.isStopped && sounds.isActive(existing)) return
    existing?.let(sounds::stop)
    flyLoops[this] = MoskuoFlyLoop(this, isFed).also(sounds::play)
}

// The loop holds its Moskuo weakly; a strong value would keep the WeakHashMap key and its old level alive.
private class MoskuoFlyLoop(moskuo: MoskuoEntity, val fat: Boolean) : AbstractTickableSoundInstance(
    (if (fat) EKSounds.MOSKUO_FLY_FAT else EKSounds.MOSKUO_FLY).get(),
    SoundSource.HOSTILE,
    SoundInstance.createUnseededRandom(),
) {
    private val ref = WeakReference(moskuo)

    init {
        looping = true
        delay = 0
        volume = MIN_VOLUME
        moveTo(moskuo)
    }

    override fun canPlaySound(): Boolean = ref.get()?.isSilent == false

    override fun tick() {
        val moskuo = ref.get()
        if (moskuo == null || moskuo.isRemoved || !moskuo.isAlive || moskuo.isPerched || moskuo.isFed != fat) {
            stop()
            return
        }
        moveTo(moskuo)
        // Shaped by speed like the vanilla BeeSoundInstance, with a floor so a hovering Moskuo still buzzes.
        val speed = Mth.clamp(moskuo.deltaMovement.length().toFloat() / FULL_SPEED, 0f, 1f)
        volume = Mth.lerp(speed, MIN_VOLUME, MAX_VOLUME)
        pitch = Mth.lerp(speed, MIN_PITCH, MAX_PITCH)
    }

    private fun moveTo(moskuo: MoskuoEntity) {
        x = moskuo.x
        y = moskuo.y
        z = moskuo.z
    }
}
