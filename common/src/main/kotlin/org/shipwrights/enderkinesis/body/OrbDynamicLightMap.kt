package org.shipwrights.enderkinesis.body

import kotlin.math.abs

/**
 * Client-side dynamic-light contribution lookup for Orb of Potential
 * bodies. Holds the active orb positions + emission level; queried by
 * [org.shipwrights.enderkinesis.mixin.BlockLightEngineOrbDynamicLightMixin]
 * during the block-light read path
 * ({@code LightEngine.getLightValue(BlockPos)}) and populated each
 * client tick by
 * [org.shipwrights.enderkinesis.client.OrbDynamicLightDriver].
 *
 *  Lives in common (no client-only Minecraft imports) so the mixin
 *  can reference it from a target class that's instantiated on both
 *  client and server. The mixin's client-only `chunkSource` gate
 *  keeps server-side queries (mob spawning, plant growth, redstone)
 *  on the vanilla light value.
 *
 *  Storage is one immutable snapshot. Lookup walks it and returns the maximum
 *  Manhattan-distance contribution. Reads are wait-free (one volatile reference).
 *  The driver publishes a new snapshot once per tick.
 */
object OrbDynamicLightMap {
    /** One snapshot: `xyz` is x, y, z for each orb in sequence. Never changed after publish. */
    private class Snapshot(val xyz: IntArray, val lightLevel: Int)

    private val EMPTY = Snapshot(IntArray(0), 0)

    // One reference, not three arrays: a chunk-build worker that read a new array together
    // with an old one indexed past the end of the shorter one.
    @Volatile private var snapshot: Snapshot = EMPTY

    @JvmStatic
    fun isEmpty(): Boolean = snapshot.xyz.isEmpty()

    /** Maximum orb-light contribution at world position `(x, y, z)`,
     *  in `[0, 15]`. Each active orb contributes `lightLevel - manhattan(orb, here)`
     *  clamped to non-negative; the call returns the max across all
     *  active orbs. Manhattan distance matches vanilla's face-by-face
     *  BFS — light decays by 1 per axis-aligned step. */
    @JvmStatic
    fun contributionAt(x: Int, y: Int, z: Int): Int {
        val snap = snapshot
        val xyz = snap.xyz
        if (xyz.isEmpty()) return 0
        val lev = snap.lightLevel
        var max = 0
        var i = 0
        while (i < xyz.size) {
            val dist = abs(x - xyz[i]) + abs(y - xyz[i + 1]) + abs(z - xyz[i + 2])
            val contrib = lev - dist
            if (contrib > max) max = contrib
            i += 3
        }
        return max
    }

    /** Atomically replace the active orb set with positions packed
     *  via [net.minecraft.core.BlockPos.asLong]. The driver publishes
     *  once per tick after working out which orbs are in range. */
    fun setActive(packedPositions: List<Long>, level: Int) {
        val n = packedPositions.size
        if (n == 0) {
            snapshot = EMPTY
            return
        }
        val xyz = IntArray(n * 3)
        for (i in 0 until n) {
            val p = packedPositions[i]
            xyz[i * 3] = net.minecraft.core.BlockPos.getX(p)
            xyz[i * 3 + 1] = net.minecraft.core.BlockPos.getY(p)
            xyz[i * 3 + 2] = net.minecraft.core.BlockPos.getZ(p)
        }
        snapshot = Snapshot(xyz, level)
    }
}
