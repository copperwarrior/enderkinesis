package org.shipwrights.enderkinesis.worldgen

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.shipwrights.enderkinesis.registry.EKBlocks
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

class Pt(val x: Double, val y: Double, val z: Double) {
    fun lerp(o: Pt, t: Double) = Pt(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
}

/** Quadratic Bézier through [p1] as the control point, sampled at [n] + 1 points. */
fun bez(p0: Pt, p1: Pt, p2: Pt, n: Int = 14): List<Pt> = (0..n).map { i ->
    val t = i.toDouble() / n
    val u = 1 - t
    Pt(
        u * u * p0.x + 2 * u * t * p1.x + t * t * p2.x,
        u * u * p0.y + 2 * u * t * p1.y + t * t * p2.y,
        u * u * p0.z + 2 * u * t * p1.z + t * t * p2.z,
    )
}

/**
 * Plans a tree in coordinates relative to [origin], then writes it in one [commit]. Reads see planned
 * blocks first, so curtains and vines hang from leaves the same tree just planned.
 */
class WogorTreeBuilder(private val level: WorldGenLevel, val random: RandomSource, private val origin: BlockPos) {
    private val planned = Long2ObjectOpenHashMap<BlockState>()
    private val pos = BlockPos.MutableBlockPos()
    private val log = EKBlocks.WOGOR_LOG.get().defaultBlockState()
    private val bark = EKBlocks.WOGOR_WOOD.get().defaultBlockState()
    private val leafBlock = EKBlocks.WOGOR_LEAVES.get()
    val leaves: BlockState = leafBlock.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true)

    fun rf(a: Double, b: Double) = a + (b - a) * random.nextDouble()
    fun ri(a: Int, b: Int) = a + random.nextInt(b - a + 1)
    fun chance(p: Double) = random.nextDouble() < p

    private fun key(x: Int, y: Int, z: Int) = BlockPos.asLong(origin.x + x, origin.y + y, origin.z + z)
    private fun worldState(x: Int, y: Int, z: Int) = level.getBlockState(pos.set(origin.x + x, origin.y + y, origin.z + z))

    fun isOpen(x: Int, y: Int, z: Int): Boolean {
        if (planned.containsKey(key(x, y, z))) return false
        val s = worldState(x, y, z)
        return s.isAir || (s.canBeReplaced() && s.fluidState.isEmpty)
    }

    fun isWater(x: Int, y: Int, z: Int) = !planned.containsKey(key(x, y, z)) && worldState(x, y, z).`is`(Blocks.WATER)

    fun isMud(x: Int, y: Int, z: Int): Boolean {
        val s = worldState(x, y, z)
        return s.`is`(Blocks.MUD) || s.`is`(EKBlocks.WOHLON_MUD.get())
    }

    private fun canWood(x: Int, y: Int, z: Int): Boolean {
        val p = planned.get(key(x, y, z))
        if (p != null) return p.`is`(leafBlock) || p.`is`(Blocks.VINE)
        val s = worldState(x, y, z)
        return s.isAir || s.canBeReplaced() || s.`is`(BlockTags.LEAVES)
    }

    private fun plan(x: Int, y: Int, z: Int, state: BlockState) {
        if (abs(x) > MAX_REACH || abs(z) > MAX_REACH || level.isOutsideBuildHeight(origin.y + y)) return
        planned.put(key(x, y, z), state)
    }

    fun wood(x: Int, y: Int, z: Int, axis: Direction.Axis = Direction.Axis.Y) {
        if (canWood(x, y, z)) plan(x, y, z, log.setValue(BlockStateProperties.AXIS, axis))
    }

    fun bark(x: Int, y: Int, z: Int) {
        if (canWood(x, y, z)) plan(x, y, z, bark)
    }

    fun leaf(x: Int, y: Int, z: Int) {
        if (isOpen(x, y, z)) plan(x, y, z, leaves)
    }

    /** Face-connected walk through [points]; each cell gets the axis of the step that reached it. */
    fun path(points: List<Pt>, visit: (Int, Int, Int, Direction.Axis) -> Unit) {
        var started = false
        var px = 0
        var py = 0
        var pz = 0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val n = ceil(max(abs(b.x - a.x), max(abs(b.y - a.y), abs(b.z - a.z))) * 2).toInt() + 1
            for (s in 0..n) {
                val c = a.lerp(b, s.toDouble() / n)
                val cx = c.x.roundToInt()
                val cy = c.y.roundToInt()
                val cz = c.z.roundToInt()
                if (!started) {
                    started = true
                    px = cx; py = cy; pz = cz
                    visit(px, py, pz, Direction.Axis.Y)
                    continue
                }
                if (cx != px) { px = cx; visit(px, py, pz, Direction.Axis.X) }
                if (cy != py) { py = cy; visit(px, py, pz, Direction.Axis.Y) }
                if (cz != pz) { pz = cz; visit(px, py, pz, Direction.Axis.Z) }
            }
        }
    }

    fun logPath(points: List<Pt>) = path(points) { x, y, z, axis -> wood(x, y, z, axis) }

    fun leafPath(points: List<Pt>) = path(points) { x, y, z, _ -> leaf(x, y, z) }

    fun pathClear(points: List<Pt>): Boolean {
        var clear = true
        path(points) { x, y, z, _ -> if (!canWood(x, y, z)) clear = false }
        return clear
    }

    fun columnClear(y0: Int, y1: Int) = (y0..y1).all { canWood(0, it, 0) }

    fun disc(cx: Int, y: Int, cz: Int, r: Double, visit: (Int, Int, Int) -> Unit) {
        val rr = ceil(r).toInt()
        for (dx in -rr..rr) for (dz in -rr..rr) {
            if (dx * dx + dz * dz <= r * r + 0.01) visit(cx + dx, y, cz + dz)
        }
    }

    /** Ellipsoid; cells in the outer shell drop out at [hole] so the edge reads ragged, not turned. */
    @Suppress("LongParameterList") // centre + radii are one shape; a wrapper type only adds noise here
    fun blob(cx: Double, cy: Double, cz: Double, rx: Double, ry: Double, rz: Double, hole: Double, wood: Boolean = false) {
        val bx = cx.roundToInt()
        val by = cy.roundToInt()
        val bz = cz.roundToInt()
        val ex = ceil(rx).toInt()
        val ey = ceil(ry).toInt()
        val ez = ceil(rz).toInt()
        for (dx in -ex..ex) for (dy in -ey..ey) for (dz in -ez..ez) {
            val x = bx + dx
            val y = by + dy
            val z = bz + dz
            val fx = (x - cx) / rx
            val fy = (y - cy) / ry
            val fz = (z - cz) / rz
            val d = fx * fx + fy * fy + fz * fz
            if (d > 1 || (d > SHELL && chance(hole))) continue
            if (wood) bark(x, y, z) else leaf(x, y, z)
        }
    }

    fun hang(x: Int, y: Int, z: Int, len: Int, state: BlockState, intoWater: Int = 0) {
        var water = intoWater
        for (i in 0 until len) {
            val yy = y - i
            if (isOpen(x, yy, z)) {
                plan(x, yy, z, state)
            } else if (water > 0 && isWater(x, yy, z) && state.hasProperty(BlockStateProperties.WATERLOGGED)) {
                plan(x, yy, z, state.setValue(BlockStateProperties.WATERLOGGED, true))
                water--
            } else {
                break
            }
        }
    }

    private fun plannedLeaves(): LongArray =
        planned.long2ObjectEntrySet().filter { it.value.`is`(leafBlock) }.map { it.longKey }.toLongArray()

    /** Leaf strands from planned leaves that are at least [minR] from the crown axis ([cx], [cz]). */
    @Suppress("LongParameterList") // the curtain's tuning knobs, named at every call site
    fun curtain(minR: Double, p: Double, min: Int, max: Int, cx: Double = 0.0, cz: Double = 0.0, intoWater: Int = 0) {
        for (k in plannedLeaves()) {
            val x = BlockPos.getX(k) - origin.x
            val y = BlockPos.getY(k) - origin.y
            val z = BlockPos.getZ(k) - origin.z
            if (!isOpen(x, y - 1, z) || hypot(x - cx, z - cz) < minR || !chance(p)) continue
            hang(x, y - 1, z, ri(min, max), leaves, intoWater)
        }
    }

    /** Same rule as the vanilla `leave_vine` decorator: a vine on an open leaf side, up to 4 more below. */
    fun vines(p: Double) {
        for (k in plannedLeaves()) {
            val x = BlockPos.getX(k) - origin.x
            val y = BlockPos.getY(k) - origin.y
            val z = BlockPos.getZ(k) - origin.z
            for (dir in Direction.Plane.HORIZONTAL) {
                val nx = x + dir.stepX
                val nz = z + dir.stepZ
                if (!isOpen(nx, y, nz) || !chance(p)) continue
                val vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(dir.opposite), true)
                hang(nx, y, nz, VINE_LENGTH, vine)
            }
        }
    }

    fun commit(): Boolean {
        var placed = false
        for (e in planned.long2ObjectEntrySet()) {
            placed = level.setBlock(pos.set(e.longKey), e.value, Block.UPDATE_CLIENTS) || placed
        }
        return placed
    }

    private companion object {
        // WorldGenRegion writes stay inside the 3×3 chunk area around the origin chunk.
        const val MAX_REACH = 14
        const val SHELL = 0.55
        const val VINE_LENGTH = 5
    }
}
