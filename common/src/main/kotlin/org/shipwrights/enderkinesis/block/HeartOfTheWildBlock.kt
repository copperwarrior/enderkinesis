package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.blockentity.HeartOfTheWildBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/**
 * Heart of the Wild — a stationary block whose presence drives the
 * [HeartOfTheWildManager] growth queue for its scope (world or ship).
 *
 * The block itself is inert decoration; all behaviour lives on the
 * paired [HeartOfTheWildBlockEntity], which ticks every
 * [HeartOfTheWildManager.GROW_PERIOD_TICKS] game ticks at a
 * Heart-specific tick offset and consumes one position from the
 * scope's queue per attempt.
 *
 * Visually placeholders as a sea lantern (see `block/heart_of_the_wild.json`)
 * until the artist returns a unique model.
 */
class HeartOfTheWildBlock(properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        HeartOfTheWildBlockEntity(pos, state)

    /** Server-only ticker. The BE's [HeartOfTheWildBlockEntity.serverTick]
     *  gates itself on the tick-offset cadence; we don't pre-filter
     *  here so a future tick-offset reroll wouldn't have to bounce
     *  through this dispatch as well. */
    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.HEART_OF_THE_WILD.get()) return null
        return BlockEntityTicker { lvl, pos, _, be ->
            (be as? HeartOfTheWildBlockEntity)?.serverTick(lvl as ServerLevel, pos)
        }
    }
}
