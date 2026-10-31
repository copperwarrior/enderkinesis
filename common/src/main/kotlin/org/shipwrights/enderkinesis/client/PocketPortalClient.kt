package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.VertexConsumer
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.LongSet
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.level.LightLayer
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.spell.AbyssalPockets

// The client light engine still treats phased blocks as solid and dark, so [relight] spreads light in from open neighbours.
object PocketPortalClient {

    // Render thread only. Light in each phased cell: block light << 4 | sky light.
    private val light = Long2IntOpenHashMap()
    private var lightTick = Long.MIN_VALUE
    // Render thread only.
    private val faces = PortalFaces()

    private const val RANGE = 96.0
    // Faces are found once a tick; this covers camera movement until the next tick.
    private const val FIND_MARGIN = 16.0
    // Inset into the hole, so the portal does not z-fight the wall face.
    private const val INSET = 0.002
    // Cells per side, for the fade and smooth light.
    private const val GRID = 4
    // Blocks.
    private const val FADE = 0.6f
    // Bound on relight passes; light goes no deeper than this many cells.
    private const val SPREAD = 8
    // The End shows faintly even in the dark.
    private const val MIN_LIGHT = 0.12f

    private const val OPEN_POS1 = 1
    private const val OPEN_NEG1 = 2
    private const val OPEN_POS2 = 4
    private const val OPEN_NEG2 = 8
    // Per face: x, y, z, direction, open-edge flags.
    private const val FACE_INTS = 5
    // Per face: shade, then block, sky, count, ao for each corner.
    private const val FACE_FLOATS = 17
    private const val FACES_INITIAL = 64

    private val DIRECTIONS = Direction.values()
    // Indexed by the [Direction.Axis] ordinal of the face normal.
    private val TANGENT1 = arrayOf(Direction.UP, Direction.EAST, Direction.EAST)
    private val TANGENT2 = arrayOf(Direction.SOUTH, Direction.SOUTH, Direction.UP)

    private class PortalFaces {
        var size = 0
            private set
        var ints = IntArray(FACE_INTS * FACES_INITIAL)
            private set
        var floats = FloatArray(FACE_FLOATS * FACES_INITIAL)
            private set

        fun clear() {
            size = 0
        }

        fun next(): Int {
            if (size * FACE_INTS == ints.size) {
                ints = ints.copyOf(ints.size * 2)
                floats = floats.copyOf(floats.size * 2)
            }
            return size++
        }
    }

    fun render(c: VertexConsumer, m: Matrix4f, level: ClientLevel, cam: Vec3, pockets: LongSet, partialTick: Float) {
        if (level.gameTime != lightTick) {
            relight(level, pockets)
            findFaces(level, cam, pockets)
            lightTick = level.gameTime
        }
        val skyDarken = level.getSkyDarken(partialTick)
        val ints = faces.ints
        for (f in 0 until faces.size) {
            val o = f * FACE_INTS
            if (cam.distanceToSqr(ints[o] + 0.5, ints[o + 1] + 0.5, ints[o + 2] + 0.5) > RANGE * RANGE) continue
            face(c, m, level, f, skyDarken)
        }
    }

    private fun findFaces(level: ClientLevel, cam: Vec3, pockets: LongSet) {
        faces.clear()
        val reach = RANGE + FIND_MARGIN
        val pos = BlockPos.MutableBlockPos()
        val wall = BlockPos.MutableBlockPos()
        val keys = pockets.iterator()
        while (keys.hasNext()) {
            pos.set(keys.nextLong())
            if (cam.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) > reach * reach) continue
            for (dir in DIRECTIONS) {
                wall.setWithOffset(pos, dir)
                if (solid(level, wall)) addFace(level, pos.immutable(), dir)
            }
        }
    }

    /** Phased blocks are part of the hole, not walls. */
    private fun solid(level: ClientLevel, pos: BlockPos): Boolean =
        !AbyssalPockets.isPhasedClient(pos) && level.getBlockState(pos).isSolidRender(level, pos)

    private fun addFace(level: ClientLevel, cell: BlockPos, dir: Direction) {
        val t1 = TANGENT1[dir.axis.ordinal]; val t2 = TANGENT2[dir.axis.ordinal]
        val wall = cell.relative(dir)
        var edges = 0
        if (open(level, cell, wall, t1)) edges = edges or OPEN_POS1
        if (open(level, cell, wall, t1.opposite)) edges = edges or OPEN_NEG1
        if (open(level, cell, wall, t2)) edges = edges or OPEN_POS2
        if (open(level, cell, wall, t2.opposite)) edges = edges or OPEN_NEG2
        val f = faces.next()
        val ints = faces.ints; val o = f * FACE_INTS
        ints[o] = cell.x; ints[o + 1] = cell.y; ints[o + 2] = cell.z; ints[o + 3] = dir.ordinal; ints[o + 4] = edges
        val floats = faces.floats; val fo = f * FACE_FLOATS
        floats[fo] = level.getShade(dir.opposite, true)
        // Corners: [0] (-,-), [1] (+,-), [2] (-,+), [3] (+,+) along t1, t2.
        for (k in 0 until 4) {
            sampleCorner(level, cell, if (k and 1 != 0) t1 else t1.opposite, if (k and 2 != 0) t2 else t2.opposite, floats, fo + 1 + 4 * k)
        }
    }

    private fun face(c: VertexConsumer, m: Matrix4f, level: ClientLevel, f: Int, skyDarken: Float) {
        val ints = faces.ints; val o = f * FACE_INTS
        val dir = DIRECTIONS[ints[o + 3]]
        val t1 = TANGENT1[dir.axis.ordinal]; val t2 = TANGENT2[dir.axis.ordinal]
        val edges = ints[o + 4]
        val openPos1 = edges and OPEN_POS1 != 0
        val openNeg1 = edges and OPEN_NEG1 != 0
        val openPos2 = edges and OPEN_POS2 != 0
        val openNeg2 = edges and OPEN_NEG2 != 0
        val floats = faces.floats; val fo = f * FACE_FLOATS
        val shade = floats[fo]
        val c0 = cornerLight(level, floats, fo + 1, skyDarken)
        val c1 = cornerLight(level, floats, fo + 5, skyDarken)
        val c2 = cornerLight(level, floats, fo + 9, skyDarken)
        val c3 = cornerLight(level, floats, fo + 13, skyDarken)

        val reach = 0.5 - INSET
        val cx = ints[o] + 0.5 + dir.stepX * reach
        val cy = ints[o + 1] + 0.5 + dir.stepY * reach
        val cz = ints[o + 2] + 0.5 + dir.stepZ * reach
        fun alpha(u: Float, v: Float): Float {
            var a = 1f
            if (openPos1) a *= ramp(1f - u)
            if (openNeg1) a *= ramp(u)
            if (openPos2) a *= ramp(1f - v)
            if (openNeg2) a *= ramp(v)
            return a
        }
        fun put(u: Float, v: Float) {
            val lit = Mth.lerp(v, Mth.lerp(u, c0, c1), Mth.lerp(u, c2, c3)) * shade
            val grey = (lit * 255).toInt().coerceIn(0, 255)
            val du = u - 0.5; val dv = v - 0.5
            c.vertex(
                m,
                (cx + t1.stepX * du + t2.stepX * dv).toFloat(),
                (cy + t1.stepY * du + t2.stepY * dv).toFloat(),
                (cz + t1.stepZ * du + t2.stepZ * dv).toFloat(),
            ).color(grey, grey, grey, (alpha(u, v) * 255).toInt().coerceIn(0, 255)).endVertex()
        }
        for (i in 0 until GRID) for (j in 0 until GRID) {
            val u0 = i.toFloat() / GRID; val u1 = (i + 1).toFloat() / GRID
            val v0 = j.toFloat() / GRID; val v1 = (j + 1).toFloat() / GRID
            if (alpha(u0, v0) + alpha(u1, v0) + alpha(u1, v1) + alpha(u0, v1) <= 0f) continue
            put(u0, v0); put(u1, v0); put(u1, v1); put(u0, v1)
        }
    }

    /** False when the surface continues onto the next wall, or round the corner onto a wall beside the cell. */
    private fun open(level: ClientLevel, cell: BlockPos, wall: BlockPos, t: Direction): Boolean {
        val beside = cell.relative(t)
        return if (AbyssalPockets.isPhasedClient(beside)) !solid(level, wall.relative(t)) else !solid(level, beside)
    }

    private fun ramp(distance: Float): Float {
        val x = (distance / FADE).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** Matches vanilla smooth light and AO. Stores sums, not a final value, because sky darkening changes each frame. */
    private fun sampleCorner(level: ClientLevel, cell: BlockPos, a: Direction, b: Direction, out: FloatArray, o: Int) {
        val side1 = cell.relative(a)
        val side2 = cell.relative(b)
        val corner = side1.relative(b)
        val solid1 = solid(level, side1)
        val solid2 = solid(level, side2)
        // The diagonal cell is hidden when both sides are walls, as in vanilla.
        val solidCorner = if (solid1 && solid2) true else solid(level, corner)
        val own = lightAt(level, cell)
        var block = (own shr 4).toFloat(); var sky = (own and 0xF).toFloat(); var count = 1
        if (!solid1) { val packed = lightAt(level, side1); block += packed shr 4; sky += packed and 0xF; count++ }
        if (!solid2) { val packed = lightAt(level, side2); block += packed shr 4; sky += packed and 0xF; count++ }
        if (!solidCorner) { val packed = lightAt(level, corner); block += packed shr 4; sky += packed and 0xF; count++ }
        val occluders = (if (solid1) 1 else 0) + (if (solid2) 1 else 0) + (if (solidCorner) 1 else 0)
        out[o] = block
        out[o + 1] = sky
        out[o + 2] = count.toFloat()
        out[o + 3] = if (solid1 && solid2) 0.45f else 1f - 0.2f * occluders
    }

    private fun cornerLight(level: ClientLevel, sample: FloatArray, o: Int, skyDarken: Float): Float {
        val level15 = maxOf(sample[o], sample[o + 1] * skyDarken) / sample[o + 2]
        return maxOf(MIN_LIGHT, brightness(level, level15 / 15f)) * sample[o + 3]
    }

    private fun lightAt(level: ClientLevel, pos: BlockPos): Int {
        val key = pos.asLong()
        if (light.containsKey(key)) return light.get(key)
        return (level.getBrightness(LightLayer.BLOCK, pos) shl 4) or level.getBrightness(LightLayer.SKY, pos)
    }

    /** Vanilla light curve (see `LightTexture.getBrightness`) on a fractional level. */
    private fun brightness(level: ClientLevel, f: Float): Float {
        val curved = f / (4f - 3f * f)
        return Mth.lerp(level.dimensionType().ambientLight(), curved, 1f)
    }

    /** Each phased cell takes its open neighbours' light, then spreads it in with one level less per cell. */
    private fun relight(level: ClientLevel, pockets: LongSet) {
        light.clear()
        val pos = BlockPos.MutableBlockPos()
        val next = BlockPos.MutableBlockPos()
        val keys = pockets.iterator()
        while (keys.hasNext()) {
            val key = keys.nextLong()
            pos.set(key)
            var block = 0; var sky = 0
            for (dir in DIRECTIONS) {
                next.setWithOffset(pos, dir)
                if (AbyssalPockets.isPhasedClient(next) || solid(level, next)) continue
                block = maxOf(block, level.getBrightness(LightLayer.BLOCK, next) - 1)
                sky = maxOf(sky, level.getBrightness(LightLayer.SKY, next) - 1)
            }
            light.put(key, (block.coerceAtLeast(0) shl 4) or sky.coerceAtLeast(0))
        }
        repeat(SPREAD) {
            var changed = false
            val all = pockets.iterator()
            while (all.hasNext()) {
                val key = all.nextLong()
                pos.set(key)
                var packed = light.get(key)
                for (dir in DIRECTIONS) {
                    next.setWithOffset(pos, dir)
                    val nextKey = next.asLong()
                    if (!light.containsKey(nextKey)) continue
                    val other = light.get(nextKey)
                    val block = maxOf(packed shr 4, (other shr 4) - 1)
                    val sky = maxOf(packed and 0xF, (other and 0xF) - 1)
                    packed = (block shl 4) or sky
                }
                if (packed != light.get(key)) {
                    light.put(key, packed)
                    changed = true
                }
            }
            if (!changed) return
        }
    }
}
