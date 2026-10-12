package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import org.shipwrights.enderkinesis.blockentity.HeartOfTheWildBlockEntity
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.registry.EKBlockEntities

class HeartOfTheWildBlock(properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {

    init {
        registerDefaultState(stateDefinition.any().setValue(MOTHER, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(MOTHER)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        HeartOfTheWildBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.HEART_OF_THE_WILD.get()) return null
        return BlockEntityTicker { lvl, pos, _, be ->
            (be as? HeartOfTheWildBlockEntity)?.serverTick(lvl as ServerLevel, pos)
        }
    }

    /** Returning 0 makes survival-break impossible (bedrock idiom). Only applies to MOTHER. */
    override fun getDestroyProgress(
        state: BlockState, player: Player, getter: BlockGetter, pos: BlockPos,
    ): Float = if (state.getValue(MOTHER)) 0f else super.getDestroyProgress(state, player, getter, pos)

    /** Gate on `state.block != newState.block` so an in-place property edit doesn't trigger the catastrophe. */
    override fun onRemove(
        state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean,
    ) {
        val wasMother = state.getValue(MOTHER)
        val sameBlock = state.`is`(newState.block)
        if (!sameBlock && level is ServerLevel) HeartOfTheWildManager.onHeartBroken(level, pos)
        super.onRemove(state, level, pos, newState, isMoving)
        if (wasMother && !sameBlock && level is ServerLevel) {
            WohlonnogondoniaCatastrophe.trigger(level.server)
        }
    }

    companion object {
        val MOTHER: BooleanProperty = BooleanProperty.create("mother")

        /** [blocks] without the Mother Heart. A ship assembly moves its blocks, and the removal
         *  of the Mother Heart from the world starts the catastrophe. */
        @JvmStatic
        fun withoutMotherHeart(level: ServerLevel, blocks: Set<BlockPos>): Set<BlockPos> {
            if (level.dimension() != Wohlonnogondonia.LEVEL_KEY) return blocks
            val mother = blocks.filter { pos ->
                val state = level.getBlockState(pos)
                state.block is HeartOfTheWildBlock && state.getValue(MOTHER)
            }
            return if (mother.isEmpty()) blocks else blocks - mother.toSet()
        }
    }
}
