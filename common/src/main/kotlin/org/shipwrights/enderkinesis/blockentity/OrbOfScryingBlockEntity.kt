package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.server.level.ServerLevel
import org.shipwrights.enderkinesis.block.OrbOfScryingBlock
import org.shipwrights.enderkinesis.registry.EKBlockEntities
import org.shipwrights.enderkinesis.scrying.ScryingOrbRegistry

/**
 * Block-entity for the Orb of Scrying. Carries no per-orb state — it exists only so the
 * client can register a custom [net.minecraft.client.renderer.blockentity.BlockEntityRenderer]
 * for the orb shell (the underlying block model is just the pedestal) and so the server has
 * a setLevel hook to register the orb with [ScryingOrbRegistry] for the dimension's target
 * lookup.
 */
class OrbOfScryingBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(EKBlockEntities.ORB_OF_SCRYING.get(), pos, state) {

    /** Active-face direction — mirrors [OrbOfScryingBlock.FACING] on the blockstate. */
    val facing: Direction
        get() = blockState.getValue(OrbOfScryingBlock.FACING)

    /** Add this orb to the per-dimension [ScryingOrbRegistry]. Idempotent — fires
     *  on chunk load too, and the registry's `add` is a Set insert that no-ops on a
     *  duplicate, so reloading a chunk that holds an already-registered orb costs one
     *  `Set.add` returning false. */
    override fun setLevel(level: Level) {
        super.setLevel(level)
        if (level is ServerLevel) {
            ScryingOrbRegistry.get(level).add(blockPos)
        }
    }

    // Note: registry removal happens in [OrbOfScryingBlock.onRemove], NOT in setRemoved.
    // setRemoved fires for BOTH real block break AND chunk unload — a chunk-unload
    // path here would remove the orb from the persisted registry even though the orb
    // still exists on disk. `Block.onRemove` only fires on real state changes, never on
    // chunk unload, so the registry-remove runs only when the orb is genuinely gone.
}
