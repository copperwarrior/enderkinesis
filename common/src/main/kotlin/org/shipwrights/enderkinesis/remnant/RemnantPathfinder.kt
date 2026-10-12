package org.shipwrights.enderkinesis.remnant

import it.unimi.dsi.fastutil.doubles.DoubleArrayList
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.Direction
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Any-direction A* for a whole remnant mass.
 *
 * **One search per connected mass, never per particle.** A mass is hundreds
 * of particles that all want to go the same way; pathing each of them would
 * multiply the cost by the particle count for hundreds of identical answers,
 * and they would disagree at every branch and tear the body apart. The
 * search runs from the cluster's centroid and its members steer at the
 * shared waypoint.
 *
 * **Any-direction, not grid-locked.** All 26 neighbours are open, weighted
 * by true step length, and diagonals may not cut a corner past geometry the
 * mass could not fit through. The result is then pulled straight wherever
 * line of sight allows, so the mass moves in real directions rather than
 * along a staircase of axis steps.
 *
 * Search is surface-biased: an open-air node costs more than one against
 * geometry, so a path prefers to run along walls and floors, which is where
 * this creature can actually crawl.
 *
 * Every loop here is bounded — expansions, path length, and the smoothing
 * lookahead — so a search into sealed geometry gives up instead of walking
 * the whole loaded world (Rule 2).
 */
class RemnantPathfinder {

    private val heapNode = LongArrayList()
    private val heapCost = DoubleArrayList()
    private val gScore = Long2DoubleOpenHashMap()
    private val cameFrom = Long2LongOpenHashMap()
    private val closed = LongOpenHashSet()
    private val raw = LongArrayList()
    private val cursor = BlockPos.MutableBlockPos()
    private val probe = BlockPos.MutableBlockPos()

    /** Passability per node for one search. Each test now costs two block
     *  lookups and A* asks about the same node from every neighbour, so the
     *  cache pays for itself several times over. */
    private val walkable = Long2ByteOpenHashMap()

    /**
     * Searches from [from] to [to], writing packed block positions into
     * [out] — nearest waypoint first. Returns false when no route was found
     * inside the expansion budget.
     */
    fun findPath(level: Level, from: BlockPos, to: BlockPos, out: LongArrayList): Boolean {
        out.clear()
        if (from.distSqr(to) > MAX_RANGE * MAX_RANGE) return false
        val start = from.asLong()
        val goal = to.asLong()
        heapNode.clear(); heapCost.clear()
        gScore.clear(); cameFrom.clear(); closed.clear(); walkable.clear()
        gScore.put(start, 0.0)
        push(start, heuristic(from, to))

        var expansions = 0
        while (!heapNode.isEmpty()) {
            val current = pop()
            if (current == goal) {
                rebuild(current, out)
                return true
            }
            if (!closed.add(current)) continue
            if (++expansions > MAX_EXPANSIONS) break
            expand(level, current, to)
        }
        return false
    }

    /** Opens every passable neighbour of [current]. */
    private fun expand(level: Level, current: Long, to: BlockPos) {
        val cx = BlockPos.getX(current)
        val cy = BlockPos.getY(current)
        val cz = BlockPos.getZ(current)
        val base = gScore.get(current)
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            if (dx == 0 && dy == 0 && dz == 0) continue
            val nx = cx + dx
            val ny = cy + dy
            val nz = cz + dz
            cursor.set(nx, ny, nz)
            if (!passable(level, cursor)) continue
            // A diagonal may not squeeze through a gap the mass could not
            // pass: every axis it moves on must be individually clear.
            if (dx != 0 && !passable(level, cursor.set(nx, cy, cz))) continue
            if (dy != 0 && !passable(level, cursor.set(cx, ny, cz))) continue
            if (dz != 0 && !passable(level, cursor.set(cx, cy, nz))) continue
            cursor.set(nx, ny, nz)
            val neighbour = cursor.asLong()
            if (closed.contains(neighbour)) continue
            val step = sqrt((dx * dx + dy * dy + dz * dz).toDouble())
            val cost = base + step + if (touchesSolid(level, nx, ny, nz)) 0.0 else OPEN_AIR_PENALTY
            if (gScore.containsKey(neighbour) && cost >= gScore.get(neighbour)) continue
            gScore.put(neighbour, cost)
            cameFrom.put(neighbour, current)
            cursor.set(nx, ny, nz)
            push(neighbour, cost + heuristic(cursor, to))
        }
    }

    /** Walks the parent chain back to the start, then straightens it. */
    private fun rebuild(goal: Long, out: LongArrayList) {
        raw.clear()
        var node = goal
        var guard = 0
        while (guard++ < MAX_PATH) {
            raw.add(node)
            if (!cameFrom.containsKey(node)) break
            node = cameFrom.get(node)
        }
        // Reverse into start-first order.
        for (i in raw.size - 1 downTo 0) out.add(raw.getLong(i))
    }

    /**
     * Pulls the path straight: replaces runs of steps with a single hop
     * wherever the line between them is clear.
     *
     * This is what makes it any-direction rather than merely 26-connected.
     * Grid A* produces staircases even with diagonals open, and a mass
     * following one visibly zigzags.
     */
    fun straighten(level: Level, path: LongArrayList) {
        if (path.size < 3) return
        raw.clear()
        var i = 0
        while (i < path.size) {
            raw.add(path.getLong(i))
            if (i == path.size - 1) break
            var best = i + 1
            val limit = minOf(path.size - 1, i + LOS_LOOKAHEAD)
            for (j in limit downTo i + 2) {
                if (lineOfSight(level, path.getLong(i), path.getLong(j))) {
                    best = j
                    break
                }
            }
            i = best
        }
        path.clear()
        path.addAll(raw)
    }

    /** Samples the segment between two nodes for blocked space. */
    private fun lineOfSight(level: Level, a: Long, b: Long): Boolean {
        val ax = BlockPos.getX(a) + 0.5
        val ay = BlockPos.getY(a) + 0.5
        val az = BlockPos.getZ(a) + 0.5
        val dx = BlockPos.getX(b) + 0.5 - ax
        val dy = BlockPos.getY(b) + 0.5 - ay
        val dz = BlockPos.getZ(b) + 0.5 - az
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        val steps = max(2, (length / LOS_STEP).toInt())
        for (s in 1 until steps) {
            val t = s.toDouble() / steps
            cursor.set(
                Math.floor(ax + dx * t).toInt(),
                Math.floor(ay + dy * t).toInt(),
                Math.floor(az + dz * t).toInt(),
            )
            if (!passable(level, cursor)) return false
        }
        return true
    }

    /**
     * Whether a node is actually open, allowing for geometry that reaches
     * out of the block it belongs to.
     *
     * A block's own shape is not the whole story: walls and fences collide
     * up to 1.5 blocks, so the node above one is half filled while its own
     * block state reports empty. Testing only the node's own shape routed
     * paths straight through that space, and the mass — which collides
     * against the real shapes — jammed against something the route insisted
     * was clear. Anything overhanging from below blocks the node.
     */
    private fun passable(level: Level, pos: BlockPos): Boolean {
        val key = pos.asLong()
        val cached = walkable.get(key)
        if (cached.toInt() != 0) return cached.toInt() > 0
        var open = level.getBlockState(pos).getCollisionShape(level, pos).isEmpty
        if (open) {
            probe.set(pos.x, pos.y - 1, pos.z)
            val below = level.getBlockState(probe).getCollisionShape(level, probe)
            if (!below.isEmpty && below.max(Direction.Axis.Y) > 1.0) open = false
        }
        walkable.put(key, if (open) 1.toByte() else (-1).toByte())
        return open
    }

    /** True when the node has geometry on any side — the surfaces this
     *  creature actually travels on. */
    private fun touchesSolid(level: Level, x: Int, y: Int, z: Int): Boolean {
        for (axis in 0 until 6) {
            val sx = x + if (axis == 0) 1 else if (axis == 1) -1 else 0
            val sy = y + if (axis == 2) 1 else if (axis == 3) -1 else 0
            val sz = z + if (axis == 4) 1 else if (axis == 5) -1 else 0
            cursor.set(sx, sy, sz)
            if (!passable(level, cursor)) return true
        }
        return false
    }

    private fun heuristic(from: BlockPos, to: BlockPos): Double {
        val dx = abs(from.x - to.x).toDouble()
        val dy = abs(from.y - to.y).toDouble()
        val dz = abs(from.z - to.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz) * HEURISTIC_WEIGHT
    }

    private fun push(node: Long, cost: Double) {
        heapNode.add(node)
        heapCost.add(cost)
        var child = heapNode.size - 1
        while (child > 0) {
            val parent = (child - 1) / 2
            if (heapCost.getDouble(parent) <= heapCost.getDouble(child)) break
            swap(parent, child)
            child = parent
        }
    }

    private fun pop(): Long {
        val top = heapNode.getLong(0)
        val last = heapNode.size - 1
        swap(0, last)
        heapNode.removeLong(last)
        heapCost.removeDouble(last)
        var parent = 0
        while (true) {
            val left = parent * 2 + 1
            if (left >= heapNode.size) break
            val right = left + 1
            var best = left
            if (right < heapNode.size && heapCost.getDouble(right) < heapCost.getDouble(left)) {
                best = right
            }
            if (heapCost.getDouble(parent) <= heapCost.getDouble(best)) break
            swap(parent, best)
            parent = best
        }
        return top
    }

    private fun swap(a: Int, b: Int) {
        val node = heapNode.getLong(a)
        heapNode.set(a, heapNode.getLong(b))
        heapNode.set(b, node)
        val cost = heapCost.getDouble(a)
        heapCost.set(a, heapCost.getDouble(b))
        heapCost.set(b, cost)
    }

    private companion object {
        /** Expansion budget. A search walled into sealed geometry has to
         *  give up rather than flood every loaded chunk. */
        private const val MAX_EXPANSIONS = 3000
        private const val MAX_PATH = 96
        private const val MAX_RANGE = 48.0

        /** Slightly greedy: trades a guaranteed-shortest route for far fewer
         *  expansions, which matters more than optimality here. */
        private const val HEURISTIC_WEIGHT = 1.15

        /**
         * Extra cost for a node with nothing to cling to.
         *
         * Heavy on purpose. This creature travels on surfaces; a route
         * through open air is one it cannot follow, it just falls out of.
         * At a small penalty the straight climb to a target overhead can
         * still come out cheaper than going along the floor, up a wall and
         * across a ceiling — and with a greedy heuristic that column gets
         * explored first, so it wins more often than the arithmetic
         * suggests. Priced this high, any route with geometry to crawl on
         * beats flying.
         */
        private const val OPEN_AIR_PENALTY = 6.0

        private const val LOS_LOOKAHEAD = 10
        private const val LOS_STEP = 0.5
    }
}

/**
 * A route held by one connected mass, shared by reference across every
 * particle in it.
 *
 * Shared rather than copied so that steering, waypoint advance, and repath
 * decisions all read the same state — hundreds of particles agreeing because
 * they are literally looking at one object, not because their copies happen
 * to match.
 */
class RemnantPath {
    val nodes = LongArrayList()
    var index = 0
    var targetX = 0.0
    var targetY = 0.0
    var targetZ = 0.0
    var builtAt = Long.MIN_VALUE

    /** No new search before this tick: the last one found no route. */
    var failedUntil = Long.MIN_VALUE

    /** Whether this mass has given up going around and is forcing through,
     *  and the progress state behind that decision. Held per mass, not per
     *  particle: a particle wedged among its own neighbours is not stuck, the
     *  crowd is simply where it is. Only the body failing to move counts. */
    var seeping = false
    var seepTicks = 0
    var stuckTicks = 0
    var lastX = Double.NaN
    var lastY = 0.0
    var lastZ = 0.0
    var evaluatedAt = Long.MIN_VALUE

    val exhausted: Boolean get() = index >= nodes.size

    fun reset(x: Double, y: Double, z: Double, tick: Long) {
        index = 0
        targetX = x; targetY = y; targetZ = z
        builtAt = tick
    }
}
