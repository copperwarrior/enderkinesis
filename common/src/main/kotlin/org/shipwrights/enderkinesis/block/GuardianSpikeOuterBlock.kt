package org.shipwrights.enderkinesis.block

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.BlockEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import java.util.EnumMap

/** One spike off the outer edge or corner of a single block. Placed only by GuardianSpikeItem and worldgen. */
class GuardianSpikeOuterBlock(props: Properties) : GuardianSpikeBase(props) {

    init {
        registerDefaultState(stateDefinition.any().setValue(ATTACH, SpikeAttachment.DOWN_NORTH).setValue(WATERLOGGED, false))
    }

    override val outer: Boolean get() = true

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(ATTACH, WATERLOGGED)
    }

    override fun spikes(state: BlockState): List<SpikeAttachment> = listOf(state.getValue(ATTACH))

    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean {
        val attach = state.getValue(ATTACH)
        val support = pos.offset(attach.dx, attach.dy, attach.dz)
        val supportState = level.getBlockState(support)
        return SUPPORT_FACES.getValue(attach).all { supportState.isFaceSturdy(level, support, it) }
    }

    // The support is not a face neighbour; any face update is just a cheap chance to re-check it.
    override fun updateShape(
        state: BlockState, dir: Direction, neighbor: BlockState, level: LevelAccessor, pos: BlockPos, neighborPos: BlockPos,
    ): BlockState {
        scheduleWater(state, level, pos)
        return if (state.canSurvive(level, pos)) state else Blocks.AIR.defaultBlockState()
    }

    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (!state.canSurvive(level, pos)) level.destroyBlock(pos, true)
    }

    override fun isRandomlyTicking(state: BlockState): Boolean = true

    // Fallback for supports removed without a break event (explosions, pistons).
    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        tick(state, level, pos, random)
    }

    companion object {
        val ATTACH: EnumProperty<SpikeAttachment> = EnumProperty.create("attach", SpikeAttachment::class.java) { it.order > 1 }

        /** The support's faces under the spike: one per non-zero axis, each facing back toward the spike cell. */
        private val SUPPORT_FACES: Map<SpikeAttachment, List<Direction>> =
            EnumMap<SpikeAttachment, List<Direction>>(SpikeAttachment::class.java).apply {
                for (attach in SpikeAttachment.values()) {
                    put(attach, buildList {
                        if (attach.dx != 0) add(Direction.fromAxisAndDirection(Direction.Axis.X, sign(-attach.dx)))
                        if (attach.dy != 0) add(Direction.fromAxisAndDirection(Direction.Axis.Y, sign(-attach.dy)))
                        if (attach.dz != 0) add(Direction.fromAxisAndDirection(Direction.Axis.Z, sign(-attach.dz)))
                    })
                }
            }

        private fun sign(d: Int): Direction.AxisDirection =
            if (d > 0) Direction.AxisDirection.POSITIVE else Direction.AxisDirection.NEGATIVE

        /** The support is not a face neighbour, so a support break schedules a check on its spikes. */
        fun registerEvents() {
            BlockEvent.BREAK.register { level, pos, _, _, _ ->
                if (level is ServerLevel) scheduleDiagonalChecks(level, pos)
                EventResult.pass()
            }
        }

        private fun scheduleDiagonalChecks(level: ServerLevel, support: BlockPos) {
            val cursor = BlockPos.MutableBlockPos()
            for (attach in ATTACH.possibleValues) {
                cursor.setWithOffset(support, -attach.dx, -attach.dy, -attach.dz)
                val state = level.getBlockState(cursor)
                if (state.block is GuardianSpikeOuterBlock && state.getValue(ATTACH) == attach) {
                    level.scheduleTick(cursor.immutable(), state.block, 1)
                }
            }
        }
    }
}
