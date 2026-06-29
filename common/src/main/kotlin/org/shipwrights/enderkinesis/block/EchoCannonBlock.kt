package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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
import org.shipwrights.enderkinesis.blockentity.EchoCannonBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/**
 * Echo Cannon — sculk-faced block that fires a warden-sonic beam when
 * powered by redstone.
 *
 *  Block behaviour is intentionally thin. The cannon is a
 *  [DirectionalBlock] with FACING = the front (the sculk-catalyst-bottom
 *  face the beam exits from). All the timing / firing / damage logic
 *  lives in [EchoCannonBlockEntity]; this class just wires placement,
 *  the redstone neighbour-change hook, and the BE ticker.
 *
 *  Texturing: every face is `minecraft:block/sculk` except the front,
 *  which uses `minecraft:block/sculk_catalyst_bottom` so the cannon
 *  reads as a sculk catalyst that's been pointed somewhere.
 */
class EchoCannonBlock(properties: BlockBehaviour.Properties) :
    DirectionalBlock(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        // Player places the cannon with its front pointing where they're
        // looking — same shape as a dispenser / observer / piston: the
        // face you click toward becomes the FACING value.
        return defaultBlockState().setValue(FACING, context.nearestLookingDirection.opposite)
    }

    /** Redstone hook. Vanilla calls this whenever a neighbour changes;
     *  we route the powered-state edge into the BE so the BE owns the
     *  charge / fire state machine. */
    @Deprecated("Deprecated in Java")
    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos,
        block: Block, fromPos: BlockPos, isMoving: Boolean,
    ) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving)
        if (level.isClientSide) return
        val be = level.getBlockEntity(pos) as? EchoCannonBlockEntity ?: return
        be.onNeighborSignal(level.hasNeighborSignal(pos))
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        EchoCannonBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.ECHO_CANNON.get()) return null
        return BlockEntityTicker { lvl, p, st, be ->
            (be as? EchoCannonBlockEntity)?.serverTick(lvl, p, st)
        }
    }
}
