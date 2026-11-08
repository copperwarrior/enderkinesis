package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.BucketPickup
import net.minecraft.world.level.block.LiquidBlockContainer
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.blockentity.RuneBlockEntity
import org.shipwrights.enderkinesis.spell.RuneTriggers
import java.util.Optional

/**
 * [FACING] points out of the host face, toward the rune. Ship assembly, splits and merges must
 * not set runes off; see [RuneTriggers.suppressMoves].
 */
class RuneBlock(properties: Properties) : BaseEntityBlock(properties), LiquidBlockContainer, BucketPickup {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP).setValue(WATERLOGGED, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, WATERLOGGED)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = RuneBlockEntity(pos, state)

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.ENTITYBLOCK_ANIMATED

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(FACING))

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        Shapes.empty()

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Vanilla still routes fluid state through this override.
    override fun getFluidState(state: BlockState): FluidState =
        if (state.getValue(WATERLOGGED)) Fluids.WATER.getSource(false) else super.getFluidState(state)

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    /** An ordinary neighbour update or host weathering, growth or work must not set the rune off. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun updateShape(
        state: BlockState, direction: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level))
        if (level is ServerLevel && direction == state.getValue(FACING).opposite) {
            val rune = level.getBlockEntity(pos) as? RuneBlockEntity
            if (rune != null && rune.hostChanged(neighborState)) RuneTriggers.trigger(level, pos)
        }
        return state
    }

    /** [RuneTriggers.trigger] ignores a rune that an Abyssal Pocket phased. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity) {
        if (level !is ServerLevel || entity.isSpectator) return
        if ((level.getBlockEntity(pos) as? RuneBlockEntity)?.isArmed() != true) return
        val decal = SHAPES.getValue(state.getValue(FACING)).bounds().move(pos)
        if (entity.boundingBox.intersects(decal)) RuneTriggers.trigger(level, pos)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (level is ServerLevel && !state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? RuneBlockEntity)?.let { RuneTriggers.onRemoved(level, pos, it) }
        }
        @Suppress("DEPRECATION") // Vanilla still routes onRemove through the deprecated override.
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    // Any fluid sets the rune off. Water waterlogs it, so the water stays after the rune goes.
    // Other fluids stay out until the rune goes on the next tick.
    override fun canPlaceLiquid(level: BlockGetter, pos: BlockPos, state: BlockState, fluid: Fluid): Boolean = true

    override fun placeLiquid(level: LevelAccessor, pos: BlockPos, state: BlockState, fluidState: FluidState): Boolean {
        val waterlogged = state.getValue(WATERLOGGED)
        if (waterlogged && fluidState.isSource) return false
        val fills = !waterlogged && fluidState.type == Fluids.WATER
        if (fills && !level.isClientSide) {
            level.setBlock(pos, state.setValue(WATERLOGGED, true), UPDATE_ALL)
            level.scheduleTick(pos, fluidState.type, fluidState.type.getTickDelay(level))
        }
        if (level is ServerLevel) RuneTriggers.trigger(level, pos)
        return fills
    }

    override fun pickupBlock(level: LevelAccessor, pos: BlockPos, state: BlockState): ItemStack {
        if (!state.getValue(WATERLOGGED)) return ItemStack.EMPTY
        level.setBlock(pos, state.setValue(WATERLOGGED, false), UPDATE_ALL)
        if (level is ServerLevel) RuneTriggers.trigger(level, pos)
        return ItemStack(Items.WATER_BUCKET)
    }

    override fun getPickupSound(): Optional<SoundEvent> = Fluids.WATER.pickupSound

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) createTickerHelper(type, EKBlockEntities.RUNE.get()) { l, p, s, rune -> RuneBlockEntity.clientTick?.invoke(l, p, s, rune) }
        else null

    companion object {
        val FACING: DirectionProperty = BlockStateProperties.FACING
        val WATERLOGGED: BooleanProperty = BlockStateProperties.WATERLOGGED

        fun facing(state: BlockState): Direction =
            if (state.hasProperty(FACING)) state.getValue(FACING) else Direction.UP

        private const val THICKNESS = 1.0

        private val SHAPES: Map<Direction, VoxelShape> = mapOf(
            Direction.UP to box(0.0, 0.0, 0.0, 16.0, THICKNESS, 16.0),
            Direction.DOWN to box(0.0, 16.0 - THICKNESS, 0.0, 16.0, 16.0, 16.0),
            Direction.NORTH to box(0.0, 0.0, 16.0 - THICKNESS, 16.0, 16.0, 16.0),
            Direction.SOUTH to box(0.0, 0.0, 0.0, 16.0, 16.0, THICKNESS),
            Direction.EAST to box(0.0, 0.0, 0.0, THICKNESS, 16.0, 16.0),
            Direction.WEST to box(16.0 - THICKNESS, 0.0, 0.0, 16.0, 16.0, 16.0),
        )
    }
}
