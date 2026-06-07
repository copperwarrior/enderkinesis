package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import org.shipwrights.enderkinesis.blockentity.PlanarAnchorBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/**
 * The Planar Anchor block — an axis-aligned pillar that, when redstone-powered on a ship, pins
 * itself to its current world position via a VS2 spherical joint (the ship freely rotates around
 * it). All the activation logic and joint lifecycle live in [PlanarAnchorBlockEntity]; the block
 * itself is a plain pillar that exposes a server ticker.
 *
 * A [POWERED] blockstate flag mirrors the redstone signal so the blockstate JSON can swap to a
 * "chain-hidden" model when the anchor isn't active.
 */
class PlanarAnchorBlock(properties: BlockBehaviour.Properties) :
    RotatedPillarBlock(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(AXIS, Direction.Axis.Y)
                .setValue(POWERED, false)
                .setValue(TARGETED, false)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(POWERED)
        builder.add(TARGETED)
    }

    /** Stamp the redstone state at placement time so the blockstate model matches reality the
     *  instant the block goes down (e.g. placed next to a powered wire). */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val base = super.getStateForPlacement(context) ?: return null
        return base.setValue(POWERED, context.level.hasNeighborSignal(context.clickedPos))
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        PlanarAnchorBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.PLANAR_ANCHOR.get()) return null
        return BlockEntityTicker { lvl, p, st, be ->
            (be as? PlanarAnchorBlockEntity)?.serverTick(lvl as ServerLevel, p, st)
        }
    }

    /**
     * Reconcile joint state the very tick redstone changes — don't wait for the next server tick
     * poll. Also keeps the [POWERED] blockstate in sync with the redstone signal so the model swap
     * happens at the same instant the joint engages/releases.
     *
     * Calls into the same [PlanarAnchorBlockEntity.serverTick] reconcile path so activation/
     * deactivation logic stays in one place; the BE's own guards make repeat invocation (which
     * `neighborChanged` does freely) idempotent.
     */
    @Deprecated("Deprecated in Java")
    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos,
        neighborBlock: Block, neighborPos: BlockPos, isMoving: Boolean,
    ) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, isMoving)
        if (level.isClientSide) return
        val shouldBePowered = level.hasNeighborSignal(pos)
        if (state.getValue(POWERED) != shouldBePowered) {
            // UPDATE_CLIENTS only; we already handle the joint reconcile below so we don't need
            // the neighbour-cascade flag here.
            level.setBlock(pos, state.setValue(POWERED, shouldBePowered), Block.UPDATE_CLIENTS)
        }
        (level.getBlockEntity(pos) as? PlanarAnchorBlockEntity)
            ?.serverTick(level as ServerLevel, pos, state)
    }

    companion object {
        val POWERED = BlockStateProperties.POWERED
        /** True when this anchor is the chain-endpoint of *another* anchor. Drives the same
         *  "chain element shown" model variant as [POWERED] (so the block visually carries a
         *  chain link), but the BE refuses to activate its own joint while targeted. */
        val TARGETED: BooleanProperty = BooleanProperty.create("targeted")
    }
}
