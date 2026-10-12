package org.shipwrights.enderkinesis.item

/**
 * Persistent per-ship Staff-of-Density state. VS2 stores mass as an absolute value, so the
 * multiplier is kept here together with the mass that the staff added for it.
 * [DensityManager] corrects [appliedExtraMass] when blocks change the mass of the ship.
 */
class DensityAttachment {
    /** Only read for a ship saved before [appliedExtraMass] existed. */
    var originalMass: Double = 0.0
    var currentMultiplier: Double = 1.0

    /** Mass added at the centre of mass for the multiplier (negative below 1.0x). NaN = old save. */
    var appliedExtraMass: Double = Double.NaN
}
