package org.shipwrights.enderkinesis.client

import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.sselith.SselithEclipse

/** Sselith fog tint and fade range, shared by the terrain fog and the sky. */
object SselithFog {
    private const val RED = 1.00f
    private const val GREEN = 0.88f
    private const val BLUE = 0.40f
    private const val START = 1.0f
    private const val END = 220.0f

    @JvmStatic
    fun red(): Float = RED

    @JvmStatic
    fun green(): Float = GREEN

    @JvmStatic
    fun blue(): Float = BLUE

    /** Distance where the fog begins. */
    @JvmStatic
    fun start(): Float = START

    /** Distance where the fog is solid. */
    @JvmStatic
    fun end(): Float = END

    /** Scale for the fog colour at altitude [y]. */
    @JvmStatic
    fun dim(level: Level, partialTick: Float, y: Double): Float {
        // Fractional gameTime + partialTick → per-frame smoothness across the eclipse ramps.
        val eclipse = SselithEclipse.intensity(level.gameTime.toDouble() + partialTick)
        return (1.0f - eclipse) * SselithRepertoryLighting.darknessDim(partialTick) *
            SselithRepertory.fogHeightFraction(y)
    }
}
