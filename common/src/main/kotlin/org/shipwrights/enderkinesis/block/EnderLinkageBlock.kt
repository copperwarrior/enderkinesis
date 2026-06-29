package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Ender Linkage. A six-directional decorative connector — like a vanilla fence, but with
 * the full set of `NORTH / SOUTH / EAST / WEST / UP / DOWN` connection booleans.
 *
 * Carries a [STABLE] flag. Placed linkages start stable; the assembly mixin flips it to
 * false the moment the linkage is "transported" into a ship. Unstable linkages tick
 * themselves once a game tick and break, but the global [lastBreakTick] gate ensures at
 * most one linkage anywhere disintegrates per server tick — so a freshly-shipified
 * cluster of N linkages takes N ticks to vanish, one popping off at a time.
 */
class EnderLinkageBlock(properties: Properties) : Block(properties) {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(NORTH, false)
                .setValue(SOUTH, false)
                .setValue(EAST, false)
                .setValue(WEST, false)
                .setValue(UP, false)
                .setValue(DOWN, false)
                .setValue(STABLE, true)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(NORTH, SOUTH, EAST, WEST, UP, DOWN, STABLE)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        val level = context.level
        val pos = context.clickedPos
        return defaultBlockState()
            .setValue(NORTH, connectsTo(level, pos.north(), Direction.SOUTH))
            .setValue(SOUTH, connectsTo(level, pos.south(), Direction.NORTH))
            .setValue(EAST,  connectsTo(level, pos.east(),  Direction.WEST))
            .setValue(WEST,  connectsTo(level, pos.west(),  Direction.EAST))
            .setValue(UP,    connectsTo(level, pos.above(), Direction.DOWN))
            .setValue(DOWN,  connectsTo(level, pos.below(), Direction.UP))
    }

    @Deprecated("Deprecated in Java")
    override fun updateShape(
        state: BlockState, dir: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState {
        val prop = directionProperty(dir) ?: return state
        return state.setValue(prop, connectsToState(level, neighborPos, dir.opposite, neighborState))
    }

    @Deprecated("Deprecated in Java")
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape =
        SHAPE_CACHE[shapeIndex(state)]

    /** No collision — players and entities pass straight through. The visual + selection
     *  outline still come from [getShape]; only the entity-collision query is empty. */
    @Deprecated("Deprecated in Java")
    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape =
        Shapes.empty()

    /** The linkage IS a sturdy face on every connected direction. `getBlockSupportShape`
     *  is what `SupportType.FULL.isSupporting` queries (via [Block.isFaceFull]) — by
     *  returning a 16×16×1 slab covering each connected face, the linkage tests sturdy
     *  there for every vanilla check (and for our flood-fill gate). The visual /
     *  collision shapes stay thin via the other overrides — players still walk through
     *  the gaps; only the sturdy-face query sees the full slabs. */
    @Deprecated("Deprecated in Java")
    override fun getBlockSupportShape(state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape =
        SUPPORT_SHAPE_CACHE[shapeIndex(state)]

    /** Schedule the first disintegration tick the moment an unstable linkage lands —
     *  whether that's a freshly-assembled ship block landing in its shipyard chunk, or
     *  any other path that places it. */
    @Deprecated("Deprecated in Java")
    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, isMoving: Boolean) {
        super.onPlace(state, level, pos, oldState, isMoving)
        if (!state.getValue(STABLE) && level is ServerLevel) {
            level.scheduleTick(pos, this, INITIAL_DELAY_TICKS)
        }
    }

    /** Once-per-game-tick disintegration with a global single-fire gate. If somebody
     *  else already broke a linkage this tick, defer to next tick; otherwise claim
     *  the tick by stamping [lastBreakTick] and warp the block out — enderman
     *  teleport sound + portal particles, then [Blocks.AIR] in place. */
    @Deprecated("Deprecated in Java")
    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (state.getValue(STABLE)) return
        val now = level.gameTime
        if (lastBreakTick < now) {
            lastBreakTick = now
            val cx = pos.x + 0.5
            val cy = pos.y + 0.5
            val cz = pos.z + 0.5
            // Portal particles + enderman teleport sound — same flavour the vanilla
            // enderman uses when it warps. No vanilla break particles / glass shatter.
            level.sendParticles(ParticleTypes.PORTAL, cx, cy, cz, 32, 0.5, 0.5, 0.5, 0.5)
            level.playSound(null, pos, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 1.0f)
            // UPDATE_ALL: neighbour linkages re-evaluate their connection booleans now
            // that this one is gone.
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
        } else {
            level.scheduleTick(pos, this, 1)
        }
    }

    /** Pack the six connection booleans into a 6-bit index into [SHAPE_CACHE]. */
    private fun shapeIndex(state: BlockState): Int {
        var idx = 0
        if (state.getValue(NORTH)) idx = idx or BIT_NORTH
        if (state.getValue(SOUTH)) idx = idx or BIT_SOUTH
        if (state.getValue(EAST))  idx = idx or BIT_EAST
        if (state.getValue(WEST))  idx = idx or BIT_WEST
        if (state.getValue(UP))    idx = idx or BIT_UP
        if (state.getValue(DOWN))  idx = idx or BIT_DOWN
        return idx
    }

    private fun connectsTo(level: BlockGetter, neighborPos: BlockPos, faceTowardLinkage: Direction): Boolean =
        connectsToState(level, neighborPos, faceTowardLinkage, level.getBlockState(neighborPos))

    private fun connectsToState(
        level: BlockGetter, neighborPos: BlockPos, faceTowardLinkage: Direction, neighborState: BlockState,
    ): Boolean {
        if (neighborState.block is EnderLinkageBlock) return true
        return neighborState.isFaceSturdy(level, neighborPos, faceTowardLinkage)
    }

    /** Compute a fully-connected default state for a fresh linkage at [pos] — what
     *  [getStateForPlacement] returns, but callable without a [BlockPlaceContext]
     *  (used by the Packing Pearl placer to spawn linkages from a queue). */
    fun freshStateAt(level: BlockGetter, pos: BlockPos): BlockState = defaultBlockState()
        .setValue(NORTH, connectsTo(level, pos.north(), Direction.SOUTH))
        .setValue(SOUTH, connectsTo(level, pos.south(), Direction.NORTH))
        .setValue(EAST,  connectsTo(level, pos.east(),  Direction.WEST))
        .setValue(WEST,  connectsTo(level, pos.west(),  Direction.EAST))
        .setValue(UP,    connectsTo(level, pos.above(), Direction.DOWN))
        .setValue(DOWN,  connectsTo(level, pos.below(), Direction.UP))

    private fun directionProperty(dir: Direction): BooleanProperty? = when (dir) {
        Direction.NORTH -> NORTH
        Direction.SOUTH -> SOUTH
        Direction.EAST  -> EAST
        Direction.WEST  -> WEST
        Direction.UP    -> UP
        Direction.DOWN  -> DOWN
    }

    companion object {
        val NORTH: BooleanProperty = BlockStateProperties.NORTH
        val SOUTH: BooleanProperty = BlockStateProperties.SOUTH
        val EAST:  BooleanProperty = BlockStateProperties.EAST
        val WEST:  BooleanProperty = BlockStateProperties.WEST
        val UP:    BooleanProperty = BlockStateProperties.UP
        val DOWN:  BooleanProperty = BlockStateProperties.DOWN

        @JvmField
        val STABLE: BooleanProperty = BooleanProperty.create("stable")

        // 20-tick initial delay so the player sees the ship form before the disintegration
        // sweep starts.
        private const val INITIAL_DELAY_TICKS = 20

        /** Global one-per-tick gate. Block ticks run on the main server thread, so a
         *  plain mutable Long is fine — no atomic / volatile needed. */
        @JvmStatic
        private var lastBreakTick: Long = Long.MIN_VALUE

        /** Flip a linkage at [pos] from stable to unstable, the moment it's "transported"
         *  into a ship. Called from the assembly mixin against the original world
         *  positions, before ShipAssembler copies the blocks into shipyard chunks —
         *  StructureTemplate preserves block state across the copy, so the linkages
         *  arrive on the ship already-unstable and `onPlace` schedules their first
         *  disintegration tick on the new shipyard position. */
        @JvmStatic
        fun markUnstable(level: ServerLevel, pos: BlockPos) {
            val state = level.getBlockState(pos)
            if (state.block !is EnderLinkageBlock) return
            if (!state.getValue(STABLE)) return
            // UPDATE_CLIENTS only: don't trigger neighbor-shape updates — we just want
            // the property flipped before ShipAssembler reads the block state.
            level.setBlock(pos, state.setValue(STABLE, false), 2)
        }

        private const val BIT_NORTH = 1
        private const val BIT_SOUTH = 2
        private const val BIT_EAST  = 4
        private const val BIT_WEST  = 8
        private const val BIT_UP    = 16
        private const val BIT_DOWN  = 32

        // Block-shape pieces (pixel space: 1 voxel = 1/16 of a block) tracking the new
        // center-cube model: a 4×4×4 core that is always present, plus a 2×2 arm out to
        // each connected face. When no direction connects, just the central pip is solid.
        private val CORE:      VoxelShape = Block.box(6.0, 6.0, 6.0, 10.0, 10.0, 10.0)
        private val NORTH_ARM: VoxelShape = Block.box(7.0, 7.0, 0.0,  9.0,  9.0, 6.0)
        private val SOUTH_ARM: VoxelShape = Block.box(7.0, 7.0, 10.0, 9.0,  9.0, 16.0)
        private val EAST_ARM:  VoxelShape = Block.box(10.0, 7.0, 7.0, 16.0, 9.0, 9.0)
        private val WEST_ARM:  VoxelShape = Block.box(0.0, 7.0, 7.0,  6.0,  9.0, 9.0)
        private val UP_ARM:    VoxelShape = Block.box(7.0, 10.0, 7.0, 9.0, 16.0, 9.0)
        private val DOWN_ARM:  VoxelShape = Block.box(7.0, 0.0, 7.0,  9.0,  6.0, 9.0)

        /** 64-entry lookup table indexed by [shapeIndex] — every combination of the six
         *  connection booleans has its shape pre-`Shapes.or`d at class init so the per-
         *  call [getShape] / [getCollisionShape] cost is a single array index. */
        private val SHAPE_CACHE: Array<VoxelShape> = Array(64) { idx ->
            var shape: VoxelShape = CORE
            if (idx and BIT_NORTH != 0) shape = Shapes.or(shape, NORTH_ARM)
            if (idx and BIT_SOUTH != 0) shape = Shapes.or(shape, SOUTH_ARM)
            if (idx and BIT_EAST  != 0) shape = Shapes.or(shape, EAST_ARM)
            if (idx and BIT_WEST  != 0) shape = Shapes.or(shape, WEST_ARM)
            if (idx and BIT_UP    != 0) shape = Shapes.or(shape, UP_ARM)
            if (idx and BIT_DOWN  != 0) shape = Shapes.or(shape, DOWN_ARM)
            shape
        }

        // Full-face slabs for the sturdy-face support shape. `Block.isFaceFull` needs the
        // shape's projection to cover the entire 16×16 face — a 1-pixel-thick slab does it.
        private val NORTH_FACE_FULL: VoxelShape = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 1.0)
        private val SOUTH_FACE_FULL: VoxelShape = Block.box(0.0, 0.0, 15.0, 16.0, 16.0, 16.0)
        private val EAST_FACE_FULL:  VoxelShape = Block.box(15.0, 0.0, 0.0, 16.0, 16.0, 16.0)
        private val WEST_FACE_FULL:  VoxelShape = Block.box(0.0, 0.0, 0.0, 1.0, 16.0, 16.0)
        private val UP_FACE_FULL:    VoxelShape = Block.box(0.0, 15.0, 0.0, 16.0, 16.0, 16.0)
        private val DOWN_FACE_FULL:  VoxelShape = Block.box(0.0, 0.0, 0.0, 16.0, 1.0, 16.0)

        /** Parallel 64-entry cache for the sturdy-face support shape. Separate from
         *  [SHAPE_CACHE] so the visual / collision shape stays thin while sturdiness
         *  queries see full faces on every connected direction. */
        private val SUPPORT_SHAPE_CACHE: Array<VoxelShape> = Array(64) { idx ->
            var shape: VoxelShape = Shapes.empty()
            if (idx and BIT_NORTH != 0) shape = Shapes.or(shape, NORTH_FACE_FULL)
            if (idx and BIT_SOUTH != 0) shape = Shapes.or(shape, SOUTH_FACE_FULL)
            if (idx and BIT_EAST  != 0) shape = Shapes.or(shape, EAST_FACE_FULL)
            if (idx and BIT_WEST  != 0) shape = Shapes.or(shape, WEST_FACE_FULL)
            if (idx and BIT_UP    != 0) shape = Shapes.or(shape, UP_FACE_FULL)
            if (idx and BIT_DOWN  != 0) shape = Shapes.or(shape, DOWN_FACE_FULL)
            shape
        }
    }
}
