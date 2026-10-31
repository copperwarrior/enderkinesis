package org.shipwrights.enderkinesis.entity

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.util.LoadedRay
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.getShipsIntersecting
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.world.clipIncludeShips
import java.util.PriorityQueue

/**
 * Any-surface A* for the ship-bodied Constriliar (Spiders-2.0-style directional nodes,
 * reimplemented). A node is an air cell attached to a solid block face — DOWN is regular
 * ground, horizontal faces are walls, UP is a ceiling. Neighbours continue along the
 * plane, turn into concave corners (the blocking block becomes the new support), or wrap
 * convex edges (around a cliff lip onto the wall below). The follower doesn't walk the
 * nodes — it feeds them as hover/velocity targets to the ship controller, so traversal
 * is still pure physics.
 */
class ConstriliarSurfaceNavigator {

    /** [face] points from the air cell toward its supporting solid block. */
    data class Node(val pos: BlockPos, val face: Direction)

    private var path: List<Node> = emptyList()
        set(value) {
            field = value
            steerIndex = -1
            slotScanIndex = -1
        }
    private var index = 0
    private var target: Vec3? = null
    /** The face of the block the order named, so a goal under a cap stays under it. */
    private var targetFace: Direction? = null
    /** Cells other walkers' routes still cross (world frame): a pack spreads over routes. */
    private var crowdCells: LongOpenHashSet? = null

    /** Carried-ship footprint: lateral chebyshev radius (0 = bare 1×1 back) and layers
     *  of clearance away from the surface (body + cargo). Set from the ship AABB before
     *  each plan; conservative — the footprint doesn't shrink with yaw. */
    private var clearRadius = 0
    private var clearHeight = 2

    fun setClearance(radius: Int, height: Int) {
        clearRadius = radius.coerceIn(0, MAX_CLEAR_RADIUS)
        clearHeight = height.coerceIn(1, MAX_CLEAR_HEIGHT)
    }

    fun isInProgress(): Boolean = index < path.size

    fun currentNode(): Node? = path.getOrNull(index)

    internal fun debugSummary(): String {
        fun fmt(n: Node?) = n?.let { "${it.pos.x},${it.pos.y},${it.pos.z}/${it.face}" } ?: "-"
        val steer = steerCached.takeIf { steerIndex == index }
        val frame = if (frameShipId == -1L) "world" else "ship $frameShipId"
        return "path ${index}/${path.size} node=${fmt(currentNode())} next=${fmt(path.getOrNull(index + 1))} " +
            "steer=${fmt(steer)} stuck=$stuckTicks expanded=$lastExpansions " +
            "search=${session?.let { "${it.expansions}/$sessionCap" } ?: "-"} frame=$frame " +
            "target=${target?.let { ConstriliarDebugLog.v(it) } ?: "-"}"
    }

    fun stop() {
        session = null
        retrySearch = null
        trappedTicks = 0
        parkedSessions = 0
        path = emptyList()
        index = 0
        target = null
        frameShipId = -1L
        stitchBlockedAt = null
        stallNode = null
    }

    /** Drops the route past its node nearest [dest] (frame coords). */
    private fun trimTailToward(dest: Vec3) {
        var best = index
        var bestDistSq = Double.MAX_VALUE
        for (i in index until path.size) {
            val p = path[i].pos
            val dx = p.x + 0.5 - dest.x
            val dy = p.y + 0.5 - dest.y
            val dz = p.z + 0.5 - dest.z
            val dSq = dx * dx + dy * dy + dz * dz
            if (dSq < bestDistSq) {
                bestDistSq = dSq
                best = i
            }
        }
        if (best < path.size - 1) path = path.subList(0, best + 1).toList()
    }

    /** The support face the walker stands on (frame coords), from its world surface
     *  normal; null near an edge, where no face clearly dominates. */
    private fun supportFace(level: Level, surfaceUpWorld: org.joml.Vector3dc): Direction? {
        val u = scratch.set(surfaceUpWorld)
        frameShip(level)?.worldToShip?.transformDirection(u)
        if (Math.max(Math.abs(u.x), Math.max(Math.abs(u.y), Math.abs(u.z))) < START_FACE_MIN_AXIS) return null
        return Direction.getNearest(-u.x.toFloat(), -u.y.toFloat(), -u.z.toFloat())
    }

    /** Other loaded ships in the plan region (own ship excluded) — their decks and
     *  hulls count as surface for this plan's solidity queries. */
    private var nearbyShips: List<Ship> = emptyList()

    /** Spots other constriliars stand at — goal selection parks beside them instead of
     *  converging onto the same cell (their ships are already solid obstacles). */
    private var occupied: List<Vec3> = emptyList()

    /** Plan-start centre, for the jam-escape exemption in [occupantBlocks]. */
    private var planStart: Vec3 = Vec3.ZERO

    /** Ship-frame planning (VS2-analogous): when start AND destination stand on the
     *  same ship's deck, the whole plan runs in that ship's SHIPYARD grid and every
     *  node converts to world through the live transform at follow time. World-grid
     *  planning on a yawed deck aliases flat floors into diagonal staircases — the
     *  ship's own block lattice is the walkable surface. -1 = world frame. */
    private var frameShipId: Long = -1L

    // One ship lookup per tick: the frame is read several times a tick on a deck.
    private var frameShipCached: Ship? = null
    private var frameShipCachedId = -1L
    private var frameShipCachedTick = Long.MIN_VALUE
    private var frameShipCachedLevel: Level? = null

    private fun frameShip(level: Level): Ship? {
        if (frameShipId == -1L) return null
        val server = level as? net.minecraft.server.level.ServerLevel ?: return null
        val now = server.gameTime
        if (frameShipCachedId == frameShipId && frameShipCachedTick == now && frameShipCachedLevel === server) {
            return frameShipCached
        }
        val ship = server.shipObjectWorld.loadedShips.getById(frameShipId)
        frameShipCached = ship
        frameShipCachedId = frameShipId
        frameShipCachedTick = now
        frameShipCachedLevel = server
        return ship
    }

    // Server thread only: frameShip() is null off a ServerLevel.
    private val frameScratch = Vector3d()

    private fun toFrame(level: Level, v: Vec3): Vec3 {
        val ship = frameShip(level) ?: return v
        val p = ship.worldToShip.transformPosition(frameScratch.set(v.x, v.y, v.z))
        return Vec3(p.x, p.y, p.z)
    }

    fun frameToWorld(level: Level, v: Vec3): Vec3 {
        val ship = frameShip(level) ?: return v
        val p = ship.shipToWorld.transformPosition(frameScratch.set(v.x, v.y, v.z))
        return Vec3(p.x, p.y, p.z)
    }

    /** World-space centre of a path node — THE accessor drive code must use. */
    fun nodeWorldCenter(level: Level, node: Node): Vec3 {
        val ship = frameShip(level) ?: return Vec3.atCenterOf(node.pos)
        val p = ship.shipToWorld.transformPosition(frameScratch.set(node.pos.x + 0.5, node.pos.y + 0.5, node.pos.z + 0.5))
        return Vec3(p.x, p.y, p.z)
    }

    /** World-space support normal (away from the surface) of [node], written to [dest].
     *  In ship frame the face is a shipyard direction and must turn with the ship. */
    fun nodeWorldUp(level: Level, node: Node, dest: Vector3d): Vector3d {
        val n = node.face.opposite
        dest.set(n.stepX.toDouble(), n.stepY.toDouble(), n.stepZ.toDouble())
        frameShip(level)?.transform?.shipToWorldRotation?.transform(dest)
        return dest
    }

    /**
     * Attitude across a face change on the path, into [out]: the up turns about the
     * shared edge by an angle that follows the body's place on a line-arc-line route
     * (Kim, Kang & Choi, ISARC 2004). The attitude then moves only with progress, and
     * a noisy surface sample cannot flip it. False when no face change is near.
     */
    fun transitionUp(level: Level, bodyWorld: Vec3, out: Vector3d): Boolean {
        for (i in index - 1..index) {
            val a = path.getOrNull(i) ?: continue
            val b = path.getOrNull(i + 1) ?: continue
            if (a.face == b.face) continue
            val body = toFrame(level, bodyWorld)
            // Far from the edge (knocked or fallen off the route) the arc's clamp would
            // still claim the far face.
            if (body.distanceToSqr(Vec3.atCenterOf(a.pos)) > TRANSITION_NEAR_SQ &&
                body.distanceToSqr(Vec3.atCenterOf(b.pos)) > TRANSITION_NEAR_SQ
            ) {
                continue
            }
            // A notch corner turns like a plain concave one, about the wall's lower end.
            when {
                a.pos == b.pos -> edgeArcUp(a, b, true, body, out)
                isNotch(b, a) -> if (pastWallFace(b, body)) notchClimbUp(b, body, out) else continue
                isNotch(a, b) -> edgeArcUp(a, Node(a.pos, b.face), true, outsideWall(a, body), out)
                isWallDrop(a, b) -> wallDropUp(a, b, body, out)
                a.pos.distManhattan(b.pos) == 2 -> edgeArcUp(a, b, false, body, out)
                else -> continue
            }
            frameShip(level)?.transform?.shipToWorldRotation?.transform(out)
            return true
        }
        return false
    }

    /** Floor to a wall that ends above it: the body turns onto the wall as it nears the
     *  wall's face, at floor height, and then climbs. A turn that waited for height
     *  never came: the floor hover held the body down until it turned. */
    private fun notchClimbUp(wall: Node, body: Vec3, out: Vector3d) {
        val d = wall.face
        val faceAlong = (wall.pos.x + 0.5) * d.stepX + (wall.pos.y + 0.5) * d.stepY + (wall.pos.z + 0.5) * d.stepZ + 0.5
        val gap = faceAlong - (body.x * d.stepX + body.y * d.stepY + body.z * d.stepZ)
        val theta = Math.PI / 2 * ((TRANSITION_RIDE + NOTCH_APPROACH - gap) / NOTCH_APPROACH).coerceIn(0.0, 1.0)
        val c = Math.cos(theta)
        val sn = Math.sin(theta)
        out.set(-d.stepX * sn, c - d.stepY * sn, -d.stepZ * sn)
    }

    /** Under a notch's wall the arc read the body as deep in the corner and tilted it
     *  further, and the tilted hover drew it further under: the arc sees the body no
     *  nearer the wall than its ride height. */
    private fun outsideWall(wall: Node, body: Vec3): Vec3 {
        val d = wall.face
        val faceAlong = (wall.pos.x + 0.5) * d.stepX + (wall.pos.y + 0.5) * d.stepY + (wall.pos.z + 0.5) * d.stepZ + 0.5
        val deeper = body.x * d.stepX + body.y * d.stepY + body.z * d.stepZ - (faceAlong - TRANSITION_RIDE)
        return if (deeper <= 0.0) body else body.subtract(d.stepX * deeper, d.stepY * deeper, d.stepZ * deeper)
    }

    /**
     * The next face change on the path, once the body is within reach of it, for the
     * legs to grab ahead: the new face's world normal into [normal], a point on it into
     * [point], and the travel along it into [dir]. A corner has no travel between its
     * nodes; there the body goes on away from the face it leaves.
     */
    fun reachAhead(level: Level, bodyWorld: Vec3, normal: Vector3d, point: Vector3d, dir: Vector3d): Boolean {
        val body = toFrame(level, bodyWorld)
        for (i in index - 1..index + 1) {
            val a = path.getOrNull(i) ?: continue
            val b = path.getOrNull(i + 1) ?: continue
            if (a.face == b.face || body.distanceToSqr(Vec3.atCenterOf(b.pos)) > REACH_AHEAD_SQ) continue
            val nb = b.face.opposite
            var dx = (b.pos.x - a.pos.x).toDouble()
            var dy = (b.pos.y - a.pos.y).toDouble()
            var dz = (b.pos.z - a.pos.z).toDouble()
            val along = dx * nb.stepX + dy * nb.stepY + dz * nb.stepZ
            dx -= nb.stepX * along
            dy -= nb.stepY * along
            dz -= nb.stepZ * along
            if (dx * dx + dy * dy + dz * dz < 1.0e-6) {
                val na = a.face.opposite
                dx = na.stepX.toDouble()
                dy = na.stepY.toDouble()
                dz = na.stepZ.toDouble()
            }
            dir.set(dx, dy, dz).normalize()
            frameShip(level)?.transform?.shipToWorldRotation?.transform(dir)
            nodeWorldUp(level, b, normal)
            val c = nodeWorldCenter(level, b)
            point.set(c.x - normal.x * 0.5, c.y - normal.y * 0.5, c.z - normal.z * 0.5)
            return true
        }
        return false
    }

    /** The nodes this walker has walked toward its order, loops cut out: published on
     *  arrival, so walkers sent to the same spot can follow it. World frame only. */
    private val trail = ArrayDeque<Node>()
    /** How often each node is on [trail]: a loop check without a scan of the trail. */
    private val trailCounts = Object2IntOpenHashMap<Node>()
    /** The spot the walker's whole order points at (world): shared routes match by it. */
    private var shareAnchor: Vec3? = null

    /** A new order: the way to the old spot is no use for it. */
    fun clearTrail() {
        trail.clear()
        trailCounts.clear()
    }

    private fun trailAdd(n: Node) {
        trail.addLast(n)
        trailCounts.addTo(n, 1)
    }

    private fun trailForget(n: Node) {
        if (trailCounts.addTo(n, -1) <= 1) trailCounts.removeInt(n)
    }

    private fun afterAdvance(level: Level, bodyPos: Vec3, passedFrom: Int) {
        holdSlotEntry(level, bodyPos, passedFrom)
        recordTrail(passedFrom)
    }

    /** The node before a slot is where the body turns into it square, the floor node
     *  before a notch climb is where it turns onto the wall, and a turn beside a block
     *  is where it rounds that block: skipped, the body cut the corner or turned under
     *  the wall. Each is reached, not passed by. */
    private fun holdSlotEntry(level: Level, bodyPos: Vec3, from: Int) {
        for (k in from until minOf(index, path.size - 1)) {
            val entry = path[k]
            val next = path[k + 1]
            val slotEntry = entry.face == next.face && isTight(level, next) && !isTight(level, entry)
            if (!slotEntry && !isNotch(next, entry) && !tightTurn(level, k)) continue
            // Along the surface only: the body rides lower than the cell's centre.
            val up = entry.face.opposite
            val dx = bodyPos.x - (entry.pos.x + 0.5)
            val dy = bodyPos.y - (entry.pos.y + 0.5)
            val dz = bodyPos.z - (entry.pos.z + 0.5)
            val along = dx * up.stepX + dy * up.stepY + dz * up.stepZ
            if (dx * dx + dy * dy + dz * dz - along * along > SLOT_ENTRY_SQ) {
                index = k
                return
            }
        }
    }

    /** The route turns at node [k] on one face with a block beside it (a fence post, a
     *  pillar, a wall's end): cut, the body ran into it. */
    private fun tightTurn(level: Level, k: Int): Boolean {
        if (k < 1 || k + 1 >= path.size) return false
        val prev = path[k - 1]
        val node = path[k]
        val next = path[k + 1]
        if (prev.face != node.face || next.face != node.face) return false
        val inX = node.pos.x - prev.pos.x
        val inZ = node.pos.z - prev.pos.z
        val inY = node.pos.y - prev.pos.y
        if (next.pos.x - node.pos.x == inX && next.pos.y - node.pos.y == inY && next.pos.z - node.pos.z == inZ) return false
        for (d in DIRECTIONS) {
            if (d.axis != node.face.axis && blocksTravel(level, node.pos.relative(d), node.face)) return true
        }
        return false
    }

    private fun recordTrail(from: Int) {
        if (frameShipId != -1L) {
            clearTrail()
            return
        }
        for (i in from until minOf(index, path.size)) {
            val n = path[i]
            if (trailCounts.getInt(n) > 0) {
                while (trail.last() != n) trailForget(trail.removeLast())
            } else {
                trailAdd(n)
            }
            if (trail.size > MAX_TRAIL) trailForget(trail.removeFirst())
        }
    }

    /** At the ordered spot: the trail, with the node reached, becomes a shared route. */
    fun publishTrail(level: Level) {
        currentNode()?.let { if (trail.lastOrNull() != it) trailAdd(it) }
        // The walker stands at its spot (the caller checked): no end check, and stop()
        // has already cleared the target.
        if (trail.size >= 2 && frameShipId == -1L && !level.isClientSide) {
            ConstriliarPathShare.of(level).publish(level.gameTime, trail, shareAnchor)
        }
    }

    private var slotScanIndex = -1
    private var slotNode: Node? = null
    private var slotMask = 0
    private var ceilNode: Node? = null
    private var ceilRoom = 0
    private val slotCursor = BlockPos.MutableBlockPos()

    /**
     * A slot near the body on the route: a node where the body cell or a load layer has
     * both sides of an axis blocked, so the load only passes centred. Its centre goes into
     * [centre]; the result is the blocked axes (1 = X, 2 = Y, 4 = Z), or 0. World frame only.
     */
    fun squeezeAhead(level: Level, bodyWorld: Vec3, centre: Vector3d): Int {
        if (frameShipId != -1L) return 0
        if (slotScanIndex != index) {
            slotScanIndex = index
            withPlanCache { scanSlot(level) }
        }
        val n = slotNode ?: return 0
        val cx = n.pos.x + 0.5
        val cy = n.pos.y + 0.5
        val cz = n.pos.z + 0.5
        if (bodyWorld.distanceToSqr(cx, cy, cz) > SQUEEZE_NEAR_SQ) return 0
        centre.set(cx, cy, cz)
        return slotMask
    }

    private fun scanSlot(level: Level) {
        slotNode = null
        slotMask = 0
        scanCeiling(level)
        for (i in maxOf(0, index - 1)..minOf(index + SQUEEZE_AHEAD, path.size - 1)) {
            val mask = slotAxes(level, path[i])
            if (mask == 0) continue
            slotNode = path[i]
            slotMask = mask
            return
        }
    }

    /** The lowest ceiling over the body's stretch of the route on its current face. */
    private fun scanCeiling(level: Level) {
        ceilNode = null
        ceilRoom = SLOT_ROOM_OPEN
        val face = currentNode()?.face ?: return
        for (i in maxOf(0, index - 1)..minOf(index + 1, path.size - 1)) {
            val n = path[i]
            if (n.face != face) continue
            val room = freeAbove(level, n)
            if (room < ceilRoom) {
                ceilRoom = room
                ceilNode = n
            }
        }
    }

    /** Free cells from [n] straight out from its support, up to [SLOT_ROOM_OPEN]. */
    private fun freeAbove(level: Level, n: Node): Int {
        val out = n.face.opposite
        for (k in 0 until SLOT_ROOM_OPEN) {
            slotCursor.set(n.pos).move(out, k)
            if (!isFree(level, slotCursor)) return k
        }
        return SLOT_ROOM_OPEN
    }

    /** The ceiling over the body's stretch of route, the face across from its support:
     *  a point on it into [point] and the route's up into [normal]. False when none is
     *  near. Kept by [squeezeAhead]'s scan. */
    fun ceilingOver(point: Vector3d, normal: Vector3d): Boolean {
        val n = ceilNode ?: return false
        if (frameShipId != -1L || slotScanIndex != index) return false
        val up = n.face.opposite
        normal.set(up.stepX.toDouble(), up.stepY.toDouble(), up.stepZ.toDouble())
        val rise = ceilRoom - HALF_CELL
        point.set(n.pos.x + HALF_CELL + normal.x * rise, n.pos.y + HALF_CELL + normal.y * rise, n.pos.z + HALF_CELL + normal.z * rise)
        return true
    }

    private fun slotAxes(level: Level, n: Node): Int {
        var mask = 0
        val out = n.face.opposite
        for (layer in 0 until clearHeight) {
            for (d in DIRECTIONS) {
                if (d.axis == n.face.axis || d.axisDirection != Direction.AxisDirection.POSITIVE) continue
                slotCursor.set(n.pos).move(out, layer)
                if (isFree(level, slotCursor.move(d)) || isFree(level, slotCursor.move(d.opposite, 2))) continue
                slotCursor.move(d)
                // Thin blocks (fence posts, walls) leave room in their own cells: the gap
                // between two of them is wider than the load and needs no line-up.
                if (sideRoom(level, slotCursor, d) + sideRoom(level, slotCursor, d.opposite) < SLOT_SLACK) {
                    mask = mask or (1 shl d.axis.ordinal)
                }
            }
        }
        return mask
    }

    /** The cells this walker's route still crosses, into [out]; world frame only. */
    fun routeCells(out: LongOpenHashSet) {
        if (frameShipId != -1L) return
        for (i in index until path.size) out.add(path[i].pos.asLong())
    }

    /** Off a floor or in a squeeze the route is one walker wide: the index in [others]
     *  of a walker on the next stretch of it, so this one waits; -1 when it is free. */
    fun narrowAheadTaken(level: Level, others: List<Vec3>): Int {
        val node = currentNode() ?: return -1
        if (node.face == Direction.DOWN && !isTight(level, node)) return -1
        for (i in index until minOf(index + QUEUE_AHEAD, path.size)) {
            val c = nodeWorldCenter(level, path[i])
            for (k in others.indices) if (others[k].distanceToSqr(c) < QUEUE_GAP_SQ) return k
        }
        return -1
    }

    /** The way the route runs at the body, world space, into [out]: toward the node
     *  [ROUTE_AHEAD] on. The walk command is no heading: a step down or a hold
     *  correction leaves it a few noisy cm/s in the plane, and the body spun after it. */
    fun routeDirection(level: Level, bodyWorld: Vec3, out: Vector3d): Boolean {
        val node = currentNode() ?: return false
        val ahead = path[minOf(index + ROUTE_AHEAD, path.size - 1)]
        val body = toFrame(level, bodyWorld)
        var c = Vec3.atCenterOf(ahead.pos)
        if (c.distanceToSqr(body) < ROUTE_MIN_SQ) {
            val prev = path.getOrNull(index - 1) ?: node
            if (prev == ahead) return false
            c = Vec3.atCenterOf(ahead.pos).subtract(Vec3.atCenterOf(prev.pos)).add(body)
        }
        out.set(c.x - body.x, c.y - body.y, c.z - body.z)
        frameShip(level)?.transform?.shipToWorldRotation?.transform(out)
        return true
    }

    /** The body stands out from [wall]'s face, not behind it: a notch climb started from
     *  under the wall's own block turned the body against its back and pushed it away. */
    private fun pastWallFace(wall: Node, body: Vec3): Boolean {
        val up = wall.face.opposite
        val face = wall.pos.relative(wall.face)
        val gap = (body.x - (face.x + 0.5 + up.stepX * 0.5)) * up.stepX +
            (body.y - (face.y + 0.5 + up.stepY * 0.5)) * up.stepY +
            (body.z - (face.z + 0.5 + up.stepZ * 0.5)) * up.stepZ
        return gap > 0.0
    }

    /** [wall] is a wall node straight above floor node [floor], across a notch. */
    private fun isNotch(wall: Node, floor: Node): Boolean {
        if (floor.face != Direction.DOWN || !wall.face.axis.isHorizontal) return false
        val k = wall.pos.y - floor.pos.y
        return k in 1..NOTCH_MAX && wall.pos.x == floor.pos.x && wall.pos.z == floor.pos.z
    }

    /** [yawDeg] rounded to the nearest grid axis of the plan frame — the deck's own
     *  grid on a ship, the world grid otherwise. */
    fun snapYawToGrid(level: Level, yawDeg: Float): Float {
        val gridYaw = frameShip(level)?.let { ship ->
            val forward = ship.transform.shipToWorldRotation.transform(scratch.set(0.0, 0.0, 1.0))
            Math.toDegrees(Math.atan2(forward.z, forward.x)).toFloat() - 90f
        } ?: 0f
        return gridYaw + Math.round(Mth.wrapDegrees(yawDeg - gridYaw) / 90f) * 90f
    }

    private val scratch = Vector3d()

    /** The ship (not [ownShipId]) whose deck supports [worldPos] within a short
     *  drop, or null for world ground. */
    private fun deckShipAt(level: Level, worldPos: Vec3, ownShipId: Long?): Ship? {
        val probe = org.joml.Vector3d()
        for (ship in level.getShipsIntersecting(AABB(worldPos, worldPos).inflate(DECK_PROBE_INFLATE))) {
            if (ship.id == ownShipId) continue
            probe.set(worldPos.x, worldPos.y, worldPos.z)
            ship.worldToShip.transformPosition(probe)
            val base = BlockPos.containing(probe.x, probe.y, probe.z)
            for (dy in 0..DECK_PROBE_DEPTH) {
                if (isSolid(level, base.below(dy))) return ship
            }
        }
        return null
    }

    // Result of the last aStar run, for the exhausted-search hold.
    private var lastExpansions = 0
    private var lastSearch: Search? = null
    /** The search toward the real destination that goes on while the walker walks. */
    private var session: Search? = null
    private var sessionTicks = 0
    private var sessionCap = SESSION_CAP
    private var trappedTicks = 0
    /** Whole searches that ended at the cap while the walker sat at its route's end with
     *  nothing new adopted: wedged in a pose no node holds, it never left. */
    private var parkedSessions = 0

    /** The search has proven for a while that no route leaves where the walker is. */
    fun isTrapped(): Boolean = trappedTicks >= TRAPPED_TICKS || parkedSessions >= PARKED_SESSIONS
    private var retrySearch: Search? = null
    private var retryAt = 0L
    private var retryTarget: Vec3? = null
    private var exhaustedDest: Vec3? = null
    private var exhaustedUntil = 0L
    private var lastReachedGoal = false

    /** Plan (or keep) a path to [dest]. Returns false when no route was found.
     *
     *  Repath threshold scales with remaining distance: a target 30 blocks out that
     *  shifts 2 doesn't invalidate the route's first 25 blocks — the kept path's TAIL
     *  re-aims at the updated dest through [extendPath] instead (splicing). */
    fun moveTo(
        level: Level,
        from: BlockPos,
        dest: Vec3,
        ownShipId: Long?,
        occupiedSpots: List<Vec3>,
        walker: net.minecraft.world.entity.Entity,
        surfaceUp: org.joml.Vector3dc,
        destFace: Direction? = null,
        crowd: LongOpenHashSet? = null,
        anchor: Vec3? = null,
    ): Boolean {
        shareAnchor = anchor
        if (!level.isClientSide) ConstriliarPathShare.of(level).refresh(level.gameTime)
        // Frame election first — switching frames invalidates any kept route.
        val fromCentreWorld = Vec3.atCenterOf(from)
        val startShip = deckShipAt(level, fromCentreWorld, ownShipId)
        val destShip = deckShipAt(level, dest, ownShipId)
        val newFrame = if (startShip != null && startShip.id == destShip?.id) startShip.id else -1L
        if (newFrame != frameShipId) {
            stop()
            frameShipId = newFrame
        }
        // The clicked face is in world axes: on a deck the frame turns with the ship.
        targetFace = if (frameShipId == -1L) destFace else null
        crowdCells = if (frameShipId == -1L) crowd else null
        val destF = toFrame(level, dest)
        val prev = target
        if (keepRoute(level, fromCentreWorld, destF)) return true
        val held = exhaustedDest
        if (held != null && level.gameTime < exhaustedUntil && held.distanceToSqr(destF) < EXHAUSTED_HOLD_DIST_SQ) {
            target = destF
            return isInProgress()
        }
        // A search still at work toward this spot keeps the walker on its partial route.
        if (session != null && prev != null && prev.distanceToSqr(destF) < EXHAUSTED_HOLD_DIST_SQ) {
            target = destF
            return true
        }
        target = destF
        val fromCentre = toFrame(level, fromCentreWorld)
        val startFace = supportFace(level, surfaceUp)
        val planned = withPlanCache {
            val legDest = preparePlan(level, fromCentre, destF, ownShipId, occupiedSpots, walker)
            val start = nearestNode(
                level, BlockPos.containing(fromCentre.x, fromCentre.y, fromCentre.z), avoidOccupied = false, startFace,
            ) ?: return@withPlanCache null
            val goal = legGoal(level, legDest, destF) ?: return@withPlanCache null
            val route = aStar(level, start, goal)
            startSession(level, start, goal, destF)
            // Near the goal only a whole route is walked; the search goes on meanwhile.
            if (lastReachedGoal || mayStitch(fromCentre, destF)) route else emptyList()
        } ?: return false
        // No stall-clock reset: a body pinned while replanning to a moving target times out.
        path = planned
        index = 0
        stitchBlockedAt = null
        noteExhausted(level, destF)
        return path.isNotEmpty()
    }

    private fun noteExhausted(level: Level, destF: Vec3) {
        if (!lastReachedGoal && lastExpansions >= MAX_EXPANSIONS && path.isNotEmpty()) {
            exhaustedDest = destF
            exhaustedUntil = level.gameTime + EXHAUSTED_HOLD_TICKS
        } else {
            exhaustedDest = null
        }
    }

    /** When the quick plan stops short of the real destination, the search toward it goes
     *  on in the background: the quick one itself if its goal is the real one. */
    private fun startSession(level: Level, start: Node, quickGoal: Node, destF: Vec3) {
        val goal = nearestGoalNode(level, destF) ?: quickGoal
        if (lastReachedGoal && quickGoal.pos == goal.pos) {
            session = null
            return
        }
        val quick = lastSearch
        session = if (quick != null && quick.goal == goal) quick else Search(start, goal)
        retrySearch = null
        sessionTicks = 0
        sessionCap = SESSION_CAP
    }

    /**
     * One slice of the background search. A full route replaces the walked one at once;
     * a partial one that ends clearly nearer the goal replaces it every [ADOPT_INTERVAL]
     * ticks, so the walker heads on while the search looks further (only toward a goal
     * beyond one planning leg: see [mayStitch]). A search that runs
     * dry starts again from where the walker is: an order is never given up.
     */
    fun tickSearch(level: Level, bodyPosWorld: Vec3) {
        var search = session ?: return
        if (!level.isClientSide) {
            val share = ConstriliarPathShare.of(level)
            share.refresh(level.gameTime)
            // A pack-mate found the way since this search began: start again to see it.
            if (search.routesSeen >= 0 && search.routesSeen != share.version &&
                share.newRouteNear(search.goalCenter, shareAnchor, search.routesSeen)
            ) {
                restartSession(level, bodyPosWorld, search)
                return
            }
        }
        if (frameShipId != -1L && frameShip(level) == null) {
            session = null
            return
        }
        if (search.exhausted) {
            if (retryHeld(level, search)) {
                // The isTrapped count a restart each tick gave: a pocket of one slice or
                // less was exhausted again each tick.
                trappedTicks = if (search.expansions <= SEARCH_SLICE) trappedTicks + 1 else 0
                return
            }
            restartSession(level, bodyPosWorld, search)
            search = session ?: return
        }
        withSearchCache(search) { search.step(level, SEARCH_SLICE) }
        sessionTicks++
        // Every reachable node searched with no route: the walker is in a pocket the plan
        // cannot leave (a ceiling patch with no way down).
        trappedTicks = if (search.exhausted) trappedTicks + 1 else 0
        when {
            search.reached != null -> {
                val route = search.route()
                adopt(level, bodyPosWorld, route)
                publishRoute(level, route)
                session = null
            }
            search.exhausted -> holdRetry(level, search)
            search.expansions >= sessionCap -> searchOn(level, bodyPosWorld, search)
            sessionTicks % ADOPT_INTERVAL == 0 && gainsOverPath(search) &&
                mayStitch(toFrame(level, bodyPosWorld), search.goalCenter) -> adopt(level, bodyPosWorld, search.route())
        }
    }

    /** An exhausted search waits before it searches the same pocket again. A new shared
     *  route near the goal (checked first in [tickSearch]) or a moved target ends the wait. */
    private fun retryHeld(level: Level, search: Search): Boolean {
        if (retrySearch !== search) {
            holdRetry(level, search)
            return true
        }
        val held = retryTarget
        val now = target
        val moved = held == null || now == null || held.distanceToSqr(now) >= EXHAUSTED_HOLD_DIST_SQ
        return level.gameTime < retryAt && !moved
    }

    private fun holdRetry(level: Level, search: Search) {
        retrySearch = search
        retryAt = level.gameTime + EXHAUSTED_RETRY_TICKS
        retryTarget = target
    }

    /** A capped search: while the walker waits on it (parked, or near the goal where only
     *  a whole route is taken) it searches on with a larger cap rather than start over
     *  and search the same ground again. */
    private fun searchOn(level: Level, bodyPosWorld: Vec3, search: Search) {
        val waiting = index >= path.size || !mayStitch(toFrame(level, bodyPosWorld), search.goalCenter)
        if (waiting && sessionCap < SESSION_CAP * SESSION_GROW_MAX) {
            sessionCap *= 2
            return
        }
        if (index >= path.size) parkedSessions++
        restartSession(level, bodyPosWorld, search)
    }

    /** A partial route may be walked only toward a goal beyond one planning leg. Nearer,
     *  the closest point found is mostly a wall between body and goal: the walkers
     *  pressed into it while the way round went unfound. */
    private fun mayStitch(body: Vec3, goal: Vec3): Boolean = body.distanceToSqr(goal) > MAX_PLAN_RANGE * MAX_PLAN_RANGE

    private fun restartSession(level: Level, bodyPosWorld: Vec3, old: Search) {
        val body = toFrame(level, bodyPosWorld)
        val start = currentNode() ?: withPlanCache {
            nearestNode(level, BlockPos.containing(body.x, body.y, body.z), avoidOccupied = false)
        }
        session = if (start == null) null else Search(start, old.goal)
        retrySearch = null
        sessionTicks = 0
        sessionCap = SESSION_CAP
    }

    private fun gainsOverPath(search: Search): Boolean {
        if (search.best == search.start) return false
        val endH = path.lastOrNull()?.let { h(it, search.goalCenter) } ?: Double.MAX_VALUE
        return search.bestH < endH - PARTIAL_GAIN
    }

    /** Take [route] up at its node nearest the body. */
    private fun adopt(level: Level, bodyPosWorld: Vec3, route: List<Node>) {
        if (route.size < 2) return
        val body = toFrame(level, bodyPosWorld)
        var nearest = 0
        var nearestSq = Double.MAX_VALUE
        for (k in route.indices) {
            val dSq = body.distanceToSqr(Vec3.atCenterOf(route[k].pos))
            if (dSq < nearestSq) {
                nearestSq = dSq
                nearest = k
            }
        }
        path = route
        index = nearest
        stitchBlockedAt = null
        if (nearest < route.size - 1) parkedSessions = 0
    }

    /** Keep the route, cut back to its node nearest the fresh spot; stitching extends from
     *  there, so a moved target costs no out-and-back detour. */
    private fun keepRoute(level: Level, fromCentreWorld: Vec3, destF: Vec3): Boolean {
        val prev = target ?: return false
        if (!isInProgress()) return false
        val remaining = Math.sqrt(toFrame(level, fromCentreWorld).distanceToSqr(destF))
        val threshold = (remaining * REPATH_FRACTION).coerceIn(REPATH_MIN, REPATH_MAX)
        if (prev.distanceToSqr(destF) >= threshold * threshold) return false
        trimTailToward(destF)
        target = destF
        return true
    }

    // Stalled-progress detection (AWCAPI-style hysteresis): grows while the distance to
    // the current waypoint stops shrinking, decays on progress.
    private var bestWaypointDistSq = Double.MAX_VALUE
    private var stuckTicks = 0
    private var stallNode: Node? = null

    /** Waypoints a stall dropped the plan at (plan frame): how often, and until when the
     *  search still pays for them. A cost, not a ban: a squeeze with no other way in is
     *  tried again. */
    private val stallCounts = Object2IntOpenHashMap<Node>()
    private val stallUntil = Object2LongOpenHashMap<Node>()

    /** Ticks without progress toward the current waypoint. */
    fun stallTicks(): Int = stuckTicks

    private fun markStall(now: Long, node: Node) {
        forgetStalls(now)
        if (stallCounts.size >= MAX_STALL_MARKS) {
            stallCounts.clear()
            stallUntil.clear()
        }
        stallCounts.addTo(node, 1)
        stallUntil.put(node, now + STALL_MEMORY_TICKS)
    }

    private fun forgetStalls(now: Long) {
        if (stallUntil.isEmpty()) return
        val iter = stallUntil.object2LongEntrySet().fastIterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.longValue > now) continue
            stallCounts.removeInt(e.key)
            iter.remove()
        }
    }

    /** Advance past reached waypoints and return the current one. Looks ahead several
     *  nodes (same attachment face only) and jumps to the furthest already in reach —
     *  strict one-at-a-time advancement backtracked to passed nodes and orbited
     *  slightly-missed waypoints. Detects stalled progress and forces a replan;
     *  [transitioning] (attitude mid-rotation at a corner) pauses that clock, since a
     *  face transition makes rotational progress with little positional change. */
    fun tickFollow(
        level: Level,
        bodyPosWorld: Vec3,
        bodyUpWorld: Vector3dc,
        transitioning: Boolean = false,
        grabPending: Boolean = false,
    ): Node? {
        forgetStalls(level.gameTime)
        if (index >= path.size) return null
        if (frameShipId != -1L && frameShip(level) == null) {
            // Frame ship unloaded/deleted mid-route — the plan's coordinates are orphaned.
            stop()
            return null
        }
        val bodyPos = toFrame(level, bodyPosWorld)
        // Stitching grows the path; drop the consumed prefix so it stays bounded (one
        // consumed node is kept — activeJumpSegment reads path[index - 1]).
        if (index > PRUNE_CONSUMED_AT) {
            path = path.subList(index - 1, path.size).toList()
            index = 1
        }
        val passedFrom = index
        val currentFace = path[index].face
        val maxI = minOf(index + LOOKAHEAD, path.size - 1)
        for (i in maxI downTo index) {
            val n = path[i]
            if (i > index && n.face != currentFace) continue   // transitions must be visited
            if (bodyPos.distanceToSqr(Vec3.atCenterOf(n.pos)) < ADVANCE_SQ && lineOpen(level, bodyPos, n.pos)) {
                index = foldGate(level, i + 1, bodyUpWorld, grabPending)
                break
            }
        }
        if (foldGate(level, index + 1, bodyUpWorld, grabPending) > index) advancePastNode(level, bodyPos)
        reachSteerTarget(level, bodyPos)
        afterAdvance(level, bodyPos, passedFrom)
        val node = currentNode() ?: return null
        if (offRoute(bodyPos, node)) return null
        if (node != stallNode) {
            stallNode = node
            bestWaypointDistSq = Double.MAX_VALUE
            stuckTicks = 0
        }
        // Stuck check: no meaningful progress toward the waypoint for a sustained
        // stretch → drop the plan; the ordering goal replans from here within ticks.
        val dSq = bodyPos.distanceToSqr(Vec3.atCenterOf(node.pos))
        if (dSq + PROGRESS_EPS_SQ < bestWaypointDistSq) {
            bestWaypointDistSq = dSq
            stuckTicks = (stuckTicks - 2).coerceAtLeast(0)
        } else if (!transitioning) {
            stuckTicks++
        }
        if (stuckTicks > STUCK_TICKS) {
            markStall(level.gameTime, node)
            stop()
            stuckTicks = 0
            bestWaypointDistSq = Double.MAX_VALUE
            return null
        }
        return steerNode(level, node)
    }

    /** The index to move to on the way to [target]: a fold (a turn over a thin plate's
     *  edge, two face changes in one block) holds at each of its nodes until the body
     *  stands on that face and no grab is due. Passed by position alone, the body spun
     *  round the edge and every foot lost its hold. */
    private fun foldGate(level: Level, target: Int, bodyUpWorld: Vector3dc, grabPending: Boolean): Int {
        for (j in index until target) {
            if (!inFold(j)) continue
            // The front feet hold the next face before the body leaves this one.
            if (grabPending || nodeWorldUp(level, path[j], foldUp).dot(bodyUpWorld) < FOLD_SETTLE_DOT) return j
        }
        return target
    }

    private fun inFold(i: Int): Boolean {
        val n = path.getOrNull(i) ?: return false
        val prev = path.getOrNull(i - 1) ?: return false
        if (n.face == prev.face) return false
        val next = path.getOrNull(i + 1)
        val before = path.getOrNull(i - 2)
        return next?.face == prev.face.opposite || before?.face == n.face.opposite
    }

    private val foldUp = Vector3d()

    /** The body steers straight at a node further on along a clear floor run: reaching
     *  it passes every node before it. Fast, it cut a corner, left its current node four
     *  blocks behind, and turned back for it into a shore lip. */
    private fun reachSteerTarget(level: Level, bodyPos: Vec3) {
        if (steerIndex != index || steerTargetIndex <= index) return
        val target = path.getOrNull(steerTargetIndex) ?: return
        if (bodyPos.distanceToSqr(Vec3.atCenterOf(target.pos)) < ADVANCE_SQ && lineOpen(level, bodyPos, target.pos)) {
            index = steerTargetIndex + 1
        }
    }

    /** No solid block between the body and [cell]'s centre (frame coords). In a twisting
     *  tunnel a node a block away through rock counted as reached, and the walker then
     *  pressed into the rock toward the node after it. */
    /** Knocked or fallen far off the route: its faces no longer apply to the body. A
     *  fall the hover rode down a wall kept a ceiling node and rolled the body over. */
    private fun offRoute(bodyPos: Vec3, node: Node): Boolean {
        if (bodyPos.distanceToSqr(Vec3.atCenterOf(node.pos)) <= OFF_ROUTE_SQ) return false
        stop()
        return true
    }

    private fun lineOpen(level: Level, from: Vec3, cell: BlockPos): Boolean {
        val to = Vec3.atCenterOf(cell)
        val steps = Math.max(1, Math.ceil(from.distanceTo(to) / LINE_STEP).toInt())
        for (k in 1 until steps) {
            val t = k.toDouble() / steps
            lineCursor.set(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t, from.z + (to.z - from.z) * t)
            // Water bears the walker but does not hide a node from it: the body rides a
            // flow's surface below the cells on top of it.
            if (isSolid(level, lineCursor) && !isFluidCell(level, lineCursor)) return false
        }
        return true
    }

    private val lineCursor = BlockPos.MutableBlockPos()

    /** A body that steered to a further node can pass a waypoint outside its advance
     *  radius; it then turned back and orbited it. Passing it along the path counts. */
    private fun advancePastNode(level: Level, bodyPos: Vec3) {
        val node = path.getOrNull(index) ?: return
        val next = path.getOrNull(index + 1) ?: return
        if (next.face != node.face) return
        val c = Vec3.atCenterOf(node.pos)
        val ahead = (bodyPos.x - c.x) * (next.pos.x - node.pos.x) + (bodyPos.y - c.y) * (next.pos.y - node.pos.y) +
            (bodyPos.z - c.z) * (next.pos.z - node.pos.z)
        if (ahead > 0.0 && bodyPos.distanceToSqr(c) < PASS_SQ && lineOpen(level, bodyPos, node.pos)) index++
    }

    // String-pulled steering target for the current index; -1 = stale.
    private var steerIndex = -1
    private var steerCached: Node? = null
    private var steerTargetIndex = -1

    /** The furthest node ahead on the same flat ground run with a clear straight line
     *  from the last passed node — a 6-way grid path is a staircase on diagonals, and
     *  steering cell-by-cell zig-zagged the body. Cached per index (line checks are
     *  block queries). Steps, jumps, tight cells, and face changes end the run. */
    private fun steerNode(level: Level, node: Node): Node {
        if (steerIndex == index) return steerCached ?: node
        steerIndex = index
        steerCached = node
        steerTargetIndex = index
        val anchor = path.getOrNull(index - 1)
        if (node.face != Direction.DOWN || anchor == null || anchor.face != Direction.DOWN ||
            anchor.pos.y != node.pos.y || anchor.pos.distManhattan(node.pos) != 1
        ) {
            return node
        }
        withPlanCache {
            if (tightSides(level, node) > 0) return@withPlanCache
            val maxI = minOf(index + STEER_LOOKAHEAD, path.size - 1)
            for (i in index + 1..maxI) {
                val n = path[i]
                if (n.face != Direction.DOWN || n.pos.y != node.pos.y ||
                    path[i - 1].pos.distManhattan(n.pos) != 1 || tightSides(level, n) > 0 ||
                    !groundLineClear(level, anchor.pos, n.pos)
                ) {
                    break
                }
                steerCached = n
                steerTargetIndex = i
            }
        }
        return steerCached ?: node
    }

    /** True when a body-wide strip from [from] to [to] (same Y) is all walkable ground. */
    private fun groundLineClear(level: Level, from: BlockPos, to: BlockPos): Boolean {
        val dx = (to.x - from.x).toDouble()
        val dz = (to.z - from.z).toDouble()
        val samples = Math.ceil(Math.sqrt(dx * dx + dz * dz) / STEER_SAMPLE_STEP).toInt()
        val cell = BlockPos.MutableBlockPos()
        for (k in 0..samples) {
            val t = k.toDouble() / samples
            val px = from.x + 0.5 + dx * t
            val pz = from.z + 0.5 + dz * t
            for (corner in 0..3) {
                val cx = px + if (corner and 1 == 0) -STEER_HALF_WIDTH else STEER_HALF_WIDTH
                val cz = pz + if (corner and 2 == 0) -STEER_HALF_WIDTH else STEER_HALF_WIDTH
                cell.set(Mth.floor(cx), from.y, Mth.floor(cz))
                if (!fits(level, cell, Direction.DOWN)) return false
                cell.move(Direction.DOWN)
                if (!isSolid(level, cell)) return false
            }
        }
        return true
    }

    /** Nearest attachable node around [around]: free cell + headroom + solid support,
     *  [preferFace] (ground by default) preferred via a small cost bias. With
     *  [avoidOccupied], cells another constriliar stands at are rejected. */
    private fun nearestNode(
        level: Level,
        around: BlockPos,
        avoidOccupied: Boolean,
        preferFace: Direction? = null,
        standOnly: Boolean = false,
        onlyFace: Direction? = null,
        radius: Int = NEAR_RADIUS,
    ): Node? {
        val preferred = preferFace ?: Direction.DOWN
        var best: Node? = null
        var bestScore = Double.MAX_VALUE
        val cursor = BlockPos.MutableBlockPos()
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    cursor.set(around.x + dx, around.y + dy, around.z + dz)
                    if (avoidOccupied && isOccupied(cursor)) continue
                    for (face in DIRECTIONS) {
                        if (onlyFace != null && face != onlyFace) continue
                        if (!isSolid(level, cursor.relative(face))) continue
                        if (!fits(level, cursor, face)) continue
                        if (face != Direction.DOWN && isFluidCell(level, cursor.relative(face))) continue
                        // The stance caches keep the node as a key: it must not move under them.
                        if (standOnly && !canStand(level, Node(cursor.immutable(), face))) continue
                        val score = (dx * dx + dy * dy + dz * dz).toDouble() +
                            if (face == preferred) 0.0 else FACE_BIAS
                        if (score < bestScore) {
                            bestScore = score
                            best = Node(cursor.immutable(), face)
                        }
                    }
                }
            }
        }
        return best
    }

    /** One A* search toward [goal]. Its open set lives on, so a long route is searched
     *  a slice per tick while the walker follows the best partial route found so far. */
    private inner class Search(val start: Node, val goal: Node) {
        val goalCenter: Vec3 = Vec3.atCenterOf(goal.pos)
        private val gScore = Object2DoubleOpenHashMap<Node>().apply { defaultReturnValue(Double.MAX_VALUE) }
        private val parent = Object2ObjectOpenHashMap<Node, Node>()
        private val closed = ObjectOpenHashSet<Node>()
        private val open = PriorityQueue<OpenEntry>(OPEN_ORDER)
        val cells = Long2IntOpenHashMap()
        val stances = Object2IntOpenHashMap<Node>().apply { defaultReturnValue(-1) }
        val fitsMemo = Long2IntOpenHashMap()
        var fitsClearance = -1
        val sideShapes = Long2ObjectOpenHashMap<VoxelShape>()
        var best = start
            private set
        var bestH = h(start, goalCenter)
            private set
        var expansions = 0
            private set
        var reached: Node? = null
            private set
        val exhausted: Boolean get() = reached == null && open.isEmpty()
        /** Shared routes toward this goal: reaching any of their nodes reaches the goal. */
        private var portals: Map<Node, ConstriliarPathShare.Portal>? = null
        /** [ConstriliarPathShare.version] when [portals] was taken. */
        var routesSeen = -1L
            private set
        private val rejected = HashSet<ConstriliarPathShare.Route>()
        /** The rest of the shared route a portal led onto. */
        var tail: List<Node> = emptyList()
            private set

        init {
            gScore.put(start, 0.0)
            open.add(OpenEntry(start, bestH))
        }

        fun step(level: Level, budget: Int) {
            val shared = portals ?: (
                if (frameShipId == -1L && !level.isClientSide) {
                    val share = ConstriliarPathShare.of(level)
                    routesSeen = share.version
                    share.portalsNear(level.gameTime, goalCenter, shareAnchor)
                } else {
                    emptyMap()
                }
                ).also { portals = it }
            var n = 0
            while (reached == null && open.isNotEmpty() && n < budget) {
                val cur = open.poll().node
                if (!closed.add(cur)) continue
                n++
                expansions++
                val curH = h(cur, goalCenter)
                if (curH < bestH) {
                    bestH = curH
                    best = cur
                }
                if (cur.pos == goal.pos) {
                    reached = cur
                    return
                }
                val portal = shared[cur]
                if (portal != null && portal.route !in rejected) {
                    if (tailHolds(level, portal)) {
                        reached = cur
                        tail = portal.route.nodes.subList(portal.index + 1, portal.route.nodes.size)
                        return
                    }
                    rejected.add(portal.route)
                }
                expand(level, cur)
            }
        }

        private fun expand(level: Level, cur: Node) {
            forEachNeighbor(level, cur) { nb ->
                val stance = stance(level, nb)
                if (nb !in closed && !occupantBlocks(nb) && stands(stance, nb) && supportHolds(level, nb)) {
                    val ng = gScore.getDouble(cur) + stepCost(level, cur, nb, stance)
                    if (ng < gScore.getDouble(nb)) {
                        gScore.put(nb, ng)
                        parent[nb] = cur
                        // Weighted A*: slightly greedy beats optimal — far fewer
                        // expansions for near-straight paths a creature would take.
                        open.add(OpenEntry(nb, ng + h(nb, goalCenter) * H_WEIGHT))
                    }
                }
            }
        }

        /** The full route once [reached]; else the route to the closest node found. */
        fun route(): List<Node> {
            val end = reached ?: best
            if (end == start && tail.isEmpty()) return emptyList()
            val head = if (end == start) listOf(start) else reconstruct(parent, end)
            return if (tail.isEmpty()) head else head + tail
        }
    }

    /** Another walker's route holds for this one only if its body and load fit and stand
     *  on every node of it here and now. */
    private fun tailHolds(level: Level, portal: ConstriliarPathShare.Portal): Boolean {
        val nodes = portal.route.nodes
        for (i in portal.index + 1 until nodes.size) {
            val n = nodes[i]
            if (!fits(level, n.pos, n.face) || !stands(stance(level, n), n) || !supportHolds(level, n)) return false
        }
        return true
    }

    /** Only a route to the walker's own destination is shared: one to a planning leg's
     *  goal, taken by a pack-mate as the way to the spot, stopped it mid-cave. */
    private fun publishRoute(level: Level, route: List<Node>) {
        val dest = target ?: return
        val end = route.lastOrNull() ?: return
        if (Vec3.atCenterOf(end.pos).distanceToSqr(dest) > ROUTE_END_NEAR_SQ) return
        if (frameShipId == -1L && !level.isClientSide) {
            ConstriliarPathShare.of(level).publish(level.gameTime, route, shareAnchor)
        }
    }

    private fun stepCost(level: Level, cur: Node, nb: Node, stance: Int): Double {
        val stepDist = Math.sqrt(cur.pos.distSqr(nb.pos).toDouble())
        val longCost = when {
            stepDist <= 1.8 || isNotch(nb, cur) || isNotch(cur, nb) || isWallDrop(cur, nb) -> 0.0
            isStride(cur, nb) -> STRIDE_COST
            else -> JUMP_COST
        }
        return stepDist +
            (if (nb.face == Direction.DOWN) 0.0 else FACE_BIAS) +
            (if (nb.face == Direction.UP) CEILING_COST else 0.0) + longCost +
            (FEET - legsOf(stance)) * MISSING_LEG_COST + (RING - ringOf(stance)) * NARROW_COST +
            gripsOf(stance) * GRIP_COST +
            TIGHT_COST * tightSides(level, nb) +
            (if (crowdCells?.contains(nb.pos.asLong()) == true) CROWD_COST else 0.0) +
            STALL_COST * stallCounts.getInt(nb) + occupiedCost(nb)
    }

    /** A quick search within the plan cache, kept as [lastSearch] for the background. */
    private fun aStar(level: Level, start: Node, goal: Node): List<Node> {
        val search = Search(start, goal)
        search.step(level, MAX_EXPANSIONS)
        lastSearch = search
        lastExpansions = search.expansions
        lastReachedGoal = search.reached != null
        val route = search.route()
        if (lastReachedGoal) publishRoute(level, route)
        return route
    }

    private inline fun forEachNeighbor(level: Level, n: Node, action: (Node) -> Unit) {
        val f = n.face
        for (d in DIRECTIONS) {
            if (d == f || d == f.opposite) continue
            val flat = n.pos.relative(d)
            var walkable = false
            if (f == Direction.DOWN && d.axis.isHorizontal) {
                val step = groundStep(level, flat)
                if (step != null) {
                    action(step)
                    continue
                }
            }
            if (blocksTravel(level, flat, f)) {
                // Concave corner: the blocking block becomes the new support. Unloaded
                // cells block travel but are no support — no wall nodes against them.
                if (isSolid(level, flat) && fits(level, n.pos, d) && faceContinues(level, n.pos, flat, f.opposite)) {
                    action(Node(n.pos, d))
                    walkable = true
                }
            } else if (isSolid(level, flat.relative(f))) {
                if (fits(level, flat, f)) {
                    action(Node(flat, f))                               // continue the plane
                    walkable = true
                }
            } else {
                val wrapped = flat.relative(f)                          // convex edge wrap
                if (wrapsOver(level, n.pos, flat, f, d)) {
                    action(Node(wrapped, d.opposite))
                    walkable = true
                }
            }
            if (!walkable && f != Direction.DOWN) {
                faceStep(level, n.pos, d, f)?.let { action(it); walkable = true }
            }
            if (f == Direction.DOWN && d.axis.isHorizontal) {
                notchWall(level, n.pos, d)?.let { action(it); walkable = true }
            } else if (d == Direction.DOWN && f.axis.isHorizontal) {
                notchFloor(level, n.pos, f)?.let(action)
                wallDrop(level, n.pos, f)?.let(action)
            }
            // A narrow level gap is walked across on the legs, even where a climb down
            // into it is possible. A genuine ledge otherwise probes for a jump landing;
            // the follower tells the two apart by [isStride].
            if (f == Direction.DOWN && d.axis.isHorizontal) {
                val stride = probeStride(level, n.pos, d)
                if (stride != null) {
                    action(stride)
                } else if (!walkable) {
                    probeJumpLanding(level, n.pos, d)?.let(action)
                }
            }
        }
    }

    /** Whether the legs can hold the body at [n]: three on a floor, two gripping on a
     *  wall or ceiling. A floor stance must also spread off one line: feet all along a
     *  beam cannot stop the body rolling off it sideways. */
    private fun canStand(level: Level, n: Node): Boolean = stands(stance(level, n), n)

    private fun stands(stance: Int, n: Node): Boolean {
        val floor = n.face == Direction.DOWN
        val minLegs = if (floor) 3 else 2
        return legsOf(stance) >= minLegs && (!floor || stance and SPREAD != 0)
    }

    /**
     * Footing around [n], packed as `legs * 16 + ring`, plus [SPREAD] when footholds
     * lie off the node's line along both in-face axes, plus side grips × [GRIP_UNIT]. The ring is the eight cells
     * around the node on its face that hold a foothold. A leg has footing when its
     * diagonal cell or one of the two cells beside it does, so a beam holds four legs
     * on its along-cells, but its ring is small. Body yaw is unknown here.
     */
    private fun stance(level: Level, n: Node): Int {
        if (!cacheActive) return computeStance(level, n)
        val cached = stanceCache.getInt(n)
        if (cached >= 0) return cached
        // A stance reads only cell flags: with no ship near, every walker reads the same.
        val shared = if (nearbyShips.isEmpty() && frameShipId == -1L && !level.isClientSide) {
            ConstriliarPathShare.of(level).stances
        } else {
            null
        }
        val known = shared?.getInt(n) ?: -1
        val value = if (known >= 0) known else computeStance(level, n).also { shared?.put(n, it) }
        stanceCache.put(n, value)
        return value
    }

    private fun computeStance(level: Level, n: Node): Int {
        val t1 = TANGENT_A[n.face.axis.ordinal]
        val t2 = TANGENT_B[n.face.axis.ordinal]
        var ring = 0
        var mask = 0
        var offA = false
        var offB = false
        for (a in -1..1) {
            for (b in -1..1) {
                if (a == 0 && b == 0) continue
                val cell = probeCursor.setWithOffset(
                    n.pos, t1.stepX * a + t2.stepX * b, t1.stepY * a + t2.stepY * b, t1.stepZ * a + t2.stepZ * b,
                )
                if (!hasFoothold(level, cell, n.face)) continue
                ring++
                mask = mask or (1 shl ((a + 1) * 3 + (b + 1)))
                if (a != 0) offA = true
                if (b != 0) offB = true
            }
        }
        // A beam: no ground beside the node, but its support block's sides are bare. A
        // foot grips each side face, and the two sides clamp the beam.
        var grips = 0
        val f = n.face
        for (side in SIDES) {
            for (axis in 0..1) {
                val a = if (axis == 0) side else 0
                val b = if (axis == 0) 0 else side
                val bit = 1 shl ((a + 1) * 3 + (b + 1))
                if (mask and bit != 0) continue
                val ox = t1.stepX * a + t2.stepX * b
                val oy = t1.stepY * a + t2.stepY * b
                val oz = t1.stepZ * a + t2.stepZ * b
                if (!isFree(level, probeCursor.setWithOffset(n.pos, ox, oy, oz)) ||
                    !isFree(level, probeCursor.setWithOffset(n.pos, ox + f.stepX, oy + f.stepY, oz + f.stepZ))
                ) {
                    continue
                }
                mask = mask or bit
                grips++
                if (a != 0) offA = true
                if (b != 0) offB = true
            }
        }
        var legs = 0
        for (a in SIDES) {
            for (b in SIDES) {
                val diag = 1 shl ((a + 1) * 3 + (b + 1))
                val sideA = 1 shl ((a + 1) * 3 + 1)
                val sideB = 1 shl (3 + (b + 1))
                if (mask and (diag or sideA or sideB) != 0) legs++
            }
        }
        return legs * 16 + ring + grips * GRIP_UNIT + if (offA && offB) SPREAD else 0
    }

    /** A foot can stand in [cell] on a face whose support lies toward [face]: level
     *  with the node, one up (on the block in [cell]), or one down. */
    private fun hasFoothold(level: Level, cell: BlockPos, face: Direction): Boolean {
        val behind = cell.relative(face)
        // Water bears a foot from above only: the side of a flooded shaft is no wall.
        if (face != Direction.DOWN && isFluidCell(level, behind)) return false
        if (isFree(level, cell)) {
            return isSolid(level, behind) || (isFree(level, behind) && isSolid(level, behind.relative(face)))
        }
        return isSolid(level, cell) && isFree(level, cell.relative(face.opposite))
    }

    /** Ground gait: 1-block steps up or down are pure ground moves, not wall nodes.
     *  Modelling steps as concave wall nodes made the attitude commit forward at every
     *  terrain step (face-plant under cargo). Real (2+) walls use the concave branch. */
    private fun groundStep(level: Level, flat: BlockPos): Node? {
        if (blocksTravel(level, flat, Direction.DOWN)) {
            val stepUp = flat.above()
            return if (isFree(level, stepUp) && fits(level, stepUp, Direction.DOWN)) Node(stepUp, Direction.DOWN) else null
        }
        if (!isFree(level, flat) || isSolid(level, flat.below())) return null
        val stepDown = flat.below()
        return if (isSolid(level, stepDown.below()) && fits(level, stepDown, Direction.DOWN)) {
            Node(stepDown, Direction.DOWN)
        } else {
            null
        }
    }

    /** A one-block step on a wall or ceiling (a stepped overhang, a staircase cliff),
     *  as [groundStep] is on a floor: the plate cannot turn onto each one-block face,
     *  so it stays on its own face and steps out or in by a block. The body passes the
     *  corner in one of the two poses, so its load must fit there too. */
    private fun faceStep(level: Level, from: BlockPos, d: Direction, f: Direction): Node? {
        val flat = from.relative(d)
        val out = f.opposite
        if (blocksTravel(level, flat, f)) {
            val stepOut = flat.relative(out)
            return if (isFree(level, stepOut) && fits(level, from.relative(out), f) && fits(level, stepOut, f) &&
                isSolid(level, stepOut.relative(f))
            ) {
                Node(stepOut, f)
            } else {
                null
            }
        }
        if (!isFree(level, flat) || isSolid(level, flat.relative(f)) || !fits(level, flat, f)) return null
        val stepIn = flat.relative(f)
        return if (isFree(level, stepIn) && isSolid(level, stepIn.relative(f)) && fits(level, stepIn, f)) {
            Node(stepIn, f)
        } else {
            null
        }
    }

    /** A face change must land on a face at least two cells long along [travel]: the
     *  1-block plate cannot tip onto a 1-block face, and jams on its edge. Ground steps
     *  of one block are plain step moves instead. */
    private fun faceContinues(level: Level, cell: BlockPos, support: BlockPos, travel: Direction): Boolean =
        isFree(level, cell.relative(travel)) && isSolid(level, support.relative(travel))

    /** The body swings over the edge through [flat]: it must fit there too, or a trunk or
     *  overhang above the edge turns the wrap into a climb. */
    private fun wrapsOver(level: Level, from: BlockPos, flat: BlockPos, f: Direction, d: Direction): Boolean {
        val wrapped = flat.relative(f)
        val support = from.relative(f)
        return isFree(level, wrapped) && fits(level, wrapped, d.opposite) && fits(level, flat, f) &&
            sweepClear(level, flat, d) &&
            (faceContinues(level, wrapped, support, f) || foldsOver(level, wrapped, support, f, d) ||
                (d == Direction.UP && stepsUpFrom(level, wrapped, f)))
    }

    /** Turning over an edge from [flat] onward along [d], the load swings through the
     *  cells beyond [flat] that way: between its pose before and after the turn. */
    private fun sweepClear(level: Level, flat: BlockPos, d: Direction): Boolean {
        for (layer in 1 until clearHeight) {
            if (!isFree(level, slotCursor.set(flat).move(d, layer))) return false
        }
        return true
    }

    /** The edge of a plate one block thick (a mushroom cap) is too short to turn onto,
     *  but a second wrap from [edge] reaches the plate's far face: allow the edge as a
     *  pass-through. [f] and [d] are the first wrap's support side and travel. */
    private fun foldsOver(level: Level, edge: BlockPos, support: BlockPos, f: Direction, d: Direction): Boolean {
        val swing = edge.relative(f)
        val far = support.relative(f)
        return !blocksTravel(level, swing, d.opposite) && isFree(level, far) &&
            fits(level, swing, d.opposite) && fits(level, far, f.opposite) && sweepClear(level, swing, f) &&
            faceContinues(level, far, support, d.opposite)
    }

    /** A one-block ledge on a wall's top with a floor one step up beside it (a red
     *  mushroom cap's rim): the plate stands on it only to step up at once. */
    private fun stepsUpFrom(level: Level, ledge: BlockPos, travel: Direction): Boolean {
        val step = ledge.relative(travel)
        return isSolid(level, step) && isFree(level, ledge.above()) && fits(level, step.above(), Direction.DOWN)
    }

    /** A wall that ends [NOTCH_MAX] blocks or less above a floor, with open space under
     *  it (a red mushroom cap's ring): the legs bridge the gap. Floor at [floor], wall
     *  toward [d]; the wall node is [k] above the floor. */
    private fun notchFits(level: Level, floor: BlockPos, d: Direction, k: Int): Boolean {
        val c = probeCursor
        for (j in 0 until k) if (!isFree(level, c.setWithOffset(floor, d.stepX, d.stepY + j, d.stepZ))) return false
        for (j in 1..k) if (!isFree(level, c.setWithOffset(floor, 0, j, 0))) return false
        return isSolid(level, c.setWithOffset(floor, d.stepX, d.stepY + k, d.stepZ)) &&
            isSolid(level, c.setWithOffset(floor, 0, -1, 0)) &&
            fits(level, floor, Direction.DOWN) && fits(level, c.setWithOffset(floor, 0, k, 0), d)
    }

    private fun notchWall(level: Level, floor: BlockPos, d: Direction): Node? {
        for (k in 1..NOTCH_MAX) {
            val wall = floor.above(k)
            if (notchFits(level, floor, d, k) && faceContinues(level, wall, wall.relative(d), Direction.UP)) {
                return Node(wall, d)
            }
        }
        return null
    }

    /** A wall that ends over open space with another wall [NOTCH_MAX] blocks or less
     *  below (a shaft opening into a cave): the legs bridge down onto it. Not the far
     *  side of the gap: the body cannot turn round between two walls. */
    private fun wallDrop(level: Level, wall: BlockPos, f: Direction): Node? {
        if (isSolid(level, wall.below().relative(f))) return null
        for (k in 1..NOTCH_MAX) {
            val cell = wall.below(k)
            if (!isFree(level, cell)) return null
            for (g in DIRECTIONS) {
                if (!g.axis.isHorizontal || g == f.opposite) continue
                if (isSolid(level, cell.relative(g)) && fits(level, cell, g) && turnsAt(level, cell, f, g)) {
                    return Node(cell, g)
                }
            }
        }
        return null
    }

    /** The body comes down to [cell] still pointing off [f] before it turns: the load
     *  needs room there in that pose. */
    private fun turnsAt(level: Level, cell: BlockPos, f: Direction, g: Direction): Boolean {
        if (g == f) return true
        for (layer in 1 until clearHeight) {
            slotCursor.set(cell).move(f.opposite, layer)
            if (!isFree(level, slotCursor)) return false
        }
        return true
    }

    /** Down first in [a]'s pose, then the turn onto [b] about [b]'s own cell: turned at
     *  the top, the load swung into the block beside the upper wall's end. */
    private fun wallDropUp(a: Node, b: Node, body: Vec3, out: Vector3d) {
        if (body.y > b.pos.y + 1.0) {
            val up = a.face.opposite
            out.set(up.stepX.toDouble(), up.stepY.toDouble(), up.stepZ.toDouble())
        } else {
            edgeArcUp(Node(b.pos, a.face), b, true, body, out)
        }
    }

    /** [b] is reached from wall [a] by [wallDrop]. */
    private fun isWallDrop(a: Node, b: Node): Boolean {
        if (!a.face.axis.isHorizontal || !b.face.axis.isHorizontal || b.face == a.face.opposite) return false
        val k = a.pos.y - b.pos.y
        return k in 1..NOTCH_MAX && a.pos.x == b.pos.x && a.pos.z == b.pos.z
    }

    private fun notchFloor(level: Level, wall: BlockPos, d: Direction): Node? {
        for (k in 1..NOTCH_MAX) {
            val floor = wall.below(k)
            if (notchFits(level, floor, d, k)) return Node(floor, Direction.DOWN)
        }
        return null
    }

    /** Landing across a level gap of 1..[STRIDE_MAX_GAP] cells along [d]: the front feet
     *  reach the far side while the back feet still stand on this one. The body
     *  crosses at ground height, so each gap cell must hold it. */
    private fun probeStride(level: Level, from: BlockPos, d: Direction): Node? {
        if (frameShipId != -1L) return null
        val first = from.relative(d)
        if (!isGap(level, first)) return null
        for (dist in 2..STRIDE_MAX_GAP + 1) {
            val cell = from.relative(d, dist)
            if (isSolid(level, cell.below()) && fits(level, cell, Direction.DOWN)) return Node(cell, Direction.DOWN)
            if (!isGap(level, cell)) return null
        }
        return null
    }

    private fun isGap(level: Level, cell: BlockPos): Boolean =
        isFree(level, cell) && !isSolid(level, cell.below()) && fits(level, cell, Direction.DOWN)

    /** Nearest landing cell across a gap along [d]: 2..JUMP_RANGE out, up to 1 up or 2
     *  down, with a clear 2-high flight lane at take-off height and valid footing. */
    private fun probeJumpLanding(level: Level, from: BlockPos, d: Direction): Node? {
        if (frameShipId != -1L) return null   // decks are contiguous; jump rays are world-frame
        for (dist in 2..JUMP_RANGE) {
            // Flight lane must be clear up to this distance.
            // fits() adds the cargo plate over the body cell, as for a walked node.
            val lane = from.relative(d, dist - 1)
            if (!isFree(level, lane.above()) || !fits(level, lane.above(), Direction.DOWN)) return null
            for (dy in intArrayOf(0, 1, -1, -2)) {
                val land = from.relative(d, dist).above(dy)
                if (isSolid(level, land.below()) && fits(level, land, Direction.DOWN)) {
                    return Node(land, Direction.DOWN)
                }
            }
        }
        return null
    }

    /** Drop a clamped leg goal onto the surface below it (fluid and ship decks count);
     *  above void or unloaded space it stays where it was. */
    private fun surfaceSnap(
        level: Level,
        point: Vec3,
        ownShipId: Long?,
        walker: net.minecraft.world.entity.Entity,
    ): Vec3 {
        if (frameShipId != -1L) {
            // Frame coords are real shipyard chunks — scan the column directly; the
            // ship-inclusive world raycast would misinterpret frame coordinates.
            var p = BlockPos.containing(point.x, point.y + SNAP_UP, point.z)
            var budget = (SNAP_UP + SNAP_DOWN).toInt()
            while (budget-- > 0) {
                if (isSolid(level, p)) return Vec3(point.x, p.y + 1.0, point.z)
                p = p.below()
            }
            return point
        }
        val hit = level.clipIncludeShips(
            ConstriliarCollision.Clip(point.add(0.0, SNAP_UP, 0.0), point.add(0.0, -SNAP_DOWN, 0.0), walker),
            true,
            ownShipId,
        )
        return if (hit.type != net.minecraft.world.phys.HitResult.Type.MISS) {
            Vec3(point.x, hit.location.y, point.z)
        } else {
            point
        }
    }

    /** The real (unclamped) destination of the current route, in WORLD space. */
    fun currentDestWorld(level: Level): Vec3? = target?.let { frameToWorld(level, it) }

    /** Leg-stitching: true when the path is running low and doesn't yet end at the real
     *  destination — time to plan the next leg from the path's END, before the walker
     *  runs out and stalls at the seam. */
    fun needsExtension(): Boolean {
        if (session != null) return false
        val dest = target ?: return false
        if (!isInProgress()) return false
        if (path.size - index > STITCH_AHEAD) return false
        if (path.size >= MAX_PATH_NODES) return false
        val end = path.lastOrNull() ?: return false
        val blockedDest = stitchBlockedDest
        if (end == stitchBlockedAt && blockedDest != null && blockedDest.distanceToSqr(dest) < EXHAUSTED_HOLD_DIST_SQ) {
            return false
        }
        return Vec3.atCenterOf(end.pos).distanceToSqr(dest) > STITCH_ARRIVE_SQ
    }

    // A stitch that found no progress is not tried again from the same end toward the
    // same dest — the walker re-plans from where it stands when the path runs out.
    private var stitchBlockedAt: Node? = null
    private var stitchBlockedDest: Vec3? = null

    /** Plan the next leg from the current path's end toward [target] and append it —
     *  the walker strides through leg seams instead of stopping to replan. */
    fun extendPath(level: Level, ownShipId: Long?, occupiedSpots: List<Vec3>, walker: net.minecraft.world.entity.Entity) {
        val dest = target ?: return
        val start = path.lastOrNull() ?: return
        val extra = withPlanCache {
            val legDest = preparePlan(level, Vec3.atCenterOf(start.pos), dest, ownShipId, occupiedSpots, walker)
            val goal = legGoal(level, legDest, dest) ?: return@withPlanCache null
            aStar(level, start, goal)
        } ?: return
        if (extra.size > 1) {
            path = path + extra.drop(1)
        } else {
            stitchBlockedAt = start
            stitchBlockedDest = dest
        }
    }

    /** Sets up one planning leg from [fromCentre] (frame coords) and returns its goal.
     *  Distant targets are planned in legs: the goal clamps to MAX_PLAN_RANGE along the
     *  line, and stitching or re-orders chain the next leg. */
    private fun preparePlan(
        level: Level,
        fromCentre: Vec3,
        dest: Vec3,
        ownShipId: Long?,
        occupiedSpots: List<Vec3>,
        walker: net.minecraft.world.entity.Entity,
    ): Vec3 {
        // In ship frame the deck's own grid IS the world — foreign-ship OBB checks
        // would compare frame coords against world boxes; the empty list disables them.
        // Cleared before the snap, which can query cells in ship frame.
        nearbyShips = emptyList()
        var legDest = dest
        if (fromCentre.distanceToSqr(dest) > MAX_PLAN_RANGE * MAX_PLAN_RANGE) {
            // The straight line to a distant target can pass high over water/valleys —
            // snap the intermediate leg goal onto the surface below it, or every leg
            // across a lake resolves to a mid-air point with no attachable node.
            legDest = surfaceSnap(
                level,
                fromCentre.add(dest.subtract(fromCentre).normalize().scale(MAX_PLAN_RANGE)),
                ownShipId,
                walker,
            )
        }
        if (frameShipId == -1L) {
            val region = AABB(fromCentre, legDest).inflate(SHIP_REGION_MARGIN)
            nearbyShips = level.getShipsIntersecting(region).filter { it.id != ownShipId }
        }
        occupied = occupiedSpots.map { toFrame(level, it) }
        planStart = fromCentre
        return legDest
    }

    /** A far target's leg goal is a point part way along the straight line, dropped onto
     *  the surface. Into a cave that point is in the rock: with no node there, plan for
     *  the real [dest] instead, and the search returns its closest partial route. */
    private fun legGoal(level: Level, legDest: Vec3, dest: Vec3): Node? =
        nearestGoalNode(level, legDest) ?: if (legDest != dest) nearestGoalNode(level, dest) else null

    /** Sibling avoidance is a PREFERENCE: a pack crowding one spot can blanket every
     *  candidate cell in exclusion discs — fall back to the nearest node regardless. */
    private fun nearestGoalNode(level: Level, legDest: Vec3): Node? {
        val around = BlockPos.containing(legDest.x, legDest.y, legDest.z)
        // A goal is a place to stand: a floor the legs hold first, any face they hold
        // next, and the old any-node snap only when nothing near can be stood on.
        val face = targetFace
        if (face != null && legDest == target) faceGoal(level, legDest, face)?.let { return it }
        return nearestNode(level, around, avoidOccupied = true, standOnly = true, onlyFace = Direction.DOWN)
            ?: nearestNode(level, around, avoidOccupied = false, standOnly = true, onlyFace = Direction.DOWN)
            ?: nearestNode(
                level, around, avoidOccupied = false, standOnly = true, onlyFace = Direction.DOWN, radius = FAR_RADIUS,
            )
            ?: nearestNode(level, around, avoidOccupied = true, standOnly = true)
            ?: nearestNode(level, around, avoidOccupied = true)
            ?: nearestNode(level, around, avoidOccupied = false)
    }

    /** The cell in front of the ordered [face], standing on it; else the nearest node
     *  on a face that way. A point on a cap's underside also lies in the cap's own
     *  cell, and the floor-first goal put the walker on top. */
    private fun faceGoal(level: Level, dest: Vec3, face: Direction): Node? {
        val clicked = BlockPos.containing(
            dest.x - face.stepX * FACE_PROBE, dest.y - face.stepY * FACE_PROBE, dest.z - face.stepZ * FACE_PROBE,
        )
        val exact = Node(clicked.relative(face), face.opposite)
        if (isSolid(level, clicked) && fits(level, exact.pos, exact.face) && canStand(level, exact) &&
            !isOccupied(exact.pos)
        ) {
            return exact
        }
        return nearestNode(level, exact.pos, avoidOccupied = true, standOnly = true, onlyFace = face.opposite)
    }

    /** The pending segment is a jump when its endpoints are ground nodes further apart
     *  than any walkable step. Returns take-off and landing. */
    fun activeJumpSegment(): Pair<Node, Node>? {
        if (index <= 0 || index >= path.size) return null
        val from = path[index - 1]
        val to = path[index]
        if (from.face != Direction.DOWN || to.face != Direction.DOWN) return null
        if (isStride(from, to)) return null
        return if (from.pos.distSqr(to.pos) >= JUMP_SEGMENT_DIST_SQ) Pair(from, to) else null
    }

    /** A level, straight hop of at most [STRIDE_MAX_GAP] cells: [probeStride] made it,
     *  since a jump landing that short and level only exists where the stride lane fails. */
    private fun isStride(from: Node, to: Node): Boolean {
        val dx = Math.abs(to.pos.x - from.pos.x)
        val dz = Math.abs(to.pos.z - from.pos.z)
        return to.pos.y == from.pos.y && (dx == 0 || dz == 0) && dx + dz in 2..STRIDE_MAX_GAP + 1
    }

    /** A sibling's hull is never climbable support: sibling plates have real collision,
     *  and pathing a wall-climb over a packmate tipped the body flat on its face chasing
     *  a "wall" that walks. On the ground a sibling is only a cost ([occupiedCost]):
     *  as a wall, one walker in a one-lane corridor sealed the others in. */
    private fun occupantBlocks(n: Node): Boolean =
        n.face != Direction.DOWN && occupied.any { spotNear(it, n.pos) }

    /** Ground beside a sibling: dear but open, so the way round or the queue wins. The
     *  sibling pressed against the start (a jam to walk out of) costs nothing. */
    private fun occupiedCost(n: Node): Double {
        if (n.face != Direction.DOWN) return 0.0
        for (spot in occupied) {
            if (spotNear(spot, n.pos) && spot.distanceToSqr(planStart) > OCCUPIED_START_EXEMPT_SQ) return OCCUPIED_COST
        }
        return 0.0
    }

    private fun isOccupied(pos: BlockPos): Boolean = occupied.any { spotNear(it, pos) }

    private fun spotNear(spot: Vec3, pos: BlockPos): Boolean {
        val dx = spot.x - (pos.x + 0.5)
        val dy = spot.y - (pos.y + 0.5)
        val dz = spot.z - (pos.z + 0.5)
        return dx * dx + dy * dy + dz * dz < OCCUPIED_RADIUS_SQ
    }

    /** A tight cell (blocked laterals) is physically risky: the plate only fits a
     *  1-wide gap when squared to the axis. Penalized in planning; the walker also
     *  slows and squares up when actually traversing one. */
    fun isTight(level: Level, node: Node): Boolean {
        // Both sides of one axis: a step up beside or ahead of the node is no squeeze, and
        // counting it crawled the walker up every terraced slope.
        for (d in DIRECTIONS) {
            if (d.axis == node.face.axis || d.axisDirection != Direction.AxisDirection.POSITIVE) continue
            if (closesIn(level, node, d) && closesIn(level, node, d.opposite)) return true
        }
        return false
    }

    private fun tightSides(level: Level, node: Node): Int {
        var blocked = 0
        for (d in DIRECTIONS) {
            if (d.axis == node.face.axis) continue
            if (closesIn(level, node, d)) blocked++
        }
        return blocked
    }

    /** The cell beside [node] toward [d] blocks travel and its shape reaches the shared
     *  face: a fence post in the middle of its cell leaves the body room. */
    private fun closesIn(level: Level, node: Node, d: Direction): Boolean =
        blocksTravel(level, node.pos.relative(d), node.face) && sideRoom(level, node.pos, d) < SIDE_SLACK

    /** Free depth (blocks) inside the cell beside [cell] toward [d], from the face they
     *  share to its collision shape. A cell with no shape (fluid, a ship, unloaded) is
     *  taken as filled. */
    private fun sideRoom(level: Level, cell: BlockPos, d: Direction): Double {
        val side = cell.relative(d)
        if (isFree(level, side)) return 1.0
        val shape = sideShape(level, side)
        if (shape.isEmpty) return 0.0
        return if (d.axisDirection == Direction.AxisDirection.POSITIVE) shape.min(d.axis) else 1.0 - shape.max(d.axis)
    }

    private fun sideShape(level: Level, pos: BlockPos): VoxelShape {
        if (!LoadedRay.blockLoaded(level, pos)) return Shapes.block()
        if (!cacheActive) return ConstriliarCollision.shipShape(level, pos, level.getBlockState(pos))
        val key = pos.asLong()
        sideShapeCache.get(key)?.let { return it }
        return ConstriliarCollision.shipShape(level, pos, level.getBlockState(pos)).also { sideShapeCache.put(key, it) }
    }

    /** The hovering body glides over low-profile collision (slabs, carpet, snow), so
     *  ground travel only counts a cell as blocking past half a block of height. */
    private fun blocksTravel(level: Level, pos: BlockPos, face: Direction): Boolean =
        cellFlags(level, pos) and (if (face == Direction.DOWN) BLOCKS_GROUND else BLOCKS_OTHER) != 0

    /** Node validity including the carried-ship footprint: the body cell itself, plus a
     *  (2r+1)² plate of free cells per clearance layer away from the surface — the cargo
     *  layer is where lateral spread lives, the body cell only needs its own column. */
    private fun fits(level: Level, pos: BlockPos, face: Direction): Boolean {
        if (!cacheActive) return computeFits(level, pos, face)
        // Bit [face] marks a known result, bit 6 + [face] holds it.
        val key = pos.asLong()
        val bits = fitsCache.get(key)
        val known = 1 shl face.ordinal
        if (bits and known != 0) return bits and (known shl FACES) != 0
        val fit = computeFits(level, pos, face)
        val result = if (fit) known shl FACES else 0
        fitsCache.put(key, bits or known or result)
        return fit
    }

    private fun computeFits(level: Level, pos: BlockPos, face: Direction): Boolean {
        if (blocksTravel(level, pos, face)) return false
        val cursor = fitsCursor
        // Upside down in a low gap, the body lies on the floor with its hover target
        // inside it.
        if (face == Direction.UP && !isFree(level, cursor.setWithOffset(pos, 0, -CEILING_FREE_BELOW, 0))) return false
        val out = face.opposite
        for (layer in 1 until clearHeight) {
            val bx = pos.x + out.stepX * layer
            val by = pos.y + out.stepY * layer
            val bz = pos.z + out.stepZ * layer
            for (a in -clearRadius..clearRadius) {
                for (b in -clearRadius..clearRadius) {
                    when (face.axis) {
                        Direction.Axis.Y -> cursor.set(bx + a, by, bz + b)
                        Direction.Axis.X -> cursor.set(bx, by + a, bz + b)
                        Direction.Axis.Z -> cursor.set(bx + a, by + b, bz)
                    }
                    if (!isFree(level, cursor)) return false
                }
            }
        }
        return true
    }

    private val fitsCursor = BlockPos.MutableBlockPos()
    private val probeCursor = BlockPos.MutableBlockPos()

    private fun reconstruct(parent: Map<Node, Node>, end: Node): List<Node> {
        val out = ArrayList<Node>()
        var cur: Node? = end
        // A parent chain has no cycle, so it holds at most every node with a parent, plus one.
        var guard = 0
        while (cur != null && guard++ <= parent.size) {
            out.add(cur)
            cur = parent[cur]
        }
        out.reverse()
        return out
    }

    private fun h(n: Node, goal: Vec3): Double {
        val dx = goal.x - (n.pos.x + 0.5)
        val dy = goal.y - (n.pos.y + 0.5)
        val dz = goal.z - (n.pos.z + 0.5)
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun isSolid(level: Level, pos: BlockPos): Boolean = cellFlags(level, pos) and SOLID != 0

    private fun isFluidCell(level: Level, pos: BlockPos): Boolean = cellFlags(level, pos) and FLUID != 0

    /** Water holds a walker on its surface, never on its side or underside: a body
     *  under it floats up. A route down the side of a flooded shaft could not be walked. */
    private fun supportHolds(level: Level, n: Node): Boolean =
        n.face == Direction.DOWN || !isFluidCell(level, n.pos.relative(n.face))

    private fun isFree(level: Level, pos: BlockPos): Boolean = cellFlags(level, pos) and FREE != 0

    // A* asks about the same cells many times in one plan. The memo is live only inside
    // a plan, so follow-time queries (isTight) see the current blocks.
    private var cellCache = Long2IntOpenHashMap()
    private var cacheActive = false

    private var stanceCache = Object2IntOpenHashMap<Node>().apply { defaultReturnValue(-1) }
    private var fitsCache = Long2IntOpenHashMap()
    private var sideShapeCache = Long2ObjectOpenHashMap<VoxelShape>()

    private inline fun <T> withPlanCache(plan: () -> T): T {
        clearPlanCaches()
        cacheActive = true
        try {
            return plan()
        } finally {
            cacheActive = false
            clearPlanCaches()
        }
    }

    private fun clearPlanCaches() {
        cellCache.clear()
        stanceCache.clear()
        fitsCache.clear()
        sideShapeCache.clear()
    }

    /** A background search keeps its own cell cache between slices. */
    private inline fun <T> withSearchCache(search: Search, plan: () -> T): T {
        val cells = cellCache
        val stances = stanceCache
        val fitted = fitsCache
        val sideShapes = sideShapeCache
        // The load can change between slices; fit results hold for one footprint only.
        val clearance = clearRadius * (MAX_CLEAR_HEIGHT + 1) + clearHeight
        if (search.fitsClearance != clearance) {
            search.fitsMemo.clear()
            search.fitsClearance = clearance
        }
        cellCache = search.cells
        stanceCache = search.stances
        fitsCache = search.fitsMemo
        sideShapeCache = search.sideShapes
        cacheActive = true
        try {
            return plan()
        } finally {
            cacheActive = false
            cellCache = cells
            stanceCache = stances
            fitsCache = fitted
            sideShapeCache = sideShapes
        }
    }

    private fun cellFlags(level: Level, pos: BlockPos): Int {
        if (!cacheActive) return computeCellFlags(level, pos)
        val key = pos.asLong()
        val cached = cellCache.get(key)
        if (cached != 0) return cached
        return computeCellFlags(level, pos).also { cellCache.put(key, it) }
    }

    // Solidity queries see the world grid PLUS nearby ships: a cell counts solid when
    // any ship block occupies its centre (world→shipyard transform, cell-centre sample —
    // approximate for rotated ships, exact for resting axis-aligned ones). Fluids count
    // as support too — the hover walks on water and lava.
    private fun computeCellFlags(level: Level, pos: BlockPos): Int {
        val terrain = terrainFlags(level, pos)
        // Unloaded (neither solid nor free): a fence, with no ship laid over it.
        if (terrain and (SOLID or FREE) == 0) return terrain
        val shipState = shipCellState(level, pos) ?: return terrain
        // Ship cells are support — EXCEPT constriliar plates: those are creatures (or
        // their corpses), not terrain. A walker planning a stroll across an identical
        // orphaned plate read as "walking on itself".
        var flags = (terrain and (FREE or FLUID).inv()) or BLOCKS_OTHER or BLOCKS_GROUND
        if (!shipState.`is`(EKBlocks.CONSTRILIAR_PLATFORM.get())) flags = flags or SOLID
        return flags
    }

    /** The blocks' own flags, shared between walkers while a plan runs (ships differ per
     *  walker: each excludes its own). */
    private fun terrainFlags(level: Level, pos: BlockPos): Int {
        if (!cacheActive || level.isClientSide) return computeTerrainFlags(level, pos)
        val shared = ConstriliarPathShare.of(level).cells
        val key = pos.asLong()
        val cached = shared.get(key)
        if (cached != 0) return cached
        return computeTerrainFlags(level, pos).also { shared.put(key, it) }
    }

    private fun isSkin(shape: net.minecraft.world.phys.shapes.VoxelShape): Boolean =
        Direction.Axis.entries.any { shape.max(it) - shape.min(it) <= SKIN_MAX }

    private fun computeTerrainFlags(level: Level, pos: BlockPos): Int {
        // Unloaded chunks are a hard fence: getBlockState there forces a synchronous
        // chunk load on the server. Not-free AND not-solid means no nodes across it.
        if (!LoadedRay.blockLoaded(level, pos)) return COMPUTED or BLOCKS_GROUND or BLOCKS_OTHER
        val state = level.getBlockState(pos)
        // The shape the ship body meets, not the player's: a cobweb blocks a walker.
        val shape = ConstriliarCollision.shipShape(level, pos, state)
        // A thin sheet of flowing water is no surface: the hover rides it a few pixels
        // over the floor under it, and nodes on top of it sat a block above the body.
        val fluid = !state.fluidState.isEmpty && state.fluidState.getHeight(level, pos) > SHALLOW_FLUID_MAX
        // A skin on a face (a sculk vein, a carpet) is a plate the body glides over: as a
        // solid cell, each vein of a patch read as a step and nodes floated above the body.
        val solidShape = !shape.isEmpty && !isSkin(shape)
        var flags = COMPUTED
        if (solidShape || fluid) flags = flags or SOLID
        // Fluid is SUPPORT, never walkable space: fluids have no collision shape, so
        // without this a walker standing ON water resolved start nodes INSIDE the lake.
        if (!fluid && !solidShape) flags = flags or FREE
        if (fluid && !solidShape) flags = flags or FLUID
        // Fluid cells aren't traversable — travel rides the surface node ABOVE them.
        // The hovering body glides over low-profile collision on the ground.
        if (fluid || solidShape) {
            flags = flags or BLOCKS_OTHER
            if (fluid || shape.max(Direction.Axis.Y) > LOW_PROFILE_MAX) flags = flags or BLOCKS_GROUND
        }
        return flags
    }

    /** The colliding ship block occupying [pos]'s centre, or null when no nearby ship
     *  claims the cell (or its block is ship-passable). */
    private val cellScratch = Vector3d()

    private fun shipCellState(level: Level, pos: BlockPos): BlockState? {
        if (nearbyShips.isEmpty()) return null
        for (ship in nearbyShips) {
            val local = ship.worldToShip.transformPosition(
                cellScratch.set(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5),
            )
            // Ship-local AABB in shipyard space = an OBB test in world space (the
            // transform carries the rotation): a cheap, rotation-exact broad phase
            // that skips the shipyard block lookup for cells outside the hull bounds.
            val bounds = ship.shipAABB ?: continue
            if (local.x < bounds.minX() - OBB_MARGIN || local.x > bounds.maxX() + OBB_MARGIN ||
                local.y < bounds.minY() - OBB_MARGIN || local.y > bounds.maxY() + OBB_MARGIN ||
                local.z < bounds.minZ() - OBB_MARGIN || local.z > bounds.maxZ() + OBB_MARGIN
            ) {
                continue
            }
            val shipPos = BlockPos.containing(local.x, local.y, local.z)
            val state = level.getBlockState(shipPos)
            if (!ConstriliarCollision.shipShape(level, shipPos, state).isEmpty) return state
        }
        return null
    }

    private class OpenEntry(val node: Node, val f: Double)

    private fun legsOf(stance: Int): Int = (stance shr 4) and 15

    private fun ringOf(stance: Int): Int = stance and 15

    private fun gripsOf(stance: Int): Int = (stance shr 9) and 7

    companion object {

        private val OPEN_ORDER = Comparator<OpenEntry> { a, b -> a.f.compareTo(b.f) }

        // Cell flag bits; COMPUTED keeps a cached entry non-zero.
        private const val COMPUTED = 1
        private const val SOLID = 2
        private const val FREE = 4
        private const val BLOCKS_GROUND = 8
        private const val BLOCKS_OTHER = 16
        private const val FLUID = 32

        /** A search that used all expansions is not started again for this time, unless
         *  the destination moved more than the hold distance. */
        private const val EXHAUSTED_HOLD_TICKS = 100L
        private const val EXHAUSTED_HOLD_DIST_SQ = 8.0 * 8.0
        /** An exhausted background search waits this long before it searches again. */
        private const val EXHAUSTED_RETRY_TICKS = 20L

        private const val MAX_EXPANSIONS = 2000
        /** Background search: steps per tick, steps before it starts again from the
         *  walker, ticks between partial-route swaps, and the gain (blocks nearer the
         *  goal) a partial route needs to replace the walked one. */
        private const val SEARCH_SLICE = 250
        private const val TRAPPED_TICKS = 40
        private const val PARKED_SESSIONS = 2
        private const val SESSION_CAP = 40_000
        /** A waiting walker's search may grow to this many times [SESSION_CAP]. */
        private const val SESSION_GROW_MAX = 4
        private const val ADOPT_INTERVAL = 10
        private const val PARTIAL_GAIN = 2.0
        /** Goal clamp per planning leg; distant targets chain legs. */
        private const val MAX_PLAN_RANGE = 32.0

        /** [transitionUp] in the plan frame: [out] turns from [a]'s up to [b]'s by the
         *  body's angle round the edge (convex) or round the corner arc (concave). */
        internal fun edgeArcUp(a: Node, b: Node, concave: Boolean, body: Vec3, out: Vector3d) {
            val f = a.face
            val nA = f.opposite
            val nB = b.face.opposite
            // Convex: turn about the shared edge. Concave: about the centre of an arc
            // tangent to both faces at ride height.
            val offset = if (concave) TRANSITION_RIDE + CONCAVE_ARC_R else 0.0
            val edge = if (concave) b.face else nB
            val cx = a.pos.x + 0.5 + (f.stepX + edge.stepX) * 0.5 + (nA.stepX + nB.stepX) * offset
            val cy = a.pos.y + 0.5 + (f.stepY + edge.stepY) * 0.5 + (nA.stepY + nB.stepY) * offset
            val cz = a.pos.z + 0.5 + (f.stepZ + edge.stepZ) * 0.5 + (nA.stepZ + nB.stepZ) * offset
            val wx = body.x - cx
            val wy = body.y - cy
            val wz = body.z - cz
            val alongA = wx * nA.stepX + wy * nA.stepY + wz * nA.stepZ
            val alongB = wx * nB.stepX + wy * nB.stepY + wz * nB.stepZ
            val theta = (if (concave) Math.atan2(-alongB, -alongA) else Math.atan2(alongB, alongA))
                .coerceIn(0.0, Math.PI / 2)
            val c = Math.cos(theta)
            val sn = Math.sin(theta)
            out.set(nA.stepX * c + nB.stepX * sn, nA.stepY * c + nB.stepY * sn, nA.stepZ * c + nB.stepZ * sn)
        }

        /** Plate centre above a face it rides on (half block + body lift), and the
         *  concave arc radius at that height. */
        private const val TRANSITION_RIDE = 0.75
        private const val CONCAVE_ARC_R = 0.5
        /** Leg-goal surface snap ray extents. */
        private const val SNAP_UP = 8.0
        /** deckShipAt: probe box inflate + how far below feet a deck may sit. */
        private const val DECK_PROBE_INFLATE = 1.0
        private const val DECK_PROBE_DEPTH = 3
        private const val SNAP_DOWN = 32.0
        /** Heuristic inflation (weighted A*). */
        private const val H_WEIGHT = 1.4
        private const val NEAR_RADIUS = 2
        /** Dominant normal component needed to prefer the stood-on face for a start node. */
        private const val START_FACE_MIN_AXIS = 0.9
        /** Repath threshold scales with remaining distance (splicing handles drift). */
        private const val REPATH_FRACTION = 0.15
        private const val REPATH_MIN = 1.0
        private const val REPATH_MAX = 6.0
        /** Leg stitching: extend when this few nodes remain; consider the route ended
         *  at dest within this range; bound total held nodes; prune consumed prefix. */
        private const val STITCH_AHEAD = 6
        private const val STITCH_ARRIVE_SQ = 4.0
        private const val MAX_PATH_NODES = 96
        private const val PRUNE_CONSUMED_AT = 24
        private const val ADVANCE_SQ = 1.2 * 1.2
        /** Waypoint lookahead depth (same-face only). */
        private const val LOOKAHEAD = 4
        /** Route smoothing: nodes scanned ahead, line sample spacing, and the strip
         *  half-width the body needs (just under half a block). */
        private const val STEER_LOOKAHEAD = 6
        private const val STEER_SAMPLE_STEP = 0.25
        private const val STEER_HALF_WIDTH = 0.45
        /** Stalled-progress budget before dropping the plan. */
        private const val STUCK_TICKS = 40
        /** A waypoint that stalled the plan: cost per stall there (blocks), how long the
         *  search remembers it, and how many it keeps. */
        private const val STALL_COST = 8.0
        private const val STALL_MEMORY_TICKS = 400L
        private const val MAX_STALL_MARKS = 32
        private const val PROGRESS_EPS_SQ = 0.01
        private const val SHIP_REGION_MARGIN = 8.0
        /** Slack on the ship-local bounds test for cells straddling the hull edge. */
        private const val OBB_MARGIN = 0.5
        private const val OCCUPIED_RADIUS_SQ = 1.6 * 1.6
        /** Cost (blocks) of a ground node beside another walker. */
        private const val OCCUPIED_COST = 12.0
        /** A sibling pressed against the start must not wall in the escape route. */
        private const val OCCUPIED_START_EXEMPT_SQ = 4.0
        private const val LOW_PROFILE_MAX = 0.53
        /** Extra cost for non-ground attachment, so it prefers floors when both route. */
        private const val FACE_BIAS = 1.5
        /** Per blocked lateral neighbour: tight squeezes route around unless clearly shorter. */
        private const val TIGHT_COST = 4.0
        /** Gap jumping: max landing distance, its planning surcharge (walking around a
         *  short gap stays preferred), and the segment length that marks a jump. */
        private const val JUMP_RANGE = 4
        private const val JUMP_COST = 3.0
        private const val FEET = 4
        private const val SPREAD = 256
        private const val GRIP_UNIT = 512
        /** Per gripped side face: a beam costs more than ground, but is a stance. */
        private const val GRIP_COST = 0.3
        /** Wider stance search, when nothing near the goal can be stood on. */
        private const val FAR_RADIUS = 4
        /** The two in-face axes of a face, by its axis (X, Y, Z). */
        private val TANGENT_A = arrayOf(Direction.UP, Direction.EAST, Direction.EAST)
        private val TANGENT_B = arrayOf(Direction.SOUTH, Direction.SOUTH, Direction.UP)
        private const val RING = 8
        /** Per leg with no footing near its corner. */
        private const val MISSING_LEG_COST = 1.5
        /** Per ring cell with no foothold: narrow ground (a beam) costs more than wide. */
        private const val NARROW_COST = 0.25
        /** Widest level gap the legs span: about two blocks at the body's reach. */
        private const val STRIDE_MAX_GAP = 2
        private const val STRIDE_COST = 1.0
        private const val CEILING_COST = 2.0
        /** The cell this far below a ceiling node must be free as well. */
        private const val CEILING_FREE_BELOW = 2
        private const val PASS_SQ = 2.5 * 2.5
        private const val LINE_STEP = 0.25
        private const val TRANSITION_NEAR_SQ = 2.5 * 2.5
        /** Past this from its current node (blocks) the body is off the route; above any
         *  jump's reach. */
        private const val OFF_ROUTE_SQ = 6.0 * 6.0
        /** Highest open gap (blocks) between a floor and a wall's lower end that the legs bridge. */
        private const val NOTCH_MAX = 2
        /** Largest load footprint the planner allows for (blocks across, layers up). */
        private const val MAX_CLEAR_RADIUS = 4
        private const val MAX_CLEAR_HEIGHT = 5
        /** Heading: the node this many on, and the least distance to it (blocks²). */
        private const val ROUTE_AHEAD = 2
        /** Slots: nodes scanned past the current one, and how near the body must be (blocks²). */
        private const val SQUEEZE_AHEAD = 3
        private const val SQUEEZE_NEAR_SQ = 2.0 * 2.0
        private const val SLOT_ROOM_OPEN = 6
        /** A shape no thicker than this (blocks) in some axis is a skin, not a block. */
        private const val SKIN_MAX = 0.1875
        private const val MAX_TRAIL = 1500
        /** A shared route must end this near the walker's destination (blocks²). */
        private const val ROUTE_END_NEAR_SQ = 4.0 * 4.0
        /** How near (blocks²) the body must come to the node before a slot. */
        private const val SLOT_ENTRY_SQ = 0.5 * 0.5
        /** Room beyond a cell's width, both sides together, under which a gap is a slot;
         *  and the room on one side under which that side closes in (blocks). */
        private const val SLOT_SLACK = 0.25
        private const val SIDE_SLACK = 0.125
        /** Fluid no taller than this (blocks) is walked through on the floor beneath. */
        private const val SHALLOW_FLUID_MAX = 0.5f
        private const val HALF_CELL = 0.5
        private const val ROUTE_MIN_SQ = 1.0
        /** Cost of a cell another walker's route crosses; under a short detour's cost. */
        private const val CROWD_COST = 2.0
        /** A narrow route counts as taken this many nodes ahead, within this gap (blocks²). */
        private const val QUEUE_AHEAD = 3
        private const val QUEUE_GAP_SQ = 1.5 * 1.5
        /** How far into the clicked block a face-order point is read (blocks). */
        private const val FACE_PROBE = 0.05
        /** Distance (blocks) from the next face's node at which the legs reach for it. */
        private const val REACH_AHEAD_SQ = 2.0 * 2.0
        /** Distance (blocks) over which a notch climb turns the body, out from ride height. */
        private const val NOTCH_APPROACH = 1.25
        /** Body up · fold node up before the path moves past it: about 30°. */
        private const val FOLD_SETTLE_DOT = 0.87
        private const val JUMP_SEGMENT_DIST_SQ = 4
        private val DIRECTIONS = Direction.values()
        private const val FACES = 6
        private val SIDES = intArrayOf(-1, 1)
    }
}
