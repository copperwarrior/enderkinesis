package org.shipwrights.enderkinesis.dimension

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.DimensionType
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Identity for **Sselith's Repertory** — an infinite dimension of stacked bookshelf boxes
 * framed by deepslate-brick columns, sliced by a 2-wide polished-deepslate cross at the
 * world axis. Sun fixed at 34.5° pitch/yaw/roll, dark orange sky with deep blue drifting
 * stars. World is void below — no floor, no bedrock.
 *
 *  - Chunk generation: [SselithRepertoryChunkGenerator]
 *  - Sky rendering: `SselithRepertorySky` (client-side, via Mixin into LevelRenderer.renderSky)
 */
object SselithRepertory {
    val ID: ResourceLocation = EnderkinesisMod.id("sselith_repertory")
    val LEVEL_KEY: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, ID)
    val DIMENSION_TYPE_KEY: ResourceKey<DimensionType> = ResourceKey.create(Registries.DIMENSION_TYPE, ID)

    /** |y| above which the fog density bottoms out at [FOG_MIN_DENSITY]. */
    const val FOG_FALLOFF_Y = 128.0

    /** Lower bound on fog density even at extreme altitudes — keeps a thin haze visible so
     *  the dimension never looks like pure overworld clarity. 0 would mean no fog at all
     *  past [FOG_FALLOFF_Y], which the player rejected as too clear. */
    const val FOG_MIN_DENSITY = 0.25f

    /** Blocks from each world axis to the edge of the band between the library quadrants. */
    const val LIBRARY_QUADRANT_SHIFT = 5

    @JvmStatic
    fun isAxialBand(x: Int, z: Int): Boolean =
        x in -LIBRARY_QUADRANT_SHIFT..LIBRARY_QUADRANT_SHIFT || z in -LIBRARY_QUADRANT_SHIFT..LIBRARY_QUADRANT_SHIFT

    /**
     * Fog density at altitude [y]: 1.0 at y=0, linearly falling to [FOG_MIN_DENSITY] at
     * |y| ≥ [FOG_FALLOFF_Y]. Used by both
     * [org.shipwrights.enderkinesis.mixin.FogRendererSselithRepertoryMixin] (for
     * terrain/world fog alpha) and `SselithRepertorySky.renderSky` (for the sky's
     * clear-colour blend), so the two stay locked in step.
     */
    @JvmStatic
    fun fogDensityAt(y: Double): Float = 1.0f - fogHeightFraction(y) * (1.0f - FOG_MIN_DENSITY)

    /** 0 at y=0, rising linearly to 1 at |y| ≥ [FOG_FALLOFF_Y]. Fog colour scales by it, so the
     *  fog is black at y=0 on the same curve the density follows. */
    @JvmStatic
    fun fogHeightFraction(y: Double): Float {
        val t = Math.abs(y) / FOG_FALLOFF_Y
        return if (t > 1.0) 1.0f else t.toFloat()
    }
}
