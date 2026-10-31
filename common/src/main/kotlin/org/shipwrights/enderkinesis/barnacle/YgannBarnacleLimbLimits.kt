package org.shipwrights.enderkinesis.barnacle

import net.minecraft.nbt.CompoundTag

/** Growth limits keyed by limb id: limb `id` reaches `arc` at `time`, then grows on at the creep rate. */
class YgannBarnacleLimbLimits(capacity: Int, private val ratePxPerTick: Double) {

    private val ids = IntArray(capacity)
    private val arcs = DoubleArray(capacity)
    private val times = LongArray(capacity)

    /** The limit (px) of limb [id] at time [t], or +∞ when it has none. */
    fun limit(id: Int, t: Double): Double {
        val i = ids.indexOf(id)
        return if (i < 0) Double.POSITIVE_INFINITY else limitAt(i, t)
    }

    /** Sets limb [id]'s limit. With no free slot, it takes the slot that is nearest to fully grown. */
    fun set(id: Int, arc: Double, time: Long) {
        require(id != 0) { "limb id 0 is the empty slot" }
        var slot = ids.indexOf(id)
        if (slot < 0) {
            var best = Double.NEGATIVE_INFINITY
            for (i in ids.indices) {
                val score = if (ids[i] == 0) Double.POSITIVE_INFINITY else limitAt(i, time.toDouble())
                if (score > best) { best = score; slot = i }
            }
        }
        ids[slot] = id
        arcs[slot] = arc
        times[slot] = time
    }

    /** Removes the limits of limbs that are not in [paths]. True when one was removed. */
    fun retain(paths: List<YgannBarnacleGeometry.BPath>): Boolean {
        var changed = false
        for (i in ids.indices) {
            if (ids[i] != 0 && paths.none { it.id == ids[i] }) {
                ids[i] = 0
                changed = true
            }
        }
        return changed
    }

    fun clear() = ids.fill(0)

    fun save(tag: CompoundTag, key: String) {
        tag.putIntArray("${key}Id", ids.copyOf())
        tag.putLongArray("${key}Arc", LongArray(arcs.size) { java.lang.Double.doubleToRawLongBits(arcs[it]) })
        tag.putLongArray("${key}Time", times.copyOf())
    }

    /** Loads the limits; true when they differ from the current ones. */
    fun load(tag: CompoundTag, key: String): Boolean {
        val newIds = tag.getIntArray("${key}Id")
        val newArcs = tag.getLongArray("${key}Arc")
        val newTimes = tag.getLongArray("${key}Time")
        var changed = false
        for (i in ids.indices) {
            val present = i < newIds.size && i < newArcs.size && i < newTimes.size
            val id = if (present) newIds[i] else 0
            val arc = if (id != 0) java.lang.Double.longBitsToDouble(newArcs[i]) else 0.0
            val time = if (id != 0) newTimes[i] else 0L
            if (id != ids[i] || (id != 0 && (arc != arcs[i] || time != times[i]))) changed = true
            ids[i] = id
            arcs[i] = arc
            times[i] = time
        }
        return changed
    }

    private fun limitAt(i: Int, t: Double): Double = arcs[i] + Math.max(0.0, t - times[i]) * ratePxPerTick
}
