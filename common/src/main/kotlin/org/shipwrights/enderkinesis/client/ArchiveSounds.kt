package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.entity.ArchiveEntity
import org.shipwrights.enderkinesis.registry.EKSounds

object ArchiveSounds {

    private const val SCAN_INTERVAL_TICKS = 10L
    private const val LOOP_VOLUME = 0.5f
    private const val ACTIVE_RANGE_SQR = ArchiveEntity.ACTIVE_RANGE * ArchiveEntity.ACTIVE_RANGE

    /** Client thread only. */
    private val loops = HashMap<ArchiveEntity, ArchiveLoop>()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        loops.values.forEach(Minecraft.getInstance().soundManager::stop)
        loops.clear()
    }

    private fun tick(level: ClientLevel) {
        if (level.gameTime % SCAN_INTERVAL_TICKS != 0L) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val sounds = mc.soundManager
        // A level change stops all sounds and does not call stop() on the instance.
        loops.values.removeIf { it.isStopped || !sounds.isActive(it) }
        val box = player.boundingBox.inflate(ArchiveEntity.ACTIVE_RANGE)
        for (archive in level.getEntitiesOfClass(ArchiveEntity::class.java, box)) {
            if (archive in loops || !isActive(archive)) continue
            loops[archive] = ArchiveLoop(archive).also(sounds::play)
        }
    }

    private fun isActive(archive: ArchiveEntity): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        return !archive.isRemoved && player.level() === archive.level() &&
            player.distanceToSqr(archive) <= ACTIVE_RANGE_SQR
    }

    private class ArchiveLoop(private val archive: ArchiveEntity) :
        AbstractTickableSoundInstance(
            EKSounds.SSELITH_ARCHIVE_LOOP.get(), SoundSource.NEUTRAL, SoundInstance.createUnseededRandom(),
        ) {

        init {
            looping = true
            delay = 0
            volume = LOOP_VOLUME
            move()
        }

        override fun tick() {
            if (!isActive(archive)) {
                stop()
                return
            }
            move()
        }

        private fun move() {
            x = archive.x
            y = archive.y + archive.bbHeight / 2.0
            z = archive.z
        }
    }
}
