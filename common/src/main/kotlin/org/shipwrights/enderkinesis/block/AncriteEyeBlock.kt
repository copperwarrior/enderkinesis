package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Vector3f
import org.shipwrights.enderkinesis.blockentity.AncriteEyeBlockEntity

/**
 * Analog redstone source — mirrors vanilla `LeverBlock` for power transmission: weak
 * `getSignal` on every side, strong `getDirectSignal` only on the attachment side so the
 * supporting block becomes a strong conductor (lets the eye power past the mount wall).
 * `onRemove` propagates a final neighbour update so a powered-while-broken eye doesn't
 * leave stale signal behind.
 *
 * Left-click / Staff of Command dispatch a momentary press — dial is stashed, [POWER]
 * jumps to [MAX_POWER], a [PRESS_DURATION_TICKS]-tick scheduled tick restores both. A
 * right-click step *during* the press cancels the revert and commits the new level.
 */
class AncriteEyeBlock(properties: BlockBehaviour.Properties) :
    FaceAttachedHorizontalDirectionalBlock(properties), EntityBlock {

    init {
        registerDefaultState(
            stateDefinition.any()
                .setValue(FACE, AttachFace.FLOOR)
                .setValue(FACING, Direction.NORTH)
                .setValue(POWER, 0)
                .setValue(PRESSED, false)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACE, FACING, POWER, PRESSED)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        AncriteEyeBlockEntity(pos, state)

    override fun isSignalSource(state: BlockState): Boolean = true

    override fun getSignal(state: BlockState, level: BlockGetter, pos: BlockPos, side: Direction): Int =
        state.getValue(POWER)

    /** Strong-power flows only out the side the eye attaches *toward*. That's how
     *  vanilla levers/buttons power blocks past the wall they're mounted on. */
    override fun getDirectSignal(state: BlockState, level: BlockGetter, pos: BlockPos, side: Direction): Int =
        if (side == getConnectedDirection(state)) state.getValue(POWER) else 0

    override fun getShape(
        state: BlockState, level: BlockGetter, pos: BlockPos, ctx: CollisionContext,
    ): VoxelShape = when (state.getValue(FACE)) {
        AttachFace.FLOOR -> SHAPE_FLOOR
        AttachFace.CEILING -> SHAPE_CEILING
        AttachFace.WALL -> when (state.getValue(FACING)) {
            Direction.NORTH -> SHAPE_WALL_NORTH
            Direction.SOUTH -> SHAPE_WALL_SOUTH
            Direction.EAST -> SHAPE_WALL_EAST
            Direction.WEST -> SHAPE_WALL_WEST
            else -> SHAPE_FLOOR
        }
    }

    @Deprecated("Deprecated in Java")
    override fun use(
        state: BlockState, level: Level, pos: BlockPos,
        player: Player, hand: InteractionHand, hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.sidedSuccess(true)
        val current = state.getValue(POWER)
        val next = if (player.isShiftKeyDown) (current - 1).coerceAtLeast(0)
                   else (current + 1).coerceAtMost(MAX_POWER)
        if (next != current) {
            val be = level.getBlockEntity(pos) as? AncriteEyeBlockEntity
            if (be != null && be.savedPower >= 0) {
                be.savedPower = -1
                be.setChanged()
            }
            setPower(level, pos, state, next, pressed = false)
        }
        return InteractionResult.CONSUME
    }

    @Deprecated("Deprecated in Java")
    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        val be = level.getBlockEntity(pos) as? AncriteEyeBlockEntity ?: return
        if (be.savedPower < 0) return
        val restored = be.savedPower
        be.savedPower = -1
        be.setChanged()
        setPower(level, pos, state, restored, pressed = false)
    }

    /** Purple dust ambience while the dial is non-zero, same idea as vanilla redstone
     *  wire's red dust but tinted violet and scaled by [POWER]. Spawned at the centre
     *  of the rendered platform — the eye's 8×3×8 box sits 1.5 voxels off the attached
     *  surface, so its midpoint is `6.5/16` away from the block centre in the
     *  attached direction (opposite of [getConnectedDirection]). */
    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        val power = state.getValue(POWER)
        if (power == 0) return
        val openDir = getConnectedDirection(state)
        val intensity = power / MAX_POWER.toFloat()
        // Spawn ~POWER/15 particles per tick — busy at max, sparse near 0.
        if (random.nextFloat() > intensity) return
        val cx = pos.x + 0.5 - openDir.stepX * SHAPE_CENTER_OFFSET
        val cy = pos.y + 0.5 - openDir.stepY * SHAPE_CENTER_OFFSET
        val cz = pos.z + 0.5 - openDir.stepZ * SHAPE_CENTER_OFFSET
        val px = cx + (random.nextDouble() - 0.5) * SHAPE_JITTER
        val py = cy + (random.nextDouble() - 0.5) * SHAPE_JITTER
        val pz = cz + (random.nextDouble() - 0.5) * SHAPE_JITTER
        val dust = DustParticleOptions(DUST_COLOR, 0.8f + 0.4f * intensity)
        level.addParticle(dust, px, py, pz, 0.0, 0.0, 0.0)
    }

    @Deprecated("Deprecated in Java")
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        if (!moved && !state.`is`(newState.block)) {
            // Mirror LeverBlock: poke the neighbour update through the supporting block so
            // a powered eye that's broken doesn't strand signal in adjacent redstone.
            if (state.getValue(POWER) > 0) updateNeighbours(state, level, pos)
            super.onRemove(state, level, pos, newState, moved)
        }
    }

    companion object {
        val FACE = BlockStateProperties.ATTACH_FACE
        val FACING = BlockStateProperties.HORIZONTAL_FACING
        val POWER = BlockStateProperties.POWER
        val PRESSED = BlockStateProperties.POWERED
        const val MAX_POWER: Int = 15

        /** How long the "pressed in" state lasts before the dial reverts. Mirrors a
         *  vanilla stone button (~10 ticks ≈ 0.5 s). */
        const val PRESS_DURATION_TICKS: Int = 10

        /** Ender-violet ambience colour — saturated purple, sits in the same palette
         *  family as the rest of the ender-themed mod blocks. */
        private val DUST_COLOR: Vector3f = Vector3f(0.55f, 0.10f, 0.85f)

        /** Offset from the block centre (0.5) to the platform centre, in block-fractions.
         *  Platform is 3 voxels (3/16 = 0.1875) thick on the attached face, so its midpoint
         *  sits 1.5/16 from the attached face = 6.5/16 = 0.40625 from the block centre. */
        private const val SHAPE_CENTER_OFFSET: Double = 6.5 / 16.0

        /** Tiny scatter around the platform centre so the particles don't all stack on
         *  one pixel. ±half this in each axis. */
        private const val SHAPE_JITTER: Double = 0.15

        private val SHAPE_FLOOR: VoxelShape = box(4.0, 0.0, 4.0, 12.0, 3.0, 12.0)
        private val SHAPE_CEILING: VoxelShape = box(4.0, 13.0, 4.0, 12.0, 16.0, 12.0)
        // Wall shapes sit on the side OPPOSITE the facing — vanilla lever convention.
        // facing=north → lever attached to the south wall, sticking north → bbox on south side.
        private val SHAPE_WALL_NORTH: VoxelShape = box(4.0, 4.0, 13.0, 12.0, 12.0, 16.0)
        private val SHAPE_WALL_SOUTH: VoxelShape = box(4.0, 4.0, 0.0, 12.0, 12.0, 3.0)
        private val SHAPE_WALL_EAST: VoxelShape = box(0.0, 4.0, 4.0, 3.0, 12.0, 12.0)
        private val SHAPE_WALL_WEST: VoxelShape = box(13.0, 4.0, 4.0, 16.0, 12.0, 12.0)

        /** Direction the eye attaches *toward* the supporting block. Floor: UP (eye stands
         *  on the block below, strong-powers UP); ceiling: DOWN; wall: the FACING direction
         *  (the eye points in FACING, attached to the wall in the opposite direction, and
         *  the supporting block queries the eye from the FACING side). Matches vanilla
         *  `LeverBlock.getConnectedDirection`. */
        fun getConnectedDirection(state: BlockState): Direction = when (state.getValue(FACE)) {
            AttachFace.CEILING -> Direction.DOWN
            AttachFace.FLOOR -> Direction.UP
            AttachFace.WALL -> state.getValue(FACING)
        }

        /** Momentary press: stash the current dial on the BE, jump to MAX with the
         *  depressed visual, and schedule the revert tick. Shared between the left-click
         *  handler and the Staff of Command dispatch.
         *
         *  Fire-rate limit: while a press is in flight (`be.savedPower >= 0`) further
         *  press calls are no-ops, so rapid left-click spam can't re-trigger the
         *  click sound or re-extend the timer. The press period itself is the cooldown,
         *  cleared when the scheduled tick fires (or when a manual right-click commits
         *  a new dial level). Matches vanilla button behaviour. */
        fun pressButton(level: Level, pos: BlockPos, state: BlockState, be: AncriteEyeBlockEntity) {
            if (be.savedPower >= 0) return
            be.savedPower = state.getValue(POWER)
            be.setChanged()
            setPower(level, pos, state, MAX_POWER, pressed = true)
            level.scheduleTick(pos, state.block, PRESS_DURATION_TICKS)
        }

        /** Apply a new (power, pressed) pair and play the analog click. Propagates a
         *  neighbour update through the supporting block so the strong-power path past
         *  the wall re-evaluates. */
        fun setPower(level: Level, pos: BlockPos, state: BlockState, power: Int, pressed: Boolean) {
            val clamped = power.coerceIn(0, MAX_POWER)
            val newState = state.setValue(POWER, clamped).setValue(PRESSED, pressed)
            level.setBlock(pos, newState, UPDATE_ALL)
            updateNeighbours(newState, level, pos)
            val pitch = 0.5f + (clamped / MAX_POWER.toFloat()) * 1.0f
            level.playSound(
                null, pos,
                SoundEvents.LEVER_CLICK, SoundSource.BLOCKS,
                0.3f, pitch,
            )
        }

        /** Same two-step neighbour poke `LeverBlock.updateNeighbours` does — at the eye's
         *  own pos and at the supporting block on the other side. */
        private fun updateNeighbours(state: BlockState, level: Level, pos: BlockPos) {
            level.updateNeighborsAt(pos, state.block)
            level.updateNeighborsAt(pos.relative(getConnectedDirection(state).opposite), state.block)
        }
    }
}
