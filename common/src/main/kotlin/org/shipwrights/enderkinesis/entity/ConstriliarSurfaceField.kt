package org.shipwrights.enderkinesis.entity

import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.valkyrienskies.mod.common.getShipsIntersecting

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

    /** Field sample at [centre]: smoothed distance to the surface union and the
     *  away-from-surface normal (floor → up, wall → out, ceiling → down). Null when no
     *  collision geometry is within [range]. [smoothing] overrides the log-sum-exp
     *  sharpness — higher = smaller rounding radius (≈ 1/k blocks), giving more
     *  decisive normals near concave corners at the cost of continuity. */
    fun sample(
        level: Level,
        entity: Entity?,
        centre: Vec3,
        range: Double,
        ownShipId: Long?,
        smoothing: Double = SMOOTHING,
        maxBoxes: Int = MAX_BOXES,
        extraBoxes: List<AABB>? = null,
    ): Sample? {
        val boxes = collectBoxes(level, entity, centre, range, ownShipId, maxBoxes, extraBoxes)
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
        return Sample(gradient.normalize(), smoothDist, dMin)
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
    ): List<AABB> = collectBoxes(level, null, centre, range, null, maxBoxes, extraBoxes)

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
        maxBoxes: Int = MAX_BOXES,
        extraBoxes: List<AABB>? = null,
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
        outer@ for (shape in level.getBlockCollisions(entity, search)) {
            for (box in shape.toAabbs()) {
                out.add(box)
                if (out.size >= maxBoxes) break@outer
            }
        }
        // Other ships' hulls are surface too (own ship excluded — it's the body).
        for (ship in level.getShipsIntersecting(search)) {
            if (ship.id == ownShipId || out.size >= maxBoxes) continue
            val shipSearch = transformAabb(search) { x, y, z ->
                ship.worldToShip.transformPosition(Vector3d(x, y, z))
            }
            ships@ for (shape in level.getBlockCollisions(null, shipSearch)) {
                for (box in shape.toAabbs()) {
                    out.add(transformAabb(box) { x, y, z -> ship.shipToWorld.transformPosition(Vector3d(x, y, z)) })
                    if (out.size >= maxBoxes) break@ships
                }
            }
        }
        return out
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
