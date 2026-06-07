package org.shipwrights.enderkinesis.physics

import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.PhysShip
import org.valkyrienskies.core.api.ships.ShipPhysicsListener
import org.valkyrienskies.core.api.world.PhysLevel

/**
 * Physics attachment that turns a grabbed ship into a cursor-following
 * spring + quaternion-driven orientation controller. Ported from
 * [VMod's `PhysgunController`](https://github.com/SuperSpaceEye/VMod/blob/1.20.1/common/src/main/kotlin/net/spaceeye/vmod/shipAttachments/PhysgunController.kt)
 * (MIT) — the only known open-source VS2 gravity-gun that handles
 * rotation without the held ship sagging or oscillating.
 *
 * # Why this shape
 *
 * A naïve gravity gun applies a spring force at the grab point and lets
 * the off-axis force induce rotation. This dies in two ways:
 *
 *  1. **Sag.** Pulling on the corner of a heavy ship while it's still
 *     accelerating means the spring fights gravity *and* the ship's
 *     inertia simultaneously — the ship drops below the cursor and
 *     oscillates back up.
 *  2. **Rotation drift.** With torque only from off-axis force,
 *     residual angular velocity from collisions / earlier inputs is
 *     bled off purely by damping — small omega values never fully die.
 *
 * The VMod approach (used here):
 *
 *  - **Position spring applied at the COM**, not at the grab point.
 *    Magnitude is derived from the grab-point → cursor error, but
 *    direction is applied through the COM so it generates *no induced
 *    torque*. This eliminates the position-vs-rotation coupling.
 *  - **Rotation driven by a target quaternion** ([idealRotation]).
 *    Mouse input modifies the target; a PD controller produces torque
 *    to drive the ship there.
 *  - **Inertia-tensor-corrected torque.** The error vector is wrapped
 *    in an `R · I · R⁻¹` sandwich so the angular response is isotropic
 *    in world space — a long thin ship turns as crisply as a cube.
 *  - **Feed-forward sag cancellation ([IDK_CONST]).** When the
 *    rotation PD outputs a non-zero rotDiff, predict how that rotation
 *    will displace the COM-relative grab-point vector and add a force
 *    component to pre-compensate. This is the fix to the sag problem
 *    visible in earlier iterations.
 */
class WyllandTomeForceInducer(
    /** Cursor world-space target the grab point should reach. */
    @Transient var targetX: Double = 0.0,
    @Transient var targetY: Double = 0.0,
    @Transient var targetZ: Double = 0.0,
    /** Grab point in ship-local frame, relative to centre of mass.
     *  Captured at grab time and held constant so the point on the
     *  ship's hull tracks the cursor regardless of ship rotation. */
    @Transient var localOffsetX: Double = 0.0,
    @Transient var localOffsetY: Double = 0.0,
    @Transient var localOffsetZ: Double = 0.0,
    /** Desired world-frame orientation, written by the manager. The PD
     *  controller drives `shipToWorldRotation` toward this each tick. */
    @Transient var idealRotation: Quaterniond = Quaterniond(),
    /** Set true by the manager while the player is grabbing this ship,
     *  false on release. The attachment stays on the ship for cheap
     *  reuse on a future grab. */
    @Transient var active: Boolean = false,
) : ShipPhysicsListener {

    override fun physTick(physShip: PhysShip, physLevel: PhysLevel) {
        if (!active) return
        if (physShip.isStatic || physShip.mass <= 0.0) return

        val mass = physShip.mass
        // Mass-gain ramp — heavier ships get up to (1 + MASS_GAIN_MAX)×
        // the base stiffness with exponential falloff. Keeps small
        // skiffs gentle and giant galleons responsive.
        val powerGain = 1.0 + MASS_GAIN_MAX *
            (1.0 - Math.exp(-mass / MASS_REFERENCE))
        // Linear (position) and angular (rotation) gains are tuned
        // independently — rotation reads "snappier" with stiffer gains,
        // and translation reads "floatier" with looser ones.
        val pConst = POSITION_STIFFNESS * powerGain
        val dConst = POSITION_DAMPING
        val pConstRot = ROTATION_STIFFNESS * powerGain
        val dConstRot = ROTATION_DAMPING
        val iConst = IDK_CONST

        val transform = physShip.transform
        val s2w = transform.shipToWorld
        val com = transform.positionInShip
        val rotation = transform.shipToWorldRotation

        // COM and grab point in world space.
        val comWorld = Vector3d(com.x(), com.y(), com.z())
        s2w.transformPosition(comWorld)
        val grabWorld = Vector3d(
            com.x() + localOffsetX,
            com.y() + localOffsetY,
            com.z() + localOffsetZ,
        )
        s2w.transformPosition(grabWorld)

        // Grab-point → cursor error, scaled by stiffness. Direction is
        // what drives the COM force; magnitude is what determines how
        // fast the ship moves.
        val idealPos = Vector3d(targetX, targetY, targetZ)
        val posDiff = Vector3d(idealPos).sub(grabWorld).mul(pConst)

        // Quaternion error: rotDiff is the (small) rotation needed to
        // fix shipToWorldRotation to idealRotation. The `.invert()`
        // gives the shortest-arc representation when combined with the
        // `if (w > 0) negate` flip below. Format ported verbatim from
        // VMod PhysgunController.kt:164.
        val rotDiff = idealRotation
            .mul(rotation.invert(Quaterniond()), Quaterniond())
            .normalize()
            .invert()
        // Small-angle axis-vector approximation of rotDiff: a unit
        // quaternion (sin(θ/2)·axis, cos(θ/2)) has 2·xyz ≈ θ·axis when
        // θ is small. Multiply by pConst → proportional control.
        val rotDiffVector = Vector3d(
            rotDiff.x * 2.0,
            rotDiff.y * 2.0,
            rotDiff.z * 2.0,
        ).mul(pConstRot)
        // Shortest-arc fix — flip sign when w > 0 to take the short
        // way around the unit-quaternion double cover.
        if (rotDiff.w > 0.0) rotDiffVector.mul(-1.0)
        // PD damping in world-frame angular velocity.
        rotDiffVector.sub(Vector3d(physShip.omega).mul(dConstRot))

        // R · I · R⁻¹ sandwich: treat rotDiffVector as desired angular
        // acceleration in world frame, transform to body frame, scale
        // by body-frame inertia, transform back to world frame. Result
        // is the world-frame torque needed to produce that acceleration
        // regardless of the inertia tensor's shape.
        val inertia = physShip.momentOfInertia
        val torque = Vector3d()
        rotation.transformInverse(rotDiffVector, torque)
        inertia.transform(torque)
        rotation.transform(torque)
        physShip.applyInvariantTorque(torque)

        // iConst feed-forward: predict how the rotation we're about to
        // apply will displace the COM-relative cursor vector, and
        // pre-apply a force that moves the COM to keep the grab point
        // under the cursor. Without this, every rotation tick visibly
        // drags the ship off the cursor before the position spring
        // catches up — that's the "sag" the user was hitting.
        val dir = Vector3d(idealPos).sub(comWorld)
        val rotatedDir = Vector3d()
        rotDiff.transform(dir, rotatedDir)
        val iDiff = rotatedDir.sub(dir).mul(iConst)

        // Force at COM (not at grab point — that's the whole point of
        // this rewrite). Gravity cancellation is unscaled: we always
        // want exactly the weight relief, not a mass-gain-amplified
        // levitation.
        val force = Vector3d(posDiff).add(iDiff)
            .sub(Vector3d(physShip.velocity).mul(dConst))
            .mul(mass)
        force.y += mass * GRAVITY
        physShip.applyInvariantForce(force)
    }

    companion object {
        /** Per-mass linear proportional gain. Higher = snappier
         *  translation response, lower = floatier. */
        const val POSITION_STIFFNESS = 20.0
        /** Per-mass linear derivative gain. Critical at 2·√k ≈ 8.94;
         *  10 sits just past critical so the ship slides crisply to
         *  the cursor with no overshoot. */
        const val POSITION_DAMPING = 10.0
        /** Per-mass angular proportional gain. Tuned separately from
         *  [POSITION_STIFFNESS] so rotation can be made noticeably
         *  snappier without making translation feel twitchy. */
        const val ROTATION_STIFFNESS = 32.0
        /** Per-mass angular derivative gain. Critical at 2·√k ≈ 11.3;
         *  12 is just past critical — rotation comes to rest sharply
         *  with no oscillation. */
        const val ROTATION_DAMPING = 12.0
        /** Feed-forward gain on the rotation-induced COM displacement
         *  (`iConst` / `IDKCONST` in VMod). Larger values more
         *  aggressively cancel sag during rotation; too large causes
         *  whip-around. VMod ships 10.0 by default. */
        const val IDK_CONST = 10.0
        /** VS2 world gravity (m/s²); subtracted from spring acceleration
         *  so the ship hovers freely without the spring fighting weight. */
        const val GRAVITY = 10.0
        /** Asymptotic stiffness multiplier at infinite mass. At m = 0
         *  → 1.0; at m → ∞ → 1 + MASS_GAIN_MAX. */
        const val MASS_GAIN_MAX = 2.0
        /** Characteristic mass scale for the gain ramp (kg). Past about
         *  3 × MASS_REFERENCE the gain is saturated. */
        const val MASS_REFERENCE = 5000.0
    }
}
