package org.shipwrights.enderkinesis.physics

import java.util.concurrent.ConcurrentHashMap
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.blockentity.CrepusculiteLatticeBlockEntity

/**
 * Tracks the catch zone of every currently-loaded crepusculite lattice, keyed by [Level].
 *
 *  ## Why per-tick publishing instead of "iterate BEs and recompute"
 *
 *  The earlier version of the probe iterated BEs and recomputed every zone (8 OBB corner
 *  transforms + ellipsoid inflate) on every entity tick. With 20 lattices in an area and a
 *  modest entity count, that's a few thousand transforms per tick of pure repeated work —
 *  the world AABB of each ship doesn't change tick-to-tick anywhere near fast enough to
 *  justify recomputing it per entity. So the lattice BE now computes its zone *once* per
 *  tick (in [CrepusculiteLatticeBlockEntity.commonTick]) and publishes it here, and the
 *  probe just iterates [zones] — a cheap [ConcurrentHashMap.values] view.
 *
 *  ## Why ConcurrentHashMap (and not synchronized)
 *
 *  Server-thread BE ticks publish; the entity mixin reads from the same thread for server
 *  entities and from the render thread for client entities. CHM gives correct iteration
 *  under concurrent writes (weakly-consistent values()) without forcing us to copy on every
 *  read — for the 100 entities × 20 lattices common case, copy-on-read would dwarf the
 *  actual probe work.
 */
object LatticeRegistry {

    /** Outer key is the [Level] (one server level per dim, one client level per dim);
     *  inner map is per-BE so unregister can target a single lattice cheaply. */
    private val byLevel: ConcurrentHashMap<Level, ConcurrentHashMap<CrepusculiteLatticeBlockEntity, LatticeCatchZone>> =
        ConcurrentHashMap()

    /** Update or insert this lattice's zone for the level. Called once per BE tick. */
    fun publish(level: Level, be: CrepusculiteLatticeBlockEntity, zone: LatticeCatchZone) {
        byLevel.computeIfAbsent(level) { ConcurrentHashMap() }[be] = zone
    }

    /** Drop this lattice from the registry. Called on chunk-unload / block-break (via
     *  [CrepusculiteLatticeBlockEntity.setRemoved]) and on transient invalidation (e.g. the
     *  BE's ship resolved to null this tick). */
    fun unregister(level: Level, be: CrepusculiteLatticeBlockEntity) {
        byLevel[level]?.remove(be)
    }

    /** Live view of every published zone in [level]. Iteration is safe under concurrent
     *  publish/unregister thanks to CHM's weak-consistency semantics — readers see a
     *  snapshot, never a CME. */
    fun zones(level: Level): Collection<LatticeCatchZone> =
        byLevel[level]?.values ?: emptyList()

    /** Like [zones] but also exposes the publishing BE so callers can resolve the lattice's
     *  ship via [be.blockPos] (shipyard coords, the BlockPos VS2's `getShipManagingPos` is
     *  defined on). The zone alone exposes only the inflated world ellipsoid, whose centre is
     *  a world chunk — never a shipyard chunk — so it can't be used directly with
     *  `getShipManagingPos`. Used by the mesh renderer's hull-footprint cutout. */
    fun entries(level: Level): Set<Map.Entry<CrepusculiteLatticeBlockEntity, LatticeCatchZone>> =
        byLevel[level]?.entries ?: emptySet()

    /** First zone in [level] whose inflated ellipsoid contains `(x, y, z)`. Used by ocean
     *  particles at spawn time to look up their host lattice's tint without a per-tick
     *  lookup. Returns null when no lattice covers the point. */
    fun zoneAt(level: Level, x: Double, y: Double, z: Double): LatticeCatchZone? {
        for (zone in zones(level)) {
            val dx = (x - zone.cx) / zone.ax
            val dy = (y - zone.cy) / zone.ay
            val dz = (z - zone.cz) / zone.az
            if (dx * dx + dy * dy + dz * dz <= 1.0) return zone
        }
        return null
    }

    /** The map key is the level. Without this, an old level and its chunks stay in memory. */
    fun dropLevels(clientSide: Boolean) {
        byLevel.keys.removeIf { it.isClientSide == clientSide }
    }
}

/**
 * Cached snapshot of a lattice's catch zone for one tick. All the geometry the probe needs to
 * answer "is entity X in this lattice's water" without re-walking the OBB or talking to VS2:
 *
 *  - **`cx, cy, cz, ax, ay, az`**: ellipsoid centre + extents in world coords, inflated by
 *    `AABB_INFLATE * 0.5 + AABB_MARGIN` from the ship's world AABB (same numbers
 *    [CrepusculiteLatticeBlockEntity.floatEntities] uses, so the probe and the BE's own
 *    iteration always agree on what's "in zone").
 *  - **`waterLineY`**: nominal sea level — the lattice's world Y. The probe checks against
 *    this directly without re-adding the Gerstner wave bump; the BE's tick refines via the
 *    [Effects.CREPUSCULAR_FLOATATION] effect on its next pass.
 *  - **`waveTime, waveIntensity`**: parameters for the Gerstner field at this tick. Used by
 *    the wave-push code in the mixin to nudge entities along the wave's tangential flow.
 *    Server uses the lattice force inducer's live value; client falls back to a default
 *    since the inducer isn't synced.
 */
data class LatticeCatchZone(
    val cx: Double, val cy: Double, val cz: Double,
    val ax: Double, val ay: Double, val az: Double,
    val waterLineY: Double,
    val waveTime: Double,
    val waveIntensity: Double,
    /** Ship yaw (cos/sin) — the ellipse is oriented with the hull, not world-axis-
     *  aligned. Identity for free-world lattices. */
    val yawCos: Double = 1.0,
    val yawSin: Double = 0.0,
    /** Body tint of the wave mesh and the ocean particles. Defaults to the original
     *  ender-purple; updated when a player dyes the lattice. */
    val tintR: Float = DEFAULT_TINT_R,
    val tintG: Float = DEFAULT_TINT_G,
    val tintB: Float = DEFAULT_TINT_B,
    /** True when the lattice has actually been dyed. Drives [tintDeepR]/etc and
     *  [tintFoamR]/etc to derive from the base tint; false leaves the un-dyed look
     *  on the original foam-green / deep-teal accents. */
    val dyed: Boolean = false,
    /** The ship of the lattice. One ship publishes one zone, from its highest lattice. */
    val shipId: Long = -1L,
) {
    /** Ship-local normalized X/Z of a world point (the ellipse's own frame). */
    fun localNx(x: Double, z: Double): Double =
        (yawCos * (x - cx) - yawSin * (z - cz)) / ax
    fun localNz(x: Double, z: Double): Double =
        (yawSin * (x - cx) + yawCos * (z - cz)) / az

    /** World-axis half-extents of the ROTATED ellipse's bounding box. */
    val boundX: Double get() = Math.abs(yawCos) * ax + Math.abs(yawSin) * az
    val boundZ: Double get() = Math.abs(yawSin) * ax + Math.abs(yawCos) * az

    /** Trough / deep-water shade — for a dyed lattice it's the base tint scaled toward
     *  black by [DEEP_FACTOR]; for the un-dyed default we keep the original deep
     *  ender-teal so the stock look isn't changed by adding dye support. */
    val tintDeepR: Float get() = if (dyed) tintR * DEEP_FACTOR else DEFAULT_DEEP_R
    val tintDeepG: Float get() = if (dyed) tintG * DEEP_FACTOR else DEFAULT_DEEP_G
    val tintDeepB: Float get() = if (dyed) tintB * DEEP_FACTOR else DEFAULT_DEEP_B

    /** Crest / foam shade — dyed: base tint lerped toward white by [FOAM_LIGHTEN];
     *  default: the original foam-green. */
    val tintFoamR: Float get() = if (dyed) tintR + (1f - tintR) * FOAM_LIGHTEN else DEFAULT_FOAM_R
    val tintFoamG: Float get() = if (dyed) tintG + (1f - tintG) * FOAM_LIGHTEN else DEFAULT_FOAM_G
    val tintFoamB: Float get() = if (dyed) tintB + (1f - tintB) * FOAM_LIGHTEN else DEFAULT_FOAM_B

    companion object {
        const val DEFAULT_TINT_R: Float = 0.52f
        const val DEFAULT_TINT_G: Float = 0.20f
        const val DEFAULT_TINT_B: Float = 0.66f
        /** Original ender-teal trough. Restored verbatim when the lattice isn't dyed. */
        const val DEFAULT_DEEP_R: Float = 0.0118f
        const val DEFAULT_DEEP_G: Float = 0.1490f
        const val DEFAULT_DEEP_B: Float = 0.1255f
        /** Original foam-green crest. Restored verbatim when the lattice isn't dyed. */
        const val DEFAULT_FOAM_R: Float = 0.30f
        const val DEFAULT_FOAM_G: Float = 1.00f
        const val DEFAULT_FOAM_B: Float = 0.55f

        /** Multiplier applied to the base tint to derive the dyed deep / trough shade. */
        private const val DEEP_FACTOR: Float = 0.25f
        /** Lerp factor toward white for the dyed foam / crest shade. */
        private const val FOAM_LIGHTEN: Float = 0.65f
    }
}
