package org.shipwrights.enderkinesis.block

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.entity.MoskuoEntity
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKEntities

/**
 * Moskuo eggs and larvae on still water, after vanilla FrogspawnBlock. They hatch into Moskuo unless fish eat them.
 * Fish eat here through [eatOne]; the planned horned frog's tadpoles should feed here too.
 */
class MoskuoLarvaeBlock(properties: Properties) : Block(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(LARVAE, 1).setValue(RETRIES, 0))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(LARVAE, RETRIES)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPE

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean = mayPlaceOn(level, pos.below())

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, movedByPiston: Boolean) {
        if (!oldState.`is`(this)) level.scheduleTick(pos, this, hatchDelay(level.random))
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun updateShape(
        state: BlockState,
        direction: Direction,
        neighborState: BlockState,
        level: LevelAccessor,
        pos: BlockPos,
        neighborPos: BlockPos,
    ): BlockState = if (canSurvive(state, level, pos)) state else Blocks.AIR.defaultBlockState()

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (!canSurvive(state, level, pos)) {
            // Return value ignored: the block may already be gone.
            level.destroyBlock(pos, false)
            return
        }
        val larvae = state.getValue(LARVAE)
        val x = pos.x + HALF
        val y = pos.y + HATCH_HEIGHT
        val z = pos.z + HALF
        val near = level.getEntitiesOfClass(MoskuoEntity::class.java, AABB(x, y, z, x, y, z).inflate(DENSITY_RADIUS)).size
        val count = larvae.coerceAtMost(MAX_MOSKUO_NEAR - near)
        if (count <= 0) {
            val retries = state.getValue(RETRIES)
            if (retries >= MAX_HATCH_RETRIES) {
                level.removeBlock(pos, false)
            } else {
                level.setBlock(pos, state.setValue(RETRIES, retries + 1), Block.UPDATE_CLIENTS)
                level.scheduleTick(pos, this, RETRY_TICKS)
            }
            return
        }
        repeat(count) {
            val moskuo = EKEntities.MOSKUO.get().create(level) ?: return@repeat
            moskuo.moveTo(x, y, z, random.nextFloat() * FULL_TURN, 0f)
            // Return value ignored: a refused spawn just loses that larva.
            level.addFreshEntity(moskuo)
        }
        level.removeBlock(pos, false)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION") // Forced by vanilla 1.20.1.
    override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity) {
        if (entity.type == EntityType.BOAT) level.destroyBlock(pos, false)
    }

    companion object {
        val LARVAE: IntegerProperty = IntegerProperty.create("larvae", 1, MAX_LARVAE)
        val RETRIES: IntegerProperty = IntegerProperty.create("retries", 0, MAX_HATCH_RETRIES)

        const val MAX_LARVAE = 3
        private const val MAX_HATCH_RETRIES = 10
        private const val HATCH_MIN_TICKS = 5000
        private const val HATCH_MAX_TICKS = 7000
        private const val RETRY_TICKS = 1200
        private const val DENSITY_RADIUS = 24.0
        private const val MAX_MOSKUO_NEAR = 5
        private const val SPACING = 6
        private const val AREA_RADIUS = 16
        private const val AREA_RADIUS_Y = 3
        private const val MAX_BLOCKS_IN_AREA = 4
        private const val HATCH_HEIGHT = 0.3
        private const val HALF = 0.5
        private const val FULL_TURN = 360f
        private val SHAPE: VoxelShape = Block.box(0.0, 0.0, 0.0, 16.0, 1.5, 16.0)

        private fun hatchDelay(random: RandomSource): Int = random.nextIntBetweenInclusive(HATCH_MIN_TICKS, HATCH_MAX_TICKS)

        private fun mayPlaceOn(level: BlockGetter, below: BlockPos): Boolean =
            level.getFluidState(below).type == Fluids.WATER && level.getFluidState(below.above()).type == Fluids.EMPTY

        /** A water source with no current and an open top in the Wohlon biome; [above] is scratch. */
        fun isLayingWater(level: ServerLevel, water: BlockPos, above: BlockPos.MutableBlockPos): Boolean {
            val fluid = level.getFluidState(water)
            if (!fluid.`is`(FluidTags.WATER) || !fluid.isSource) return false
            above.setWithOffset(water, 0, 1, 0)
            val top = level.getBlockState(above)
            if (!top.isAir && !top.`is`(EKBlocks.MOSKUO_LARVAE.get())) return false
            return fluid.getFlow(level, water).lengthSqr() == 0.0 && level.getBiome(water).`is`(Wohlonnogondonia.BIOME_KEY)
        }

        /**
         * Lays [count] larvae on the surface above [water]: adds to a block that is already there, or places a new one
         * when no other larvae lie within [SPACING] and the area holds fewer than [MAX_BLOCKS_IN_AREA].
         */
        fun lay(level: ServerLevel, water: BlockPos, count: Int): Boolean {
            val at = water.above()
            val block = EKBlocks.MOSKUO_LARVAE.get()
            val state = level.getBlockState(at)
            if (state.`is`(block)) {
                val larvae = (state.getValue(LARVAE) + count).coerceAtMost(MAX_LARVAE)
                return level.setBlock(at, state.setValue(LARVAE, larvae), Block.UPDATE_CLIENTS)
            }
            if (!state.isAir || !areaAllows(level, at)) return false
            return level.setBlock(at, block.defaultBlockState().setValue(LARVAE, count.coerceIn(1, MAX_LARVAE)), Block.UPDATE_ALL)
        }

        private fun areaAllows(level: ServerLevel, at: BlockPos): Boolean {
            var found = 0
            val spacingSq = SPACING * SPACING
            return scan(level, at, AREA_RADIUS, AREA_RADIUS_Y, BlockPos.MutableBlockPos()) { pos ->
                found++
                pos.distSqr(at) < spacingSq || found >= MAX_BLOCKS_IN_AREA
            } == null
        }

        /** Nearest larvae block within [radius] (and ±[radiusY]) of [from], or null; [cursor] is scratch. */
        fun findNear(level: ServerLevel, from: BlockPos, radius: Int, radiusY: Int, cursor: BlockPos.MutableBlockPos): BlockPos? {
            var best: BlockPos? = null
            var bestDistance = Double.MAX_VALUE
            // Return value ignored: the visitor never stops the scan; it only records the nearest hit.
            scan(level, from, radius, radiusY, cursor) { pos ->
                val distance = pos.distSqr(from)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = pos.immutable()
                }
                false
            }
            return best
        }

        /**
         * Visits each larvae block in the box until [visit] returns true, and returns that position.
         * Sections whose palette has no larvae are skipped, so a scan over open water costs a few palette checks.
         */
        private inline fun scan(
            level: ServerLevel,
            center: BlockPos,
            radius: Int,
            radiusY: Int,
            cursor: BlockPos.MutableBlockPos,
            visit: (BlockPos.MutableBlockPos) -> Boolean,
        ): BlockPos? {
            val block = EKBlocks.MOSKUO_LARVAE.get()
            val minY = maxOf(center.y - radiusY, level.minBuildHeight)
            val maxY = minOf(center.y + radiusY, level.maxBuildHeight - 1)
            for (sx in SectionPos.blockToSectionCoord(center.x - radius)..SectionPos.blockToSectionCoord(center.x + radius)) {
                for (sz in SectionPos.blockToSectionCoord(center.z - radius)..SectionPos.blockToSectionCoord(center.z + radius)) {
                    val chunk = level.chunkSource.getChunkNow(sx, sz) ?: continue
                    for (sy in SectionPos.blockToSectionCoord(minY)..SectionPos.blockToSectionCoord(maxY)) {
                        val section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy))
                        if (section.hasOnlyAir() || !section.maybeHas { it.`is`(block) }) continue
                        val x0 = maxOf(center.x - radius, SectionPos.sectionToBlockCoord(sx))
                        val x1 = minOf(center.x + radius, SectionPos.sectionToBlockCoord(sx) + SECTION_MAX)
                        val y0 = maxOf(minY, SectionPos.sectionToBlockCoord(sy))
                        val y1 = minOf(maxY, SectionPos.sectionToBlockCoord(sy) + SECTION_MAX)
                        val z0 = maxOf(center.z - radius, SectionPos.sectionToBlockCoord(sz))
                        val z1 = minOf(center.z + radius, SectionPos.sectionToBlockCoord(sz) + SECTION_MAX)
                        for (y in y0..y1) for (x in x0..x1) for (z in z0..z1) {
                            cursor.set(x, y, z)
                            if (section.getBlockState(x and SECTION_MAX, y and SECTION_MAX, z and SECTION_MAX).`is`(block) &&
                                visit(cursor)
                            ) {
                                return cursor.immutable()
                            }
                        }
                    }
                }
            }
            return null
        }

        /** Eats one larva at [pos]; removes the block at zero. False when no larvae are there. */
        fun eatOne(level: ServerLevel, pos: BlockPos): Boolean {
            val state = level.getBlockState(pos)
            if (!state.`is`(EKBlocks.MOSKUO_LARVAE.get())) return false
            val larvae = state.getValue(LARVAE)
            if (larvae <= 1) return level.removeBlock(pos, false)
            return level.setBlock(pos, state.setValue(LARVAE, larvae - 1), Block.UPDATE_CLIENTS)
        }

        private const val SECTION_MAX = 15
    }
}
