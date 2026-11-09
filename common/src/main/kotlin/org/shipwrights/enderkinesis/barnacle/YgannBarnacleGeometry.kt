package org.shipwrights.enderkinesis.barnacle

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.doubles.DoubleArrayList
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntArrays
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.core.Direction
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Quaterniond
import org.joml.Math as JomlMath
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.block.YgannBarnacleBlock
import java.util.Random

/**
 * Shared (server + client) tendril geometry for the Ygann Barnacle. The seeded
 * surface-following walk that the renderer draws is authoritative here so the SERVER
 * can compute the same tendril routes for its damage boxes. The boxes give damage only;
 * they are not colliders.
 *
 * Pure function of `(seed, facing, hull geometry)`; the only world access is
 * `getCollisionShape`, so it runs identically on both sides and inside a VS2 shipyard.
 * Limbs follow the real collision shape of each block, not its full cell.
 */
object YgannBarnacleGeometry {

    // `arcPx` is mutable so the renderer can keep one list for a morphing tendril.
    class BWaypoint(val pos: Vector3d, val normal: Vector3d, var arcPx: Double)
    /** [id] names the limb across walks (see [walk]); never 0. [parent] is the index in this walk of
     *  the path this one branches from (-1 for a root), and [parentArc] is the parent's arc at the
     *  branch point. A parent always has a lower index. */
    class BPath(
        val id: Int, val points: List<BWaypoint>, val phase: Double, val startArc: Double, val originArc: Double,
        val parent: Int, val parentArc: Double,
    ) {
        val totalPx: Double get() = points.lastOrNull()?.arcPx ?: 0.0
        /** Where the drawn tube's straight segments meet (path-local arcs). Fixed for the route, so they
         *  do not shift as the limb grows or moves. Client only. */
        val breaks: DoubleArray by lazy(LazyThreadSafetyMode.NONE) { segmentBreaks(points, DRAW_SEGMENT_PX) }
    }

    /** The hull cells that one walk read, with the collision shape of each. A walk is a pure
     *  function of these shapes, so the walk must run again only when [changed] is true. */
    class HullSnapshot(private val cells: LongArray, private val shapes: Array<VoxelShape>) {
        /** Cells whose block has a dynamic shape (shulker box, moving piston): a block-entity change moves
         *  it with no block change, so [changedSince] always reads them. */
        internal var dynamic: LongArray = LongArray(0)
        private var seenEpoch = NOT_SEEN
        private val index: Long2IntOpenHashMap by lazy(LazyThreadSafetyMode.NONE) {
            Long2IntOpenHashMap(cells.size).apply {
                defaultReturnValue(-1)
                for (i in cells.indices) put(cells[i], i)
            }
        }
        private val chunks: LongOpenHashSet by lazy(LazyThreadSafetyMode.NONE) {
            LongOpenHashSet().apply {
                for (cell in cells) add(ChunkPos.asLong(BlockPos.getX(cell) shr 4, BlockPos.getZ(cell) shr 4))
            }
        }

        fun changed(level: Level): Boolean {
            val cursor = BlockPos.MutableBlockPos()
            for (i in cells.indices) if (cellChanged(level, i, cursor)) return true
            return false
        }

        /** Marks the snapshot as current at [log]'s present epoch; call right after the walk. */
        fun watch(level: Level, log: YgannBarnacleHullLog) {
            seenEpoch = log.stamp(level)
        }

        /** Same answer as [changed], but reads only the cells that [log] saw change since the last call.
         *  It falls back to [changed] when the log cannot tell (other level, overflow, chunk load). */
        fun changedSince(level: Level, log: YgannBarnacleHullLog): Boolean {
            val epoch = log.stamp(level)
            val cursor = BlockPos.MutableBlockPos()
            if (seenEpoch == NOT_SEEN || epoch - seenEpoch > log.capacity || epoch < seenEpoch) {
                return fullCheck(level, epoch, cursor)
            }
            var e = seenEpoch
            while (e < epoch) {
                val key = log.keyAt(e)
                if (log.isChunkAt(e)) {
                    if (chunks.contains(key)) return fullCheck(level, epoch, cursor)
                } else {
                    val i = index.get(key)
                    if (i >= 0 && cellChanged(level, i, cursor)) return true
                }
                e++
            }
            for (cell in dynamic) {
                val i = index.get(cell)
                if (i >= 0 && cellChanged(level, i, cursor)) return true
            }
            seenEpoch = epoch
            return false
        }

        private fun fullCheck(level: Level, epoch: Long, cursor: BlockPos.MutableBlockPos): Boolean {
            for (i in cells.indices) if (cellChanged(level, i, cursor)) return true
            seenEpoch = epoch
            return false
        }

        private fun cellChanged(level: Level, i: Int, cursor: BlockPos.MutableBlockPos): Boolean {
            val now = hullShape(level, cursor.set(cells[i]))
            // Most states share cached shapes; a block with a dynamic shape gives a new equal instance.
            return now !== shapes[i] && Shapes.joinIsNotEmpty(now, shapes[i], BooleanOp.NOT_SAME)
        }

        private companion object {
            const val NOT_SEEN = Long.MIN_VALUE
        }
    }

    class Walk(val paths: List<BPath>, val hull: HullSnapshot)

    /** The collision shape of the hull cell at (x, y, z). */
    fun interface HullShapes {
        fun shape(x: Int, y: Int, z: Int): VoxelShape
    }

    /** One cell's collision boxes, clipped to the cell, in cell-local coordinates (6 values per box),
     *  with their total [volume] and volume-weighted centre. */
    private class CellShape(
        val shape: VoxelShape, val boxes: DoubleArray,
        val volume: Double, val cx: Double, val cy: Double, val cz: Double,
    ) {
        /** The outward normal of the face that a step from [ax],[ay],[az] (air) to the solid point
         *  [sx],[sy],[sz] crossed, all cell-local, into [out]. False when no box holds the solid point. */
        fun crossedFace(
            ax: Double, ay: Double, az: Double, sx: Double, sy: Double, sz: Double, out: Vector3d,
        ): Boolean {
            var b = 0
            while (b < boxes.size) {
                if (sx >= boxes[b] && sy >= boxes[b + 1] && sz >= boxes[b + 2] &&
                    sx < boxes[b + 3] && sy < boxes[b + 4] && sz < boxes[b + 5]
                ) {
                    // The axis on which the air point is farthest outside this box; it may sit exactly on the face.
                    val ox0 = boxes[b] - ax; val ox1 = ax - boxes[b + 3]
                    val oy0 = boxes[b + 1] - ay; val oy1 = ay - boxes[b + 4]
                    val oz0 = boxes[b + 2] - az; val oz1 = az - boxes[b + 5]
                    var best = -FACE_EPS
                    out.set(0.0)
                    if (ox0 > best) { best = ox0; out.set(-1.0, 0.0, 0.0) }
                    if (ox1 > best) { best = ox1; out.set(1.0, 0.0, 0.0) }
                    if (oy0 > best) { best = oy0; out.set(0.0, -1.0, 0.0) }
                    if (oy1 > best) { best = oy1; out.set(0.0, 1.0, 0.0) }
                    if (oz0 > best) { best = oz0; out.set(0.0, 0.0, -1.0) }
                    if (oz1 > best) { out.set(0.0, 0.0, 1.0) }
                    return out.lengthSquared() > 0.0
                }
                b += 6
            }
            return false
        }

        fun contains(x: Double, y: Double, z: Double): Boolean {
            var b = 0
            while (b < boxes.size) {
                if (x >= boxes[b] && y >= boxes[b + 1] && z >= boxes[b + 2] &&
                    x < boxes[b + 3] && y < boxes[b + 4] && z < boxes[b + 5]
                ) return true
                b += 6
            }
            return false
        }

        companion object {
            val AIR = CellShape(Shapes.empty(), DoubleArray(0), 0.0, 0.5, 0.5, 0.5)
            private val FULL = CellShape(Shapes.block(), doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), 1.0, 0.5, 0.5, 0.5)

            fun of(shape: VoxelShape): CellShape {
                if (shape.isEmpty) return AIR
                if (shape === Shapes.block()) return FULL
                val list = shape.toAabbs()
                val boxes = DoubleArray(list.size * 6)
                var n = 0
                var volume = 0.0; var sx = 0.0; var sy = 0.0; var sz = 0.0
                for (box in list) {
                    // A fence or wall reaches into the cell above; that cell's own walk probes cover it.
                    val x0 = box.minX.coerceIn(0.0, 1.0); val x1 = box.maxX.coerceIn(0.0, 1.0)
                    val y0 = box.minY.coerceIn(0.0, 1.0); val y1 = box.maxY.coerceIn(0.0, 1.0)
                    val z0 = box.minZ.coerceIn(0.0, 1.0); val z1 = box.maxZ.coerceIn(0.0, 1.0)
                    val v = (x1 - x0) * (y1 - y0) * (z1 - z0)
                    if (v <= 0.0) continue
                    boxes[n] = x0; boxes[n + 1] = y0; boxes[n + 2] = z0
                    boxes[n + 3] = x1; boxes[n + 4] = y1; boxes[n + 5] = z1
                    n += 6
                    volume += v
                    sx += (x0 + x1) * 0.5 * v; sy += (y0 + y1) * 0.5 * v; sz += (z0 + z1) * 0.5 * v
                }
                if (volume <= 0.0) return CellShape(shape, DoubleArray(0), 0.0, 0.5, 0.5, 0.5)
                return CellShape(shape, boxes.copyOf(n), volume, sx / volume, sy / volume, sz / volume)
            }
        }
    }

    // The probes overlap a lot (27-cell neighbourhoods, 0.05-block march steps), so each cell is read once.
    private class HullReader(private val shapes: HullShapes) {
        private val seen = Long2ObjectOpenHashMap<CellShape>()

        fun cell(x: Int, y: Int, z: Int): CellShape {
            val key = BlockPos.asLong(x, y, z)
            seen.get(key)?.let { return it }
            val value = CellShape.of(shapes.shape(x, y, z))
            seen.put(key, value)
            return value
        }

        /** See [CellShape.crossedFace]; world-frame points. */
        fun crossedFace(air: Vector3d, solid: Vector3d, out: Vector3d): Boolean {
            val fx = Math.floor(solid.x); val fy = Math.floor(solid.y); val fz = Math.floor(solid.z)
            val c = cell(fx.toInt(), fy.toInt(), fz.toInt())
            return c.crossedFace(air.x - fx, air.y - fy, air.z - fz, solid.x - fx, solid.y - fy, solid.z - fz, out)
        }

        fun solidAt(x: Double, y: Double, z: Double): Boolean {
            val fx = Math.floor(x); val fy = Math.floor(y); val fz = Math.floor(z)
            val c = cell(fx.toInt(), fy.toInt(), fz.toInt())
            return c.volume > 0.0 && c.contains(x - fx, y - fy, z - fz)
        }

        fun snapshot(): HullSnapshot {
            val cells = LongArray(seen.size)
            val shapes = arrayOfNulls<VoxelShape>(seen.size)
            var i = 0
            for (entry in seen.long2ObjectEntrySet()) {
                cells[i] = entry.longKey
                shapes[i] = entry.value.shape
                i++
            }
            return HullSnapshot(cells, shapes.requireNoNulls())
        }
    }

    /** [avoid] is the route this limb grew from: its ancestors' points up to its branch point. */
    private class Tip(
        val id: Int, val root: Int, val avoid: List<Vector3d>, val pos: Vector3d, val normal: Vector3d, val heading: Vector3d,
        val startArc: Double, val originArc: Double,
        val parent: Int, val parentArc: Double,
    )

    /** A planned branch: it leaves its parent after [step] walk steps, to side [sideSign]. */
    private class Branch(val step: Int, val sideSign: Double, val childId: Int)

    /** The branch tree: each limb's branches in step order, every limb id in walk order, and each
     *  limb's parent id (0 for a root). */
    private class Plan(val branches: Int2ObjectOpenHashMap<List<Branch>>, val order: IntArray, val parentOf: Int2IntOpenHashMap) {
        fun isAncestor(ancestor: Int, id: Int): Boolean {
            var p = parentOf.get(id)
            var guard = 0
            while (p != 0 && guard++ <= MAX_GEN) {
                if (p == ancestor) return true
                p = parentOf.get(p)
            }
            return false
        }
    }

    /** The branch tree, from the seed alone: each limb id maps to its branches in step order. The
     *  [MAX_TENTACLES] budget is spent here, so a branch that the hull cuts off never frees a slot
     *  for a limb in another place. */
    private fun branchPlan(seed: Long): Plan {
        val plan = Int2ObjectOpenHashMap<List<Branch>>()
        val order = ArrayList<Int>()
        val parentOf = Int2IntOpenHashMap()
        val ids = ArrayDeque<Int>()
        val gens = ArrayDeque<Int>()
        val usedIds = HashSet<Int>()
        for (i in 1..TOTAL_ROOTS) { ids.add(i); gens.add(0); usedIds.add(i) }
        var spawned = TOTAL_ROOTS
        while (ids.isNotEmpty()) {
            val id = ids.removeFirst()
            val gen = gens.removeFirst()
            order.add(id)
            if (gen >= MAX_GEN) continue
            val rng = Random(seed xor (id.toLong() * LIMB_SEED_MIX) xor BRANCH_SEED_SALT)
            var branchNumber = 0
            var branches: ArrayList<Branch>? = null
            for (step in 1..STEPS_PER_LIMB) {
                if (step * STEP_PX <= BRANCH_MIN_ARC || rng.nextFloat() >= BRANCH_CHANCE) continue
                // Draw the side before the budget test, so the budget never shifts the sequence.
                val sideSign = if (rng.nextBoolean()) 1.0 else -1.0
                branchNumber++
                val childId = branchId(id, branchNumber)
                if (spawned >= MAX_TENTACLES || !usedIds.add(childId)) continue
                if (branches == null) branches = ArrayList()
                branches.add(Branch(step, sideSign, childId))
                parentOf.put(childId, id)
                ids.add(childId)
                gens.add(gen + 1)
                spawned++
            }
            if (branches != null) plan.put(id, branches)
        }
        return Plan(plan, order.toIntArray(), parentOf)
    }

    /** Each limb's route on a flat, endless face: the same wander and spread as [walk], with no hull.
     *  It comes from the seed alone, so limbs can keep apart by avoiding each other's ideal routes and
     *  a hull edit still moves only the limbs near it. */
    private fun idealRoutes(
        seed: Long, plan: Plan, rootStarts: List<Vector3d>, rootCourses: List<Vector3d>, origin: Vector3d, n0: Vector3d,
        sc: WalkScratch,
    ): Int2ObjectOpenHashMap<List<Vector3d>> {
        val routes = Int2ObjectOpenHashMap<List<Vector3d>>()
        val starts = Int2ObjectOpenHashMap<Vector3d>()
        val startCourses = Int2ObjectOpenHashMap<Vector3d>()
        val prefixes = Int2ObjectOpenHashMap<List<Vector3d>>()
        for (i in rootStarts.indices) { starts.put(i + 1, rootStarts[i]); startCourses.put(i + 1, rootCourses[i]) }
        for ((index, id) in plan.order.withIndex()) {
            val start = starts.get(id) ?: continue
            val rng = Random(seed xor (id.toLong() * LIMB_SEED_MIX))
            rng.nextDouble()
            val turns = DoubleArray(MAX_STEPS + 1) { (rng.nextDouble() - 0.5) * 2.0 * MAX_TURN }
            val avoid = sc.avoid
            avoid.clear()
            prefixes.get(id)?.let { avoid.addAll(it) }
            for (k in 0 until index) if (!plan.isAncestor(plan.order[k], id)) routes.get(plan.order[k])?.let { avoid.addAll(it) }
            val pos = Vector3d(start)
            val course = Vector3d(startCourses.get(id))
            val points = ArrayList<Vector3d>()
            points.add(Vector3d(pos))
            val branches = plan.branches.get(id)
            var nextBranch = 0
            var turnDone = 0.0
            for (step in 1..STEPS_PER_LIMB) {
                val turnNow = wanderTurn(turns, (step - 1) * STEP_PX)
                rotateAround(course, n0, turnNow - turnDone)
                turnDone = turnNow
                val dir = sc.dir.set(course).mul(W_PRIMARY).add(steer(pos, origin, avoid, sc))
                projectToPlane(dir, n0)
                if (dir.lengthSquared() < 1.0e-9) break
                course.set(dir.normalize())
                pos.fma(STEP_PX / 16.0, course)
                points.add(Vector3d(pos))
                if (branches != null && nextBranch < branches.size && branches[nextBranch].step == step) {
                    val branch = branches[nextBranch++]
                    val side = Vector3d(course).cross(n0).normalize()
                    starts.put(branch.childId, Vector3d(pos))
                    startCourses.put(branch.childId, Vector3d(course).add(side.mul(branch.sideSign)).normalize())
                    val prefix = ArrayList<Vector3d>(avoid.size + points.size)
                    prefixes.get(id)?.let { prefix.addAll(it) }
                    prefix.addAll(points)
                    prefixes.put(branch.childId, prefix)
                }
            }
            routes.put(id, points)
        }
        return routes
    }

    /** The spread away from the core plus the push away from [avoid] points, at [pos], into `sc.spread`. */
    private fun steer(pos: Vector3d, origin: Vector3d, avoid: AvoidGrid, sc: WalkScratch): Vector3d {
        val spread = sc.spread.set(pos).sub(origin)
        if (spread.lengthSquared() > 1.0e-6) spread.normalize().mul(W_SPREAD) else spread.set(0.0)
        val push = avoid.push(pos, sc.push.set(0.0), sc.found)
        if (push.lengthSquared() > 1.0e-9) push.normalize().mul(W_AVOID) else push.set(0.0)
        return spread.add(push)
    }

    /** Per-walk scratch. A walk runs on one thread, so the scratch is never shared. */
    private class WalkScratch {
        val avoid = AvoidGrid()
        val laid = LaidGrid()
        val found = IntArrayList()
        val spread = Vector3d()
        val push = Vector3d()
        val dir = Vector3d()
        val next = Vector3d()
        val newPos = Vector3d()
        val moved = Vector3d()
        val tmp = Vector3d()
        val tmp2 = Vector3d()
        val side = Vector3d()
        val axis = Vector3d()
        val probeW = Vector3d()
        val probeDir = Vector3d()
        val air = Vector3d()
        val solid = Vector3d()
        val face = Vector3d()
        val hitPos = Vector3d()
        val hitNormal = Vector3d()
    }

    /** [push] sums in list order, so its result is bit-identical to a plain scan of the list. */
    private class AvoidGrid {
        private val xs = DoubleArrayList()
        private val ys = DoubleArrayList()
        private val zs = DoubleArrayList()
        private val cells = Long2ObjectOpenHashMap<IntArrayList>()

        val size: Int get() = xs.size

        fun clear() {
            xs.clear(); ys.clear(); zs.clear()
            for (list in cells.values) list.clear()
        }

        fun addAll(points: List<Vector3d>) {
            for (p in points) {
                val i = xs.size
                xs.add(p.x); ys.add(p.y); zs.add(p.z)
                val key = gridKey(p.x, p.y, p.z, AVOID_CELL)
                val list = cells.get(key) ?: IntArrayList().also { cells.put(key, it) }
                list.add(i)
            }
        }

        fun push(pos: Vector3d, out: Vector3d, found: IntArrayList): Vector3d {
            found.clear()
            val cx = gridCell(pos.x, AVOID_CELL); val cy = gridCell(pos.y, AVOID_CELL); val cz = gridCell(pos.z, AVOID_CELL)
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val list = cells.get(packCell(cx + dx, cy + dy, cz + dz)) ?: continue
                for (j in 0 until list.size) {
                    val i = list.getInt(j)
                    val dxp = pos.x - xs.getDouble(i); val dyp = pos.y - ys.getDouble(i); val dzp = pos.z - zs.getDouble(i)
                    val d2 = dxp * dxp + dyp * dyp + dzp * dzp
                    if (d2 < AVOID_RADIUS_SQ && d2 > 1.0e-9) found.add(i)
                }
            }
            IntArrays.quickSort(found.elements(), 0, found.size)
            for (j in 0 until found.size) {
                val i = found.getInt(j)
                val dxp = pos.x - xs.getDouble(i); val dyp = pos.y - ys.getDouble(i); val dzp = pos.z - zs.getDouble(i)
                val inv = 1.0 / (dxp * dxp + dyp * dyp + dzp * dzp)
                out.add(dxp * inv, dyp * inv, dzp * inv)
            }
            return out
        }
    }

    private class LaidGrid {
        private class Bucket {
            val points = ArrayList<Vector3d>()
            val roots = IntArrayList()
        }
        private val cells = Long2ObjectOpenHashMap<Bucket>()

        fun add(p: Vector3d, root: Int) {
            val key = gridKey(p.x, p.y, p.z, LAID_CELL)
            val bucket = cells.get(key) ?: Bucket().also { cells.put(key, it) }
            bucket.points.add(p)
            bucket.roots.add(root)
        }

        /** True when [p] is within [SELF_STOP] of a laid point of a root other than [root]. */
        fun touchesOther(p: Vector3d, root: Int): Boolean {
            val cx = gridCell(p.x, LAID_CELL); val cy = gridCell(p.y, LAID_CELL); val cz = gridCell(p.z, LAID_CELL)
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val bucket = cells.get(packCell(cx + dx, cy + dy, cz + dz)) ?: continue
                for (j in 0 until bucket.points.size) {
                    if (bucket.roots.getInt(j) != root && p.distanceSquared(bucket.points[j]) < SELF_STOP * SELF_STOP) return true
                }
            }
            return false
        }
    }

    private fun gridCell(v: Double, cell: Double): Int = Math.floor(v / cell).toInt()

    private fun gridKey(x: Double, y: Double, z: Double, cell: Double): Long =
        packCell(gridCell(x, cell), gridCell(y, cell), gridCell(z, cell))

    private fun packCell(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and CELL_MASK) shl 42) or ((y.toLong() and CELL_MASK) shl 21) or (z.toLong() and CELL_MASK)

    /** The seeded tendril walk. The branch tree comes from the seed alone ([branchPlan]), each limb has
     *  its own random sequence from its lineage id, and a limb avoids only the route it grew from. So a
     *  hull change re-routes only the limbs that pass near it and their branches. */
    fun walk(level: Level, blockPos: BlockPos, seed: Long, facing: Direction): Walk {
        val cursor = BlockPos.MutableBlockPos()
        val dynamic = LongArrayList()
        val walk = walk(HullShapes { x, y, z -> hullShape(level, cursor.set(x, y, z), dynamic) }, blockPos, seed, facing)
        walk.hull.dynamic = dynamic.toLongArray()
        return walk
    }

    fun walk(shapes: HullShapes, blockPos: BlockPos, seed: Long, facing: Direction): Walk {
        val hull = HullReader(shapes)
        val sc = WalkScratch()
        val rootRng = Random(seed)
        val n0 = Vector3d(facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble())
        val origin = Vector3d(0.5, 0.5, 0.5).sub(Vector3d(n0).mul(0.5))
        val frame = frameOf(facing)
        val t1 = frame.t1
        val t2 = frame.t2
        val base = Vector3d(blockPos.x.toDouble(), blockPos.y.toDouble(), blockPos.z.toDouble())

        val queue = ArrayDeque<Tip>()
        val rootStarts = ArrayList<Vector3d>()
        val rootCourses = ArrayList<Vector3d>()
        for (i in 0 until TOTAL_ROOTS) {
            val ang = (i + rootRng.nextDouble() * 0.6) * (Math.PI * 2.0 / TOTAL_ROOTS)
            val heading = Vector3d(t1).mul(Math.cos(ang)).add(Vector3d(t2).mul(Math.sin(ang))).normalize()
            val batch = i / CONCURRENT_ROOTS
            val start = Vector3d(origin).add(Vector3d(heading).mul(2.5 / 16.0)).add(Vector3d(n0).mul(0.01))
            rootStarts.add(start)
            rootCourses.add(heading)
            queue.add(Tip(i + 1, i, emptyList(), Vector3d(start), Vector3d(n0), heading, batch * TENTACLE_LEN_PX, 0.0, -1, 0.0))
        }

        val plan = branchPlan(seed)
        // Real points of the limbs laid so far, by root; a limb stops where it would touch another root's limb.
        val laid = sc.laid
        val ideal = idealRoutes(seed, plan, rootStarts, rootCourses, origin, n0, sc)
        val orderIndex = Int2IntOpenHashMap()
        for ((k, id) in plan.order.withIndex()) orderIndex.put(id, k)
        val paths = ArrayList<BPath>()
        while (queue.isNotEmpty() && paths.size < MAX_TENTACLES) {
            val tip = queue.removeFirst()
            val rng = Random(seed xor (tip.id.toLong() * LIMB_SEED_MIX))
            val phase = rng.nextDouble() * Math.PI * 2.0
            val branches = plan.branches.get(tip.id)
            // The real route this limb grew from, and the ideal routes of the limbs before it in other lineages.
            val avoidPoints = sc.avoid
            avoidPoints.clear()
            avoidPoints.addAll(tip.avoid)
            val myIndex = orderIndex.get(tip.id)
            for (k in 0 until myIndex) {
                val other = plan.order[k]
                if (!plan.isAncestor(other, tip.id)) ideal.get(other)?.let { avoidPoints.addAll(it) }
            }
            var nextBranch = 0
            var step = 0
            val pos = Vector3d(tip.pos)
            val normal = Vector3d(tip.normal)
            // The limb's course, on the barnacle's own face. On any other face the heading is the course
            // turned by the one rotation from that face to this one, so a detour never bends the course.
            val course = Vector3d(tip.heading)
            val heading = Vector3d()
            val points = ArrayList<BWaypoint>()
            val faceTurn = Quaterniond()
            val turns = DoubleArray(MAX_STEPS + 1) { (rng.nextDouble() - 0.5) * 2.0 * MAX_TURN }
            var planar = 0.0
            var turnDone = 0.0
            var arc = 0.0
            points.add(BWaypoint(Vector3d(pos), Vector3d(normal), arc))
            var guard = 0

            while (arc < TENTACLE_LEN_PX && guard++ < MAX_STEPS) {
                // The wander follows progress across the barnacle's own face, so a climb over a block does not
                // use up its turns.
                val turnNow = wanderTurn(turns, planar)
                rotateAround(course, n0, turnNow - turnDone)
                turnDone = turnNow
                val dir = sc.dir.set(course).mul(W_PRIMARY).add(steer(pos, origin, avoidPoints, sc))
                projectToPlane(dir, n0)
                if (dir.lengthSquared() < 1.0e-9) break
                course.set(dir.normalize())
                faceHeading(course, n0, normal, faceTurn, heading, sc.axis)
                if (heading.lengthSquared() < 1.0e-9) break

                val next = sc.next.set(pos).add(sc.tmp.set(heading).mul(STEP_PX / 16.0))
                if (!surfaceProbe(hull, base, next, normal, heading, sc)) break
                val subNormal = sc.hitNormal
                val newPos = sc.newPos.set(sc.hitPos).fma(SURFACE_OFFSET, subNormal)
                // Over a convex edge the chord between the two faces cuts the block's corner; put a point on the edge.
                if (normal.dot(subNormal) < CORNER_DOT && sc.tmp.set(newPos).sub(pos).dot(normal) < 0.0) {
                    val corner = Vector3d(pos).fma(sc.tmp.set(newPos).sub(pos).dot(subNormal), subNormal)
                    if (!hull.solidAt(base.x + corner.x, base.y + corner.y, base.z + corner.z)) {
                        points.add(BWaypoint(corner, Vector3d(normal).add(subNormal).normalize(), arc + STEP_PX * 0.5))
                    }
                }
                keepClear(hull, base, newPos, subNormal, heading, sc.side)
                // Out of room: a limb stops where it would touch its own route or the route it grew from, so it
                // never coils round a small block.
                if (step >= SELF_GAP_STEPS && (touchesLineage(newPos, points, tip.avoid) || laid.touchesOther(newPos, tip.root))) break
                normal.set(subNormal)
                val moved = sc.moved.set(newPos).sub(pos)
                val across = sc.tmp.set(moved).sub(sc.tmp2.set(n0).mul(moved.dot(n0))).length()
                // Climbing counts a little, so a limb that hangs down a hull wall still wanders.
                planar += (across + CLIMB_WANDER * (moved.length() - across)) * 16.0
                pos.add(moved)
                faceHeading(course, n0, normal, faceTurn, heading, sc.axis)
                arc += STEP_PX
                step++
                points.add(BWaypoint(Vector3d(pos), Vector3d(normal), arc))

                // A planned branch is lost when the hull ends this limb before its step.
                if (branches != null && nextBranch < branches.size && branches[nextBranch].step == step) {
                    val branch = branches[nextBranch++]
                    val side = Vector3d(course).cross(n0).normalize()
                    val childHeading = Vector3d(course).add(side.mul(branch.sideSign)).normalize()
                    val childAvoid = ArrayList<Vector3d>(tip.avoid.size + points.size)
                    childAvoid.addAll(tip.avoid)
                    for (wp in points) childAvoid.add(wp.pos)
                    // This tip's path is added next, so its index is the current size.
                    queue.add(
                        Tip(
                            branch.childId, tip.root, childAvoid, Vector3d(pos), Vector3d(normal), childHeading,
                            tip.startArc + arc, tip.originArc + arc, paths.size, arc,
                        ),
                    )
                }
            }
            if (points.size > 1) {
                paths.add(BPath(tip.id, points, phase, tip.startArc, tip.originArc, tip.parent, tip.parentArc))
                for (wp in points) laid.add(wp.pos, tip.root)
            }
        }
        return Walk(paths, hull.snapshot())
    }

    /** The heading on the face with normal [n] for [course] on the barnacle's face [n0], into [out]: the
     *  course turned by the rotation from [n0] to [n]. Under the barnacle's face it turns back over the
     *  course's own edge. Zero length when the course runs straight into the face. */
    private fun faceHeading(course: Vector3d, n0: Vector3d, n: Vector3d, rot: Quaterniond, out: Vector3d, axis: Vector3d) {
        if (n.dot(n0) < OPPOSITE_DOT) {
            rot.fromAxisAngleRad(axis.set(n0).cross(course).normalize(), Math.PI)
        } else {
            rot.rotationTo(n0, n)
        }
        rot.transform(out.set(course))
        projectToPlane(out, n)
        if (out.lengthSquared() > 1.0e-12) out.normalize() else out.set(0.0)
    }

    /** True when [p] is within [SELF_STOP] of [ancestors] or of [own], except [own]'s latest points. */
    private fun touchesLineage(p: Vector3d, own: List<BWaypoint>, ancestors: List<Vector3d>): Boolean {
        for (q in ancestors) if (p.distanceSquared(q) < SELF_STOP * SELF_STOP) return true
        for (i in 0 until own.size - SELF_GAP_STEPS) if (p.distanceSquared(own[i].pos) < SELF_STOP * SELF_STOP) return true
        return false
    }

    /** Nudges [p] (barnacle-local, on the face with normal [n]) away from a block beside it, so the
     *  drawn tube does not sink into a wall that the limb runs along. */
    private fun keepClear(hull: HullReader, base: Vector3d, p: Vector3d, n: Vector3d, heading: Vector3d, sideOut: Vector3d) {
        val side = sideOut.set(heading).cross(n)
        if (side.lengthSquared() < 1.0e-9) return
        side.normalize()
        repeat(SIDE_CLEAR_TRIES) { _ ->
            val left = sideSolid(hull, base, p, n, side, 1.0)
            val right = sideSolid(hull, base, p, n, side, -1.0)
            if (left == right) return
            p.fma(if (left) -SIDE_CLEAR_STEP else SIDE_CLEAR_STEP, side)
        }
    }

    private fun sideSolid(hull: HullReader, base: Vector3d, p: Vector3d, n: Vector3d, side: Vector3d, sign: Double): Boolean {
        val x = base.x + p.x + side.x * sign * SIDE_CLEAR + n.x * SIDE_CLEAR
        val y = base.y + p.y + side.y * sign * SIDE_CLEAR + n.y * SIDE_CLEAR
        val z = base.z + p.z + side.z * sign * SIDE_CLEAR + n.z * SIDE_CLEAR
        return hull.solidAt(x, y, z)
    }

    /** The summed wander turn (radians) after [planarPx] of progress across the barnacle's face. */
    private fun wanderTurn(turns: DoubleArray, planarPx: Double): Double {
        val x = (planarPx / STEP_PX).coerceIn(0.0, (turns.size - 1).toDouble())
        val k = Math.floor(x).toInt()
        var sum = 0.0
        for (i in 0 until k) sum += turns[i]
        return if (k < turns.size) sum + turns[k] * (x - k) else sum
    }

    private fun branchId(parentId: Int, branchNumber: Int): Int {
        val h = it.unimi.dsi.fastutil.HashCommon.mix(parentId * 31 + branchNumber)
        return if (h == 0) 1 shl 30 else h
    }

    /** True when [shorter]'s route is the start of [longer]'s route (same waypoints). */
    fun isPrefix(shorter: BPath, longer: BPath): Boolean {
        if (shorter.points.size > longer.points.size) return false
        for (i in shorter.points.indices) if (shorter.points[i].pos != longer.points[i].pos) return false
        return true
    }

    /** Calls [action] with (centre x, y, z, half-size) for a chain of small boxes that hug the hull along
     *  each limb's revealed arc ([reveals], path-local px), in world or shipyard space. */
    private inline fun forEachLimbBox(
        blockPos: BlockPos, paths: List<BPath>, reveals: DoubleArray, action: (Double, Double, Double, Double) -> Unit,
    ) {
        val bx = blockPos.x.toDouble(); val by = blockPos.y.toDouble(); val bz = blockPos.z.toDouble()
        val cursor = PathCursor(keepTinyNormal = true)
        for (i in paths.indices) {
            val path = paths[i]
            val reveal = Math.min(reveals[i], path.totalPx)
            if (reveal <= 1.0) continue
            cursor.reset(path.points)
            var arc = 0.0
            while (arc < reveal - 1.0e-4) {
                cursor.at(arc)
                val p = cursor.pos
                val nrm = cursor.normal
                val r = colliderRadiusAt(path.originArc + arc) / 16.0
                // Box centre sits a radius above the surface, like the render box.
                action(bx + p.x + nrm.x * r, by + p.y + nrm.y * r, bz + p.z + nrm.z * r, r)
                arc += COLLIDE_STEP_PX
            }
        }
    }

    /** The bounds of all limb boxes into [out] (minX, minY, minZ, maxX, maxY, maxZ). False when there is no box. */
    fun limbBoxBounds(blockPos: BlockPos, paths: List<BPath>, reveals: DoubleArray, out: DoubleArray): Boolean {
        require(out.size >= 6) { "bounds array too small" }
        var any = false
        out[0] = Double.MAX_VALUE; out[1] = Double.MAX_VALUE; out[2] = Double.MAX_VALUE
        out[3] = -Double.MAX_VALUE; out[4] = -Double.MAX_VALUE; out[5] = -Double.MAX_VALUE
        forEachLimbBox(blockPos, paths, reveals) { cx, cy, cz, r ->
            any = true
            if (cx - r < out[0]) out[0] = cx - r; if (cx + r > out[3]) out[3] = cx + r
            if (cy - r < out[1]) out[1] = cy - r; if (cy + r > out[4]) out[4] = cy + r
            if (cz - r < out[2]) out[2] = cz - r; if (cz + r > out[5]) out[5] = cz + r
        }
        return any
    }

    /** True when a limb box, grown by [reach], overlaps the box ([minX]..[maxZ]); the same test as
     *  `box.inflate(reach).intersects(other)`. */
    @Suppress("LongParameterList")
    fun limbBoxesHit(
        blockPos: BlockPos, paths: List<BPath>, reveals: DoubleArray, reach: Double,
        minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double,
    ): Boolean {
        forEachLimbBox(blockPos, paths, reveals) { cx, cy, cz, r ->
            if ((cx - r) - reach < maxX && (cx + r) + reach > minX &&
                (cy - r) - reach < maxY && (cy + r) + reach > minY &&
                (cz - r) - reach < maxZ && (cz + r) + reach > minZ
            ) return true
        }
        return false
    }

    /** Position on [path] at [arcPx], in world or shipyard space. */
    fun limbPoint(blockPos: BlockPos, path: BPath, arcPx: Double): Vec3 {
        val p = Vector3d()
        sampleAt(path.points, arcPx, p, Vector3d())
        return Vec3(blockPos.x + p.x, blockPos.y + p.y, blockPos.z + p.z)
    }

    /** The drawn tube's segment ends along [pts] (from 0 to its full length): the tube breaks only at block
     *  edges, where a straight box would cut across the corner, and each stretch between edges is split
     *  into equal segments of at most [segmentPx]. */
    fun segmentBreaks(pts: List<BWaypoint>, segmentPx: Double): DoubleArray {
        val total = pts.lastOrNull()?.arcPx ?: 0.0
        val out = ArrayList<Double>()
        var arc = 0.0
        out.add(arc)
        var guard = 0
        while (arc < total - 1.0e-4 && guard++ < MAX_BREAKS) {
            var end = total
            for (i in 1 until pts.size - 1) {
                val a = pts[i].arcPx
                if (a <= arc + MIN_SEGMENT_PX) continue
                if (a >= total) break
                if (isEdge(pts, i)) { end = a; break }
            }
            val pieces = Math.max(1.0, Math.ceil((end - arc) / segmentPx - 1.0e-6))
            arc += (end - arc) / pieces
            out.add(arc)
        }
        return out.toDoubleArray()
    }

    /** A waypoint on a block edge: its normal turns away from both neighbours' normals. */
    private fun isEdge(pts: List<BWaypoint>, i: Int): Boolean {
        val n = pts[i].normal
        return n.dot(pts[i - 1].normal) < EDGE_COS && n.dot(pts[i + 1].normal) < EDGE_COS
    }

    /** (pos, normal) on the path at arc length [arc]. */
    fun sampleAt(pts: List<BWaypoint>, arc: Double): Pair<Vector3d, Vector3d> {
        val pos = Vector3d()
        val nrm = Vector3d()
        sampleAt(pts, arc, pos, nrm)
        return Pair(pos, nrm)
    }

    /** [sampleAt] into [outPos] and [outNrm]. */
    fun sampleAt(pts: List<BWaypoint>, arc: Double, outPos: Vector3d, outNrm: Vector3d) {
        PathCursor.sample(pts, arc, if (arc <= 0.0) 0 else firstAtOrPast(pts, arc, 1), outPos, outNrm, true)
    }

    private fun firstAtOrPast(pts: List<BWaypoint>, arc: Double, from: Int): Int {
        var i = from
        while (i < pts.size && pts[i].arcPx < arc) i++
        return i
    }

    /** [sampleAt] with a forward-only search, exact because waypoint arcs never fall. With [keepTinyNormal]
     *  false, a near-zero blended normal becomes the next waypoint's normal. One thread only. */
    class PathCursor(private val keepTinyNormal: Boolean) {
        val pos = Vector3d()
        val normal = Vector3d()
        private var points: List<BWaypoint> = emptyList()
        private var next = 1
        private var lastArc = Double.NEGATIVE_INFINITY

        fun reset(pts: List<BWaypoint>): PathCursor {
            points = pts
            next = 1
            lastArc = Double.NEGATIVE_INFINITY
            return this
        }

        fun at(arc: Double): PathCursor = sampleInto(arc, pos, normal)

        fun sampleInto(arc: Double, outPos: Vector3d, outNrm: Vector3d): PathCursor {
            if (arc < lastArc) next = 1
            lastArc = arc
            if (arc > 0.0) next = firstAtOrPast(points, arc, next)
            sample(points, arc, if (arc <= 0.0) 0 else next, outPos, outNrm, keepTinyNormal)
            return this
        }

        companion object {
            /** [i] is 0 for an arc at or before the start, else the first waypoint index at or past the arc. */
            internal fun sample(
                pts: List<BWaypoint>, arc: Double, i: Int, outPos: Vector3d, outNrm: Vector3d, keepTinyNormal: Boolean,
            ) {
                if (i == 0) {
                    outPos.set(pts[0].pos); outNrm.set(pts[0].normal)
                    return
                }
                if (i >= pts.size) {
                    val l = pts.size - 1
                    outPos.set(pts[l].pos); outNrm.set(pts[l].normal)
                    return
                }
                val a = pts[i - 1]
                val b = pts[i]
                val f = ((arc - a.arcPx) / (b.arcPx - a.arcPx)).coerceIn(0.0, 1.0)
                a.pos.lerp(b.pos, f, outPos)
                a.normal.lerp(b.normal, f, outNrm)
                if (outNrm.lengthSquared() > 1.0e-9) outNrm.normalize() else if (!keepTinyNormal) outNrm.set(b.normal)
            }
        }
    }

    /** Collider half-width (px), tapering root→tip like the render tube. */
    private fun colliderRadiusAt(arcPx: Double): Double {
        val t = (arcPx / TENTACLE_LEN_PX).coerceIn(0.0, 1.0)
        return Math.max(0.6, 2.4 * (1.0 - t * t * 0.8))
    }

    /** The surface near the step target [worldLocal] (barnacle-local), into `sc.hitPos` (surface point) and
     *  `sc.hitNormal` (face normal); false when there is none. It tries, in order: the face under the step
     *  (flat ground), the face ahead (a wall to climb), the face down and back (an edge to wrap over), then
     *  the face away from the nearby blocks' centre. A surface counts only near the step, so a limb never
     *  jumps across a block. */
    private fun surfaceProbe(
        hull: HullReader, base: Vector3d, worldLocal: Vector3d, normal: Vector3d, heading: Vector3d, sc: WalkScratch,
    ): Boolean {
        val w = sc.probeW.set(base).add(worldLocal)
        val dir = sc.probeDir.set(normal).negate()
        if (marchProbe(hull, base, w, dir, FLAT_REACH, sc)) return true
        if (marchProbe(hull, base, w, dir.set(heading), PROBE_REACH, sc)) return true
        if (marchProbe(hull, base, w, dir.set(normal).add(heading).negate().normalize(), PROBE_REACH, sc)) return true
        // Only cells that touch the probe's reach, so a block diagonally past a limb leaves it alone.
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var weight = 0.0
        val fx = Math.floor(w.x).toInt(); val fy = Math.floor(w.y).toInt(); val fz = Math.floor(w.z).toInt()
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            val ex = Math.max(0.0, Math.max(fx + dx - w.x, w.x - (fx + dx + 1)))
            val ey = Math.max(0.0, Math.max(fy + dy - w.y, w.y - (fy + dy + 1)))
            val ez = Math.max(0.0, Math.max(fz + dz - w.z, w.z - (fz + dz + 1)))
            if (ex * ex + ey * ey + ez * ez > PROBE_REACH * PROBE_REACH) continue
            val c = hull.cell(fx + dx, fy + dy, fz + dz)
            if (c.volume <= 0.0) continue
            sx += (fx + dx + c.cx) * c.volume
            sy += (fy + dy + c.cy) * c.volume
            sz += (fz + dz + c.cz) * c.volume
            weight += c.volume
        }
        if (weight <= 0.0) return false
        dir.set(sx / weight, sy / weight, sz / weight).sub(w)
        if (dir.lengthSquared() < 1.0e-6) return false
        return marchProbe(hull, base, w, dir.normalize(), PROBE_REACH, sc)
    }

    /** Marches from behind [w] along [dir] to the first face, up to [reach] past [w]. On a hit, writes the
     *  point on the air side (barnacle-local) to `sc.hitPos` and that face's normal to `sc.hitNormal`. False
     *  when there is none, the face does not look back along [dir], or the point is more than [MAX_SNAP] from [w]. */
    private fun marchProbe(
        hull: HullReader, base: Vector3d, w: Vector3d, dir: Vector3d, reach: Double, sc: WalkScratch,
    ): Boolean {
        var d = -MARCH_START
        // A target deep in a block: back out until the march starts in air.
        while (hull.solidAt(w.x + dir.x * d, w.y + dir.y * d, w.z + dir.z * d)) {
            d -= MARCH_STEP
            if (d < -MARCH_BACKOUT) return false
        }
        var lastAirD = d
        var hit = false
        while (d <= reach) {
            if (hull.solidAt(w.x + dir.x * d, w.y + dir.y * d, w.z + dir.z * d)) { hit = true; break }
            lastAirD = d
            d += MARCH_STEP
        }
        if (!hit) return false
        var lo = lastAirD
        var hi = d
        repeat(MARCH_REFINE_STEPS) {
            val mid = (lo + hi) * 0.5
            if (hull.solidAt(w.x + dir.x * mid, w.y + dir.y * mid, w.z + dir.z * mid)) hi = mid else lo = mid
        }
        val air = sc.air.set(w).fma(lo, dir)
        if (air.distanceSquared(w) > MAX_SNAP * MAX_SNAP) return false
        val face = sc.face
        if (!hull.crossedFace(air, sc.solid.set(w).fma(hi, dir), face) || face.dot(dir) > -FACE_MIN_DOT) return false
        sc.hitPos.set(air).sub(base)
        sc.hitNormal.set(face)
        return true
    }

    /** True when the point ([x], [y], [z]), in the level's own frame (shipyard on a ship), is inside a
     *  block's collision shape. Barnacles do not count, as in the walk. */
    fun hullSolidAt(level: Level, x: Double, y: Double, z: Double, cursor: BlockPos.MutableBlockPos): Boolean {
        val fx = Math.floor(x); val fy = Math.floor(y); val fz = Math.floor(z)
        val shape = hullShape(level, cursor.set(fx.toInt(), fy.toInt(), fz.toInt()))
        if (shape.isEmpty) return false
        if (shape === Shapes.block()) return true
        val lx = x - fx; val ly = y - fy; val lz = z - fz
        for (box in shape.toAabbs()) {
            if (lx >= box.minX && lx < box.maxX && ly >= box.minY && ly < box.maxY && lz >= box.minZ && lz < box.maxZ) return true
        }
        return false
    }

    // `level.getBlockState` loads a chunk that is not loaded, on the server thread.
    private fun hullShape(level: Level, pos: BlockPos, dynamicOut: LongArrayList? = null): VoxelShape {
        val chunk = level.getChunk(pos.x shr 4, pos.z shr 4, ChunkStatus.FULL, false) ?: return Shapes.empty()
        val state = chunk.getBlockState(pos)
        // A barnacle's bulb grows with age, so as hull it would re-route the whole colony as it grows.
        if (state.block is YgannBarnacleBlock) return Shapes.empty()
        // Only a dynamic-shape state skips the per-state shape cache, so only it can change with no block change.
        if (dynamicOut != null && state.block.hasDynamicShape()) dynamicOut.add(pos.asLong())
        return state.getCollisionShape(level, pos)
    }

    /** A barnacle face's fixed axes: normal [n], tangents [t1] and [t2], and [planeOrigin], the face
     *  centre in block-local space. */
    class FaceFrame(val n: Vector3dc, val t1: Vector3dc, val t2: Vector3dc, val planeOrigin: Vector3dc)

    private val FRAMES: Array<FaceFrame> = Array(Direction.entries.size) { i ->
        val facing = Direction.entries[i]
        val n = Vector3d(facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble())
        val ref = if (Math.abs(n.y) > 0.5) Vector3d(1.0, 0.0, 0.0) else Vector3d(0.0, 1.0, 0.0)
        val t1 = Vector3d(ref).cross(n).normalize()
        val t2 = Vector3d(n).cross(t1).normalize()
        FaceFrame(n, t1, t2, Vector3d(0.5, 0.5, 0.5).sub(Vector3d(n).mul(0.5)))
    }

    fun frameOf(facing: Direction): FaceFrame = FRAMES[facing.ordinal]

    /** Rotates [v] by [angle] round the unit [axis] (Rodrigues). Same arithmetic as the JOML calls it replaces. */
    fun rotateAround(v: Vector3d, axis: Vector3dc, angle: Double) {
        val cos = Math.cos(angle)
        val sin = Math.sin(angle)
        val cx = JomlMath.fma(axis.y(), v.z, -axis.z() * v.y)
        val cy = JomlMath.fma(axis.z(), v.x, -axis.x() * v.z)
        val cz = JomlMath.fma(axis.x(), v.y, -axis.y() * v.x)
        val k = axis.dot(v) * (1.0 - cos)
        v.set(v.x * cos + cx * sin + axis.x() * k, v.y * cos + cy * sin + axis.y() * k, v.z * cos + cz * sin + axis.z() * k)
    }

    fun projectToPlane(v: Vector3d, normal: Vector3dc) {
        val d = v.dot(normal)
        v.sub(normal.x() * d, normal.y() * d, normal.z() * d)
    }

    const val TENTACLE_LEN_PX = 130.0
    private const val STEP_PX = 3.0
    private const val SURFACE_OFFSET = 0.03
    private const val MARCH_START = 0.3
    private const val MARCH_STEP = 0.05
    private const val MARCH_BACKOUT = 1.5
    private const val MARCH_REFINE_STEPS = 5
    private const val PROBE_REACH = 1.0
    /** Probe reach (blocks) under a step, the farthest a surface may be from the step, and how squarely the
     *  march must meet a face. */
    private const val FLAT_REACH = 0.25
    private const val MAX_SNAP = 0.35
    private const val FACE_MIN_DOT = 0.1
    /** Side clearance (blocks): the thickest tube's half-width, the nudge per try, and the most tries. */
    private const val SIDE_CLEAR = 2.6 / 16.0
    private const val SIDE_CLEAR_STEP = 0.5 / 16.0
    private const val SIDE_CLEAR_TRIES = 6
    /** A limb stops within [SELF_STOP] (blocks) of its own route or its ancestors'; its latest
     *  [SELF_GAP_STEPS] points, and its own first steps, do not count. */
    private const val SELF_STOP = 0.2
    private const val SELF_GAP_STEPS = 6
    /** Consecutive faces that meet at less than this (cosine) count as an edge. */
    private const val CORNER_DOT = 0.9
    private const val FACE_EPS = 1.0e-7
    /** A waypoint whose normal turns by more than about 25° from both neighbours is on an edge; no drawn
     *  segment is shorter than [MIN_SEGMENT_PX] (px). */
    private const val EDGE_COS = 0.9
    private const val MIN_SEGMENT_PX = 0.5
    private const val MAX_BREAKS = 256
    /** The longest straight segment of the drawn tube (px). */
    const val DRAW_SEGMENT_PX = 10.0
    private const val W_PRIMARY = 1.0
    /** The largest wander turn per step (radians). */
    private const val MAX_TURN = 0.17
    /** Faces at least this opposite to the barnacle's own face count as its underside. */
    private const val OPPOSITE_DOT = -0.999
    /** How much of a step off the barnacle's face plane counts toward the wander. */
    private const val CLIMB_WANDER = 0.3
    private const val W_SPREAD = 0.25
    private const val TOTAL_ROOTS = 8
    private const val CONCURRENT_ROOTS = 3
    private const val W_AVOID = 0.5
    private const val AVOID_RADIUS_SQ = 0.35 * 0.35
    private const val BRANCH_MIN_ARC = 12.0
    private const val BRANCH_CHANCE = 0.06f
    private const val MAX_GEN = 2
    const val MAX_TENTACLES = 22
    private const val LIMB_SEED_MIX = -7046029254386353131L
    private const val BRANCH_SEED_SALT = 0x5DEECE66DL
    private const val MAX_STEPS = 80
    private val STEPS_PER_LIMB = Math.ceil(TENTACLE_LEN_PX / STEP_PX).toInt()
    /** Collider box spacing along the arc (px). */
    private const val COLLIDE_STEP_PX = 6.0
    /** Hash cell sizes (blocks), each larger than the radius it serves, so a 27-cell query finds every point. */
    private const val AVOID_CELL = 0.4
    private const val LAID_CELL = 0.25
    private const val CELL_MASK = 0x1FFFFFL
}
