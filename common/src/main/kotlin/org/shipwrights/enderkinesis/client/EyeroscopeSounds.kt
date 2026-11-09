package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import org.joml.Vector3d
import org.shipwrights.enderkinesis.blockentity.EyeroscopeBlockEntity
import org.shipwrights.enderkinesis.blockentity.EyeroscopeClientRegistry
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.util.blockCentreInWorld

object EyeroscopeSounds {

    private const val IDLE_VOLUME: Float = 1f
    private const val FADE_TICKS: Int = 20
    private const val SCAN_INTERVAL_TICKS: Int = 10
    /** A small margin on the audible range, so the loop is at volume before the player hears it. */
    private const val START_RANGE: Double = EKSounds.EYEROSCOPE_IDLE_RANGE + 2.0
    private const val START_RANGE_SQR: Double = START_RANGE * START_RANGE

    /** Client thread only. */
    private val loops = HashMap<EyeroscopeBlockEntity, IdleLoop>()
    private val scratch = Vector3d()
    private var scanCooldown = 0

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        loops.values.forEach(sounds::stop)
        loops.clear()
    }

    private fun tick(level: ClientLevel) {
        if (--scanCooldown > 0) return
        scanCooldown = SCAN_INTERVAL_TICKS
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val sounds = mc.soundManager
        // A level change stops all sounds and does not call stop() on the instance.
        loops.values.removeIf { it.isStopped || !sounds.isActive(it) }
        for (be in EyeroscopeClientRegistry.all(level)) {
            if (be in loops || be.isRemoved || !inRange(level, be, player, scratch)) continue
            loops[be] = IdleLoop(level, be).also(sounds::play)
        }
    }

    private fun inRange(level: ClientLevel, be: EyeroscopeBlockEntity, player: Player, out: Vector3d): Boolean {
        level.blockCentreInWorld(be.blockPos, out)
        return player.distanceToSqr(out.x, out.y, out.z) <= START_RANGE_SQR
    }

    private class IdleLoop(
        private val level: ClientLevel,
        private val be: EyeroscopeBlockEntity,
    ) : AbstractTickableSoundInstance(
        EKSounds.EYEROSCOPE_IDLE.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom(),
    ) {

        private val position = Vector3d()

        init {
            looping = true
            delay = 0
            volume = 0f
            move()
        }

        // The fade starts at volume 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val player = Minecraft.getInstance().player?.takeIf { !be.isRemoved && it.level() === level }
            if (player != null) move()
            val audible = player != null && player.distanceToSqr(x, y, z) <= START_RANGE_SQR
            val step = IDLE_VOLUME / FADE_TICKS
            volume = if (audible) (volume + step).coerceAtMost(IDLE_VOLUME) else volume - step
            if (volume <= 0f) stop()
        }

        private fun move() {
            level.blockCentreInWorld(be.blockPos, position)
            x = position.x
            y = position.y
            z = position.z
        }
    }
}
