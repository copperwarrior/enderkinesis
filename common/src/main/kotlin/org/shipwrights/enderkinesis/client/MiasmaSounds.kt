package org.shipwrights.enderkinesis.client

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.ChunkPos
import org.shipwrights.enderkinesis.registry.EKSounds

/** The gas has no field identity, so a field here is a group of touching chunks that hold gas. Each loop sits at its group's gas nearest the listener. */
object MiasmaSounds {
    private const val UPDATE_EVERY = 10
    private const val VOLUME = 0.35f
    /** A loop that loses its anchor chunk takes over the nearest new group within this distance, so it does not restart. */
    private const val FOLLOW_RANGE_SQR = 24.0 * 24.0

    private class Group {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var nearestSqr = Double.MAX_VALUE
        var amount = 0.0
        var anchor = 0L
        var claimed = false
    }

    // Client thread only.
    private val loops = ArrayList<FieldSound>()
    private val groups = ArrayList<Group>()
    private val groupOf = Long2IntOpenHashMap().apply { defaultReturnValue(-1) }
    private val stack = LongArrayList()
    private val orphans = ArrayList<FieldSound>()
    private var ticks = 0

    fun reset() {
        loops.forEach(FieldSound::end)
        loops.clear()
    }

    fun tick() {
        if (++ticks % UPDATE_EVERY != 0) return
        group()
        match()
    }

    /** Works on chunk summaries, so the cost follows the chunk count, not the cell count. */
    private fun group() {
        groups.clear()
        groupOf.clear()
        val keys = GasFieldClient.chunkKeys().iterator()
        while (keys.hasNext()) {
            val start = keys.nextLong()
            if (groupOf.containsKey(start)) continue
            val index = groups.size
            val group = Group().also { it.anchor = start }
            groups += group
            groupOf.put(start, index)
            stack.clear()
            stack.add(start)
            // Bounded: each chunk with gas enters the stack once.
            while (!stack.isEmpty) {
                val chunk = stack.removeLong(stack.size - 1)
                GasFieldClient.summary(chunk)?.let { gas ->
                    group.amount += gas.amount
                    if (gas.nearestSqr < group.nearestSqr) {
                        group.nearestSqr = gas.nearestSqr
                        group.x = gas.x; group.y = gas.y; group.z = gas.z
                    }
                }
                val cx = ChunkPos.getX(chunk)
                val cz = ChunkPos.getZ(chunk)
                for (dx in -1..1) for (dz in -1..1) {
                    val next = ChunkPos.asLong(cx + dx, cz + dz)
                    if (groupOf.containsKey(next) || GasFieldClient.summary(next) == null) continue
                    groupOf.put(next, index)
                    stack.add(next)
                }
            }
        }
    }

    private fun match() {
        orphans.clear()
        val it = loops.iterator()
        while (it.hasNext()) {
            val loop = it.next()
            if (loop.isStopped) { it.remove(); continue }
            val group = groups.getOrNull(groupOf.get(loop.anchor))
            if (group == null || group.claimed) { orphans += loop; continue }
            group.claimed = true
            loop.aim(group)
        }
        for (loop in orphans) {
            var best: Group? = null
            var bestSqr = FOLLOW_RANGE_SQR
            for (group in groups) {
                if (group.claimed) continue
                val d = loop.distanceSqr(group)
                if (d <= bestSqr) { best = group; bestSqr = d }
            }
            if (best == null) { loop.end(); loops.remove(loop); continue }
            best.claimed = true
            loop.anchor = best.anchor
            loop.aim(best)
        }
        for (group in groups) {
            if (group.claimed || group.amount <= 0.0) continue
            FieldSound(group).also { loops += it; Minecraft.getInstance().soundManager.play(it) }
        }
    }

    private class FieldSound(group: Group) : AbstractTickableSoundInstance(EKSounds.SPELL_CORRUPTION_MIASMA_LOOP.get(), SoundSource.PLAYERS, SoundInstance.createUnseededRandom()) {
        var anchor = group.anchor
        private var peak = group.amount
        private var toX = group.x
        private var toY = group.y
        private var toZ = group.z
        private var toVolume = VOLUME

        init {
            looping = true
            delay = 0
            x = toX; y = toY; z = toZ
            volume = VOLUME
        }

        /** Fades with the amount of gas, measured against the most this field held. */
        fun aim(group: Group) {
            peak = maxOf(peak, group.amount)
            toX = group.x; toY = group.y; toZ = group.z
            toVolume = (VOLUME * group.amount / peak).toFloat()
        }

        fun distanceSqr(group: Group): Double = (group.x - toX) * (group.x - toX) + (group.y - toY) * (group.y - toY) + (group.z - toZ) * (group.z - toZ)

        // Targets change every UPDATE_EVERY ticks, so ease toward them to hide the steps.
        override fun tick() {
            val k = 1.0 / UPDATE_EVERY
            x += (toX - x) * k
            y += (toY - y) * k
            z += (toZ - z) * k
            volume += (toVolume - volume) * k.toFloat()
        }

        fun end() = stop()
    }
}
