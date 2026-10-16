package org.shipwrights.enderkinesis.util

import net.minecraft.util.RandomSource
import org.joml.Vector3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Random points in a cone, relative to its apex. Not thread-safe: give each thread its own instance. */
class ConeSampler {
    private val axis = Vector3d()
    private val e1 = Vector3d()
    private val e2 = Vector3d()

    /** The axis must be a unit vector. */
    fun setAxis(x: Double, y: Double, z: Double): ConeSampler {
        axis.set(x, y, z)
        // A helper near the axis gives a near-zero cross product.
        if (abs(y) < 0.95) e1.set(0.0, 1.0, 0.0) else e1.set(1.0, 0.0, 0.0)
        axis.cross(e1, e1).normalize()
        axis.cross(e1, e2).normalize()
        return this
    }

    fun sample(rand: RandomSource, length: Double, halfAngleTan: Double, out: Vector3d): Vector3d {
        val depth = rand.nextDouble() * length
        // Without the sqrt, points collect on the axis and the cone shape does not show.
        val radial = depth * halfAngleTan * sqrt(rand.nextDouble())
        val angle = rand.nextDouble() * 2.0 * PI
        return out.set(axis).mul(depth)
            .fma(cos(angle) * radial, e1)
            .fma(sin(angle) * radial, e2)
    }
}
