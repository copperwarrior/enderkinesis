package org.shipwrights.enderkinesis.item

import net.minecraft.resources.ResourceLocation
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry mapping each tome (by its `TOME_KIND` [ResourceLocation]) to the accent colour its
 * orb-network beams should carry. The client renderer pulls the colour off here when building
 * a [org.shipwrights.enderkinesis.client.BeamPath] so 20% of the beam's glyphs render in the
 * tome's signature hue (the rest stay the default white wash).
 *
 * Each tome registers its own entry — keeps the colour next to the tome that owns it, instead
 * of forcing a central enum that has to be updated every time a new tome ships. Call
 * [register] from a module init that runs on both Fabric and Forge (mod-common `init`).
 */
object TomeBeamPalette {

    /** Per-glyph probability of using the registered accent colour. 0.20 = "20% of the beam". */
    const val ACCENT_CHANCE: Double = 0.20

    private val colors = ConcurrentHashMap<ResourceLocation, Int>()

    /** Register the accent colour ([0xRRGGBB]-packed) for [kind]. Idempotent — last write wins. */
    fun register(kind: ResourceLocation, color: Int) {
        colors[kind] = color
    }

    /** Look up the accent colour for [kind], or null if the kind isn't registered. */
    fun colorFor(kind: ResourceLocation?): Int? = kind?.let { colors[it] }
}
