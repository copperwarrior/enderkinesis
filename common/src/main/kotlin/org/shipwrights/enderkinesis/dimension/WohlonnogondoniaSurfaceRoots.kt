package org.shipwrights.enderkinesis.dimension

import kotlin.math.cos
import kotlin.math.sin

/**
 * Surface-root path geometry shared between the chunkgen and the Overworld grower so the
 * painted shape is byte-identical across both. Stateless — all scratch is local, so C2ME's
 * concurrent `fillFromNoise` workers can invoke from many threads.
 *
 * Shaped like real arching roots (mangrove props, buttress laterals): separate arches that lift
 * off, span 12–30 blocks and set back down, with half-buried runs between them; arches are
 * tallest near the hub and shrink as the root tapers, and a span over water stretches until it
 * lands. Roots taper from hub to tip and fork by roughly area-preserving rules.
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
    /** How fast the root eases toward its target height: lag bridges short dips. */
    const val TUNNEL_Y_TRACK_BIAS = 0.35
    /** Share of the root's radius sunk below the ground: half-buried, not perched. */
    const val ROOT_BURY = 0.4

    const val ARCH_SPAN_MIN = 12.0
    const val ARCH_SPAN_MAX = 30.0
    /** Arch height as a share of its span, before the taper scale. */
    const val ARCH_RISE_MIN = 0.15
    const val ARCH_RISE_MAX = 0.35
    /** Thin roots still arch, but at this share of a hub-thick root's height. */
    const val ARCH_TIP_SCALE = 0.35
    /** Longest a span may stretch while looking for land past open water. */
    const val ARCH_SPAN_WATER_MAX = 45.0
    const val GROUND_RUN_MIN = 3.0
    const val GROUND_RUN_MAX = 10.0
    /** How tightly the root follows its arch line (the ground run uses [TUNNEL_Y_TRACK_BIAS]). */
    const val ARCH_TRACK_BIAS = 0.7
    /** Share of roots that lift straight into an arch at the hub. */
    const val ARCH_AT_HUB_CHANCE = 0.6

    const val TUNNEL_BRANCH_FIRST_STEP = 12
    const val TUNNEL_BRANCH_STRIDE = 16
    const val TUNNEL_BRANCH_MIN_STEPS = 20
    const val TUNNEL_BRANCH_MAX_STEPS = 40

    const val ROOT_HUB_RADIUS = 3.5
    const val ROOT_TIP_RADIUS = 1.0
    /** Tip-ward bias of the taper: roots lose girth quickly near the hub, slowly near the tip. */
    const val ROOT_TAPER_EXPONENT = 1.3
    /** Lateral starts at this share of the parent's radius at the branch point. */
    const val ROOT_LATERAL_SHARE = 0.6
    /** Share of branch points that are near-equal forks rather than side laterals. */
    const val ROOT_FORK_CHANCE = 0.3

    const val STARBURST_PATH_COUNT = 12
    const val STARBURST_PATH_STEPS = 40
    const val STARBURST_PITCH_JITTER = 0.2
    const val STARBURST_YAW_JITTER = 0.25
    const val STARBURST_RADIUS = 2

    /** Share of the yaw error to a target that a reaching root turns away each step. */
    const val REACH_PULL = 0.35
    const val REACH_ARRIVE = 1.5
    /** Step budget over the straight-line distance, for the wander and the arches. */
    const val REACH_STEP_SLACK = 1.4

    /** Packed `[x0,y0,z0, x1,y1,z1, …]`, with the radius at the first and last point. */
    class TunnelPath(
        @JvmField val points: IntArray,
        @JvmField val count: Int,
        @JvmField val startRadius: Double,
        @JvmField val endRadius: Double,
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
        waterTopY: Int = Int.MIN_VALUE,
    ): Array<TunnelPath> {
        val seed = wohlonHash32(regionX, regionZ, 0x70F1FA52.toInt())
        if ((seed and 0xF) == 0) return EMPTY_PATHS

        val startWX = regionX * TUNNEL_REGION_SIZE + regionStartOffset(regionX, regionZ, 1)
        val startWZ = regionZ * TUNNEL_REGION_SIZE + regionStartOffset(regionX, regionZ, 2)

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
                heightAt, yMin, yMax, groundOnly, waterTopY,
            )
        }

        val startYaw = wohlonHash01(seed, 3, 0) * Math.PI * 2.0
        val startPitch = (wohlonHash01(seed, 4, 0) - 0.5) * 0.4

        val main = walkPath(
            regionX, regionZ, pathIdx = 0,
            startX, startY, startZ, startYaw, startPitch,
            maxSteps = TUNNEL_MAX_STEPS,
            heightAt, yMin, yMax, groundOnly, waterTopY,
            startRadius = ROOT_HUB_RADIUS,
        )

        val results = ArrayList<TunnelPath>(1 + TUNNEL_MAX_STEPS / TUNNEL_BRANCH_STRIDE)
        results.add(main)
        val mainPts = main.points
        var stepIdx = TUNNEL_BRANCH_FIRST_STEP
        var branchIdx = 0
        while (stepIdx < main.count - 4) {
            val branchSeed = wohlonHash32(regionX, regionZ, 300 + branchIdx)
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
                // Forks split at 30–45°, side laterals leave at 60–90° and start thinner.
                val fork = ((branchSeed ushr 29) and 0x7) / 8.0 < ROOT_FORK_CHANCE
                val spread = ((branchSeed ushr 5) and 0xFF) / 256.0
                val yawOffset = sign * if (fork) {
                    Math.toRadians(30.0 + spread * 15.0)
                } else {
                    Math.toRadians(60.0 + spread * 30.0)
                }
                val parentR = radiusAt(main, stepIdx)
                val branchR = if (fork) parentR * Math.sqrt(0.5) else parentR * ROOT_LATERAL_SHARE
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
                        heightAt, yMin, yMax, groundOnly, waterTopY,
                        startRadius = branchR.coerceAtLeast(ROOT_TIP_RADIUS),
                    )
                )
            }
            stepIdx += TUNNEL_BRANCH_STRIDE
            branchIdx++
        }
        return results.toTypedArray()
    }

    /** Sphere-raster each step into [sink], at the path's tapered radius. No bounds checking —
     *  sink filters. Duplicates arrive (consecutive spheres overlap); dedupe at the sink if needed. */
    fun paintPath(path: TunnelPath, sink: (Int, Int, Int) -> Unit) {
        val pts = path.points
        for (i in 0 until path.count) {
            val r = radiusAt(path, i)
            val rSq = r * r
            val reach = Math.ceil(r).toInt()
            val px = pts[i * 3 + 0]
            val py = pts[i * 3 + 1]
            val pz = pts[i * 3 + 2]
            for (dx in -reach..reach) {
                for (dz in -reach..reach) {
                    val dxzSq = dx * dx + dz * dz
                    if (dxzSq > rSq) continue
                    for (dy in -reach..reach) {
                        if (dxzSq + dy * dy > rSq) continue
                        sink(px + dx, py + dy, pz + dz)
                    }
                }
            }
        }
    }

    /** Radius at step [i]: from [TunnelPath.startRadius] down to [TunnelPath.endRadius] at the tip. */
    fun radiusAt(path: TunnelPath, i: Int): Double = taperedRadius(path.startRadius, path.endRadius, i, path.count)

    private fun taperedRadius(start: Double, end: Double, i: Int, count: Int): Double {
        val t = if (count <= 1) 0.0 else i.toDouble() / (count - 1)
        return end + (start - end) * Math.pow(1.0 - t, ROOT_TAPER_EXPONENT)
    }

    private fun buildStarburst(
        regionX: Int, regionZ: Int,
        startX: Double, startY: Double, startZ: Double,
        heightAt: (Int, Int) -> Int,
        yMin: Int, yMax: Int, groundOnly: Boolean, waterTopY: Int,
    ): Array<TunnelPath> {
        return buildStarburstFromHub(
            seedA = regionX, seedB = regionZ,
            startX = startX, startY = startY, startZ = startZ,
            pathCount = STARBURST_PATH_COUNT,
            pathSteps = STARBURST_PATH_STEPS,
            heightAt = heightAt, yMin = yMin, yMax = yMax,
            groundOnly = groundOnly, waterTopY = waterTopY,
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
        waterTopY: Int = Int.MIN_VALUE,
    ): Array<TunnelPath> {
        val results = ArrayList<TunnelPath>(pathCount)
        val sliceWidth = (2.0 * Math.PI) / pathCount
        for (i in 0 until pathCount) {
            val yawJitter = (wohlonHash01(seedA, seedB, 700 + i) - 0.5) * STARBURST_YAW_JITTER
            val pitchJitter = (wohlonHash01(seedA, seedB, 800 + i) - 0.5) * STARBURST_PITCH_JITTER
            val yaw = i * sliceWidth + yawJitter
            results.add(
                walkPath(
                    seedA, seedB, pathIdx = i,
                    startX, startY, startZ, yaw, pitchJitter,
                    maxSteps = pathSteps,
                    heightAt, yMin, yMax, groundOnly, waterTopY,
                    startRadius = STARBURST_RADIUS.toDouble(),
                )
            )
        }
        return results.toTypedArray()
    }

    /** Walker for a reaching root, aimed at the target in XZ; [walk] it one leg at a time. */
    fun startReachingWalker(
        seedA: Int, seedB: Int,
        startX: Double, startY: Double, startZ: Double,
        targetX: Double, targetZ: Double,
    ): RootWalker {
        val dx = targetX - startX
        val dz = targetZ - startZ
        val dist = Math.sqrt(dx * dx + dz * dz)
        return RootWalker(
            seedA, seedB, pathIdx = 0,
            startX, startY, startZ, Math.atan2(dz, dx), 0.0,
            taperSteps = Math.ceil(dist / TUNNEL_STEP_LEN * REACH_STEP_SLACK).toInt() + 4,
            startRadius = ROOT_HUB_RADIUS,
            targetX = targetX, targetZ = targetZ,
        )
    }

    private fun walkPath(
        regionX: Int, regionZ: Int, pathIdx: Int,
        startX: Double, startY: Double, startZ: Double,
        startYaw: Double, startPitch: Double,
        maxSteps: Int,
        heightAt: (Int, Int) -> Int,
        yMin: Int, yMax: Int,
        groundOnly: Boolean,
        waterTopY: Int,
        startRadius: Double,
    ): TunnelPath {
        val walker = RootWalker(regionX, regionZ, pathIdx, startX, startY, startZ, startYaw, startPitch, maxSteps, startRadius)
        val path = walker.walk(maxSteps, heightAt, yMin, yMax, groundOnly, waterTopY)
        // The fixed radii keep chunkgen output the same as before; a leg's re-derived ones round differently.
        return TunnelPath(path.points, path.count, startRadius, ROOT_TIP_RADIUS)
    }

    /** One root's walk state. A reaching walker ([targetX] set) keeps it between legs, so arches carry over. */
    class RootWalker(
        @JvmField val regionX: Int, @JvmField val regionZ: Int, @JvmField val pathIdx: Int,
        @JvmField var x: Double, @JvmField var y: Double, @JvmField var z: Double,
        @JvmField var yaw: Double, startPitch: Double,
        /** Step count the radius tapers over. */
        @JvmField val taperSteps: Int,
        @JvmField val startRadius: Double,
        @JvmField val targetX: Double = Double.NaN,
        @JvmField val targetZ: Double = Double.NaN,
    ) {
        @JvmField var pitch = startPitch.coerceIn(-TUNNEL_PITCH_LIMIT, TUNNEL_PITCH_LIMIT)
        @JvmField var step = 0
        @JvmField var archLeft = 0
        @JvmField var archLen = 0
        @JvmField var archStartY = 0.0
        @JvmField var archEndY = 0.0
        @JvmField var archHeight = 0.0
        private val pathSeed = wohlonHash32(regionX * 31 + pathIdx, regionZ, 0x2A7C4E5)
        @JvmField var groundLeft = if (unit(pathSeed) < ARCH_AT_HUB_CHANCE) 0 else runSteps(pathSeed, 1)
        @JvmField var arrived = false

        private val reaching: Boolean get() = !targetX.isNaN()

        fun radiusAt(step: Int): Double = taperedRadius(startRadius, ROOT_TIP_RADIUS, step.coerceAtMost(taperSteps - 1), taperSteps)

        /** Up to [maxSteps] more steps; the radii run from this walk's first step to its last. */
        @Suppress("LongMethod") // One step of the walk; chunkgen shares it, so it stays whole.
        fun walk(
            maxSteps: Int,
            heightAt: (Int, Int) -> Int,
            yMin: Int, yMax: Int,
            groundOnly: Boolean,
            waterTopY: Int,
        ): TunnelPath {
            val firstStep = step
            val points = IntArray(maxSteps * 3)
            var count = 0
            @Suppress("UnusedPrivateProperty") // A bounded loop that breaks early; detekt reads the counter as a property.
            for (n in 0 until maxSteps) {
                var remaining = Double.POSITIVE_INFINITY
                if (reaching) {
                    remaining = Math.hypot(targetX - x, targetZ - z)
                    if (remaining < REACH_ARRIVE) {
                        arrived = true
                        break
                    }
                    val error = Math.atan2(targetZ - z, targetX - x) - yaw
                    yaw += Math.atan2(sin(error), cos(error)) * REACH_PULL
                }
                val cosp = cos(pitch)
                x += cos(yaw) * cosp * TUNNEL_STEP_LEN
                z += sin(yaw) * cosp * TUNNEL_STEP_LEN

                val turnSeed = wohlonHash32(regionX * 31 + pathIdx, regionZ, 100 + step)
                yaw += ((turnSeed and 0xFFFF) / 65536.0 - 0.5) * TUNNEL_YAW_TURN
                pitch = (
                    pitch + (((turnSeed ushr 16) and 0xFFFF) / 65536.0 - 0.5) * TUNNEL_PITCH_TURN
                    ).coerceIn(-TUNNEL_PITCH_LIMIT, TUNNEL_PITCH_LIMIT)

                val r = taperedRadius(startRadius, ROOT_TIP_RADIUS, step.coerceAtMost(taperSteps - 1), taperSteps)
                val ground = Math.max(heightAt(x.toInt(), z.toInt()), waterTopY)
                val base = ground - r * ROOT_BURY

                if (archLeft == 0 && groundLeft == 0) {
                    // Lift off: pick a span, stretch it past open water, and aim the far foot at the ground there.
                    val archSeed = wohlonHash32(regionX * 31 + pathIdx, regionZ, 500 + step)
                    // A reaching root sets its last foot down at the target, not past it.
                    var span = Math.min(ARCH_SPAN_MIN + unit(archSeed) * (ARCH_SPAN_MAX - ARCH_SPAN_MIN), remaining)
                    var footX = x + cos(yaw) * span
                    var footZ = z + sin(yaw) * span
                    while (span < ARCH_SPAN_WATER_MAX && heightAt(footX.toInt(), footZ.toInt()) < waterTopY) {
                        span += TUNNEL_STEP_LEN * 2
                        footX = x + cos(yaw) * span
                        footZ = z + sin(yaw) * span
                    }
                    archLen = Math.max(2, (span / TUNNEL_STEP_LEN).toInt())
                    archLeft = archLen
                    archStartY = y
                    val footR = taperedRadius(startRadius, ROOT_TIP_RADIUS, (step + archLen).coerceAtMost(taperSteps - 1), taperSteps)
                    archEndY = Math.max(heightAt(footX.toInt(), footZ.toInt()), waterTopY) - footR * ROOT_BURY
                    val rise = ARCH_RISE_MIN + unit(archSeed ushr 16) * (ARCH_RISE_MAX - ARCH_RISE_MIN)
                    val thickness = ARCH_TIP_SCALE + (1.0 - ARCH_TIP_SCALE) * (r / ROOT_HUB_RADIUS).coerceAtMost(1.0)
                    archHeight = span * rise * thickness
                }

                if (archLeft > 0) {
                    val t = (archLen - archLeft + 1).toDouble() / archLen
                    val line = archStartY + (archEndY - archStartY) * t
                    val target = Math.max(line + 4.0 * archHeight * t * (1.0 - t), base)
                    y += (target - y) * ARCH_TRACK_BIAS
                    if (--archLeft == 0) groundLeft = runSteps(pathSeed, step)
                } else {
                    y += (base - y) * TUNNEL_Y_TRACK_BIAS
                    groundLeft--
                }
                if (groundOnly && y > ground) y = ground.toDouble()

                points[count * 3 + 0] = x.toInt()
                points[count * 3 + 1] = Math.round(y).toInt().coerceIn(yMin, yMax)
                points[count * 3 + 2] = z.toInt()
                count++
                step++
            }
            return TunnelPath(points, count, radiusAt(firstStep), radiusAt(maxOf(firstStep, step - 1)))
        }
    }

    /** Steps in a half-buried run between two arches. */
    private fun runSteps(pathSeed: Int, step: Int): Int {
        val blocks = GROUND_RUN_MIN + unit(wohlonHash32(pathSeed, step, 0x6E0D)) * (GROUND_RUN_MAX - GROUND_RUN_MIN)
        return Math.max(1, (blocks / TUNNEL_STEP_LEN).toInt())
    }

    private fun unit(h: Int): Double = (h and 0xFFFF) / 65536.0

    /** Hub offset inside a region. Modulo, not a bit mask: the region size is 48, and a mask
     *  of 47 never gives 16 to 31. */
    fun regionStartOffset(regionX: Int, regionZ: Int, axisSalt: Int): Int =
        Math.floorMod(wohlonHash32(regionX, regionZ, axisSalt), TUNNEL_REGION_SIZE)
}
