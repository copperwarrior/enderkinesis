package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DirectionalBlock
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraft.world.level.BlockGetter
import org.shipwrights.enderkinesis.blockentity.ShulkerStrutBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/**
 * Shulker Strut — redstone-driven extender. POWER (0–15) maps to extension distance
 * (0–5 blocks) along [BlockStateProperties.FACING]; the BE pairs the base with a dynamic
 * [VsBody] for the lid and pulls it via a prismatic joint.
 *
 * Closed-state collision split (the trick that lets the lid live in the same world-block
 * volume as the base without a physics fight): this block's collision is a half-cube on
 * the side OPPOSITE FACING (where the base shell renders), and the lid body's collision is
 * a half-cube on the FACING side (where the lid shell renders). At extension=0 the two
 * halves union into the full block cube; at extension>0 they separate cleanly. The base
 * keeps a *full*-cube outline shape so it still selects / breaks like a normal block.
 */
class ShulkerStrutBlock(properties: BlockBehaviour.Properties) :
    DirectionalBlock(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(BlockStateProperties.FACING, Direction.UP)
                .setValue(BlockStateProperties.POWER, 0),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(BlockStateProperties.FACING, BlockStateProperties.POWER)
    }

    /** Same dispenser/Shulker-Puffer convention: clicking a face points the strut's lid
     *  extension *toward the player* — `FACING = opposite of nearest look direction`. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val initialPower = context.level.getBestNeighborSignal(context.clickedPos)
        return defaultBlockState()
            .setValue(BlockStateProperties.FACING, context.nearestLookingDirection.opposite)
            .setValue(BlockStateProperties.POWER, initialPower)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        ShulkerStrutBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.SHULKER_STRUT.get()) return null
        return BlockEntityTicker { lvl, p, st, be ->
            (be as? ShulkerStrutBlockEntity)?.serverTick(
                lvl as net.minecraft.server.level.ServerLevel, p, st,
            )
        }
    }

    /** Mirror the strongest neighbour signal into [BlockStateProperties.POWER] so the BE's
     *  next tick observes the new target extension. */
    @Deprecated("Deprecated in Java")
    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos,
        block: Block, fromPos: BlockPos, moving: Boolean,
    ) {
        if (level.isClientSide) return
        val newPower = level.getBestNeighborSignal(pos)
        if (state.getValue(BlockStateProperties.POWER) != newPower) {
            level.setBlock(pos, state.setValue(BlockStateProperties.POWER, newPower), 3)
        }
    }

    /** Half-cube on the side opposite FACING — matches where the base shell renders.
     *  We push the half-cube into `getShape` (not just `getCollisionShape`) because VS2's
     *  ship-block collision pipeline reads `getShape` for solid blocks (see
     *  `MassDatapackResolver` → `if (blockState.isSolid) getShape else getCollisionShape`);
     *  without this, a strut placed on a ship would carry a full-cube collider that fights
     *  the lid ship's half-cube collider. `getCollisionShape` is overridden to the same
     *  value for symmetry with world-mounted strut behaviour. */
    @Deprecated("Deprecated in Java")
    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = baseHalfCube(state.getValue(BlockStateProperties.FACING))

    @Deprecated("Deprecated in Java")
    override fun getCollisionShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = baseHalfCube(state.getValue(BlockStateProperties.FACING))

    /** Raycast targeting (break-select / right-click) uses the full cube so the player can
     *  still aim at the whole block even though the collision is a half-cube. */
    @Deprecated("Deprecated in Java")
    override fun getInteractionShape(
        state: BlockState, level: BlockGetter, pos: BlockPos,
    ): VoxelShape = Shapes.block()

    /** Right-click with a dye item paints the strut. Updates the base BE's `dyeColor`,
     *  which propagates to the lid BE and triggers a block update so both BERs pick the
     *  matching shulker entity texture (e.g. `entity/shulker/shulker_red.png`). */
    @Deprecated("Deprecated in Java")
    override fun use(
        state: BlockState, level: Level, pos: BlockPos, player: Player,
        hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        val held = player.getItemInHand(hand)
        val dye = held.item as? DyeItem ?: return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        val be = level.getBlockEntity(pos) as? ShulkerStrutBlockEntity ?: return InteractionResult.PASS
        if (be.dyeColor == dye.dyeColor) return InteractionResult.PASS
        be.setDyeColor(dye.dyeColor)
        if (!player.abilities.instabuild) held.shrink(1)
        return InteractionResult.CONSUME
    }

    /** Tear down the body when the base breaks so the lid doesn't get orphaned. */
    @Deprecated("Deprecated in Java")
    override fun onRemove(
        state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean,
    ) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? ShulkerStrutBlockEntity)?.releaseAndDestroyTop()
            super.onRemove(state, level, pos, newState, isMoving)
        }
    }

    companion object {
        /** Half-cube voxel shapes keyed by FACING. The base occupies the half of the block
         *  *opposite* the FACING direction, so the lid body's half-cube (in the FACING
         *  half) tiles flush against it without overlap. Built once and reused. */
        private val BASE_HALF_CUBE: Map<Direction, VoxelShape> = Direction.values().associateWith { d ->
            val n = d.normal
            // Voxel range [0..16] on each axis. Where n.<axis> = +1, base occupies 0..8;
            // where -1, base occupies 8..16; where 0, base spans the full 0..16.
            val xMin = if (n.x > 0) 0.0 else if (n.x < 0) 8.0 else 0.0
            val xMax = if (n.x > 0) 8.0 else if (n.x < 0) 16.0 else 16.0
            val yMin = if (n.y > 0) 0.0 else if (n.y < 0) 8.0 else 0.0
            val yMax = if (n.y > 0) 8.0 else if (n.y < 0) 16.0 else 16.0
            val zMin = if (n.z > 0) 0.0 else if (n.z < 0) 8.0 else 0.0
            val zMax = if (n.z > 0) 8.0 else if (n.z < 0) 16.0 else 16.0
            Block.box(xMin, yMin, zMin, xMax, yMax, zMax)
        }

        private fun baseHalfCube(facing: Direction): VoxelShape =
            BASE_HALF_CUBE[facing] ?: Shapes.block()
    }
}
