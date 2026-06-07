package org.shipwrights.enderkinesis.physics

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/**
 * Same-tick "is this entity in a lattice fluid right now" check + wave push application.
 *
 *  ## Why a probe at all
 *
 *  The lattice BE's [floatEntities] is the eventually-consistent path — it applies
 *  [Effects.CREPUSCULAR_FLOATATION], which the next entity tick reads via [EntityMixin].
 *  In MC, the entity tick runs before BE ticks in the same frame, so a player who just
 *  crossed the wave plane *this tick* still reads no effect → vanilla water travel is
 *  skipped that tick → at terminal velocity they fall 3–4 extra blocks before drag engages.
 *  The probe closes that gap by checking the *current* tick's zones synchronously from the
 *  entity's own tick.
 *
 *  ## Why this is efficient even with 20 lattices
 *
 *  Each lattice publishes its [LatticeCatchZone] to [LatticeRegistry] **once per tick** (in
 *  [CrepusculiteLatticeBlockEntity.commonTick]). The probe just walks that pre-built list
 *  and runs the ellipsoid + surface compare against each cached zone — no VS2 ship lookup,
 *  no shipToWorld transforms, no AABB corner walk per entity. Cost is N_lattices × ~10 FLOPs
 *  per entity tick.
 *
 *  ## Wave push
 *
 *  Beyond just answering "in water Y/N", once we know the entity is in some zone we can
 *  also push them along the local Gerstner velocity field (so the waves carry entities
 *  around, not just the ships). Scaled down from full ship-velocity so an entity rides the
 *  swell instead of being shot off it.
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

        val zones = LatticeRegistry.zones(entity.level())
        if (zones.isEmpty()) return null

        val ex = entity.x; val ey = entity.y; val ez = entity.z
        val feetY = entity.boundingBox.minY
        for (zone in zones) {
            if (entityInZone(zone, ex, ey, ez, feetY)) return zone
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
