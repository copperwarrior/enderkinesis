package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.shipObjectWorld

object ConcealmentSounds {

    private const val HUM_MAX_VOLUME: Float = 0.4f

    private class ShipAudio(
        var shipId: Long,
        var cloaking: Boolean,
        var oneShot: SoundInstance? = null,
        var hum: HumLoop? = null,
    )

    /** Client thread only. */
    private val audioByShip: MutableMap<Long, ShipAudio> = HashMap()

    fun onServerUpdate(shipId: Long, cloaking: Boolean) {
        val existing = audioByShip[shipId]
        if (existing?.cloaking == cloaking) return
        if (existing == null && !cloaking) return
        val pos = shipPosition(shipId) ?: return
        val audio = existing ?: ShipAudio(shipId, cloaking).also { audioByShip[shipId] = it }
        audio.cloaking = cloaking
        val sounds = Minecraft.getInstance().soundManager
        audio.oneShot?.let(sounds::stop)
        val event = if (cloaking) EKSounds.CONCEALMENT_CLOAK.get() else EKSounds.CONCEALMENT_DECLOAK.get()
        val oneShot = SimpleSoundInstance(
            event, SoundSource.PLAYERS, 1f, 1f, SoundInstance.createUnseededRandom(), pos.x(), pos.y(), pos.z(),
        )
        sounds.play(oneShot)
        audio.oneShot = oneShot
        if (cloaking && audio.hum == null) {
            val hum = HumLoop(audio, EKSounds.CONCEALMENT_HUM_LOOP.get(), pos)
            sounds.play(hum)
            audio.hum = hum
        }
    }

    fun onTransfer(fromShipId: Long, toShipId: Long) {
        val audio = audioByShip.remove(fromShipId) ?: return
        audio.shipId = toShipId
        audioByShip[toShipId] = audio
    }

    fun reset() {
        val sounds = Minecraft.getInstance().soundManager
        for (audio in audioByShip.values) {
            audio.oneShot?.let(sounds::stop)
            audio.hum?.let(sounds::stop)
        }
        audioByShip.clear()
    }

    private fun shipPosition(shipId: Long): Vector3dc? {
        val level = Minecraft.getInstance().level ?: return null
        return level.shipObjectWorld.allShips.getById(shipId)?.transform?.positionInWorld
    }

    private class HumLoop(private val audio: ShipAudio, event: SoundEvent, pos: Vector3dc) :
        AbstractTickableSoundInstance(event, SoundSource.PLAYERS, SoundInstance.createUnseededRandom()) {

        init {
            looping = true
            delay = 0
            volume = 0f
            moveTo(pos)
        }

        // The cloak fade starts at progress 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val pos = shipPosition(audio.shipId)
            val progress = ClientShipCloakingState.getProgress(audio.shipId)
            if (pos == null || (progress <= 0f && !audio.cloaking)) {
                audioByShip.remove(audio.shipId, audio)
                stop()
                return
            }
            moveTo(pos)
            volume = HUM_MAX_VOLUME * progress
        }

        private fun moveTo(pos: Vector3dc) {
            x = pos.x()
            y = pos.y()
            z = pos.z()
        }
    }
}
