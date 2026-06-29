package org.shipwrights.enderkinesis.item

import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Geometry helpers for the Staff-of-Aegis shield box.
 *
 *  The box is an oriented bounding box (OBB):
 *   - Centre = player.eye + look × [FORWARD_DISTANCE]
 *   - Local +Z = look direction (the "into the shield" axis)
 *   - Local +X = right vector perpendicular to look in the XZ plane
 *   - Local +Y = up vector orthogonal to both
 *  Dimensions: [WIDTH] × [HEIGHT] × [VISUAL_DEPTH] visually, with a wider
 *  [DETECTION_DEPTH] in physics so fast projectiles don't tunnel through. */
object AegisBox {

    /** Centre distance from the player's eye, in blocks. */
    const val FORWARD_DISTANCE: Double = 4.0
    /** Box dimensions (X / Y / Z in box-local space). */
    const val WIDTH: Double = 7.0
    const val HEIGHT: Double = 5.0
    /** Slim slice the player sees as a shield. */
    const val VISUAL_DEPTH: Double = 1.0
    /** Thicker volume used for entity / projectile intersection so fast
     *  travellers don't sweep through the 1-block visual in a single tick. */
    const val DETECTION_DEPTH: Double = 2.0

    private const val HALF_W: Double = WIDTH / 2.0
    private const val HALF_H: Double = HEIGHT / 2.0
    private const val HALF_VD: Double = VISUAL_DEPTH / 2.0
    private const val HALF_DD: Double = DETECTION_DEPTH / 2.0

    /** Compact descriptor of one tick's OBB, suitable for caching across
     *  intersection tests and (optionally) sharing with the renderer. */
    class Frame(
        val center: Vec3,
        /** Unit vector along the shield's local +X (right). */
        val axisX: Vec3,
        /** Unit vector along the shield's local +Y (up). */
        val axisY: Vec3,
        /** Unit vector along the shield's local +Z (forward, into-shield). */
        val axisZ: Vec3,
    ) {
        /** World-space AABB encompassing the OBB at detection depth — used
         *  for broad-phase entity / ship queries. */
        val worldAabb: AABB by lazy { computeWorldAabb(this) }

        /** True if `worldPoint` lies inside the visual-depth OBB. Used by the
         *  particle spawner and the "is the player INSIDE the shield" check
         *  if we ever need one. */
        fun containsVisual(worldPoint: Vec3): Boolean = containsLocal(worldPoint, HALF_VD)
        /** True if `worldPoint` lies inside the (thicker) detection OBB. */
        fun containsDetection(worldPoint: Vec3): Boolean = containsLocal(worldPoint, HALF_DD)

        private fun containsLocal(worldPoint: Vec3, halfDepth: Double): Boolean {
            val rel = worldPoint.subtract(center)
            val lx = rel.dot(axisX)
            val ly = rel.dot(axisY)
            val lz = rel.dot(axisZ)
            return lx in -HALF_W..HALF_W && ly in -HALF_H..HALF_H && lz in -halfDepth..halfDepth
        }

        /** True if the segment from `from` to `to` intersects the detection
         *  OBB. Cheap conservative check: sample the segment endpoints +
         *  midpoint. Catches typical projectile-tunnelling cases (one tick of
         *  travel) without a full slab-intersect implementation. */
        fun segmentIntersects(from: Vec3, to: Vec3): Boolean {
            if (containsDetection(from)) return true
            if (containsDetection(to)) return true
            val mid = from.add(to).scale(0.5)
            if (containsDetection(mid)) return true
            // Two more samples at quarter points — keeps the test cheap but
            // gives some safety against the segment passing through corners
            // that the simple 3-sample probe would miss.
            val q1 = from.add(to.subtract(from).scale(0.25))
            val q3 = from.add(to.subtract(from).scale(0.75))
            return containsDetection(q1) || containsDetection(q3)
        }

        /** Iterate the 8 OBB corner positions in world space. Used by the
         *  wireframe renderer (connect adjacent corners with [edges]) and by
         *  [worldAabb]. The ordering is `(sx, sy, sz)` for sx,sy,sz ∈ {-1,1}
         *  packed into a 3-bit index; same order as [edges] expects. */
        fun corner(i: Int): Vec3 {
            val sx = if (i and 1 != 0) HALF_W else -HALF_W
            val sy = if (i and 2 != 0) HALF_H else -HALF_H
            val sz = if (i and 4 != 0) HALF_VD else -HALF_VD
            return center
                .add(axisX.scale(sx))
                .add(axisY.scale(sy))
                .add(axisZ.scale(sz))
        }
    }

    /** Build the [Frame] for the given player. Use [partialTick] = 1f for
     *  tick-end state (server side, hit-tests) and the render context's
     *  current partialTick for client rendering — that interpolates the eye
     *  position and look direction between previous and current ticks so the
     *  shield doesn't stutter against the player's smooth render motion.
     *
     *  The right vector is derived from the **body yaw** (not the look's
     *  horizontal component). When the player stares straight up or down the
     *  look's XZ component vanishes; deriving right from look would either
     *  snap to a fixed world axis or rotate wildly across the threshold. Yaw
     *  is well-defined at every pitch, so the right vector stays continuous
     *  through the whole pitch range. */
    fun forPlayer(player: Player, partialTick: Float = 1f): Frame {
        val eye = player.getEyePosition(partialTick)
        val look = player.getViewVector(partialTick).normalize()
        val yawDeg = Mth.lerp(partialTick, player.yRotO, player.yRot).toDouble()
        val yawRad = Math.toRadians(yawDeg)
        // MC convention: yaw = 0 → body forward is +Z. Rotating that 90°
        // clockwise about world +Y gives the body-right vector below.
        val right = Vec3(Math.cos(yawRad), 0.0, Math.sin(yawRad))
        val up = look.cross(right).normalize()
        val center = eye.add(look.scale(FORWARD_DISTANCE))
        return Frame(center = center, axisX = right, axisY = up, axisZ = look)
    }

    /** Pairs of corner-indices that define the 12 edges of the OBB. Uses the
     *  same 3-bit (sx, sy, sz) encoding [Frame.corner] does. */
    val edges: IntArray = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7,   // bottom-front, bottom-back, top-front, top-back X edges
        0, 2, 1, 3, 4, 6, 5, 7,   // front-left, front-right, back-left, back-right Y edges
        0, 4, 1, 5, 2, 6, 3, 7,   // four Z (depth) edges
    )

    /** First-hit of a ray against the visual-depth OBB. [distance] is the
     *  parametric t along the ray (= world distance since `dir` is unit);
     *  [normal] is the **outward** face normal in world space at the entry
     *  point — what the reflection formula `D − 2(D·N)N` needs. */
    data class IntersectResult(val distance: Double, val normal: Vec3)

    /** Standard slab-method ray-OBB intersection in the box's local frame.
     *  Treats the box at its **visual depth** ([VISUAL_DEPTH]) so the
     *  beam reflects off the same volume the user sees, not the wider
     *  [DETECTION_DEPTH] used for entity broad-phase.
     *
     *  Returns null if the ray misses, points away from the box, or
     *  starts inside the box (a beam fired from inside its own shield is
     *  ill-defined and we'd rather not reflect immediately). */
    fun rayIntersect(origin: Vec3, dir: Vec3, frame: Frame): IntersectResult? {
        val rel = origin.subtract(frame.center)
        val ox = rel.dot(frame.axisX); val oy = rel.dot(frame.axisY); val oz = rel.dot(frame.axisZ)
        val dx = dir.dot(frame.axisX); val dy = dir.dot(frame.axisY); val dz = dir.dot(frame.axisZ)

        var tMin = -Double.MAX_VALUE
        var tMax = Double.MAX_VALUE
        var entryAxis = -1
        var entrySign = 0

        // Inlined per-axis slab clip — avoids loop / array allocation in
        // what's a hot path on every Sundering server tick.
        run {
            val h = HALF_W; val o = ox; val d = dx
            if (Math.abs(d) < 1e-9) {
                if (Math.abs(o) > h) return null
            } else {
                val t1 = (-h - o) / d; val t2 = (h - o) / d
                val tEnter = if (t1 < t2) t1 else t2
                val tExit = if (t1 < t2) t2 else t1
                if (tEnter > tMin) {
                    tMin = tEnter
                    entryAxis = 0
                    entrySign = if (d > 0) -1 else +1
                }
                if (tExit < tMax) tMax = tExit
                if (tMin > tMax) return null
            }
        }
        run {
            val h = HALF_H; val o = oy; val d = dy
            if (Math.abs(d) < 1e-9) {
                if (Math.abs(o) > h) return null
            } else {
                val t1 = (-h - o) / d; val t2 = (h - o) / d
                val tEnter = if (t1 < t2) t1 else t2
                val tExit = if (t1 < t2) t2 else t1
                if (tEnter > tMin) {
                    tMin = tEnter
                    entryAxis = 1
                    entrySign = if (d > 0) -1 else +1
                }
                if (tExit < tMax) tMax = tExit
                if (tMin > tMax) return null
            }
        }
        run {
            val h = HALF_VD; val o = oz; val d = dz
            if (Math.abs(d) < 1e-9) {
                if (Math.abs(o) > h) return null
            } else {
                val t1 = (-h - o) / d; val t2 = (h - o) / d
                val tEnter = if (t1 < t2) t1 else t2
                val tExit = if (t1 < t2) t2 else t1
                if (tEnter > tMin) {
                    tMin = tEnter
                    entryAxis = 2
                    entrySign = if (d > 0) -1 else +1
                }
                if (tExit < tMax) tMax = tExit
                if (tMin > tMax) return null
            }
        }

        if (tMax < 0.0) return null                            // box behind ray
        if (tMin < 0.0) return null                            // ray starts inside the OBB
        if (entryAxis == -1) return null                       // all axes near-parallel (rare)

        val axis = when (entryAxis) {
            0 -> frame.axisX
            1 -> frame.axisY
            else -> frame.axisZ
        }
        val normal = axis.scale(entrySign.toDouble())
        return IntersectResult(tMin, normal)
    }

    private fun computeWorldAabb(frame: Frame): AABB {
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY; var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY; var maxZ = Double.NEGATIVE_INFINITY
        for (i in 0 until 8) {
            val sx = if (i and 1 != 0) HALF_W else -HALF_W
            val sy = if (i and 2 != 0) HALF_H else -HALF_H
            // Detection depth (wider) for broad-phase reach.
            val sz = if (i and 4 != 0) HALF_DD else -HALF_DD
            val p = frame.center
                .add(frame.axisX.scale(sx))
                .add(frame.axisY.scale(sy))
                .add(frame.axisZ.scale(sz))
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
            if (p.z < minZ) minZ = p.z; if (p.z > maxZ) maxZ = p.z
        }
        return AABB(minX, minY, minZ, maxX, maxY, maxZ)
    }
}
