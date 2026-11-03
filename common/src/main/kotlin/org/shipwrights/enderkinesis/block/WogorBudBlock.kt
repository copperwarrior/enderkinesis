package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.SimpleWaterloggedBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Growth faces are not in the block state: every sturdy neighbour face grows, read from the world
 * ([supportMask]). AGE 0 is the root-strand stage. Uses scheduled ticks, NOT random ticks; the delay
 * comes from [growDelay] so that the client tracker can calculate the same schedule.
 */
class WogorBudBlock(properties: BlockBehaviour.Properties) : Block(properties), SimpleWaterloggedBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(AGE, 0).setValue(WATERLOGGED, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(AGE, WATERLOGGED)
    }

    // WogorBudRenderer draws the bud; the model only supplies the break-particle texture.
    @Deprecated("Deprecated in Java")
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.INVISIBLE

    @Deprecated("Deprecated in Java")
    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape {
        // Off the server thread, a neighbour read joins the server thread and deadlocks the light engine.
        if (level is ServerLevel && !level.server.isSameThread) return Shapes.empty()
        return SHAPES[state.getValue(AGE)][supportMask(level, pos)]
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION") // Vanilla still routes fluid state through this override.
    override fun getFluidState(state: BlockState): FluidState =
        if (state.getValue(WATERLOGGED)) Fluids.WATER.getSource(false) else super.getFluidState(state)

    /** A noCollission block's vanilla default returns true here, so flowing water would erase the bud. */
    @Deprecated("Deprecated in Java")
    override fun canBeReplaced(state: BlockState, fluid: Fluid): Boolean = false

    @Deprecated("Deprecated in Java")
    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean =
        supportMask(level, pos) != 0

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION") // Vanilla still routes canSurvive and updateShape through the deprecated overrides.
    override fun updateShape(
        state: BlockState,
        direction: Direction,
        neighborState: BlockState,
        level: LevelAccessor,
        pos: BlockPos,
        neighborPos: BlockPos,
    ): BlockState {
        val waterlogged = state.getValue(WATERLOGGED)
        if (waterlogged) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level))
        return when {
            canSurvive(state, level, pos) -> super.updateShape(state, direction, neighborState, level, pos, neighborPos)
            waterlogged -> Blocks.WATER.defaultBlockState()
            else -> Blocks.AIR.defaultBlockState()
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION") // Vanilla still routes onPlace through the deprecated override.
    override fun onPlace(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        oldState: BlockState,
        isMoving: Boolean,
    ) {
        super.onPlace(state, level, pos, oldState, isMoving)
        if (level.isClientSide) return
        // `tick` schedules its own next grow; AGE and WATERLOGGED changes must not schedule a second one.
        if (oldState.`is`(this)) return
        level.scheduleTick(pos, this, growDelay(pos, 0))
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION") // Vanilla still routes onRemove through the deprecated override.
    override fun onRemove(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        newState: BlockState,
        isMoving: Boolean,
    ) {
        // A bud removed before maturity must not leave a target for a later bud.
        if (!level.isClientSide && !newState.`is`(this)) WogorBudTargetData.take(level, pos)
        super.onRemove(state, level, pos, newState, isMoving)
    }

    @Deprecated("Deprecated in Java")
    override fun tick(
        state: BlockState,
        level: ServerLevel,
        pos: BlockPos,
        random: RandomSource,
    ) {
        val age = state.getValue(AGE)
        // Peek, so that the per-target terminal age does not remove the entry.
        val target = WogorBudTargetData.peek(level, pos)
        val terminalAge = if (target != null) terminalAgeFor(target) else MAX_AGE
        if (age < terminalAge) {
            level.setBlock(pos, state.setValue(AGE, age + 1), UPDATE_CLIENTS)
            level.scheduleTick(pos, this, growDelay(pos, age + 1))
        } else if (target != null) {
            WogorBudTargetData.take(level, pos)
            // The destroy hook filters only bud → wood; a bud → variant change must not enroll.
            HeartOfTheWildManager.withEnrollSuppressed { level.setBlock(pos, target, UPDATE_ALL) }
        } else {
            val wogor = EKBlocks.WOGOR_WOOD.get().defaultBlockState()
                .setValue(BlockStateProperties.AXIS, woodAxis(supportMask(level, pos)))
            level.setBlock(pos, wogor, UPDATE_ALL)
        }
    }

    /** AGE at which the bud matures into [target]: small shapes after AGE 1 (4 px), slab / wall after AGE 2. */
    private fun terminalAgeFor(target: BlockState): Int = when (target.block) {
        EKBlocks.WOGOR_WOOD_BUTTON.get(),
        EKBlocks.WOGOR_WOOD_PANE.get(),
        EKBlocks.WOGOR_WOOD_FENCE.get() -> 1
        EKBlocks.WOGOR_WOOD_SLAB.get(),
        EKBlocks.WOGOR_WOOD_WALL.get() -> 2
        else -> MAX_AGE
    }

    companion object {
        val AGE: IntegerProperty = BlockStateProperties.AGE_3
        val WATERLOGGED: BooleanProperty = BlockStateProperties.WATERLOGGED
        const val MAX_AGE: Int = 3

        /** Gates WHERE new buds can be planted. `canSurvive` checks only for a sturdy face, so a placed bud
         *  does not break when its support is not tagged. Both growers bypass this for the seed bud. */
        val WOGOR_BUD_GROWABLE: TagKey<Block> = TagKey.create(
            Registries.BLOCK, ResourceLocation("enderkinesis", "wogor_bud_growable"),
        )

        const val GROW_DELAY_MIN_TICKS: Int = 5
        const val GROW_DELAY_MAX_TICKS: Int = 7

        /** Ticks that AGE [age] lasts at [pos]. Deterministic, because the client tracker calculates it too. */
        @Suppress("DEPRECATION") // Mth.getSeed is the vanilla position hash; Mojang marks it internal only.
        fun growDelay(pos: BlockPos, age: Int): Int {
            val span = GROW_DELAY_MAX_TICKS - GROW_DELAY_MIN_TICKS + 1
            val h = Mth.getSeed(pos.x, pos.y + age * AGE_SALT, pos.z) ushr HASH_SHIFT
            return GROW_DELAY_MIN_TICKS + Math.floorMod(h, span.toLong()).toInt()
        }

        /** Bit `1 shl direction.ordinal` is set for each neighbour whose face toward [pos] is sturdy. */
        fun supportMask(level: BlockGetter, pos: BlockPos, n: BlockPos.MutableBlockPos = BlockPos.MutableBlockPos()): Int {
            var mask = 0
            for (dir in DIRECTIONS) {
                n.setWithOffset(pos, dir)
                if (level.getBlockState(n).isFaceSturdy(level, n, dir.opposite)) mask = mask or (1 shl dir.ordinal)
            }
            return mask
        }

        /** The block state for a new bud at [pos], waterlogged if a water source is there. */
        fun newBud(level: BlockGetter, pos: BlockPos, bud: Block = EKBlocks.WOGOR_BUD.get()): BlockState {
            val fluid = level.getFluidState(pos)
            return bud.defaultBlockState().setValue(WATERLOGGED, fluid.`is`(Fluids.WATER) && fluid.isSource)
        }

        /** The axis with the most supported faces; ties go Y, then Z, then X. */
        fun woodAxis(mask: Int): Direction.Axis {
            fun count(a: Direction, b: Direction) = (mask shr a.ordinal and 1) + (mask shr b.ordinal and 1)
            val y = count(Direction.DOWN, Direction.UP)
            val z = count(Direction.NORTH, Direction.SOUTH)
            val x = count(Direction.WEST, Direction.EAST)
            return when {
                y >= z && y >= x -> Direction.Axis.Y
                z >= x -> Direction.Axis.Z
                else -> Direction.Axis.X
            }
        }

        private val DIRECTIONS: Array<Direction> = Direction.values()
        private const val AGE_SALT = 7919
        private const val HASH_SHIFT = 16

        /** Outline size (px) of each AGE; AGE 0 is the root stage with a seedling-sized outline. */
        private val SIZES: IntArray = intArrayOf(4, 4, 8, 12)

        /** [AGE][support mask]: the union of the per-face shapes; precomputed so getShape does not allocate. */
        private val SHAPES: Array<Array<VoxelShape>> = Array(SIZES.size) { age ->
            Array(1 shl DIRECTIONS.size) { mask ->
                DIRECTIONS.filter { mask shr it.ordinal and 1 == 1 }
                    .fold(Shapes.empty()) { acc, dir -> Shapes.or(acc, shapeFor(SIZES[age], dir)) }
                    .optimize()
            }
        }

        /** [support] is the direction from the bud to the block it grows from. */
        private fun shapeFor(size: Int, support: Direction): VoxelShape {
            val half = size / 2.0
            val lo = 8.0 - half
            val hi = 8.0 + half
            val s = size.toDouble()
            return when (support) {
                Direction.UP -> box(lo, 16.0 - s, lo, hi, 16.0, hi)
                Direction.DOWN -> box(lo, 0.0, lo, hi, s, hi)
                Direction.NORTH -> box(lo, lo, 0.0, hi, hi, s)
                Direction.SOUTH -> box(lo, lo, 16.0 - s, hi, hi, 16.0)
                Direction.EAST -> box(16.0 - s, lo, lo, 16.0, hi, hi)
                Direction.WEST -> box(0.0, lo, lo, s, hi, hi)
            }
        }
    }
}
