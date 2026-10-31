package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.levelgen.Heightmap
import org.shipwrights.enderkinesis.dimension.TreeSegment
import org.shipwrights.enderkinesis.dimension.TreeTip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a ritual tree knows about its site: ground and canopy heights, water, and a copy of the
 * blocks around it. [survey] runs on the server thread; everything else reads only the copy, so the
 * off-thread build can use it. The ecology rules live here: roots seek water and low ground, the
 * crown leans to open sky, and branches bend around what even roots cannot break.
 */
internal class WohlonTreeSite private constructor(
    private val cx: Int,
    private val cz: Int,
    private val radius: Int,
    private val ground: IntArray,
    private val canopyTop: IntArray,
    private val water: java.util.BitSet,
    private val minSectionY: Int,
    private val sectionsY: Int,
    private val chunkMinX: Int,
    private val chunkMinZ: Int,
    private val chunksX: Int,
    private val chunksZ: Int,
    private val sections: Array<PalettedContainer<BlockState>?>,
) {
    private val side = radius * 2 + 1

    private fun column(x: Int, z: Int): Int {
        val dx = (x - cx).coerceIn(-radius, radius) + radius
        val dz = (z - cz).coerceIn(-radius, radius) + radius
        return dz * side + dx
    }

    /** Top block that stops motion, leaves skipped, water counted. Clamped to the survey edge. */
    fun groundY(x: Int, z: Int): Int = ground[column(x, z)]

    /** Top block that stops motion, leaves counted: what shades the crown. */
    fun canopyTopY(x: Int, z: Int): Int = canopyTop[column(x, z)]

    fun isWater(x: Int, z: Int): Boolean = water[column(x, z)]

    /** The copied block, or null outside the copy. */
    fun stateAt(x: Int, y: Int, z: Int): BlockState? {
        val chx = (x shr 4) - chunkMinX
        val chz = (z shr 4) - chunkMinZ
        val sy = (y shr 4) - minSectionY
        if (chx !in 0 until chunksX || chz !in 0 until chunksZ || sy !in 0 until sectionsY) return null
        val container = sections[(chz * chunksX + chx) * sectionsY + sy] ?: return null
        return container.get(x and 15, y and 15, z and 15)
    }

    private fun sectionCopy(chx: Int, chz: Int, sy: Int): PalettedContainer<BlockState>? = sections[(chz * chunksX + chx) * sectionsY + sy]

    // ----- Roots: hydrotropism -----

    /**
     * Heading of a root arm near [baseAngle], turned within [spread] radians toward water and lower
     * ground, and its reach: longer to get to water, shorter on a climb. The arms keep apart, as each
     * may turn only inside its own share of the circle.
     */
    fun rootArm(baseX: Double, baseZ: Double, baseY: Int, baseAngle: Double, spread: Double, reach: Int): DoubleArray {
        var bestAngle = baseAngle
        var bestScore = -Double.MAX_VALUE
        for (k in -ROOT_TURN_STEPS..ROOT_TURN_STEPS) {
            val a = baseAngle + spread * k / ROOT_TURN_STEPS
            val score = rootScore(baseX, baseZ, baseY, a, reach + ROOT_LOOK_PAST)
            if (score > bestScore) {
                bestScore = score
                bestAngle = a
            }
        }
        val waterAt = firstWater(baseX, baseZ, bestAngle, reach + ROOT_LOOK_PAST)
        val climb = meanClimb(baseX, baseZ, baseY, bestAngle, reach)
        val newReach = when {
            waterAt > 0 -> max(reach, min(waterAt + ROOT_INTO_WATER, ROOT_MAX_REACH))
            climb > ROOT_CLIMB_SHORTEN -> (reach * ROOT_CLIMB_FACTOR).toInt()
            else -> reach
        }
        return doubleArrayOf(bestAngle, newReach.toDouble())
    }

    private fun rootScore(bx: Double, bz: Double, baseY: Int, angle: Double, reach: Int): Double {
        var score = 0.0
        val ca = cos(angle); val sa = sin(angle)
        var d = ROOT_SAMPLE_STEP
        while (d <= reach) {
            val x = (bx + ca * d).toInt(); val z = (bz + sa * d).toInt()
            // Near water counts most: a root takes the shortest way to it.
            val nearness = 1.0 - d.toDouble() / (reach + 1)
            if (isWater(x, z)) score += WATER_WEIGHT * (0.5 + nearness)
            val dy = groundY(x, z) - baseY
            score += if (dy < 0) min(-dy, DOWNHILL_CAP) * DOWNHILL_WEIGHT else -min(dy, UPHILL_CAP) * UPHILL_WEIGHT
            d += ROOT_SAMPLE_STEP
        }
        return score
    }

    private fun firstWater(bx: Double, bz: Double, angle: Double, reach: Int): Int {
        val ca = cos(angle); val sa = sin(angle)
        for (d in ROOT_SAMPLE_STEP..reach step 2) {
            if (isWater((bx + ca * d).toInt(), (bz + sa * d).toInt())) return d
        }
        return -1
    }

    private fun meanClimb(bx: Double, bz: Double, baseY: Int, angle: Double, reach: Int): Double {
        val ca = cos(angle); val sa = sin(angle)
        var sum = 0.0; var n = 0
        var d = ROOT_SAMPLE_STEP
        while (d <= reach) {
            sum += groundY((bx + ca * d).toInt(), (bz + sa * d).toInt()) - baseY
            n++
            d += ROOT_SAMPLE_STEP
        }
        return if (n == 0) 0.0 else sum / n
    }

    /** How far [topY] sits above the median ground of a ring round the trunk; a building or hilltop under the candle counts. */
    fun elevation(baseX: Double, baseZ: Double, topY: Int): Int {
        val heights = IntArray(ELEVATION_ANGLES * ELEVATION_RADII.size)
        var n = 0
        for (r in ELEVATION_RADII) {
            for (k in 0 until ELEVATION_ANGLES) {
                val a = k * 2.0 * PI / ELEVATION_ANGLES
                heights[n++] = groundY((baseX + cos(a) * r).toInt(), (baseZ + sin(a) * r).toInt())
            }
        }
        heights.sort()
        return max(0, topY - heights[n / 2])
    }

    // ----- Crown: phototropism -----

    /** Light around a crown whose lowest leaves are at [crownBottomY]: open share of each sector, from 0 to 1. */
    fun sectorLight(baseX: Double, baseZ: Double, crownBottomY: Double): DoubleArray {
        val light = DoubleArray(SECTORS)
        for (s in 0 until SECTORS) {
            val a = (s + 0.5) * 2.0 * PI / SECTORS
            var shade = 0.0; var n = 0
            var r = LIGHT_MIN_RADIUS
            while (r <= LIGHT_MAX_RADIUS) {
                for (off in -1..1) {
                    val aa = a + off * (PI / SECTORS) * 0.6
                    val top = canopyTopY((baseX + cos(aa) * r).toInt(), (baseZ + sin(aa) * r).toInt())
                    // Higher and nearer cover shades more.
                    shade += ((top - crownBottomY) / LIGHT_FULL_SHADE).coerceIn(0.0, 1.0) * (1.0 - 0.5 * r / LIGHT_MAX_RADIUS)
                    n++
                }
                r += LIGHT_STEP
            }
            light[s] = 1.0 - (shade / n).coerceIn(0.0, 1.0)
        }
        return light
    }

    // ----- Grow around obstacles -----

    /**
     * Moves the skeleton away from blocks that even roots cannot break, by a distance field over a
     * coarse grid. Each segment end takes its own push plus a decaying share of its parent's, so a
     * branch bends as a whole and its children follow it, instead of single voxels jumping. [segments]
     * must list each segment after the segment that ends at its start.
     */
    fun bendAround(
        segments: List<TreeSegment>, tips: List<TreeTip>, isObstacle: (BlockState) -> Boolean,
    ): Pair<List<TreeSegment>, List<TreeTip>> {
        if (segments.isEmpty()) return segments to tips
        val field = ObstacleField.build(this, segments, isObstacle) ?: return segments to tips
        val shift = HashMap<Long, DoubleArray>(segments.size * 2)
        val out = ArrayList<TreeSegment>(segments.size)
        for (seg in segments) {
            val startKey = BlockPos.asLong(seg.startX.toInt(), seg.startY.toInt(), seg.startZ.toInt())
            val startShift = shift[startKey] ?: ZERO
            val endKey = BlockPos.asLong(seg.endX.toInt(), seg.endY.toInt(), seg.endZ.toInt())
            val own = field.push(seg.endX + startShift[0], seg.endY + startShift[1], seg.endZ + startShift[2], seg.endRadius + CLEARANCE)
            val endShift = doubleArrayOf(
                startShift[0] * FOLLOW + own[0], startShift[1] * FOLLOW + own[1], startShift[2] * FOLLOW + own[2],
            )
            clampLength(endShift, MAX_BEND)
            shift.putIfAbsent(endKey, endShift)
            out.add(
                TreeSegment(
                    seg.startX + startShift[0], seg.startY + startShift[1], seg.startZ + startShift[2],
                    seg.endX + endShift[0], seg.endY + endShift[1], seg.endZ + endShift[2],
                    seg.startRadius, seg.endRadius,
                ),
            )
        }
        val outTips = tips.map { tip ->
            val s = shift[BlockPos.asLong(tip.x, tip.y, tip.z)] ?: ZERO
            TreeTip((tip.x + s[0]).toInt(), (tip.y + s[1]).toInt(), (tip.z + s[2]).toInt())
        }
        return out to outTips
    }

    private fun clampLength(v: DoubleArray, maxLen: Double) {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len <= maxLen) return
        val k = maxLen / len
        v[0] *= k; v[1] *= k; v[2] *= k
    }

    /** Blocks to the nearest obstacle on a [CELL]-block grid round the skeleton, out to [FIELD_REACH] cells. */
    private class ObstacleField(
        private val ox: Int, private val oy: Int, private val oz: Int,
        private val nx: Int, private val ny: Int, private val nz: Int,
        private val dist: ByteArray,
    ) {
        private fun at(x: Int, y: Int, z: Int): Int {
            if (x !in 0 until nx || y !in 0 until ny || z !in 0 until nz) return FIELD_REACH
            return dist[(z * ny + y) * nx + x].toInt()
        }

        /** Push, in blocks, that moves a point with [clearance] off the nearest obstacle. */
        fun push(px: Double, py: Double, pz: Double, clearance: Double): DoubleArray {
            val gx = ((px - ox) / CELL).toInt(); val gy = ((py - oy) / CELL).toInt(); val gz = ((pz - oz) / CELL).toInt()
            val d = at(gx, gy, gz) * CELL.toDouble()
            if (d >= clearance) return ZERO
            // Down the distance field's gradient points to the obstacle; push the other way.
            var vx = (at(gx + 1, gy, gz) - at(gx - 1, gy, gz)).toDouble()
            var vy = (at(gx, gy + 1, gz) - at(gx, gy - 1, gz)).toDouble()
            var vz = (at(gx, gy, gz + 1) - at(gx, gy, gz - 1)).toDouble()
            val len = sqrt(vx * vx + vy * vy + vz * vz)
            if (len < 1.0e-6) {
                // Inside a flat region of the field: lift, as a branch climbs over what it meets.
                vx = 0.0; vy = 1.0; vz = 0.0
            } else {
                vx /= len; vy /= len; vz /= len
            }
            val amount = clearance - d
            return doubleArrayOf(vx * amount, vy * amount, vz * amount)
        }

        companion object {
            fun build(site: WohlonTreeSite, segments: List<TreeSegment>, isObstacle: (BlockState) -> Boolean): ObstacleField? {
                var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
                var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
                for (s in segments) {
                    minX = min(minX, min(s.startX, s.endX)); maxX = max(maxX, max(s.startX, s.endX))
                    minY = min(minY, min(s.startY, s.endY)); maxY = max(maxY, max(s.startY, s.endY))
                    minZ = min(minZ, min(s.startZ, s.endZ)); maxZ = max(maxZ, max(s.startZ, s.endZ))
                }
                val pad = FIELD_REACH * CELL + 4
                val ox = minX.toInt() - pad; val oy = minY.toInt() - pad; val oz = minZ.toInt() - pad
                val nx = ((maxX.toInt() + pad - ox) / CELL + 1).coerceAtMost(MAX_CELLS)
                val ny = ((maxY.toInt() + pad - oy) / CELL + 1).coerceAtMost(MAX_CELLS)
                val nz = ((maxZ.toInt() + pad - oz) / CELL + 1).coerceAtMost(MAX_CELLS)
                val dist = ByteArray(nx * ny * nz) { FIELD_REACH.toByte() }
                val queue = IntArray(nx * ny * nz)
                var tail = 0
                var any = false
                for (gz in 0 until nz) for (gy in 0 until ny) for (gx in 0 until nx) {
                    if (!site.cellHasObstacle(ox + gx * CELL, oy + gy * CELL, oz + gz * CELL, isObstacle)) continue
                    val i = (gz * ny + gy) * nx + gx
                    dist[i] = 0
                    queue[tail++] = i
                    any = true
                }
                if (!any) return null
                // Breadth-first out to FIELD_REACH; each cell enters the queue once, so the loop is bounded.
                var head = 0
                while (head < tail) {
                    val i = queue[head++]
                    val d = dist[i] + 1
                    if (d >= FIELD_REACH) continue
                    val gx = i % nx; val gy = (i / nx) % ny; val gz = i / (nx * ny)
                    for (n in 0 until 6) {
                        val x = gx + NX[n]; val y = gy + NY[n]; val z = gz + NZ[n]
                        if (x !in 0 until nx || y !in 0 until ny || z !in 0 until nz) continue
                        val j = (z * ny + y) * nx + x
                        if (dist[j] <= d) continue
                        dist[j] = d.toByte()
                        queue[tail++] = j
                    }
                }
                return ObstacleField(ox, oy, oz, nx, ny, nz, dist)
            }

            private val NX = intArrayOf(1, -1, 0, 0, 0, 0)
            private val NY = intArrayOf(0, 0, 1, -1, 0, 0)
            private val NZ = intArrayOf(0, 0, 0, 0, 1, -1)
        }
    }

    private fun cellHasObstacle(x0: Int, y0: Int, z0: Int, isObstacle: (BlockState) -> Boolean): Boolean {
        val chx = (x0 shr 4) - chunkMinX; val chz = (z0 shr 4) - chunkMinZ; val sy = (y0 shr 4) - minSectionY
        if (chx !in 0 until chunksX || chz !in 0 until chunksZ || sy !in 0 until sectionsY) return false
        // A section with no obstacle anywhere is skipped whole; most of the volume is air and soil.
        val key = (chz * chunksX + chx) * sectionsY + sy
        val known = sectionHasObstacle[key]
        if (known == SECTION_CLEAR) return false
        if (known == SECTION_UNKNOWN) {
            val container = sectionCopy(chx, chz, sy)
            val has = container != null && container.maybeHas { isObstacle(it) }
            sectionHasObstacle[key] = if (has) SECTION_HAS else SECTION_CLEAR
            if (!has) return false
        }
        for (dz in 0 until CELL) for (dy in 0 until CELL) for (dx in 0 until CELL) {
            val state = stateAt(x0 + dx, y0 + dy, z0 + dz) ?: continue
            if (isObstacle(state)) return true
        }
        return false
    }

    private val sectionHasObstacle = ByteArray(sections.size) { SECTION_UNKNOWN }

    companion object {
        // Roots.
        private const val ROOT_TURN_STEPS = 4
        private const val ROOT_SAMPLE_STEP = 4
        private const val ROOT_LOOK_PAST = 12
        private const val ROOT_INTO_WATER = 6
        private const val ROOT_MAX_REACH = 52
        private const val ROOT_CLIMB_SHORTEN = 4.0
        private const val ROOT_CLIMB_FACTOR = 0.75
        private const val WATER_WEIGHT = 4.0
        private const val DOWNHILL_WEIGHT = 0.5
        private const val DOWNHILL_CAP = 6
        private const val UPHILL_WEIGHT = 0.4
        private const val UPHILL_CAP = 10

        // Elevation ring: out past a tower's walls, inside the survey.
        private const val ELEVATION_ANGLES = 24
        private val ELEVATION_RADII = intArrayOf(12, 20, 28)

        // Crown light.
        const val SECTORS = 12
        private const val LIGHT_MIN_RADIUS = 12
        private const val LIGHT_MAX_RADIUS = 60
        private const val LIGHT_STEP = 6
        private const val LIGHT_FULL_SHADE = 30.0

        // Bending: grid cell size, field reach in cells, clearance past a branch's radius, the share of
        // the parent's bend a child keeps, and the most a point may move.
        private const val CELL = 2
        private const val FIELD_REACH = 5
        private const val MAX_CELLS = 160
        private const val CLEARANCE = 1.5
        private const val FOLLOW = 0.85
        private const val MAX_BEND = 8.0

        private const val SECTION_UNKNOWN: Byte = 0
        private const val SECTION_CLEAR: Byte = 1
        private const val SECTION_HAS: Byte = 2

        private val ZERO = DoubleArray(3)

        /** Server thread. Reads heights and copies the chunk sections in reach of the tree. */
        fun survey(level: ServerLevel, centre: BlockPos, radius: Int, below: Int, above: Int): WohlonTreeSite {
            val side = radius * 2 + 1
            val ground = IntArray(side * side)
            val canopy = IntArray(side * side)
            val water = java.util.BitSet(side * side)
            val probe = BlockPos.MutableBlockPos()
            val centreGround = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centre.x, centre.z) - 1
            // A disc, as a height read can load its chunk: the corners of the square reach chunks the tree never uses.
            ground.fill(centreGround)
            canopy.fill(centreGround)
            for (dz in -radius..radius) {
                for (dx in -radius..radius) {
                    if (dx * dx + dz * dz > radius * radius) continue
                    val x = centre.x + dx; val z = centre.z + dz
                    val i = (dz + radius) * side + (dx + radius)
                    val g = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
                    ground[i] = g
                    canopy[i] = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1
                    probe.set(x, g, z)
                    if (level.getFluidState(probe).`is`(FluidTags.WATER)) water.set(i)
                }
            }
            val chunkMinX = (centre.x - radius) shr 4; val chunkMaxX = (centre.x + radius) shr 4
            val chunkMinZ = (centre.z - radius) shr 4; val chunkMaxZ = (centre.z + radius) shr 4
            val minSectionY = (centre.y - below) shr 4
            val sectionsY = ((centre.y + above) shr 4) - minSectionY + 1
            val chunksX = chunkMaxX - chunkMinX + 1; val chunksZ = chunkMaxZ - chunkMinZ + 1
            val sections = arrayOfNulls<PalettedContainer<BlockState>>(chunksX * chunksZ * sectionsY)
            for (chz in 0 until chunksZ) for (chx in 0 until chunksX) {
                val chunk = level.chunkSource.getChunkNow(chunkMinX + chx, chunkMinZ + chz) ?: continue
                for (sy in 0 until sectionsY) {
                    val idx = chunk.getSectionIndexFromSectionY(minSectionY + sy)
                    if (idx !in chunk.sections.indices) continue
                    val section = chunk.sections[idx]
                    if (section.hasOnlyAir()) continue
                    // A copy: the off-thread build must not read the live container.
                    sections[(chz * chunksX + chx) * sectionsY + sy] = section.states.copy()
                }
            }
            return WohlonTreeSite(
                centre.x, centre.z, radius, ground, canopy, water,
                minSectionY, sectionsY, chunkMinX, chunkMinZ, chunksX, chunksZ, sections,
            )
        }
    }
}
