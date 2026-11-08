package org.shipwrights.enderkinesis.entity

import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.ArrayVoxelShape
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape
import net.minecraft.world.phys.shapes.BooleanOp
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Vector3d
import org.valkyrienskies.mod.common.getShipsIntersecting
import java.util.Arrays
import org.shipwrights.enderkinesis.util.hasChunkAtPos

/**
 * Smoothed signed-distance field over nearby collision geometry (the Spiders 2.0
 * CollisionSmoothingUtil technique, reimplemented): per-box signed distances combined
 * with an exponential smooth-min, whose weighted gradient at the body centre is a
 * normal that rotates CONTINUOUSLY around edges and corners — the smoothing radius
 * mathematically rounds the geometry, so there is no face snapping and no blending
 * heuristics. Stairs, slabs, walls, and other ships' hulls all contribute their real
 * collision boxes.
 */
object ConstriliarSurfaceField {

    /** [distance] is the SMOOTHED distance — useful for soft falloffs, but it
     *  systematically reads short wherever several boxes contribute (a flat
     *  floor of nine blocks biases it by about a quarter block at k=4), so it
     *  must not be used to place a body against a surface. [rawDistance] is
     *  the true distance to the nearest box and is what contact resolution
     *  wants; [up] stays smoothed, which is the whole point of the field. */
    class Sample(val up: Vector3d, val distance: Double, val rawDistance: Double)

    private const val MAX_BOXES = 128
    /** Log-sum-exp sharpness: rounding radius ≈ 1/k blocks around edges. */
    private const val SMOOTHING = 3.0
    /** The epsilon of Shapes.create and IndirectMerger. */
    private const val SHAPE_EPS = 1.0e-7

    /** Field sample at [centre]: smoothed distance to the surface union and the
     *  away-from-surface normal (floor → up, wall → out, ceiling → down). Null when no
     *  collision geometry is within [range]. [smoothing] overrides the log-sum-exp
     *  sharpness — higher = smaller rounding radius (≈ 1/k blocks), giving more
     *  decisive normals near concave corners at the cost of continuity.
     *
     *  [mergeBlocks]: the normal comes from the world blocks merged into maximal boxes.
     *  A soft union of per-block boxes reads closer at every seam, which tilted the
     *  normal up to ~17° on a flat floor at a 0.2-block sample height. The distances
     *  stay per-block, so thresholds tuned on them keep their meaning.
     *
     *  [physicsOnly]: skip blocks a VS2 body passes through (see [ConstriliarCollision]). */
    fun sample(
        level: Level,
        entity: Entity?,
        centre: Vec3,
        range: Double,
        ownShipId: Long?,
        smoothing: Double = SMOOTHING,
        maxBoxes: Int = MAX_BOXES,
        extraBoxes: List<AABB>? = null,
        mergeBlocks: Boolean = false,
        mergeCache: MergeCache? = null,
        physicsOnly: Boolean = false,
    ): Sample? {
        val merged = if (mergeBlocks) ArrayList<AABB>(16) else null
        val boxes = collectBoxes(
            level, entity, centre, range, ownShipId, maxBoxes, extraBoxes, merged, mergeCache, physicsOnly,
        )
        if (boxes.isEmpty()) return null

        val dists = DoubleArray(boxes.size)
        val grads = Array(boxes.size) { Vector3d() }
        var dMin = Double.MAX_VALUE
        for (i in boxes.indices) {
            dists[i] = boxSdf(centre, boxes[i], grads[i])
            if (dists[i] < dMin) dMin = dists[i]
        }

        var weightSum = 0.0
        val gradient = Vector3d()
        for (i in boxes.indices) {
            val w = Math.exp(-smoothing * (dists[i] - dMin))
            weightSum += w
            gradient.fma(w, grads[i])
        }
        if (gradient.lengthSquared() < 1.0e-8 || weightSum <= 0.0) return null
        val smoothDist = dMin - Math.log(weightSum) / smoothing
        gradient.normalize()
        if (merged != null && merged.isNotEmpty()) smoothNormal(centre, merged, smoothing, gradient)
        return Sample(gradient, smoothDist, dMin)
    }

    /** Smoothed normal over [boxes] into [out]; leaves [out] as it was when the field
     *  has no direction at [centre]. */
    private fun smoothNormal(centre: Vec3, boxes: List<AABB>, smoothing: Double, out: Vector3d) {
        val grad = Vector3d()
        var dMin = Double.MAX_VALUE
        for (box in boxes) dMin = Math.min(dMin, boxSdf(centre, box, grad))
        val sum = Vector3d()
        for (box in boxes) {
            val d = boxSdf(centre, box, grad)
            sum.fma(Math.exp(-smoothing * (d - dMin)), grad)
        }
        if (sum.lengthSquared() >= 1.0e-8) out.set(sum).normalize()
    }

    /** The last block merge, reused while the world boxes around the sample are the
     *  same: the shape join is the costly part of a sample. One for each caller thread. */
    class MergeCache {
        internal val key = ArrayList<AABB>(64)
        internal val merged = ArrayList<AABB>(16)
    }

    /** Reused arrays and the result of [sampleBoxes]. One for each caller thread. */
    class SampleScratch {
        internal var dists = DoubleArray(32)
        internal var grads = Array(32) { Vector3d() }
        val up = Vector3d()
        var rawDistance = 0.0

        internal fun ensure(size: Int) {
            if (dists.size >= size) return
            val grown = Math.max(size, dists.size * 2)
            dists = DoubleArray(grown)
            grads = Array(grown) { Vector3d() }
        }
    }

    /** The collision boxes around [centre], for a caller that samples many points from one
     *  list with [sampleBoxes]. */
    fun boxesNear(
        level: Level, centre: Vec3, range: Double, maxBoxes: Int, extraBoxes: List<AABB>?,
    ): List<AABB> = collectBoxes(level, null, centre, range, null, maxBoxes, extraBoxes, null, null, false)

    /** [sample] over a given box list, with no allocation. False when the field has no
     *  direction at the point. The result is in [scratch]. */
    fun sampleBoxes(
        boxes: List<AABB>, x: Double, y: Double, z: Double, smoothing: Double, scratch: SampleScratch,
    ): Boolean {
        val count = boxes.size
        if (count == 0) return false
        scratch.ensure(count)
        val dists = scratch.dists
        val grads = scratch.grads
        var dMin = Double.MAX_VALUE
        for (i in 0 until count) {
            dists[i] = boxSdf(x, y, z, boxes[i], grads[i])
            if (dists[i] < dMin) dMin = dists[i]
        }
        var weightSum = 0.0
        val gradient = scratch.up.set(0.0, 0.0, 0.0)
        for (i in 0 until count) {
            val w = Math.exp(-smoothing * (dists[i] - dMin))
            weightSum += w
            gradient.fma(w, grads[i])
        }
        if (gradient.lengthSquared() < 1.0e-8 || weightSum <= 0.0) return false
        gradient.normalize()
        scratch.rawDistance = dMin
        return true
    }

    private fun collectBoxes(
        level: Level,
        entity: Entity?,
        centre: Vec3,
        range: Double,
        ownShipId: Long?,
        maxBoxes: Int,
        extraBoxes: List<AABB>?,
        mergedOut: MutableList<AABB>?,
        mergeCache: MergeCache?,
        physicsOnly: Boolean,
    ): List<AABB> {
        val out = ArrayList<AABB>(32)
        val search = AABB(centre, centre).inflate(range)
        // Caller-supplied surfaces (entity hulls, for the remnant). Passed in
        // rather than gathered here so that collecting them stays a once-per-
        // tick job for whoever wants them, instead of an entity query per
        // sample — and so this field keeps its existing meaning for callers
        // that only care about world geometry.
        if (extraBoxes != null) {
            for (box in extraBoxes) {
                if (!box.intersects(search)) continue
                out.add(box)
                if (out.size >= maxBoxes) return out
            }
        }
        val extraEnd = out.size
        addCollisions(level, entity, search, physicsOnly, out, maxBoxes, null)
        val worldEnd = out.size
        if (mergedOut != null) {
            mergedOut.addAll(out.subList(0, extraEnd))
            mergedOut.addAll(mergeWorldBoxes(out.subList(extraEnd, worldEnd), mergeCache))
        }
        // Other ships' hulls are surface too (own ship excluded — it's the body).
        for (ship in level.getShipsIntersecting(search)) {
            if (ship.id == ownShipId || out.size >= maxBoxes) continue
            val shipSearch = transformAabb(search) { x, y, z ->
                ship.worldToShip.transformPosition(Vector3d(x, y, z))
            }
            addCollisions(level, null, shipSearch, physicsOnly, out, maxBoxes) { box ->
                transformAabb(box) { x, y, z -> ship.shipToWorld.transformPosition(Vector3d(x, y, z)) }
            }
        }
        mergedOut?.addAll(out.subList(worldEnd, out.size))
        return out
    }

    /** The collision boxes in [area] into [out], through [map] when given. With
     *  [physicsOnly], blocks a VS2 body passes through are skipped; that needs the block
     *  state, which [Level.getBlockCollisions] does not give. */
    private fun addCollisions(
        level: Level,
        entity: Entity?,
        area: AABB,
        physicsOnly: Boolean,
        out: MutableList<AABB>,
        maxBoxes: Int,
        map: ((AABB) -> AABB)?,
    ) {
        if (!physicsOnly) {
            for (shape in level.getBlockCollisions(entity, area)) {
                for (box in shape.toAabbs()) {
                    out.add(map?.invoke(box) ?: box)
                    if (out.size >= maxBoxes) return
                }
            }
            return
        }
        val context = if (entity == null) CollisionContext.empty() else CollisionContext.of(entity)
        for (pos in BlockPos.betweenClosed(
            Mth.floor(area.minX), Mth.floor(area.minY), Mth.floor(area.minZ),
            Mth.floor(area.maxX), Mth.floor(area.maxY), Mth.floor(area.maxZ),
        )) {
            if (!level.hasChunkAtPos(pos)) continue
            val state = level.getBlockState(pos)
            if (state.isAir || ConstriliarCollision.shipPassable(state)) continue
            val shape = state.getCollisionShape(level, pos, context)
            if (shape.isEmpty) continue
            for (local in shape.toAabbs()) {
                val box = local.move(pos)
                if (!box.intersects(area)) continue
                out.add(map?.invoke(box) ?: box)
                if (out.size >= maxBoxes) return
            }
        }
    }

    /** optimize() + toAabbs() emits maximal boxes: a flat floor becomes one box. */
    private fun mergeWorldBoxes(world: List<AABB>, cache: MergeCache?): List<AABB> {
        if (cache != null && cache.key == world) return cache.merged
        val joined = unionShape(world) ?: chainUnion(world)
        val merged = joined.optimize().toAabbs()
        if (cache != null) {
            cache.key.clear()
            cache.key.addAll(world)
            cache.merged.clear()
            cache.merged.addAll(merged)
        }
        return merged
    }

    private fun chainUnion(world: List<AABB>): VoxelShape {
        var joined: VoxelShape = Shapes.empty()
        for (box in world) joined = Shapes.joinUnoptimized(joined, Shapes.create(box), BooleanOp.OR)
        return joined
    }

    /** The same shape as [chainUnion] (same coords, same cells), filled in one pass. Null where
     *  the chain could differ: Shapes.create's unit-cell path, or coords that IndirectMerger fuses. */
    private fun unionShape(world: List<AABB>): VoxelShape? {
        val xs = DoubleArray(world.size * 2)
        val ys = DoubleArray(world.size * 2)
        val zs = DoubleArray(world.size * 2)
        var n = 0
        for (box in world) {
            if (isThin(box)) continue
            if (inUnitCell(box.minX, box.maxX) && inUnitCell(box.minY, box.maxY) && inUnitCell(box.minZ, box.maxZ)) {
                return null
            }
            xs[n] = box.minX; ys[n] = box.minY; zs[n] = box.minZ; n++
            xs[n] = box.maxX; ys[n] = box.maxY; zs[n] = box.maxZ; n++
        }
        if (n < 4) return null
        val cx = distinctCoords(xs, n) ?: return null
        val cy = distinctCoords(ys, n) ?: return null
        val cz = distinctCoords(zs, n) ?: return null
        val cells = BitSetDiscreteVoxelShape(cx.size - 1, cy.size - 1, cz.size - 1)
        for (box in world) {
            if (isThin(box)) continue
            val x1 = Arrays.binarySearch(cx, box.maxX)
            val y1 = Arrays.binarySearch(cy, box.maxY)
            val z1 = Arrays.binarySearch(cz, box.maxZ)
            for (x in Arrays.binarySearch(cx, box.minX) until x1) {
                for (y in Arrays.binarySearch(cy, box.minY) until y1) {
                    for (z in Arrays.binarySearch(cz, box.minZ) until z1) cells.fill(x, y, z)
                }
            }
        }
        return object : ArrayVoxelShape(cells, cx, cy, cz) {}
    }

    private fun isThin(box: AABB): Boolean =
        box.maxX - box.minX < SHAPE_EPS || box.maxY - box.minY < SHAPE_EPS || box.maxZ - box.minZ < SHAPE_EPS

    private fun inUnitCell(min: Double, max: Double): Boolean = min >= -SHAPE_EPS && max <= 1.0 + SHAPE_EPS

    private fun distinctCoords(values: DoubleArray, count: Int): DoubleArray? {
        Arrays.sort(values, 0, count)
        var size = 0
        for (i in 0 until count) {
            val v = values[i]
            if (size > 0 && v == values[size - 1]) continue
            if (size > 0 && v - values[size - 1] <= SHAPE_EPS) return null
            values[size++] = v
        }
        return values.copyOf(size)
    }

    /** Bounding AABB of a box's 8 corners under [transform] — conservative for rotated
     *  ships, exact for resting axis-aligned ones. */
    private inline fun transformAabb(box: AABB, transform: (Double, Double, Double) -> Vector3d): AABB {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (cx in 0..1) {
            for (cy in 0..1) {
                for (cz in 0..1) {
                    val p = transform(
                        if (cx == 0) box.minX else box.maxX,
                        if (cy == 0) box.minY else box.maxY,
                        if (cz == 0) box.minZ else box.maxZ,
                    )
                    minX = Math.min(minX, p.x); minY = Math.min(minY, p.y); minZ = Math.min(minZ, p.z)
                    maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y); maxZ = Math.max(maxZ, p.z)
                }
            }
        }
        return AABB(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /** Standard box SDF with analytic gradient. Outside: distance/direction from the
     *  clamped closest point. Inside: negative distance to the nearest face, gradient
     *  toward it. */
    private fun boxSdf(p: Vec3, box: AABB, outGrad: Vector3d): Double = boxSdf(p.x, p.y, p.z, box, outGrad)

    private fun boxSdf(px: Double, py: Double, pz: Double, box: AABB, outGrad: Vector3d): Double {
        val vx = px - px.coerceIn(box.minX, box.maxX)
        val vy = py - py.coerceIn(box.minY, box.maxY)
        val vz = pz - pz.coerceIn(box.minZ, box.maxZ)
        val outsideSq = vx * vx + vy * vy + vz * vz
        if (outsideSq > 1.0e-12) {
            val d = Math.sqrt(outsideSq)
            outGrad.set(vx / d, vy / d, vz / d)
            return d
        }
        // Inside: signed distances to each face pair (all negative); nearest face wins.
        val dxNeg = box.minX - px; val dxPos = px - box.maxX
        val dyNeg = box.minY - py; val dyPos = py - box.maxY
        val dzNeg = box.minZ - pz; val dzPos = pz - box.maxZ
        var d = dxNeg; outGrad.set(-1.0, 0.0, 0.0)
        if (dxPos > d) { d = dxPos; outGrad.set(1.0, 0.0, 0.0) }
        if (dyNeg > d) { d = dyNeg; outGrad.set(0.0, -1.0, 0.0) }
        if (dyPos > d) { d = dyPos; outGrad.set(0.0, 1.0, 0.0) }
        if (dzNeg > d) { d = dzNeg; outGrad.set(0.0, 0.0, -1.0) }
        if (dzPos > d) { d = dzPos; outGrad.set(0.0, 0.0, 1.0) }
        return d
    }
}
