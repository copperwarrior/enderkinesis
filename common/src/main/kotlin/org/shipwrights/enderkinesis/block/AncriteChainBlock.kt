package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.util.RandomSource
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ChainBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.joml.Vector3f

/**
 * Ancrite Chain — vanilla [ChainBlock] augmented with redstone transmission along its axis.
 *
 * Behaves like an "invisible wire": signals don't render wire connections to the endcaps, but
 * the chain *does* propagate power through itself with vanilla-wire-style decay (one block of
 * fall-off per chain segment). The four perpendicular faces emit no signal; the two axis-aligned
 * endcaps emit the chain's current [POWER].
 *
 * Visuals match redstone dust:
 *  - Block-light emission = POWER (0 unpowered, 15 at full).
 *  - Texture tint via the registered BlockColor — ender-green that brightens with POWER.
 *  - [animateTick] sprays ender-green dust particles when powered, same cadence as wire.
 */
class AncriteChainBlock(properties: BlockBehaviour.Properties) : ChainBlock(properties) {

    init {
        // ChainBlock's own constructor set the default state with WATERLOGGED + AXIS only;
        // re-register here so POWER is included with a sane default of 0.
        registerDefaultState(
            stateDefinition.any()
                .setValue(BlockStateProperties.WATERLOGGED, false)
                .setValue(BlockStateProperties.AXIS, Direction.Axis.Y)
                .setValue(POWER, 0)
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)              // adds WATERLOGGED + AXIS
        builder.add(POWER)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val base = super.getStateForPlacement(context) ?: return null
        // Compute the starting POWER from whatever's already adjacent so the chain doesn't
        // need a server tick of latency to "discover" an existing signal source on placement.
        return base.setValue(POWER, computeInputPower(context.level, context.clickedPos, base))
    }

    override fun isSignalSource(state: BlockState): Boolean = true

    /** Weak-signal output: [POWER] on the two axis-aligned faces, 0 on the four perpendicular
     *  faces (so wires sneaking around the sides don't pick up the signal). No direct/strong
     *  signal — adjacent solid blocks pick up only weak power, exactly like wire-with-no-
     *  direct-output. */
    override fun getSignal(state: BlockState, level: BlockGetter, pos: BlockPos, side: Direction): Int {
        if (side.axis != state.getValue(BlockStateProperties.AXIS)) return 0
        return state.getValue(POWER)
    }

    @Deprecated("Deprecated in Java")
    override fun neighborChanged(
        state: BlockState, level: Level, pos: BlockPos,
        neighborBlock: Block, neighborPos: BlockPos, isMoving: Boolean,
    ) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, isMoving)
        if (level.isClientSide) return
        val newPower = computeInputPower(level, pos, state)
        if (state.getValue(POWER) != newPower) {
            // UPDATE_ALL fires neighborChanged on the next chain along the axis → cascade
            // propagates the new level with one-block decay, exactly like vanilla redstone wire.
            level.setBlock(pos, state.setValue(POWER, newPower), Block.UPDATE_ALL)
        }
    }

    /** Take the max of "input from either axis-aligned end". For chain-to-chain transmission we
     *  subtract 1 to match vanilla wire's decay; for any other block we read the weak signal it
     *  emits toward us (`level.getSignal`), which already accounts for source / conductor /
     *  strong-power semantics. */
    private fun computeInputPower(level: Level, pos: BlockPos, state: BlockState): Int {
        val axis = state.getValue(BlockStateProperties.AXIS)
        var best = 0
        for (axisDir in arrayOf(Direction.AxisDirection.POSITIVE, Direction.AxisDirection.NEGATIVE)) {
            val dir = Direction.fromAxisAndDirection(axis, axisDir)
            val nPos = pos.relative(dir)
            val nState = level.getBlockState(nPos)
            val input = if (nState.`is`(this) && nState.getValue(BlockStateProperties.AXIS) == axis) {
                (nState.getValue(POWER) - 1).coerceAtLeast(0)
            } else {
                level.getSignal(nPos, dir).coerceAtLeast(0)
            }
            if (input > best) best = input
        }
        return best.coerceIn(0, 15)
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        val power = state.getValue(POWER)
        if (power == 0) return
        // Spawn one dust particle per call, constrained to the chain's voxel shape. Vanilla
        // chain is a 3-wide pillar (6.5..9.5 px) on the two perpendicular axes and full extent
        // (0..16 px) along the chain's axis — randomise inside that AABB.
        val axis = state.getValue(BlockStateProperties.AXIS)
        val xMin = if (axis == Direction.Axis.X) 0.0 else CHAIN_NARROW_MIN
        val xMax = if (axis == Direction.Axis.X) 1.0 else CHAIN_NARROW_MAX
        val yMin = if (axis == Direction.Axis.Y) 0.0 else CHAIN_NARROW_MIN
        val yMax = if (axis == Direction.Axis.Y) 1.0 else CHAIN_NARROW_MAX
        val zMin = if (axis == Direction.Axis.Z) 0.0 else CHAIN_NARROW_MIN
        val zMax = if (axis == Direction.Axis.Z) 1.0 else CHAIN_NARROW_MAX
        val x = pos.x + xMin + random.nextDouble() * (xMax - xMin)
        val y = pos.y + yMin + random.nextDouble() * (yMax - yMin)
        val z = pos.z + zMin + random.nextDouble() * (zMax - zMin)
        val intensity = 0.25f + (power / 15f) * 0.75f
        level.addParticle(
            DustParticleOptions(Vector3f(0.20f * intensity, 0.95f * intensity, 0.45f * intensity), 1.0f),
            x, y, z, 0.0, 0.0, 0.0,
        )
    }

    companion object {
        val POWER = BlockStateProperties.POWER

        /** Vanilla chain voxel shape: 3-wide pillar from 6.5/16 to 9.5/16 on the two
         *  perpendicular axes. Used to constrain particle spawns to the chain's actual volume. */
        private const val CHAIN_NARROW_MIN = 6.5 / 16.0
        private const val CHAIN_NARROW_MAX = 9.5 / 16.0

        /** Tint at POWER 0. Texture (`ancrite_chain_bright.png`) is the brightest reference;
         *  this factor (0–1) is what the white tint at POWER 0 multiplies it down to, sized
         *  so the result matches the original `ancrite_chain.png` appearance. `0.667` ≈ 1/1.5,
         *  matching the current bright-texture being ~1.5× the original. */
        private const val MIN_BRIGHTNESS_FACTOR = 0.667f

        /** White-only ARGB tint scaling with POWER. The texture stays its native colour at every
         *  level (R = G = B from the tint just scales luminance). Used by the BlockColor
         *  registered for ANCRITE_CHAIN in EnderkinesisModClient. */
        fun getColorForPower(power: Int): Int {
            val p = power.coerceIn(0, 15)
            val factor = MIN_BRIGHTNESS_FACTOR + (1f - MIN_BRIGHTNESS_FACTOR) * (p / 15f)
            val v = (factor * 255f).toInt().coerceIn(0, 255)
            return (v shl 16) or (v shl 8) or v
        }
    }
}
