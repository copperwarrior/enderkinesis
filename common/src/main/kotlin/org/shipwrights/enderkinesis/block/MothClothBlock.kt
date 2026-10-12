package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.dimension.MothClothShipRegistry
import org.shipwrights.enderkinesis.registry.EKParticles

/**
 * Wool-textured "moth cloth" block, one variant per [DyeColor]. Rendering is
 * chunk-mesh through a custom `RenderType` that adds a fresnel-driven yellow
 * sheen (see the client-side `MothClothRenderState` / shader wiring). No BER.
 *
 * Physics: each placed cloth block pulls its host ship upward toward Y=134.5
 * via a shared [MothClothShipRegistry]-tracked position set + a per-ship
 * `MothClothForceInducer`. Removed blocks unregister on the next tick.
 */
class MothClothBlock(val color: DyeColor, properties: BlockBehaviour.Properties) :
    Block(properties) {

    override fun onPlace(
        state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, isMoving: Boolean,
    ) {
        super.onPlace(state, level, pos, oldState, isMoving)
        if (level.isClientSide) return
        if (oldState.block === this) return                    // pure state change; already tracked
        MothClothShipRegistry.add(level as net.minecraft.server.level.ServerLevel, pos)
    }

    @Deprecated("Deprecated in Java")
    override fun onRemove(
        state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean,
    ) {
        if (!level.isClientSide && newState.block !== this) {
            MothClothShipRegistry.remove(level as net.minecraft.server.level.ServerLevel, pos)
        }
        super.onRemove(state, level, pos, newState, isMoving)
    }

    /** Sparse Sselith-yellow dust drifting off the block's faces. Vanilla
     *  fires [animateTick] roughly once per second per visible block, so
     *  the [DUST_CHANCE] gate keeps overall density soft. Each spawn picks
     *  a random face, a random point on that face, and drifts the dust
     *  outward with a small perpendicular jitter. */
    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(DUST_CHANCE) != 0) return
        val face = Direction.values()[random.nextInt(6)]
        // Two axes tangent to the face — the point across the face is random
        // in both, the third axis is pinned to the face plane (0 or 1).
        val nx = face.stepX
        val ny = face.stepY
        val nz = face.stepZ
        val u = random.nextDouble()
        val v = random.nextDouble()
        // If nx != 0 the face is on the X axis; the tangents are Y and Z.
        val px = if (nx != 0) (if (nx > 0) 1.0 else 0.0) else u
        val py = if (ny != 0) (if (ny > 0) 1.0 else 0.0) else if (nx != 0) u else v
        val pz = if (nz != 0) (if (nz > 0) 1.0 else 0.0) else v
        val drift = 0.015
        level.addParticle(
            EKParticles.sselithDust(),
            pos.x + px, pos.y + py, pos.z + pz,
            nx * drift + (random.nextDouble() - 0.5) * 0.004,
            ny * drift + (random.nextDouble() - 0.5) * 0.004,
            nz * drift + (random.nextDouble() - 0.5) * 0.004,
        )
    }

    companion object {
        /** Higher = sparser. ~1 spawn every 20 s per visible block. */
        private const val DUST_CHANCE = 20
    }
}
