package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Intermediate "bud" growth stage that the Heart of the Wild plants
 * onto a solid face. The bud advances through [MAX_AGE]+1 visual
 * stages (small → medium → almost-full) on successive **scheduled
 * ticks** and then transforms into a [EKBlocks.WOGOR_WOOD] log on
 * the final tick.
 *
 * **Tick scheme.** Scheduled ticks, NOT random ticks. The bud
 * deliberately isn't a BlockEntity — random ticks were too slow
 * (~65 s mean between advances at default `randomTickSpeed=3`,
 * ~3 min total to mature), and the per-block memory of a BE is
 * unjustified for what's just "advance an age counter on a fixed
 * cadence." [onPlace] schedules the first growth tick; each
 * [tick] callback advances [AGE] and schedules the next one until
 * the bud matures. Vanilla persists scheduled ticks across save /
 * chunk-unload, so the growth survives the player leaving the
 * area mid-cycle.
 *
 * **Growth cadence** (see [GROW_DELAY_MIN_TICKS] / [GROW_DELAY_MAX_TICKS]):
 * each of the three stage transitions takes a uniformly-random
 * 5–7 ticks, so a fresh bud matures into Wogor Wood in 15–21
 * total ticks (≈3⁄4 of a second). Tune the constants if pacing
 * needs to change.
 *
 * **Geometry.** The bud occupies one cuboid sized by age (4/8/12 of
 * 16) pinned to the face indicated by [FACING]. [FACING] points
 * *at* the supporting neighbour — `FACING=DOWN` ⇒ support is one
 * block below, `FACING=NORTH` ⇒ support is one block to the
 * north, etc. The bud visually sits on the cell-half adjacent
 * to that support. (Inverse of vanilla amethyst's `clickedFace`
 * convention, which we found unintuitive in this codebase's
 * grow-from-the-Heart context.)
 *
 * **Maturation.** When [tick] fires on a state with `age ==
 * MAX_AGE`, the block is replaced by Wogor Wood whose pillar
 * `AXIS` follows the bud's `FACING` axis. That gives mature trunks
 * a consistent orientation relative to whatever the bud grew off
 * of: ceiling-mounted buds become vertical logs, wall-mounted buds
 * become horizontal logs along the wall's normal. */
class WogorBudBlock(properties: BlockBehaviour.Properties) : Block(properties) {

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

    /** A bud needs the face it grew off of to stay sturdy.
     *  [FACING] points directly at the supporting neighbour, and
     *  the face on that neighbour that touches the bud is the
     *  neighbour's `FACING.opposite` face — so we check that
     *  face's sturdiness on the neighbour. */
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

    /** Drop the bud the moment its support stops being a sturdy
     *  face — block removed, neighbour swapped for a non-sturdy
     *  one, etc. Returning [Blocks.AIR] from [updateShape]
     *  triggers vanilla's standard removal path (a `destroyBlock`
     *  through [Block.updateOrDestroy]). */
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

    /** Kick off the growth chain. Fires for both fresh placements
     *  (manager grow, /setblock, …) and the transition from one
     *  bud age to the next — distinguish via [oldState] so we
     *  don't double-schedule when [tick] advances the bud. */
    override fun onPlace(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        oldState: BlockState,
        isMoving: Boolean,
    ) {
        super.onPlace(state, level, pos, oldState, isMoving)
        if (level.isClientSide) return
        // Age-advance transitions from inside [tick] already
        // schedule the next tick before returning; an unguarded
        // schedule here would double the growth rate after each
        // advance.
        if (oldState.`is`(this)) return
        scheduleNextGrow(level, pos, level.random)
    }

    /** Scheduled-tick callback. Advances [AGE] and reschedules,
     *  or matures into [EKBlocks.WOGOR_WOOD] when at [MAX_AGE]. */
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
            // Mature: replace with a Wogor Wood log whose AXIS lines
            // up with the bud's growth direction. UP/DOWN buds → Y,
            // NORTH/SOUTH → Z, EAST/WEST → X.
            val facing = state.getValue(FACING)
            val wogor = EKBlocks.WOGOR_WOOD.get().defaultBlockState()
                .setValue(BlockStateProperties.AXIS, facing.axis)
            level.setBlock(pos, wogor, UPDATE_ALL)
        }
    }

    /** Queue the next growth tick at a uniformly random delay in
     *  the [GROW_DELAY_MIN_TICKS, GROW_DELAY_MAX_TICKS] range. The
     *  small jitter keeps adjacent buds from advancing in
     *  lockstep — visually nicer for clumps of growth. */
    private fun scheduleNextGrow(level: Level, pos: BlockPos, random: RandomSource) {
        val span = GROW_DELAY_MAX_TICKS - GROW_DELAY_MIN_TICKS + 1
        val delay = GROW_DELAY_MIN_TICKS + random.nextInt(span)
        level.scheduleTick(pos, this, delay)
    }

    companion object {
        val AGE: IntegerProperty = BlockStateProperties.AGE_2
        val FACING: DirectionProperty = BlockStateProperties.FACING
        const val MAX_AGE: Int = 2

        /** Inclusive bounds on the per-stage scheduled-tick delay
         *  (game ticks). Three stage-transitions × 5–7 ticks each
         *  ⇒ full maturation in 15–21 ticks (about ¾ s at 20 tps).
         *  Tuned so the overall bud-to-Wogor-Wood time matches the
         *  Heart of the Wild's 15–20-tick growth window. */
        const val GROW_DELAY_MIN_TICKS: Int = 5
        const val GROW_DELAY_MAX_TICKS: Int = 7

        /** Bud cube side length in 16ths-of-a-block per AGE.
         *  4 → "small", 8 → "medium", 12 → "almost full". */
        private val SIZES: IntArray = intArrayOf(4, 8, 12)

        /** Precomputed `SHAPES[age][facing.ordinal]` so the shape
         *  lookup in [getShape] is a couple of array indexes
         *  rather than allocating a VoxelShape per render frame. */
        private val SHAPES: Array<Array<VoxelShape>> = Array(SIZES.size) { age ->
            val size = SIZES[age]
            Array(Direction.values().size) { dirOrdinal ->
                shapeFor(size, Direction.values()[dirOrdinal])
            }
        }

        /** Build a single VoxelShape pinning a cube of side [size]
         *  (in 16ths) to the FACE indicated by [facing]. Under
         *  this block's convention [facing] *points at* the
         *  support, so the bud sits on the cell-half **toward
         *  the support**: `FACING=UP` ⇒ support above ⇒ bud at
         *  high Y; `FACING=NORTH` ⇒ support to the north ⇒ bud
         *  at low Z (north side of cell); etc. */
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
