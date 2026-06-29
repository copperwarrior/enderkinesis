package org.shipwrights.enderkinesis.physics

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.client.CrepusculiteLatticeFootprintCache
import org.valkyrienskies.mod.common.getShipManagingPos

/**
 * Same-tick "is this entity in a lattice fluid right now" check + wave-push application.
 *
 * Exists because the BE [floatEntities] path is eventually-consistent: it applies
 * [Effects.CREPUSCULAR_FLOATATION] which the *next* entity tick reads via [EntityMixin].
 * Since MC ticks entities before block entities in the same frame, a player crossing the
 * wave plane this tick reads no effect → vanilla water travel is skipped → at terminal
 * velocity they fall 3–4 blocks past the surface before drag engages. The probe closes
 * that gap by checking the current tick's zones synchronously from the entity's own tick.
 *
 * Cost stays bounded because each lattice publishes its [LatticeCatchZone] to
 * [LatticeRegistry] once per tick — the probe just walks that pre-built list, no VS2 ship
 * lookups or shipToWorld transforms per entity.
 */
object CrepusculiteLatticeFluidProbe {

    /** Fraction of the local Gerstner surface velocity that gets added to entity motion per
     *  tick. The wave's tangential flow on a ship is applied at 1.0 (the ship rides it
     *  directly); here we want "enough to feel the swell" without ragdolling — at 0.15 a
     *  typical wave nudges a player ~0.05 blocks/tick along the wave's direction. */
    private const val ENTITY_WAVE_PUSH_SCALE = 0.15

    /**
     * Walk every registered zone in the entity's level and return the first one whose
     * ellipsoid + surface gate contains the entity, or null if none does. Skips entities
     * that wouldn't be eligible for water travel regardless (mirrors the early-out filters
     * in [floatEntities] so the probe and the BE always agree on eligibility).
     */
    fun findContainingZone(entity: Entity): LatticeCatchZone? {
        if (entity !is LivingEntity) return null
        if (entity.isPassenger) return null
        if (entity.isFallFlying) return null
        if (entity is Player && (entity.isSpectator || entity.abilities.flying)) return null
        if (entity.onGround()) return null

        val level = entity.level()
        val entries = LatticeRegistry.entries(level)
        if (entries.isEmpty()) return null

        val ex = entity.x; val ey = entity.y; val ez = entity.z
        val feetY = entity.boundingBox.minY
        for ((be, zone) in entries) {
            if (!entityInZone(zone, ex, ey, ez, feetY)) continue
            // Inside the inflated ellipsoid + below the water line — but if the entity is
            // also inside an air pocket within the ship's hull (cabin, hold, sealed
            // chamber), there's no virtual fluid here. Use the same footprint classifier
            // the mesh renderer uses to cut the surface mesh through the hull silhouette.
            val ship = level.getShipManagingPos(be.blockPos) ?: return zone   // free-world lattice; no hull to check
            val state = CrepusculiteLatticeFootprintCache.stateAt(
                level, ship, ship.transform.worldToShip, ex, ey, ez,
            )
            // BOUNDARY = hull face; INTERIOR = deep hull; ENCLOSED = sealed air pocket — all
            // hull-side states. Only EMPTY (true exterior) and EMPTY_NEAR_HULL (adjacent
            // water cell just outside hull) count as actual fluid.
            val inHull = state == CrepusculiteLatticeFootprintCache.BOUNDARY ||
                state == CrepusculiteLatticeFootprintCache.INTERIOR ||
                state == CrepusculiteLatticeFootprintCache.ENCLOSED
            if (inHull) continue
            return zone
        }
        return null
    }

    /** Convenience: true if any zone contains the entity. */
    fun isInAnyCatchZone(entity: Entity): Boolean = findContainingZone(entity) != null

    /**
     * If [entity] is in any zone, apply a small horizontal nudge matching the wave's
     * surface velocity at the entity's position. Vertical component is left alone — wave
     * bobbing is handled by the BE's own [floatEntities] non-player branch + vanilla water
     * travel for players, so adding Y here would double up.
     *
     *  Server-vs-client side: for *players* the client owns motion (server-applied delta
     *  would snap back), so we only push players on the client. For non-players the server
     *  is authoritative, so we only push them on the server. The mixin runs on both sides
     *  so each side picks up the entities it owns.
     */
    fun applyWavePush(entity: Entity, zone: LatticeCatchZone) {
        val isClient = entity.level().isClientSide
        val isPlayer = entity is Player
        if (isPlayer != isClient) return                      // only push the side that owns motion

        val out = Vector3d()
        GerstnerOcean.velocityAt(entity.x, entity.z, zone.waveTime, zone.waveIntensity, out)
        val mv = entity.deltaMovement
        entity.deltaMovement = Vec3(
            mv.x + out.x * ENTITY_WAVE_PUSH_SCALE,
            mv.y,
            mv.z + out.z * ENTITY_WAVE_PUSH_SCALE,
        )
    }

    private fun entityInZone(zone: LatticeCatchZone, ex: Double, ey: Double, ez: Double, feetY: Double): Boolean {
        val nx = (ex - zone.cx) / zone.ax
        val ny = (ey - zone.cy) / zone.ay
        val nz = (ez - zone.cz) / zone.az
        if (nx * nx + ny * ny + nz * nz > 1.0) return false   // outside ellipsoid
        return feetY < zone.waterLineY                        // below nominal sea level
    }
}
