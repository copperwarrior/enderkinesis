package org.shipwrights.enderkinesis.util

/** Mass (kg) at which [massFalloff] halves a mass-scaled force. */
const val FALLOFF_MASS_KG: Double = 20_000.0

/** Multiplier for a mass-scaled force, so heavier targets get less acceleration. */
fun massFalloff(mass: Double, reference: Double = FALLOFF_MASS_KG): Double = 1.0 / (1.0 + mass / reference)
