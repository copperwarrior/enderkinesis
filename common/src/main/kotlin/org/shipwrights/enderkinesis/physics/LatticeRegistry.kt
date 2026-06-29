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
)
