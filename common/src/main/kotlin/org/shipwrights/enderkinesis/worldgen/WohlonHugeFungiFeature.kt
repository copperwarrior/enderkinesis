package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Rare large mushrooms where their small forms would thrive. Lumpy (root partners): raised
 * ground near a tree, well away from the water, since waterlogging starves them of oxygen.
 * Cup (wood decomposers): beside fallen logs and stumps. Kept inside the chunk, so a later
 * neighbour's river or root paint cannot cut the cap.
 */
class WohlonHugeFungiFeature(codec: Codec<NoneFeatureConfiguration>) : Feature<NoneFeatureConfiguration>(codec) {

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        val random = context.random()
        val x0 = context.origin().x and 15.inv()
        val z0 = context.origin().z and 15.inv()
        val waterTopY = context.chunkGenerator().seaLevel - 1
        val pos = BlockPos.MutableBlockPos()
        var placed = false
        for (shape in HugeWohlonMushroomFeature.Shape.values()) {
            val chance = if (shape == HugeWohlonMushroomFeature.Shape.LUMPY) LUMPY_CHUNK_CHANCE else CUP_CHUNK_CHANCE
            if (random.nextFloat() >= chance) continue
            for (attempt in 0 until CANDIDATES) {
                val x = x0 + EDGE + random.nextInt(SPAN)
                val z = z0 + EDGE + random.nextInt(SPAN)
                val ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
                val soil = level.getBlockState(pos.set(x, ground, z))
                if (!soil.`is`(EKBlocks.WOHLON_MUD.get()) && !soil.`is`(EKBlocks.WOHLON_MOSS.get())) continue
                val fits = when (shape) {
                    HugeWohlonMushroomFeature.Shape.LUMPY ->
                        ground - waterTopY >= LUMPY_MIN_RISE && !waterNear(level, pos, x, z, waterTopY) &&
                            near(level, pos, x, ground, z, LUMPY_TREE_REACH, ::isTrunk)
                    HugeWohlonMushroomFeature.Shape.CUP ->
                        ground > waterTopY && near(level, pos, x, ground, z, CUP_WOOD_REACH, ::isDeadWood)
                }
                if (fits && HugeWohlonMushroomFeature.grow(level, random, BlockPos(x, ground + 1, z), shape)) {
                    placed = true
                    break
                }
            }
        }
        return placed
    }

    private fun waterNear(level: WorldGenLevel, pos: BlockPos.MutableBlockPos, x: Int, z: Int, waterTopY: Int): Boolean {
        for (dx in -LUMPY_WATER_CLEARANCE..LUMPY_WATER_CLEARANCE) {
            for (dz in -LUMPY_WATER_CLEARANCE..LUMPY_WATER_CLEARANCE) {
                if (level.getFluidState(pos.set(x + dx, waterTopY, z + dz)).`is`(FluidTags.WATER)) return true
            }
        }
        return false
    }

    private fun near(
        level: WorldGenLevel, pos: BlockPos.MutableBlockPos, x: Int, ground: Int, z: Int, reach: Int,
        test: (BlockState) -> Boolean,
    ): Boolean {
        for (dx in -reach..reach) {
            for (dz in -reach..reach) {
                for (dy in 1..2) {
                    if (test(level.getBlockState(pos.set(x + dx, ground + dy, z + dz)))) return true
                }
            }
        }
        return false
    }

    private fun isWood(state: BlockState): Boolean =
        state.`is`(EKBlocks.WOGOR_WOOD.get()) || state.`is`(EKBlocks.WOGOR_LOG.get())

    private fun isTrunk(state: BlockState): Boolean =
        isWood(state) && state.getValue(BlockStateProperties.AXIS) == Direction.Axis.Y

    /** Fallen logs lie on their side; stumps end in a splintered slab or stair. */
    private fun isDeadWood(state: BlockState): Boolean =
        (isWood(state) && state.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y) ||
            state.`is`(EKBlocks.WOGOR_WOOD_SLAB.get()) || state.`is`(EKBlocks.WOGOR_WOOD_STAIRS.get())

    private companion object {
        const val EDGE = HugeWohlonMushroomFeature.MAX_CAP_REACH
        const val SPAN = 16 - 2 * EDGE
        const val CANDIDATES = 8
        /** About one of each in 6–8 chunks. */
        const val LUMPY_CHUNK_CHANCE = 0.16f
        const val CUP_CHUNK_CHANCE = 0.12f
        const val LUMPY_MIN_RISE = 2
        const val LUMPY_WATER_CLEARANCE = 3
        const val LUMPY_TREE_REACH = 4
        const val CUP_WOOD_REACH = 3
    }
}
