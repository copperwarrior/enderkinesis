package org.shipwrights.enderkinesis.barnacle

import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.core.Direction
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import org.joml.Vector3d
import java.util.Random

/**
 * Shared (server + client) tendril geometry for the Ygann Barnacle. The seeded
 * surface-following walk that the renderer draws is authoritative here so the SERVER
 * can compute the same tendril routes for its damage boxes. The boxes give damage only;
 * they are not colliders.
 *
 * Pure function of `(seed, facing, hull geometry)`; the only world access is
 * `getCollisionShape`, so it runs identically on both sides and inside a VS2 shipyard.
 */
object YgannBarnacleGeometry {

    // `arcPx` is mutable so the renderer can keep one list for a morphing tendril.
    class BWaypoint(val pos: Vector3d, val normal: Vector3d, var arcPx: Double)
    class BPath(val points: List<BWaypoint>, val phase: Double, val startArc: Double, val originArc: Double) {
        val totalPx: Double get() = points.lastOrNull()?.arcPx ?: 0.0
    }

    private class Tip(
        val pos: Vector3d, val normal: Vector3d, val heading: Vector3d,
        val startArc: Double, val originArc: Double, val gen: Int,
    )

    /** The seeded tendril walk — verbatim from the original renderer. */
    fun walk(level: Level, blockPos: BlockPos, seed: Long, facing: Direction): List<BPath> {
        val rng = Random(seed)
        val n0 = Vector3d(facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble())
        val origin = Vector3d(0.5, 0.5, 0.5).sub(Vector3d(n0).mul(0.5))
        val (t1, t2) = tangentsOf(facing)
        val base = Vector3d(blockPos.x.toDouble(), blockPos.y.toDouble(), blockPos.z.toDouble())

        val queue = ArrayDeque<Tip>()
        for (i in 0 until TOTAL_ROOTS) {
            val ang = (i + rng.nextDouble() * 0.6) * (Math.PI * 2.0 / TOTAL_ROOTS)
            val heading = Vector3d(t1).mul(Math.cos(ang)).add(Vector3d(t2).mul(Math.sin(ang))).normalize()
            val batch = i / CONCURRENT_ROOTS
            queue.add(
                Tip(
                    Vector3d(origin).add(Vector3d(heading).mul(2.5 / 16.0)).add(Vector3d(n0).mul(0.01)),
                    Vector3d(n0), heading, batch * TENTACLE_LEN_PX, 0.0, 0,
                ),
            )
        }

        val paths = ArrayList<BPath>()
        val laidPoints = ArrayList<Vector3d>()
        var tipsSpawned = TOTAL_ROOTS
        while (queue.isNotEmpty() && paths.size < MAX_TENTACLES) {
            val tip = queue.removeFirst()
            val pos = Vector3d(tip.pos)
            val normal = Vector3d(tip.normal)
            val heading = Vector3d(tip.heading)
            val points = ArrayList<BWaypoint>()
            var arc = 0.0
            points.add(BWaypoint(Vector3d(pos), Vector3d(normal), arc))
            var guard = 0

            while (arc < TENTACLE_LEN_PX && guard++ < MAX_STEPS) {
                val random = Vector3d(
                    (rng.nextDouble() - 0.5), (rng.nextDouble() - 0.5), (rng.nextDouble() - 0.5),
                ).mul(W_RANDOM)
                val spread = Vector3d(pos).sub(origin)
                if (spread.lengthSquared() > 1.0e-6) spread.normalize().mul(W_SPREAD) else spread.set(0.0)
                val avoid = Vector3d()
                for (pt in laidPoints) {
                    val dxp = pos.x - pt.x; val dyp = pos.y - pt.y; val dzp = pos.z - pt.z
                    val d2 = dxp * dxp + dyp * dyp + dzp * dzp
                    if (d2 < AVOID_RADIUS_SQ && d2 > 1.0e-9) {
                        val inv = 1.0 / d2
                        avoid.add(dxp * inv, dyp * inv, dzp * inv)
                    }
                }
                if (avoid.lengthSquared() > 1.0e-9) avoid.normalize().mul(W_AVOID) else avoid.set(0.0)
                val dir = Vector3d(heading).mul(W_PRIMARY).add(random).add(spread).add(avoid)
                projectToPlane(dir, normal)
                if (dir.lengthSquared() < 1.0e-9) break
                dir.normalize()
                heading.set(dir)

                val next = Vector3d(pos).add(Vector3d(heading).mul(STEP_PX / 16.0))
                val sub = surfaceProbe(level, base, next, normal) ?: break
                normal.set(sub.second)
                pos.set(sub.first).add(Vector3d(normal).mul(SURFACE_OFFSET))
                projectToPlane(heading, normal)
                if (heading.lengthSquared() > 1.0e-9) heading.normalize()
                arc += STEP_PX
                points.add(BWaypoint(Vector3d(pos), Vector3d(normal), arc))

                if (tip.gen < MAX_GEN && tipsSpawned < MAX_TENTACLES &&
                    arc > BRANCH_MIN_ARC && rng.nextFloat() < BRANCH_CHANCE
                ) {
                    val side = Vector3d(heading).cross(normal).normalize()
                    val childHeading = Vector3d(heading)
                        .add(side.mul(if (rng.nextBoolean()) 1.0 else -1.0)).normalize()
                    queue.add(Tip(Vector3d(pos), Vector3d(normal), childHeading, tip.startArc + arc, tip.originArc + arc, tip.gen + 1))
                    tipsSpawned++
                }
            }
            if (points.size > 1) {
                paths.add(BPath(points, rng.nextDouble() * Math.PI * 2.0, tip.startArc, tip.originArc))
                for (wp in points) laidPoints.add(wp.pos)
            }
        }
        return paths
    }

    /** World/shipyard-space collider AABBs along every REVEALED tendril — a chain of
     *  small block-like boxes hugging the hull, matching what the renderer draws. */
    fun collisionBoxes(blockPos: BlockPos, paths: List<BPath>, growthPx: Double): List<AABB> {
        val bx = blockPos.x.toDouble(); val by = blockPos.y.toDouble(); val bz = blockPos.z.toDouble()
        val out = ArrayList<AABB>()
        for (path in paths) {
            val reveal = Math.min(growthPx - path.startArc, path.totalPx)
            if (reveal <= 1.0) continue
            var arc = 0.0
            while (arc < reveal - 1.0e-4) {
                val s = sampleAt(path.points, arc)
                val r = colliderRadiusAt(path.originArc + arc) / 16.0
                // Box centre sits a radius above the surface, like the render box.
                val cx = bx + s.first.x + s.second.x * r
                val cy = by + s.first.y + s.second.y * r
                val cz = bz + s.first.z + s.second.z * r
                out.add(AABB(cx - r, cy - r, cz - r, cx + r, cy + r, cz + r))
                arc += COLLIDE_STEP_PX
            }
        }
        return out
    }

    /** (pos, normal) on the path at arc length [arc]. */
    private fun sampleAt(pts: List<BWaypoint>, arc: Double): Pair<Vector3d, Vector3d> {
        if (arc <= 0.0) return Pair(Vector3d(pts[0].pos), Vector3d(pts[0].normal))
        for (i in 1 until pts.size) {
            val b = pts[i]
            if (b.arcPx >= arc) {
                val a = pts[i - 1]
                val f = ((arc - a.arcPx) / (b.arcPx - a.arcPx)).coerceIn(0.0, 1.0)
                val nrm = Vector3d(a.normal).lerp(b.normal, f)
                if (nrm.lengthSquared() > 1.0e-9) nrm.normalize()
                return Pair(Vector3d(a.pos).lerp(b.pos, f), nrm)
            }
        }
        val l = pts.size - 1
        return Pair(Vector3d(pts[l].pos), Vector3d(pts[l].normal))
    }

    /** Collider half-width (px), tapering root→tip like the render tube. */
    private fun colliderRadiusAt(arcPx: Double): Double {
        val t = (arcPx / TENTACLE_LEN_PX).coerceIn(0.0, 1.0)
        return Math.max(0.6, 2.4 * (1.0 - t * t * 0.8))
    }

    private fun surfaceProbe(
        level: Level, base: Vector3d, worldLocal: Vector3d, normalHint: Vector3d,
    ): Pair<Vector3d, Vector3d>? {
        val wx = base.x + worldLocal.x
        val wy = base.y + worldLocal.y
        val wz = base.z + worldLocal.z
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var count = 0
        var nearest: Vector3d? = null
        var nearestSq = Double.MAX_VALUE
        val cursor = BlockPos.MutableBlockPos()
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            cursor.set(Math.floor(wx + dx).toInt(), Math.floor(wy + dy).toInt(), Math.floor(wz + dz).toInt())
            if (!isHullSolid(level, cursor)) continue
            val cxw = cursor.x + 0.5; val cyw = cursor.y + 0.5; val czw = cursor.z + 0.5
            sx += cxw; sy += cyw; sz += czw; count++
            val d = (cxw - wx) * (cxw - wx) + (cyw - wy) * (cyw - wy) + (czw - wz) * (czw - wz)
            if (d < nearestSq) { nearestSq = d; nearest = Vector3d(cxw, cyw, czw) }
        }
        if (count == 0 || nearest == null) return null
        val centroid = Vector3d(sx / count, sy / count, sz / count)
        val outward = Vector3d(wx, wy, wz).sub(centroid)
        if (outward.lengthSquared() < 1.0e-6) outward.set(normalHint) else outward.normalize()
        val probe = BlockPos.MutableBlockPos()
        var d = -MARCH_START
        var lastAirD = d
        var hit = false
        while (d <= MARCH_MAX) {
            probe.set(
                Math.floor(wx - outward.x * d).toInt(),
                Math.floor(wy - outward.y * d).toInt(),
                Math.floor(wz - outward.z * d).toInt(),
            )
            if (isHullSolid(level, probe)) { hit = true; break }
            lastAirD = d
            d += MARCH_STEP
        }
        if (!hit) return null
        val crossing = lastAirD + MARCH_STEP * 0.5
        val surface = Vector3d(wx - outward.x * crossing, wy - outward.y * crossing, wz - outward.z * crossing)
        return Pair(surface.sub(base), outward)
    }

    // `level.getBlockState` loads a chunk that is not loaded, on the server thread.
    private fun isHullSolid(level: Level, pos: BlockPos): Boolean {
        val chunk = level.getChunk(pos.x shr 4, pos.z shr 4, ChunkStatus.FULL, false) ?: return false
        return !chunk.getBlockState(pos).getCollisionShape(level, pos).isEmpty
    }

    fun tangentsOf(facing: Direction): Pair<Vector3d, Vector3d> {
        val n = Vector3d(facing.stepX.toDouble(), facing.stepY.toDouble(), facing.stepZ.toDouble())
        val ref = if (Math.abs(n.y) > 0.5) Vector3d(1.0, 0.0, 0.0) else Vector3d(0.0, 1.0, 0.0)
        val t1 = Vector3d(ref).cross(n).normalize()
        val t2 = Vector3d(n).cross(t1).normalize()
        return Pair(t1, t2)
    }

    fun rotateAround(v: Vector3d, axis: Vector3d, angle: Double) {
        val cos = Math.cos(angle)
        val sin = Math.sin(angle)
        val cross = Vector3d(axis).cross(v)
        val dot = axis.dot(v)
        v.mul(cos).add(cross.mul(sin)).add(Vector3d(axis).mul(dot * (1.0 - cos)))
    }

    fun projectToPlane(v: Vector3d, normal: Vector3d) {
        v.sub(Vector3d(normal).mul(v.dot(normal)))
    }

    const val TENTACLE_LEN_PX = 130.0
    private const val STEP_PX = 3.0
    private const val SURFACE_OFFSET = 0.03
    private const val MARCH_START = 0.3
    private const val MARCH_MAX = 1.6
    private const val MARCH_STEP = 0.05
    private const val W_PRIMARY = 1.0
    private const val W_RANDOM = 0.35
    private const val W_SPREAD = 0.25
    private const val TOTAL_ROOTS = 8
    private const val CONCURRENT_ROOTS = 3
    private const val W_AVOID = 0.5
    private const val AVOID_RADIUS_SQ = 0.35 * 0.35
    private const val BRANCH_MIN_ARC = 12.0
    private const val BRANCH_CHANCE = 0.06f
    private const val MAX_GEN = 2
    private const val MAX_TENTACLES = 22
    private const val MAX_STEPS = 80
    /** Collider box spacing along the arc (px). */
    private const val COLLIDE_STEP_PX = 6.0
}
