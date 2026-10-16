package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.EnderAstrolabeBlock
import org.shipwrights.enderkinesis.blockentity.EnderAstrolabeBlockEntity
import org.shipwrights.enderkinesis.blockentity.EnderAstrolabeClientRegistry
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.getShipManagingPos

object EnderAstrolabeSounds {

    private const val CHARGE_VOLUME: Float = 1f
    private const val CHARGE_PITCH_START: Double = 0.8
    private const val CHARGE_PITCH_END: Double = 1.4
    /** A short fade prevents a click when the loop stops in the middle of the file. */
    private const val FADE_OUT_TICKS: Int = 4

    /** Client thread only. */
    private val loops = HashMap<EnderAstrolabeBlockEntity, ChargeLoop>()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        loops.values.forEach(sounds::stop)
        loops.clear()
    }

    private fun tick(level: ClientLevel) {
        val sounds = Minecraft.getInstance().soundManager
        // A level change stops all sounds and does not call stop() on the instance.
        if (loops.isNotEmpty()) loops.values.removeIf { it.isStopped || !sounds.isActive(it) }
        for (be in EnderAstrolabeClientRegistry.all(level)) {
            if (be in loops || !isCharging(be)) continue
            loops[be] = ChargeLoop(level, be).also(sounds::play)
        }
    }

    private fun isCharging(be: EnderAstrolabeBlockEntity): Boolean =
        !be.isRemoved && be.blockState.getValue(EnderAstrolabeBlock.CHARGING)

    private class ChargeLoop(
        private val level: ClientLevel,
        private val be: EnderAstrolabeBlockEntity,
    ) : AbstractTickableSoundInstance(
        EKSounds.ENDER_ASTROLABE_CHARGE_LOOP.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom(),
    ) {

        private val scratch = Vector3d()

        init {
            looping = true
            delay = 0
            volume = CHARGE_VOLUME
            pitch = CHARGE_PITCH_START.toFloat()
            moveToBlock()
        }

        override fun tick() {
            if (Minecraft.getInstance().level !== level || !isCharging(be)) {
                volume -= CHARGE_VOLUME / FADE_OUT_TICKS
                if (volume <= 0f) stop()
                return
            }
            moveToBlock()
            // The spin rate increases exponentially, so the pitch does the same.
            val progress = be.chargeProgress(level.gameTime.toDouble()) ?: 0f
            pitch = (CHARGE_PITCH_START * Math.pow(CHARGE_PITCH_END / CHARGE_PITCH_START, progress.toDouble())).toFloat()
        }

        private fun moveToBlock() {
            val pos = be.blockPos
            scratch.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            level.getShipManagingPos(pos)?.shipToWorld?.transformPosition(scratch)
            x = scratch.x
            y = scratch.y
            z = scratch.z
        }
    }
}
