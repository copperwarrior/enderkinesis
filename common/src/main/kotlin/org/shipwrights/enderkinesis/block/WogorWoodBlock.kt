package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import org.shipwrights.enderkinesis.registry.EKBlocks
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty

/** Random-tick vine growth gated by [WogorVineNoise] so vine zones cluster instead of evenly covering all wood. */
class WogorWoodBlock(properties: Properties) : RotatedPillarBlock(properties) {

    override fun isRandomlyTicking(state: BlockState): Boolean = true

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
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
            val side = pos.relative(dir)
            if (!columnCovered(side.x, side.z, idx)) continue
            // VineBlock property names the wall the vine attaches to — from the vine's POV at sidePos, the wood is in dir.opposite.
            val face = faceProperty(dir.opposite)
            val sideState = level.getBlockState(side)
            if (sideState.isAir) {
                if (!startsHere(level, side, idx)) continue
                level.setBlock(side, EKBlocks.WEJE_VINE.get().defaultBlockState().setValue(face, true), 2)
                return
            }
            if (isVineOn(sideState, face) && hangDown(level, side, face, hangLength(side.x, side.z, idx))) return
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

        /** Scales the zone noise to ~3-block clumps, so covered columns come in patches with gaps between. */
        private const val CLUMP_SCALE = 4
        private const val COVER_THRESHOLD = 0.55
        private const val COVER_NOISE_Y = 0x5EED
        private const val START_CHANCE = 1.0 / 6.0
        private const val MAX_HANG = 5

        private fun columnCovered(x: Int, z: Int, side: Int): Boolean =
            WogorVineNoise.rawNoise(x * CLUMP_SCALE, COVER_NOISE_Y + side * 37, z * CLUMP_SCALE) > COVER_THRESHOLD

        /** A vine starts under a lip, or at a sparse hashed height on an open face. */
        private fun startsHere(level: ServerLevel, side: BlockPos, idx: Int): Boolean =
            !level.isEmptyBlock(side.above()) ||
                WogorVineNoise.cellHash01(side.x, side.y * 4 + idx, side.z) < START_CHANCE

        /** Squared so most hangs are short and a few trail far. */
        private fun hangLength(x: Int, z: Int, idx: Int): Int {
            val u = WogorVineNoise.cellHash01(x, COVER_NOISE_Y - idx, z)
            return 1 + (u * u * MAX_HANG).toInt().coerceAtMost(MAX_HANG - 1)
        }

        private fun isVineOn(state: BlockState, face: BooleanProperty): Boolean =
            state.`is`(EKBlocks.WEJE_VINE.get()) && state.getValue(face)

        /** Grows the hang one cell; vanilla lets a vine hold on the same-face vine above it with no wall behind. */
        private fun hangDown(level: ServerLevel, from: BlockPos, face: BooleanProperty, length: Int): Boolean {
            // Every wood block beside the hang ticks it, so the length counts from the hang's top, not from here.
            val cursor = from.mutable()
            var above = 0
            while (above < length && isVineOn(level.getBlockState(cursor.move(Direction.UP)), face)) above++
            cursor.set(from)
            repeat(length - above - 1) {
                cursor.move(Direction.DOWN)
                if (level.isOutsideBuildHeight(cursor)) return false
                val below = level.getBlockState(cursor)
                if (!isVineOn(below, face)) {
                    if (!below.isAir) return false
                    level.setBlock(cursor, EKBlocks.WEJE_VINE.get().defaultBlockState().setValue(face, true), 2)
                    return true
                }
            }
            return false
        }

        private fun faceProperty(toward: Direction): BooleanProperty = when (toward) {
            Direction.NORTH -> VineBlock.NORTH
            Direction.SOUTH -> VineBlock.SOUTH
            Direction.EAST -> VineBlock.EAST
            Direction.WEST -> VineBlock.WEST
            else -> error("Vine face property only defined for horizontals; got $toward")
        }
    }
}
