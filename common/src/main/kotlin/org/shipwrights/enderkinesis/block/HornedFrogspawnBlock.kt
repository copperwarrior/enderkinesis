package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.FrogspawnBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import org.shipwrights.enderkinesis.entity.HornedFrogEntity
import org.shipwrights.enderkinesis.registry.EKEntities

/** Vanilla frogspawn shape, placement and boat breaking; it hatches straight into baby Horned Frogs, not tadpoles. */
class HornedFrogspawnBlock(properties: Properties) : FrogspawnBlock(properties) {

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (canSurvive(state, level, pos)) hatch(level, pos, random)
        // Return value ignored: the block may already be gone.
        level.destroyBlock(pos, false)
    }

    private fun hatch(level: ServerLevel, pos: BlockPos, random: RandomSource) {
        val x = pos.x + HALF
        val y = pos.y + HATCH_HEIGHT
        val z = pos.z + HALF
        val near = level.getEntitiesOfClass(HornedFrogEntity::class.java, AABB(pos).inflate(HornedFrogEntity.CROWD_RADIUS)).size
        val count = random.nextIntBetweenInclusive(MIN_FROGS, MAX_FROGS).coerceAtMost(HornedFrogEntity.MAX_CROWD - near)
        if (count <= 0) return
        level.playSound(null, pos, SoundEvents.FROGSPAWN_HATCH, SoundSource.BLOCKS, 1f, 1f)
        repeat(count) {
            val frog = EKEntities.HORNED_FROG.get().create(level) ?: return@repeat
            frog.isBaby = true
            frog.moveTo(x + (random.nextDouble() - HALF) * SPREAD, y, z + (random.nextDouble() - HALF) * SPREAD, random.nextFloat() * FULL_TURN, 0f)
            // Return value ignored: a refused spawn just loses that egg.
            level.addFreshEntity(frog)
        }
    }

    private companion object {
        const val MIN_FROGS = 2
        const val MAX_FROGS = 4
        const val HALF = 0.5
        const val HATCH_HEIGHT = 0.1
        const val SPREAD = 0.6
        const val FULL_TURN = 360f
    }
}
