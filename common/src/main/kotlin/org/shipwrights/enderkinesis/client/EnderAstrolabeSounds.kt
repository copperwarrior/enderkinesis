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
    /** The loop is a variable-range event at volume 1, so vanilla attenuates it to zero at 16 blocks. */
    private const val AUDIBLE_RANGE: Double = 16.0
    /** A small margin on the audible range, so the loop is at volume before the player hears it. */
    private const val START_RANGE: Double = AUDIBLE_RANGE + 2.0
    private const val START_RANGE_SQR: Double = START_RANGE * START_RANGE

    /** Client thread only. */
    private val loops = HashMap<EnderAstrolabeBlockEntity, ChargeLoop>()
    private val scratch = Vector3d()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        loops.values.forEach(sounds::stop)
        loops.clear()
    }

    private fun tick(level: ClientLevel) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val sounds = mc.soundManager
        // A level change stops all sounds and does not call stop() on the instance.
        if (loops.isNotEmpty()) loops.values.removeIf { it.isStopped || !sounds.isActive(it) }
        for (be in EnderAstrolabeClientRegistry.all(level)) {
            if (be in loops || !isCharging(be)) continue
            worldCentre(level, be, scratch)
            if (player.distanceToSqr(scratch.x, scratch.y, scratch.z) > START_RANGE_SQR) continue
            loops[be] = ChargeLoop(level, be).also(sounds::play)
        }
    }

    /** On a ship, the block position is a shipyard position. */
    private fun worldCentre(level: ClientLevel, be: EnderAstrolabeBlockEntity, out: Vector3d) {
        val pos = be.blockPos
        out.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        level.getShipManagingPos(pos)?.shipToWorld?.transformPosition(out)
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
            if (Minecraft.getInstance().level !== level || !isCharging(be) || !isInRange()) {
                volume -= CHARGE_VOLUME / FADE_OUT_TICKS
                if (volume <= 0f) stop()
                return
            }
            // The spin rate increases exponentially, so the pitch does the same.
            val progress = be.chargeProgress(level.gameTime.toDouble()) ?: 0f
            pitch = (CHARGE_PITCH_START * Math.pow(CHARGE_PITCH_END / CHARGE_PITCH_START, progress.toDouble())).toFloat()
        }

        private fun isInRange(): Boolean {
            val player = Minecraft.getInstance().player ?: return false
            moveToBlock()
            return player.distanceToSqr(x, y, z) <= START_RANGE_SQR
        }

        private fun moveToBlock() {
            worldCentre(level, be, scratch)
            x = scratch.x
            y = scratch.y
            z = scratch.z
        }
    }
}
