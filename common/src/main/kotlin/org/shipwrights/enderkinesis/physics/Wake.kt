package org.shipwrights.enderkinesis.physics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Snapshot of a ship's wake-relevant state for the lattice virtual-ocean wake field.
 *
 * **Deterministic.** [Wake.heightAt] is a pure function of `(sample point, t, sources)`.
 * Both client and server construct their own [WakeSource] lists from VS2's already-synced
 * ship state (`transform.positionInWorld`, `velocity`, `shipAABB`), so identical inputs
 * yield identical outputs without any new lattice-side networking.
 *
 *  - [centerX] / [centerZ] : ship centre in world XZ.
 *  - [dirX] / [dirZ] : unit forward-velocity direction (XZ plane).
 *  - [speed] : velocity magnitude in **blocks/tick** (convert from VS2's blocks/sec via ×1/20).
 *  - [halfLength] : along-velocity hull half-extent (bow at +halfLength, stern at −halfLength).
 *  - [halfWidth] : perpendicular-velocity hull half-extent.
 */
data class WakeSource(
    val centerX: Double,
    val centerZ: Double,
    val dirX: Double,
    val dirZ: Double,
    val speed: Double,
    val halfLength: Double,
    val halfWidth: Double,
)

/**
 * Ship wave field from two analytical components:
 *
 *  - **Bow — Bernoulli stagnation rise**: `η_max = V² / (2g)` at the stagnation point,
 *    Gaussian falloff around it (closed-form needs Michell/Havelock thin-ship integrals).
 *    Quadratic in V — slow ships have no bump.
 *  - **Wake — Kelvin far-field**: wedge half-angle `arctan(1/√8) ≈ 19.47°`, independent
 *    of speed. Transverse waves with phase `kT·r·cos²(φ)` (kT = g/V²), divergent waves
 *    with phase `kT·r·cos²(θ_D) + π/4` where `θ_D = arctan(1/√2) ≈ 35.26°`. Amplitude
 *    falls as `V²/√r` plus exponential cutoff to terminate at finite distance.
 *
 * Not modelled: hull-shape effects (Michell/Havelock), Airy caustic at the wedge edge,
 * finite-depth dispersion (Boussinesq), acceleration/turn history (current-position
 * approximation). Pure function — no PRNG, no allocations; coarse `r² > maxR²` rejection
 * at the top skips out-of-range sources cheaply.
 */
object Wake {

    /** Effective gravity in blocks/tick². Vanilla MC gravity is ~0.08 (≈ 9.8 m/s² scaled
     *  for the 1-block / 1-second physics step). At `V = 0.3` blocks/tick this gives
     *  `λ_T = 2π·V²/g ≈ 7 blocks` transverse wavelength — readable on the 0.75-block mesh.
     *  Treat as a *tunable*, not a physical constant: lower g → longer wakes; higher g →
     *  tighter wakes. */
    private const val G: Double = 0.08

    /** Min forward speed below which a ship contributes no wake at all. Avoids
     *  divide-by-zero in the Kelvin `g/V²` and prevents twitchy faint bumps from
     *  numerical-noise-level drift. */
    private const val MIN_SPEED: Double = 0.02

    /** Offset ahead of the bow tip along the velocity axis where the stagnation point
     *  sits. ~1 block forward — empirical fit; small variations don't read visually. */
    private const val BOW_OFFSET: Double = 1.0

    /** Gaussian σ controlling bow-bump spatial spread (blocks). */
    private const val BOW_SIGMA: Double = 2.5
    private const val BOW_TWO_SIGMA_SQ: Double = 2.0 * BOW_SIGMA * BOW_SIGMA
    private const val BOW_CUTOFF_SQ: Double = (4.0 * BOW_SIGMA) * (4.0 * BOW_SIGMA)

    /** Distance behind stern at which the wake terminates regardless of decay. */
    private const val KELVIN_MAX_DIST: Double = 30.0

    /** Min r for Kelvin evaluation — avoids singularity at the source point. */
    private const val KELVIN_MIN_R: Double = 0.5

    /** Kelvin wedge half-angle: `arctan(1/√8) ≈ 19.47°`. Universal, ship-speed-independent. */
    private const val KELVIN_WEDGE_HALF_ANGLE: Double = 0.3398369094541219

    /** `cos²(θ_D)` where `θ_D = arctan(1/√2) ≈ 35.26°` is the optimal divergent-wave
     *  angle. The closed form is exactly `2/3`. */
    private const val KELVIN_DIVERGENT_COS_SQ: Double = 2.0 / 3.0

    /** Amplitude coefficient on the `V²/√r` Kelvin term. Combines the Bernoulli stagnation
     *  reference height with the stationary-phase asymptotic constant; tuned visually. */
    private const val KELVIN_AMP: Double = 6.0

    /** Distance behind stern at which Kelvin amplitude has dropped to `e^-1`. */
    private const val KELVIN_FADE: Double = 15.0
    private const val KELVIN_FADE_INV: Double = 1.0 / KELVIN_FADE

    /** Outer reach for the per-source rejection test at the top of [contributeFrom]. */
    private const val MAX_RADIUS: Double = KELVIN_MAX_DIST + 8.0

    /** Sum the wake-height contributions from every source at world `(x, z)` and game
     *  time `t` (ticks). Returns `0.0` for an empty source list. The `t` parameter is
     *  retained in the API for forward compatibility but unused — the Kelvin solution is
     *  steady in the ship's frame, so the wake pattern translates with the ship via the
     *  source's centre position rather than via a time-dependent phase. */
    @Suppress("UNUSED_PARAMETER")
    fun heightAt(x: Double, z: Double, t: Double, sources: List<WakeSource>): Double {
        if (sources.isEmpty()) return 0.0
        var sum = 0.0
        for (i in sources.indices) {
            sum += contributeFrom(x, z, sources[i])
        }
        return sum
    }

    private fun contributeFrom(x: Double, z: Double, src: WakeSource): Double {
        if (src.speed < MIN_SPEED) return 0.0

        // Sample relative to ship centre, world XZ.
        val rx = x - src.centerX
        val rz = z - src.centerZ
        val r2World = rx * rx + rz * rz
        val maxR = src.halfLength + MAX_RADIUS
        if (r2World > maxR * maxR) return 0.0

        // Ship-local frame: u = along velocity (+ ahead of centre), v = perpendicular.
        val u = rx * src.dirX + rz * src.dirZ
        val v = -rx * src.dirZ + rz * src.dirX
        val absV = abs(v)
        val V = src.speed
        val V2 = V * V

        var dy = 0.0

        val bowU = u - (src.halfLength + BOW_OFFSET)
        val bowDistSq = bowU * bowU + v * v
        if (bowDistSq < BOW_CUTOFF_SQ) {
            val stagnationRise = V2 / (2.0 * G)
            dy += stagnationRise * exp(-bowDistSq / BOW_TWO_SIGMA_SQ)
        }

        // sternU = positive distance behind the stern in ship-local along-velocity.
        val sternU = -(u + src.halfLength)
        if (sternU > 0.0 && sternU < KELVIN_MAX_DIST) {
            val r = sqrt(sternU * sternU + v * v)
            if (r > KELVIN_MIN_R) {
                // Observation angle from the wake axis (the ship's track).
                val phi = atan2(absV, sternU)
                if (phi < KELVIN_WEDGE_HALF_ANGLE) {
                    // Transverse wavenumber base: kT = g / V² (Kelvin's dispersion relation
                    // for a wave whose phase speed matches the ship's V — so the wave
                    // appears stationary in the ship's frame).
                    val kT = G / V2

                    val cosPhi = cos(phi)
                    // Transverse system: stationary-phase angle ≈ φ for points near axis.
                    val phaseT = kT * r * cosPhi * cosPhi
                    // Divergent system: stationary-phase angle ≈ θ_D = arctan(1/√2),
                    // cos²(θ_D) = 2/3 exactly. The +π/4 is Fresnel correction from
                    // saddle-point evaluation.
                    val phaseD = kT * r * KELVIN_DIVERGENT_COS_SQ + PI * 0.25

                    // Asymptotic stationary-phase amplitude: V²/√r times exponential decay.
                    val amp = KELVIN_AMP * V2 / sqrt(r) * exp(-r * KELVIN_FADE_INV)

                    // Smooth wedge gate. The strict Kelvin solution has an Airy-function
                    // caustic at the wedge edge (amplitude actually *increases* near φ_wedge
                    // before cutting off); we use a quadratic fade as a phenomenological
                    // stand-in.
                    val wedgeFraction = phi / KELVIN_WEDGE_HALF_ANGLE
                    val wedgeFade = 1.0 - wedgeFraction * wedgeFraction

                    dy += amp * (cos(phaseT) + cos(phaseD)) * wedgeFade
                }
            }
        }

        return dy
    }
}
