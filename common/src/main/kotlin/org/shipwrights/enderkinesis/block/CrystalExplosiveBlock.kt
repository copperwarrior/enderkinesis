package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.blockentity.CrystalExplosiveBlockEntity
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/**
 * Detonates when its host ship experiences a sufficiently strong collision-shaped
 * velocity change. The BE listens on the physics tick (where ship velocity is live)
 * and queues an explosion back onto the game tick, where `Level.explode` is safe to
 * call. Detonation strength scales with the kinetic energy delivered to *this* block,
 * not just the ship's average, so an explosive at the bow of a ramming ship goes off
 * harder than one near the centre of mass.
 */
class CrystalExplosiveBlock(properties: BlockBehaviour.Properties) :
    Block(properties), EntityBlock {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        CrystalExplosiveBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? {
        if (level.isClientSide) return null
        if (type != EKBlockEntities.CRYSTAL_EXPLOSIVE.get()) return null
        return BlockEntityTicker { lvl, p, st, be ->
            (be as? CrystalExplosiveBlockEntity)?.serverTick(lvl as ServerLevel, p, st)
        }
    }

    /** Chain reaction: when an *external* explosion destroys a crystal explosive, fire
     *  a follow-up detonation at this position. The set guard skips the chain for the
     *  block that itself originated the in-flight explosion (so its own blast doesn't
     *  re-trigger itself); only neighbours caught in the radius propagate. Deferred
     *  through `server.execute` (in [CrystalExplosiveBlockEntity.queueChainExplosion])
     *  so a long line of explosives propagates over consecutive ticks instead of
     *  recursing through `level.explode` on the call stack. */
    override fun wasExploded(level: Level, pos: BlockPos, explosion: Explosion) {
        super.wasExploded(level, pos, explosion)
        if (level !is ServerLevel) return
        if (pos in CrystalExplosiveBlockEntity.EXPLODING_POSITIONS) return
        CrystalExplosiveBlockEntity.queueChainExplosion(level, pos)
    }
}
