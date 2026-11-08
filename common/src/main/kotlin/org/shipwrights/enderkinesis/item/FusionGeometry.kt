package org.shipwrights.enderkinesis.item

import it.unimi.dsi.fastutil.doubles.DoubleArrayList
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import org.joml.Matrix3d
import org.joml.Matrix4d
import org.joml.Quaterniond
import org.joml.Vector3d
import org.joml.Vector3dc
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.BlockStateInfo
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import org.valkyrienskies.core.api.util.GameTickOnly

/** Ship geometry for the Staff of Fusion. All positions are block centers in a ship's shipyard frame
 *  unless a name says world. */
@OptIn(GameTickOnly::class)
internal object FusionGeometry {

    /** Two touching unit cubes are this close; the margin absorbs physics penetration slop. */
    private const val CONTACT_MARGIN: Double = 0.05
    const val HALF: Double = 0.5 + CONTACT_MARGIN
    private val DIRECTIONS: Array<Direction> = Direction.values()

    /** The 24 rotations that map the block grid onto itself. */
    private val GRID_ROTATIONS: List<Quaterniond> = buildList {
        for (x in Direction.values()) for (y in Direction.values()) {
            if (x.axis == y.axis) continue
            val xv = Vector3d(x.stepX.toDouble(), x.stepY.toDouble(), x.stepZ.toDouble())
            val yv = Vector3d(y.stepX.toDouble(), y.stepY.toDouble(), y.stepZ.toDouble())
            val zv = Vector3d(xv).cross(yv)
            add(Quaterniond().setFromNormalized(Matrix3d(xv, yv, zv)))
        }
    }

    private val UPRIGHT_ROTATIONS: List<Quaterniond> = GRID_ROTATIONS.filter {
        it.transform(Vector3d(0.0, 1.0, 0.0)).y > 0.5
    }

    /** The non-air blocks of a ship near the bubble, their VS2 masses, and their cell bounds. */
    class ShipBlocks(
        val packed: LongArray, val masses: DoubleArray,
        val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int,
    ) {
        val set: LongOpenHashSet = LongOpenHashSet(packed)

        /** Blocks with a face on air or on an unscanned cell. Only these can touch another ship. */
        val surface: LongArray by lazy(LazyThreadSafetyMode.NONE) {
            val out = LongArrayList()
            for (block in packed) {
                if (DIRECTIONS.any { !set.contains(BlockPos.offset(block, it)) }) out.add(block)
            }
            out.toLongArray()
        }
    }

    /** A ship with blocks inside the bubble: their indices in [blocks], their share of the ship's
     *  mass, and their centroid. */
    class Lifted(
        val ship: LoadedServerShip, val blocks: ShipBlocks, val inside: IntArray, val share: Double,
        val centroid: Vector3d,
    )

    class Contact(val a: BlockPos, val b: BlockPos, val snap: Double)

    /** [relative] maps the source ship's frame onto the target ship's grid; [goal] is the source's
     *  world rotation for it; [error] is the turn left, in radians. */
    class Alignment(val goal: Quaterniond, val relative: Quaterniond, val error: Double)

    fun sameScale(a: Double, b: Double): Boolean = abs(a - b) <= FUSION_SCALE_EPSILON

    /** The blocks of [ship] within [worldRadius] of [worldCenter]. The work is bounded by the
     *  sphere, not by the size of the ship. */
    fun scan(level: ServerLevel, ship: LoadedServerShip, worldCenter: Vector3dc, worldRadius: Double): ShipBlocks {
        val c = ship.worldToShip.transformPosition(Vector3d(worldCenter))
        val r = worldRadius / ship.transform.shipToWorldScaling.x()
        val rSq = r * r
        val minX = Mth.floor(c.x - r)
        val maxX = Mth.floor(c.x + r)
        val minY = Mth.floor(c.y - r)
        val maxY = Mth.floor(c.y + r)
        val minZ = Mth.floor(c.z - r)
        val maxZ = Mth.floor(c.z + r)
        val packed = LongArrayList()
        val masses = DoubleArrayList()
        for (cx in (minX shr 4)..(maxX shr 4)) for (cz in (minZ shr 4)..(maxZ shr 4)) {
            if (!ship.chunkClaim.contains(cx, cz)) continue
            val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
            for (sy in (minY shr 4)..(maxY shr 4)) {
                val index = chunk.getSectionIndexFromSectionY(sy)
                if (index !in chunk.sections.indices) continue
                val section = chunk.sections[index]
                if (section.hasOnlyAir()) continue
                for (x in maxOf(minX, cx shl 4)..minOf(maxX, (cx shl 4) + 15)) {
                    for (y in maxOf(minY, sy shl 4)..minOf(maxY, (sy shl 4) + 15)) {
                        for (z in maxOf(minZ, cz shl 4)..minOf(maxZ, (cz shl 4) + 15)) {
                            val dx = x + 0.5 - c.x
                            val dy = y + 0.5 - c.y
                            val dz = z + 0.5 - c.z
                            if (dx * dx + dy * dy + dz * dz > rSq) continue
                            val state = section.getBlockState(x and 15, y and 15, z and 15)
                            if (state.isAir) continue
                            packed.add(BlockPos.asLong(x, y, z))
                            masses.add(BlockStateInfo.get(state)?.first ?: 0.0)
                        }
                    }
                }
            }
        }
        return ShipBlocks(packed.toLongArray(), masses.toDoubleArray(), minX, minY, minZ, maxX, maxY, maxZ)
    }

    /** Only the blocks inside the bubble are pulled. Null when none are inside. */
    fun lifted(ship: LoadedServerShip, blocks: ShipBlocks, worldCenter: Vector3dc, worldRadius: Double): Lifted? {
        val local = ship.worldToShip.transformPosition(Vector3d(worldCenter))
        val localRadius = worldRadius / ship.transform.shipToWorldScaling.x()
        val radiusSq = localRadius * localRadius
        var mass = 0.0
        val inside = IntArrayList()
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        for (i in blocks.packed.indices) {
            val block = blocks.packed[i]
            val x = BlockPos.getX(block) + 0.5
            val y = BlockPos.getY(block) + 0.5
            val z = BlockPos.getZ(block) + 0.5
            if ((x - local.x) * (x - local.x) + (y - local.y) * (y - local.y) + (z - local.z) * (z - local.z) > radiusSq) continue
            inside.add(i)
            mass += blocks.masses[i]
            sx += x
            sy += y
            sz += z
        }
        val count = inside.size
        if (count == 0) return null
        val shipMass = ship.inertiaData.mass
        val share = if (shipMass > 0.0) (mass / shipMass).coerceIn(0.0, 1.0) else 0.0
        return Lifted(ship, blocks, inside.toIntArray(), share, Vector3d(sx / count, sy / count, sz / count))
    }

    /** Near-closest inside blocks of [a] and [b]: alternate nearest-block passes from [a]'s centroid,
     *  three passes, not a full pairwise scan. Returns a's block, b's block and their world distance squared. */
    fun nearestPair(a: Lifted, b: Lifted): Triple<Vector3d, Vector3d, Double>? {
        val world = a.ship.shipToWorld.transformPosition(Vector3d(a.centroid))
        val bBlock = nearestInside(b, b.ship.worldToShip.transformPosition(Vector3d(world))) ?: return null
        b.ship.shipToWorld.transformPosition(world.set(bBlock))
        val aBlock = nearestInside(a, a.ship.worldToShip.transformPosition(Vector3d(world))) ?: return null
        a.ship.shipToWorld.transformPosition(world.set(aBlock))
        val bBest = nearestInside(b, b.ship.worldToShip.transformPosition(Vector3d(world))) ?: return null
        val bWorld = b.ship.shipToWorld.transformPosition(Vector3d(bBest))
        return Triple(aBlock, bBest, world.distanceSquared(bWorld))
    }

    private fun nearestInside(ship: Lifted, local: Vector3d): Vector3d? {
        var bestDistSq = Double.MAX_VALUE
        var best = 0L
        val packed = ship.blocks.packed
        for (i in ship.inside) {
            val block = packed[i]
            val x = BlockPos.getX(block) + 0.5
            val y = BlockPos.getY(block) + 0.5
            val z = BlockPos.getZ(block) + 0.5
            val distSq = (x - local.x) * (x - local.x) + (y - local.y) * (y - local.y) + (z - local.z) * (z - local.z)
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                best = block
            }
        }
        if (bestDistSq == Double.MAX_VALUE) return null
        return Vector3d(BlockPos.getX(best) + 0.5, BlockPos.getY(best) + 0.5, BlockPos.getZ(best) + 0.5)
    }

    /** Of the block pairs whose unit cubes touch, the pair that the weld moves least. The caller
     *  ensures both ships have the same uniform scale, so only a rotation is between the frames. */
    fun closestContact(a: LoadedServerShip, aBlocks: ShipBlocks, b: LoadedServerShip, bBlocks: ShipBlocks): Contact? {
        if (aBlocks.packed.size <= bBlocks.packed.size) return contactFrom(a, aBlocks, b, bBlocks)
        val swapped = contactFrom(b, bBlocks, a, aBlocks) ?: return null
        return Contact(swapped.b, swapped.a, swapped.snap)
    }

    /** Maps each surface block of [a] near [b]'s scanned bounds into [b]'s frame and tests it against the
     *  27 cells around it. Blocks far from [b] are dropped on integer bounds before any transform. */
    private fun contactFrom(a: LoadedServerShip, aBlocks: ShipBlocks, b: LoadedServerShip, bBlocks: ShipBlocks): Contact? {
        val aToB = Matrix4d(b.worldToShip).mul(a.shipToWorld)
        val near = boundsInFrame(Matrix4d(a.worldToShip).mul(b.shipToWorld), bBlocks)
        val rotation = Rotation(aToB)
        val c = Vector3d()
        var bestSnap = Double.MAX_VALUE
        var bestA = 0L
        var bestB = 0L
        for (packed in aBlocks.surface) {
            val px = BlockPos.getX(packed)
            val py = BlockPos.getY(packed)
            val pz = BlockPos.getZ(packed)
            if (px < near[0] || px > near[3] || py < near[1] || py > near[4] || pz < near[2] || pz > near[5]) continue
            aToB.transformPosition(c.set(BlockPos.getX(packed) + 0.5, BlockPos.getY(packed) + 0.5, BlockPos.getZ(packed) + 0.5))
            if (c.x < bBlocks.minX - 1 || c.x > bBlocks.maxX + 2 || c.y < bBlocks.minY - 1 || c.y > bBlocks.maxY + 2 ||
                c.z < bBlocks.minZ - 1 || c.z > bBlocks.maxZ + 2) continue
            val bx = Math.floor(c.x).toInt()
            val by = Math.floor(c.y).toInt()
            val bz = Math.floor(c.z).toInt()
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val cell = BlockPos.asLong(bx + dx, by + dy, bz + dz)
                if (!bBlocks.set.contains(cell)) continue
                rotation.t[0] = c.x - (bx + dx + 0.5)
                rotation.t[1] = c.y - (by + dy + 0.5)
                rotation.t[2] = c.z - (bz + dz + 0.5)
                if (!cubesOverlap(rotation)) continue
                val snap = snapDistance(rotation.t)
                if (snap < bestSnap) {
                    bestSnap = snap
                    bestA = packed
                    bestB = cell
                }
            }
        }
        return if (bestSnap == Double.MAX_VALUE) null else Contact(BlockPos.of(bestA), BlockPos.of(bestB), bestSnap)
    }

    /** The cells of [blocks]' bounds, mapped by [toFrame], as an integer box (min xyz, max xyz) grown
     *  by one cell: a block outside it cannot touch a block of [blocks]. */
    private fun boundsInFrame(toFrame: Matrix4d, blocks: ShipBlocks): IntArray {
        val corner = Vector3d()
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE
        for (i in 0 until 8) {
            toFrame.transformPosition(corner.set(
                (if (i and 1 == 0) blocks.minX else blocks.maxX + 1).toDouble(),
                (if (i and 2 == 0) blocks.minY else blocks.maxY + 1).toDouble(),
                (if (i and 4 == 0) blocks.minZ else blocks.maxZ + 1).toDouble(),
            ))
            minX = minOf(minX, corner.x); maxX = maxOf(maxX, corner.x)
            minY = minOf(minY, corner.y); maxY = maxOf(maxY, corner.y)
            minZ = minOf(minZ, corner.z); maxZ = maxOf(maxZ, corner.z)
        }
        return intArrayOf(
            Mth.floor(minX) - 1, Mth.floor(minY) - 1, Mth.floor(minZ) - 1,
            Mth.floor(maxX) + 1, Mth.floor(maxY) + 1, Mth.floor(maxZ) + 1,
        )
    }

    /** The weld puts the moved block in the cell next to the kept block, on the face nearest to it.
     *  [offset] is the moved block center minus the kept block center. */
    private fun snapDistance(offset: DoubleArray): Double {
        var axis = 0
        for (m in 1..2) if (abs(offset[m]) > abs(offset[axis])) axis = m
        var sum = 0.0
        for (m in 0..2) {
            val cell = if (m == axis) Math.signum(offset[m]) else 0.0
            val d = offset[m] - cell
            sum += d * d
        }
        return sqrt(sum)
    }

    /** The 3×3 part of a frame-to-frame matrix, for [cubesOverlap]. rot[i][j] is component i of the
     *  rotated cube's axis j (JOML is column-major). */
    private class Rotation(m: Matrix4d) {
        val rot: Array<DoubleArray> = arrayOf(
            doubleArrayOf(m.m00(), m.m10(), m.m20()),
            doubleArrayOf(m.m01(), m.m11(), m.m21()),
            doubleArrayOf(m.m02(), m.m12(), m.m22()),
        )
        val absR: Array<DoubleArray> = Array(3) { i -> DoubleArray(3) { j -> abs(rot[i][j]) + 1e-9 } }
        val t: DoubleArray = DoubleArray(3)
    }

    /** Separating-axis test (Gottschalk) between an axis-aligned cube and a cube rotated by [r],
     *  both with half-size [HALF]; [Rotation.t] is the center offset. */
    private fun cubesOverlap(r: Rotation): Boolean {
        val rot = r.rot
        val absR = r.absR
        val t = r.t
        for (i in 0..2) {
            if (abs(t[i]) > HALF + HALF * (absR[i][0] + absR[i][1] + absR[i][2])) return false
        }
        for (j in 0..2) {
            val proj = t[0] * rot[0][j] + t[1] * rot[1][j] + t[2] * rot[2][j]
            if (abs(proj) > HALF * (absR[0][j] + absR[1][j] + absR[2][j]) + HALF) return false
        }
        for (i in 0..2) for (j in 0..2) {
            val i1 = (i + 1) % 3
            val i2 = (i + 2) % 3
            val ra = HALF * (absR[i1][j] + absR[i2][j])
            val rb = HALF * (absR[i][(j + 1) % 3] + absR[i][(j + 2) % 3])
            if (abs(t[i2] * rot[i1][j] - t[i1] * rot[i2][j]) > ra + rb) return false
        }
        return true
    }

    /** The rotation that puts [source] on the block grid of [target] with the least turn. With [upright],
     *  only yaw turns: VS2's rotateState refuses any other turn of a skull or a carved pumpkin. */
    fun alignment(target: LoadedServerShip, source: LoadedServerShip, upright: Boolean = false): Alignment {
        val targetRotation = target.transform.rotation
        val relative = Quaterniond(targetRotation).invert().mul(source.transform.rotation).normalize()
        val rotations = if (upright) UPRIGHT_ROTATIONS else GRID_ROTATIONS
        var best = rotations[0]
        var bestDot = -1.0
        for (rotation in rotations) {
            val dot = abs(rotation.dot(relative))
            if (dot > bestDot) {
                bestDot = dot
                best = rotation
            }
        }
        val error = 2.0 * acos(bestDot.coerceAtMost(1.0))
        return Alignment(Quaterniond(targetRotation).mul(best), Quaterniond(best), error)
    }

    /** The face of [fromPos], in [from]'s shipyard frame, that points at [otherPos] on [other]. */
    fun faceToward(from: LoadedServerShip, fromPos: BlockPos, other: LoadedServerShip, otherPos: BlockPos): Direction {
        val world = other.shipToWorld.transformPosition(Vector3d(otherPos.x + 0.5, otherPos.y + 0.5, otherPos.z + 0.5))
        val local = from.worldToShip.transformPosition(world)
        return Direction.getNearest(local.x - (fromPos.x + 0.5), local.y - (fromPos.y + 0.5), local.z - (fromPos.z + 0.5))
    }

    /** The source face that the grid rotation [relative] turns onto the opposite of [targetFace]. VS2
     *  only accepts a rotation for which this holds, so this face always agrees with the alignment. */
    fun sourceFaceFor(targetFace: Direction, relative: Quaterniond): Direction {
        val v = Vector3d(-targetFace.stepX.toDouble(), -targetFace.stepY.toDouble(), -targetFace.stepZ.toDouble())
        Quaterniond(relative).conjugate().transform(v)
        return Direction.getNearest(v.x, v.y, v.z)
    }
}

private const val FUSION_SCALE_EPSILON: Double = 1e-6

@OptIn(GameTickOnly::class)
internal fun LoadedServerShip.uniformScale(): Double? {
    val s = transform.shipToWorldScaling
    if (abs(s.x() - s.y()) > FUSION_SCALE_EPSILON || abs(s.x() - s.z()) > FUSION_SCALE_EPSILON) return null
    return s.x()
}

/** Every non-air block of this ship, packed. Null while a chunk of it is not loaded. */
@OptIn(GameTickOnly::class)
internal fun LoadedServerShip.allBlocks(level: ServerLevel): LongArray? {
    val packed = LongArrayList()
    var missing = false
    activeChunksSet.forEach { cx, cz ->
        val chunk = level.chunkSource.getChunkNow(cx, cz)
        if (chunk == null) {
            missing = true
            return@forEach
        }
        for (index in chunk.sections.indices) {
            val section = chunk.sections[index]
            if (section.hasOnlyAir()) continue
            val baseY = chunk.getSectionYFromSectionIndex(index) shl 4
            for (x in 0..15) for (y in 0..15) for (z in 0..15) {
                if (!section.getBlockState(x, y, z).isAir) packed.add(BlockPos.asLong((cx shl 4) + x, baseY + y, (cz shl 4) + z))
            }
        }
    }
    return if (missing) null else packed.toLongArray()
}

/** The non-air blocks of this ship, counted up to [limit]. Null while a chunk of it is not loaded,
 *  unless [limit] is reached first: then a full count is at least [limit] too. */
@OptIn(GameTickOnly::class)
internal fun LoadedServerShip.countUpTo(level: ServerLevel, limit: Int): Int? {
    require(limit > 0) { "limit must be positive" }
    var count = 0
    var missing = false
    activeChunksSet.forEach { cx, cz ->
        if (count >= limit) return@forEach
        val chunk = level.chunkSource.getChunkNow(cx, cz)
        if (chunk == null) {
            missing = true
            return@forEach
        }
        for (section in chunk.sections) {
            if (section.hasOnlyAir()) continue
            for (x in 0..15) for (y in 0..15) for (z in 0..15) {
                if (section.getBlockState(x, y, z).isAir) continue
                if (++count >= limit) return@forEach
            }
        }
    }
    return if (count >= limit) limit else if (missing) null else count
}

@OptIn(GameTickOnly::class)
internal fun LoadedServerShip.isFreeCell(level: ServerLevel, pos: BlockPos): Boolean =
    chunkClaim.contains(pos.x shr 4, pos.z shr 4) && !level.isOutsideBuildHeight(pos) && level.getBlockState(pos).isAir

/** Squared gap between the world bounding boxes: a lower bound on any block-center distance. */
@OptIn(GameTickOnly::class)
internal fun LoadedServerShip.aabbGapSq(b: LoadedServerShip): Double {
    val p = worldAABB
    val q = b.worldAABB
    val dx = maxOf(q.minX() - p.maxX(), 0.0, p.minX() - q.maxX())
    val dy = maxOf(q.minY() - p.maxY(), 0.0, p.minY() - q.maxY())
    val dz = maxOf(q.minZ() - p.maxZ(), 0.0, p.minZ() - q.maxZ())
    return dx * dx + dy * dy + dz * dz
}

@OptIn(GameTickOnly::class)
internal fun LoadedServerShip.aabbsTouch(b: LoadedServerShip): Boolean {
    val p = worldAABB
    val q = b.worldAABB
    val m = 2.0 * FusionGeometry.HALF
    return p.minX() - m <= q.maxX() && q.minX() - m <= p.maxX() &&
        p.minY() - m <= q.maxY() && q.minY() - m <= p.maxY() &&
        p.minZ() - m <= q.maxZ() && q.minZ() - m <= p.maxZ()
}
