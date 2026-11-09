package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Swamp trunk bases, after the trees: a mud collar (trees stand on hummocks), a broken ring
 * of water-filled hollows around it, buttress fins up to the flood line, and cypress knees whose
 * tips stand 1–2 blocks above the water. Only trees growing near the water line get them.
 */
class WohlonTrunkBaseFeature(codec: Codec<NoneFeatureConfiguration>) : Feature<NoneFeatureConfiguration>(codec) {

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        val random = context.random()
        val x0 = context.origin().x and 15.inv()
        val z0 = context.origin().z and 15.inv()
        if (x0.toLong() * x0 + z0.toLong() * z0 < MOTHER_TREE_CLEARANCE_SQ) return false
        // WorldGenRegion reports the vanilla default of 63; the generator holds the real sea level.
        val waterTopY = context.chunkGenerator().seaLevel - 1
        val pos = BlockPos.MutableBlockPos()
        var placed = false
        for (lx in 0..15) {
            for (lz in 0..15) {
                val x = x0 + lx
                val z = z0 + lz
                val baseY = trunkBaseY(level, pos, x, z) ?: continue
                val groundY = baseY - 1
                if (groundY > waterTopY + 1) continue
                // An edge column of a thick trunk is one of many; scale per-column work down.
                val share = if (woodSides(level, pos, x, baseY, z) > 0) THICK_TRUNK_SHARE else 1f
                raiseCollar(level, random, pos, x, groundY, z)
                carveHollows(level, random, pos, x, z, waterTopY, share)
                growFins(level, random, pos, x, baseY, z, waterTopY + FLOOD_RISE)
                growKnees(level, random, pos, x, z, waterTopY, share)
                placed = true
            }
        }
        return placed
    }

    /** Lowest block of an upright wogor trunk standing on mud in this column, if it is a trunk edge. */
    private fun trunkBaseY(level: WorldGenLevel, pos: BlockPos.MutableBlockPos, x: Int, z: Int): Int? {
        val top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
        if (!isUprightWood(level.getBlockState(pos.set(x, top, z)))) return null
        // Living trees only: stumps and logs carry no crown.
        if (!level.getBlockState(pos.set(x, top + 1, z)).`is`(BlockTags.LEAVES)) return null
        var y = top
        var run = 0
        while (run < MAX_TRUNK_SCAN && isUprightWood(level.getBlockState(pos.set(x, y - 1, z)))) {
            y--
            run++
        }
        if (run + 1 < MIN_TRUNK_RUN || !isMud(level.getBlockState(pos.set(x, y - 1, z)))) return null
        // Inner columns of a thick trunk are boxed in by wood; only the edge flares.
        return if (woodSides(level, pos, x, y, z) == HORIZONTAL.size) null else y
    }

    private fun woodSides(level: WorldGenLevel, pos: BlockPos.MutableBlockPos, x: Int, y: Int, z: Int): Int {
        var sides = 0
        for (dir in HORIZONTAL) {
            if (isWood(level.getBlockState(pos.set(x + dir.stepX, y, z + dir.stepZ)))) sides++
        }
        return sides
    }

    /** Mounds mud up against the trunk: raise low columns near it to [groundY] + 1, ragged at the rim. */
    private fun raiseCollar(
        level: WorldGenLevel, random: RandomSource, pos: BlockPos.MutableBlockPos, x: Int, groundY: Int, z: Int,
    ) {
        val mud = EKBlocks.WOHLON_MUD.get().defaultBlockState()
        for (dx in -COLLAR_RADIUS..COLLAR_RADIUS) {
            for (dz in -COLLAR_RADIUS..COLLAR_RADIUS) {
                val d2 = dx * dx + dz * dz
                if (d2 > COLLAR_RADIUS * COLLAR_RADIUS) continue
                if (d2 > 1 && random.nextFloat() >= COLLAR_RIM_CHANCE) continue
                val top = mudTop(level, pos, x + dx, z + dz, groundY + 1) ?: continue
                for (y in top + 1..groundY + 1) {
                    if (!isOpen(level.getBlockState(pos.set(x + dx, y, z + dz)))) break
                    level.setBlock(pos, mud, Block.UPDATE_CLIENTS)
                }
            }
        }
    }

    /** Patchy hollows in a ring around the tree, cut down to one block under the water. */
    private fun carveHollows(
        level: WorldGenLevel, random: RandomSource, pos: BlockPos.MutableBlockPos,
        x: Int, z: Int, waterTopY: Int, share: Float,
    ) {
        val water = Blocks.WATER.defaultBlockState()
        for (dx in -HOLLOW_OUTER..HOLLOW_OUTER) {
            for (dz in -HOLLOW_OUTER..HOLLOW_OUTER) {
                val d2 = dx * dx + dz * dz
                if (d2 < HOLLOW_INNER * HOLLOW_INNER || d2 > HOLLOW_OUTER * HOLLOW_OUTER) continue
                if (random.nextFloat() >= HOLLOW_CHANCE * share) continue
                val top = mudTop(level, pos, x + dx, z + dz, waterTopY + 1) ?: continue
                if (top < waterTopY || !level.isEmptyBlock(pos.set(x + dx, top + 1, z + dz))) continue
                for (y in waterTopY..top) {
                    level.setBlock(pos.set(x + dx, y, z + dz), if (y <= waterTopY) water else AIR, Block.UPDATE_CLIENTS)
                }
            }
        }
    }

    /** Fins of wood that taper outward and down from the flood line, like a buttressed base. */
    private fun growFins(
        level: WorldGenLevel, random: RandomSource, pos: BlockPos.MutableBlockPos,
        x: Int, baseY: Int, z: Int, floodY: Int,
    ) {
        val wood = EKBlocks.WOGOR_WOOD.get().defaultBlockState()
        val finTop = maxOf(floodY, baseY + 1)
        for (dir in FIN_DIRECTIONS) {
            if (random.nextFloat() >= FIN_CHANCE) continue
            if (isWood(level.getBlockState(pos.set(x + dir[0], baseY, z + dir[1])))) continue
            val length = 1 + random.nextInt(FIN_MAX_LENGTH)
            for (d in 1..length) {
                val fx = x + dir[0] * d
                val fz = z + dir[1] * d
                val top = baseY - 1 + Math.ceil((finTop - baseY + 1) * (length - d + 1).toDouble() / length).toInt()
                val floor = mudTop(level, pos, fx, fz, top) ?: break
                for (y in floor + 1..top) {
                    if (!isOpen(level.getBlockState(pos.set(fx, y, fz)))) break
                    level.setBlock(pos, wood, Block.UPDATE_CLIENTS)
                }
            }
        }
    }

    /** Knees rise from the roots around the trunk; their tips sit 1–2 blocks above the water. */
    private fun growKnees(
        level: WorldGenLevel, random: RandomSource, pos: BlockPos.MutableBlockPos,
        x: Int, z: Int, waterTopY: Int, share: Float,
    ) {
        if (random.nextFloat() >= KNEE_TREE_CHANCE * share) return
        val wood = EKBlocks.WOGOR_WOOD.get().defaultBlockState()
        val count = KNEE_MIN + random.nextInt(KNEE_MAX - KNEE_MIN + 1)
        @Suppress("UnusedPrivateProperty") // A bounded loop that breaks early; detekt reads the counter as a property.
        for (k in 0 until count) {
            val angle = random.nextDouble() * 2.0 * Math.PI
            val dist = KNEE_MIN_DIST + random.nextDouble() * (KNEE_MAX_DIST - KNEE_MIN_DIST)
            val kx = x + Math.round(Math.cos(angle) * dist).toInt()
            val kz = z + Math.round(Math.sin(angle) * dist).toInt()
            val tip = waterTopY + 1 + random.nextInt(2)
            val floor = mudTop(level, pos, kx, kz, tip - 1) ?: continue
            if (floor < waterTopY - MAX_KNEE_WATER_DEPTH) continue
            var y = floor + 1
            while (y <= tip && isOpen(level.getBlockState(pos.set(kx, y, kz)))) {
                level.setBlock(pos, wood, Block.UPDATE_CLIENTS)
                y++
            }
            if (y <= tip) continue
            if (random.nextFloat() < KNEE_MOSS_CHANCE && level.isEmptyBlock(pos.set(kx, tip + 1, kz))) {
                level.setBlock(pos, EKBlocks.WOHLON_MOSS_CARPET.get().defaultBlockState(), Block.UPDATE_CLIENTS)
            }
            if (random.nextFloat() < KNEE_VINE_CHANCE) vineDome(level, pos, kx, tip, kz, waterTopY)
        }
    }

    /** Vines climbing every side of a knee above the water, as poison ivy domes over knees. */
    private fun vineDome(level: WorldGenLevel, pos: BlockPos.MutableBlockPos, kx: Int, tip: Int, kz: Int, waterTopY: Int) {
        for (dir in HORIZONTAL) {
            val face = VineBlock.getPropertyForFace(dir.opposite)
            for (y in maxOf(tip - 1, waterTopY + 1)..tip) {
                if (!level.isEmptyBlock(pos.set(kx + dir.stepX, y, kz + dir.stepZ))) continue
                level.setBlock(pos, Blocks.VINE.defaultBlockState().setValue(face, true), Block.UPDATE_CLIENTS)
            }
        }
    }

    /** Topmost mud block in the column at or below [fromY], scanning a short bounded range. */
    private fun mudTop(level: WorldGenLevel, pos: BlockPos.MutableBlockPos, x: Int, z: Int, fromY: Int): Int? {
        for (y in fromY downTo fromY - MUD_SCAN_DEPTH) {
            val state = level.getBlockState(pos.set(x, y, z))
            if (isMud(state)) return y
            if (!isOpen(state)) return null
        }
        return null
    }

    private fun isOpen(state: BlockState): Boolean = state.isAir || state.canBeReplaced()

    private fun isMud(state: BlockState): Boolean =
        state.`is`(EKBlocks.WOHLON_MUD.get()) || state.`is`(Blocks.MUD) || state.`is`(EKBlocks.WOHLON_MOSS.get())

    private fun isWood(state: BlockState): Boolean =
        state.`is`(EKBlocks.WOGOR_WOOD.get()) || state.`is`(EKBlocks.WOGOR_LOG.get())

    private fun isUprightWood(state: BlockState): Boolean =
        isWood(state) && state.getValue(BlockStateProperties.AXIS) == Direction.Axis.Y

    private companion object {
        val AIR: BlockState = Blocks.AIR.defaultBlockState()
        val HORIZONTAL = arrayOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
        val FIN_DIRECTIONS = arrayOf(
            intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
            intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1),
        )

        /** The Mother Tree and its mound have their own hand-built base. */
        const val MOTHER_TREE_CLEARANCE_SQ = 200L * 200L
        const val MIN_TRUNK_RUN = 6
        const val MAX_TRUNK_SCAN = 64
        const val MUD_SCAN_DEPTH = 8

        /** Buttresses reach the usual flood level, the same line where trunk lichen starts. */
        const val FLOOD_RISE = 2
        const val COLLAR_RADIUS = 2
        const val COLLAR_RIM_CHANCE = 0.45f
        const val HOLLOW_INNER = 3
        const val HOLLOW_OUTER = 5
        const val HOLLOW_CHANCE = 0.35f
        const val FIN_CHANCE = 0.55f
        const val THICK_TRUNK_SHARE = 0.2f
        const val FIN_MAX_LENGTH = 3

        const val KNEE_TREE_CHANCE = 0.7f
        const val KNEE_MIN = 2
        const val KNEE_MAX = 5
        const val KNEE_MIN_DIST = 2.0
        const val KNEE_MAX_DIST = 5.0
        const val MAX_KNEE_WATER_DEPTH = 3
        const val KNEE_MOSS_CHANCE = 0.5f
        const val KNEE_VINE_CHANCE = 0.15f
    }
}
