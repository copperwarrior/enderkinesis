package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty

/** Random-tick vine growth gated by [WogorVineNoise] so vine zones cluster instead of evenly covering all wood. */
class WogorWoodBlock(properties: Properties) : RotatedPillarBlock(properties) {

    override fun isRandomlyTicking(state: BlockState): Boolean = true

    override fun randomTick(
        state: BlockState,
        level: ServerLevel,
        pos: BlockPos,
        random: RandomSource,
    ) {
        if (!WogorVineNoise.shouldVine(pos.x, pos.y, pos.z)) return

        // 1/4 gate → first vine ~4 min after chunk-load at vanilla randomTickSpeed=3.
        if (random.nextInt(4) != 0) return

        val order = SCRATCH_INDICES.copyOf()
        for (i in 3 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = order[i]; order[i] = order[j]; order[j] = tmp
        }
        for (idx in order) {
            val dir = HORIZONTAL_DIRECTIONS[idx]
            val sidePos = pos.relative(dir)
            val sideState = level.getBlockState(sidePos)
            if (!sideState.isAir) continue

            // VineBlock property names the wall the vine attaches to — from the vine's POV at sidePos, the wood is in dir.opposite.
            val vineState = Blocks.VINE.defaultBlockState()
                .setValue(faceProperty(dir.opposite), true)
            level.setBlock(sidePos, vineState, 2)
            return
        }
    }

    companion object {
        private val HORIZONTAL_DIRECTIONS: Array<Direction> = arrayOf(
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
        )
        private val SCRATCH_INDICES: IntArray = intArrayOf(0, 1, 2, 3)

        private fun faceProperty(toward: Direction): BooleanProperty = when (toward) {
            Direction.NORTH -> VineBlock.NORTH
            Direction.SOUTH -> VineBlock.SOUTH
            Direction.EAST -> VineBlock.EAST
            Direction.WEST -> VineBlock.WEST
            else -> error("Vine face property only defined for horizontals; got $toward")
        }
    }
}
