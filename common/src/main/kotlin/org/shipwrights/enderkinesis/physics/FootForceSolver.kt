package org.shipwrights.enderkinesis.physics

/**
 * Splits one body wrench into foot forces by weighted damped least squares, with
 * feet that only push (an active set) and a friction cone. Virtual model control
 * as on MIT Cheetah 3: the legs are the actuators of the body controller.
 * Physics-thread only; it keeps all of its scratch.
 */
internal class FootForceSolver {

    /** Foot forces in world space, `[i * 3 + axis]`. Zero for feet out of the solve. */
    val force = DoubleArray(FEET * 3)

    private val r = DoubleArray(FEET * 3)
    private val weight = DoubleArray(FEET)
    private val active = BooleanArray(FEET)
    private val wrench = DoubleArray(6)
    private val m = DoubleArray(36)
    private val y = DoubleArray(6)
    private val a = DoubleArray(18)

    /** [weight] in 0..1 is the foot's load ramp; 0 keeps the foot out of the solve.
     *  The offset is from the centre of mass to the foot, in world space. */
    fun setFoot(leg: Int, rx: Double, ry: Double, rz: Double, weight: Double) {
        r[leg * 3] = rx; r[leg * 3 + 1] = ry; r[leg * 3 + 2] = rz
        this.weight[leg] = weight
    }

    /** [unilateral] false lets the feet pull and slide: climbing feet hold on. */
    @Suppress("LongParameterList") // One wrench, one contact normal; boxing them costs allocation per frame.
    fun solve(
        fx: Double, fy: Double, fz: Double,
        tx: Double, ty: Double, tz: Double,
        nx: Double, ny: Double, nz: Double,
        unilateral: Boolean,
        friction: Double,
    ) {
        wrench[0] = fx; wrench[1] = fy; wrench[2] = fz
        // Torque rows in Newtons, so one damping value suits both halves.
        wrench[3] = tx / LEVER; wrench[4] = ty / LEVER; wrench[5] = tz / LEVER
        for (i in 0 until FEET) active[i] = weight[i] > 0.0
        // Each pass drops at least one pulling foot, so FEET + 1 passes always settle.
        @Suppress("UnusedPrivateProperty") // A bounded loop that breaks early; detekt reads the counter as a property.
        for (pass in 0..FEET) {
            solveActive()
            if (!unilateral || !dropPullingFeet(nx, ny, nz)) break
        }
        for (i in 0 until FEET) {
            if (!active[i]) force.fill(0.0, i * 3, i * 3 + 3)
            else if (unilateral) clampToCone(i, nx, ny, nz, friction)
        }
    }

    /** f_i = w_i · A_iᵀ (Σ w_j A_j A_jᵀ + λ²I)⁻¹ · wrench. */
    private fun solveActive() {
        m.fill(0.0)
        for (d in 0 until 6) m[d * 6 + d] = DAMPING
        for (i in 0 until FEET) {
            if (!active[i]) continue
            fillA(i)
            for (row in 0 until 6) {
                for (col in 0..row) {
                    val dot = a[row * 3] * a[col * 3] + a[row * 3 + 1] * a[col * 3 + 1] + a[row * 3 + 2] * a[col * 3 + 2]
                    m[row * 6 + col] += weight[i] * dot
                }
            }
        }
        choleskySolve()
        for (i in 0 until FEET) {
            if (!active[i]) continue
            fillA(i)
            for (axis in 0 until 3) {
                var sum = 0.0
                for (row in 0 until 6) sum += a[row * 3 + axis] * y[row]
                force[i * 3 + axis] = weight[i] * sum
            }
        }
    }

    /** A_i = [I; [r_i]× / LEVER], row-major 6×3: column k is the wrench of a unit force on axis k. */
    private fun fillA(leg: Int) {
        val rx = r[leg * 3] / LEVER
        val ry = r[leg * 3 + 1] / LEVER
        val rz = r[leg * 3 + 2] / LEVER
        a.fill(0.0)
        a[0] = 1.0; a[4] = 1.0; a[8] = 1.0
        a[9] = 0.0; a[10] = -rz; a[11] = ry
        a[12] = rz; a[13] = 0.0; a[14] = -rx
        a[15] = -ry; a[16] = rx; a[17] = 0.0
    }

    /** In place on the lower triangle of [m]; [m] is SPD because DAMPING > 0. */
    private fun choleskySolve() {
        for (j in 0 until 6) {
            var diag = m[j * 6 + j]
            for (k in 0 until j) diag -= m[j * 6 + k] * m[j * 6 + k]
            val l = Math.sqrt(diag)
            m[j * 6 + j] = l
            for (i in j + 1 until 6) {
                var s = m[i * 6 + j]
                for (k in 0 until j) s -= m[i * 6 + k] * m[j * 6 + k]
                m[i * 6 + j] = s / l
            }
        }
        for (i in 0 until 6) {
            var s = wrench[i]
            for (k in 0 until i) s -= m[i * 6 + k] * y[k]
            y[i] = s / m[i * 6 + i]
        }
        for (i in 5 downTo 0) {
            var s = y[i]
            for (k in i + 1 until 6) s -= m[k * 6 + i] * y[k]
            y[i] = s / m[i * 6 + i]
        }
    }

    private fun dropPullingFeet(nx: Double, ny: Double, nz: Double): Boolean {
        var dropped = false
        for (i in 0 until FEET) {
            if (active[i] && normalOf(i, nx, ny, nz) < 0.0) {
                active[i] = false
                dropped = true
            }
        }
        return dropped
    }

    private fun clampToCone(leg: Int, nx: Double, ny: Double, nz: Double, friction: Double) {
        val fn = normalOf(leg, nx, ny, nz).coerceAtLeast(0.0)
        val tx = force[leg * 3] - nx * fn
        val ty = force[leg * 3 + 1] - ny * fn
        val tz = force[leg * 3 + 2] - nz * fn
        val tLen = Math.sqrt(tx * tx + ty * ty + tz * tz)
        val scale = if (tLen > friction * fn) friction * fn / tLen else 1.0
        force[leg * 3] = nx * fn + tx * scale
        force[leg * 3 + 1] = ny * fn + ty * scale
        force[leg * 3 + 2] = nz * fn + tz * scale
    }

    private fun normalOf(leg: Int, nx: Double, ny: Double, nz: Double): Double =
        force[leg * 3] * nx + force[leg * 3 + 1] * ny + force[leg * 3 + 2] * nz

    companion object {
        const val FEET = 4

        /** Torque-row scale (m), about the hip-to-foot reach. */
        private const val LEVER = 0.6

        /** λ². Two feet cannot make torque about the line between them; this keeps
         *  near-collinear stances from asking for huge opposed forces. */
        private const val DAMPING = 0.02
    }
}
