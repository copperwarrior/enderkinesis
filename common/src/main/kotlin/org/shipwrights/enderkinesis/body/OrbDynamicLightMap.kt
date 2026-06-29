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
 *  Storage is three parallel `IntArray`s + a single light-level int.
 *  Lookup walks the arrays and returns the maximum Manhattan-distance
 *  contribution. Reads are wait-free (volatile array reference; the
 *  arrays themselves are read-only between writes). Writes are
 *  publish-once-per-tick from the driver — we swap fresh arrays in,
 *  not mutate the live ones.
 */
object OrbDynamicLightMap {
    @Volatile private var posXs: IntArray = IntArray(0)
    @Volatile private var posYs: IntArray = IntArray(0)
    @Volatile private var posZs: IntArray = IntArray(0)
    @Volatile private var lightLevel: Int = 0

    @JvmStatic
    fun isEmpty(): Boolean = posXs.isEmpty()

    /** Maximum orb-light contribution at world position `(x, y, z)`,
     *  in `[0, 15]`. Each active orb contributes `lightLevel - manhattan(orb, here)`
     *  clamped to non-negative; the call returns the max across all
     *  active orbs. Manhattan distance matches vanilla's face-by-face
     *  BFS — light decays by 1 per axis-aligned step. */
    @JvmStatic
    fun contributionAt(x: Int, y: Int, z: Int): Int {
        // Snapshot the array references — they may be replaced
        // mid-call by the driver's `setActive`, but each snapshot is
        // internally consistent (we never mutate live arrays).
        val xs = posXs; val ys = posYs; val zs = posZs
        val lev = lightLevel
        if (xs.isEmpty()) return 0
        var max = 0
        for (i in xs.indices) {
            val dist = abs(x - xs[i]) + abs(y - ys[i]) + abs(z - zs[i])
            val contrib = lev - dist
            if (contrib > max) max = contrib
        }
        return max
    }

    /** Atomically replace the active orb set with positions packed
     *  via [net.minecraft.core.BlockPos.asLong]. The driver publishes
     *  once per tick after working out which orbs are in range. */
    fun setActive(packedPositions: List<Long>, level: Int) {
        val n = packedPositions.size
        val newX = IntArray(n)
        val newY = IntArray(n)
        val newZ = IntArray(n)
        for (i in 0 until n) {
            val p = packedPositions[i]
            newX[i] = net.minecraft.core.BlockPos.getX(p)
            newY[i] = net.minecraft.core.BlockPos.getY(p)
            newZ[i] = net.minecraft.core.BlockPos.getZ(p)
        }
        lightLevel = level
        posXs = newX; posYs = newY; posZs = newZ
    }
}
