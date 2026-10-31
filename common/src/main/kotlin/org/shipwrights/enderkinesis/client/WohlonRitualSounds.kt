package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundSource
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.WohlonRitualNetwork
import org.shipwrights.enderkinesis.registry.EKSounds

object WohlonRitualSounds {

    private const val SPREAD_VOLUME = 4f
    private const val GHOST_VOLUME = 3f

    // The same distance from the camera as vanilla LevelRenderer.globalLevelEvent.
    private const val CALL_DISTANCE = 2.0

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, WohlonRitualNetwork.S2C_CALL) { buf, _ ->
            val candle = BlockPos.of(buf.readLong())
            Minecraft.getInstance().execute { playCall(candle) }
        }
    }

    fun playSpread(heart: Vector3d) {
        val level = Minecraft.getInstance().level ?: return
        level.playLocalSound(
            heart.x, heart.y, heart.z, EKSounds.WOHLON_RITUAL_SPREAD.get(), SoundSource.BLOCKS, SPREAD_VOLUME, 1f, false,
        )
    }

    /** The caller keeps the instance, to stop it if the ghost fades early. */
    fun playGhost(trunk: BlockPos): SoundInstance {
        val sound = SimpleSoundInstance(
            EKSounds.WOHLON_RITUAL_GHOST.get(), SoundSource.BLOCKS, GHOST_VOLUME, 1f,
            SoundInstance.createUnseededRandom(), trunk.x + 0.5, trunk.y + 0.5, trunk.z + 0.5,
        )
        Minecraft.getInstance().soundManager.play(sound)
        return sound
    }

    fun stop(sound: SoundInstance) {
        Minecraft.getInstance().soundManager.stop(sound)
    }

    private fun playCall(candle: BlockPos) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val camera = mc.gameRenderer.mainCamera
        if (!camera.isInitialized) return
        val eye = camera.position
        val dx = candle.x + 0.5 - eye.x
        val dy = candle.y + 0.5 - eye.y
        val dz = candle.z + 0.5 - eye.z
        val dist = Math.sqrt(dx * dx + dy * dy + dz * dz)
        val scale = if (dist > 0.0) CALL_DISTANCE / dist else 0.0
        level.playLocalSound(
            eye.x + dx * scale, eye.y + dy * scale, eye.z + dz * scale,
            EKSounds.WOHLON_RITUAL_CALL.get(), SoundSource.BLOCKS, 1f, 1f, false,
        )
    }
}
