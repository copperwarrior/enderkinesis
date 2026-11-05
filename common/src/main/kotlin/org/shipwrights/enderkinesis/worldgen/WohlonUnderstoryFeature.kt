package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.MultifaceBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration
import org.shipwrights.enderkinesis.block.WogorBudBlock
import org.shipwrights.enderkinesis.block.WogorVineNoise
import org.shipwrights.enderkinesis.block.WohlonLichenBlock
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Swamp understory for one chunk, after the trees. Follows wetland zonation:
 * sphagnum mats at hollow edges and tree bases, moss on logs and roots above the
 * high-water line, sedge grass at sunny hollow edges, ferns on raised shaded hummocks,
 * nothing in the flooded hollows. Trunk lichen stops sharply at the high-water line.
 */
class WohlonUnderstoryFeature(codec: Codec<NoneFeatureConfiguration>) : Feature<NoneFeatureConfiguration>(codec) {

    override fun place(context: FeaturePlaceContext<NoneFeatureConfiguration>): Boolean {
        val level = context.level()
        val random = context.random()
        val x0 = context.origin().x and 15.inv()
        val z0 = context.origin().z and 15.inv()
        // WorldGenRegion reports the vanilla default of 63; the generator holds the real sea level.
        val waterTopY = context.chunkGenerator().seaLevel - 1
        val wet = ChunkWet(wetGrid(level, x0, z0, waterTopY))
        val pos = BlockPos.MutableBlockPos()
        val column = Column()
        var placed = false
        for (lx in 0..15) {
            for (lz in 0..15) {
                val x = x0 + lx
                val z = z0 + lz
                // Neighbour chunks have no primed heightmaps yet, so only this chunk's columns are read.
                val groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
                val rise = groundY - waterTopY
                if (rise < 1) {
                    placed = decorateShore(level, random, pos, wet, x, z, groundY, waterTopY) || placed
                    continue
                }
                if (!level.isEmptyBlock(pos.set(x, groundY + 1, z))) continue
                column.x = x
                column.groundY = groundY
                column.z = z
                column.rise = rise
                column.waterDistance = WohlonWetlandZones.waterDistance(wet.at(lx, lz))
                column.shaded = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) > groundY + 1
                placed = decorate(level, random, pos, column) || placed
                placed = growTrunkLichen(level, random, pos, column, waterTopY) || placed
                if (column.shaded && random.nextFloat() < VINE_ROPE_CHANCE) {
                    placed = hangVineRope(level, random, pos, column) || placed
                }
            }
        }
        return placed
    }

    /** This chunk's standing-water grid, seen from one column. */
    private class ChunkWet(private val wet: BooleanArray) : WohlonWetlandZones.WetGrid {
        private var lx = 0
        private var lz = 0

        fun at(lx: Int, lz: Int): ChunkWet {
            this.lx = lx
            this.lz = lz
            return this
        }

        override fun wet(dx: Int, dz: Int): Boolean = wet[(lx + dx + WATER_REACH) * GRID + (lz + dz + WATER_REACH)]
    }

    /** Reused for every column of one chunk. */
    private class Column {
        var x = 0
        var groundY = 0
        var z = 0
        var rise = 0
        var waterDistance = 0
        var shaded = false
    }

    private fun decorate(level: WorldGenLevel, random: RandomSource, pos: BlockPos.MutableBlockPos, c: Column): Boolean {
        val ground = level.getBlockState(pos.set(c.x, c.groundY, c.z))
        val mossPatch = patch(WogorVineNoise.rawNoise(c.x, MOSS_NOISE_Y, c.z))
        if (isWogorWood(ground)) {
            // Wohlon root has no axis; only logs lying on their side count as fallen.
            val fallen = ground.getOptionalValue(BlockStateProperties.AXIS).map { it != Direction.Axis.Y }.orElse(false)
            // Cup fungi decompose rotting wood.
            if (fallen && random.nextFloat() < CUP_ON_LOG_CHANCE) {
                return place(level, pos.set(c.x, c.groundY + 1, c.z), EKBlocks.WOHLON_CUP_MUSHROOM.get().defaultBlockState())
            }
            if (random.nextFloat() >= LOG_CARPET_CHANCE * mossPatch) return false
            // Nurse logs: seedlings take on mossy logs. Static bud, no growth tick, so it stays a seedling.
            val at = pos.set(c.x, c.groundY + 1, c.z)
            val top = if (fallen && random.nextFloat() < NURSE_BUD_SHARE) {
                WogorBudBlock.newBud(level, at)
            } else {
                EKBlocks.WOHLON_MOSS_CARPET.get().defaultBlockState()
            }
            return place(level, at, top)
        }
        if (!ground.`is`(EKBlocks.WOHLON_MUD.get())) return false

        val hollowEdge = c.waterDistance <= 1 && c.rise <= HOLLOW_EDGE_MAX_RISE
        if ((hollowEdge || nearTreeBase(level, pos, c)) && random.nextFloat() < MAT_CHANCE * mossPatch) {
            level.setBlock(pos.set(c.x, c.groundY, c.z), EKBlocks.WOHLON_MOSS.get().defaultBlockState(), Block.UPDATE_CLIENTS)
            if (random.nextFloat() < MAT_CARPET_CHANCE) {
                place(level, pos.set(c.x, c.groundY + 1, c.z), EKBlocks.WOHLON_MOSS_CARPET.get().defaultBlockState())
            }
            return true
        }

        val grassPatch = patch(WogorVineNoise.rawNoise(c.x, GRASS_NOISE_Y, c.z))
        val sedgeZone = c.waterDistance <= SEDGE_WATER_REACH && c.rise <= HOLLOW_EDGE_MAX_RISE
        val sun = if (c.shaded) GRASS_SHADE_FACTOR else 1.0f
        if (sedgeZone && random.nextFloat() < SEDGE_CHANCE * grassPatch * sun &&
            place(level, pos.set(c.x, c.groundY + 1, c.z), EKBlocks.WOHLON_GRASS.get().defaultBlockState())
        ) {
            return true
        }

        if (placeSmallFungus(level, random, pos, c)) return true

        val fernPatch = patch(WogorVineNoise.rawNoise(c.x, FERN_NOISE_Y, c.z))
        val light = if (c.shaded) 1.0f else SUN_FACTOR
        val hummock = c.rise <= HUMMOCK_MAX_RISE && c.waterDistance <= HUMMOCK_WATER_REACH
        val chance = (if (hummock) HUMMOCK_FERN_CHANCE else UPLAND_FERN_CHANCE) * fernPatch * light
        if (random.nextFloat() >= chance) return placeLeafLitter(level, random, pos, c)
        pos.set(c.x, c.groundY + 1, c.z)
        val tall = hummock && c.shaded && random.nextFloat() < TALL_FERN_SHARE
        if (tall && level.isEmptyBlock(pos.above())) {
            val state = EKBlocks.WOHLON_TALL_FERN.get().defaultBlockState()
            if (!state.canSurvive(level, pos)) return false
            DoublePlantBlock.placeAt(level, state, pos, Block.UPDATE_CLIENTS)
            return true
        }
        return place(level, pos, EKBlocks.WOHLON_FERN.get().defaultBlockState())
    }

    /**
     * Cup fungi on mud beside fallen logs and stumps (wood decomposers). Lumpy mushrooms on shaded,
     * raised ground near a tree and away from the water's edge (root partners; waterlogging starves them).
     */
    private fun placeSmallFungus(level: WorldGenLevel, random: RandomSource, pos: BlockPos.MutableBlockPos, c: Column): Boolean {
        val shade = if (c.shaded) 1.0f else FUNGUS_SUN_FACTOR
        if (besideDeadWood(level, pos, c) && random.nextFloat() < CUP_BESIDE_WOOD_CHANCE * shade) {
            return place(level, pos.set(c.x, c.groundY + 1, c.z), EKBlocks.WOHLON_CUP_MUSHROOM.get().defaultBlockState())
        }
        if (c.waterDistance < LUMPY_WATER_CLEARANCE || !c.shaded) return false
        val patch = patch(WogorVineNoise.rawNoise(c.x, FUNGUS_NOISE_Y, c.z))
        if (patch <= 0f || random.nextFloat() >= LUMPY_CHANCE * patch || !nearTreeBase(level, pos, c)) return false
        return place(level, pos.set(c.x, c.groundY + 1, c.z), EKBlocks.WOHLON_LUMPY_MUSHROOM.get().defaultBlockState())
    }

    private fun besideDeadWood(level: WorldGenLevel, pos: BlockPos.MutableBlockPos, c: Column): Boolean {
        for (dir in HORIZONTAL) {
            val state = level.getBlockState(pos.set(c.x + dir.stepX, c.groundY + 1, c.z + dir.stepZ))
            val fallen = isWogorWood(state) &&
                state.getOptionalValue(BlockStateProperties.AXIS).map { it != Direction.Axis.Y }.orElse(false)
            if (fallen || state.`is`(EKBlocks.WOGOR_WOOD_SLAB.get()) || state.`is`(EKBlocks.WOGOR_WOOD_STAIRS.get())) return true
            // A stump: upright bark with a splintered top, not a living trunk.
            if (state.`is`(EKBlocks.WOGOR_WOOD.get()) && !level.getBlockState(pos.set(c.x + dir.stepX, c.groundY + 2, c.z + dir.stepZ))
                    .`is`(net.minecraft.tags.BlockTags.LEAVES)
            ) {
                return true
            }
        }
        return false
    }

    private fun place(level: WorldGenLevel, pos: BlockPos, state: BlockState): Boolean {
        if (!state.canSurvive(level, pos)) return false
        return level.setBlock(pos, state, Block.UPDATE_CLIENTS)
    }

    /**
     * Water's edge and open water. Emergent reeds in a band along the shore at depth 0–1;
     * duckweed filling enclosed still pools and thinning out on open water; lily pads on
     * open water 1–3 deep where the sky is clear.
     */
    private fun decorateShore(
        level: WorldGenLevel, random: RandomSource, pos: BlockPos.MutableBlockPos,
        wet: ChunkWet, x: Int, z: Int, groundY: Int, waterTopY: Int,
    ): Boolean {
        if (groundY != waterTopY) return false
        val grid = wet.at(x and 15, z and 15)
        val surface = level.getBlockState(pos.set(x, groundY, z))
        if (!surface.`is`(Blocks.WATER)) {
            // Mud flush with the water line: the landward edge of the reed band.
            if (!WohlonWetlandZones.reedOnLand(WohlonWetlandZones.waterDistance(grid))) return false
            if (random.nextFloat() >= WohlonWetlandZones.reedChance(x, z)) return false
            return placeReed(level, pos.set(x, groundY + 1, z))
        }
        if (!level.isEmptyBlock(pos.set(x, waterTopY + 1, z))) return false
        val depth = waterDepth(level, pos, x, z, waterTopY)
        val landDistance = WohlonWetlandZones.landDistance(grid)
        if (WohlonWetlandZones.reedInWater(depth, landDistance) && random.nextFloat() < WohlonWetlandZones.reedChance(x, z)) {
            return placeReed(level, pos.set(x, waterTopY, z))
        }
        val enclosure = WohlonWetlandZones.enclosure(grid)
        if (random.nextFloat() < WohlonWetlandZones.duckweedChance(enclosure, landDistance)) {
            return level.setBlock(
                pos.set(x, waterTopY + 1, z), EKBlocks.WOHLON_DUCKWEED.get().defaultBlockState(), Block.UPDATE_CLIENTS,
            )
        }
        if (!WohlonWetlandZones.lilyWater(depth, enclosure)) return false
        if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) > waterTopY + 1) return false
        if (random.nextFloat() >= WohlonWetlandZones.lilyChance(x, z)) return false
        return level.setBlock(
            pos.set(x, waterTopY + 1, z), EKBlocks.WOHLON_LILY_PAD.get().defaultBlockState(), Block.UPDATE_CLIENTS,
        )
    }

    /** Reed rooted at [pos]; the lower half takes on water if it stands in it. */
    private fun placeReed(level: WorldGenLevel, pos: BlockPos.MutableBlockPos): Boolean {
        val reed = EKBlocks.WOHLON_REED.get().defaultBlockState()
        if (!reed.canSurvive(level, pos) || !level.isEmptyBlock(pos.above())) return false
        DoublePlantBlock.placeAt(level, reed, pos, Block.UPDATE_CLIENTS)
        return true
    }

    private fun waterDepth(level: WorldGenLevel, pos: BlockPos.MutableBlockPos, x: Int, z: Int, waterTopY: Int): Int {
        var depth = 0
        while (depth <= WohlonWetlandZones.LILY_MAX_DEPTH && level.getBlockState(pos.set(x, waterTopY - depth, z)).`is`(Blocks.WATER)) {
            depth++
        }
        return depth
    }

    /** Leaf litter gathers on shaded ground where nothing else grows. */
    private fun placeLeafLitter(level: WorldGenLevel, random: RandomSource, pos: BlockPos.MutableBlockPos, c: Column): Boolean {
        if (!c.shaded) return false
        if (random.nextFloat() >= WohlonWetlandZones.litterChance(c.x, c.z, shaded = true)) return false
        return place(level, pos.set(c.x, c.groundY + 1, c.z), EKBlocks.WOHLON_LEAF_LITTER.get().defaultBlockState())
    }

    /** Vine rope hanging from the side of the first branch over a shaded column. */
    private fun hangVineRope(level: WorldGenLevel, random: RandomSource, pos: BlockPos.MutableBlockPos, c: Column): Boolean {
        var branchY = -1
        for (y in c.groundY + ROPE_MIN_CLEARANCE..c.groundY + ROPE_SCAN_HEIGHT) {
            val state = level.getBlockState(pos.set(c.x, y, c.z))
            if (state.isAir) continue
            if (isWogorWood(state)) branchY = y
            break
        }
        if (branchY < 0) return false
        val dir = HORIZONTAL[random.nextInt(HORIZONTAL.size)]
        val vx = c.x + dir.stepX
        val vz = c.z + dir.stepZ
        val vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(dir.opposite), true)
        val length = ROPE_MIN_LENGTH + random.nextInt(ROPE_MAX_LENGTH - ROPE_MIN_LENGTH + 1)
        var hung = 0
        for (y in branchY downTo maxOf(branchY - length + 1, c.groundY + 2)) {
            if (!level.isEmptyBlock(pos.set(vx, y, vz))) break
            // Only the top block clings to the branch; the rest hang from the vine above.
            if (y != branchY && hung == 0) break
            level.setBlock(pos, vine, Block.UPDATE_CLIENTS)
            hung++
        }
        return hung > 0
    }

    /** Vertical lichen runs on the trunk faces around this air column, starting at the lichen line. */
    private fun growTrunkLichen(
        level: WorldGenLevel, random: RandomSource, pos: BlockPos.MutableBlockPos, c: Column, waterTopY: Int,
    ): Boolean {
        val lichenPatch = patch(WogorVineNoise.rawNoise(c.x, LICHEN_NOISE_Y, c.z))
        if (lichenPatch <= 0f) return false
        val startY = maxOf(waterTopY + LICHEN_LINE_RISE, c.groundY + 1)
        val lichen = EKBlocks.WOHLON_LICHEN.get() as WohlonLichenBlock
        var placed = false
        for (face in HORIZONTAL) {
            if (random.nextFloat() >= LICHEN_CHANCE * lichenPatch) continue
            val run = random.nextIntBetweenInclusive(LICHEN_MIN_RUN, LICHEN_MAX_RUN)
            val woodTop = woodTopY(level, pos, c.x + face.stepX, c.z + face.stepZ, startY, run)
            if (woodTop < startY) continue
            val onRootOrBranch = woodTop < startY + run - 1
            if (onRootOrBranch && random.nextFloat() >= LICHEN_WOOD_TOP_CHANCE) continue
            for (y in startY..woodTop) {
                // Bottom-up, so only the run's lowest block sees no lichen below and becomes the tip.
                val state = lichenFace(level, lichen, pos.set(c.x, y, c.z), face) ?: break
                placed = level.setBlock(pos, state, Block.UPDATE_CLIENTS) || placed
                if (!onRootOrBranch || y != woodTop) continue
                // The wood top takes lichen too, so the wall tip hangs from a carpet, not bare wood.
                val top = lichenFace(level, lichen, pos.set(c.x + face.stepX, y + 1, c.z + face.stepZ), Direction.DOWN)
                if (top != null) placed = level.setBlock(pos, top, Block.UPDATE_CLIENTS) || placed
            }
        }
        return placed
    }

    /** Highest wood Y in the first [run] blocks of the wall column from [startY], or startY - 1 when none. */
    private fun woodTopY(level: WorldGenLevel, pos: BlockPos.MutableBlockPos, x: Int, z: Int, startY: Int, run: Int): Int {
        var y = startY
        while (y < startY + run && isWogorWood(level.getBlockState(pos.set(x, y, z)))) y++
        return y - 1
    }

        /** [lichen] at [pos] with [face] added, or null when something other than air or lichen fills [pos]. */
    private fun lichenFace(level: WorldGenLevel, lichen: WohlonLichenBlock, pos: BlockPos, face: Direction): BlockState? {
        val existing = level.getBlockState(pos)
        val base = when {
            existing.`is`(lichen) -> existing
            existing.isAir -> lichen.defaultBlockState()
            else -> return null
        }
        return lichen.withTip(base.setValue(MultifaceBlock.getFaceProperty(face), true), level, pos)
    }

    /** Wood one block above the ground within [BASE_RADIUS]: a trunk or root flare stands next to this cell. */
    private fun nearTreeBase(level: WorldGenLevel, pos: BlockPos.MutableBlockPos, c: Column): Boolean {
        for (dx in -BASE_RADIUS..BASE_RADIUS) {
            for (dz in -BASE_RADIUS..BASE_RADIUS) {
                if (isWogorWood(level.getBlockState(pos.set(c.x + dx, c.groundY + 1, c.z + dz)))) return true
            }
        }
        return false
    }

    private fun isWogorWood(state: BlockState): Boolean =
        state.`is`(EKBlocks.WOGOR_WOOD.get()) || state.`is`(EKBlocks.WOGOR_LOG.get()) ||
            state.`is`(EKBlocks.WOHLON_ROOT.get())

    /** Standing-water columns for this chunk plus a [WATER_REACH] margin; block reads are safe in neighbours. */
    private fun wetGrid(level: WorldGenLevel, x0: Int, z0: Int, waterTopY: Int): BooleanArray {
        val grid = BooleanArray(GRID * GRID)
        val pos = BlockPos.MutableBlockPos()
        for (gx in 0 until GRID) {
            for (gz in 0 until GRID) {
                pos.set(x0 + gx - WATER_REACH, waterTopY, z0 + gz - WATER_REACH)
                grid[gx * GRID + gz] = level.getFluidState(pos).`is`(FluidTags.WATER)
            }
        }
        return grid
    }

    private fun patch(noise: Double): Float = WohlonWetlandZones.patch(noise)

    private companion object {
        const val WATER_REACH = WohlonWetlandZones.WATER_REACH
        const val GRID = 16 + 2 * WATER_REACH
        const val BASE_RADIUS = 2
        const val MOSS_NOISE_Y = 4096
        const val FERN_NOISE_Y = 8192
        const val GRASS_NOISE_Y = 12288
        const val LICHEN_NOISE_Y = 16384
        val HORIZONTAL = arrayOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)

        const val HOLLOW_EDGE_MAX_RISE = 2
        const val HUMMOCK_MAX_RISE = 5
        const val HUMMOCK_WATER_REACH = 3

        const val LOG_CARPET_CHANCE = 0.45f
        const val MAT_CHANCE = 0.7f
        const val MAT_CARPET_CHANCE = 0.3f
        const val HUMMOCK_FERN_CHANCE = 0.3f
        const val UPLAND_FERN_CHANCE = 0.05f
        const val SUN_FACTOR = 0.4f
        const val TALL_FERN_SHARE = 0.4f

        const val SEDGE_WATER_REACH = 2
        const val SEDGE_CHANCE = 0.45f
        const val GRASS_SHADE_FACTOR = 0.35f

        /** Blocks above the water surface where standing floods stop lichen growth. */
        const val LICHEN_LINE_RISE = 2
        const val LICHEN_CHANCE = 0.5f
        const val LICHEN_MIN_RUN = 2
        const val LICHEN_MAX_RUN = 5
        const val LICHEN_WOOD_TOP_CHANCE = 0.25f

        const val NURSE_BUD_SHARE = 0.25f


        const val FUNGUS_NOISE_Y = 32768
        const val FUNGUS_SUN_FACTOR = 0.4f
        const val CUP_ON_LOG_CHANCE = 0.1f
        const val CUP_BESIDE_WOOD_CHANCE = 0.3f
        const val LUMPY_CHANCE = 0.12f
        /** Columns this close to standing water stay too waterlogged for lumpy mushrooms. */
        const val LUMPY_WATER_CLEARANCE = 2

        const val VINE_ROPE_CHANCE = 0.02f
        const val ROPE_MIN_CLEARANCE = 3
        const val ROPE_SCAN_HEIGHT = 20
        const val ROPE_MIN_LENGTH = 3
        const val ROPE_MAX_LENGTH = 8
    }
}
