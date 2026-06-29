package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.registry.EKParticles

/**
 * Sselith Lantern. Sitting-on-the-bottom-only variant — there is no hanging
 * form (unlike vanilla [Blocks.LANTERN]), so we don't carry the `hanging`
 * blockstate property or the side-mounted AABB at all. Decorative lantern
 * the Sselith chunk generator places everywhere it would otherwise drop a
 * vanilla lantern.
 *
 * Light emission and durability come from the [Properties] the caller hands
 * in; this class only adds:
 *  - a placement / survival check that requires a sturdy face on the block
 *    directly below, and
 *  - a fixed AABB that hugs the visible body of the model.
 */
class SselithLanternBlock(properties: Properties) : Block(properties) {

    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean {
        // Mirror vanilla [Blocks.LANTERN]'s sitting-variant support check:
        // canSupportCenter probes a 2×2-pixel central column rather than the
        // full top face, so partial-shape blocks like walls, fences and
        // chains (whose top is a narrow post but not a sturdy face) still
        // count as valid supports — same as the vanilla lantern.
        return Block.canSupportCenter(level, pos.below(), Direction.UP)
    }

    override fun updateShape(
        state: BlockState,
        direction: Direction,
        neighborState: BlockState,
        level: LevelAccessor,
        pos: BlockPos,
        neighborPos: BlockPos,
    ): BlockState =
        if (direction == Direction.DOWN && !canSurvive(state, level, pos)) Blocks.AIR.defaultBlockState()
        else super.updateShape(state, direction, neighborState, level, pos, neighborPos)

    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = SHAPE

    /** Sselith Bookmoths — a small cluster of warm-yellow pixel particles that
     *  flicker and flutter around the lantern. Driven directly off
     *  [animateTick] so the moths track the client's standard random-block
     *  visit budget instead of needing a separate world scanner. The 1-in-N
     *  roll plus the engine's own per-block visit rate yields ~1 spawn every
     *  few seconds per visible lantern. */
    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(BOOKMOTH_SPAWN_DENOM) != 0) return
        val rng = random
        val spawnX = pos.x + 0.5 + (rng.nextDouble() - 0.5) * BOOKMOTH_SPAWN_RADIUS_XZ
        val spawnY = pos.y + 0.6 + (rng.nextDouble() - 0.5) * BOOKMOTH_SPAWN_RADIUS_Y
        val spawnZ = pos.z + 0.5 + (rng.nextDouble() - 0.5) * BOOKMOTH_SPAWN_RADIUS_XZ
        // The xd/yd/zd slots carry the source lantern's block coords — the moth
        // constructor reads them via the Provider and queries the level for the
        // lantern's actual VoxelShape sub-AABBs to use as no-fly volumes. Safe
        // to repurpose because the particle overrides its own velocity to zero
        // in the constructor.
        level.addParticle(
            EKParticles.SSELITH_BOOKMOTH.get() as SimpleParticleType,
            spawnX, spawnY, spawnZ,
            pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(),
        )
    }

    companion object {
        /** Multi-box union matching every distinct element in the lantern's
         *  Blockbench model, so the outline / collision / particle-avoidance
         *  shape reflects what the player actually sees. Per-element extents
         *  (in block-local 1/16-voxel coords, copied from the model JSON):
         *   - base plate: x∈[4,12], y∈[0,1],   z∈[4,12]
         *   - column:     x∈[7,9],  y∈[1,5],   z∈[7,9]   (thin riser to body)
         *   - main body:  x∈[5,11], y∈[5,12],  z∈[5,11]  (the glass cube)
         *   - top plate:  x∈[2,14], y∈[11,13], z∈[2,14]  (the wide skirt)
         *   - cap:        x∈[4,12], y∈[13,15], z∈[4,12]
         */
        private val SHAPE: VoxelShape = Shapes.or(
            Block.box(4.0, 0.0, 4.0, 12.0, 1.0, 12.0),
            Block.box(7.0, 1.0, 7.0, 9.0, 5.0, 9.0),
            Block.box(5.0, 5.0, 5.0, 11.0, 12.0, 11.0),
            Block.box(2.0, 11.0, 2.0, 14.0, 13.0, 14.0),
            Block.box(4.0, 13.0, 4.0, 12.0, 15.0, 12.0),
        )

        /** 1-in-N roll per animate-tick visit. The engine selects ~667 random
         *  blocks/tick within ~16 blocks of the player, so an N of 8 yields
         *  a steady-state cluster of a few moths per visible lantern. */
        private const val BOOKMOTH_SPAWN_DENOM: Int = 8

        /** Half-extents of the moth spawn box around the lantern centre. */
        private const val BOOKMOTH_SPAWN_RADIUS_XZ: Double = 0.9
        private const val BOOKMOTH_SPAWN_RADIUS_Y: Double = 0.7
    }
}
