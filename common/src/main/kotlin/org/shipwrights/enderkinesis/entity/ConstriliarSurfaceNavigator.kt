package org.shipwrights.enderkinesis.entity

import net.minecraft.core.Direction
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.util.LoadedRay
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.BlockStateInfo
import org.valkyrienskies.mod.common.getShipsIntersecting
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.world.clipIncludeShips
import org.valkyrienskies.mod.common.vsCore
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
    private var index = 0
    private var target: Vec3? = null

    /** Carried-ship footprint: lateral chebyshev radius (0 = bare 1×1 back) and layers
     *  of clearance away from the surface (body + cargo). Set from the ship AABB before
     *  each plan; conservative — the footprint doesn't shrink with yaw. */
    private var clearRadius = 0
    private var clearHeight = 2

    fun setClearance(radius: Int, height: Int) {
        clearRadius = radius.coerceIn(0, 3)
        clearHeight = height.coerceIn(1, 4)
    }

    fun isInProgress(): Boolean = index < path.size

    fun currentNode(): Node? = path.getOrNull(index)

    fun stop() {
        path = emptyList()
        index = 0
        target = null
        frameShipId = -1L
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

    private fun frameShip(level: Level): Ship? {
        if (frameShipId == -1L) return null
        return (level as? net.minecraft.server.level.ServerLevel)
            ?.shipObjectWorld?.loadedShips?.getById(frameShipId)
    }

    private fun toFrame(level: Level, v: Vec3): Vec3 {
        val ship = frameShip(level) ?: return v
        val p = ship.worldToShip.transformPosition(org.joml.Vector3d(v.x, v.y, v.z))
        return Vec3(p.x, p.y, p.z)
    }

    fun frameToWorld(level: Level, v: Vec3): Vec3 {
        val ship = frameShip(level) ?: return v
        val p = ship.shipToWorld.transformPosition(org.joml.Vector3d(v.x, v.y, v.z))
        return Vec3(p.x, p.y, p.z)
    }

    /** World-space centre of a path node — THE accessor drive code must use. */
    fun nodeWorldCenter(level: Level, node: Node): Vec3 =
        frameToWorld(level, Vec3.atCenterOf(node.pos))

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

    // Diagnostics for the last aStar run, reported by moveTo's debug log.
    private var lastExpansions = 0
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
    ): Boolean {
        // Frame election first — switching frames invalidates any kept route.
        val fromCentreWorld = Vec3.atCenterOf(from)
        val startShip = deckShipAt(level, fromCentreWorld, ownShipId)
        val destShip = deckShipAt(level, dest, ownShipId)
        val newFrame = if (startShip != null && startShip.id == destShip?.id) startShip.id else -1L
        if (newFrame != frameShipId) {
            stop()
            frameShipId = newFrame
        }
        val destF = toFrame(level, dest)
        val prev = target
        if (prev != null && isInProgress()) {
            val remaining = Math.sqrt(toFrame(level, fromCentreWorld).distanceToSqr(destF))
            val threshold = (remaining * REPATH_FRACTION).coerceIn(REPATH_MIN, REPATH_MAX)
            if (prev.distanceToSqr(destF) < threshold * threshold) {
                // Keep the route; future extensions aim at the fresh spot.
                target = destF
                return true
            }
        }
        val held = exhaustedDest
        if (held != null && level.gameTime < exhaustedUntil && held.distanceToSqr(destF) < EXHAUSTED_HOLD_DIST_SQ) {
            target = destF
            return isInProgress()
        }
        target = destF
        // Distant targets are planned in legs: clamp the goal to MAX_PLAN_RANGE along
        // the line and chain — each arrival (or partial) triggers the next leg from the
        // ordering goal's periodic re-order. Keeps plans cheap and never reaches into
        // far terrain.
        var legDest = destF
        val fromCentre = toFrame(level, fromCentreWorld)
        val fromPos = BlockPos.containing(fromCentre.x, fromCentre.y, fromCentre.z)
        if (fromCentre.distanceToSqr(destF) > MAX_PLAN_RANGE * MAX_PLAN_RANGE) {
            // The straight line to a distant target can pass high over water/valleys —
            // snap the intermediate leg goal onto the surface below it, or every leg
            // across a lake resolves to a mid-air point with no attachable node.
            legDest = surfaceSnap(
                level,
                fromCentre.add(destF.subtract(fromCentre).normalize().scale(MAX_PLAN_RANGE)),
                ownShipId,
                walker,
            )
        }
        // In ship frame the deck's own grid IS the world — foreign-ship OBB checks
        // would compare frame coords against world boxes; the empty list disables them.
        nearbyShips = if (frameShipId != -1L) emptyList() else {
            val region = AABB(fromCentre, legDest).inflate(SHIP_REGION_MARGIN)
            level.getShipsIntersecting(region).filter { it.id != ownShipId }
        }
        occupied = occupiedSpots.map { toFrame(level, it) }
        planStart = fromCentre
        val start = nearestNode(level, fromPos, avoidOccupied = false)
        if (start == null) {
            return false
        }
        val goalAround = BlockPos.containing(legDest.x, legDest.y, legDest.z)
        // Sibling avoidance is a PREFERENCE: a pack crowding one spot can blanket every
        // candidate cell in exclusion discs, deadlocking the whole pack's planning —
        // fall back to the nearest node regardless and let the jam rules sort contact.
        val goal = nearestNode(level, goalAround, avoidOccupied = true)
            ?: nearestNode(level, goalAround, avoidOccupied = false)
        if (goal == null) {
            return false
        }
        path = aStar(level, start, goal)
        index = 0
        bestWaypointDistSq = Double.MAX_VALUE
        stuckTicks = 0
        if (!lastReachedGoal && lastExpansions >= MAX_EXPANSIONS) {
            exhaustedDest = destF
            exhaustedUntil = level.gameTime + EXHAUSTED_HOLD_TICKS
        } else {
            exhaustedDest = null
        }
        return path.isNotEmpty()
    }

    // Stalled-progress detection (AWCAPI-style hysteresis): grows while the distance to
    // the current waypoint stops shrinking, decays on progress.
    private var bestWaypointDistSq = Double.MAX_VALUE
    private var stuckTicks = 0

    /** Advance past reached waypoints and return the current one. Looks ahead several
     *  nodes (same attachment face only) and jumps to the furthest already in reach —
     *  strict one-at-a-time advancement backtracked to passed nodes and orbited
     *  slightly-missed waypoints. Detects stalled progress and forces a replan;
     *  [transitioning] (attitude mid-rotation at a corner) pauses that clock, since a
     *  face transition makes rotational progress with little positional change. */
    fun tickFollow(level: Level, bodyPosWorld: Vec3, transitioning: Boolean = false): Node? {
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
        val currentFace = path[index].face
        val maxI = minOf(index + LOOKAHEAD, path.size - 1)
        for (i in maxI downTo index) {
            val n = path[i]
            if (i > index && n.face != currentFace) continue   // transitions must be visited
            if (bodyPos.distanceToSqr(Vec3.atCenterOf(n.pos)) < ADVANCE_SQ) {
                index = i + 1
                bestWaypointDistSq = Double.MAX_VALUE
                stuckTicks = 0
                break
            }
        }
        val node = currentNode() ?: return null
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
            stop()
            stuckTicks = 0
            bestWaypointDistSq = Double.MAX_VALUE
            return null
        }
        return node
    }

    /** Nearest attachable node around [around]: free cell + headroom + solid support,
     *  ground preferred over walls/ceilings via a small cost bias. With [avoidOccupied],
     *  cells another constriliar stands at are rejected. */
    private fun nearestNode(level: Level, around: BlockPos, avoidOccupied: Boolean): Node? {
        var best: Node? = null
        var bestScore = Double.MAX_VALUE
        val cursor = BlockPos.MutableBlockPos()
        for (dy in -NEAR_RADIUS..NEAR_RADIUS) {
            for (dx in -NEAR_RADIUS..NEAR_RADIUS) {
                for (dz in -NEAR_RADIUS..NEAR_RADIUS) {
                    cursor.set(around.x + dx, around.y + dy, around.z + dz)
                    if (avoidOccupied && isOccupied(cursor)) continue
                    for (face in DIRECTIONS) {
                        if (!isSolid(level, cursor.relative(face))) continue
                        if (!fits(level, cursor, face)) continue
                        val score = (dx * dx + dy * dy + dz * dz).toDouble() +
                            if (face == Direction.DOWN) 0.0 else FACE_BIAS
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

    private fun aStar(level: Level, start: Node, goal: Node): List<Node> {
        val goalCenter = Vec3.atCenterOf(goal.pos)
        val gScore = HashMap<Node, Double>()
        val parent = HashMap<Node, Node>()
        val closed = HashSet<Node>()
        val open = PriorityQueue<Pair<Node, Double>>(compareBy { it.second })
        gScore[start] = 0.0
        open.add(start to h(start, goalCenter))
        var best = start
        var bestH = h(start, goalCenter)
        var expansions = 0
        lastReachedGoal = false
        while (open.isNotEmpty() && expansions < MAX_EXPANSIONS) {
            val cur = open.poll().first
            if (!closed.add(cur)) continue
            expansions++
            val curH = h(cur, goalCenter)
            if (curH < bestH) {
                bestH = curH
                best = cur
            }
            if (cur.pos == goal.pos) {
                lastExpansions = expansions
                lastReachedGoal = true
                return reconstruct(parent, cur)
            }
            forEachNeighbor(level, cur) { nb ->
                if (nb !in closed && !occupantBlocks(nb)) {
                    val stepDist = Math.sqrt(cur.pos.distSqr(nb.pos).toDouble())
                    val ng = gScore.getValue(cur) + stepDist +
                        (if (nb.face == Direction.DOWN) 0.0 else FACE_BIAS) +
                        (if (stepDist > 1.8) JUMP_COST else 0.0) +
                        TIGHT_COST * tightSides(level, nb)
                    if (ng < (gScore[nb] ?: Double.MAX_VALUE)) {
                        gScore[nb] = ng
                        parent[nb] = cur
                        // Weighted A*: slightly greedy beats optimal — far fewer
                        // expansions for near-straight paths a creature would take.
                        open.add(nb to ng + h(nb, goalCenter) * H_WEIGHT)
                    }
                }
            }
        }
        // No full route in budget: head for the closest point found (partial paths keep
        // followers moving instead of freezing at the first hard corner).
        lastExpansions = expansions
        return if (best == start) emptyList() else reconstruct(parent, best)
    }

    private inline fun forEachNeighbor(level: Level, n: Node, action: (Node) -> Unit) {
        val f = n.face
        for (d in DIRECTIONS) {
            if (d == f || d == f.opposite) continue
            val flat = n.pos.relative(d)
            var walkable = false
            // Ground gait: 1-block steps are pure ground moves — the hover crests them
            // without wall attachment. Modelling steps as concave wall nodes made the
            // attitude commit forward at every terrain step (face-plant under cargo).
            // Wall nodes remain for real (2+) walls via the concave branch below.
            if (f == Direction.DOWN && d.axis.isHorizontal) {
                if (blocksTravel(level, flat, f)) {
                    val stepUp = flat.above()
                    if (isFree(level, stepUp) && fits(level, stepUp, Direction.DOWN)) {
                        action(Node(stepUp, Direction.DOWN))
                        continue
                    }
                } else if (isFree(level, flat) && !isSolid(level, flat.below())) {
                    val stepDown = flat.below()
                    if (isSolid(level, stepDown.below()) && fits(level, stepDown, Direction.DOWN)) {
                        action(Node(stepDown, Direction.DOWN))
                        continue
                    }
                }
            }
            if (blocksTravel(level, flat, f)) {
                // Concave corner: the blocking block becomes the new support.
                if (fits(level, n.pos, d)) {
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
                if (isFree(level, wrapped) && fits(level, wrapped, d.opposite)) {
                    action(Node(wrapped, d.opposite))
                    walkable = true
                }
            }
            // Genuine ledge (no walkable continuation on the ground plane): probe for a
            // jump landing across the gap. Detected as a long segment by the follower.
            if (!walkable && f == Direction.DOWN && d.axis.isHorizontal) {
                probeJumpLanding(level, n.pos, d)?.let(action)
            }
        }
    }

    /** Nearest landing cell across a gap along [d]: 2..JUMP_RANGE out, up to 1 up or 2
     *  down, with a clear 2-high flight lane at take-off height and valid footing. */
    private fun probeJumpLanding(level: Level, from: BlockPos, d: Direction): Node? {
        if (frameShipId != -1L) return null   // decks are contiguous; jump rays are world-frame
        for (dist in 2..JUMP_RANGE) {
            // Flight lane must be clear up to this distance.
            val lane = from.relative(d, dist - 1)
            if (!isFree(level, lane.above()) || !isFree(level, lane.above(2))) return null
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
            net.minecraft.world.level.ClipContext(
                point.add(0.0, SNAP_UP, 0.0),
                point.add(0.0, -SNAP_DOWN, 0.0),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.ANY,
                walker,
            ),
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
        val dest = target ?: return false
        if (!isInProgress()) return false
        if (path.size - index > STITCH_AHEAD) return false
        if (path.size >= MAX_PATH_NODES) return false
        val end = path.lastOrNull() ?: return false
        return Vec3.atCenterOf(end.pos).distanceToSqr(dest) > STITCH_ARRIVE_SQ
    }

    /** Plan the next leg from the current path's end toward [target] and append it —
     *  the walker strides through leg seams instead of stopping to replan. */
    fun extendPath(level: Level, ownShipId: Long?, occupiedSpots: List<Vec3>, walker: net.minecraft.world.entity.Entity) {
        val dest = target ?: return
        val start = path.lastOrNull() ?: return
        val fromCentre = Vec3.atCenterOf(start.pos)
        var legDest = dest
        if (fromCentre.distanceToSqr(dest) > MAX_PLAN_RANGE * MAX_PLAN_RANGE) {
            legDest = surfaceSnap(
                level,
                fromCentre.add(dest.subtract(fromCentre).normalize().scale(MAX_PLAN_RANGE)),
                ownShipId,
                walker,
            )
        }
        nearbyShips = if (frameShipId != -1L) emptyList() else {
            val region = AABB(fromCentre, legDest).inflate(SHIP_REGION_MARGIN)
            level.getShipsIntersecting(region).filter { it.id != ownShipId }
        }
        occupied = occupiedSpots.map { toFrame(level, it) }
        planStart = fromCentre
        val stitchAround = BlockPos.containing(legDest.x, legDest.y, legDest.z)
        val goal = nearestNode(level, stitchAround, avoidOccupied = true)
            ?: nearestNode(level, stitchAround, avoidOccupied = false)
            ?: return
        val extra = aStar(level, start, goal)
        if (extra.size > 1) {
            path = path + extra.drop(1)
        }
    }

    /** The pending segment is a jump when its endpoints are ground nodes further apart
     *  than any walkable step. Returns take-off and landing. */
    fun activeJumpSegment(): Pair<Node, Node>? {
        if (index <= 0 || index >= path.size) return null
        val from = path[index - 1]
        val to = path[index]
        if (from.face != Direction.DOWN || to.face != Direction.DOWN) return null
        return if (from.pos.distSqr(to.pos) >= JUMP_SEGMENT_DIST_SQ) Pair(from, to) else null
    }

    /** Sibling walkers block traversal. The jam-escape exemption (a sibling pressed
     *  against our start) applies to GROUND cells only — walking away around the buddy
     *  is allowed, but its hull is never climbable support: sibling plates have real
     *  collision, and pathing a wall-climb over a packmate tipped the body flat on its
     *  face chasing a "wall" that walks. */
    private fun occupantBlocks(n: Node): Boolean {
        for (spot in occupied) {
            val dx = spot.x - (n.pos.x + 0.5)
            val dy = spot.y - (n.pos.y + 0.5)
            val dz = spot.z - (n.pos.z + 0.5)
            if (dx * dx + dy * dy + dz * dz >= OCCUPIED_RADIUS_SQ) continue
            val nearStart = spot.distanceToSqr(planStart) <= OCCUPIED_START_EXEMPT_SQ
            if (!nearStart || n.face != Direction.DOWN) return true
        }
        return false
    }

    private fun isOccupied(pos: BlockPos.MutableBlockPos): Boolean {
        for (spot in occupied) {
            val dx = spot.x - (pos.x + 0.5)
            val dy = spot.y - (pos.y + 0.5)
            val dz = spot.z - (pos.z + 0.5)
            if (dx * dx + dy * dy + dz * dz < OCCUPIED_RADIUS_SQ) return true
        }
        return false
    }

    /** A tight cell (blocked laterals) is physically risky: the plate only fits a
     *  1-wide gap when squared to the axis. Penalized in planning; the walker also
     *  slows and squares up when actually traversing one. */
    fun isTight(level: Level, node: Node): Boolean = tightSides(level, node) > 0

    private fun tightSides(level: Level, node: Node): Int {
        var blocked = 0
        for (d in DIRECTIONS) {
            if (d.axis == node.face.axis) continue
            if (blocksTravel(level, node.pos.relative(d), node.face)) blocked++
        }
        return blocked
    }

    /** The hovering body glides over low-profile collision (slabs, carpet, snow), so
     *  ground travel only counts a cell as blocking past half a block of height. */
    private fun blocksTravel(level: Level, pos: BlockPos, face: Direction): Boolean {
        if (shipCellState(level, pos) != null) return true
        if (!LoadedRay.blockLoaded(level, pos)) return true
        val state = level.getBlockState(pos)
        // Fluid cells aren't traversable — travel rides the surface node ABOVE them
        // (the fluid is their support).
        if (!state.fluidState.isEmpty) return true
        val shape = state.getCollisionShape(level, pos)
        if (shape.isEmpty) return false
        return !(face == Direction.DOWN && shape.max(Direction.Axis.Y) <= LOW_PROFILE_MAX)
    }

    /** Node validity including the carried-ship footprint: the body cell itself, plus a
     *  (2r+1)² plate of free cells per clearance layer away from the surface — the cargo
     *  layer is where lateral spread lives, the body cell only needs its own column. */
    private fun fits(level: Level, pos: BlockPos, face: Direction): Boolean {
        if (blocksTravel(level, pos, face)) return false
        val out = face.opposite
        val cursor = BlockPos.MutableBlockPos()
        for (layer in 1 until clearHeight) {
            val base = pos.relative(out, layer)
            for (a in -clearRadius..clearRadius) {
                for (b in -clearRadius..clearRadius) {
                    when (face.axis) {
                        Direction.Axis.Y -> cursor.set(base.x + a, base.y, base.z + b)
                        Direction.Axis.X -> cursor.set(base.x, base.y + a, base.z + b)
                        Direction.Axis.Z -> cursor.set(base.x + a, base.y + b, base.z)
                    }
                    if (!isFree(level, cursor)) return false
                }
            }
        }
        return true
    }

    private fun reconstruct(parent: Map<Node, Node>, end: Node): List<Node> {
        val out = ArrayList<Node>()
        var cur: Node? = end
        var guard = 0
        while (cur != null && guard++ < MAX_EXPANSIONS) {
            out.add(cur)
            cur = parent[cur]
        }
        out.reverse()
        return out
    }

    private fun h(n: Node, goal: Vec3): Double = Vec3.atCenterOf(n.pos).distanceTo(goal)

    // Solidity queries see the world grid PLUS nearby ships: a cell counts solid when
    // any ship block occupies its centre (world→shipyard transform, cell-centre sample —
    // approximate for rotated ships, exact for resting axis-aligned ones). Fluids count
    // as support too — the hover walks on water and lava.
    private fun isSolid(level: Level, pos: BlockPos): Boolean {
        if (!LoadedRay.blockLoaded(level, pos)) return false
        val state = level.getBlockState(pos)
        if (!state.getCollisionShape(level, pos).isEmpty && !isShipPassable(state)) return true
        if (!state.fluidState.isEmpty) return true
        // Ship cells are support — EXCEPT constriliar plates: those are creatures (or
        // their corpses), not terrain. A walker planning a stroll across an identical
        // orphaned plate read as "walking on itself".
        val shipState = shipCellState(level, pos) ?: return false
        return !shipState.`is`(EKBlocks.CONSTRILIAR_PLATFORM.get())
    }

    // Unloaded chunks are a hard fence: getBlockState there forces a synchronous chunk
    // load on the server — a distant plan must never do that. Not-free AND not-solid
    // means no nodes can exist across the border.
    private fun isFree(level: Level, pos: BlockPos): Boolean {
        if (!LoadedRay.blockLoaded(level, pos)) return false
        val state = level.getBlockState(pos)
        // Fluid is SUPPORT, never walkable space: fluids have no collision shape, so
        // without this a walker standing ON water (its blockPosition IS the fluid cell)
        // resolved start nodes INSIDE the lake and the search thrashed submerged until
        // the budget died — "unreachable" across any body of water.
        if (!state.fluidState.isEmpty) return false
        return (state.getCollisionShape(level, pos).isEmpty || isShipPassable(state)) &&
            shipCellState(level, pos) == null
    }

    /** Blocks whose VS2 type resolves to air (vs_mass no_collision — leaves etc.) are
     *  no obstacle to a ship body: the plan walks through them, not around. */
    private fun isShipPassable(state: BlockState): Boolean {
        if (!BlockStateInfo.isSortedRegistryInitialized()) return false
        return BlockStateInfo.get(state)?.second == vsCore.blockTypes.air
    }

    /** The colliding ship block occupying [pos]'s centre, or null when no nearby ship
     *  claims the cell (or its block is ship-passable). */
    private fun shipCellState(level: Level, pos: BlockPos): BlockState? {
        if (nearbyShips.isEmpty()) return null
        for (ship in nearbyShips) {
            val local = ship.worldToShip.transformPosition(
                Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5),
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
            if (!state.getCollisionShape(level, shipPos).isEmpty && !isShipPassable(state)) return state
        }
        return null
    }

    companion object {


        /** A search that used all expansions is not started again for this time, unless
         *  the destination moved more than the hold distance. */
        private const val EXHAUSTED_HOLD_TICKS = 100L
        private const val EXHAUSTED_HOLD_DIST_SQ = 8.0 * 8.0

        private const val MAX_EXPANSIONS = 2000
        /** Goal clamp per planning leg; distant targets chain legs. */
        private const val MAX_PLAN_RANGE = 32.0
        /** Leg-goal surface snap ray extents. */
        private const val SNAP_UP = 8.0
        /** deckShipAt: probe box inflate + how far below feet a deck may sit. */
        private const val DECK_PROBE_INFLATE = 1.0
        private const val DECK_PROBE_DEPTH = 3
        private const val SNAP_DOWN = 32.0
        /** Heuristic inflation (weighted A*). */
        private const val H_WEIGHT = 1.4
        private const val NEAR_RADIUS = 2
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
        /** Stalled-progress budget before dropping the plan. */
        private const val STUCK_TICKS = 40
        private const val PROGRESS_EPS_SQ = 0.01
        private const val SHIP_REGION_MARGIN = 8.0
        /** Slack on the ship-local bounds test for cells straddling the hull edge. */
        private const val OBB_MARGIN = 0.5
        private const val OCCUPIED_RADIUS_SQ = 1.6 * 1.6
        /** A sibling pressed against the start must not wall in the escape route. */
        private const val OCCUPIED_START_EXEMPT_SQ = 4.0
        private const val LOW_PROFILE_MAX = 0.53
        /** Extra cost for non-ground attachment, so it prefers floors when both route. */
        private const val FACE_BIAS = 1.5
        /** Per blocked lateral neighbour: tight squeezes route around unless clearly shorter. */
        private const val TIGHT_COST = 1.5
        /** Gap jumping: max landing distance, its planning surcharge (walking around a
         *  short gap stays preferred), and the segment length that marks a jump. */
        private const val JUMP_RANGE = 4
        private const val JUMP_COST = 3.0
        private const val JUMP_SEGMENT_DIST_SQ = 4
        private val DIRECTIONS = Direction.values()
    }
}
