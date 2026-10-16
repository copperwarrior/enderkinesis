package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.HeartOfTheWildBlock
import org.shipwrights.enderkinesis.blockentity.HeartOfTheWildClientRegistry
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.getShipManagingPos

/** One heartbeat for each [HeartOfTheWildRenderer] pulse cycle, on the same wall clock. */
object HeartOfTheWildSounds {

    private const val CYCLE_MILLIS: Long = 2500L
    /** The lub is 0.06 s into each file, so it lands on the chamber peak at 1.29 s. */
    private const val BEAT_MILLIS: Long = 1240L
    /** The range of a variable range event at volume 1. */
    private const val RANGE: Double = 16.0
    private const val RANGE_SQR: Double = RANGE * RANGE

    /** Client thread only. */
    private var lastMillis: Long = -1L
    private val scratch = Vector3d()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        lastMillis = -1L
    }

    private fun tick(level: ClientLevel) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val now = Util.getMillis()
        val last = lastMillis
        lastMillis = now
        if (last < 0L) return
        for (be in HeartOfTheWildClientRegistry.all(level)) {
            if (be.isRemoved) continue
            val mother = be.blockState.getValue(HeartOfTheWildBlock.MOTHER)
            val rate = if (mother) 1f else HeartOfTheWildRenderer.pulseRate(HeartOfTheWildRenderer.hashPos(be.blockPos))
            if (!crossesBeat(last, now, rate)) continue
            val pos = be.blockPos
            scratch.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            level.getShipManagingPos(pos)?.shipToWorld?.transformPosition(scratch)
            if (player.distanceToSqr(scratch.x, scratch.y, scratch.z) > RANGE_SQR) continue
            val event = if (mother) EKSounds.HEART_OF_THE_WILD_MOTHER_HEARTBEAT
                else EKSounds.HEART_OF_THE_WILD_HEARTBEAT
            mc.soundManager.play(
                SimpleSoundInstance(
                    event.get(), SoundSource.BLOCKS, 1f, rate, SoundInstance.createUnseededRandom(),
                    scratch.x, scratch.y, scratch.z,
                ),
            )
        }
    }

    /** The same cycle time as [HeartOfTheWildModel.animateIdleMillis]. */
    private fun cycleMillis(millis: Long, rate: Float): Long = (millis.toDouble() * rate).toLong() % CYCLE_MILLIS

    private fun crossesBeat(last: Long, now: Long, rate: Float): Boolean {
        // After a pause or a long stall, the cycle can wrap more than once. Skip that tick.
        if ((now - last) * rate >= CYCLE_MILLIS) return false
        val from = cycleMillis(last, rate)
        val to = cycleMillis(now, rate)
        return if (to >= from) from < BEAT_MILLIS && to >= BEAT_MILLIS else from < BEAT_MILLIS || to >= BEAT_MILLIS
    }
}
