package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Containers
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import org.shipwrights.enderkinesis.blockentity.CrystalExplosiveBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities

class CrystalExplosiveBlock(properties: BlockBehaviour.Properties) :
    Block(properties), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        CrystalExplosiveBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.CRYSTAL_EXPLOSIVE.get()) return null
        return BlockEntityTicker { lvl, p, _, be ->
            (be as? CrystalExplosiveBlockEntity)?.serverTick(lvl as ServerLevel, p)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Vanilla still routes block use through this override.
    override fun use(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        val be = level.getBlockEntity(pos) as? CrystalExplosiveBlockEntity ?: return InteractionResult.PASS
        val held = player.getItemInHand(hand)
        if (be.armed) {
            if (!held.isEmpty) return InteractionResult.PASS
            if (!level.isClientSide) player.setItemInHand(hand, be.take())
            return InteractionResult.sidedSuccess(level.isClientSide)
        }
        if (!be.canPlaceItem(0, held)) return InteractionResult.PASS
        if (!level.isClientSide) {
            val stack = if (player.abilities.instabuild) held.copy() else held
            be.insert(stack)
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player) {
        if (!level.isClientSide) {
            val be = level.getBlockEntity(pos) as? CrystalExplosiveBlockEntity
            val crystal = be?.take()
            if (crystal != null && !crystal.isEmpty && !player.abilities.instabuild) {
                Containers.dropItemStack(level, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, crystal)
            }
        }
        super.playerWillDestroy(level, pos, state, player)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Vanilla still routes onRemove through this override.
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (!state.`is`(newState.block) && level is ServerLevel) {
            val be = level.getBlockEntity(pos) as? CrystalExplosiveBlockEntity
            if (be?.armed == true) CrystalExplosiveBlockEntity.noteArmedRemoval(level, pos)
        }
        super.onRemove(state, level, pos, newState, moved)
    }

    /** Only an armed explosive caught in another blast sets off a chain blast. */
    override fun wasExploded(level: Level, pos: BlockPos, explosion: Explosion) {
        super.wasExploded(level, pos, explosion)
        if (level !is ServerLevel) return
        if (CrystalExplosiveBlockEntity.consumeArmedRemoval(level, pos)) {
            CrystalExplosiveBlockEntity.queueChainExplosion(level, pos)
        }
    }
}
