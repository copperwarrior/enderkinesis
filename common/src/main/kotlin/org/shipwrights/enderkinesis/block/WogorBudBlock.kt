package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlockContainer
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Bud growth stage planted by Heart of the Wild. [FACING] points AT the supporting
 * neighbour (inverse of vanilla amethyst's clickedFace convention). Uses scheduled ticks,
 * NOT random ticks — random was too slow (~3 min) and a BlockEntity wasn't justified for
 * "advance an age counter".
 */
class WogorBudBlock(properties: BlockBehaviour.Properties) : Block(properties), LiquidBlockContainer {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(AGE, 0)
                .setValue(FACING, Direction.UP),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(AGE, FACING)
    }

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = SHAPES[state.getValue(AGE)][state.getValue(FACING).ordinal]

    /** Blocks `BucketItem.emptyContents` from erasing the bud — a noCollission block's
     *  vanilla default returns true here. */
    override fun canBeReplaced(state: BlockState, fluid: Fluid): Boolean = false

    /** Blocks `FlowingFluid.canHoldFluid` from flooding the cell — without this, flowing
     *  water destroys the bud. */
    override fun canPlaceLiquid(
        level: BlockGetter, pos: BlockPos, state: BlockState, fluid: Fluid,
    ): Boolean = false

    override fun placeLiquid(
        level: LevelAccessor, pos: BlockPos, state: BlockState, fluidState: FluidState,
    ): Boolean = false

    override fun canSurvive(
        state: BlockState,
        level: LevelReader,
        pos: BlockPos,
    ): Boolean {
        val facing = state.getValue(FACING)
        val supportPos = pos.relative(facing)
        val supportState = level.getBlockState(supportPos)
        return supportState.isFaceSturdy(level, supportPos, facing.opposite)
    }

    override fun updateShape(
        state: BlockState,
        direction: Direction,
        neighborState: BlockState,
        level: LevelAccessor,
        pos: BlockPos,
        neighborPos: BlockPos,
    ): BlockState {
        return if (!canSurvive(state, level, pos)) {
            Blocks.AIR.defaultBlockState()
        } else {
            super.updateShape(state, direction, neighborState, level, pos, neighborPos)
        }
    }

    override fun onPlace(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        oldState: BlockState,
        isMoving: Boolean,
    ) {
        super.onPlace(state, level, pos, oldState, isMoving)
        if (level.isClientSide) return
        // `tick` schedules its own next grow; guard against double-scheduling on age transitions.
        if (oldState.`is`(this)) return
        scheduleNextGrow(level, pos, level.random)
    }

    override fun tick(
        state: BlockState,
        level: ServerLevel,
        pos: BlockPos,
        random: RandomSource,
    ) {
        val age = state.getValue(AGE)
        if (age < MAX_AGE) {
            level.setBlock(pos, state.setValue(AGE, age + 1), UPDATE_CLIENTS)
            scheduleNextGrow(level, pos, random)
        } else {
            val facing = state.getValue(FACING)
            val wogor = EKBlocks.WOGOR_WOOD.get().defaultBlockState()
                .setValue(BlockStateProperties.AXIS, facing.axis)
            level.setBlock(pos, wogor, UPDATE_ALL)
        }
    }

    private fun scheduleNextGrow(level: Level, pos: BlockPos, random: RandomSource) {
        val span = GROW_DELAY_MAX_TICKS - GROW_DELAY_MIN_TICKS + 1
        val delay = GROW_DELAY_MIN_TICKS + random.nextInt(span)
        level.scheduleTick(pos, this, delay)
    }

    companion object {
        val AGE: IntegerProperty = BlockStateProperties.AGE_2
        val FACING: DirectionProperty = BlockStateProperties.FACING
        const val MAX_AGE: Int = 2

        /** Gates WHERE new buds can be planted. `canSurvive` is unchanged (sturdy face check),
         *  so an already-placed bud doesn't self-destruct if its support isn't tagged. Both
         *  growers bypass this for the seed bud so it can anchor onto whatever's already there. */
        val WOGOR_BUD_GROWABLE: TagKey<Block> = TagKey.create(
            Registries.BLOCK, ResourceLocation("enderkinesis", "wogor_bud_growable"),
        )

        const val GROW_DELAY_MIN_TICKS: Int = 5
        const val GROW_DELAY_MAX_TICKS: Int = 7

        private val SIZES: IntArray = intArrayOf(4, 8, 12)

        private val SHAPES: Array<Array<VoxelShape>> = Array(SIZES.size) { age ->
            val size = SIZES[age]
            Array(Direction.values().size) { dirOrdinal ->
                shapeFor(size, Direction.values()[dirOrdinal])
            }
        }

        private fun shapeFor(size: Int, facing: Direction): VoxelShape {
            val half = size / 2.0
            return when (facing) {
                Direction.UP -> box(
                    8.0 - half, 16.0 - size, 8.0 - half,
                    8.0 + half, 16.0, 8.0 + half,
                )
                Direction.DOWN -> box(
                    8.0 - half, 0.0, 8.0 - half,
                    8.0 + half, size.toDouble(), 8.0 + half,
                )
                Direction.NORTH -> box(
                    8.0 - half, 8.0 - half, 0.0,
                    8.0 + half, 8.0 + half, size.toDouble(),
                )
                Direction.SOUTH -> box(
                    8.0 - half, 8.0 - half, 16.0 - size,
                    8.0 + half, 8.0 + half, 16.0,
                )
                Direction.EAST -> box(
                    16.0 - size, 8.0 - half, 8.0 - half,
                    16.0, 8.0 + half, 8.0 + half,
                )
                Direction.WEST -> box(
                    0.0, 8.0 - half, 8.0 - half,
                    size.toDouble(), 8.0 + half, 8.0 + half,
                )
            }
        }
    }
}
