package org.shipwrights.enderkinesis.client

import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.sounds.SoundEvent
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BushBlock
import net.minecraft.world.level.block.GrowingPlantBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.MultifaceBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import java.util.IdentityHashMap
import kotlin.math.abs

/**
 * Finds the object round an aimed block, from "General voxel object detection" (reports/). It grows from the seed in
 * order of the highest edge cost on the path (a seeded watershed), so the blocks that join at or under each cost
 * level form that level's candidate, and records the volume at each level. The default candidate is the most stable
 * level: the one whose volume changes least round it. The work runs in time slices across the use.
 */
internal class ObjectFinder(private val level: ClientLevel, val seed: BlockPos) {

    private val side = 2 * BOX + 1
    private val groundHeight = IntArray(side * side)
    private var groundReady = false

    private val buckets = Array(LEVELS) { LongArrayList() }
    private var bucket = 0
    private val info = Long2LongOpenHashMap().apply { defaultReturnValue(NONE) }
    private val accepted = LongOpenHashSet()
    private var pops = 0
    private var cappedAt = LEVELS

    /** Solid blocks in the order they joined; keys never fall, so each level's candidate is a prefix. */
    val solids = LongArrayList()
    private val solidKeys = IntArrayList()
    private val volumeAt = IntArray(LEVELS)
    private val groundAt = IntArray(LEVELS)
    private var boxTouch = LEVELS

    var done = false
        private set

    init {
        val state = level.getBlockState(seed)
        info.put(seed.asLong(), pack(0, 0, 0, Block.getId(state), false))
        buckets[0].add(seed.asLong())
    }

    /** Works until [deadline] (System.nanoTime); bounded by the box and [NODE_CAP]. */
    fun step(deadline: Long) {
        if (!groundReady) {
            readGround()
            groundReady = true
            return
        }
        val pos = BlockPos.MutableBlockPos()
        val near = BlockPos.MutableBlockPos()
        while (!done) {
            while (bucket < LEVELS && buckets[bucket].isEmpty) bucket++
            if (bucket == LEVELS) {
                done = true
                return
            }
            if (pops >= NODE_CAP) {
                cappedAt = bucket
                done = true
                return
            }
            val list = buckets[bucket]
            val key = list.removeLong(list.size - 1)
            if (!accepted.add(key)) continue
            pops++
            visit(key, pos.set(key), near)
            if (pops % CHECK_EVERY == 0 && System.nanoTime() > deadline) return
        }
    }

    private fun visit(key: Long, pos: BlockPos.MutableBlockPos, near: BlockPos.MutableBlockPos) {
        val packed = info.get(key)
        val sum = sumOf(packed)
        val state = level.getBlockState(pos)
        val air = isAir(state)
        val band: Boolean
        val fromState: BlockState
        if (air) {
            band = bandOf(packed)
            fromState = Block.stateById(solidOf(packed))
        } else {
            band = inBand(pos, state)
            fromState = state
            solids.add(key)
            solidKeys.add(bucket)
            volumeAt[bucket]++
            if (band) groundAt[bucket]++
            if (onBoxFace(pos)) boxTouch = minOf(boxTouch, bucket)
        }
        val run = if (air) airOf(packed) else 0
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            if (dx == 0 && dy == 0 && dz == 0) continue
            near.setWithOffset(pos, dx, dy, dz)
            if (!inBox(near) || level.isOutsideBuildHeight(near)) continue
            val nearKey = near.asLong()
            if (accepted.contains(nearKey)) continue
            val nearState = level.getBlockState(near)
            if (nearState.block is LiquidBlock) continue
            val nearAir = isAir(nearState)
            val nearRun = if (nearAir) run + 1 else 0
            if (nearRun > MAX_AIR_RUN) continue
            val nearBand = !nearAir && inBand(near, nearState)
            val edge = if (nearAir) {
                if (nearRun == 1) FIRST_AIR else SECOND_AIR
            } else {
                cost(fromState, nearState, band, nearBand)
            }
            val nearLevel = maxOf(bucket, edge)
            val nearSum = sum + edge
            if (nearSum > SUM_CAP) continue
            val old = info.get(nearKey)
            if (old != NONE && (levelOf(old) < nearLevel || levelOf(old) == nearLevel && sumOf(old) <= nearSum)) continue
            val solid = if (nearAir) solidOf(packed).takeIf { air } ?: Block.getId(state) else Block.getId(nearState)
            info.put(nearKey, pack(nearLevel, nearRun, nearSum, solid, if (nearAir) band else nearBand))
            buckets[nearLevel].add(nearKey)
        }
    }

    /**
     * The candidate levels, most stable first, or empty when every level is unbounded or mostly ground. A candidate
     * is the region at a level where blocks joined; its stability is how many levels pass before the next join, the
     * box face or the node cap (a long-lived region, as in MSER and UCM). Ties go to the larger region.
     */
    fun candidates(): List<Int> {
        val limit = minOf(boxTouch, cappedAt)
        val joins = (0 until limit).filter { volumeAt[it] > 0 }
        var volume = 0
        var ground = 0
        val found = ArrayList<IntArray>()
        for ((i, t) in joins.withIndex()) {
            volume += volumeAt[t]
            ground += groundAt[t]
            if (ground * 2 > volume) continue
            val next = if (i + 1 < joins.size) joins[i + 1] else limit
            found.add(intArrayOf(t, next - t, volume))
        }
        val sized = found.filter { it[2] >= MIN_OBJECT }.ifEmpty { found }
        return sized.sortedWith(compareByDescending<IntArray> { it[1] }.thenByDescending { it[2] }).map { it[0] }
    }

    /** The solid blocks of the candidate at [level]: a prefix of [solids]. */
    fun blocksAt(level: Int): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        for (i in 0 until solids.size) {
            if (solidKeys.getInt(i) > level) break
            out.add(BlockPos.of(solids.getLong(i)))
        }
        return out
    }

    private fun onBoxFace(pos: BlockPos) =
        abs(pos.x - seed.x) == BOX || abs(pos.y - seed.y) == BOX || abs(pos.z - seed.z) == BOX

    private fun inBox(pos: BlockPos) =
        abs(pos.x - seed.x) <= BOX && abs(pos.y - seed.y) <= BOX && abs(pos.z - seed.z) <= BOX

    private fun inBand(pos: BlockPos, state: BlockState): Boolean {
        if (features(state).kind != GROUND) return false
        val i = (pos.x - seed.x + BOX) * side + (pos.z - seed.z + BOX)
        return abs(pos.y - groundHeight[i]) <= BAND
    }

    /**
     * The top surface of each column in the box, with things narrower than the windows (roofs, crowns) opened away:
     * Zhang's progressive morphological filter over the client's MOTION_BLOCKING heightmap.
     */
    private fun readGround() {
        val surface = IntArray(side * side)
        for (i in 0 until side) for (j in 0 until side) {
            val x = seed.x - BOX + i
            val z = seed.z - BOX + j
            surface[i * side + j] = level.getChunk(x shr 4, z shr 4).getHeight(Heightmap.Types.MOTION_BLOCKING, x and 15, z and 15)
        }
        val scratch = IntArray(side * side)
        for (w in WINDOWS.indices) {
            val opened = surface.copyOf()
            filter(opened, scratch, WINDOWS[w] / 2, min = true)
            filter(opened, scratch, WINDOWS[w] / 2, min = false)
            for (i in surface.indices) if (surface[i] - opened[i] > HEIGHT_STEPS[w]) surface[i] = opened[i]
        }
        surface.copyInto(groundHeight)
    }

    /** A square min or max filter of radius [r], as a row pass then a column pass. */
    private fun filter(grid: IntArray, scratch: IntArray, r: Int, min: Boolean) {
        for (pass in 0..1) {
            for (i in 0 until side) for (j in 0 until side) {
                var best = grid[i * side + j]
                for (d in -r..r) {
                    val a = if (pass == 0) i + d else i
                    val b = if (pass == 0) j else j + d
                    if (a !in 0 until side || b !in 0 until side) continue
                    val value = grid[a * side + b]
                    best = if (min) minOf(best, value) else maxOf(best, value)
                }
                scratch[i * side + j] = best
            }
            scratch.copyInto(grid)
        }
    }

    private class Features(
        val block: Block,
        val sound: SoundEvent,
        val mapColor: Int,
        val tools: Int,
        val stem: String,
        val full: Boolean,
        val kind: Int,
    )

    companion object {
        // Start values from the report; tune them in play.
        const val BOX = 32
        private const val LEVELS = 32
        private const val NODE_CAP = 60_000
        private const val SUM_CAP = 400
        private const val CHECK_EVERY = 256
        private const val MAX_AIR_RUN = 2
        private const val FIRST_AIR = 4
        private const val SECOND_AIR = 8
        private const val BAND = 1
        private const val MIN_OBJECT = 8
        private val WINDOWS = intArrayOf(3, 5, 9, 17)
        private val HEIGHT_STEPS = intArrayOf(1, 2, 4, 8)

        private const val SAME_FAMILY = 1
        private const val SAME_LOOK = 2
        private const val SAME_SOUND_OR_TOOL = 4
        private const val UNRELATED = 8
        private const val SHAPE_CHANGE = 2
        private const val KIND_CHANGE = 8
        private const val GROUND_ENTRY = 10

        private const val BUILT = 0
        private const val GROWTH = 1
        private const val GROUND = 2

        // A log is a trunk in a tree and a post in a house, so it pays no change of kind either way.
        private const val EITHER = 3

        private const val NONE = -1L

        private val STEM_SUFFIXES = listOf(
            "_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_trapdoor", "_door", "_planks", "_log", "_wood",
            "_button", "_pressure_plate", "_wall_hanging_sign", "_hanging_sign", "_wall_sign", "_sign", "_leaves",
            "_sapling", "_bricks", "_brick", "_tiles", "_pillar",
        )
        private val TOOL_TAGS = listOf(
            BlockTags.MINEABLE_WITH_AXE, BlockTags.MINEABLE_WITH_PICKAXE, BlockTags.MINEABLE_WITH_SHOVEL,
            BlockTags.MINEABLE_WITH_HOE,
        )

        // Render thread only.
        private val cache = IdentityHashMap<BlockState, Features>()

        private fun pack(level: Int, air: Int, sum: Int, solid: Int, band: Boolean): Long =
            level.toLong() or (air.toLong() shl 5) or (sum.toLong() shl 7) or ((if (band) 1L else 0L) shl 19) or
                (solid.toLong() shl 32)

        private fun levelOf(packed: Long) = (packed and 0x1F).toInt()
        private fun airOf(packed: Long) = ((packed shr 5) and 0x3).toInt()
        private fun sumOf(packed: Long) = ((packed shr 7) and 0xFFF).toInt()
        private fun bandOf(packed: Long) = (packed shr 19) and 1L == 1L
        private fun solidOf(packed: Long) = (packed ushr 32).toInt()

        private fun isAir(state: BlockState) = state.isAir || state.renderShape == RenderShape.INVISIBLE

        /**
         * The cost of joining [to] from [from], 0 to 31: low for one family or look, high across a change of
         * kind (built, growing, ground) and into the ground band.
         */
        private fun cost(from: BlockState, to: BlockState, fromBand: Boolean, toBand: Boolean): Int {
            val a = features(from)
            val b = features(to)
            if (a.block === b.block) return if (toBand && !fromBand) GROUND_ENTRY else 0
            var cost = when {
                a.stem.isNotEmpty() && a.stem == b.stem -> SAME_FAMILY
                a.sound === b.sound && a.mapColor == b.mapColor -> SAME_LOOK
                a.sound === b.sound || a.tools and b.tools != 0 -> SAME_SOUND_OR_TOOL
                else -> UNRELATED
            }
            if (a.full != b.full) cost += SHAPE_CHANGE
            if (a.kind != b.kind && a.kind != EITHER && b.kind != EITHER) cost += KIND_CHANGE
            if (toBand && !fromBand) cost += GROUND_ENTRY
            return cost.coerceAtMost(LEVELS - 1)
        }

        private fun features(state: BlockState): Features = cache.getOrPut(state) {
            val block = state.block
            val id = BuiltInRegistries.BLOCK.getKey(block)
            var stem = id.path.removePrefix("stripped_")
            STEM_SUFFIXES.firstOrNull { stem.endsWith(it) }?.let { stem = stem.removeSuffix(it) }
            val full = runCatching {
                Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
            }.getOrDefault(false)
            val mapColor = runCatching { state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).id }.getOrDefault(0)
            var tools = 0
            TOOL_TAGS.forEachIndexed { i, tag -> if (state.`is`(tag)) tools = tools or (1 shl i) }
            Features(
                block, state.soundType.hitSound, mapColor, tools, "${id.namespace}:$stem", full,
                when {
                    isGround(state) -> GROUND
                    state.`is`(BlockTags.LOGS) -> EITHER
                    isGrowth(state) -> GROWTH
                    else -> BUILT
                },
            )
        }

        /** The surface things stand on: terrain, paths, fields, snow and fluids. */
        fun isGround(state: BlockState): Boolean =
            state.`is`(BlockTags.OVERWORLD_CARVER_REPLACEABLES) || state.`is`(BlockTags.NETHER_CARVER_REPLACEABLES) ||
                state.`is`(BlockTags.DIRT) || state.`is`(BlockTags.SNOW) ||
                state.`is`(Blocks.DIRT_PATH) || state.`is`(Blocks.FARMLAND) || state.`is`(Blocks.BEDROCK) ||
                state.block is LiquidBlock

        /** What grows: unplaced leaves and plants. Crops are a farm's, so they count as built. */
        private fun isGrowth(state: BlockState): Boolean {
            if (state.`is`(BlockTags.LEAVES)) return !(state.hasProperty(LeavesBlock.PERSISTENT) && state.getValue(LeavesBlock.PERSISTENT))
            return state.block is VineBlock || state.block is MultifaceBlock || state.block is GrowingPlantBlock ||
                state.block is BushBlock && !state.`is`(BlockTags.CROPS)
        }
    }
}
