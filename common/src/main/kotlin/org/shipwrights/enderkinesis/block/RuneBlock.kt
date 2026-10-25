package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.sounds.SoundEvent
import net.minecraft.util.RandomSource
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
import org.joml.Vector3f
import org.shipwrights.enderkinesis.blockentity.RuneBlockEntity
import org.shipwrights.enderkinesis.spell.RuneTriggers
import java.util.Optional

/**
 * A rune: a spell trap written on one face of a block, as a thin glowing decal with no
 * collision. [FACING] points out of the face it's written on, toward the rune. Any of
 * these sets it off, casting its spell as an Area burst around itself on the next tick:
 *
 * - anything touching the decal: entities (the caster too), items, projectiles, and ships
 *   (see [org.shipwrights.enderkinesis.spell.RuneCollisionRouter]);
 * - the rune being mined, exploded, or pushed by a piston (it breaks rather than moves);
 * - its host block being broken, replaced, pushed, or moved by a spell;
 * - any change to its fluid: water flowing in, or a bucket taking its water.
 *
 * Ship assembly, splits and merges move runes without setting them off; see
 * [RuneTriggers.suppressMoves].
 */
class RuneBlock(properties: Properties) : BaseEntityBlock(properties), LiquidBlockContainer, BucketPickup {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP).setValue(WATERLOGGED, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, WATERLOGGED)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = RuneBlockEntity(pos, state)

    @Deprecated("Deprecated in Java")
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    @Deprecated("Deprecated in Java")
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(FACING))

    @Deprecated("Deprecated in Java")
    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        Shapes.empty()

    @Deprecated("Deprecated in Java")
    override fun getFluidState(state: BlockState): FluidState =
        if (state.getValue(WATERLOGGED)) Fluids.WATER.getSource(false) else super.getFluidState(state)

    @Deprecated("Deprecated in Java")
    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    @Deprecated("Deprecated in Java")
    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    /** A changed host sets the rune off; an ordinary neighbour update doesn't. */
    @Deprecated("Deprecated in Java")
    override fun updateShape(
        state: BlockState, direction: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level))
        if (level is ServerLevel && direction == state.getValue(FACING).opposite) {
            val rune = level.getBlockEntity(pos) as? RuneBlockEntity
            if (rune != null && rune.host != neighborState.block) RuneTriggers.trigger(level, pos)
        }
        return state
    }

    @Deprecated("Deprecated in Java")
    override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity) {
        if (level !is ServerLevel || entity.isSpectator) return
        val decal = SHAPES.getValue(state.getValue(FACING)).bounds().move(pos)
        if (entity.boundingBox.intersects(decal)) RuneTriggers.trigger(level, pos)
    }

    @Deprecated("Deprecated in Java")
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (level is ServerLevel && !state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? RuneBlockEntity)?.let { RuneTriggers.onRemoved(level, pos, it) }
        }
        @Suppress("DEPRECATION")
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    // Any fluid may try to enter, and its arrival sets the rune off. The rune keeps the fluid
    // out until it's gone the next tick, then the fluid flows in normally.
    override fun canPlaceLiquid(level: BlockGetter, pos: BlockPos, state: BlockState, fluid: Fluid): Boolean = true

    override fun placeLiquid(level: LevelAccessor, pos: BlockPos, state: BlockState, fluidState: FluidState): Boolean {
        if (level is ServerLevel && !(fluidState.isSource && state.getValue(WATERLOGGED))) RuneTriggers.trigger(level, pos)
        return false
    }

    override fun pickupBlock(level: LevelAccessor, pos: BlockPos, state: BlockState): ItemStack {
        if (!state.getValue(WATERLOGGED)) return ItemStack.EMPTY
        level.setBlock(pos, state.setValue(WATERLOGGED, false), UPDATE_ALL)
        if (level is ServerLevel) RuneTriggers.trigger(level, pos)
        return ItemStack(Items.WATER_BUCKET)
    }

    override fun getPickupSound(): Optional<SoundEvent> = Fluids.WATER.pickupSound

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(PARTICLE_CHANCE) != 0) return
        val rune = level.getBlockEntity(pos) as? RuneBlockEntity ?: return
        val shape = SHAPES.getValue(state.getValue(FACING)).bounds()
        val x = pos.x + shape.minX + random.nextDouble() * shape.xsize
        val y = pos.y + shape.minY + random.nextDouble() * shape.ysize
        val z = pos.z + shape.minZ + random.nextDouble() * shape.zsize
        val tint = rune.spell.tint()
        val colour = Vector3f(((tint shr 16) and 0xFF) / 255f, ((tint shr 8) and 0xFF) / 255f, (tint and 0xFF) / 255f)
        level.addParticle(DustParticleOptions(colour, 0.8f), x, y, z, 0.0, 0.02, 0.0)
    }

    companion object {
        val FACING: DirectionProperty = BlockStateProperties.FACING
        val WATERLOGGED: BooleanProperty = BlockStateProperties.WATERLOGGED

        fun facing(state: BlockState): Direction =
            if (state.hasProperty(FACING)) state.getValue(FACING) else Direction.UP

        private const val PARTICLE_CHANCE = 4
        private const val THICKNESS = 1.0

        /** The decal, flat against the host's face. */
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
