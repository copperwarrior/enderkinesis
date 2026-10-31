package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

class PhantomLightBlock(properties: Properties) : Block(properties) {

    @Deprecated("Deprecated in Java")
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

    @Deprecated("Deprecated in Java")
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        Shapes.empty()

    @Deprecated("Deprecated in Java")
    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, movedByPiston: Boolean) {
        level.scheduleTick(pos, this, LIFETIME_TICKS)
    }

    @Deprecated("Deprecated in Java")
    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        level.removeBlock(pos, false)
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(SPARKLE_CHANCE) != 0) return
        level.addParticle(
            ParticleTypes.END_ROD,
            pos.x + random.nextDouble(), pos.y + random.nextDouble(), pos.z + random.nextDouble(),
            0.0, 0.01, 0.0,
        )
    }

    companion object {
        const val LIFETIME_TICKS = 1200
        private const val SPARKLE_CHANCE = 3
    }
}
