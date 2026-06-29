package org.shipwrights.enderkinesis.physics

import org.joml.Vector3d
import kotlin.math.cos
import kotlin.math.sin

/**
 * Single source of truth for the ocean's wave field — both rendering and physics call in
 * with the same `level.gameTime` clock (shared client/server state, pure function of
 * position + time + compile-time constants → no sync packets needed).
 *
 * 16-wave Gerstner spectrum (3 swell λ=13–32, 5 chop λ=1.8–8, 8 capillary λ=0.27–1.4),
 * directions spread across the compass. Per-wave `steepness × amplitude × wavenumber` sum
 * stays under 1.0 even after [CHOPPY], so the trochoidal displacement never self-intersects.
 *
 * Each wave's height carries a Stokes 2nd-order term `−½·a²·k·cos(2φ)`: same `2a`
 * peak-to-trough budget, but shifts the profile toward the classic Stokes shape (narrow
 * tall peaks, broad flat troughs) without burning steepness budget. `cos(2φ)` uses the
 * double-angle identity `1 − 2·sin²(φ)`, so [heightAt] needs only one trig call per wave.
 */
object GerstnerOcean {
    private const val TWO_PI = Math.PI * 2.0

    /** [dirX, dirZ, amplitude, wavelength, speed (rad/tick), steepness]. */
    // Hand-shaped 16-wave spectrum. Direction vectors are unit-length. Wavelengths follow
    // a roughly geometric series with non-harmonic spacing (32, 19, 13, 8, 5, 3.3, 2.4, 1.8,
    // 1.4, 1.05, 0.85, 0.65, 0.50, 0.40, 0.32, 0.27) so wave components don't lock into
    // resonance patterns. Amplitudes decay log-style; speeds increase with frequency
    // (k·omega² ≈ const, the deep-water dispersion relation, hand-tuned not strict).
    private val WAVES = arrayOf(
        // Q (steepness) cranked toward the self-intersection limit. Stokes 2nd-order is a
        // gentle correction; the *real* peak-sharpening lever is Q (the trochoidal
        // displacement amplitude). Q=0.60 produced sinusoidal-looking peaks; Q≈0.90 forms
        // near-cycloid pinched crests with narrow, near-cusped tops and broad flat troughs.
        // The Σ(CHOPPY·Q·a·k) budget below sits just past 1.0, so the most aligned crests
        // occasionally show a slight fold — that reads as "breaking surf" rather than as a
        // glitch. Dial Qs back to ~0.80 if folds become objectionable.
        doubleArrayOf( 0.965,  0.259, 1.10, 32.0, 0.030, 0.92),  // dir 15°
        doubleArrayOf(-0.259,  0.965, 0.75, 19.0, 0.050, 0.88),  // dir 105°
        doubleArrayOf( 0.574, -0.819, 0.50, 13.0, 0.072, 0.82),  // dir -55°

        // Boosted amplitudes and Qs; mid-frequency contribution is what the new mesh
        // resolution (0.75-block grid) most distinctly resolves as visible chop.
        doubleArrayOf( 0.788,  0.616, 0.36,  8.0, 0.095, 0.60),  // dir 38°
        doubleArrayOf( 0.208, -0.978, 0.24,  5.0, 0.130, 0.55),  // dir -78°
        doubleArrayOf(-0.966,  0.259, 0.16,  3.3, 0.175, 0.50),  // dir 165°
        doubleArrayOf(-0.574, -0.819, 0.11,  2.4, 0.210, 0.45),  // dir -125°
        doubleArrayOf(-0.087,  0.996, 0.07,  1.8, 0.250, 0.40),  // dir 95°

        // Half of these are below the mesh's Nyquist limit; kept for buoyancy/splash physics
        // (which sample heightAt at arbitrary world points), but visually only the top 4
        // contribute much under the new mesh. Qs lowered since they eat the global budget.
        doubleArrayOf( 0.927,  0.375, 0.040, 1.40, 0.300, 0.16),  // dir 22°
        doubleArrayOf(-0.866, -0.500, 0.030, 1.05, 0.350, 0.14),  // dir -150°
        doubleArrayOf( 0.259,  0.966, 0.022, 0.85, 0.410, 0.12),  // dir 75°
        doubleArrayOf( 0.707, -0.707, 0.015, 0.65, 0.480, 0.10),  // dir -45°
        doubleArrayOf(-0.643,  0.766, 0.010, 0.50, 0.540, 0.08),  // dir 130°
        doubleArrayOf(-0.174, -0.985, 0.007, 0.40, 0.620, 0.06),  // dir -100°
        doubleArrayOf( 0.643,  0.766, 0.005, 0.32, 0.710, 0.04),  // dir 50°
        doubleArrayOf( 0.906, -0.423, 0.004, 0.27, 0.790, 0.04),  // dir -25°
    )

    /** Trochoidal-displacement multiplier on the wave's `dx`/`dz` term. With the new
     *  high-Q values the per-wave Q is already eating most of the global budget, so the
     *  choppy boost is held at 1.0 (pure unmodified Gerstner trochoidal). Pushing it
     *  above 1 here would multiply the already-saturated Σ(Q·a·k) sum, producing
     *  pervasive self-intersection artefacts instead of the occasional dramatic crest
     *  cusp the new Q values give. */
    private const val CHOPPY: Double = 1.00

    /** Max surface rise from rest at full intensity. Sum of 1st-order amplitudes plus the
     *  Stokes-correction lift at peaks (`+½·a²·k` per wave at `sin(φ) = 1`). Used by the
     *  splash-band pre-filter and by visual fades — anywhere "could the surface be at
     *  this Y" needs an upper bound. */
    val maxAmplitude: Double = computeMaxAmp(0, WAVES.size)

    /** Same metric as [maxAmplitude] but restricted to the [SWELL_COUNT] dominant swell
     *  components — the slow, long-wavelength waves at the top of the [WAVES] array. Used
     *  to normalise a "swell-only" `t01` for the **colour gradient** without the chop /
     *  capillary detail driving per-vertex colour into the muddy-mid-tone region. */
    val swellMaxAmplitude: Double = computeMaxAmp(0, SWELL_COUNT)

    private fun computeMaxAmp(fromInclusive: Int, toExclusive: Int): Double {
        var sum = 0.0
        for (i in fromInclusive until toExclusive) {
            val w = WAVES[i]
            val a = w[2]
            val k = TWO_PI / w[3]
            sum += a + 0.5 * a * a * k       // 1st-order amp + Stokes lift at peak
        }
        return sum
    }

    val waveCount: Int get() = WAVES.size

    /** Number of dominant swell components at the head of [WAVES] — the ones [swellHeightAt]
     *  sums. The remaining waves (mid chop + capillaries) drive geometric detail but are
     *  excluded from the smoothed colour-sampling pass. */
    private const val SWELL_COUNT: Int = 3

    private const val DEPTH_FOR_FULL = 60.0
    private const val MIN_INTENSITY = 0.1

    private fun phase(w: DoubleArray, x: Double, z: Double, t: Double): Double =
        (TWO_PI / w[3]) * (w[0] * x + w[1] * z) + t * w[4]

    /** Wave intensity (0..1) from depth — deep water → full waves, shallow → small.
     *  Identical formula client + server so the visible field and the physics match. */
    fun intensityFor(planeY: Double, terrainY: Double): Double =
        ((planeY - terrainY) / DEPTH_FOR_FULL).coerceIn(MIN_INTENSITY, 1.0)

    /** Unit surface normal at world `(x, z)` — analytic gradient of [heightAt] including
     *  Stokes contribution. On a flat sea this is `(0, 1, 0)`; on a wave face it tilts
     *  down-slope (sharper tilt now near crests because Stokes adds curvature there). */
    fun normalAt(x: Double, z: Double, t: Double, intensity: Double, out: Vector3d) {
        var dhx = 0.0
        var dhz = 0.0
        for (w in WAVES) {
            val k = TWO_PI / w[3]
            val a = w[2] * intensity
            val p = phase(w, x, z, t)
            val sp = sin(p)
            val cp = cos(p)
            // 1st-order: d/dx[a·sin(p)] = a·k·cos(p)·dirX
            val c1 = cp * a * k
            dhx += c1 * w[0]
            dhz += c1 * w[1]
            // 2nd-order Stokes: d/dx[-½·a²·k·cos(2p)] = a²·k²·sin(2p)·dirX
            // sin(2p) = 2·sin(p)·cos(p) — reuse already-computed sp,cp.
            val s2 = (2.0 * sp * cp) * a * a * k * k
            dhx += s2 * w[0]
            dhz += s2 * w[1]
        }
        out.set(-dhx, 1.0, -dhz).normalize()
    }

    /** Velocity of the water parcel at world `(x, z)` (blocks/tick) — time derivative of
     *  [displaceFromSample], including Stokes contribution to the vertical term and the
     *  choppy multiplier on the trochoidal horizontal terms. Used by the foam-spray's
     *  wind-coupling and by the splash-emission velocity calc. */
    fun velocityAt(x: Double, z: Double, t: Double, intensity: Double, out: Vector3d) {
        var vx = 0.0; var vy = 0.0; var vz = 0.0
        for (w in WAVES) {
            val k = TWO_PI / w[3]
            val a = w[2] * intensity
            val s = w[4]                                  // dphase/dt
            val Q = w[5]
            val p = phase(w, x, z, t)
            val sp = sin(p)
            val cp = cos(p)
            // 1st-order height time-derivative: d/dt[a·sin(p)] = a·cos(p)·s
            vy += cp * a * s
            // 2nd-order Stokes: d/dt[-½·a²·k·cos(2p)] = a²·k·sin(2p)·s
            vy += a * a * k * (2.0 * sp * cp) * s
            // Trochoidal x/z velocity with choppy boost:
            // d/dt[CHOPPY·Q·a·dir·cos(p)] = -CHOPPY·Q·a·dir·sin(p)·s
            val ss = sp * a * s * CHOPPY
            vx += -Q * ss * w[0]
            vz += -Q * ss * w[1]
        }
        out.set(vx, vy, vz)
    }

    /** Surface height offset at world `(x, z)` and game time `t` (ticks), scaled by
     *  [intensity]. Includes 1st-order Gerstner + 2nd-order Stokes correction (sharper
     *  peaks, flatter troughs). */
    fun heightAt(x: Double, z: Double, t: Double, intensity: Double): Double {
        var dy = 0.0
        for (w in WAVES) {
            val k = TWO_PI / w[3]
            val a = w[2] * intensity
            val p = phase(w, x, z, t)
            val sp = sin(p)
            // 1st-order
            dy += a * sp
            // 2nd-order Stokes: cos(2p) = 1 − 2·sin²(p) (no extra cos call)
            dy -= 0.5 * a * a * k * (1.0 - 2.0 * sp * sp)
        }
        return dy
    }

    /** Same as [heightAt] but restricted to the [SWELL_COUNT] dominant swell components.
     *  Used by the mesh renderer to derive a **smooth colour field**: by sampling only the
     *  long-wavelength waves, adjacent mesh vertices land on the same slow curve and end up
     *  with similar `t01` values → adjacent-vertex colour interpolation across mesh quads
     *  reads as a gradient instead of as muddy mid-tone smears.
     *
     *  Geometry still uses the full [heightAt] — only the *colour-driving* height is smoothed.
     *  Cost: 3 of the 16 trig calls in [heightAt], so cheaper than the full call. */
    fun swellHeightAt(x: Double, z: Double, t: Double, intensity: Double): Double {
        var dy = 0.0
        for (i in 0 until SWELL_COUNT) {
            val w = WAVES[i]
            val k = TWO_PI / w[3]
            val a = w[2] * intensity
            val p = phase(w, x, z, t)
            val sp = sin(p)
            dy += a * sp
            dy -= 0.5 * a * a * k * (1.0 - 2.0 * sp * sp)
        }
        return dy
    }

    /**
     * Precompute the per-sample constants used by [displaceFromSample]. Same purpose as
     * before: position-dependent terms baked once at construction so per-tick stays a tight
     * sin/cos sum. Arrays must be sized to [waveCount] = 16.
     */
    fun precomputeSample(
        baseX: Double, baseZ: Double, intensity: Double,
        basePhase: DoubleArray, amp: DoubleArray,
    ) {
        for (i in WAVES.indices) {
            val w = WAVES[i]
            basePhase[i] = (TWO_PI / w[3]) * (w[0] * baseX + w[1] * baseZ)
            amp[i] = w[2] * intensity
        }
    }

    /**
     * Fast trochoidal displacement at game time [t] using precomputed [basePhase] / [amp]
     * arrays. Writes `(dx, dy, dz)` into [out].
     *
     * Includes 1st-order Gerstner + 2nd-order Stokes correction (height term only) +
     * [CHOPPY] horizontal multiplier. The result is consistent with [heightAt],
     * [normalAt], and [velocityAt] so a long-lived particle riding [displaceFromSample]
     * stays in phase with the per-frame surface samples and the splash simulation.
     */
    fun displaceFromSample(
        t: Double, basePhase: DoubleArray, amp: DoubleArray, out: DoubleArray,
    ) {
        var dx = 0.0; var dy = 0.0; var dz = 0.0
        for (i in WAVES.indices) {
            val w = WAVES[i]
            val p = basePhase[i] + t * w[4]
            val a = amp[i]
            val k = TWO_PI / w[3]
            val qa = w[5] * a * CHOPPY                    // choppy-boosted trochoidal scale
            val sp = sin(p)
            val cp = cos(p)
            // Trochoidal x/z
            dx += qa * w[0] * cp
            dz += qa * w[1] * cp
            // Height: 1st-order + 2nd-order Stokes (cos(2p) via double-angle of sp)
            dy += a * sp - 0.5 * a * a * k * (1.0 - 2.0 * sp * sp)
        }
        out[0] = dx; out[1] = dy; out[2] = dz
    }
}
