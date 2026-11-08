package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.sounds.SoundSource
import org.shipwrights.enderkinesis.registry.EKSounds
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKParticles

class InegricRetortBlock(properties: Properties) : Block(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(DRIPPING, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(DRIPPING)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPE

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean {
        val above = pos.above()
        return level.getBlockState(above).isFaceSturdy(level, above, Direction.DOWN)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun updateShape(
        state: BlockState, direction: Direction, neighborState: BlockState,
        level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState =
        if (direction == Direction.UP && !state.canSurvive(level, pos)) Blocks.AIR.defaultBlockState() else state

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, moved: Boolean) {
        if (level !is ServerLevel || oldState.`is`(this)) return
        syncDripping(state, level, pos)
        refreshJarBelow(level, pos)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, moved: Boolean) {
        super.onRemove(state, level, pos, newState, moved)
        if (!level.isClientSide && !newState.`is`(this)) refreshJarBelow(level, pos)
    }

    private fun refreshJarBelow(level: Level, pos: BlockPos) {
        val target = firstBlockBelow(level, pos) ?: return
        val state = level.getBlockState(target)
        if (state.`is`(EKBlocks.ICHAERIC_INK_JAR.get())) IchaericInkJarBlock.refreshOpen(level, target, state)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        syncDripping(state, level, pos)
        if (random.nextFloat() < InegricRetortRates.dripChance(level.dimension())) drip(level, pos)
    }

    // The rates are server data, so the client reads this property to know when to show drips.
    private fun syncDripping(state: BlockState, level: ServerLevel, pos: BlockPos) {
        val dripping = InegricRetortRates.dripChance(level.dimension()) > 0f
        if (state.getValue(DRIPPING) != dripping) level.setBlock(pos, state.setValue(DRIPPING, dripping), UPDATE_ALL)
    }

    private fun drip(level: ServerLevel, pos: BlockPos) {
        val target = firstBlockBelow(level, pos) ?: return
        val state = level.getBlockState(target)
        if (state.`is`(EKBlocks.ICHAERIC_INK_JAR.get())) {
            fillJar(level, target, state)
        } else if (pos.y - target.y > 1 && state.isFaceSturdy(level, target, Direction.UP)) {
            placePuddle(level, target.above())
        }
    }

    private fun firstBlockBelow(level: BlockGetter, pos: BlockPos): BlockPos? {
        val cursor = pos.mutable()
        repeat(MAX_DRIP_DISTANCE) {
            if (!level.getBlockState(cursor.move(Direction.DOWN)).isAir) return cursor.immutable()
        }
        return null
    }

    private fun fillJar(level: ServerLevel, pos: BlockPos, state: BlockState) {
        val bottles = state.getValue(IchaericInkJarBlock.BOTTLES)
        if (bottles >= IchaericInkJarBlock.MAX_BOTTLES) return
        level.setBlock(pos, state.setValue(IchaericInkJarBlock.BOTTLES, bottles + 1), UPDATE_ALL)
        level.playSound(null, pos, EKSounds.INEGRIC_RETORT_DRIP.get(), SoundSource.BLOCKS, 1f, 1f)
        level.gameEvent(null, GameEvent.BLOCK_CHANGE, pos)
    }

    private fun placePuddle(level: ServerLevel, pos: BlockPos) {
        val puddle = EKBlocks.ICHAERIC_INK.get().defaultBlockState()
            .setValue(LiquidBlock.LEVEL, IchaericInkFlowingFluid.PUDDLE_BLOCK_LEVEL)
        level.setBlock(pos, puddle, UPDATE_ALL)
        level.playSound(null, pos, EKSounds.INEGRIC_RETORT_SPLAT.get(), SoundSource.BLOCKS, 1f, 1f)
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (!state.getValue(DRIPPING) || random.nextFloat() >= DRIP_PARTICLE_CHANCE) return
        level.addParticle(EKParticles.ichaericInkDrip(), pos.x + 0.5, pos.y + TIP_Y, pos.z + 0.5, 0.0, 0.0, 0.0)
    }

    companion object {
        val DRIPPING: BooleanProperty = BooleanProperty.create("dripping")

        // Pointed dripstone's cauldron search length and water drip particle chance.
        const val MAX_DRIP_DISTANCE = 11
        private const val DRIP_PARTICLE_CHANCE = 0.12f

        private const val TIP_Y = 1.0 / 16.0
        private val SHAPE: VoxelShape = Shapes.or(
            box(4.0, 7.0, 4.0, 12.0, 16.0, 12.0),
            box(6.0, 1.0, 6.0, 10.0, 7.0, 10.0),
        )
    }
}
