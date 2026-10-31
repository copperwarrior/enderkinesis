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

        val order = SIDE_ORDERS[random.nextInt(SIDE_ORDERS.size)]
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
        /** All 24 orders of the 4 sides; a random row is a uniform shuffle with no allocation. */
        private val SIDE_ORDERS: Array<IntArray> = buildList {
            for (a in 0..3) for (b in 0..3) for (c in 0..3) for (d in 0..3) {
                if (setOf(a, b, c, d).size == 4) add(intArrayOf(a, b, c, d))
            }
        }.toTypedArray()

        private fun faceProperty(toward: Direction): BooleanProperty = when (toward) {
            Direction.NORTH -> VineBlock.NORTH
            Direction.SOUTH -> VineBlock.SOUTH
            Direction.EAST -> VineBlock.EAST
            Direction.WEST -> VineBlock.WEST
            else -> error("Vine face property only defined for horizontals; got $toward")
        }
    }
}
