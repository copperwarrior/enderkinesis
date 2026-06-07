package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.RelativeMovement
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.world.ServerShipWorld
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.vsCore

/**
 * Vertical wrap-around for [SselithRepertory]: anything that falls below [WRAP_BOTTOM]
 * is teleported back up to [WRAP_TOP] with [WRAP_DELTA_Y] added to its y, preserving
 * (x, z), velocity, and orientation. Combined with the [DamageTypes.FELL_OUT_OF_WORLD]
 * cancellation below, this turns Sselith into an endless vertical cylinder — falling off
 * the bottom lands you (and your ship) at the top, falling forever if you don't catch
 * yourself.
 *
 * ## Why a tick handler and a mixin
 *
 *  - The **tick handler** is the wrap itself, and runs for both entities and VS2 ships.
 *  - The companion mixin
 *    `org.shipwrights.enderkinesis.mixin.EntitySselithVoidWrapMixin` cancels
 *    `Entity.outOfWorld()` in Sselith. Without it, vanilla discards non-living entities
 *    (item drops, arrows, XP orbs, …) once they fall below `minBuildHeight - 64`, which is
 *    well above [WRAP_BOTTOM] — they'd vanish before the wrap could catch them.
 *  - Living entities don't go through `Entity.outOfWorld()`'s discard path; their
 *    `LivingEntity.outOfWorld()` calls `hurt(fellOutOfWorld, 4)` instead. That damage hits
 *    `EntityEvent.LIVING_HURT` first, which is where [onLivingHurt] cancels it. Players
 *    also route through the same `hurt()` call, so they're covered by the same hook.
 *
 * Ships are teleported via [vsCore.teleportShip] (same call the Ender Astrolabe uses),
 * keeping the ship's rotation and only translating its world position by [WRAP_DELTA_Y].
 */
object SselithVoidWrap {

    /** Below this Y the wrap fires. Symmetric with [WRAP_TOP] so the dimension reads as a
     *  vertical band centred on y=0. */
    private const val WRAP_BOTTOM = -512.0

    /** Where the wrap puts you: y + [WRAP_DELTA_Y]. With bottom −512 and top +512 this is
     *  a 1024-block jump. The long return trip keeps the teleport from feeling abrupt —
     *  at terminal velocity (~3 blocks/tick) a free-faller spends ~16 seconds back in view
     *  before the next wrap, more than enough time to read it as "very long fall, briefly
     *  vanished" rather than "snapped back instantly". */
    private const val WRAP_TOP = 512.0
    private const val WRAP_DELTA_Y = WRAP_TOP - WRAP_BOTTOM  // 1024

    fun init() {
        EntityEvent.LIVING_HURT.register(::onLivingHurt)
        TickEvent.SERVER_LEVEL_POST.register(TickEvent.ServerLevelTick { level ->
            if (level.dimension() != SselithRepertory.LEVEL_KEY) return@ServerLevelTick
            wrapEntities(level)
            wrapShips(level)
        })
    }

    /**
     * Cancel void *and* fall damage in Sselith. Two damage types are blocked:
     *  - [DamageTypes.FELL_OUT_OF_WORLD]: the vanilla void-damage tick that fires below
     *    `minBuildHeight - 64`, well above [WRAP_BOTTOM] — without this the entity is
     *    already chipped for the descent before the wrap saves them.
     *  - [DamageTypes.FALL]: the ground-impact damage when an entity finally hits a block
     *    after a long drop. Even with [WRAP_DELTA_Y] reset on `fallDistance`, the next
     *    fall (from `WRAP_TOP` back down) accumulates 768 blocks of fallDistance which
     *    would one-shot anything on landing. Disabling fall damage outright is the
     *    intent of the "no damage in the Sselith void" spec.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun onLivingHurt(
        entity: LivingEntity, source: DamageSource, amount: Float,
    ): EventResult {
        if (entity.level().dimension() != SselithRepertory.LEVEL_KEY) return EventResult.pass()
        if (source.`is`(DamageTypes.FELL_OUT_OF_WORLD)) return EventResult.interruptFalse()
        if (source.`is`(DamageTypes.FALL)) return EventResult.interruptFalse()
        return EventResult.pass()
    }

    /**
     * Iterate every loaded entity in the level and wrap any that crossed the bottom this
     * tick. `ServerLevel.getAllEntities()` is the broad-iteration API (covers items,
     * mobs, players, projectiles, …) — preferable to an AABB query because the wrap
     * region is unbounded in (x, z).
     */
    private fun wrapEntities(level: ServerLevel) {
        for (entity in level.allEntities) {
            if (entity.y >= WRAP_BOTTOM) continue
            // Passenger entities are repositioned by their vehicle's tick — moving them
            // directly would desync the mount until the next vehicle tick. Skip; the
            // vehicle will be the one to cross the threshold and bring its riders along.
            if (entity.isPassenger) continue

            if (entity is ServerPlayer) {
                // The 6-arg `connection.teleport` always takes **absolute** target
                // coordinates and rotations. The RelativeMovement set only controls how
                // the resulting `ClientboundPlayerPositionPacket` is encoded: for each
                // flagged axis the server subtracts the player's current value, so the
                // packet carries a delta. The client side then routes through the
                // relative branches, which is what gives us the seamless visual:
                //   • Position-delta branch: `xo += delta; yo += delta; zo += delta;
                //     setPos(currentX + delta, …); setDeltaMovement(oldDelta)`. Because
                //     `xo/yo/zo` shift by the SAME delta as the new position,
                //     `Mth.lerp(partial, yo, y)` collapses to `newY` — no camera-lerp
                //     blur across 1024 blocks. `deltaMovement` is fully preserved.
                //   • Rotation-delta branch: `setXRot(currentXRot + 0); xRotO += 0` —
                //     rotation state is untouched, so the arm-swing/head-bob state
                //     machine doesn't reset (which is what produced the "arm whip" on
                //     the absolute path).
                //
                // So we pass absolute (x, y+1024, z, yRot, xRot) plus `ALL` to get the
                // relative-encoded packet on the wire.
                entity.connection.teleport(
                    entity.x, entity.y + WRAP_DELTA_Y, entity.z,
                    entity.yRot, entity.xRot,
                    RelativeMovement.ALL,
                )
            } else {
                // Non-player entities aren't a camera, so the visual smoothness concern
                // doesn't apply. Some Entity subclasses do zero `deltaMovement` in their
                // `teleportTo` override, so we snapshot/restore here.
                val keepDelta = entity.deltaMovement
                entity.teleportTo(entity.x, entity.y + WRAP_DELTA_Y, entity.z)
                entity.deltaMovement = keepDelta
            }
            // `fallDistance` is reset to gate mob landing-impact behaviour (slime jumping,
            // etc.) which we don't want triggered by the wrap. With FALL damage cancelled
            // dimension-wide this is redundant for damage purposes.
            entity.fallDistance = 0f
        }
    }

    /**
     * Same wrap for VS2 ships. Ships don't go through Entity ticks; they have their own
     * physics step and `transform.positionInWorld` is the world-frame centre-of-mass.
     * We translate by [WRAP_DELTA_Y] keeping the ship's rotation, which means any
     * passengers / cargo on the ship are carried along by the ship-frame transform.
     */
    /**
     * Same wrap for VS2 ships. Two things this previously got wrong:
     *
     *  1. `ServerShipWorld` is *global* — `allShips` / `loadedShips` contain every ship on
     *     the server, across every dimension (per VS2's own API doc). Without a
     *     `chunkClaimDimension` filter we were iterating ships in other dimensions, and
     *     `teleportShip` would either no-op or move the wrong ship.
     *  2. `newShipTeleportData(...)` defaults both `newVel` and `newOmega` to zero —
     *     teleporting a falling ship without preserving velocity left it hanging
     *     motionless at the new Y until physics rebuilt the fall. We now forward the
     *     ship's current `velocity` / `omega` so the wrap is continuous.
     *
     *  Iteration uses `loadedShips` because only loaded ships have a current world
     *  position to test against [WRAP_BOTTOM]; unloaded ships hold stale data.
     */
    private fun wrapShips(level: ServerLevel) {
        val world = level.shipObjectWorld
        val dimId = level.dimensionId

        val toWrap = ArrayList<LoadedServerShip>()
        for (ship in world.loadedShips) {
            if (ship.chunkClaimDimension != dimId) continue
            if (ship.transform.positionInWorld.y() >= WRAP_BOTTOM) continue
            toWrap.add(ship)
        }
        if (toWrap.isEmpty()) return

        val sow = world as ServerShipWorld
        for (ship in toWrap) {
            val pos = ship.transform.positionInWorld
            val newPos = Vector3d(pos.x(), pos.y() + WRAP_DELTA_Y, pos.z())
            // Preserve linear + angular velocity so the wrap reads as continuous motion:
            // a ship at terminal velocity at the bottom keeps falling at terminal velocity
            // from the top. Default `newShipTeleportData` zeros both.
            val data = vsCore.newShipTeleportData(
                newPos = newPos,
                newRot = ship.transform.shipToWorldRotation,
                newVel = Vector3d(ship.velocity),
                newOmega = Vector3d(ship.omega),
                newDimension = dimId,
            )
            vsCore.teleportShip(sow, ship, data)
        }
    }
}
