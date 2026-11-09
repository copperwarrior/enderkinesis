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
import org.shipwrights.enderkinesis.block.ShulkerPufferBlock
import org.shipwrights.enderkinesis.blockentity.ShulkerPufferBlockEntity
import org.shipwrights.enderkinesis.blockentity.ShulkerPufferClientRegistry
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.util.blockCentreInWorld

object ShulkerPufferSounds {

    /** Also the fade-in and fade-out time. */
    private const val CROSSFADE_TICKS: Int = 10
    private const val PITCH_STEP: Float = 0.01f
    /** A small margin on the audible range, so the loop is at volume before the player hears it. */
    private const val START_RANGE: Double = EKSounds.SHULKER_PUFFER_LOOP_RANGE + 2.0
    private const val START_RANGE_SQR: Double = START_RANGE * START_RANGE
    /** Many puffers on one ship play the same loop, and more copies only blur it. */
    private const val MAX_LOOPS = 4
    /** A playing puffer counts as this much nearer, so two at near the same distance do not swap each tick. */
    private const val PLAYING_BIAS_SQR = 0.8

    /** Client thread only. */
    private val loops = HashMap<ShulkerPufferBlockEntity, PufferLoop>()
    private val fuelLoops = HashMap<ShulkerPufferBlockEntity, PufferLoop>()
    private val selected = HashSet<ShulkerPufferBlockEntity>()
    private val candidates = ArrayList<ShulkerPufferBlockEntity>()
    private var candidateDistSqr = DoubleArray(16)
    private val scratch = Vector3d()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(::tick)
    }

    fun onDisconnect() {
        val sounds = Minecraft.getInstance().soundManager
        loops.values.forEach(sounds::stop)
        fuelLoops.values.forEach(sounds::stop)
        loops.clear()
        fuelLoops.clear()
        selected.clear()
        candidates.clear()
    }

    private fun tick(level: ClientLevel) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val sounds = mc.soundManager
        // A level change stops all sounds and does not call stop() on the instance.
        loops.values.removeIf { it.isStopped || !sounds.isActive(it) }
        fuelLoops.values.removeIf { it.isStopped || !sounds.isActive(it) }
        gatherCandidates(level, player)
        selectNearest()
        for (be in selected) {
            val fuel = fueled(be)
            val map = if (fuel) fuelLoops else loops
            if (be !in map) {
                map[be] = PufferLoop(level, be, fuel).also(sounds::play)
                be.puffCountdown = 0f
            }
        }
        ShulkerPufferPuffs.tick(level, player)
    }

    /** The pitch of the loop that matches the fuel state, or null when it does not play. */
    internal fun activePitch(be: ShulkerPufferBlockEntity): Float? =
        (if (fueled(be)) fuelLoops[be] else loops[be])?.pitch

    private fun gatherCandidates(level: ClientLevel, player: Player) {
        candidates.clear()
        for (be in ShulkerPufferClientRegistry.all(level)) {
            if (be.isRemoved || power(be) <= 0 || !inRange(level, be, player)) continue
            if (candidates.size == candidateDistSqr.size) {
                candidateDistSqr = candidateDistSqr.copyOf(candidates.size * 2)
            }
            val distSqr = player.distanceToSqr(scratch.x, scratch.y, scratch.z)
            val playing = be in loops || be in fuelLoops
            candidateDistSqr[candidates.size] = if (playing) distSqr * PLAYING_BIAS_SQR else distSqr
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

    internal fun power(be: ShulkerPufferBlockEntity): Int = be.blockState.getValue(BlockStateProperties.POWER)

    internal fun fueled(be: ShulkerPufferBlockEntity): Boolean = be.blockState.getValue(ShulkerPufferBlock.FUELED)

    private fun targetVolume(power: Int): Float = 0.5f + 0.5f * power / 15f

    internal fun targetPitch(power: Int): Float = 0.75f + 0.25f * (power - 1) / 14f

    /** Leaves the puffer's world centre in [scratch]. */
    private fun inRange(level: ClientLevel, be: ShulkerPufferBlockEntity, player: Player): Boolean {
        level.blockCentreInWorld(be.blockPos, scratch)
        return player.distanceToSqr(scratch.x, scratch.y, scratch.z) <= START_RANGE_SQR
    }

    /** One of the two loops of a puffer, at its world position. It follows the ship of the puffer. */
    private class PufferLoop(
        private val level: ClientLevel,
        private val be: ShulkerPufferBlockEntity,
        private val fuel: Boolean,
    ) : AbstractTickableSoundInstance(
        if (fuel) EKSounds.SHULKER_PUFFER_LOOP_FUEL.get() else EKSounds.SHULKER_PUFFER_LOOP.get(),
        SoundSource.BLOCKS, SoundInstance.createUnseededRandom(),
    ) {

        private val position = Vector3d()

        init {
            looping = true
            delay = 0
            volume = 0f
            pitch = targetPitch(power(be))
            move()
        }

        // The fade starts at volume 0. The sound engine drops a silent sound without this.
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val power = if (isAudible() && be in selected) power(be) else 0
            val goal = if (power > 0 && fueled(be) == fuel) targetVolume(power) else 0f
            val step = 1f / CROSSFADE_TICKS
            volume = if (volume < goal) (volume + step).coerceAtMost(goal) else (volume - step).coerceAtLeast(goal)
            if (power > 0) {
                val pitchGoal = targetPitch(power)
                pitch = if (pitch < pitchGoal) {
                    (pitch + PITCH_STEP).coerceAtMost(pitchGoal)
                } else {
                    (pitch - PITCH_STEP).coerceAtLeast(pitchGoal)
                }
            }
            if (goal <= 0f && volume <= 0f) stop()
        }

        private fun isAudible(): Boolean {
            val player = Minecraft.getInstance().player ?: return false
            if (player.level() !== level || be.isRemoved) return false
            move()
            return player.distanceToSqr(x, y, z) <= START_RANGE_SQR
        }

        private fun move() {
            level.blockCentreInWorld(be.blockPos, position)
            x = position.x
            y = position.y
            z = position.z
        }
    }
}
