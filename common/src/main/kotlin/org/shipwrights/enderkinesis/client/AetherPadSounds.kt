package org.shipwrights.enderkinesis.client

import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Vector3d
import org.shipwrights.enderkinesis.blockentity.AetherPadBlockEntity
import org.shipwrights.enderkinesis.blockentity.AetherPadClientRegistry
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.getShipManagingPos

object AetherPadSounds {

    private const val FADE_OUT_TICKS: Int = 5
    /** The fraction of the remaining pitch and volume change that the loop does in one tick. */
    private const val GLIDE: Float = 0.25f
    /** A small margin on the audible range, so the loop is at volume before the player hears it. */
    private const val START_RANGE: Double = EKSounds.AETHER_PAD_LOOP_RANGE + 2.0
    private const val START_RANGE_SQR: Double = START_RANGE * START_RANGE
    /** Many pads on one ship play the same loop, and more copies only blur it. */
    private const val MAX_LOOPS = 4
    /** A playing pad counts as this much nearer, so two pads at near the same distance do not swap each tick. */
    private const val PLAYING_BIAS_SQR = 0.8

    /** Client thread only. */
    private val loops = HashMap<AetherPadBlockEntity, PadLoop>()
    private val selected = HashSet<AetherPadBlockEntity>()
    private val candidates = ArrayList<AetherPadBlockEntity>()
    private var candidateDistSqr = DoubleArray(16)
    private val scratch = Vector3d()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        loops.values.forEach(sounds::stop)
        loops.clear()
        selected.clear()
        candidates.clear()
    }

    private fun tick(level: ClientLevel) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val sounds = mc.soundManager
        // A level change stops all sounds and does not call stop() on the instance.
        loops.values.removeIf { it.isStopped || !sounds.isActive(it) }
        gatherCandidates(level, player)
        selectNearest()
        for (be in selected) {
            if (be !in loops) loops[be] = PadLoop(level, be).also(sounds::play)
        }
    }

    private fun gatherCandidates(level: ClientLevel, player: Player) {
        candidates.clear()
        for (be in AetherPadClientRegistry.all(level)) {
            if (be.isRemoved || power(be) <= 0 || !inRange(level, be, player)) continue
            if (candidates.size == candidateDistSqr.size) {
                candidateDistSqr = candidateDistSqr.copyOf(candidates.size * 2)
            }
            val distSqr = player.distanceToSqr(scratch.x, scratch.y, scratch.z)
            candidateDistSqr[candidates.size] = if (be in loops) distSqr * PLAYING_BIAS_SQR else distSqr
            candidates.add(be)
        }
    }

    /** A partial selection sort: at most [MAX_LOOPS] passes over the candidates. */
    private fun selectNearest() {
        selected.clear()
        val count = candidates.size
        for (pick in 0 until minOf(MAX_LOOPS, count)) {
            var best = pick
            for (i in pick + 1 until count) if (candidateDistSqr[i] < candidateDistSqr[best]) best = i
            if (best != pick) {
                candidates[pick] = candidates[best].also { candidates[best] = candidates[pick] }
                candidateDistSqr[pick] = candidateDistSqr[best].also { candidateDistSqr[best] = candidateDistSqr[pick] }
            }
            selected.add(candidates[pick])
        }
    }

    private fun power(be: AetherPadBlockEntity): Int = be.blockState.getValue(BlockStateProperties.POWER)

    private fun targetVolume(power: Int): Float = 0.25f + 0.5f * power / 15f

    private fun targetPitch(power: Int): Float = 0.85f + 0.15f * power / 15f

    /** On a ship, the block position is a shipyard position. */
    private fun worldCentre(level: ClientLevel, be: AetherPadBlockEntity, out: Vector3d) {
        val pos = be.blockPos
        out.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        level.getShipManagingPos(pos)?.shipToWorld?.transformPosition(out)
    }

    /** Leaves the pad's world centre in [scratch]. */
    private fun inRange(level: ClientLevel, be: AetherPadBlockEntity, player: Player): Boolean {
        worldCentre(level, be, scratch)
        return player.distanceToSqr(scratch.x, scratch.y, scratch.z) <= START_RANGE_SQR
    }

    /** A loop at the world position of a pad. It follows the ship of the pad. */
    private class PadLoop(
        private val level: ClientLevel,
        private val be: AetherPadBlockEntity,
    ) : AbstractTickableSoundInstance(EKSounds.AETHER_PAD_LOOP.get(), SoundSource.BLOCKS,
        SoundInstance.createUnseededRandom()) {

        private val position = Vector3d()

        init {
            looping = true
            delay = 0
            val power = power(be)
            volume = targetVolume(power)
            pitch = targetPitch(power)
            move()
        }

        override fun tick() {
            val power = if (isAudible() && be in selected) power(be) else 0
            if (power <= 0) {
                volume -= 1f / FADE_OUT_TICKS
                if (volume <= 0f) stop()
                return
            }
            volume += (targetVolume(power) - volume) * GLIDE
            pitch += (targetPitch(power) - pitch) * GLIDE
        }

        private fun isAudible(): Boolean {
            val player = Minecraft.getInstance().player ?: return false
            if (player.level() !== level || be.isRemoved) return false
            move()
            return player.distanceToSqr(x, y, z) <= START_RANGE_SQR
        }

        private fun move() {
            worldCentre(level, be, position)
            x = position.x
            y = position.y
            z = position.z
        }
    }
}
