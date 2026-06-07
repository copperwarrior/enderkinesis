package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.registry.EKSounds

/**
 * Uncommon Sselith's Repertory ambience — whispering murmurs that surface now and then while the
 * player is in-dimension, the library's equivalent of the Ygann Abyss drones. Same scheduling shape
 * as [YgannAbyssAmbience]: a per-session timer spaces plays a random gap apart (shorter first delay
 * on entry), no new sound starts while one is still playing, and it's non-directional ([SoundSource]
 * AMBIENT, attenuation NONE) so the murmur surrounds the player. The single [EKSounds.SSELITH_MURMUR]
 * event holds all ten samples (5 cues × the `r` companion takes); the sound manager picks one.
 */
object SselithAmbience {

    private const val GAP_MIN: Long = 1400L
    private const val GAP_MAX: Long = 3600L
    private const val FIRST_MIN: Long = 200L
    private const val FIRST_MAX: Long = 900L
    private const val VOLUME: Float = 0.6f
    private const val PITCH_VARIANCE: Float = 0.06f

    private var scheduled = false
    private var nextPlayTick = 0L
    private var current: SoundInstance? = null

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel { level -> tick(level) })
    }

    private fun tick(level: ClientLevel) {
        if (level.dimension() != SselithRepertory.LEVEL_KEY) {
            scheduled = false
            current = null
            return
        }
        val mc = Minecraft.getInstance()
        if (mc.player == null) return
        val now = level.gameTime
        val rng = level.random

        if (!scheduled) {
            nextPlayTick = now + FIRST_MIN + rng.nextInt((FIRST_MAX - FIRST_MIN).toInt())
            scheduled = true
            return
        }
        if (now < nextPlayTick) return
        current?.let { if (mc.soundManager.isActive(it)) return }

        val pitch = 1.0f + (rng.nextFloat() - 0.5f) * 2.0f * PITCH_VARIANCE
        val sound = SimpleSoundInstance(
            EKSounds.SSELITH_MURMUR.get().location, SoundSource.AMBIENT, VOLUME, pitch, rng,
            false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true,
        )
        mc.soundManager.play(sound)
        current = sound
        nextPlayTick = now + GAP_MIN + rng.nextInt((GAP_MAX - GAP_MIN).toInt())
    }
}
