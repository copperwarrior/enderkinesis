package org.shipwrights.enderkinesis.spell

import net.minecraft.world.phys.Vec3

/** Segment-to-segment geometry for beam crossings and loops. */
object BeamGeometry {

    /** The closest points between segments `p0→p1` and `q0→q1`: [s] and [t] are their
     *  fractions along each, [a] and [b] the points, [distance] the gap between them. */
    class Closest(val s: Double, val t: Double, val a: Vec3, val b: Vec3) {
        val distance: Double get() = a.distanceTo(b)
        val midpoint: Vec3 get() = a.add(b).scale(0.5)
    }

    /** Closest points between two segments (Ericson, *Real-Time Collision Detection* 5.1.9). */
    fun closest(p0: Vec3, p1: Vec3, q0: Vec3, q1: Vec3): Closest {
        val d1 = p1.subtract(p0)
        val d2 = q1.subtract(q0)
        val r = p0.subtract(q0)
        val a = d1.dot(d1)
        val e = d2.dot(d2)
        val f = d2.dot(r)
        var s: Double
        var t: Double
        if (a <= EPS && e <= EPS) {
            s = 0.0; t = 0.0
        } else if (a <= EPS) {
            s = 0.0; t = (f / e).coerceIn(0.0, 1.0)
        } else {
            val c = d1.dot(r)
            if (e <= EPS) {
                t = 0.0; s = (-c / a).coerceIn(0.0, 1.0)
            } else {
                val b = d1.dot(d2)
                val denom = a * e - b * b
                s = if (denom > EPS * a * e) ((b * f - c * e) / denom).coerceIn(0.0, 1.0) else {
                    // Parallel: the middle of where they overlap, so beams meeting head-on
                    // meet between their casters rather than at one caster's hand.
                    val q0s = -c / a
                    val q1s = (b - c) / a
                    val lo = maxOf(0.0, minOf(q0s, q1s))
                    val hi = minOf(1.0, maxOf(q0s, q1s))
                    if (lo <= hi) (lo + hi) * 0.5 else (if (q0s < 0.0 && q1s < 0.0) 0.0 else 1.0)
                }
                t = (b * s + f) / e
                if (t < 0.0) {
                    t = 0.0; s = (-c / a).coerceIn(0.0, 1.0)
                } else if (t > 1.0) {
                    t = 1.0; s = ((b - c) / a).coerceIn(0.0, 1.0)
                }
            }
        }
        return Closest(s, t, p0.add(d1.scale(s)), q0.add(d2.scale(t)))
    }

    /** Whether unit directions [a] and [b] point within [HEAD_ON_DEGREES] of opposite. */
    fun headOn(a: Vec3, b: Vec3): Boolean = a.dot(b) <= -HEAD_ON_COS

    const val HEAD_ON_DEGREES = 10.0
    private val HEAD_ON_COS = Math.cos(Math.toRadians(HEAD_ON_DEGREES))
    private const val EPS = 1.0e-9
}
