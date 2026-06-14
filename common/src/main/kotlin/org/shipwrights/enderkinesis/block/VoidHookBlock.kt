package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.shipwrights.enderkinesis.blockentity.VoidHookBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/**
 * Void Hook — directional, longer-effective-range cousin of [VoidHarnessBlock]. The hook
 * always pulls *other* ships within [VoidHookBlockEntity.PULL_RADIUS] of itself toward its
 * own world position, whether the hook is placed in the world or on another ship — so a
 * ship-mounted hook is a directional grappler that drags target ships in to dock with it,
 * not a self-thruster like the harness.
 *
 * The 6-way [FACING] uses dispenser placement convention: the face the player clicks
 * becomes the back; the hook's "open end" points the opposite way (toward whatever
 * direction the player was looking). Only ships in the half-space *in front of* the
 * hook (positive dot product against FACING) get pulled — the back-half is ignored, so
 * the hook genuinely projects in one direction rather than acting as an omni-pull.
 *
 * Visual placeholder shares the void-harness crepusculite-glass shell + crystal
 * rendering; the dispenser-style FACING is functional, not yet visually distinguished.
 */
class VoidHookBlock(properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(POWER, 0)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, POWER)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        // Dispenser convention: the clicked face becomes the back, so FACING points the
        // opposite way (toward the player's look direction).
        val facing = context.nearestLookingDirection.opposite
        val powered = context.level.getBestNeighborSignal(context.clickedPos)
        return defaultBlockState()
            .setValue(FACING, facing)
            .setValue(POWER, powered)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        VoidHookBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.VOID_HOOK.get()) return null
        return BlockEntityTicker { lvl, p, st, be ->
            (be as? VoidHookBlockEntity)?.serverTick(lvl as ServerLevel, p, st)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos,
        neighborBlock: Block, neighborPos: BlockPos, isMoving: Boolean,
    ) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, isMoving)
        if (level.isClientSide) return
        val signal = level.getBestNeighborSignal(pos)
        if (state.getValue(POWER) != signal) {
            level.setBlock(pos, state.setValue(POWER, signal), Block.UPDATE_CLIENTS)
        }
    }

    companion object {
        /** 6-direction facing (UP/DOWN/N/S/E/W) — same property type as a dispenser. */
        val FACING = BlockStateProperties.FACING

        /** Redstone power 0–15 — scales pull force linearly. */
        val POWER = BlockStateProperties.POWER
    }
}
