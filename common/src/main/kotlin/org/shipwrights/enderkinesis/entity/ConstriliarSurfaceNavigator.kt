package org.shipwrights.enderkinesis.entity

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
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
        set(value) {
            field = value
            steerIndex = -1
        }
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

    /** World-space support normal (away from the surface) of [node], written to [dest].
     *  In ship frame the face is a shipyard direction and must turn with the ship. */
    fun nodeWorldUp(level: Level, node: Node, dest: Vector3d): Vector3d {
        val n = node.face.opposite
        dest.set(n.stepX.toDouble(), n.stepY.toDouble(), n.stepZ.toDouble())
        frameShip(level)?.transform?.shipToWorldRotation?.transform(dest)
        return dest
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
                // Keep the route, cut back to its node nearest the fresh spot; stitching
                // extends from there, so a moved target costs no out-and-back detour.
                trimTailToward(destF)
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
        val fromCentre = toFrame(level, fromCentreWorld)
        val startFace = supportFace(level, surfaceUp)
        val planned = withPlanCache {
            val legDest = preparePlan(level, fromCentre, destF, ownShipId, occupiedSpots, walker)
            val start = nearestNode(
                level, BlockPos.containing(fromCentre.x, fromCentre.y, fromCentre.z), avoidOccupied = false, startFace,
            ) ?: return@withPlanCache null
            val goal = nearestGoalNode(level, legDest) ?: return@withPlanCache null
            aStar(level, start, goal)
        } ?: return false
        // The stall clock is NOT reset here: it follows the waypoint (see tickFollow), so
        // a body pinned while replanning toward a moving target still times out.
        path = planned
        index = 0
        stitchBlockedAt = null
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
    private var stallNode: Node? = null

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
                break
            }
        }
        val node = currentNode() ?: return null
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
            stop()
            stuckTicks = 0
            bestWaypointDistSq = Double.MAX_VALUE
            return null
        }
        return steerNode(level, node)
    }

    // String-pulled steering target for the current index; -1 = stale.
    private var steerIndex = -1
    private var steerCached: Node? = null

    /** The furthest node ahead on the same flat ground run with a clear straight line
     *  from the last passed node — a 6-way grid path is a staircase on diagonals, and
     *  steering cell-by-cell zig-zagged the body. Cached per index (line checks are
     *  block queries). Steps, jumps, tight cells, and face changes end the run. */
    private fun steerNode(level: Level, node: Node): Node {
        if (steerIndex == index) return steerCached ?: node
        steerIndex = index
        steerCached = node
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
    ): Node? {
        val preferred = preferFace ?: Direction.DOWN
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

    private fun aStar(level: Level, start: Node, goal: Node): List<Node> {
        val goalCenter = Vec3.atCenterOf(goal.pos)
        val gScore = Object2DoubleOpenHashMap<Node>().apply { defaultReturnValue(Double.MAX_VALUE) }
        val parent = Object2ObjectOpenHashMap<Node, Node>()
        val closed = ObjectOpenHashSet<Node>()
        val open = PriorityQueue<OpenEntry>(OPEN_ORDER)
        gScore.put(start, 0.0)
        open.add(OpenEntry(start, h(start, goalCenter)))
        var best = start
        var bestH = h(start, goalCenter)
        var expansions = 0
        lastReachedGoal = false
        while (open.isNotEmpty() && expansions < MAX_EXPANSIONS) {
            val cur = open.poll().node
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
                    val ng = gScore.getDouble(cur) + stepDist +
                        (if (nb.face == Direction.DOWN) 0.0 else FACE_BIAS) +
                        (if (stepDist > 1.8) JUMP_COST else 0.0) +
                        TIGHT_COST * tightSides(level, nb)
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
                // Concave corner: the blocking block becomes the new support. Unloaded
                // cells block travel but are no support — no wall nodes against them.
                if (isSolid(level, flat) && fits(level, n.pos, d)) {
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
            val goal = nearestGoalNode(level, legDest) ?: return@withPlanCache null
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

    /** Sibling avoidance is a PREFERENCE: a pack crowding one spot can blanket every
     *  candidate cell in exclusion discs — fall back to the nearest node regardless. */
    private fun nearestGoalNode(level: Level, legDest: Vec3): Node? {
        val around = BlockPos.containing(legDest.x, legDest.y, legDest.z)
        return nearestNode(level, around, avoidOccupied = true)
            ?: nearestNode(level, around, avoidOccupied = false)
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
            if (!spotNear(spot, n.pos)) continue
            val nearStart = spot.distanceToSqr(planStart) <= OCCUPIED_START_EXEMPT_SQ
            if (!nearStart || n.face != Direction.DOWN) return true
        }
        return false
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
    private fun blocksTravel(level: Level, pos: BlockPos, face: Direction): Boolean =
        cellFlags(level, pos) and (if (face == Direction.DOWN) BLOCKS_GROUND else BLOCKS_OTHER) != 0

    /** Node validity including the carried-ship footprint: the body cell itself, plus a
     *  (2r+1)² plate of free cells per clearance layer away from the surface — the cargo
     *  layer is where lateral spread lives, the body cell only needs its own column. */
    private fun fits(level: Level, pos: BlockPos, face: Direction): Boolean {
        if (blocksTravel(level, pos, face)) return false
        val out = face.opposite
        val cursor = fitsCursor
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

    private val fitsCursor = BlockPos.MutableBlockPos()

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

    private fun isSolid(level: Level, pos: BlockPos): Boolean = cellFlags(level, pos) and SOLID != 0

    private fun isFree(level: Level, pos: BlockPos): Boolean = cellFlags(level, pos) and FREE != 0

    // A* asks about the same cells many times in one plan. The memo is live only inside
    // a plan, so follow-time queries (isTight) see the current blocks.
    private val cellCache = Long2IntOpenHashMap()
    private var cacheActive = false

    private inline fun <T> withPlanCache(plan: () -> T): T {
        cellCache.clear()
        cacheActive = true
        try {
            return plan()
        } finally {
            cacheActive = false
            cellCache.clear()
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
        // Unloaded chunks are a hard fence: getBlockState there forces a synchronous
        // chunk load on the server. Not-free AND not-solid means no nodes across it.
        if (!LoadedRay.blockLoaded(level, pos)) return COMPUTED or BLOCKS_GROUND or BLOCKS_OTHER
        val state = level.getBlockState(pos)
        val shape = state.getCollisionShape(level, pos)
        val fluid = !state.fluidState.isEmpty
        // Ship-passable collision (leaves) is open space: the body flies through it.
        val solidShape = !shape.isEmpty && !isShipPassable(state)
        val shipState = shipCellState(level, pos)
        var flags = COMPUTED
        // Ship cells are support — EXCEPT constriliar plates: those are creatures (or
        // their corpses), not terrain. A walker planning a stroll across an identical
        // orphaned plate read as "walking on itself".
        if (solidShape || fluid ||
            (shipState != null && !shipState.`is`(EKBlocks.CONSTRILIAR_PLATFORM.get()))
        ) {
            flags = flags or SOLID
        }
        // Fluid is SUPPORT, never walkable space: fluids have no collision shape, so
        // without this a walker standing ON water resolved start nodes INSIDE the lake.
        if (!fluid && !solidShape && shipState == null) flags = flags or FREE
        // Fluid cells aren't traversable — travel rides the surface node ABOVE them.
        // The hovering body glides over low-profile collision on the ground.
        if (shipState != null || fluid || solidShape) {
            flags = flags or BLOCKS_OTHER
            if (shipState != null || fluid || shape.max(Direction.Axis.Y) > LOW_PROFILE_MAX) {
                flags = flags or BLOCKS_GROUND
            }
        }
        return flags
    }

    /** Blocks whose VS2 type resolves to air (vs_mass no_collision — leaves etc.) are
     *  no obstacle to a ship body: the plan walks through them, not around. */
    private fun isShipPassable(state: BlockState): Boolean {
        if (!BlockStateInfo.isSortedRegistryInitialized()) return false
        return BlockStateInfo.get(state)?.second == vsCore.blockTypes.air
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
            if (!state.getCollisionShape(level, shipPos).isEmpty && !isShipPassable(state)) return state
        }
        return null
    }

    private class OpenEntry(val node: Node, val f: Double)

    companion object {

        private val OPEN_ORDER = Comparator<OpenEntry> { a, b -> a.f.compareTo(b.f) }

        // Cell flag bits; COMPUTED keeps a cached entry non-zero.
        private const val COMPUTED = 1
        private const val SOLID = 2
        private const val FREE = 4
        private const val BLOCKS_GROUND = 8
        private const val BLOCKS_OTHER = 16

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
