package org.shipwrights.enderkinesis.physics

import org.joml.Vector3d
import kotlin.math.cos
import kotlin.math.sin

/**
 * The single source of truth for the virtual ocean's Gerstner wave field.
 *
 * Both the visual particles (client) and the buoyancy (server physics) call into this with the
 * same parameters and the same clock — Minecraft's world game time (`level.getGameTime()`), which
 * the client tracks in lockstep with the server — so the surface the player sees and the surface
 * that pushes the ship are identical. No mod packets needed: the only inputs are world position
 * and game time, which are already shared state.
 */
object GerstnerOcean {
    private const val TWO_PI = Math.PI * 2.0

    /** [dirX, dirZ, amplitude, wavelength, speed (rad/tick), steepness]. */
    // Summed Gerstner waves, the way real-time ocean games do it: a wide directional spread (so
    // it isn't grid-like or unidirectional), a steeply decaying amplitude spectrum (one dominant
    // swell carries the height → tall peaks, not many equal bumps), and high steepness so crests
    // pinch into sharp peaks with flat troughs. Per-wave steepness·amp·k is kept so the total
    // stays < 1 (no self-intersection). Amplitudes still sum to 3.0.
    //
    // [dirX, dirZ, amplitude, wavelength, speed (rad/tick), steepness], dirs ≈ unit vectors at
    // 18°, -35°, 62°, -78°, 130°, -160° — non-harmonic angles & wavelengths to avoid a pattern.
    private val WAVES = arrayOf(
        doubleArrayOf(0.951, 0.309, 1.30, 21.0, 0.040, 0.60),
        doubleArrayOf(0.819, -0.574, 0.85, 13.0, 0.060, 0.56),
        doubleArrayOf(0.469, 0.883, 0.45, 8.0, 0.085, 0.49),
        doubleArrayOf(0.208, -0.978, 0.22, 5.0, 0.120, 0.42),
        doubleArrayOf(-0.643, 0.766, 0.12, 3.1, 0.170, 0.39),
        doubleArrayOf(-0.940, -0.342, 0.06, 1.9, 0.230, 0.35),
    )

    /** Sum of wave amplitudes — the max surface rise/fall at full intensity (3 blocks). */
    val maxAmplitude: Double = WAVES.sumOf { it[2] }

    /** Number of summed wave components — caller-side arrays for [precomputeSample] must size to this. */
    val waveCount: Int get() = WAVES.size

    /**
     * Depth (wave plane above the world heightmap, in blocks) at which waves reach full [maxAmplitude].
     * Tuned shallow→calm: linear, so a <10-block "ocean" is barely rippling (~0.5 blocks) and only
     * very deep water (~60+ blocks) builds up to the full 3-block swell.
     */
    private const val DEPTH_FOR_FULL = 60.0

    /** A shallow ocean still has a faint ripple rather than a dead-flat plane. */
    private const val MIN_INTENSITY = 0.1

    private fun phase(w: DoubleArray, x: Double, z: Double, t: Double): Double =
        (TWO_PI / w[3]) * (w[0] * x + w[1] * z) + t * w[4]

    /**
     * Wave intensity (0..1) from how deep the virtual ocean is: the gap between the wave plane
     * [planeY] and the world heightmap [terrainY] beneath it. Deep ocean → full waves, shallow →
     * small. Identical formula on client and server so the visual and physics stay coherent.
     */
    fun intensityFor(planeY: Double, terrainY: Double): Double =
        ((planeY - terrainY) / DEPTH_FOR_FULL).coerceIn(MIN_INTENSITY, 1.0)

    /**
     * Unit surface normal at world (x, z) — the analytic gradient of [heightAt]. On a flat sea
     * this is (0,1,0); on a wave face it tilts down-slope, so a splash deflected along it sprays
     * off the wave correctly instead of always straight up.
     */
    fun normalAt(x: Double, z: Double, t: Double, intensity: Double, out: Vector3d) {
        var dhx = 0.0
        var dhz = 0.0
        for (w in WAVES) {
            val k = TWO_PI / w[3]
            val c = cos(phase(w, x, z, t)) * (w[2] * intensity) * k
            dhx += c * w[0]
            dhz += c * w[1]
        }
        out.set(-dhx, 1.0, -dhz).normalize()
    }

    /**
     * Velocity (blocks/tick) of the water parcel at world (x, z) — the time derivative of the
     * trochoidal [displace]. Lets a splash be a real collision against *moving* water rather than
     * a static surface.
     */
    fun velocityAt(x: Double, z: Double, t: Double, intensity: Double, out: Vector3d) {
        var vx = 0.0; var vy = 0.0; var vz = 0.0
        for (w in WAVES) {
            val p = phase(w, x, z, t)
            val a = w[2] * intensity
            val s = w[4]                       // dphase/dt
            vy += cos(p) * a * s               // d/dt of a·sin(phase)
            val ss = sin(p) * a * s
            vx += -w[5] * ss * w[0]            // d/dt of Q·a·dir·cos(phase)
            vz += -w[5] * ss * w[1]
        }
        out.set(vx, vy, vz)
    }

    /** Surface height offset at world (x, z), game time [t] (ticks), scaled by [intensity]. */
    fun heightAt(x: Double, z: Double, t: Double, intensity: Double): Double {
        var dy = 0.0
        for (w in WAVES) dy += w[2] * intensity * sin(phase(w, x, z, t))
        return dy
    }

    /**
     * Precompute the per-particle constants used by [displaceFromSample]. Replaced the older
     * `displace(baseX, baseZ, t, intensity, out)` which recomputed the position-dependent half of
     * each wave's phase every tick — for hundreds of long-lived ocean particles that was a 30%+
     * chunk of their per-tick cost. The phase splits cleanly
     * into a position-dependent term (`k · (dirX·baseX + dirZ·baseZ)`) and a time-dependent term
     * (`t · speed`); only the latter changes between ticks, so the former is computed once and
     * stamped into [basePhase]. [amp] folds the wave amplitude with the fluid intensity (also
     * stable for a given particle's lifetime) so the per-tick path skips a multiply per wave.
     *
     * Both arrays must be sized to [waveCount]. The visual particles' tick loop runs once per
     * particle per render frame and is one of the hottest paths in the client when a lattice is
     * active — every operation lifted out of it removes 6× that operation per particle per tick.
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
     * Fast trochoidal displacement at game time [t] using precomputed per-particle [basePhase] +
     * [amp] arrays from [precomputeSample]. Result is written into [out] as (dx, dy, dz).
     *
     * Beyond the precompute savings, this also evaluates `cos(p)` once per wave (the original
     * [displace] called it twice — for `dx` and `dz` — costing 6 extra cos calls per particle per
     * tick) and pulls each wave row's invariant fields into locals so the JIT sees a tight loop.
     */
    fun displaceFromSample(
        t: Double, basePhase: DoubleArray, amp: DoubleArray, out: DoubleArray,
    ) {
        var dx = 0.0; var dy = 0.0; var dz = 0.0
        for (i in WAVES.indices) {
            val w = WAVES[i]
            val p = basePhase[i] + t * w[4]
            val a = amp[i]
            val qa = w[5] * a
            val cp = cos(p)
            dx += qa * w[0] * cp
            dz += qa * w[1] * cp
            dy += a * sin(p)
        }
        out[0] = dx; out[1] = dy; out[2] = dz
    }
}
