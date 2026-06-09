package org.shipwrights.enderkinesis.dimension

import kotlin.math.cos
import kotlin.math.sin

/**
 * Surface-root path geometry shared between the chunkgen and the Overworld grower so the
 * painted shape is byte-identical across both. Stateless — all scratch is local, so C2ME's
 * concurrent `fillFromNoise` workers can invoke from many threads.
 */
object WohlonnogondoniaSurfaceRoots {

    const val TUNNEL_REGION_SIZE_CHUNKS = 3
    const val TUNNEL_REGION_SIZE = TUNNEL_REGION_SIZE_CHUNKS * 16
    const val TUNNEL_REGION_SEARCH = 4

    const val TUNNEL_MAX_STEPS = 80
    const val TUNNEL_STEP_LEN = 1.5
    const val TUNNEL_YAW_TURN = 0.40
    const val TUNNEL_PITCH_TURN = 0.12
    const val TUNNEL_PITCH_LIMIT = 0.5
    const val TUNNEL_Y_TRACK_BIAS = 0.07

    const val TUNNEL_BRANCH_FIRST_STEP = 12
    const val TUNNEL_BRANCH_STRIDE = 16
    const val TUNNEL_BRANCH_MIN_STEPS = 20
    const val TUNNEL_BRANCH_MAX_STEPS = 40

    const val ROOT_TUBE_RADIUS = 3
    const val ROOT_TIP_TAPER_STEPS = 8

    const val STARBURST_PATH_COUNT = 12
    const val STARBURST_PATH_STEPS = 40
    const val STARBURST_PITCH_JITTER = 0.2
    const val STARBURST_YAW_JITTER = 0.25
    const val STARBURST_RADIUS = 2

    /** Packed `[x0,y0,z0, x1,y1,z1, …]`. */
    class TunnelPath(
        @JvmField val points: IntArray,
        @JvmField val count: Int,
        @JvmField val taperHeadSteps: Int = 0,
        @JvmField val taperTailSteps: Int = 0,
    )

    @JvmField
    val EMPTY_PATHS: Array<TunnelPath> = emptyArray()

    /**
     * Build every path originating in region `(regionX, regionZ)` — one main + 0..N branches.
     * Pure function of the region coords + [heightAt]. Returns [EMPTY_PATHS] for sparse-gap
     * regions (~6%) or starts inside [motherTreeExclusionSq].
     *
     * [groundOnly] pins Y to the local surface (Wohlon's airborne arcs are suppressed) — the
     * Overworld grower uses this. [starburst] replaces main+branches with N short tendrils
     * radiating from the hub at evenly-distributed yaws.
     */
    fun buildPaths(
        regionX: Int,
        regionZ: Int,
        heightAt: (Int, Int) -> Int,
        yMin: Int,
        yMax: Int,
        motherTreeExclusionSq: Long = 0L,
        groundOnly: Boolean = false,
        starburst: Boolean = false,
    ): Array<TunnelPath> {
        val seed = hash32(regionX, regionZ, 0x70F1FA52.toInt())
        if ((seed and 0xF) == 0) return EMPTY_PATHS

        val startWX = regionX * TUNNEL_REGION_SIZE +
            (hash32(regionX, regionZ, 1) and (TUNNEL_REGION_SIZE - 1))
        val startWZ = regionZ * TUNNEL_REGION_SIZE +
            (hash32(regionX, regionZ, 2) and (TUNNEL_REGION_SIZE - 1))

        if (motherTreeExclusionSq > 0L) {
            val startDistSq = startWX.toLong() * startWX + startWZ.toLong() * startWZ
            if (startDistSq < motherTreeExclusionSq) return EMPTY_PATHS
        }

        val startSurface = heightAt(startWX, startWZ)
        val startX = startWX.toDouble() + 0.5
        val startY = startSurface.toDouble()
        val startZ = startWZ.toDouble() + 0.5

        if (starburst) {
            return buildStarburst(
                regionX, regionZ,
                startX, startY, startZ,
                heightAt, yMin, yMax, groundOnly,
            )
        }

        val startYaw = hash01(seed, 3, 0) * Math.PI * 2.0
        val startPitch = (hash01(seed, 4, 0) - 0.5) * 0.4

        val main = walkPath(
            regionX, regionZ, pathIdx = 0,
            startX, startY, startZ, startYaw, startPitch,
            maxSteps = TUNNEL_MAX_STEPS,
            heightAt, yMin, yMax, groundOnly,
        )

        val results = ArrayList<TunnelPath>(1 + TUNNEL_MAX_STEPS / TUNNEL_BRANCH_STRIDE)
        results.add(main)
        val mainPts = main.points
        var stepIdx = TUNNEL_BRANCH_FIRST_STEP
        var branchIdx = 0
        while (stepIdx < main.count - 4) {
            val branchSeed = hash32(regionX, regionZ, 300 + branchIdx)
            if ((branchSeed and 0x3) == 0) {
                val bx = mainPts[stepIdx * 3 + 0].toDouble() + 0.5
                val by = mainPts[stepIdx * 3 + 1].toDouble()
                val bz = mainPts[stepIdx * 3 + 2].toDouble() + 0.5

                val nx = mainPts[(stepIdx + 1) * 3 + 0]
                val nz = mainPts[(stepIdx + 1) * 3 + 2]
                val baseYaw = Math.atan2(
                    (nz - mainPts[stepIdx * 3 + 2]).toDouble(),
                    (nx - mainPts[stepIdx * 3 + 0]).toDouble(),
                )
                val sign = if ((branchSeed and 0x10) == 0) 1.0 else -1.0
                val yawOffset = sign * (Math.PI / 3.0 +
                    ((branchSeed ushr 5) and 0xFF) / 256.0 * (Math.PI / 3.0))
                val branchYaw = baseYaw + yawOffset
                val branchPitch = (((branchSeed ushr 13) and 0xFFFF) /
                    65536.0 - 0.5) * 0.4
                val branchLen = TUNNEL_BRANCH_MIN_STEPS +
                    ((branchSeed ushr 21) and 0xFF) %
                    (TUNNEL_BRANCH_MAX_STEPS - TUNNEL_BRANCH_MIN_STEPS + 1)

                results.add(
                    walkPath(
                        regionX, regionZ, pathIdx = 1 + branchIdx,
                        bx, by, bz, branchYaw, branchPitch,
                        maxSteps = branchLen,
                        heightAt, yMin, yMax, groundOnly,
                    )
                )
            }
            stepIdx += TUNNEL_BRANCH_STRIDE
            branchIdx++
        }
        return results.toTypedArray()
    }

    /** Sphere-raster each step into [sink]. No bounds checking — sink filters. Duplicates
     *  arrive (consecutive spheres overlap heavily); dedupe at the sink if needed. */
    fun paintPath(
        path: TunnelPath,
        sink: (Int, Int, Int) -> Unit,
        baseR: Int = ROOT_TUBE_RADIUS,
    ) {
        val pts = path.points
        val n = path.count
        for (i in 0 until n) {
            val r = radiusAt(path, baseR, i)
            val rSqMax = r * r
            val px = pts[i * 3 + 0]
            val py = pts[i * 3 + 1]
            val pz = pts[i * 3 + 2]
            for (dx in -r..r) {
                val dxSq = dx * dx
                for (dz in -r..r) {
                    val dxzSq = dxSq + dz * dz
                    if (dxzSq > rSqMax) continue
                    for (dy in -r..r) {
                        if (dxzSq + dy * dy > rSqMax) continue
                        sink(px + dx, py + dy, pz + dz)
                    }
                }
            }
        }
    }

    /** Sphere radius at step `i` — full [baseR] in the middle, ramped to 1 over taper windows. */
    fun radiusAt(path: TunnelPath, baseR: Int, i: Int): Int =
        radiusD(path, baseR.toDouble(), i).toInt().coerceAtLeast(1)

    private fun radiusD(path: TunnelPath, baseR: Double, i: Int): Double {
        var r = baseR
        if (path.taperHeadSteps > 0 && i < path.taperHeadSteps) {
            val t = (i + 1).toDouble() / (path.taperHeadSteps + 1)
            val tapered = (baseR * t).coerceAtLeast(0.5)
            if (tapered < r) r = tapered
        }
        if (path.taperTailSteps > 0) {
            val fromEnd = path.count - 1 - i
            if (fromEnd < path.taperTailSteps) {
                val t = (fromEnd + 1).toDouble() / (path.taperTailSteps + 1)
                val tapered = (baseR * t).coerceAtLeast(0.5)
                if (tapered < r) r = tapered
            }
        }
        return r
    }

    private fun buildStarburst(
        regionX: Int, regionZ: Int,
        startX: Double, startY: Double, startZ: Double,
        heightAt: (Int, Int) -> Int,
        yMin: Int, yMax: Int, groundOnly: Boolean,
    ): Array<TunnelPath> {
        return buildStarburstFromHub(
            seedA = regionX, seedB = regionZ,
            startX = startX, startY = startY, startZ = startZ,
            pathCount = STARBURST_PATH_COUNT,
            pathSteps = STARBURST_PATH_STEPS,
            heightAt = heightAt, yMin = yMin, yMax = yMax,
            groundOnly = groundOnly,
        )
    }

    /** Starburst with explicit hub and `(seedA, seedB)` — used by the grower's chained child
     *  starbursts so successive generations don't replay the parent's pattern. */
    fun buildStarburstFromHub(
        seedA: Int, seedB: Int,
        startX: Double, startY: Double, startZ: Double,
        pathCount: Int,
        pathSteps: Int,
        heightAt: (Int, Int) -> Int,
        yMin: Int, yMax: Int, groundOnly: Boolean,
    ): Array<TunnelPath> {
        val results = ArrayList<TunnelPath>(pathCount)
        val sliceWidth = (2.0 * Math.PI) / pathCount
        for (i in 0 until pathCount) {
            val yawJitter = (hash01(seedA, seedB, 700 + i) - 0.5) * STARBURST_YAW_JITTER
            val pitchJitter = (hash01(seedA, seedB, 800 + i) - 0.5) * STARBURST_PITCH_JITTER
            val yaw = i * sliceWidth + yawJitter
            results.add(
                walkPath(
                    seedA, seedB, pathIdx = i,
                    startX, startY, startZ, yaw, pitchJitter,
                    maxSteps = pathSteps,
                    heightAt, yMin, yMax, groundOnly,
                )
            )
        }
        return results.toTypedArray()
    }

    private fun walkPath(
        regionX: Int, regionZ: Int, pathIdx: Int,
        startX: Double, startY: Double, startZ: Double,
        startYaw: Double, startPitch: Double,
        maxSteps: Int,
        heightAt: (Int, Int) -> Int,
        yMin: Int, yMax: Int,
        groundOnly: Boolean = false,
    ): TunnelPath {
        val paramSeed = hash32(regionX, regionZ, 0x57F1CE17.toInt() xor pathIdx)
        val yAmp = 12.0 + (paramSeed and 0x1F)
        val yPeriod = 30.0 + ((paramSeed ushr 5) and 0x1F)
        val yPhase = ((paramSeed ushr 10) and 0xFF) / 256.0 * 2.0 * Math.PI

        var x = startX
        var y = startY
        var z = startZ
        var yaw = startYaw
        var pitch = startPitch.coerceIn(-TUNNEL_PITCH_LIMIT, TUNNEL_PITCH_LIMIT)

        val points = IntArray(maxSteps * 3)
        var count = 0
        for (step in 0 until maxSteps) {
            val cosp = cos(pitch)
            x += cos(yaw) * cosp * TUNNEL_STEP_LEN
            y += sin(pitch) * TUNNEL_STEP_LEN
            z += sin(yaw) * cosp * TUNNEL_STEP_LEN

            val turnSeed = hash32(regionX * 31 + pathIdx, regionZ, 100 + step)
            yaw += ((turnSeed and 0xFFFF) / 65536.0 - 0.5) * TUNNEL_YAW_TURN
            pitch = (
                pitch + (((turnSeed ushr 16) and 0xFFFF) / 65536.0 - 0.5) * TUNNEL_PITCH_TURN
                ).coerceIn(-TUNNEL_PITCH_LIMIT, TUNNEL_PITCH_LIMIT)

            val localSurface = heightAt(x.toInt(), z.toInt())
            val targetY = localSurface + sin(
                yPhase + step * (2.0 * Math.PI / yPeriod)
            ) * yAmp
            y += (targetY - y) * TUNNEL_Y_TRACK_BIAS

            // Ground-ride: cap Y at surface so airborne arcs pin to ground; underground dives stay.
            if (groundOnly && y > localSurface) {
                y = localSurface.toDouble()
            }

            points[count * 3 + 0] = x.toInt()
            points[count * 3 + 1] = y.toInt().coerceIn(yMin, yMax)
            points[count * 3 + 2] = z.toInt()
            count++
        }

        val tipX = points[(count - 1) * 3 + 0]
        val tipY = points[(count - 1) * 3 + 1]
        val tipZ = points[(count - 1) * 3 + 2]
        val tipSurface = heightAt(tipX, tipZ)
        // Taper only applies to unclamped paths whose tip ended above ground.
        val taperTail = if (tipY > tipSurface) ROOT_TIP_TAPER_STEPS else 0

        return TunnelPath(points, count, taperTailSteps = taperTail)
    }

    fun hash32(a: Int, b: Int, salt: Int): Int {
        var h = a * 0x9E3779B1.toInt() xor (b * 0x85EBCA77.toInt()) xor (salt * 0xC2B2AE3D.toInt())
        h = (h xor (h ushr 15)) * 0x2C1B3C6D.toInt()
        h = (h xor (h ushr 12)) * 0x297A2D39.toInt()
        h = h xor (h ushr 15)
        return h
    }

    fun hash01(seed: Int, k1: Int, k2: Int): Double =
        (hash32(seed, k1, k2) and 0x7FFFFFFF) / 2147483648.0
}
