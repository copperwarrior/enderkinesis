package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.registry.EKSounds

class CrepusculiteLightstoneBlock(properties: Properties) : FaceAttachedHorizontalDirectionalBlock(properties) {

    init {
        registerDefaultState(
            stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(FACE, AttachFace.FLOOR),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, FACE)
    }

    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = shapeFor(state)

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        val box = shapeFor(state).bounds()
        level.addParticle(
            EKParticles.crepusculiteLightstoneShine(),
            pos.x + box.minX + random.nextDouble() * box.xsize,
            pos.y + box.minY + random.nextDouble() * box.ysize,
            pos.z + box.minZ + random.nextDouble() * box.zsize,
            0.0, 0.0, 0.0,
        )
        if (random.nextInt(CHIME_CHANCE) != 0) return
        level.playLocalSound(
            pos.x + box.minX + box.xsize * 0.5, pos.y + box.minY + box.ysize * 0.5, pos.z + box.minZ + box.zsize * 0.5,
            EKSounds.CREPUSCULITE_CHIME, SoundSource.BLOCKS,
            CHIME_VOLUME, 0.8f + random.nextFloat() * 0.4f, false,
        )
    }

    private fun shapeFor(state: BlockState): VoxelShape {
        val alongX = state.getValue(FACING).axis == Direction.Axis.X
        return when (state.getValue(FACE)) {
            AttachFace.FLOOR -> if (alongX) FLOOR_X else FLOOR_Z
            AttachFace.CEILING -> if (alongX) CEILING_X else CEILING_Z
            AttachFace.WALL -> when (state.getValue(FACING)) {
                Direction.SOUTH -> WALL_SOUTH
                Direction.EAST -> WALL_EAST
                Direction.WEST -> WALL_WEST
                else -> WALL_NORTH
            }
        }
    }

    companion object {
        private val FLOOR_Z: VoxelShape = box(6.0, 0.0, 3.0, 10.0, 2.0, 13.0)
        private val FLOOR_X: VoxelShape = box(3.0, 0.0, 6.0, 13.0, 2.0, 10.0)
        private val CEILING_Z: VoxelShape = box(6.0, 14.0, 3.0, 10.0, 16.0, 13.0)
        private val CEILING_X: VoxelShape = box(3.0, 14.0, 6.0, 13.0, 16.0, 10.0)
        private val WALL_NORTH: VoxelShape = box(6.0, 3.0, 14.0, 10.0, 13.0, 16.0)
        private val WALL_SOUTH: VoxelShape = box(6.0, 3.0, 0.0, 10.0, 13.0, 2.0)
        private val WALL_EAST: VoxelShape = box(0.0, 3.0, 6.0, 2.0, 13.0, 10.0)
        private val WALL_WEST: VoxelShape = box(14.0, 3.0, 6.0, 16.0, 13.0, 10.0)

        private const val CHIME_CHANCE: Int = 200
        private const val CHIME_VOLUME: Float = 0.2f
    }
}
