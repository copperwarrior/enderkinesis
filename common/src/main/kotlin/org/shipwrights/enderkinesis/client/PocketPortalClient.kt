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

/**
 * The walls of an Abyssal Pocket open onto the End: an end portal surface over every solid
 * face bordering a phased block, just inside the hole, drawn with [PocketPortalRenderType].
 *
 * Each face is lit as the block face beneath it would be: smooth light and ambient occlusion
 * from the cells in the hole round each corner, and the face's directional shade. The phased
 * blocks are still solid in the client's light engine, so the hole's own light is spread
 * inward from its open neighbours (see [relight]). Where the surface stops (at the pocket's
 * mouth, or a wall that doesn't continue) the portal fades out over the face, showing the
 * block beneath.
 */
object PocketPortalClient {

    // Render thread only. Light in each phased cell: block light << 4 | sky light.
    private val light = Long2IntOpenHashMap()
    private var lightTick = Long.MIN_VALUE

    private const val RANGE = 96.0
    /** How far inside the hole the portal sits, off the neighbours' own faces. */
    private const val INSET = 0.002
    /** Cells per side each face is split into, for the fade and smooth light. */
    private const val GRID = 4
    /** How far over a face, in blocks, the portal fades in from an open edge. */
    private const val FADE = 0.6f
    /** Light can't be shed more than this many cells into a pocket. */
    private const val SPREAD = 8
    /** Never quite black: the End shows faintly even in the dark. */
    private const val MIN_LIGHT = 0.12f

    fun render(c: VertexConsumer, m: Matrix4f, level: ClientLevel, cam: Vec3, pockets: LongSet, partialTick: Float) {
        if (level.gameTime != lightTick) {
            relight(level, pockets)
            lightTick = level.gameTime
        }
        val skyDarken = level.getSkyDarken(partialTick)
        val pos = BlockPos.MutableBlockPos()
        val wall = BlockPos.MutableBlockPos()
        val keys = pockets.iterator()
        while (keys.hasNext()) {
            pos.set(keys.nextLong())
            if (cam.distanceToSqr(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5) > RANGE * RANGE) continue
            for (dir in Direction.values()) {
                wall.setWithOffset(pos, dir)
                if (solid(level, wall)) face(c, m, level, pos.immutable(), dir, skyDarken)
            }
        }
    }

    /** A cell is a wall if it's a real, full block: phased blocks are part of the hole. */
    private fun solid(level: ClientLevel, pos: BlockPos): Boolean =
        !AbyssalPockets.isPhasedClient(pos) && level.getBlockState(pos).isSolidRender(level, pos)

    /** Face tangents: the two axes across a face whose normal lies along [axis]. */
    private fun tangents(axis: Direction.Axis): Pair<Direction, Direction> = when (axis) {
        Direction.Axis.X -> Direction.UP to Direction.SOUTH
        Direction.Axis.Y -> Direction.EAST to Direction.SOUTH
        Direction.Axis.Z -> Direction.EAST to Direction.UP
    }

    /** The portal over the face of the wall on [dir]'s side of hole cell [cell]. */
    private fun face(c: VertexConsumer, m: Matrix4f, level: ClientLevel, cell: BlockPos, dir: Direction, skyDarken: Float) {
        val (t1, t2) = tangents(dir.axis)
        val wall = cell.relative(dir)
        // Which edges the surface stops at, so the portal fades out toward them.
        val openPos1 = open(level, cell, wall, t1)
        val openNeg1 = open(level, cell, wall, t1.opposite)
        val openPos2 = open(level, cell, wall, t2)
        val openNeg2 = open(level, cell, wall, t2.opposite)
        // Light at each corner: [0] (-,-), [1] (+,-), [2] (-,+), [3] (+,+) along t1, t2.
        val corners = FloatArray(4) { k ->
            cornerLight(level, cell, if (k and 1 != 0) t1 else t1.opposite, if (k and 2 != 0) t2 else t2.opposite, skyDarken)
        }
        val shade = level.getShade(dir.opposite, true)

        val reach = 0.5 - INSET
        val cx = cell.x + 0.5 + dir.stepX * reach
        val cy = cell.y + 0.5 + dir.stepY * reach
        val cz = cell.z + 0.5 + dir.stepZ * reach
        fun alpha(u: Float, v: Float): Float {
            var a = 1f
            if (openPos1) a *= ramp(1f - u)
            if (openNeg1) a *= ramp(u)
            if (openPos2) a *= ramp(1f - v)
            if (openNeg2) a *= ramp(v)
            return a
        }
        fun put(u: Float, v: Float) {
            val lit = Mth.lerp(v, Mth.lerp(u, corners[0], corners[1]), Mth.lerp(u, corners[2], corners[3])) * shade
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

    /** Whether the portal surface stops at the face's edge toward [t]: it carries on over
     *  the next wall along, or round the corner onto a wall beside the cell. */
    private fun open(level: ClientLevel, cell: BlockPos, wall: BlockPos, t: Direction): Boolean {
        val beside = cell.relative(t)
        return if (AbyssalPockets.isPhasedClient(beside)) !solid(level, wall.relative(t)) else !solid(level, beside)
    }

    private fun ramp(distance: Float): Float {
        val x = (distance / FADE).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * Smooth light at the face corner toward [a] and [b]: the light of the open cells round
     * it in the hole, darkened by ambient occlusion from the walls among them, as vanilla
     * lights a block face.
     */
    private fun cornerLight(level: ClientLevel, cell: BlockPos, a: Direction, b: Direction, skyDarken: Float): Float {
        val side1 = cell.relative(a)
        val side2 = cell.relative(b)
        val corner = side1.relative(b)
        val solid1 = solid(level, side1)
        val solid2 = solid(level, side2)
        // The diagonal cell is hidden when both sides are walls, as in vanilla.
        val solidCorner = if (solid1 && solid2) true else solid(level, corner)
        var block = 0f; var sky = 0f; var count = 0
        for ((pos, isSolid) in arrayOf(cell to false, side1 to solid1, side2 to solid2, corner to solidCorner)) {
            if (isSolid) continue
            val packed = lightAt(level, pos)
            block += packed shr 4
            sky += packed and 0xF
            count++
        }
        val occluders = (if (solid1) 1 else 0) + (if (solid2) 1 else 0) + (if (solidCorner) 1 else 0)
        val ao = if (solid1 && solid2) 0.45f else 1f - 0.2f * occluders
        val level15 = maxOf(block, sky * skyDarken) / count.coerceAtLeast(1)
        return maxOf(MIN_LIGHT, brightness(level, level15 / 15f)) * ao
    }

    /** Light in [pos]: the spread light for a phased cell, the world's for any other. */
    private fun lightAt(level: ClientLevel, pos: BlockPos): Int {
        val key = pos.asLong()
        if (light.containsKey(key)) return light.get(key)
        return (level.getBrightness(LightLayer.BLOCK, pos) shl 4) or level.getBrightness(LightLayer.SKY, pos)
    }

    /** Vanilla's light curve (see `LightTexture.getBrightness`), on a fractional level. */
    private fun brightness(level: ClientLevel, f: Float): Float {
        val curved = f / (4f - 3f * f)
        return Mth.lerp(level.dimensionType().ambientLight(), curved, 1f)
    }

    /**
     * Light in the phased cells. The client still holds the phased blocks, so its light
     * engine treats them as solid and dark; instead each takes the light of its open
     * neighbours, and that spreads further in, one level less per cell, as light would.
     */
    private fun relight(level: ClientLevel, pockets: LongSet) {
        light.clear()
        val pos = BlockPos.MutableBlockPos()
        val next = BlockPos.MutableBlockPos()
        val keys = pockets.iterator()
        while (keys.hasNext()) {
            val key = keys.nextLong()
            pos.set(key)
            var block = 0; var sky = 0
            for (dir in Direction.values()) {
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
                for (dir in Direction.values()) {
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
