package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import it.unimi.dsi.fastutil.floats.FloatArrayList
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.registry.EKBlocks
import kotlin.math.abs

/**
 * Started by the server's [org.shipwrights.enderkinesis.spell.SpellNetwork.MUD_CREEP] batch, as block shifts are.
 * The client draws the old block over the mud mesh and lets the mud creep through it.
 */
// Client thread only.
object WohlonMudCreepTracker {

    class Creep(val pos: Long, val old: BlockState, val start: Long) {
        /** Captured old-block quads, [QUAD_FLOATS] each; null until the next tick captures them. */
        var quads: FloatArray? = null
        var dirty = true
        /** The batch can arrive before the block update; only a block that was mud and then is not ends the creep. */
        var seenMud = false
        /** Game time at which the mud neighbour in each direction began its mask, or [NO_EDGE]. */
        val edgeStart = LongArray(DIRECTION_COUNT) { NO_EDGE }
        /** Bit per face that had no mud edge at the start; fixed for life so no edge mask ever disappears. -1: not set. */
        var fallbackFaces = -1
    }

    val creeps = Long2ObjectLinkedOpenHashMap<Creep>()
    private var trackedLevel: Level? = null
    private val scratch = BlockPos.MutableBlockPos()
    private val neighbour = BlockPos.MutableBlockPos()
    private val capture = QuadCapture()
    private val identity = PoseStack()
    private val random = RandomSource.create()
    private val DIRECTIONS: Array<Direction> = Direction.values()

    fun start(level: Level, pos: Long, oldId: Int) {
        useLevel(level)
        val old = Block.stateById(oldId)
        if (old.isAir) return
        if (creeps.size >= MAX_CREEPS && !creeps.containsKey(pos)) creeps.removeFirst()
        creeps.put(pos, Creep(pos, old, level.gameTime))
        // A creeping neighbour gains a mud edge here.
        for (dir in DIRECTIONS) creeps.get(BlockPos.offset(pos, dir))?.dirty = true
    }

    fun tick(level: Level) {
        useLevel(level)
        if (creeps.isEmpty()) return
        val now = level.gameTime
        val mud = EKBlocks.WOHLON_MUD.get()
        val it = creeps.values.iterator()
        while (it.hasNext()) {
            val creep = it.next()
            scratch.set(creep.pos)
            val isMud = level.getBlockState(scratch).`is`(mud)
            if (isMud) creep.seenMud = true
            if (now - creep.start >= DURATION_TICKS || (creep.seenMud && !isMud) || !level.isLoaded(scratch)) {
                it.remove()
                continue
            }
            if (creep.dirty) {
                creep.dirty = false
                creep.quads = captureQuads(level, creep.old, scratch)
                connectEdges(level, creep, now)
            }
        }
    }

    fun clear() {
        creeps.clear()
        trackedLevel = null
    }

    /** A mud or creeping neighbour starts the mask from that edge; one that turns to mud later starts it then. */
    private fun connectEdges(level: Level, creep: Creep, now: Long) {
        val first = creep.fallbackFaces < 0
        val wohlonMud = EKBlocks.WOHLON_MUD.get()
        for (dir in DIRECTIONS) {
            if (creep.edgeStart[dir.ordinal] != NO_EDGE) continue
            neighbour.setWithOffset(scratch, dir)
            val state = level.getBlockState(neighbour)
            val mud = state.`is`(wohlonMud) || state.`is`(Blocks.MUD) || creeps.containsKey(neighbour.asLong())
            if (mud) creep.edgeStart[dir.ordinal] = if (first) creep.start else now
        }
        if (!first) return
        var fallback = 0
        for (face in DIRECTIONS) {
            if (DIRECTIONS.none { it.axis != face.axis && creep.edgeStart[it.ordinal] != NO_EDGE }) {
                fallback = fallback or (1 shl face.ordinal)
            }
        }
        creep.fallbackFaces = fallback
    }

    private fun useLevel(level: Level) {
        if (trackedLevel === level) return
        creeps.clear()
        trackedLevel = level
    }

    /** The old block's quads as the chunk mesh would draw them here: AO, shade, tint and light included. */
    private fun captureQuads(level: Level, old: BlockState, pos: BlockPos): FloatArray {
        capture.reset()
        Minecraft.getInstance().blockRenderer.renderBatched(old, pos, level, identity, capture, true, random)
        return capture.out.toFloatArray()
    }

    /** Keeps each axis-aligned quad as a face rectangle in the mask frame of [WohlonMudCreepRenderer]. */
    private class QuadCapture : VertexConsumer {
        val out = FloatArrayList()
        private val v = FloatArray(VERTICES * VERTEX_FLOATS)
        private var count = 0
        private var cursor = 0

        fun reset() {
            out.clear()
            count = 0
            cursor = 0
        }

        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer {
            v[cursor + VX] = x.toFloat(); v[cursor + VY] = y.toFloat(); v[cursor + VZ] = z.toFloat()
            return this
        }

        override fun color(r: Int, g: Int, b: Int, a: Int): VertexConsumer {
            v[cursor + VR] = r / COLOR_MAX; v[cursor + VG] = g / COLOR_MAX; v[cursor + VB] = b / COLOR_MAX
            return this
        }

        override fun uv(u: Float, vv: Float): VertexConsumer {
            v[cursor + VU] = u; v[cursor + VV] = vv
            return this
        }

        override fun overlayCoords(u: Int, vv: Int): VertexConsumer = this

        override fun uv2(u: Int, vv: Int): VertexConsumer {
            v[cursor + VBLOCK] = u.toFloat(); v[cursor + VSKY] = vv.toFloat()
            return this
        }

        override fun normal(x: Float, y: Float, z: Float): VertexConsumer {
            v[cursor + VNX] = x; v[cursor + VNY] = y; v[cursor + VNZ] = z
            return this
        }

        override fun endVertex() {
            count++
            cursor += VERTEX_FLOATS
            if (count < VERTICES) return
            finishQuad()
            count = 0
            cursor = 0
        }

        override fun defaultColor(r: Int, g: Int, b: Int, a: Int) = Unit

        override fun unsetDefaultColor() = Unit

        private fun finishQuad() {
            val dir = faceOf(v[VNX], v[VNY], v[VNZ]) ?: return
            var sMin = Float.MAX_VALUE; var sMax = -Float.MAX_VALUE
            var tMin = Float.MAX_VALUE; var tMax = -Float.MAX_VALUE
            var plane = 0f
            for (i in 0 until VERTICES) {
                val o = i * VERTEX_FLOATS
                val s = faceS(dir, v[o + VX], v[o + VZ])
                val t = faceT(dir, v[o + VY], v[o + VZ])
                if (s < sMin) sMin = s
                if (s > sMax) sMax = s
                if (t < tMin) tMin = t
                if (t > tMax) tMax = t
                plane += planeOf(dir, v[o + VX], v[o + VY], v[o + VZ]) / VERTICES
            }
            if (sMax - sMin < EPS || tMax - tMin < EPS) return
            val base = out.size
            out.size(base + QUAD_FLOATS)
            val a = out.elements()
            a[base + Q_DIR] = dir.ordinal.toFloat()
            a[base + Q_PLANE] = plane
            a[base + Q_S0] = sMin; a[base + Q_S1] = sMax; a[base + Q_T0] = tMin; a[base + Q_T1] = tMax
            var seen = 0
            for (i in 0 until VERTICES) {
                val o = i * VERTEX_FLOATS
                val s = faceS(dir, v[o + VX], v[o + VZ])
                val t = faceT(dir, v[o + VY], v[o + VZ])
                val atS1 = abs(s - sMax) < EPS
                val atT1 = abs(t - tMax) < EPS
                // Not a rectangle in the face frame (a turned or sloped quad): drop it.
                if (!atS1 && abs(s - sMin) >= EPS || !atT1 && abs(t - tMin) >= EPS) {
                    out.size(base)
                    return
                }
                val corner = (if (atS1) 1 else 0) or (if (atT1) 2 else 0)
                seen = seen or (1 shl corner)
                a[base + Q_ORDER + i] = corner.toFloat()
                val c = base + Q_CORNERS + corner * CORNER_FLOATS
                a[c + C_R] = v[o + VR]; a[c + C_G] = v[o + VG]; a[c + C_B] = v[o + VB]
                a[c + C_U] = v[o + VU]; a[c + C_V] = v[o + VV]
                a[c + C_BLOCK] = v[o + VBLOCK]; a[c + C_SKY] = v[o + VSKY]
            }
            if (seen != ALL_CORNERS) out.size(base)
        }

        private fun faceOf(nx: Float, ny: Float, nz: Float): Direction? = when {
            ny > AXIS_MIN -> Direction.UP
            ny < -AXIS_MIN -> Direction.DOWN
            nz < -AXIS_MIN -> Direction.NORTH
            nz > AXIS_MIN -> Direction.SOUTH
            nx < -AXIS_MIN -> Direction.WEST
            nx > AXIS_MIN -> Direction.EAST
            else -> null
        }

        private fun planeOf(dir: Direction, x: Float, y: Float, z: Float): Float = when (dir.axis) {
            Direction.Axis.X -> x
            Direction.Axis.Y -> y
            Direction.Axis.Z -> z
        }
    }

    /** Mask column: left to right as the block texture runs on that face. */
    internal fun faceS(dir: Direction, x: Float, z: Float): Float = when (dir) {
        Direction.NORTH -> 1f - x
        Direction.SOUTH, Direction.UP, Direction.DOWN -> x
        Direction.WEST -> z
        Direction.EAST -> 1f - z
    }

    /** Mask row: 0 at the top edge of a side face. */
    internal fun faceT(dir: Direction, y: Float, z: Float): Float = when (dir) {
        Direction.UP -> z
        Direction.DOWN -> 1f - z
        else -> 1f - y
    }

    /**
     * Mask run of one edge: the mean wait before the spreader picks the same block again at default game rules.
     * Every face has an edge that starts with the creep, so the block is all mud this long after the change.
     */
    const val DURATION_TICKS = 4096.0 / 3
    const val NO_EDGE = Long.MIN_VALUE
    private const val DIRECTION_COUNT = 6
    private const val MAX_CREEPS = 8192
    private const val VERTICES = 4
    private const val ALL_CORNERS = 0b1111
    private const val EPS = 1e-4f
    private const val AXIS_MIN = 0.9f
    private const val COLOR_MAX = 255f

    private const val VX = 0
    private const val VY = 1
    private const val VZ = 2
    private const val VR = 3
    private const val VG = 4
    private const val VB = 5
    private const val VU = 6
    private const val VV = 7
    private const val VBLOCK = 8
    private const val VSKY = 9
    private const val VNX = 10
    private const val VNY = 11
    private const val VNZ = 12
    private const val VERTEX_FLOATS = 13

    const val Q_DIR = 0
    const val Q_PLANE = 1
    const val Q_S0 = 2
    const val Q_S1 = 3
    const val Q_T0 = 4
    const val Q_T1 = 5
    /** Corner code of each source vertex, in source order, so a sub-rectangle keeps the winding. */
    const val Q_ORDER = 6
    /** Attributes by corner code: bit 0 = s at max, bit 1 = t at max. */
    const val Q_CORNERS = 10
    const val C_R = 0
    const val C_G = 1
    const val C_B = 2
    const val C_U = 3
    const val C_V = 4
    const val C_BLOCK = 5
    const val C_SKY = 6
    const val CORNER_FLOATS = 7
    const val QUAD_FLOATS = Q_CORNERS + 4 * CORNER_FLOATS
}
