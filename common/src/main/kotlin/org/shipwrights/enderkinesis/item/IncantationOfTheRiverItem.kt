package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AbstractCandleBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LevelEvent
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.registry.EKSounds
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.mod.common.ValkyrienSkiesMod
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.IEntityDraggingInformationProvider
import org.valkyrienskies.core.api.util.GameTickOnly

/** Incantation of the River: hold right-click to shoot a pressure-washer jet along the view. The jet stops on the
 *  first entity, ship or block. It pushes and hurts that target, much harder close up, puts out fire, candles,
 *  campfires and lava at the impact, and washes what it holds on ([RiverWash]). The recoil pushes the caster, or the
 *  ship the caster stands on. */
@OptIn(GameTickOnly::class)
class IncantationOfTheRiverItem(properties: Properties) : IncantationItem(
    properties,
    HAND_COLOUR,
    EKSounds.INCANTATION_OF_THE_RIVER_START,
    EKSounds.INCANTATION_OF_THE_RIVER_STOP,
    EKSounds.INCANTATION_OF_THE_RIVER_LOOP,
) {

    override fun cast(level: ServerLevel, player: Player) {
        val jet = trace(level, player, 1f)
        val look = player.lookAngle
        val pressure = pressureAt(jet.length)
        thrustShip(level, player, look)
        brakeFall(player, look)
        RiverWash.wash(level, player, jet)
        val target = jet.entity
        if (target != null) {
            if (target.isOnFire) target.clearFire()
            target.deltaMovement = target.deltaMovement.add(look.scale(ENTITY_PUSH_SPEED * pressure))
            markPushed(target)
            if (target is LivingEntity && player.tickCount % DAMAGE_INTERVAL_TICKS == 0) hurt(player, target, pressure)
            return
        }
        val hit = jet.block ?: return
        pushShip(level, hit, look, pressure)
        quench(level, player, hit)
    }

    private fun hurt(player: Player, target: LivingEntity, pressure: Double) {
        val weak = if (target.isSensitiveToWater) WATER_WEAK_FACTOR else 1.0
        target.hurt(player.damageSources().playerAttack(player), (PRESSURE_DAMAGE * pressure * weak).toFloat())
    }

    /** Fall damage counts the height fallen, not the speed, so a slowed fall clears it as Slow Falling does. */
    private fun brakeFall(player: Player, look: Vec3) {
        if (player.onGround() || player.abilities.flying) return
        if (look.y < -BRAKE_AIM) player.resetFallDistance()
    }

    /** The recoil of a caster on a ship goes into the ship; [RiverJetClient] pushes a caster who is not on one. */
    private fun thrustShip(level: ServerLevel, player: Player, look: Vec3) {
        val info = (player as? IEntityDraggingInformationProvider)?.draggingInformation ?: return
        if (!info.isEntityBeingDraggedByAShip()) return
        val shipId = info.lastShipStoodOn ?: return
        if (level.shipObjectWorld.loadedShips.getById(shipId) == null) return
        ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId).applyWorldForce(
            shipId,
            Vector3d(-look.x * SHIP_THRUST, -look.y * SHIP_THRUST, -look.z * SHIP_THRUST),
            Vector3d(player.x, player.y, player.z),
        )
    }

    /** VS2: a ship hit has a world `location` and a shipyard `blockPos`. */
    private fun pushShip(level: ServerLevel, hit: BlockHitResult, look: Vec3, pressure: Double) {
        val ship = level.getShipManagingPos(hit.blockPos) as? ServerShip ?: return
        val mag = (PUSH_FORCE_PER_MASS * ship.inertiaData.mass.coerceAtLeast(1.0)).coerceAtLeast(PUSH_MIN_FORCE) * pressure
        ValkyrienSkiesMod.getOrCreateGTPA(level.dimensionId).applyWorldForce(
            ship.id,
            Vector3d(look.x * mag, look.y * mag, look.z * mag),
            Vector3d(hit.location.x, hit.location.y, hit.location.z),
        )
    }

    /** Tests the cells round the cell in front of the hit face. Offsets stay in the hit's frame, so ship blocks work. */
    private fun quench(level: ServerLevel, player: Player, hit: BlockHitResult) {
        if (!player.mayBuild()) return
        val front = hit.blockPos.relative(hit.direction)
        val pos = BlockPos.MutableBlockPos()
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            pos.setWithOffset(front, dx, dy, dz)
            val state = level.getBlockState(pos)
            val lava = state.`is`(Blocks.LAVA)
            if (!lava && !WindBlockEffects.isSmoking(state) && !isLitCandle(state)) continue
            val at = pos.immutable()
            if (!level.mayInteract(player, at)) continue
            when {
                lava -> {
                    val cooled = if (state.fluidState.isSource) Blocks.OBSIDIAN else Blocks.COBBLESTONE
                    level.setBlock(at, cooled.defaultBlockState(), Block.UPDATE_ALL)
                    level.levelEvent(LevelEvent.LAVA_FIZZ, at, 0)
                    level.gameEvent(player, GameEvent.BLOCK_CHANGE, at)
                }
                state.block is AbstractCandleBlock -> AbstractCandleBlock.extinguish(player, state, level, at)
                else -> WindBlockEffects.extinguish(level, player, at, state)
            }
        }
    }

    private fun isLitCandle(state: net.minecraft.world.level.block.state.BlockState): Boolean =
        state.block is AbstractCandleBlock && state.getValue(AbstractCandleBlock.LIT)

    /** The jet from the eye: [end] is the impact point, or the far end on a miss. */
    class Jet(val end: Vec3, val length: Double, val entity: Entity?, val block: BlockHitResult?)

    companion object {
        /** Jet reach along the view, blocks. */
        const val RANGE = 20.0

        /** The one River blue: jet, shell, mist and hand motes all use it. */
        const val WATER_COLOUR = 0x7ABDFF
        private const val HAND_COLOUR = WATER_COLOUR

        /** Blocks/tick of acceleration on the hit entity, before the pressure factor. */
        private const val ENTITY_PUSH_SPEED = 0.12

        /** N per kg of ship mass, at the hit point. */
        private const val PUSH_FORCE_PER_MASS = 5.0
        private const val PUSH_MIN_FORCE = 625.0

        /** Pressure factor at the palm and at [RANGE]; it falls with the square of the distance between them. */
        private const val CLOSE_PRESSURE = 2.0
        private const val FAR_PRESSURE = 0.1

        /** Damage at a pressure of 1; water-weak mobs (Endermen, Blazes, Striders, Snow Golems) take more. */
        private const val PRESSURE_DAMAGE = 1.0
        private const val WATER_WEAK_FACTOR = 3.0
        private const val DAMAGE_INTERVAL_TICKS = 10

        /** Newtons on the caster's ship, against the aim. Not mass-relative, so a small boat moves and a big ship barely. */
        private const val SHIP_THRUST = 12_000.0

        /** Blocks/tick of recoil on a falling or elytra-flying caster not on a ship. Near gravity (0.08), so aiming
         *  down slows a fall. */
        const val RECOIL = 0.07

        /** How far below level the aim must point (sine of 30 degrees) for the recoil to brake a fall. */
        private const val BRAKE_AIM = 0.5

        fun pressureAt(distance: Double): Double {
            val near = 1.0 - (distance / RANGE).coerceIn(0.0, 1.0)
            return FAR_PRESSURE + (CLOSE_PRESSURE - FAR_PRESSURE) * near * near
        }

        private const val ENTITY_SEARCH_MARGIN = 1.0

        /** Same margin as a projectile hit. */
        private const val ENTITY_HIT_INFLATE = 0.3

        /** Shared by the server cast and the client visuals, so both stop the jet at the same place. */
        fun trace(level: Level, player: Player, partialTick: Float): Jet {
            val eye = player.getEyePosition(partialTick)
            val far = eye.add(player.getViewVector(partialTick).scale(RANGE))
            // The jet goes through water but stops on lava, so a caster under water can still aim.
            val solid = level.clip(ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
            val fluid = level.clip(ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player))
            val lavaFirst = fluid.type != HitResult.Type.MISS &&
                level.getFluidState(fluid.blockPos).`is`(net.minecraft.tags.FluidTags.LAVA) &&
                eye.distanceToSqr(fluid.location) < eye.distanceToSqr(solid.location)
            val block = (if (lavaFirst) fluid else solid).takeIf { it.type != HitResult.Type.MISS }
            val blockEnd = block?.location ?: far
            var best: Entity? = null
            var bestPoint = blockEnd
            var bestDistSqr = eye.distanceToSqr(blockEnd)
            val candidates = level.getEntities(player, AABB(eye, blockEnd).inflate(ENTITY_SEARCH_MARGIN)) { e ->
                e.isAlive && !e.isSpectator && e !== player.vehicle
            }
            for (e in candidates) {
                val point = e.boundingBox.inflate(ENTITY_HIT_INFLATE).clip(eye, blockEnd).orElse(null) ?: continue
                val distSqr = eye.distanceToSqr(point)
                if (distSqr < bestDistSqr) {
                    best = e
                    bestPoint = point
                    bestDistSqr = distSqr
                }
            }
            return Jet(bestPoint, kotlin.math.sqrt(bestDistSqr), best, if (best == null) block else null)
        }
    }
}
