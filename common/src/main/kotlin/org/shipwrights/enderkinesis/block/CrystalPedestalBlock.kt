package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.RandomSource
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Vector3d
import org.shipwrights.enderkinesis.body.GamylanCagePedestals
import org.shipwrights.enderkinesis.registry.EKParticles
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getShipObjectManagingPos

/**
 * Home block for a Ga'Mylan cage crystal. When a crystal drifts above a
 * pedestal, [GamylanCagePedestals] adopts it as the crystal's home,
 * pulls the body into alignment, and once rotation is within tolerance
 * welds a VSFixedJoint. Powering the pedestal (redstone signal > 0)
 * flips [POWERED] and disengages both weld and alignment — the crystal
 * is released until power drops.
 *
 * Placeholder model reuses vanilla `chiseled_stone_bricks` at the
 * blockstate + item level; no dedicated texture ships yet.
 */
class CrystalPedestalBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    /** Right-click with an item → conjure a REAL Ga'Mylan cage crystal
     *  above the pedestal holding ONE of the item. The crystal is an
     *  ordinary cage body: it docks + welds through the standard
     *  pedestal-home flow, spins, takes damage, and breaking it returns
     *  the item. Refused while any crystal is already homed to (or
     *  hovering over) this pedestal — one occupant per pedestal. */
    @Deprecated("Deprecated in Java")
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: net.minecraft.world.entity.player.Player,
        hand: net.minecraft.world.InteractionHand,
        hit: net.minecraft.world.phys.BlockHitResult,
    ): net.minecraft.world.InteractionResult {
        val held = player.getItemInHand(hand)
        if (held.isEmpty) return net.minecraft.world.InteractionResult.PASS
        if (level.isClientSide) return net.minecraft.world.InteractionResult.SUCCESS
        val serverLevel = level as net.minecraft.server.level.ServerLevel
        if (org.shipwrights.enderkinesis.body.GamylanCagePedestals.isPedestalOccupied(serverLevel, pos)) {
            return net.minecraft.world.InteractionResult.PASS
        }
        val placed = org.shipwrights.enderkinesis.item.GamylanCageManager.captureItem(
            serverLevel, held.copyWithCount(1), pos,
        )
        if (placed) {
            if (!player.abilities.instabuild) held.shrink(1)
            level.playSound(
                null, pos,
                net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, 0.9f,
            )
        }
        return net.minecraft.world.InteractionResult.SUCCESS
    }


    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(BlockStateProperties.POWERED, false)
                .setValue(BlockStateProperties.LIT, false),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(BlockStateProperties.POWERED, BlockStateProperties.LIT)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(BlockStateProperties.POWERED, context.level.hasNeighborSignal(context.clickedPos))

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext): VoxelShape =
        SHAPE

    override fun getOcclusionShape(state: BlockState, level: BlockGetter, pos: BlockPos): VoxelShape =
        SHAPE

    override fun useShapeForLightOcclusion(state: BlockState): Boolean = true

    /** Comparator output: full signal while a crystal is docked (welded).
     *  LIT is flipped by [GamylanCagePedestals] exactly on weld/release, so
     *  it doubles as the dock indicator without a BE lookup. */
    @Deprecated("Deprecated in Java")
    override fun hasAnalogOutputSignal(state: BlockState): Boolean = true

    @Deprecated("Deprecated in Java")
    override fun getAnalogOutputSignal(state: BlockState, level: Level, pos: BlockPos): Int =
        if (state.getValue(BlockStateProperties.LIT)) 15 else 0

    @Deprecated("Deprecated in Java")
    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos,
        block: Block, fromPos: BlockPos, moving: Boolean,
    ) {
        if (level.isClientSide) return
        val powered = level.hasNeighborSignal(pos)
        if (state.getValue(BlockStateProperties.POWERED) == powered) return
        level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, powered), 3)
        if (powered) GamylanCagePedestals.onPedestalPowered(level as net.minecraft.server.level.ServerLevel, pos)
    }

    @Deprecated("Deprecated in Java")
    override fun onRemove(
        state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean,
    ) {
        if (!state.`is`(newState.block) && !level.isClientSide) {
            GamylanCagePedestals.onPedestalRemoved(level as net.minecraft.server.level.ServerLevel, pos)
        }
        super.onRemove(state, level, pos, newState, isMoving)
    }

    /** Ambient particles. Two modes driven by the [BlockStateProperties.LIT]
     *  bit that [GamylanCagePedestals] flips on/off with the joint:
     *
     *   - **Unlit** (no crystal): rare Soul particle at the position a
     *     crystal centre would occupy — a wispy "waiting" marker.
     *   - **Lit** (crystal welded): denser Wohlon fireflies orbiting
     *     the same position — the same ambient the Wohlon overworld
     *     uses.
     *
     *  A ship-mounted pedestal transforms the block-frame spawn point
     *  through the host ship's `renderTransform.shipToWorld` before
     *  passing it to `level.addParticle`, so the particles ride the
     *  ship rather than drifting off in shipyard space. */
    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        val lit = state.getValue(BlockStateProperties.LIT)
        if (lit) {
            if (random.nextInt(FIREFLY_SPAWN_DENOM) != 0) return
            val jitterXZ = FIREFLY_JITTER_XZ
            val jitterY = FIREFLY_JITTER_Y
            val bx = pos.x + 0.5 + (random.nextDouble() - 0.5) * 2.0 * jitterXZ
            val by = pos.y + CRYSTAL_CENTRE_Y + (random.nextDouble() - 0.5) * 2.0 * jitterY
            val bz = pos.z + 0.5 + (random.nextDouble() - 0.5) * 2.0 * jitterXZ
            spawnAmbient(level, pos, bx, by, bz, EKParticles.WOHLON_FIREFLY.get() as SimpleParticleType,
                dx = 0.0, dy = 0.0, dz = 0.0)
        } else {
            if (random.nextInt(SOUL_SPAWN_DENOM) != 0) return
            val bx = pos.x + 0.5
            val by = pos.y + CRYSTAL_CENTRE_Y
            val bz = pos.z + 0.5
            spawnAmbient(level, pos, bx, by, bz, ParticleTypes.SOUL,
                dx = 0.0, dy = SOUL_DRIFT_Y, dz = 0.0)
        }
    }

    /** Spawn `type` at `(bx,by,bz)` in the block's own frame, transformed
     *  to world coords if the pedestal is on a VS ship. Velocities are
     *  passed through untouched — the ship's world-frame motion is
     *  independent of the particle's own drift. */
    private fun spawnAmbient(
        level: Level, pos: BlockPos,
        bx: Double, by: Double, bz: Double,
        type: net.minecraft.core.particles.ParticleOptions,
        dx: Double, dy: Double, dz: Double,
    ) {
        val ship = level.getShipObjectManagingPos(pos) as? ClientShip
        var wx = bx; var wy = by; var wz = bz
        if (ship != null) {
            val local = Vector3d(bx, by, bz)
            ship.renderTransform.shipToWorld.transformPosition(local)
            wx = local.x; wy = local.y; wz = local.z
        }
        level.addParticle(type, wx, wy, wz, dx, dy, dz)
    }

    companion object {
        /** Approximate Y (blocks above the block's origin) at which a
         *  crystal welded to this pedestal sits. Real welded position is
         *  `pedestalTop + midH + tipLen + PEDESTAL_TOP_CLEARANCE`, which
         *  varies with the captured mob's culling AABB; 2.4 matches an
         *  average chicken-to-cow-sized crystal. Fireflies and the
         *  "waiting" soul particle both anchor here. */
        private const val CRYSTAL_CENTRE_Y: Double = 2.4

        /** ~1 spawn every ~3 s per visible pedestal — a lone wisp, not
         *  a stream. */
        private const val SOUL_SPAWN_DENOM: Int = 60
        private const val SOUL_DRIFT_Y: Double = 0.02

        /** ~1 spawn every ~0.25 s per visible pedestal — enough for a
         *  gentle orbiting cluster without visual noise. */
        private const val FIREFLY_SPAWN_DENOM: Int = 5
        private const val FIREFLY_JITTER_XZ: Double = 0.75
        private const val FIREFLY_JITTER_Y: Double = 0.5

        /** Union of the eight boxes in `models/block/crystal_pedestal.json`.
         *  Base slab + top slab + two 2-thick crossbeams through the neck +
         *  four small prongs jutting outward from the top slab. */
        private val SHAPE: VoxelShape = Shapes.or(
            Block.box(1.0, 0.0, 1.0, 15.0, 2.0, 15.0),
            Block.box(1.0, 12.0, 1.0, 15.0, 14.0, 15.0),
            Block.box(7.0, 2.0, 3.0, 9.0, 12.0, 13.0),
            Block.box(3.0, 2.0, 7.0, 13.0, 12.0, 9.0),
            Block.box(0.0, 13.0, 7.0, 2.0, 16.0, 9.0),
            Block.box(14.0, 13.0, 7.0, 16.0, 16.0, 9.0),
            Block.box(7.0, 13.0, 14.0, 9.0, 16.0, 16.0),
            Block.box(7.0, 13.0, 0.0, 9.0, 16.0, 2.0),
        )
    }
}
