package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.OarscribeBlock
import org.shipwrights.enderkinesis.blockentity.OarscribeBlockEntity
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.getShipManagingPos

object OarscribeSounds {

    /** A small margin on the 16-block audible range, so a loop is at volume before the player hears it. */
    private const val RANGE_SQR = 18.0 * 18.0

    /** Client thread only. */
    private val loops = HashMap<OarscribeBlockEntity, WritingLoop>()
    private val scratch = Vector3d()

    fun tick(oarscribe: OarscribeBlockEntity, level: Level, pos: BlockPos, state: BlockState) {
        val writing = state.getValue(OarscribeBlock.WRITING)
        val heard = oarscribe.heardWriting
        oarscribe.heardWriting = writing
        if (heard != null && heard != writing) {
            playAt(level, pos, if (writing) EKSounds.OARSCRIBE_START.get() else EKSounds.OARSCRIBE_STOP.get())
        }
        if (writing) startLoop(oarscribe, level, pos)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        loops.values.forEach(sounds::stop)
        loops.clear()
    }

    private fun startLoop(oarscribe: OarscribeBlockEntity, level: Level, pos: BlockPos) {
        val mc = Minecraft.getInstance()
        val sounds = mc.soundManager
        val running = loops[oarscribe]
        // A level change stops all sounds and does not call stop() on the instance.
        if (running != null && !running.isStopped && sounds.isActive(running)) return
        val player = mc.player ?: return
        worldCentre(level, pos, scratch)
        if (player.distanceToSqr(scratch.x, scratch.y, scratch.z) > RANGE_SQR) return
        loops.values.removeIf { it.isStopped || !sounds.isActive(it) }
        loops[oarscribe] = WritingLoop(level, oarscribe).also(sounds::play)
    }

    private fun playAt(level: Level, pos: BlockPos, sound: SoundEvent) {
        worldCentre(level, pos, scratch)
        level.playLocalSound(scratch.x, scratch.y, scratch.z, sound, SoundSource.BLOCKS, 1f, 1f, false)
    }

    /** On a ship, the block position is a shipyard position. */
    private fun worldCentre(level: Level, pos: BlockPos, out: Vector3d) {
        out.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        level.getShipManagingPos(pos)?.shipToWorld?.transformPosition(out)
    }

    private class WritingLoop(
        private val level: Level,
        private val oarscribe: OarscribeBlockEntity,
    ) : AbstractTickableSoundInstance(
        EKSounds.OARSCRIBE_LOOP.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom(),
    ) {

        private val position = Vector3d()

        init {
            looping = true
            delay = 0
            move()
        }

        override fun tick() {
            if (isWanted()) return
            stop()
            // Drop the entry now, so a removed Oarscribe and its level are not held until the next loop starts.
            loops.remove(oarscribe, this)
        }

        private fun isWanted(): Boolean {
            val player = Minecraft.getInstance().player ?: return false
            if (player.level() !== level || oarscribe.isRemoved) return false
            if (!oarscribe.blockState.getValue(OarscribeBlock.WRITING)) return false
            move()
            return player.distanceToSqr(x, y, z) <= RANGE_SQR
        }

        private fun move() {
            worldCentre(level, oarscribe.blockPos, position)
            x = position.x
            y = position.y
            z = position.z
        }
    }
}
