package org.shipwrights.enderkinesis.block

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.valkyrienskies.mod.common.getLoadedShipManagingPos

object HeartOfTheWildManager {

    private val LOG = LogUtils.getLogger()

    /** Per-dimension chunked position pools. `dim → chunkKey → pool`.
     *  Pool semantics so that breaking the same position twice
     *  doesn't double-register it. Only [Wohlonnogondonia.LEVEL_KEY]
     *  is expected to populate. */
    private val worldPositions:
        MutableMap<ResourceKey<Level>, MutableMap<Long, PositionPool>> = HashMap()

    /** Per-ship chunked position pools. `shipId → chunkKey → pool`. */
    private val shipPositions:
        MutableMap<Long, MutableMap<Long, PositionPool>> = HashMap()

    /** Per-ship registry of Heart positions — a ship is considered
     *  to "hold a Heart" iff its set is non-empty. */
    private val shipHearts: MutableMap<Long, MutableSet<BlockPos>> = HashMap()

    /** Block tag listing the seeds the Wild may plant into a
     *  qualifying registered position. */
    private val GROW_BLOCKS_TAG: TagKey<Block> = TagKey.create(
        Registries.BLOCK,
        ResourceLocation("enderkinesis", "wild_heart_grow_blocks"),
    )

    /** Per-break probability that we *also* register a random
     *  adjacent-replaceable neighbour. Drives outward spread. */
    private const val SPREAD_CHANCE: Float = 0.001f

    /** How many random positions per chunk each consumer tries on
     *  one fire. One — so each fire produces at most one visible
     *  new bud per chunk. The "growth cycle" is now an emergent
     *  property of [GROW_PERIOD_MIN_TICKS]/MAX × this count
     *  (≈ 5 plantings per 15–20 ticks per consumer per chunk),
     *  spread across the cycle window instead of bursting all at
     *  once on a single fire. */
    private const val PICKS_PER_CHUNK_PER_FIRE: Int = 1

    /** How many random positions per chunk per fire we *check*
     *  while looking for one that's growable. Each check costs
     *  ≈ 19 BlockState reads (1 for canBeReplaced + up to 18 for
     *  the non-corner-diagonal neighbours). The old code iterated
     *  the ENTIRE pool's set to classify every position as growable
     *  or not, then random-picked one — that scaled the cost
     *  linearly with pool size. Sampling caps the per-fire cost at
     *  a constant ≈ [MAX_SAMPLES_PER_FIRE] × 19 reads regardless of
     *  pool size. Spark v8 had `tryGrowInChunk` + `hasNonCornerAdjacency`
     *  at ~1 s cumulative on the server thread per 160 s sample;
     *  this should reduce that to milliseconds. */
    private const val MAX_SAMPLES_PER_FIRE: Int = 8

    /** Inclusive bounds on the per-consumer-fire delay (game
     *  ticks). 3–4 ticks ⇒ ≈ 5 fires per the legacy 15–20-tick
     *  cycle, so the per-chunk planting rate is unchanged but
     *  each plant is visible at its own moment instead of five
     *  appearing on a single tick. Lowered from 15–20 specifically
     *  to smooth the visible "cycle triggering" — the Heart BE
     *  and the Wohlon dim consumer both reroll a fresh delay in
     *  this range between fires, so multiple consumers (Hearts +
     *  dim) further desync naturally. */
    const val GROW_PERIOD_MIN_TICKS: Int = 3
    const val GROW_PERIOD_MAX_TICKS: Int = 4

    /** Per-dimension scheduled tick for the Wohlon implicit
     *  consumer's next fire. */
    private val nextWorldGrowTick: MutableMap<ResourceKey<Level>, Long> = HashMap()

    /** Pick the next inter-fire delay (game ticks). Uniform over
     *  `[GROW_PERIOD_MIN_TICKS, GROW_PERIOD_MAX_TICKS]`. */
    fun nextGrowPeriod(random: net.minecraft.util.RandomSource): Long {
        val span = GROW_PERIOD_MAX_TICKS - GROW_PERIOD_MIN_TICKS + 1
        return GROW_PERIOD_MIN_TICKS.toLong() + random.nextInt(span).toLong()
    }

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::tickServerLevel)
        // Block-destruction registration is driven by
        // org.shipwrights.enderkinesis.mixin.LevelChunkWildHeartDestroyMixin
        // calling onBlockDestroyed below.
    }

    /** Thread-local flag: while true, [onBlockDestroyed] returns
     *  immediately without enrolling the destroyed position or stashing a
     *  bud target. Use [withEnrollSuppressed] around a block of code that
     *  should consume blocks without arming the wogor regrowth pipeline —
     *  e.g. the Wik-Lak Host's mud-and-skull construction in
     *  [org.shipwrights.enderkinesis.entity.WikLakConstruction], where
     *  the three pattern blocks are intentionally sacrificed and must
     *  not later sprout a bud where the host stands. */
    private val enrollSuppressed: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    /** Run [block] with the regrowth-enroll path suppressed for any
     *  block destruction it triggers on this thread. Re-entrant via
     *  save/restore so nested callers compose. */
    fun <T> withEnrollSuppressed(block: () -> T): T {
        val previous = enrollSuppressed.get()
        enrollSuppressed.set(true)
        try {
            return block()
        } finally {
            enrollSuppressed.set(previous)
        }
    }

    @JvmStatic
    fun onBlockDestroyed(server: ServerLevel, pos: BlockPos, oldState: BlockState) {
        // Chunkgen workers fire LevelChunk.setBlockState during paint flushes, which would
        // mutate our plain HashMaps concurrently with tickServerLevel AND enroll millions of
        // paint positions during pre-gen. Filter to server-thread destructions only.
        if (!server.server.isSameThread) return

        // Suppressed path — see [withEnrollSuppressed]. The construction
        // wraps its block-consumption in this guard so the wogor bud
        // pipeline doesn't latch onto the sacrificed blocks.
        if (enrollSuppressed.get()) return

        // Shape-preserving regrow: resolve a wogor variant matching the destroyed
        // block's shape family ([WogorVariantPicker]) and stash it for the bud's
        // maturation tick ([WogorBudBlock.tick]) to consume. Variant blocks are
        // skipped — restashing them would just round-trip back to themselves.
        val pickedTarget = WogorVariantPicker.pick(oldState)
        if (pickedTarget.block != oldState.block) {
            WogorBudTargetData.put(server, pos.immutable(), pickedTarget)
        }

        val ship = server.getLoadedShipManagingPos(pos)
        val scopeMap: MutableMap<Long, PositionPool> = if (ship != null) {
            val shipId = ship.id
            if (!shipHearts.containsKey(shipId)) {
                LOG.debug("WildHeart: ignored ship break at {} on ship {} (no Heart)", pos, shipId)
                return
            }
            shipPositions.getOrPut(shipId) { HashMap() }
        } else {
            if (server.dimension() != Wohlonnogondonia.LEVEL_KEY) return
            worldPositions.getOrPut(server.dimension()) { HashMap() }
        }
        addPosition(scopeMap, pos.immutable())
        LOG.debug("WildHeart: registered {} (oldBlock={})", pos, oldState.block)

        if (server.random.nextFloat() < SPREAD_CHANCE) {
            val spread = pickRandomAdjacentReplaceable(server, pos)
            if (spread != null) {
                addPosition(scopeMap, spread)
                LOG.debug("WildHeart: spread-registered {}", spread)
            }
        }
    }

    /** Insert [pos] into the chunk pool it belongs to, creating the
     *  per-chunk pool on first add. Pool semantics ⇒ adding the
     *  same position twice is a no-op. */
    private fun addPosition(
        scopeMap: MutableMap<Long, PositionPool>,
        pos: BlockPos,
    ) {
        scopeMap.getOrPut(chunkKey(pos)) { PositionPool() }.add(pos)
    }


    /** Wohlon's implicit dimension consumer. Fires every 15–20
     *  ticks regardless of Heart presence, giving the dimension
     *  its baseline rate. Hearts in Wohlon are additional
     *  consumers on the same chunk-map. */
    private fun tickServerLevel(level: ServerLevel) {
        if (level.dimension() != Wohlonnogondonia.LEVEL_KEY) return
        try {
            val key = level.dimension()
            val scheduled = nextWorldGrowTick[key]
            if (scheduled == null) {
                nextWorldGrowTick[key] = level.gameTime + nextGrowPeriod(level.random)
                return
            }
            if (level.gameTime < scheduled) return
            val scopeMap = worldPositions[key]
            if (scopeMap != null) consumeScope(level, scopeMap)
            nextWorldGrowTick[key] = level.gameTime + nextGrowPeriod(level.random)
        } catch (t: Throwable) {
            LOG.error("WildHeart: dimension consumer threw; rescheduling", t)
            nextWorldGrowTick[level.dimension()] = level.gameTime + nextGrowPeriod(level.random)
        }
    }

    /** Per-Heart consumer fire. Looks up the scope (ship if the
     *  Heart is on one, else world dimension) and runs one round
     *  of attempts. */
    fun tickHeart(level: ServerLevel, heartPos: BlockPos) {
        val ship = level.getLoadedShipManagingPos(heartPos)
        val scopeMap: MutableMap<Long, PositionPool>? =
            if (ship != null) shipPositions[ship.id]
            else worldPositions[level.dimension()]
        scopeMap ?: return
        consumeScope(level, scopeMap)
    }

    /** Iterate every chunk in [scopeMap] and run one prune-and-pick
     *  pass per chunk. */
    private fun consumeScope(
        level: ServerLevel,
        scopeMap: MutableMap<Long, PositionPool>,
    ) {
        // Snapshot keys so we can prune empty chunks during the
        // iteration without ConcurrentModificationException.
        val keys = ArrayList(scopeMap.keys)
        for (chunkKey in keys) {
            val pool = scopeMap[chunkKey] ?: continue
            tryGrowInChunk(level, pool)
            if (pool.isEmpty()) scopeMap.remove(chunkKey)
        }
    }

    /** Sample-based pick that caps per-fire cost at ~MAX_SAMPLES × 19 BlockState reads
     *  regardless of pool size. Failed samples stay in the pool for retry.
     *  No wood-prune step needed: [LevelChunkWildHeartDestroyMixin] filters the two
     *  internal transitions (any → bud, bud → wood), so neither plant nor maturation
     *  re-adds positions. */
    private fun tryGrowInChunk(level: ServerLevel, pool: PositionPool) {
        if (pool.isEmpty()) return

        val samples = minOf(MAX_SAMPLES_PER_FIRE, pool.size())
        for (attempt in 0 until samples) {
            val pos = pool.randomPick(level.random) ?: return
            val current = level.getBlockState(pos)
            if (!current.canBeReplaced()) continue
            if (!hasNonCornerAdjacency(level, pos)) continue

            // Found a growable. Attempt plant.
            val growBlock = pickRandomGrowBlock(level) ?: continue
            val growState = prepareGrowState(growBlock, pos, level) ?: continue
            if (level.setBlock(pos, growState, 3)) {
                pool.remove(pos)
                LOG.debug("WildHeart: grew {} at {}", growBlock, pos)
                return    // PICKS_PER_CHUNK_PER_FIRE = 1
            }
        }
    }

    /** Called by the BE's [net.minecraft.world.level.block.entity.BlockEntity.setLevel]
     *  on load — for ship-resident Hearts, tracks the Heart in
     *  [shipHearts] so the break listener can see "this ship hosts
     *  at least one Heart". World Hearts don't need tracking. */
    fun registerHeart(level: ServerLevel, pos: BlockPos) {
        val ship = level.getLoadedShipManagingPos(pos) ?: return
        shipHearts.getOrPut(ship.id) { HashSet() }.add(pos.immutable())
    }

    fun unregisterHeart(level: ServerLevel, pos: BlockPos) {
        val ship = level.getLoadedShipManagingPos(pos) ?: return
        val set = shipHearts[ship.id] ?: return
        set.remove(pos)
        if (set.isEmpty()) {
            shipHearts.remove(ship.id)
            shipPositions.remove(ship.id)
        }
    }

    /** Pack a block position's chunk coords into a single long
     *  key. Arithmetic shift preserves Minecraft's negative-chunk
     *  semantics — block x=-1 is in chunk x=-1, x=-17 in chunk x=-2. */
    private fun chunkKey(pos: BlockPos): Long =
        ChunkPos.asLong(pos.x shr 4, pos.z shr 4)

    /** Build the final [BlockState] to place at [pos]. Returns
     *  null if the block needs a face-adjacent support and none
     *  is available. FACING is set to the direction from this
     *  position toward the picked supporting neighbour (FACING
     *  points AT the support). AXIS is uniformly randomized for
     *  pillar-style blocks. */
    private fun prepareGrowState(
        blockType: Block, pos: BlockPos, level: ServerLevel,
    ): BlockState? {
        var state = blockType.defaultBlockState()

        if (state.hasProperty(BlockStateProperties.FACING)) {
            val supportDir = pickFaceAdjacentSupport(level, pos) ?: return null
            state = state.setValue(BlockStateProperties.FACING, supportDir)
        }

        if (state.hasProperty(BlockStateProperties.AXIS)) {
            val axes = Direction.Axis.values()
            state = state.setValue(
                BlockStateProperties.AXIS,
                axes[level.random.nextInt(axes.size)],
            )
        }

        return state
    }

    /** Pick a uniformly random direction (one of the six
     *  cardinals) in which [pos] has a face-sturdy neighbour. */
    private fun pickFaceAdjacentSupport(level: ServerLevel, pos: BlockPos): Direction? {
        val candidates = ArrayList<Direction>(6)
        for (dir in Direction.values()) {
            val neighborPos = pos.relative(dir)
            val neighborState = level.getBlockState(neighborPos)
            if (neighborState.isFaceSturdy(level, neighborPos, dir.opposite)) {
                candidates.add(dir)
            }
        }
        if (candidates.isEmpty()) return null
        return candidates[level.random.nextInt(candidates.size)]
    }

    /** Sample a random block from [GROW_BLOCKS_TAG]. */
    private fun pickRandomGrowBlock(level: ServerLevel): Block? {
        val registry = level.registryAccess().registryOrThrow(Registries.BLOCK)
        val holders = registry.getTagOrEmpty(GROW_BLOCKS_TAG).toList()
        if (holders.isEmpty()) return null
        val idx = level.random.nextInt(holders.size)
        return holders[idx].value()
    }

    /** True iff any of the 18 face-or-edge-adjacent neighbours of
     *  [pos] holds a non-air non-replaceable block. */
    private fun hasNonCornerAdjacency(level: ServerLevel, pos: BlockPos): Boolean {
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            if (dx == 0 && dy == 0 && dz == 0) continue
            val nonZero = (if (dx != 0) 1 else 0) +
                (if (dy != 0) 1 else 0) +
                (if (dz != 0) 1 else 0)
            if (nonZero == 3) continue
            val n = pos.offset(dx, dy, dz)
            val s = level.getBlockState(n)
            if (!s.isAir && !s.canBeReplaced()) return true
        }
        return false
    }

    /** Pick a uniformly random replaceable position from the
     *  18-neighbourhood (face + edge, no corner diagonals) of
     *  [pos]. Used for the 0.1 % spread roll. */
    private fun pickRandomAdjacentReplaceable(level: ServerLevel, pos: BlockPos): BlockPos? {
        val candidates = ArrayList<BlockPos>(18)
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
            if (dx == 0 && dy == 0 && dz == 0) continue
            val nonZero = (if (dx != 0) 1 else 0) +
                (if (dy != 0) 1 else 0) +
                (if (dz != 0) 1 else 0)
            if (nonZero == 3) continue
            val n = pos.offset(dx, dy, dz).immutable()
            if (level.getBlockState(n).canBeReplaced()) candidates.add(n)
        }
        if (candidates.isEmpty()) return null
        return candidates[level.random.nextInt(candidates.size)]
    }

    /** A set-like container that supports O(1) random pick AND O(1)
     *  add / contains / remove. Parallel structures: an
     *  `ArrayList<BlockPos>` for indexed access and an
     *  `HashMap<BlockPos, Int>` mapping each position to its current
     *  list index. Remove is implemented by swapping the doomed slot
     *  with the last element and dropping the last slot — O(1)
     *  amortised — instead of `ArrayList.remove(o)`'s O(N) linear
     *  search.
     *
     *  Replaces the old `MutableSet<BlockPos>` (HashSet) used in
     *  HeartOfTheWildManager. HashSet gave O(1) add/contains/remove
     *  but its iterator-based random sampling was effectively O(N)
     *  per pick. With the new sampled grow loop this would have
     *  been the new hot path; PositionPool keeps O(1) on every
     *  operation [tryGrowInChunk] needs. */
    private class PositionPool {
        private val list = ArrayList<BlockPos>()
        private val indexMap = HashMap<BlockPos, Int>()

        fun add(pos: BlockPos): Boolean {
            if (indexMap.containsKey(pos)) return false
            indexMap[pos] = list.size
            list.add(pos)
            return true
        }

        fun remove(pos: BlockPos): Boolean {
            val idx = indexMap.remove(pos) ?: return false
            val lastIdx = list.size - 1
            if (idx < lastIdx) {
                val last = list[lastIdx]
                list[idx] = last
                indexMap[last] = idx
            }
            list.removeAt(lastIdx)
            return true
        }

        fun randomPick(rng: net.minecraft.util.RandomSource): BlockPos? {
            if (list.isEmpty()) return null
            return list[rng.nextInt(list.size)]
        }

        fun size(): Int = list.size
        fun isEmpty(): Boolean = list.isEmpty()
        fun contains(pos: BlockPos): Boolean = indexMap.containsKey(pos)
    }
}
