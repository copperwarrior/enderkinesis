package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
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
import org.shipwrights.enderkinesis.blockentity.AetherPadBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/**
 * Aether Pad — a directional thruster designed for hover vehicles.
 *
 *  ## Two coupled functions (same nozzle, opposite-direction effects)
 *
 *  - **Forward entity push.** Entities in front of the pad (in `FACING`) within
 *    [AetherPadBlockEntity.ENTITY_RANGE] blocks get a velocity additive along `FACING`,
 *    falling off linearly with distance. Reads as a leaf-blower / boost-pad style nudge.
 *  - **Reaction lift on the host ship.** When the pad is mounted on a VS2 ship *and* the
 *    pad's `FACING` direction has solid ground within [AetherPadBlockEntity.GROUND_MAX_RANGE]
 *    blocks, the host ship gets a force in the opposite direction (`-FACING` in world frame,
 *    after rotating through `shipToWorldRotation`). Force scales with proximity to ground:
 *    full at contact, zero at the cutoff. That's the "ground-effect" hover behaviour
 *    Trackwork's hover engine uses — only pushes while the surface is close enough to
 *    react against. A vertical-FACING pad on the bottom of a ship + ground below = hover.
 *
 *  ## Visual
 *
 *  Front face wears `crepusculite_block`; the other five faces wear `purpur_pillar`.
 *  Block model uses vanilla's `block/orientable` parent + per-`FACING` rotations in the
 *  blockstate JSON, same setup as the Shulker Puffer.
 *
 *  ## Create contraption integration
 *
 *  A pad embedded in a Create contraption keeps working — see [CreateCompat]'s reflective
 *  `MovementBehaviour` proxy, which drives the same forward-push + ground-effect lift on
 *  every contraption tick via [AetherPadForceProvider]. The BE is the path for ship-resident
 *  and world-placed pads; the proxy is the path for contraption-mounted pads. Both call
 *  into the shared static helpers on [AetherPadBlockEntity].
 */
class AetherPadBlock(properties: BlockBehaviour.Properties) :
    DirectionalBlock(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(BlockStateProperties.FACING, Direction.DOWN)
                .setValue(BlockStateProperties.POWER, 0),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(BlockStateProperties.FACING, BlockStateProperties.POWER)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState {
        // Dispenser convention: FACING = opposite of nearest looking direction. The
        // crepusculite face points where the player was aiming, which matches "place pad
        // facing down by aiming down at a block you're standing on", etc.
        return defaultBlockState()
            .setValue(BlockStateProperties.FACING, context.nearestLookingDirection.opposite)
            .setValue(BlockStateProperties.POWER, context.level.getBestNeighborSignal(context.clickedPos))
    }

    /** Track redstone signal level changes — the BE reads [BlockStateProperties.POWER]
     *  each tick and scales every force (entity beam, lift, friction) linearly by
     *  `POWER / 15`. POWER 0 = pad fully inert; POWER 15 = the constants on
     *  [AetherPadBlockEntity] in full. */
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

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        AetherPadBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (type != EKBlockEntities.AETHER_PAD.get()) return null
        // Ticks on BOTH sides. Create's encased fan does the same — the client BE ticks
        // the air current locally so client-side `LocalPlayer` motion gets pushed by the
        // client's own integration instead of relying on a server packet to override it.
        // Without dual-side ticking, the only way to make a player see the push is a
        // `ClientboundSetEntityMotionPacket`, which wipes their input-driven walk
        // velocity each tick → the "drag" / "stiff" / "stuttery" feel the user reported.
        return BlockEntityTicker { lvl, p, st, be ->
            (be as? AetherPadBlockEntity)?.commonTick(lvl, p, st)
        }
    }
}
