package org.shipwrights.enderkinesis.blockentity

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import org.joml.Vector3d
import org.shipwrights.enderkinesis.body.OrbBodyRegistry
import org.shipwrights.enderkinesis.body.OrbOfPotentialManager
import org.shipwrights.enderkinesis.registry.EKBlockEntities

/**
 * One-shot spawn marker for an Orb of Potential VS Body.
 *
 *  Lifecycle is intentionally ephemeral. The fractal_projector block
 *  is painted by Sselith worldgen (the `sselith_globe.nbt` structure
 *  replacement); when the chunk loads and the BE first ticks, this
 *  class:
 *    1. Creates a non-ship `ServerVsBody` with sphere collision via
 *       [OrbOfPotentialManager.spawnAt].
 *    2. Registers the body with the persistent [OrbBodyRegistry] —
 *       the body's identity survives the marker block.
 *    3. Replaces the marker block with air, which removes this BE.
 *
 *  After step 3 the BE no longer exists. All subsequent rendering,
 *  collision and grab support is driven from the body itself; no
 *  client-side BE tracking is needed. */
class FractalProjectorBlockEntity(
    pos: BlockPos, state: BlockState,
) : BlockEntity(EKBlockEntities.FRACTAL_PROJECTOR.get(), pos, state) {

    companion object {
        @JvmStatic
        fun serverTick(
            level: Level, pos: BlockPos, state: BlockState, @Suppress("UNUSED_PARAMETER") be: FractalProjectorBlockEntity,
        ) {
            if (level !is ServerLevel) return
            val newId = OrbOfPotentialManager.spawnAt(level, pos) ?: return
            val anchor = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            OrbBodyRegistry.add(level, newId, anchor)
            // Marker has done its job — replace with air. The BE is
            // dropped automatically when the block changes.
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
        }
    }
}
