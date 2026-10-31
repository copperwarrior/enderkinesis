package org.shipwrights.enderkinesis.item

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.AbstractCandleBlock
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.LevelEvent
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Incantation of Winds on blocks: blows smoke off fire and campfires and sometimes puts them out, puts out candles,
 *  and strips snow and powder snow. */
object WindBlockEffects {
    /** Random cells tested in the cone each tick; the cone holds about 3000, so a held cast clears it in seconds. */
    private const val SAMPLES_PER_TICK = 96

    /** A wall hit this much (squared blocks) short of the target cell shields it. */
    private const val SHIELD_MARGIN_SQR = 1.0

    private const val SNOWFLAKES = 3
    private const val SNOWFLAKE_SPEED = 0.4
    private const val SMOKE_PUFFS = 4
    private const val SMOKE_SPEED = 0.25

    /** Chance that a hit on fire or a campfire also puts it out. A fire gets about 0.6 hits a second
     *  in a held cast, so this gives about 11 seconds on average. */
    private const val EXTINGUISH_CHANCE = 0.15f

    fun blow(level: ServerLevel, player: Player, eye: Vec3, look: Vec3) {
        val canBuild = player.mayBuild()
        val rnd = level.random
        val range = IncantationOfWindsItem.RANGE
        val tanHalf = IncantationOfWindsItem.TAN_HALF_ANGLE
        val ref = if (kotlin.math.abs(look.y) > 0.99) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        val u = look.cross(ref).normalize()
        val w = look.cross(u)
        val pos = BlockPos.MutableBlockPos()
        repeat(SAMPLES_PER_TICK) {
            // Cube root and square root give a uniform spread over the cone volume.
            val along = range * cbrt(rnd.nextDouble())
            val r = along * tanHalf * sqrt(rnd.nextDouble())
            val theta = rnd.nextDouble() * 2.0 * Math.PI
            val c = cos(theta) * r
            val s = sin(theta) * r
            pos.set(
                eye.x + look.x * along + u.x * c + w.x * s,
                eye.y + look.y * along + u.y * c + w.y * s,
                eye.z + look.z * along + u.z * c + w.z * s,
            )
            val state = level.getBlockState(pos)
            val smokes = isSmoking(state)
            if (!smokes && !(canBuild && isChanged(state))) return@repeat
            if (!reaches(level, player, eye, pos)) return@repeat
            if (smokes) {
                smoke(level, pos, look)
                if (canBuild && rnd.nextFloat() < EXTINGUISH_CHANCE && level.mayInteract(player, pos)) {
                    extinguish(level, player, pos.immutable(), state)
                }
            } else if (level.mayInteract(player, pos)) {
                apply(level, player, pos.immutable(), state, look)
            }
        }
    }

    private fun isSmoking(state: BlockState): Boolean =
        state.block is BaseFireBlock || CampfireBlock.isLitCampfire(state)

    private fun isChanged(state: BlockState): Boolean =
        (state.block is AbstractCandleBlock && state.getValue(AbstractCandleBlock.LIT)) ||
            state.`is`(Blocks.SNOW) ||
            state.`is`(Blocks.POWDER_SNOW)

    /** Walls shield the cells behind them. */
    private fun reaches(level: ServerLevel, player: Player, eye: Vec3, pos: BlockPos): Boolean {
        val target = Vec3.atCenterOf(pos)
        val hit = level.clip(ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        if (hit.type == HitResult.Type.MISS || hit.blockPos == pos) return true
        // VS2: a ship hit has a shipyard blockPos, so compare world distances, not positions.
        return eye.distanceToSqr(hit.location) >= eye.distanceToSqr(target) - SHIELD_MARGIN_SQR
    }

    private fun apply(level: ServerLevel, player: Player, pos: BlockPos, state: BlockState, look: Vec3) {
        when {
            state.block is AbstractCandleBlock -> AbstractCandleBlock.extinguish(player, state, level, pos)
            state.`is`(Blocks.SNOW) -> {
                val layers = state.getValue(SnowLayerBlock.LAYERS)
                if (layers > 1) {
                    level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, layers - 1), Block.UPDATE_ALL)
                } else {
                    level.removeBlock(pos, false)
                }
                puff(level, pos, state, look)
            }
            state.`is`(Blocks.POWDER_SNOW) -> {
                level.removeBlock(pos, false)
                puff(level, pos, state, look)
            }
        }
    }

    private fun extinguish(level: ServerLevel, player: Player, pos: BlockPos, state: BlockState) {
        level.levelEvent(null, LevelEvent.SOUND_EXTINGUISH_FIRE, pos, 0)
        if (state.block is BaseFireBlock) {
            level.removeBlock(pos, false)
        } else {
            // Same steps as a shovel on a campfire.
            CampfireBlock.dowse(player, level, pos, state)
            level.setBlock(pos, state.setValue(CampfireBlock.LIT, false), Block.UPDATE_ALL_IMMEDIATE)
            level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos)
        }
    }

    /** Count 0: the offset is the particle's velocity, so the smoke streams downwind. */
    private fun smoke(level: ServerLevel, pos: BlockPos, look: Vec3) {
        val rnd = level.random
        repeat(SMOKE_PUFFS) {
            level.sendParticles(
                ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
                pos.x + rnd.nextDouble(), pos.y + 0.5 + rnd.nextDouble() * 0.5, pos.z + rnd.nextDouble(),
                0, look.x, look.y, look.z, SMOKE_SPEED,
            )
        }
    }

    private fun puff(level: ServerLevel, pos: BlockPos, state: BlockState, look: Vec3) {
        val x = pos.x + 0.5
        val y = pos.y + 0.5
        val z = pos.z + 0.5
        level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, state), x, y, z, 6, 0.3, 0.2, 0.3, 0.05)
        // Count 0: the offset is the snowflake's velocity, so it flies downwind.
        repeat(SNOWFLAKES) {
            level.sendParticles(ParticleTypes.SNOWFLAKE, x, y, z, 0, look.x, look.y, look.z, SNOWFLAKE_SPEED)
        }
        level.gameEvent(null, GameEvent.BLOCK_DESTROY, pos)
    }
}
