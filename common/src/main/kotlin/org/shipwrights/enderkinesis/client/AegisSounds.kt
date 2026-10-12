package org.shipwrights.enderkinesis.client

import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.item.AegisBox
import org.shipwrights.enderkinesis.registry.EKSounds

object AegisSounds {

    private const val SHARD_BOUNCE_FULL_VOLUME_SPEED: Double = 0.4
    private const val SHARD_BOUNCE_MIN_VOLUME: Float = 0.1f

    fun onRaise(level: ClientLevel, player: Player) {
        playOnce(level, player, EKSounds.AEGIS_RAISE)
        Minecraft.getInstance().soundManager.play(HumLoop(player))
    }

    fun onShatter(level: ClientLevel, player: Player) {
        playOnce(level, player, EKSounds.AEGIS_SHATTER)
    }

    /** Returns true if the sound played. The volume is linear in [impactSpeed] (blocks/tick). */
    fun playShardBounce(level: ClientLevel, pos: Vec3, impactSpeed: Double): Boolean {
        val volume = (impactSpeed / SHARD_BOUNCE_FULL_VOLUME_SPEED).coerceAtMost(1.0).toFloat()
        if (volume < SHARD_BOUNCE_MIN_VOLUME) return false
        level.playLocalSound(
            pos.x, pos.y, pos.z, EKSounds.AEGIS_SHARD_BOUNCE.get(), SoundSource.PLAYERS, volume, 1f, false,
        )
        return true
    }

    private fun playOnce(level: ClientLevel, player: Player, event: RegistrySupplier<SoundEvent>) {
        val pos = AegisBox.forPlayer(player).center
        level.playLocalSound(pos.x, pos.y, pos.z, event.get(), SoundSource.PLAYERS, 1f, 1f, false)
    }

    private class HumLoop(private val player: Player) : AbstractTickableSoundInstance(
        EKSounds.AEGIS_HUM_LOOP.get(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom(),
    ) {

        init {
            looping = true
            delay = 0
            moveTo(AegisBox.forPlayer(player).center)
        }

        override fun tick() {
            if (!player.isRemoved && AegisClient.isWielding(player)) moveTo(AegisBox.forPlayer(player).center) else stop()
        }

        private fun moveTo(pos: Vec3) {
            x = pos.x
            y = pos.y
            z = pos.z
        }
    }
}
