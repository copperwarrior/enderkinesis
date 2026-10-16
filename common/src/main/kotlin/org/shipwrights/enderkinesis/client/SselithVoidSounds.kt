package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.dimension.SselithVoidWrap
import org.shipwrights.enderkinesis.registry.EKSounds

object SselithVoidSounds {

    private const val FALL_PEAK_VOLUME = 0.35f
    private const val FADE_IN_TICKS = 60
    private const val FADE_OUT_TICKS = 20
    private const val FALL_MIN_SPEED = 0.5
    private const val FALL_SPEED_RANGE = 2.5

    /** The void fog fades out past this distance from Y 0. */
    private const val FOG_FREE_ABS_Y = 160.0
    private const val WRAP_VOLUME = 0.7f

    /** Client thread only. */
    private var fallLoop: FlatFadeLoop? = null

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SselithVoidWrap.WRAPPED_PACKET) { _, ctx ->
            ctx.queue { playFlat(EKSounds.SSELITH_VOID_WRAP.get(), WRAP_VOLUME) }
        }
    }

    fun onDisconnect() {
        fallLoop?.let(Minecraft.getInstance().soundManager::stop)
        fallLoop = null
    }

    private fun tick(level: ClientLevel) {
        if (level.dimension() != SselithRepertory.LEVEL_KEY) return
        if (fallLoop?.isPlaying() == true || fallStrength() <= 0f) return
        fallLoop = FlatFadeLoop(
            EKSounds.SSELITH_VOID_FALL.get(), FALL_PEAK_VOLUME, FADE_IN_TICKS, FADE_OUT_TICKS, ::fallStrength,
        ).also(Minecraft.getInstance().soundManager::play)
    }

    private fun fallStrength(): Float {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return 0f
        if (mc.level?.dimension() != SselithRepertory.LEVEL_KEY) return 0f
        if (player.y in -FOG_FREE_ABS_Y..FOG_FREE_ABS_Y) return 0f
        return ((-player.deltaMovement.y - FALL_MIN_SPEED) / FALL_SPEED_RANGE).coerceIn(0.0, 1.0).toFloat()
    }
}
