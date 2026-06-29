package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * **Binding Roots** — a Wohlon-themed cobweb stand-in that also serves as the binding anchor
 * for Valkyrien Skies ship-merge into the host body.
 *
 * **Direction model.** Uses [BlockStateProperties.FACING] (six directions) rather than
 * [net.minecraft.world.level.block.RotatedPillarBlock]'s `AXIS` (three) so the slab can sit
 * on the specific face the player clicked. `FACING` = direction the cross extends (the
 * "front" of the block, away from the host). Slab is on the OPPOSITE face — the contact face.
 * That mirrors vanilla end-rod placement (`ctx.getClickedFace()` for FACING).
 *
 * **Entity behaviour.** Cobweb-style: no collision on the cross volume, but `entityInside`
 * stuns motion with the same vector vanilla `WebBlock` uses. The slab half is solid so an
 * entity can stand on the slab and one root can rest on another.
 *
 * **Merger.** `BindingRootsMerger` reads `FACING` to compute the world-space "front" vector
 * of the cross; two roots merge when their fronts are anti-parallel in world space (i.e.,
 * pointing at each other) within tolerance.
 */
class BindingRootsBlock(properties: Properties) : Block(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.clickedFace)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    /** Half-block slab on the face OPPOSITE the cross direction (i.e., the contact face the
     *  block was placed against). The cross volume is non-collidable but applies
     *  [entityInside]'s stuck-in-block effect. */
    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = shapeFor(state.getValue(FACING))

    /** Block outline / selection shape. Vanilla `Block` defaults to `Shapes.block()` (a full
     *  cube), which VS2 picks up during ship voxelization — so the cross volume ends up in
     *  the ship's collision hull even though `getCollisionShape` doesn't include it. Mirror
     *  the slab shape here so VS2 only voxelizes the actual solid part. */
    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = shapeFor(state.getValue(FACING))

    private fun shapeFor(facing: Direction): VoxelShape = when (facing) {
        Direction.UP -> SHAPE_DOWN
        Direction.DOWN -> SHAPE_UP
        Direction.NORTH -> SHAPE_SOUTH
        Direction.SOUTH -> SHAPE_NORTH
        Direction.EAST -> SHAPE_WEST
        Direction.WEST -> SHAPE_EAST
    }

    override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity) {
        entity.makeStuckInBlock(state, STUCK_VECTOR)
        if (entity is LivingEntity) {
            entity.fallDistance = 0f
        }
    }

    override fun setPlacedBy(
        level: Level,
        pos: BlockPos,
        state: BlockState,
        placer: LivingEntity?,
        stack: ItemStack,
    ) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level is ServerLevel) {
            BindingRootsMerger.registerPosition(level, pos)
        }
    }

    override fun onRemove(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        newState: BlockState,
        isMoving: Boolean,
    ) {
        if (!state.`is`(newState.block) && level is ServerLevel) {
            BindingRootsMerger.unregisterPosition(level, pos)
            // No-op if this block isn't anchoring a joint — releases the joint otherwise.
            BindingRootsMerger.onJointAnchorBroken(level, pos)
        }
        super.onRemove(state, level, pos, newState, isMoving)
    }

    companion object {
        /** Direction the cross extends (= the "front" of the block, away from contact). The
         *  slab lives on the opposite face. */
        val FACING: DirectionProperty = BlockStateProperties.FACING

        private val STUCK_VECTOR: Vec3 = Vec3(0.25, 0.05, 0.25)

        // Slab on each face — named after the side the slab occupies, not the cross direction.
        // 0.8× of the full half-slab: face dims 16→12.8 (centred, 1.6 inset both sides),
        // depth 8→6.4 (anchored AT the contact face so the slab stays flush with whatever
        // it was placed against).
        private val SHAPE_DOWN: VoxelShape = Block.box(1.6, 0.0, 1.6, 14.4, 6.4, 14.4)
        private val SHAPE_UP: VoxelShape = Block.box(1.6, 9.6, 1.6, 14.4, 16.0, 14.4)
        private val SHAPE_NORTH: VoxelShape = Block.box(1.6, 1.6, 0.0, 14.4, 14.4, 6.4)
        private val SHAPE_SOUTH: VoxelShape = Block.box(1.6, 1.6, 9.6, 14.4, 14.4, 16.0)
        private val SHAPE_WEST: VoxelShape = Block.box(0.0, 1.6, 1.6, 6.4, 14.4, 14.4)
        private val SHAPE_EAST: VoxelShape = Block.box(9.6, 1.6, 1.6, 16.0, 14.4, 14.4)
    }
}
