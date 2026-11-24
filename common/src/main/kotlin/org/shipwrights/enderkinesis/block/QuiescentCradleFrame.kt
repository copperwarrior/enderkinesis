package org.shipwrights.enderkinesis.block

import net.minecraft.core.Direction
import org.joml.Matrix3d
import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.Vector3dc
import org.joml.primitives.AABBic
import kotlin.math.PI
import kotlin.math.round

/** All points are in the cradle frame: world coordinates, or shipyard coordinates when the cradle is on a ship. */
object QuiescentCradleFrame {

    const val PITCH = 0
    const val YAW = 1
    const val ROLL = 2
    const val RINGS = 3

    /** Half sizes (x, y, z) of the ship marker at the orrery centre, in the roll ring's frame. */
    val MARKER_HALF = doubleArrayOf(0.025, 0.015, 0.04)

    /** Control orrery ring radii, in blocks, pitch (outermost) to roll. */
    val ORRERY_RADII = doubleArrayOf(0.19, 0.16, 0.13)

    // Model pixels with the control face to the north: the `place_rings_here` centre, and the top U gap.
    private const val ORRERY_X = 8.0 / 16.0
    private const val ORRERY_Y = 12.0 / 16.0
    private const val ORRERY_Z = 1.0 / 16.0
    private const val GAP_X = 8.0 / 16.0
    private const val GAP_Y = 16.0 / 16.0
    private const val GAP_Z = 10.5 / 16.0

    /** The pitch circle line sits this far above the floor of the U gap. */
    private const val CIRCLE_LIFT = 0.2

    private const val ROLL_MARGIN = 1.5
    private const val YAW_MARGIN = 3.0
    private const val PITCH_MARGIN = 4.5

    /** Big circle radius for [ring] around a ship whose bounding sphere has radius [sphere]. */
    fun circleRadius(ring: Int, sphere: Double): Double {
        val margin = when (ring) {
            PITCH -> PITCH_MARGIN
            YAW -> YAW_MARGIN
            else -> ROLL_MARGIN
        }
        return round((sphere + margin) * 2.0) / 2.0
    }

    /** The bounding-sphere radius whose pitch circle is [pitchRadius]. */
    fun sphereForPitch(pitchRadius: Double): Double = pitchRadius - PITCH_MARGIN

    /** The control face looks toward [facing]; the model is authored facing north. */
    private fun turnsOf(facing: Direction): Int = when (facing) {
        Direction.EAST -> 1
        Direction.SOUTH -> 2
        Direction.WEST -> 3
        else -> 0
    }

    /** Model point to block-local point, turned about the block centre as the blockstate turns the model. */
    internal fun modelToBlock(facing: Direction, x: Double, y: Double, z: Double, dest: Vector3d): Vector3d {
        var px = x
        var pz = z
        repeat(turnsOf(facing)) {
            val nx = 1.0 - pz
            pz = px
            px = nx
        }
        return dest.set(px, y, pz)
    }

    /** Block-local centre of the control orrery. */
    fun orreryCentre(facing: Direction, dest: Vector3d): Vector3d = modelToBlock(facing, ORRERY_X, ORRERY_Y, ORRERY_Z, dest)

    /** Block-local centre of the big gimbal, for a pitch circle of radius [pitchRadius]. */
    fun holdCentre(facing: Direction, pitchRadius: Double, dest: Vector3d): Vector3d =
        modelToBlock(facing, GAP_X, GAP_Y, GAP_Z, dest).add(0.0, CIRCLE_LIFT + pitchRadius, 0.0)

    /** The blockstate turn as a rotation: model axes to cradle-frame axes. */
    fun facingRotation(facing: Direction, dest: Quaterniond): Quaterniond =
        dest.rotationY(-turnsOf(facing) * PI / 2.0)

    /**
     * The frame of [ring] in the gimbal, pitch → yaw → roll. Ring k turns about its own model axis (X, Y, Z), so
     * the frame of ring k is F·Rx(pitch)…, up to and including ring k. [angles] is pitch, yaw, roll in radians.
     */
    fun ringFrame(ring: Int, facing: Direction, angles: DoubleArray, dest: Quaterniond): Quaterniond {
        facingRotation(facing, dest).rotateX(angles[PITCH])
        if (ring >= YAW) dest.rotateY(angles[YAW])
        if (ring >= ROLL) dest.rotateZ(angles[ROLL])
        return dest
    }

    /** The whole gimbal turn in the cradle frame: F·Rx·Ry·Rz·F⁻¹. [scratch] must not be [dest]. */
    fun gimbal(facing: Direction, angles: DoubleArray, dest: Quaterniond, scratch: Quaterniond): Quaterniond {
        ringFrame(ROLL, facing, angles, dest)
        return dest.mul(facingRotation(facing, scratch).conjugate())
    }

    /**
     * In-plane unit axes (u, v) of [ring] in its own frame, with u × v = the ring axis, so a positive angle from u
     * toward v is a positive turn about the axis. Returns the axis index of u; v is the next index.
     */
    fun planeU(ring: Int): Int = (ring + 1) % RINGS

    fun planeV(ring: Int): Int = (ring + 2) % RINGS

    /** Unit vector along model axis [index] (0 X, 1 Y, 2 Z). */
    fun axis(index: Int, dest: Vector3d): Vector3d = when (index) {
        0 -> dest.set(1.0, 0.0, 0.0)
        1 -> dest.set(0.0, 1.0, 0.0)
        else -> dest.set(0.0, 0.0, 1.0)
    }

    /** The 24 turns that map the axes onto ±X, ±Y, ±Z: the turns of a cube. Built once; never changed. */
    private val AXIS_ALIGNED: List<Quaterniondc> = buildList {
        val permutations = arrayOf(intArrayOf(0, 1, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(0, 2, 1), intArrayOf(2, 1, 0), intArrayOf(1, 0, 2))
        for ((index, p) in permutations.withIndex()) {
            val parity = if (index < EVEN_PERMUTATIONS) 1 else -1
            for (signs in 0 until SIGN_SETS) {
                val sx = if (signs and 1 == 0) 1.0 else -1.0
                val sy = if (signs and 2 == 0) 1.0 else -1.0
                val sz = if (signs and 4 == 0) 1.0 else -1.0
                if (parity * sx * sy * sz < 0.0) continue
                val m = Matrix3d().zero()
                m.setColumn(0, column(p[0], sx))
                m.setColumn(1, column(p[1], sy))
                m.setColumn(2, column(p[2], sz))
                add(Quaterniond().setFromNormalized(m))
            }
        }
        check(size == CUBE_TURNS) { "Expected $CUBE_TURNS cube turns, built $size" }
    }

    private const val EVEN_PERMUTATIONS = 3
    private const val SIGN_SETS = 8
    private const val CUBE_TURNS = 24

    private fun column(axis: Int, sign: Double): Vector3d = axis(axis, Vector3d()).mul(sign)

    /** Game thread. The cube turn nearest [orientation] (smallest turn between them), into [dest]. */
    fun nearestAxisAligned(orientation: Quaterniondc, dest: Quaterniond): Quaterniond {
        var best = AXIS_ALIGNED[0]
        var bestDot = -1.0
        for (candidate in AXIS_ALIGNED) {
            // |q1·q2| = cos(θ/2): the largest is the smallest turn, either sign of the double cover.
            val dot = Math.abs(candidate.dot(orientation))
            if (dot > bestDot) {
                bestDot = dot
                best = candidate
            }
        }
        return dest.set(best)
    }

    // VS-core adds 1 to the maxima of shipAABB, so max is exclusive: the box runs from min to max, no +1.
    /** Centre of a ship's block box ([AABBic] from `shipAABB`), in shipyard coordinates. */
    fun boxCentre(aabb: AABBic, dest: Vector3d): Vector3d = dest.set(
        (aabb.minX() + aabb.maxX()) * 0.5,
        (aabb.minY() + aabb.maxY()) * 0.5,
        (aabb.minZ() + aabb.maxZ()) * 0.5,
    )

    /** Radius of the sphere about [centre] that holds the whole ship box [aabb]. */
    fun sphereAround(aabb: AABBic, centre: Vector3dc): Double {
        val dx = Math.max(Math.abs(aabb.minX() - centre.x()), Math.abs(aabb.maxX() - centre.x()))
        val dy = Math.max(Math.abs(aabb.minY() - centre.y()), Math.abs(aabb.maxY() - centre.y()))
        val dz = Math.max(Math.abs(aabb.minZ() - centre.z()), Math.abs(aabb.maxZ() - centre.z()))
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Wraps [angle] into [-π, π). */
    fun wrap(angle: Double): Double {
        val turns = Math.floor((angle + PI) / (2.0 * PI))
        return angle - turns * 2.0 * PI
    }
}
