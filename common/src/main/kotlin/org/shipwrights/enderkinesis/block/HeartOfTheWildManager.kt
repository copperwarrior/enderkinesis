package org.shipwrights.enderkinesis.block

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.saveddata.SavedData
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.util.ShipBlockMove
import org.shipwrights.enderkinesis.util.ShipMoveListener
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.isBlockInShipyard

object HeartOfTheWildManager : ShipMoveListener {

    private val LOG = LogUtils.getLogger()

    private const val DATA_NAME = "enderkinesis_wild_heart_regrowth"

    private fun regrowth(level: ServerLevel): RegrowthData =
        level.dataStorage.computeIfAbsent({ tag -> RegrowthData.load(tag) }, { RegrowthData() }, DATA_NAME)

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
        LifecycleEvent.SERVER_STOPPED.register { _ ->
            shipHearts.clear()
            nextWorldGrowTick.clear()
        }
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

        val ship = server.getLoadedShipManagingPos(pos)
        val data: RegrowthData
        val scopeMap: MutableMap<Long, PositionPool> = if (ship != null) {
            val shipId = ship.id
            if (!shipHearts.containsKey(shipId)) {
                return
            }
            data = regrowth(server)
            data.ships.getOrPut(shipId) { HashMap() }
        } else {
            if (server.dimension() != Wohlonnogondonia.LEVEL_KEY) return
            data = regrowth(server)
            data.world
        }
        addPosition(scopeMap, pos.immutable())
        data.setDirty()

        // Shape-preserving regrow: [WogorBudBlock.tick] consumes the target. Only positions in
        // scope get one, or the store grows with each block break in each dimension.
        // Variant blocks are skipped — restashing them would round-trip back to themselves.
        if (!oldState.`is`(EKBlocks.WOGOR_BUD.get())) {
            val pickedTarget = WogorVariantPicker.pick(oldState)
            if (pickedTarget.block != oldState.block) {
                WogorBudTargetData.put(server, pos.immutable(), pickedTarget)
            }
        }

        if (server.random.nextFloat() < SPREAD_CHANCE) {
            val spread = pickRandomAdjacentReplaceable(server, pos)
            if (spread != null) {
                addPosition(scopeMap, spread)
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
        if (WohlonnogondoniaCatastrophe.isWipeInProgress()) return
        try {
            val key = level.dimension()
            val scheduled = nextWorldGrowTick[key]
            if (scheduled == null) {
                nextWorldGrowTick[key] = level.gameTime + nextGrowPeriod(level.random)
                return
            }
            if (level.gameTime < scheduled) return
            val data = regrowth(level)
            if (consumeScope(level, data.world)) data.setDirty()
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
        val data = regrowth(level)
        val scopeMap = (if (ship != null) data.ships[ship.id] else data.world) ?: return
        if (consumeScope(level, scopeMap)) data.setDirty()
    }

    /** Run one prune-and-pick pass for each loaded chunk in [scopeMap]. True when a pool changed. */
    private fun consumeScope(
        level: ServerLevel,
        scopeMap: MutableMap<Long, PositionPool>,
    ): Boolean {
        if (scopeMap.isEmpty()) return false
        var changed = false
        val chunkSource = level.chunkSource
        val iter = scopeMap.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val chunkKey: Long = entry.key
            // A block read loads its chunk; an unloaded pool waits.
            if (chunkSource.getChunkNow(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey)) == null) continue
            if (tryGrowInChunk(level, entry.value)) changed = true
            if (entry.value.isEmpty()) iter.remove()
        }
        return changed
    }

    /** Sample-based pick that caps per-fire cost at ~MAX_SAMPLES × 19 BlockState reads
     *  regardless of pool size. A sample with no support stays in the pool for retry; a
     *  sample that holds a solid block leaves it, and a later break enrolls it again.
     *  No wood-prune step needed: [LevelChunkWildHeartDestroyMixin] filters the two
     *  internal transitions (any → bud, bud → wood), so neither plant nor maturation
     *  re-adds positions. True when the pool changed. */
    private fun tryGrowInChunk(level: ServerLevel, pool: PositionPool): Boolean {
        if (pool.isEmpty()) return false
        var changed = false

        val samples = minOf(MAX_SAMPLES_PER_FIRE, pool.size())
        for (attempt in 0 until samples) {
            val pos = pool.randomPick(level.random) ?: return changed
            if (!neighboursLoaded(level, pos)) continue
            val current = level.getBlockState(pos)
            if (!current.canBeReplaced()) {
                pool.remove(pos)
                changed = true
                continue
            }
            if (!hasNonCornerAdjacency(level, pos)) continue

            // Found a growable. Attempt plant.
            val growBlock = pickRandomGrowBlock(level) ?: continue
            val growState = prepareGrowState(growBlock, pos, level) ?: continue
            if (level.setBlock(pos, growState, 3)) {
                pool.remove(pos)
                return true    // PICKS_PER_CHUNK_PER_FIRE = 1
            }
        }
        return changed
    }

    /** The support and adjacency reads go one block out, which can be the next chunk. */
    private fun neighboursLoaded(level: ServerLevel, pos: BlockPos): Boolean {
        val lx = pos.x and 15
        val lz = pos.z and 15
        if (lx in 1..14 && lz in 1..14) return true
        val chunkSource = level.chunkSource
        for (cx in ((pos.x - 1) shr 4)..((pos.x + 1) shr 4)) {
            for (cz in ((pos.z - 1) shr 4)..((pos.z + 1) shr 4)) {
                if (chunkSource.getChunkNow(cx, cz) == null) return false
            }
        }
        return true
    }

    /** Track a ship Heart, so the break listener knows the ship holds one. World Hearts need no
     *  tracking. False when the ship is not known yet (the chunk can load before its ship);
     *  the block entity then tries again. */
    fun registerHeart(level: ServerLevel, pos: BlockPos): Boolean {
        val ship = level.getShipManagingPos(pos) ?: return !level.isBlockInShipyard(pos)
        shipHearts.getOrPut(ship.id) { HashSet() }.add(pos.immutable())
        return true
    }

    /** Regrowth stays with its own part; a ship deleted by a weld only drops its entries. */
    override fun onShipBlocksMoved(level: ServerLevel, move: ShipBlockMove) {
        if (!move.fromShipDeleted) return
        val data = regrowth(level)
        if (data.ships.remove(move.fromShipId) != null) data.setDirty()
        shipHearts.remove(move.fromShipId)
    }

    /** Runs for a chunk unload too, so the saved pool of the ship stays. */
    fun unregisterHeart(level: ServerLevel, pos: BlockPos) {
        val ship = level.getShipManagingPos(pos) ?: return
        val set = shipHearts[ship.id] ?: return
        set.remove(pos)
        if (set.isEmpty()) shipHearts.remove(ship.id)
    }

    /** A real break of a Heart. The last Heart of a ship takes the ship's pool with it. */
    fun onHeartBroken(level: ServerLevel, pos: BlockPos) {
        val ship = level.getShipManagingPos(pos) ?: return
        val set = shipHearts[ship.id]
        set?.remove(pos)
        if (!set.isNullOrEmpty()) return
        shipHearts.remove(ship.id)
        val data = regrowth(level)
        if (data.ships.remove(ship.id) != null) data.setDirty()
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
        fun toLongArray(): LongArray = LongArray(list.size) { list[it].asLong() }
    }

    /** The pools, saved with the level so a break before a restart still regrows. `world` is
     *  the dimension scope (Wohlonnogondonia only); `ships` is keyed by ship id. */
    private class RegrowthData : SavedData() {
        val world: MutableMap<Long, PositionPool> = HashMap()
        val ships: MutableMap<Long, MutableMap<Long, PositionPool>> = HashMap()

        override fun save(tag: CompoundTag): CompoundTag {
            tag.putLongArray(WORLD, flatten(world))
            val list = ListTag()
            for ((shipId, scope) in ships) {
                val entry = CompoundTag()
                entry.putLong(SHIP_ID, shipId)
                entry.putLongArray(POSITIONS, flatten(scope))
                list.add(entry)
            }
            tag.put(SHIPS, list)
            return tag
        }

        companion object {
            private const val WORLD = "world"
            private const val SHIPS = "ships"
            private const val SHIP_ID = "id"
            private const val POSITIONS = "positions"

            fun load(tag: CompoundTag): RegrowthData {
                val data = RegrowthData()
                fill(data.world, tag.getLongArray(WORLD))
                val list = tag.getList(SHIPS, Tag.TAG_COMPOUND.toInt())
                for (i in 0 until list.size) {
                    val entry = list.getCompound(i)
                    fill(data.ships.getOrPut(entry.getLong(SHIP_ID)) { HashMap() }, entry.getLongArray(POSITIONS))
                }
                return data
            }

            private fun flatten(scope: Map<Long, PositionPool>): LongArray {
                val out = LongArray(scope.values.sumOf { it.size() })
                var i = 0
                for (pool in scope.values) {
                    val positions = pool.toLongArray()
                    positions.copyInto(out, i)
                    i += positions.size
                }
                return out
            }

            private fun fill(scope: MutableMap<Long, PositionPool>, packed: LongArray) {
                for (p in packed) {
                    val pos = BlockPos.of(p)
                    scope.getOrPut(ChunkPos.asLong(pos.x shr 4, pos.z shr 4)) { PositionPool() }.add(pos)
                }
            }
        }
    }
}
