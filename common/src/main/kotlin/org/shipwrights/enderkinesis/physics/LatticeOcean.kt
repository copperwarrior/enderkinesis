package org.shipwrights.enderkinesis.physics

import net.minecraft.world.level.Level
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.WeakHashMap
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The one definition of where the water of a lattice ocean is, for all game logic: the
 * fluid probe and the lattice block entity. It uses the terms of the drawn ocean: the shore
 * of the wave mesh, the wave surface with wake and shore taper, and the straight-wall depth
 * of the volume. The hull test is separate, in [LatticeHullVolume].
 */
object LatticeOcean {

    /** Shore alpha at which a column counts as water: the middle of the shore falloff. */
    private const val SHORE_SOLID = 0.5f

    /**
     * World Y of the wave surface at a column, or NaN where the ocean of [zone] has no water.
     * This is the top wave layer of the mesh; the phantom layer is not included.
     */
    fun surfaceY(zone: LatticeCatchZone, x: Double, z: Double, wake: List<WakeSource>): Double {
        val nx = zone.localNx(x, z)
        val nz = zone.localNz(x, z)
        val rRaw = sqrt(nx * nx + nz * nz)
        if (rRaw >= 1.0) return Double.NaN
        var taper = 1.0
        if (rRaw > LatticeShore.interiorSafeRadius(zone.ax, zone.az)) {
            val theta = atan2(nz * zone.az, nx * zone.ax)
            val rDir = LatticeShore.rDirAt(theta, zone.ax, zone.az)
            val circumference = Math.PI * (zone.ax + zone.az)
            val rWarped = rRaw / LatticeShore.rimWarpAt(theta, rDir, zone.waveTime, circumference)
            if (LatticeShore.shoreEdgeAt(rWarped, rDir) < SHORE_SOLID) {
                return Double.NaN
            }
            taper = LatticeShore.amplitudeTaper(rWarped)
        }
        val wave = GerstnerOcean.heightAt(x, z, zone.waveTime, zone.waveIntensity) +
            Wake.heightAt(x, z, zone.waveTime, wake)
        return zone.waterLineY + wave * taper
    }

    /** The deepest level of the water: the lowest point of the zone ellipsoid, as in the drawn volume. */
    fun bottomY(zone: LatticeCatchZone): Double = zone.cy - zone.ay

    /** True when the point is in the water of [zone]. This does not include the hull test. */
    fun contains(zone: LatticeCatchZone, x: Double, y: Double, z: Double, wake: List<WakeSource>): Boolean =
        y >= bottomY(zone) && belowSurface(zone, x, y, z, wake)

    /** True when the column has water and the point is below its surface. */
    fun belowSurface(zone: LatticeCatchZone, x: Double, y: Double, z: Double, wake: List<WakeSource>): Boolean {
        // Cheap reject before the shore and wave math. The margin covers the largest wake.
        if (y >= zone.waterLineY + maxWaveHeight(zone) + WAKE_MARGIN) return false
        return y < surfaceY(zone, x, z, wake)       // false for NaN
    }

    private const val WAKE_MARGIN = 12.0

    // The Stokes term of the wave sum grows with the square of the intensity.
    private fun maxWaveHeight(zone: LatticeCatchZone): Double =
        GerstnerOcean.maxAmplitude * zone.waveIntensity * Math.max(1.0, zone.waveIntensity)

    private class WakeCache {
        var gameTime = Long.MIN_VALUE
        var sources: List<WakeSource> = emptyList()
    }

    // One for each side: in singleplayer the server thread and the client thread ask at the same time.
    private val clientWake = WeakHashMap<Level, WakeCache>()
    private val serverWake = WeakHashMap<Level, WakeCache>()

    /** The wake sources of the moving ships in [level]. One build for each game tick. Call from the level's own thread. */
    fun wakeSources(level: Level): List<WakeSource> {
        val cache = (if (level.isClientSide) clientWake else serverWake).getOrPut(level) { WakeCache() }
        val now = level.gameTime
        if (cache.gameTime == now) return cache.sources
        val dim = level.dimensionId
        var out: ArrayList<WakeSource>? = null
        for (ship in level.shipObjectWorld.loadedShips) {
            if (ship.chunkClaimDimension != dim) continue
            val aabb = ship.shipAABB ?: continue
            val source = Wake.sourceOf(aabb, ship.velocity, ship.transform.worldToShip, ship.transform.positionInWorld)
                ?: continue
            if (out == null) out = ArrayList()
            out.add(source)
        }
        cache.gameTime = now
        cache.sources = out ?: emptyList()
        return cache.sources
    }

    fun dropLevels(clientSide: Boolean) {
        (if (clientSide) clientWake else serverWake).clear()
    }
}
