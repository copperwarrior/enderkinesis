package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Coarse woody debris: short broken stumps (more of them near the Mother Tree) and fallen logs
 * that share a windthrow direction in each region and lie half-sunk where they cross water.
 */
class WohlonDeadwoodFeature(codec: Codec<NoneFeatureConfiguration>) : Feature<NoneFeatureConfiguration>(codec) {

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        val random = context.random()
        val x0 = context.origin().x and 15.inv()
        val z0 = context.origin().z and 15.inv()
        // WorldGenRegion reports the vanilla default of 63; the generator holds the real sea level.
        val waterTopY = context.chunkGenerator().seaLevel - 1
        val pos = BlockPos.MutableBlockPos()
        var placed = false

        val nearMother = x0.toLong() * x0 + z0.toLong() * z0 < MOTHER_SNAG_RADIUS_SQ
        if (random.nextFloat() < if (nearMother) SNAG_CHANCE_NEAR_MOTHER else SNAG_CHANCE) {
            placed = placeSnag(level, random, pos, x0 + random.nextInt(16), z0 + random.nextInt(16), waterTopY)
        }
        val logs = if (random.nextFloat() < SECOND_LOG_CHANCE) 2 else 1
        repeat(logs) {
            if (random.nextFloat() >= LOG_CHANCE) return@repeat
            val x = x0 + random.nextInt(16)
            val z = z0 + random.nextInt(16)
            val yaw = windthrowYaw(level, random, x, z)
            placed = placeFallenLog(level, random, pos, x0, z0, x, z, yaw, waterTopY) || placed
        }
        return placed
    }

    /** Ground (mud) top for a column of this chunk, allowing at most 1 block of water over it. */
    private fun groundY(level: WorldGenLevel, pos: BlockPos.MutableBlockPos, x: Int, z: Int, waterTopY: Int): Int? {
        var y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
        if (level.getBlockState(pos.set(x, y, z)).`is`(Blocks.WATER)) {
            if (y != waterTopY) return null
            y--
        }
        return if (isGround(level.getBlockState(pos.set(x, y, z))) && y >= waterTopY - 1) y else null
    }

    /** A broken stump: bark trunk, a splintered top from wood slabs and stairs, and 1–2 branch stubs. */
    private fun placeSnag(
        level: WorldGenLevel, random: RandomSource, pos: BlockPos.MutableBlockPos, x: Int, z: Int, waterTopY: Int,
    ): Boolean {
        val ground = groundY(level, pos, x, z, waterTopY) ?: return false
        if (ground <= waterTopY && random.nextFloat() < WET_SNAG_SKIP) return false
        val height = SNAG_MIN_HEIGHT + random.nextInt(SNAG_MAX_HEIGHT - SNAG_MIN_HEIGHT + 1)
        val wood = EKBlocks.WOGOR_WOOD.get().defaultBlockState()
        var top = ground
        for (y in ground + 1..ground + height) {
            if (!isOpen(level.getBlockState(pos.set(x, y, z)))) break
            level.setBlock(pos, wood, Block.UPDATE_CLIENTS)
            top = y
        }
        if (top == ground) return false
        if (level.isEmptyBlock(pos.set(x, top + 1, z))) {
            level.setBlock(pos, splinter(random), Block.UPDATE_CLIENTS)
        }
        if (top - ground < SNAG_STUB_MIN_Y) return true
        repeat(random.nextInt(SNAG_MAX_STUBS) + 1) {
            val dir = Direction.Plane.HORIZONTAL.getRandomDirection(random)
            val y = ground + SNAG_STUB_MIN_Y - 1 + random.nextInt(top - ground - SNAG_STUB_MIN_Y + 1)
            if (level.isEmptyBlock(pos.set(x + dir.stepX, y, z + dir.stepZ))) {
                level.setBlock(pos, wood.setValue(BlockStateProperties.AXIS, dir.axis), Block.UPDATE_CLIENTS)
            }
        }
        return true
    }

    /** Jagged broken top: a half block or a stair facing a random way. */
    private fun splinter(random: RandomSource): BlockState =
        if (random.nextBoolean()) {
            EKBlocks.WOGOR_WOOD_SLAB.get().defaultBlockState()
        } else {
            EKBlocks.WOGOR_WOOD_STAIRS.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.Plane.HORIZONTAL.getRandomDirection(random))
        }

    /** Fallen trees in one region lie along a shared wind direction, ±[WINDTHROW_SPREAD]. */
    private fun windthrowYaw(level: WorldGenLevel, random: RandomSource, x: Int, z: Int): Double {
        val rx = Math.floorDiv(x, WINDTHROW_REGION)
        val rz = Math.floorDiv(z, WINDTHROW_REGION)
        val regionRandom = RandomSource.create(level.seed xor (rx * 341873128712L + rz * 132897987541L))
        val base = regionRandom.nextDouble() * 2.0 * Math.PI
        return base + (random.nextDouble() * 2.0 - 1.0) * WINDTHROW_SPREAD
    }

    /**
     * A log must rest on ground along its whole length: on dry mud, or on the bed of water one
     * block deep. It stays inside its own chunk, because a neighbour chunk paints its rivers and
     * roots later and would cut the ground out from under it. If the wind direction does not fit,
     * the log falls the opposite way; if neither fits, there is no log.
     */
    private fun placeFallenLog(
        level: WorldGenLevel, random: RandomSource, pos: BlockPos.MutableBlockPos,
        x0: Int, z0: Int, x: Int, z: Int, yaw: Double, waterTopY: Int,
    ): Boolean {
        val startGround = groundY(level, pos, x, z, waterTopY) ?: return false
        val y = logY(startGround, waterTopY)
        if (y != startGround + 1) return false
        val length = LOG_MIN_LENGTH + random.nextInt(LOG_MAX_LENGTH - LOG_MIN_LENGTH + 1)
        var stepX = Math.cos(yaw)
        var stepZ = Math.sin(yaw)
        var run = restingRun(level, pos, x0, z0, x, y, z, stepX, stepZ, length)
        if (run < LOG_MIN_LENGTH) {
            stepX = -stepX
            stepZ = -stepZ
            run = restingRun(level, pos, x0, z0, x, y, z, stepX, stepZ, length)
        }
        if (run < LOG_MIN_LENGTH) return false
        val axis = if (Math.abs(stepX) >= Math.abs(stepZ)) Direction.Axis.X else Direction.Axis.Z
        val log = EKBlocks.WOGOR_LOG.get().defaultBlockState().setValue(BlockStateProperties.AXIS, axis)
        for (t in 0 until run) {
            pos.set(x + Mth.floor(stepX * t + 0.5), y, z + Mth.floor(stepZ * t + 0.5))
            level.setBlock(pos, log, Block.UPDATE_CLIENTS)
        }
        return true
    }

    /** How many log blocks in a row fit at [y], each in open space with ground directly beneath, inside this chunk. */
    private fun restingRun(
        level: WorldGenLevel, pos: BlockPos.MutableBlockPos, x0: Int, z0: Int,
        x: Int, y: Int, z: Int, stepX: Double, stepZ: Double, length: Int,
    ): Int {
        for (t in 0 until length) {
            val lx = x + Mth.floor(stepX * t + 0.5)
            val lz = z + Mth.floor(stepZ * t + 0.5)
            if (lx - x0 !in 0..15 || lz - z0 !in 0..15) return t
            if (!isOpen(level.getBlockState(pos.set(lx, y, lz)))) return t
            if (!isGround(level.getBlockState(pos.set(lx, y - 1, lz)))) return t
        }
        return length
    }

    /** A log on dry ground rests on it; in water one block deep it rests on the bed, half-sunk. */
    private fun logY(ground: Int, waterTopY: Int): Int = maxOf(ground + 1, waterTopY)

    private fun isOpen(state: BlockState): Boolean = state.isAir || state.canBeReplaced()

    private fun isGround(state: BlockState): Boolean =
        state.`is`(EKBlocks.WOHLON_MUD.get()) || state.`is`(Blocks.MUD) || state.`is`(EKBlocks.WOHLON_MOSS.get())

    private companion object {
        const val MOTHER_SNAG_RADIUS_SQ = 300L * 300L
        const val SNAG_CHANCE_NEAR_MOTHER = 1.0f
        const val SNAG_CHANCE = 0.3f
        /** Share of stumps dropped where the ground is at or under the water line. */
        const val WET_SNAG_SKIP = 0.5f
        const val SNAG_MIN_HEIGHT = 3
        const val SNAG_MAX_HEIGHT = 7
        const val SNAG_MAX_STUBS = 2
        const val SNAG_STUB_MIN_Y = 2

        const val LOG_CHANCE = 0.85f
        const val SECOND_LOG_CHANCE = 0.4f
        const val LOG_MIN_LENGTH = 4
        const val LOG_MAX_LENGTH = 10
        const val WINDTHROW_REGION = 128
        /** ±30°. */
        const val WINDTHROW_SPREAD = Math.PI / 6.0
    }
}
