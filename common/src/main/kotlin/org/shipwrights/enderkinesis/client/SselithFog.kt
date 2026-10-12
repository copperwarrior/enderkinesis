package org.shipwrights.enderkinesis.client

import dev.architectury.networking.NetworkManager
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.command.SselithFogDebugCommands
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.sselith.SselithEclipse

/** Sselith fog tint and fade range, shared by the terrain fog and the sky, with the live
 *  overrides that `/sselithfog` and `/sselithfogrange` send. Overrides are client memory
 *  only; a relog clears them. */
object SselithFog {
    private const val DEFAULT_R = 1.00f
    private const val DEFAULT_G = 0.88f
    private const val DEFAULT_B = 0.40f
    private const val DEFAULT_START = 1.0f
    private const val DEFAULT_END = 220.0f

    @Volatile
    private var overrideRgb: Int = SselithFogDebugCommands.NO_OVERRIDE

    // NaN means no override.
    @Volatile
    private var overrideStart: Float = Float.NaN

    @Volatile
    private var overrideEnd: Float = Float.NaN

    @JvmStatic
    fun red(): Float = channel(16, DEFAULT_R)

    @JvmStatic
    fun green(): Float = channel(8, DEFAULT_G)

    @JvmStatic
    fun blue(): Float = channel(0, DEFAULT_B)

    /** Distance where the fog begins. */
    @JvmStatic
    fun start(): Float = overrideStart.let { if (it.isNaN()) DEFAULT_START else it }

    /** Distance where the fog is solid. */
    @JvmStatic
    fun end(): Float = overrideEnd.let { if (it.isNaN()) DEFAULT_END else it }

    /** Scale for the fog colour at altitude [y]. */
    @JvmStatic
    fun dim(level: Level, partialTick: Float, y: Double): Float {
        // Fractional gameTime + partialTick → per-frame smoothness across the eclipse ramps.
        val eclipse = SselithEclipse.intensity(level.gameTime.toDouble() + partialTick)
        return (1.0f - eclipse) * SselithRepertoryLighting.darknessDim(partialTick) *
            SselithRepertory.fogHeightFraction(y)
    }

    private fun channel(shift: Int, default: Float): Float {
        val rgb = overrideRgb
        return if (rgb == SselithFogDebugCommands.NO_OVERRIDE) default else ((rgb shr shift) and 0xFF) / 255f
    }

    fun onDisconnect() {
        overrideRgb = SselithFogDebugCommands.NO_OVERRIDE
        overrideStart = Float.NaN
        overrideEnd = Float.NaN
    }

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SselithFogDebugCommands.COLOR_PACKET) { buf, ctx ->
            val rgb = buf.readInt()
            ctx.queue { overrideRgb = rgb }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, SselithFogDebugCommands.RANGE_PACKET) { buf, ctx ->
            val start = buf.readFloat()
            val end = buf.readFloat()
            ctx.queue {
                overrideStart = start
                overrideEnd = end
            }
        }
    }
}
