package org.shipwrights.enderkinesis.client

import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.shipwrights.enderkinesis.body.GamylanCageShape
import org.shipwrights.enderkinesis.client.GamylanCageClientRegistry.Entry

/** Client-side raycast against active Ga'Mylan cage bodies. Mirrors
 *  the shape of Skyriders' `VehicleInteractionHandler.findVehicleOnRay`
 *  (Skyriders' melee-attack mixin entry point) — given the player's
 *  eye + look ray, return the closest cage along the ray within
 *  [maxRange] blocks, or `null` if nothing's hit.
 *
 *  Two-stage test per cage: a bounding-sphere reject (cheap, catches
 *  most far-miss rays) followed by a per-triangle test against the
 *  same 24-triangle mesh the renderer draws — 6 top-pyramid tris + 6
 *  bottom-pyramid tris + 12 middle-prism tris (six quads split). The
 *  triangles are transformed to world space using the body's live
 *  pose (position + rotation + scale), so hits track the crystal's
 *  actual on-screen silhouette instead of just its bounding sphere. */
object GamylanCageRaytrace {

    /** Distance of the last hit from [findCageOnRay]. Client thread only. */
    @JvmField
    var lastHitDistance: Double = Double.MAX_VALUE

    /** True when the vanilla crosshair target is nearer than the last cage hit, so the cage
     *  is behind a block or an entity. */
    @JvmStatic
    fun vanillaTargetIsNearer(eye: Vec3, vanillaHit: net.minecraft.world.phys.HitResult?): Boolean {
        if (vanillaHit == null || vanillaHit.type == net.minecraft.world.phys.HitResult.Type.MISS) return false
        return eye.distanceTo(vanillaHit.location) < lastHitDistance
    }

    /** Closest [Entry] along the ray `eye → eye + look·maxRange`, or
     *  null if no cage is hit. */
    fun findCageOnRay(eye: Vec3, look: Vec3, maxRange: Double): Entry? {
        lastHitDistance = Double.MAX_VALUE
        val dir = look.normalize()
        var best: Entry? = null
        var bestT = maxRange
        val end = eye.add(dir.scale(maxRange))
        for (entry in GamylanCageClientRegistry.currentLevelEntries()) {
            val liveScale = GamylanCageClientRegistry.liveScale(entry).toDouble()
            val r = GamylanCageShape.boundingRadius(entry.shapeDims) * liveScale
            val livePos = GamylanCageClientRegistry.livePosition(entry)
            // Stage 1 — cheap sphere reject. `maybeEnterT` is the ray
            // parameter for the FIRST sphere-surface entry, or null if
            // the ray misses the sphere; we skip the tri test entirely
            // in that case.
            raySegmentSphereEnterT(eye, end, livePos.x, livePos.y, livePos.z, r)
                ?: continue
            // Stage 2 — accurate per-triangle test against the actual
            // hex bipyramid. Rejects the "clip the crystal's outer
            // extents" false positives the sphere alone would accept.
            val meshT = rayHitCageMesh(entry, eye, dir, bestT) ?: continue
            if (meshT < bestT) {
                bestT = meshT
                best = entry
                lastHitDistance = meshT
            }
        }
        return best
    }

    /** Ray parameter for the FIRST entry of the segment `eye → end`
     *  into the sphere at `(cx, cy, cz)` with radius `r`, or null if
     *  the segment misses. Only used as a cheap early-reject before
     *  the tri test — we don't actually consume the returned t for
     *  distance ranking. */
    private fun raySegmentSphereEnterT(
        eye: Vec3, end: Vec3,
        cx: Double, cy: Double, cz: Double, r: Double,
    ): Double? {
        val dx = end.x - eye.x
        val dy = end.y - eye.y
        val dz = end.z - eye.z
        val len2 = dx * dx + dy * dy + dz * dz
        if (len2 < 1e-9) return null
        val ex = eye.x - cx; val ey = eye.y - cy; val ez = eye.z - cz
        val b = 2.0 * (dx * ex + dy * ey + dz * ez)
        val c = ex * ex + ey * ey + ez * ez - r * r
        // The eye is inside the sphere: a large cage at close range has no surface entry
        // on the segment, but the mesh can still be hit.
        if (c <= 0.0) return 0.0
        val disc = b * b - 4.0 * len2 * c
        if (disc < 0.0) return null
        val sqrtD = Math.sqrt(disc)
        val t1 = (-b - sqrtD) / (2.0 * len2)
        val t2 = (-b + sqrtD) / (2.0 * len2)
        return when {
            t1 in 0.0..1.0 -> t1
            t2 in 0.0..1.0 -> t2
            else -> null
        }
    }

    /** Ray-vs-hex-bipyramid mesh intersection in world space. Returns
     *  the world-space t (i.e. distance along the unit-direction ray)
     *  of the closest hit within `maxT`, or null. */
    private fun rayHitCageMesh(entry: Entry, eye: Vec3, dir: Vec3, maxT: Double): Double? {
        val dims = entry.shapeDims
        val liveScale = GamylanCageClientRegistry.liveScale(entry).toDouble()
        val rx = dims.x * liveScale
        val midH = dims.y * liveScale
        val tipLen = dims.z * liveScale
        val rot = GamylanCageClientRegistry.liveRotation(entry)
        val pos = GamylanCageClientRegistry.livePosition(entry)

        // Precompute the 14 canonical body-local vertices, transform
        // each once to world space via `rot` and `pos`, and reuse them
        // across the 24 triangle tests.
        val topApex = toWorld(0.0, midH + tipLen, 0.0, rot, pos)
        val bottomApex = toWorld(0.0, -(midH + tipLen), 0.0, rot, pos)
        val topEq = Array(6) { i ->
            val a = i * Math.PI / 3.0
            toWorld(rx * Math.cos(a), midH, rx * Math.sin(a), rot, pos)
        }
        val bottomEq = Array(6) { i ->
            val a = i * Math.PI / 3.0
            toWorld(rx * Math.cos(a), -midH, rx * Math.sin(a), rot, pos)
        }

        var bestT = maxT
        var hit = false
        for (i in 0 until 6) {
            val i1 = (i + 1) % 6
            // Top pyramid tri — outward-facing winding matches renderer.
            rayTriangle(eye, dir, topApex, topEq[i1], topEq[i])?.let {
                if (it < bestT) { bestT = it; hit = true }
            }
            // Bottom pyramid tri.
            rayTriangle(eye, dir, bottomApex, bottomEq[i], bottomEq[i1])?.let {
                if (it < bestT) { bestT = it; hit = true }
            }
            // Middle-prism side quad, split into two tris.
            rayTriangle(eye, dir, topEq[i], topEq[i1], bottomEq[i1])?.let {
                if (it < bestT) { bestT = it; hit = true }
            }
            rayTriangle(eye, dir, topEq[i], bottomEq[i1], bottomEq[i])?.let {
                if (it < bestT) { bestT = it; hit = true }
            }
        }
        return if (hit) bestT else null
    }

    /** Transform a body-local point (bx, by, bz) into world space via
     *  the crystal's live rotation and world position. */
    private fun toWorld(bx: Double, by: Double, bz: Double, rot: org.joml.Quaternionf, pos: org.joml.Vector3d): Vec3 {
        val v = Vector3f(bx.toFloat(), by.toFloat(), bz.toFloat())
        rot.transform(v)
        return Vec3(pos.x + v.x, pos.y + v.y, pos.z + v.z)
    }

    /** Möller-Trumbore ray-triangle intersection. Returns the world-
     *  space `t` (distance along the unit direction ray `dir`) of the
     *  hit, or null if the ray doesn't hit the triangle in front of
     *  the eye. No back-face culling — every face is hittable from
     *  both sides so the player can attack the crystal from any angle
     *  and hit whichever facet is nearest. */
    private fun rayTriangle(
        eye: Vec3, dir: Vec3,
        v0: Vec3, v1: Vec3, v2: Vec3,
    ): Double? {
        val e1x = v1.x - v0.x; val e1y = v1.y - v0.y; val e1z = v1.z - v0.z
        val e2x = v2.x - v0.x; val e2y = v2.y - v0.y; val e2z = v2.z - v0.z
        val hx = dir.y * e2z - dir.z * e2y
        val hy = dir.z * e2x - dir.x * e2z
        val hz = dir.x * e2y - dir.y * e2x
        val a = e1x * hx + e1y * hy + e1z * hz
        if (Math.abs(a) < 1e-9) return null
        val f = 1.0 / a
        val sx = eye.x - v0.x; val sy = eye.y - v0.y; val sz = eye.z - v0.z
        val u = f * (sx * hx + sy * hy + sz * hz)
        if (u < 0.0 || u > 1.0) return null
        val qx = sy * e1z - sz * e1y
        val qy = sz * e1x - sx * e1z
        val qz = sx * e1y - sy * e1x
        val v = f * (dir.x * qx + dir.y * qy + dir.z * qz)
        if (v < 0.0 || u + v > 1.0) return null
        val t = f * (e2x * qx + e2y * qy + e2z * qz)
        return if (t >= 0.0) t else null
    }
}
