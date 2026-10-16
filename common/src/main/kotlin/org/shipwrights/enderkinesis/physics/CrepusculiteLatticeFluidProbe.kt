package org.shipwrights.enderkinesis.physics

import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKEffects
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

    /** Effect duration above which the grace time counts. The lattice keeps the duration
     *  in [50, 60] while an entity is in the water; the margin is for client/server skew. */
    private const val ACTIVE_DURATION_THRESHOLD = 45

    /**
     * The zone whose water holds the entity, or null. The water is [LatticeOcean] less the
     * dry parts of the ship hull. The test point is the feet of the entity.
     */
    fun findContainingZone(entity: Entity): LatticeCatchZone? {
        if (!eligible(entity)) return null
        val level = entity.level()
        val entries = LatticeRegistry.entries(level)
        if (entries.isEmpty()) return null

        val x = entity.x
        val z = entity.z
        val feetY = entity.boundingBox.minY
        val wake = LatticeOcean.wakeSources(level)
        for ((be, zone) in entries) {
            if (!LatticeOcean.contains(zone, x, feetY, z, wake)) continue
            val ship = level.getShipManagingPos(be.blockPos) ?: continue
            if (LatticeHullVolume.isWet(level, ship, ship.transform.worldToShip, x, feetY, z)) return zone
        }
        return null
    }

    /**
     * True during the grace time of an entity that left the water of a zone but is still below
     * its wave surface: below the deepest level, or in a dry part of a hull. Above the surface
     * there is no grace.
     */
    fun inGrace(entity: Entity): Boolean {
        if (entity !is LivingEntity || !eligible(entity)) return false
        val instance = entity.getEffect(EKEffects.CREPUSCULAR_FLOATATION.get()) ?: return false
        if (instance.duration < ACTIVE_DURATION_THRESHOLD) return false
        val level = entity.level()
        val wake = LatticeOcean.wakeSources(level)
        val feetY = entity.boundingBox.minY
        for (zone in LatticeRegistry.zones(level)) {
            if (LatticeOcean.belowSurface(zone, entity.x, feetY, entity.z, wake)) return true
        }
        return false
    }

    private fun eligible(entity: Entity): Boolean {
        if (entity !is LivingEntity) return false
        if (entity.isPassenger || entity.isFallFlying) return false
        return !(entity is Player && (entity.isSpectator || entity.abilities.flying))
    }

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
}
