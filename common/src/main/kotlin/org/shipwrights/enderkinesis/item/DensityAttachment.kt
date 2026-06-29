package org.shipwrights.enderkinesis.item

/** Persistent per-ship Staff-of-Density state.
 *
 *  Stored on a VS2 ServerShip via `saveAttachment(DensityAttachment::class.java, ...)`.
 *  Captures the ship's mass at the moment the staff was FIRST used on it, so subsequent
 *  staff uses scale relative to that fixed reference instead of drifting with each apply.
 *
 *  Why the reference matters: the multiplier is a *ratio*, but VS2 stores mass as an
 *  absolute double that we mutate via `addMassAt`. To go from 1.5x → 1.0x correctly we
 *  need to subtract the previously-added amount; that's why we track `currentMultiplier`
 *  too. The delta we apply each commit is `originalMass * (newMultiplier - currentMultiplier)`.
 *
 *  Block additions/removals between staff uses change the ship's mass independently; the
 *  multiplier still acts on the originally-captured base mass, so the ship's effective
 *  mass after blocks change becomes `currentBlockMass + originalMass*(multiplier - 1)`.
 *  That's the simplest model that preserves user-visible "I set it to 1.5x" semantics. */
class DensityAttachment {
    var originalMass: Double = 0.0
    var currentMultiplier: Double = 1.0
}
