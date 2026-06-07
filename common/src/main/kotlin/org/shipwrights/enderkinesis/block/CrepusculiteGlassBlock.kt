package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HalfTransparentBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty

/**
 * Crepusculite glass — translucent crepusculite-tinted glass.
 *
 * Random ticks toggle the [LIT] state, giving the block a slow pulse between the baseline
 * dim glow (light 1) and a soft light level of 4. Client-side [animateTick] occasionally
 * spits an end-rod-style sparkle and an amethyst chime, independent of the LIT state, so
 * every crepusculite glass block twinkles whether it's currently pulsed bright or not.
 */
class CrepusculiteGlassBlock(properties: BlockBehaviour.Properties) : HalfTransparentBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(LIT, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(LIT)
    }

    @Deprecated("Deprecated in Java")
    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        level.setBlock(pos, state.setValue(LIT, !state.getValue(LIT)), Block.UPDATE_ALL)
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(SPARKLE_CHANCE) != 0) return
        val x = pos.x + random.nextDouble()
        val y = pos.y + random.nextDouble()
        val z = pos.z + random.nextDouble()
        // END_ROD particle with vanilla end-rod-style velocity: small horizontal jitter and a
        // gentle upward drift — gives the slow drifty sparkle look without a custom particle.
        level.addParticle(
            ParticleTypes.END_ROD,
            x, y, z,
            (random.nextDouble() - 0.5) * 0.005,
            random.nextDouble() * 0.05,
            (random.nextDouble() - 0.5) * 0.005,
        )
        // The chime is gated independently of the sparkle — half the sparkles get a chime, so
        // the audible rate is half the visual rate.
        if (random.nextInt(CHIME_PER_SPARKLE) == 0) {
            level.playLocalSound(
                pos.x + 0.5, pos.y + 0.5, pos.z + 0.5,
                SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.BLOCKS,
                0.4f, 0.8f + random.nextFloat() * 0.4f, false,
            )
        }
    }

    companion object {
        val LIT: BooleanProperty = BlockStateProperties.LIT

        /** 1-in-N animate ticks spawns a sparkle. animateTick fires ~once per visible block per
         *  client tick, so 80 averages to a sparkle every ~4 seconds per block. */
        private const val SPARKLE_CHANCE = 80
        /** 1-in-N sparkles also rings the amethyst chime. 2 → chime at half the sparkle rate
         *  (one chime every ~8 seconds per block on average). */
        private const val CHIME_PER_SPARKLE = 2
    }
}
