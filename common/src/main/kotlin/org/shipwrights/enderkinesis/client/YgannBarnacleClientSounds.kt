package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.mod.common.toWorldCoordinates

object YgannBarnacleClientSounds {

    private val PULSE_RANGE_SQ: Double =
        EKSounds.YGANN_BARNACLE_PULSE_RANGE.toDouble() * EKSounds.YGANN_BARNACLE_PULSE_RANGE

    /** Every barnacle pulses on the same tick, and more copies of the sound only blur it. */
    private const val MAX_PULSES = 4

    // Client thread only: the nearest barnacles that pulsed on [batchTime], played at the next frame.
    private val xs = DoubleArray(MAX_PULSES)
    private val ys = DoubleArray(MAX_PULSES)
    private val zs = DoubleArray(MAX_PULSES)
    private val distSq = DoubleArray(MAX_PULSES)
    private var count = 0
    private var batchTime = Long.MIN_VALUE
    private var batchLevel: Level? = null

    /** Skips a barnacle out of range, so a hull full of them does not fill the sound channels. */
    fun pulse(level: Level, pos: BlockPos) {
        val player = Minecraft.getInstance().player ?: return
        // On a ship, pos is a shipyard position.
        val centre = level.toWorldCoordinates(Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5))
        val d2 = player.distanceToSqr(centre)
        if (d2 > PULSE_RANGE_SQ) return
        if (level !== batchLevel || level.gameTime != batchTime) {
            flush()
            batchLevel = level
            batchTime = level.gameTime
        }
        val slot = if (count < MAX_PULSES) count++ else farthest().takeIf { d2 < distSq[it] } ?: return
        xs[slot] = centre.x; ys[slot] = centre.y; zs[slot] = centre.z; distSq[slot] = d2
    }

    /** Plays the pulses gathered on the last pulse tick. Called at the start of each frame. */
    fun flush() {
        val level = batchLevel
        batchLevel = null
        val n = count
        count = 0
        if (level == null || level !== Minecraft.getInstance().level) return
        for (i in 0 until n) {
            level.playLocalSound(xs[i], ys[i], zs[i], EKSounds.YGANN_BARNACLE_PULSE.get(), SoundSource.BLOCKS, 1f, 1f, false)
        }
    }

    private fun farthest(): Int {
        var best = 0
        for (i in 1 until MAX_PULSES) if (distSq[i] > distSq[best]) best = i
        return best
    }
}
