package org.shipwrights.enderkinesis.block

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.LongArrayTag
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.saveddata.SavedData
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.mixin.ChunkMapGetChunksAccessor
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKGameRules
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Wohlonnogondonia biome spreader.
 *
 * Four features wired together (per the user's design spec):
 *
 *  1. **Biome spread in non-Wohlon dimensions.** Every
 *     [SPREAD_INTERVAL_TICKS], pick one Wohlon biome cell at
 *     random from every loaded chunk in this dimension and
 *     convert one of its 14 face-or-vertex neighbour cells to
 *     Wohlon. 14 = 6 face-adjacent + 8 vertex-adjacent — the
 *     "no edge midpoints" subset of the 26-neighbourhood.
 *     `400` ticks ⇒ 3 conversions per minute.
 *
 *  2. **Force-load tainted chunks at 1/hour.** Tracks every
 *     chunk that has ever held a Wohlon biome cell in this
 *     dimension via per-dim [TaintedChunkData] SavedData. Every
 *     in-game hour (1000 ticks), force-load one unloaded
 *     tainted chunk so it can spread + convert blocks even
 *     when no player is nearby.
 *
 *  3. **Conversion tags.**
 *     - `enderkinesis:converts_to_wogor_wood` → wogor wood
 *     - `enderkinesis:converts_to_mud` → mud
 *     - `enderkinesis:converts_to_wogor_leaves` → mangrove
 *       leaves (the canopy block Wohlon's chunkgen uses)
 *
 *  4. **Random-tick Wohlon biome blocks.** Every server tick,
 *     for every loaded chunk with Wohlon biome cells: pick
 *     `randomTickSpeed` random positions per section and, if
 *     the position's biome cell is Wohlon AND the block is in
 *     one of the converts-to tags, replace it. Same rate as
 *     vanilla random tick (3-per-section-per-tick at default
 *     game rule).
 *
 * All state lives in per-dimension [TaintedChunkData] SavedData;
 * the manager itself is stateless. The tainted chunk set is
 * populated lazily during the spread step (every loaded chunk
 * that has Wohlon biome gets added) and grown by spread events.
 * It is never shrunk — by design, once Wohlon biome touches a
 * chunk that chunk stays in the spread/conversion economy.
 */
object WohlonnogondoniaSpreader {

    private val LOG = LogUtils.getLogger()

    /** 1 in-game hour = 1000 ticks (vanilla day = 24000 ticks ÷ 24). */
    private const val FORCE_LOAD_INTERVAL_TICKS: Long = 1000L

    /** Per-second biome-change rate at `wohlonSpreadSpeed = 1`.
     *  The gamerule multiplies this — so `speed = 10` gives
     *  10 cell flips per second; `speed = 100` gives 100. The
     *  per-tick allowance is computed as `speed / 20.0` with a
     *  carry-over accumulator in [spreadBudget], so non-integer
     *  per-tick rates are honoured exactly over the long run. */
    private const val SPREAD_PER_SEC_BASE: Int = 1

    /** Per-dim SavedData key for the tainted-chunk set. */
    private const val TAINTED_DATA_NAME = "enderkinesis_wohlon_tainted_chunks"

    /** Conversion tags. Created in
     *  `data/enderkinesis/tags/blocks/converts_to_*.json`. Each
     *  tag maps to one target block via [CONVERSIONS]. */
    private val CONVERTS_TO_WOGOR_LOG: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "converts_to_wogor_log"))
    private val CONVERTS_TO_WOGOR_WOOD: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "converts_to_wogor_wood"))
    private val CONVERTS_TO_MUD: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "converts_to_mud"))
    private val CONVERTS_TO_WOGOR_LEAVES: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "converts_to_wogor_leaves"))

    /** Tag → conversion supplier. The supplier takes the source
     *  block state so it can copy across compatible properties
     *  (axis on logs/wood, persistent/distance/waterlogged on
     *  leaves, etc.) — see [inheritedState]. Order matters: the
     *  first matching tag in this list wins, so put more specific
     *  tags first if conflicts ever arise.
     *
     *  Log and wood are kept as **disjoint tags** — a vanilla
     *  log converts to `wogor_log` (keeping its end-cap), a
     *  vanilla wood converts to `wogor_wood` (keeping its
     *  all-bark look). Critically, neither tag references
     *  `#minecraft:logs`: that vanilla tag includes both log and
     *  wood variants *and* has `enderkinesis:wogor_wood` added
     *  to it (in `data/minecraft/tags/blocks/logs.json`) for
     *  leaf-decay purposes. If `converts_to_wogor_log` pulled in
     *  `#minecraft:logs`, every wogor_wood block in a Wohlon
     *  biome would mutate into wogor_log on its next random
     *  tick. Explicit per-block lists in
     *  `converts_to_wogor_log.json` / `converts_to_wogor_wood.json`
     *  avoid that collision. */
    private val CONVERSIONS: List<Pair<TagKey<Block>, (BlockState) -> BlockState>> = listOf(
        CONVERTS_TO_WOGOR_LOG to { src -> inheritedState(src, EKBlocks.WOGOR_LOG.get().defaultBlockState()) },
        CONVERTS_TO_WOGOR_WOOD to { src -> inheritedState(src, EKBlocks.WOGOR_WOOD.get().defaultBlockState()) },
        CONVERTS_TO_MUD to { src -> inheritedState(src, Blocks.MUD.defaultBlockState()) },
        CONVERTS_TO_WOGOR_LEAVES to { src -> inheritedState(src, Blocks.MANGROVE_LEAVES.defaultBlockState()) },
    )

    /** Copy every property [source] has that [target] also
     *  defines. Catches axis on logs, persistent/distance/
     *  waterlogged on leaves, etc., without per-block special-
     *  casing. Property type erasure forces a cast — the
     *  hasProperty gate ensures it's sound. */
    @Suppress("UNCHECKED_CAST")
    private fun inheritedState(source: BlockState, target: BlockState): BlockState {
        var result = target
        for (prop in source.properties) {
            if (!result.hasProperty(prop)) continue
            val typed = prop as Property<Comparable<Any?>>
            result = result.setValue(typed, source.getValue(typed))
        }
        return result
    }


    /** Total ticks the portal-creation seed spreads over. 300
     *  ticks = 15 s at 20 tps. */
    private const val SEED_TOTAL_TICKS: Int = 300

    /** Block-radius of the portal-creation seed sphere. The
     *  sphere is sampled in block-space then snapped to biome
     *  quarter-cells (4 blocks per cell) so we touch roughly
     *  (4/3)π(16/4)³ ≈ 270 unique biome cells around the portal. */
    private const val SEED_RADIUS_BLOCKS: Int = 16

    /** 6 cardinal directions used by the spread trigger to pick
     *  one random adjacent block per Wohlon-cell random-tick.
     *  Cached because `Direction.values()` allocates a fresh array
     *  per call. */
    private val SPREAD_DIRECTIONS: Array<Direction> = Direction.values()

    /** Rarity divisor for the boundary-block conversion. Blocks
     *  in non-Wohlon biome cells whose cell is face-adjacent to
     *  at least one Wohlon biome cell get a `1 / BOUNDARY_RARITY`
     *  chance per random-tick roll to convert via the same
     *  `pickConversion` tag mapping the in-cell branch uses.
     *  `100` ⇒ ~1 % of the in-cell rate — a sparse fringe of
     *  converted blocks just outside the biome line that softens
     *  the otherwise pixel-sharp 4 × 4 × 4 cell grid. Scaled down
     *  by `wohlonSpreadSpeed` (faster spread ⇒ thicker fringe). */
    private const val BOUNDARY_RARITY: Int = 100

    /** Per-(dim, chunk) cache: 64-bit mask where bit `i` is set
     *  iff section index `i` of the chunk contains a Wohlon biome
     *  cell. `0L` means "no sections have Wohlon — skip the
     *  whole chunk". Updated lazily on first scan and on every
     *  successful biome spread (the chunk's entry is cleared so
     *  the next conversion tick re-computes it).
     *
     *  Keyed by `(level.dimension hash, ChunkPos.asLong)` — see
     *  [chunkCacheKey]. Lives in a `ConcurrentHashMap` because the
     *  server tick is single-threaded but chunk unload can fire
     *  off-tick on some platforms; we don't want lock contention. */
    private val sectionMaskCache: ConcurrentHashMap<Long, Long> = ConcurrentHashMap()

    /** Reusable per-tick chunk-list buffer. Avoids re-allocating
     *  the list every server tick. */
    private val loadedChunkBuffer: ArrayList<LevelChunk> = ArrayList(256)

    /** Per-dimension list of in-progress portal seed jobs. A job
     *  starts when [startPortalSeed] is called by the heart
     *  candle ritual; it converts its queue of biome cells over
     *  [SEED_TOTAL_TICKS] ticks (one batch per tick).
     *
     *  Not persisted across restarts — if the server goes down
     *  mid-seed, only the cells already converted stay Wohlon;
     *  the rest of the sphere will fill in via natural spread
     *  over the following minutes. The portal itself is
     *  persistent regardless. */
    private val seedJobs: ConcurrentHashMap<ResourceKey<Level>, MutableList<SeedJob>> = ConcurrentHashMap()

    /** Per-dimension batched chunk-resync queue. Random-tick
     *  spread accumulates touched chunks here and the
     *  [BROADCAST_FLUSH_INTERVAL_TICKS] tick beat flushes the set
     *  with one packet per chunk. Pre-fix this was a per-tick
     *  broadcast loop — same chunks at the spread front rebroadcast
     *  20 times per second, ~26 % of server CPU. Set semantics
     *  dedupe so a chunk touched 20 ticks in a row only costs one
     *  packet per second. */
    private val pendingResyncs: ConcurrentHashMap<ResourceKey<Level>, MutableSet<Long>> = ConcurrentHashMap()

    /** Ticks between batched chunk-resync flushes. 20 = once per
     *  second at 20 tps. Visual latency is bounded by this. */
    private const val BROADCAST_FLUSH_INTERVAL_TICKS: Long = 20L

    /** Fixed per-second cell-flip budget for the queue drain
     *  (per dimension). Independent of `wohlonSpreadSpeed` —
     *  that gamerule still scales random-tick density (and so
     *  the **rate at which the queue fills**), but the drain
     *  out of the queue is hard-capped here. */
    private const val SPREAD_DRAIN_PER_SEC: Int = 5

    /** Per-dimension FIFO of biome cells waiting to be flipped
     *  to Wohlon. Populated by [enqueueCellSpread] from inside
     *  [tickChunkConversions]: every random-tick inside a Wohlon
     *  biome cell picks one cardinal-adjacent block, and if
     *  *that block* is in a `converts_to_*` tag (i.e. **can be
     *  converted** — not air, not stone, not a player-placed
     *  block) and its biome cell is not yet Wohlon, the cell
     *  enqueues. Drained by [drainSpreadQueue] up to
     *  [SPREAD_DRAIN_PER_SEC] cells per second. `LinkedHashSet`
     *  gives O(1) add / contains (so duplicate enqueues coalesce)
     *  while preserving insertion order for the drain. Not
     *  persisted — on server restart the queue starts empty and
     *  refills naturally as random-ticks fire. */
    private val spreadQueue: MutableMap<ResourceKey<Level>, LinkedHashSet<Long>> = HashMap()

    /** Per-dimension fractional accumulator for the queue
     *  drain. Each tick we add `SPREAD_DRAIN_PER_SEC / 20.0`
     *  and consume the floor; the remainder carries over so
     *  the long-run drain rate matches exactly. */
    private val spreadDrainBudget: MutableMap<ResourceKey<Level>, Double> = HashMap()

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::tickLevel)
    }

    private fun chunkCacheKey(dimHash: Int, chunkLong: Long): Long {
        // 32 bits of dim hash in the upper word, chunkpos.asLong's
        // lower 32 bits in the lower word — collisions are
        // extremely unlikely across dimensions and chunk indexes
        // and would only cost a stale recompute.
        return (dimHash.toLong() shl 32) xor chunkLong
    }

    /** Read + clamp `wohlonSpreadSpeed`. Snapped to `[1, 100]`
     *  since vanilla's `IntegerValue.create(int)` in 1.20.1 has
     *  no min/max overload — values outside that range are
     *  silently clipped here. */
    private fun spreadSpeed(level: ServerLevel): Int {
        return level.gameRules.getInt(EKGameRules.WOHLON_SPREAD_SPEED).coerceIn(1, 100)
    }

    private fun tickLevel(level: ServerLevel) {
        val gameTime = level.gameTime
        val isWohlon = level.dimension() == Wohlonnogondonia.LEVEL_KEY

        if (isWohlon) {
            tickConversionsWohlon(level)
            return
        }

        // Non-Wohlon dimension. Skip the spreader entirely if
        // Wohlon biome has never touched this dimension AND no
        // portal-seed is in progress here. This avoids wasted
        // work in nether / end / other modded dims that will
        // never see Wohlon biome unless the player builds a
        // portal there.
        val tainted = getTaintedData(level)
        val hasActiveSeeds = !seedJobs[level.dimension()].isNullOrEmpty()
        val hasPendingSpread = !spreadQueue[level.dimension()].isNullOrEmpty()
        if (tainted.positions.isEmpty() && !hasActiveSeeds && !hasPendingSpread) return

        val speed = spreadSpeed(level)
        tickSeedJobs(level)
        val forceLoadInterval = max(1L, FORCE_LOAD_INTERVAL_TICKS / speed)

        // Drain the random-tick-driven spread queue at the
        // fixed [SPREAD_DRAIN_PER_SEC] rate. The queue is
        // populated by [tickConversionsTainted] (via
        // [tickChunkConversions]) whenever a random-tick lands
        // on a block whose biome cell is non-Wohlon and
        // face-adjacent to a Wohlon cell — i.e. cells that
        // would have been valid spread targets in the old
        // timed model, only now they're queued by the random
        // tick that observed them rather than by a per-tick
        // frontier scan.
        val prev = spreadDrainBudget.getOrDefault(level.dimension(), 0.0)
        val budget = prev + (SPREAD_DRAIN_PER_SEC.toDouble() / 20.0)
        val toDrain = budget.toInt()
        spreadDrainBudget[level.dimension()] = budget - toDrain
        if (toDrain > 0) drainSpreadQueue(level, toDrain)

        if (gameTime % 600L == 0L) {
            val queueSize = spreadQueue[level.dimension()]?.size ?: 0
            LOG.info(
                "WohlonSpreader: dim={} speed={} (ticks {}× random-density), drain={}/s, queue={}, forceLoadInterval={}t, tainted: {}",
                level.dimension().location(), speed, speed,
                SPREAD_DRAIN_PER_SEC, queueSize,
                forceLoadInterval, tainted.positions.size,
            )
        }
        if (gameTime % forceLoadInterval == 0L) tryForceLoadTaintedChunk(level)
        tickConversionsTainted(level)
        if (gameTime % BROADCAST_FLUSH_INTERVAL_TICKS == 0L) flushPendingResyncs(level)
    }

    /** Enqueue a biome cell for spread. Idempotent — repeated
     *  calls for the same cell collapse into one queue entry
     *  via [LinkedHashSet]. Called from [tickChunkConversions]
     *  every time a random-tick lands on a block in a non-
     *  Wohlon biome cell that is face-adjacent to a Wohlon cell.
     *
     *  In high-random-tick scenarios (e.g. `randomTickSpeed=3,
     *  wohlonSpreadSpeed=100`) the queue may grow faster than
     *  it drains, in which case the queue absorbs the burst
     *  and the drain rate stays bounded. In low-random-tick
     *  scenarios (e.g. mostly air around the Wohlon front)
     *  the queue may stay near-empty and the drain is the
     *  bottleneck. Both cases self-stabilise. */
    private fun enqueueCellSpread(level: ServerLevel, qx: Int, qy: Int, qz: Int) {
        val queue = spreadQueue.getOrPut(level.dimension()) { LinkedHashSet() }
        queue.add(BlockPos.asLong(qx, qy, qz))
    }

    /** Drain up to [count] cells off the spread queue and flip
     *  each to Wohlon. Cells that turn out to be already Wohlon
     *  (because some other write got there first) or to be in
     *  an unloaded chunk don't count against the budget — the
     *  drain keeps going until [count] **successful** flips, or
     *  until the queue runs dry, whichever comes first.
     *
     *  Resyncs are coalesced per touched chunk: the
     *  [setBiomeCellSilent] writes don't broadcast, and a single
     *  [broadcastChunkResync] at the end of the drain sends one
     *  packet per chunk regardless of how many cells flipped in
     *  it. */
    private fun drainSpreadQueue(level: ServerLevel, count: Int) {
        if (count <= 0) return
        val queue = spreadQueue[level.dimension()] ?: return
        if (queue.isEmpty()) return
        val wohlonBiome = lookupBiome(level) ?: return
        val touchedChunks = HashSet<ChunkPos>()
        val iter = queue.iterator()
        var drained = 0
        while (iter.hasNext() && drained < count) {
            val packed = iter.next()
            iter.remove()
            val qx = BlockPos.getX(packed)
            val qy = BlockPos.getY(packed)
            val qz = BlockPos.getZ(packed)
            if (setBiomeCellSilent(level, qx, qy, qz, wohlonBiome, touchedChunks)) {
                drained++
            }
        }
        if (touchedChunks.isNotEmpty()) {
            // Immediate broadcast — same shape as [tickSeedJobs].
            val chunkSource = level.chunkSource
            for (pos in touchedChunks) {
                val chunk = chunkSource.getChunkNow(pos.x, pos.z) ?: continue
                broadcastChunkResync(level, chunk)
            }
        }
    }

    /** Queue a 16-block-radius sphere of biome cells around
     *  [portalPos] to be converted to Wohlon over 15 seconds.
     *  Called by the heart-candle ritual after a successful
     *  pattern + biome check; the actual writes happen
     *  incrementally in [tickSeedJobs] so the cost of ~270
     *  biome writes + chunk-resync packets is spread out instead
     *  of dumped into one tick. */
    /** Flip the single biome cell containing [blockPos] to
     *  Wohlonnogondonia. Routes through [setBiomeCellTo] so the
     *  chunk is properly tainted, the section mask is bumped,
     *  and the resync packet fires — i.e. it's the same write
     *  path the spreader uses internally, just exposed publicly
     *  for the `/wohlon setcell` debug command. Returns `true`
     *  if the cell flipped, `false` if the chunk is unloaded
     *  or the cell was already Wohlon. */
    @JvmStatic
    fun convertSingleCellToWohlon(level: ServerLevel, blockPos: BlockPos): Boolean {
        val wohlonBiome = lookupBiome(level) ?: return false
        return setBiomeCellTo(
            level,
            blockPos.x shr 2, blockPos.y shr 2, blockPos.z shr 2,
            wohlonBiome,
        )
    }

    /** Convert every biome cell touched by a collection of
     *  block positions to the Wohlon biome, deduplicating
     *  shared cells (4 × 4 × 4 block cells) and coalescing
     *  client resyncs to one packet per touched chunk.
     *
     *  Used by [WohlonnogondoniaTreeGrower] to convert the
     *  biome along the tree's growth wave: every tick, the
     *  positions of the freshly-placed voxels get fed in and
     *  the cells they cover flip to Wohlon. The tree's painted
     *  shape then carries the biome with it as it grows. */
    @JvmStatic
    fun convertCellsToWohlon(level: ServerLevel, blockPositions: Collection<BlockPos>) {
        if (blockPositions.isEmpty()) return
        val wohlonBiome = lookupBiome(level) ?: return
        val touchedChunks = HashSet<ChunkPos>()
        val seenCells = HashSet<Long>(blockPositions.size)
        for (pos in blockPositions) {
            val qx = pos.x shr 2
            val qy = pos.y shr 2
            val qz = pos.z shr 2
            // 4×4×4 cells share a single biome entry, so many
            // adjacent placed voxels resolve to the same cell —
            // dedupe before paying the `getAndSet` cost.
            if (!seenCells.add(BlockPos.asLong(qx, qy, qz))) continue
            setBiomeCellSilent(level, qx, qy, qz, wohlonBiome, touchedChunks)
        }
        // One resync per touched chunk, regardless of how many
        // cells flipped in it — mirrors [tickSeedJobs]'s
        // coalesce strategy.
        for (cp in touchedChunks) {
            val chunk = level.chunkSource.getChunkNow(cp.x, cp.z) as? LevelChunk ?: continue
            broadcastChunkResync(level, chunk)
        }
    }

    @JvmStatic
    fun startPortalSeed(level: ServerLevel, portalPos: BlockPos) {
        val cells = computeSphereCells(portalPos, SEED_RADIUS_BLOCKS)
        if (cells.isEmpty()) return
        // Scale the seed window by the gamerule multiplier.
        // wohlonSpreadSpeed=10 ⇒ 1.5 s sphere instead of 15 s.
        val speed = spreadSpeed(level)
        val totalTicks = max(1, SEED_TOTAL_TICKS / speed)
        val job = SeedJob(ArrayDeque(cells), totalTicks)
        seedJobs.computeIfAbsent(level.dimension()) { mutableListOf() }.add(job)
        LOG.info(
            "WohlonSeed: queued {} cells around {} in {} over {} ticks (speed {})",
            cells.size, portalPos, level.dimension().location(), totalTicks, speed,
        )
    }

    /** Enumerate the biome cells inside a sphere of radius
     *  [radiusBlocks] around [center]. Voxels at distance ≤
     *  `r` are mapped to their owning biome quarter-cell and
     *  deduplicated; the result is **sorted nearest-first** so
     *  the seeded biome visibly grows outward from the portal
     *  rather than appearing in row-scan order. */
    private fun computeSphereCells(center: BlockPos, radiusBlocks: Int): List<CellCoord> {
        val r2 = radiusBlocks * radiusBlocks
        val byCell = HashMap<CellCoord, Int>()  // cell → squared distance of closest voxel
        for (dx in -radiusBlocks..radiusBlocks) {
            for (dy in -radiusBlocks..radiusBlocks) {
                for (dz in -radiusBlocks..radiusBlocks) {
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (d2 > r2) continue
                    val cell = CellCoord(
                        (center.x + dx) shr 2,
                        (center.y + dy) shr 2,
                        (center.z + dz) shr 2,
                    )
                    val prev = byCell[cell]
                    if (prev == null || d2 < prev) byCell[cell] = d2
                }
            }
        }
        return byCell.entries.sortedBy { it.value }.map { it.key }
    }

    /** Tick one slot of every active seed job in [level]:
     *  - Drain `ceil(remaining_cells / remaining_ticks)` cells
     *    per job. This keeps the rate even as cells are removed
     *    so the queue empties exactly at tick [SEED_TOTAL_TICKS].
     *  - Skip writes for cells that are already Wohlon (no work,
     *    no packet).
     *  - Coalesce client resyncs: every cell write across all
     *    jobs this tick is batched into a single
     *    `Set<ChunkPos>` and we send ONE
     *    `ClientboundLevelChunkWithLightPacket` per chunk at the
     *    end — not one per cell. */
    private fun tickSeedJobs(level: ServerLevel) {
        val list = seedJobs[level.dimension()] ?: return
        if (list.isEmpty()) {
            seedJobs.remove(level.dimension())
            return
        }
        val wohlonBiome = lookupBiome(level) ?: return
        val touchedChunks = HashSet<ChunkPos>()
        val iter = list.iterator()
        while (iter.hasNext()) {
            val job = iter.next()
            if (job.cells.isEmpty()) {
                iter.remove()
                continue
            }
            val remainingTicks = job.ticksRemaining.coerceAtLeast(1)
            val batchSize = (job.cells.size + remainingTicks - 1) / remainingTicks
            repeat(batchSize) {
                if (job.cells.isEmpty()) return@repeat
                val cell = job.cells.removeFirst()
                setBiomeCellSilent(level, cell.qx, cell.qy, cell.qz, wohlonBiome, touchedChunks)
            }
            job.ticksRemaining = (job.ticksRemaining - 1).coerceAtLeast(0)
        }
        // Coalesced resync — one packet per touched chunk per
        // tick regardless of how many cells changed in it.
        for (chunkPos in touchedChunks) {
            val chunk = level.chunkSource.getChunkNow(chunkPos.x, chunkPos.z) ?: continue
            broadcastChunkResync(level, chunk)
        }
        if (list.isEmpty()) seedJobs.remove(level.dimension())
    }

    /** Fill [buffer] with the currently-loaded `LevelChunk`s in
     *  [level] and return it. Uses a shared, reusable buffer to
     *  avoid per-tick allocation; the previous `Sequence`-based
     *  iterator chewed 5.5 s of `BaseContinuationImpl.resumeWith`
     *  cost over the profile sample. Goes through the mixin
     *  invoker on [ChunkMap.getChunks] (vanilla method is
     *  package-private). */
    private fun loadedChunksInto(level: ServerLevel, buffer: ArrayList<LevelChunk>): ArrayList<LevelChunk> {
        buffer.clear()
        @Suppress("CAST_NEVER_SUCCEEDS")
        val accessor = level.chunkSource.chunkMap as ChunkMapGetChunksAccessor
        for (holder in accessor.`enderkinesis$getChunks`()) {
            val chunk = holder.fullChunk ?: continue
            buffer.add(chunk)
        }
        return buffer
    }

    /** Pick one unloaded tainted chunk and force-load it (vanilla
     *  `getChunk` is synchronous), giving its biome-spread + block-
     *  conversion logic a turn even when no player is nearby. We
     *  don't explicitly unload — vanilla's chunk-keep-alive will
     *  drop the chunk again on its normal cadence. */
    private fun tryForceLoadTaintedChunk(level: ServerLevel) {
        val data = getTaintedData(level)
        if (data.positions.isEmpty()) return
        val chunkSource = level.chunkSource
        val unloaded = ArrayList<Long>()
        for (packed in data.positions) {
            val pos = ChunkPos(packed)
            if (chunkSource.getChunkNow(pos.x, pos.z) == null) unloaded.add(packed)
        }
        if (unloaded.isEmpty()) return
        val random = level.random
        val pick = unloaded[random.nextInt(unloaded.size)]
        val pos = ChunkPos(pick)
        // Touching the chunk via getChunk pulls it through the
        // chunkgen pipeline (or loads from disk) to ChunkStatus.FULL
        // and registers it as loaded for subsequent ticks.
        level.getChunk(pos.x, pos.z)
        LOG.debug("WohlonSpread: force-loaded tainted chunk {} in {}", pos, level.dimension().location())
    }

    /** Wohlon-dimension path: every chunk has Wohlon biome (it's
     *  the only biome there), so no per-section "is this Wohlon?"
     *  check is needed. Just random-tick all sections directly.
     *  No biome flips will ever happen here (every cell is already
     *  Wohlon, so the non-Wohlon branch in tickChunkConversions
     *  never fires), so we pass an empty mutable set that nobody
     *  writes to. */
    private fun tickConversionsWohlon(level: ServerLevel) {
        val randomTickSpeed = level.gameRules.getInt(GameRules.RULE_RANDOMTICKING)
        if (randomTickSpeed <= 0) return
        val effectiveTickRate = randomTickSpeed * spreadSpeed(level)
        val random = level.random
        val mutPos = BlockPos.MutableBlockPos()
        val chunks = loadedChunksInto(level, loadedChunkBuffer)
        for (i in 0 until chunks.size) {
            val chunk = chunks[i]
            tickChunkConversions(level, chunk, ALL_SECTIONS_MASK, effectiveTickRate, random, mutPos)
        }
    }

    /** Non-Wohlon-dimension path: walk only the tainted chunks
     *  (the chunks that hold any Wohlon biome cells), random-
     *  tick the sections inside them that contain Wohlon cells.
     *
     *  Spread originates **from inside Wohlon cells**: each
     *  random-tick picks an adjacent block and, if that block
     *  is convertible, enqueues its cell for the drain. There's
     *  no need to tick neighbour chunks for spread — the
     *  trigger is the Wohlon-cell source, the target may live
     *  in an X/Z-adjacent chunk, but we only need the source
     *  chunk to be ticked. Cross-chunk targets are handled by
     *  the spread itself: once the drain flips a cell in a
     *  previously-untainted neighbour chunk,
     *  [setBiomeCellSilent] → [markTainted] adds that chunk
     *  to the tainted set and the next tick will random-tick
     *  it normally — wave continues across the seam without
     *  any explicit neighbour-chunk pass.
     *
     *  No Y-expansion of the section mask either: the boundary
     *  block conversion that needed Y-adjacent sections is gone,
     *  so we only tick sections that themselves contain Wohlon
     *  cells. */
    private fun tickConversionsTainted(level: ServerLevel) {
        val randomTickSpeed = level.gameRules.getInt(GameRules.RULE_RANDOMTICKING)
        if (randomTickSpeed <= 0) return
        val effectiveTickRate = randomTickSpeed * spreadSpeed(level)
        val data = getTaintedData(level)
        if (data.positions.isEmpty()) return
        val random = level.random
        val mutPos = BlockPos.MutableBlockPos()
        val chunkSource = level.chunkSource
        val dimHash = level.dimension().location().hashCode()
        val taintedSnapshot = data.positions.toLongArray()

        for (packed in taintedSnapshot) {
            val pos = ChunkPos(packed)
            val chunk = chunkSource.getChunkNow(pos.x, pos.z) ?: continue
            val mask = sectionMaskFor(level, chunk, dimHash)
            if (mask == 0L) continue
            tickChunkConversions(level, chunk, mask, effectiveTickRate, random, mutPos)
        }
    }

    /** Drain the per-dim batched resync queue, sending one
     *  full-chunk packet per chunk. Called from [tickLevel] on
     *  the [BROADCAST_FLUSH_INTERVAL_TICKS] beat. */
    private fun flushPendingResyncs(level: ServerLevel) {
        val pending = pendingResyncs[level.dimension()] ?: return
        if (pending.isEmpty()) return
        val chunkSource = level.chunkSource
        for (packed in pending) {
            val pos = ChunkPos(packed)
            val chunk = chunkSource.getChunkNow(pos.x, pos.z) ?: continue
            broadcastChunkResync(level, chunk)
        }
        pending.clear()
    }

    /** Per-chunk inner loop. [sectionMask] selects which sections
     *  contain Wohlon biome cells (bit `i` set ⇒ section index
     *  `i` has at least one Wohlon cell). Per section: pick
     *  [randomTickSpeed] voxels at random and run them.
     *
     *  Per random tick on a non-air block in a Wohlon biome cell:
     *
     *   1. **Source-block conversion** — if the source block is
     *      in a `converts_to_*` tag (dirt → mud, oak_log →
     *      wogor_log, etc.), it converts. Independent of the
     *      spread step.
     *
     *   2. **trySpread** — pick one of the 6 cardinal block
     *      neighbours. If that block can be converted (matches
     *      a `converts_to_*` tag — so air, stone, player builds,
     *      etc. are blocking) **and** its biome cell isn't
     *      already Wohlon, enqueue that cell for the drain.
     *
     *  Air sources are skipped (vanilla random-tick semantics —
     *  air doesn't random-tick — and we don't want air to be
     *  able to spread the biome). Sources in non-Wohlon cells
     *  are also no-ops; the spread only originates from inside
     *  the Wohlon footprint. */
    private fun tickChunkConversions(
        level: ServerLevel,
        chunk: LevelChunk,
        sectionMask: Long,
        randomTickSpeed: Int,
        random: net.minecraft.util.RandomSource,
        mutPos: BlockPos.MutableBlockPos,
    ) {
        val minSection = chunk.minSection
        val sections = chunk.sections
        val chunkBaseX = chunk.pos.x shl 4
        val chunkBaseZ = chunk.pos.z shl 4
        val chunkSource = level.chunkSource
        val boundaryRarity = max(1, BOUNDARY_RARITY / spreadSpeed(level))
        for (idx in sections.indices) {
            if ((sectionMask ushr idx) and 1L == 0L) continue
            val section: LevelChunkSection = sections[idx]
            if (section.hasOnlyAir()) continue
            val sectionBaseY = (minSection + idx) shl 4
            repeat(randomTickSpeed) {
                val lx = random.nextInt(16)
                val ly = random.nextInt(16)
                val lz = random.nextInt(16)
                val state = section.getBlockState(lx, ly, lz)
                if (state.isAir) return@repeat
                val cell = section.getNoiseBiome(lx shr 2, ly shr 2, lz shr 2)
                if (!isWohlon(cell)) {
                    // Boundary block conversion — non-Wohlon cell
                    // face-adjacent to a Wohlon cell. Same source-
                    // block tag rule as the in-cell branch, just at
                    // a `1 / boundaryRarity` per-tick chance instead
                    // of full rate. Produces a sparse fringe of
                    // converted blocks just outside the biome line
                    // that softens the otherwise pixel-sharp cell
                    // grid edge.
                    val sourceTarget = pickConversion(state) ?: return@repeat
                    if (random.nextInt(boundaryRarity) != 0) return@repeat
                    val cellQx = (chunkBaseX + lx) shr 2
                    val cellQy = (sectionBaseY + ly) shr 2
                    val cellQz = (chunkBaseZ + lz) shr 2
                    if (!hasWohlonCellNeighbor(level, cellQx, cellQy, cellQz)) return@repeat
                    mutPos.set(chunkBaseX + lx, sectionBaseY + ly, chunkBaseZ + lz)
                    level.setBlock(mutPos, sourceTarget, 2)
                    return@repeat
                }

                // (1) Convert the source block if it's in a
                // converts_to_* tag. Independent of spread.
                val sourceTarget = pickConversion(state)
                if (sourceTarget != null) {
                    mutPos.set(chunkBaseX + lx, sectionBaseY + ly, chunkBaseZ + lz)
                    // Flag 2 (UPDATE_CLIENTS only): no neighbour-
                    // notify chain — both blocks are inert solids.
                    level.setBlock(mutPos, sourceTarget, 2)
                }

                // (2) trySpread to one cardinal-neighbour cell.
                // No gate on what the neighbour block is — air,
                // stone, player-placed blocks, mod blocks all let
                // the spread through. The biome occupies space the
                // same way vanilla biomes do (full column,
                // including air); the per-block transmutation
                // step inside Wohlon cells is independent and
                // still only fires on tag-matched sources.
                val dir = SPREAD_DIRECTIONS[random.nextInt(6)]
                val nWx = chunkBaseX + lx + dir.stepX
                val nWy = sectionBaseY + ly + dir.stepY
                val nWz = chunkBaseZ + lz + dir.stepZ
                // Resolve the neighbour's chunk without forcing
                // a sync load — if it's not in memory, skip and
                // wait for the player / chunk-loader to bring it.
                val nCx = nWx shr 4
                val nCz = nWz shr 4
                val neighborChunk = if (nCx == chunk.pos.x && nCz == chunk.pos.z) {
                    chunk
                } else {
                    chunkSource.getChunkNow(nCx, nCz) ?: return@repeat
                }
                val cellQx = nWx shr 2
                val cellQy = nWy shr 2
                val cellQz = nWz shr 2
                // Skip if the cell is already Wohlon — drain would
                // no-op the flip, but pre-filtering keeps the
                // queue smaller.
                val neighborCell = neighborChunk.getNoiseBiome(cellQx, cellQy, cellQz)
                if (isWohlon(neighborCell)) return@repeat
                enqueueCellSpread(level, cellQx, cellQy, cellQz)
            }
        }
    }

    /** True iff any of the 6 face-adjacent biome cells of
     *  `(qx, qy, qz)` is Wohlon. Used by the boundary-block
     *  conversion branch in [tickChunkConversions] — only cells
     *  one step outside the biome line qualify for the
     *  `1 / boundaryRarity` block-conversion chance. */
    private fun hasWohlonCellNeighbor(level: ServerLevel, qx: Int, qy: Int, qz: Int): Boolean {
        if (isWohlon(level.getNoiseBiome(qx - 1, qy, qz))) return true
        if (isWohlon(level.getNoiseBiome(qx + 1, qy, qz))) return true
        if (isWohlon(level.getNoiseBiome(qx, qy - 1, qz))) return true
        if (isWohlon(level.getNoiseBiome(qx, qy + 1, qz))) return true
        if (isWohlon(level.getNoiseBiome(qx, qy, qz - 1))) return true
        if (isWohlon(level.getNoiseBiome(qx, qy, qz + 1))) return true
        return false
    }

    /** Get-or-compute the per-chunk section bitmask. A `0L` entry
     *  means "no Wohlon cells anywhere in this chunk" and the
     *  conversion loop short-circuits. The cache is invalidated
     *  on biome spread via [invalidateSectionMask]. */
    private fun sectionMaskFor(level: ServerLevel, chunk: LevelChunk, dimHash: Int): Long {
        val key = chunkCacheKey(dimHash, chunk.pos.toLong())
        val cached = sectionMaskCache[key]
        if (cached != null) return cached
        var mask = 0L
        val sections = chunk.sections
        for (idx in sections.indices) {
            val section = sections[idx]
            outer@ for (qx in 0..3) for (qy in 0..3) for (qz in 0..3) {
                if (isWohlon(section.getNoiseBiome(qx, qy, qz))) {
                    mask = mask or (1L shl idx)
                    break@outer
                }
            }
        }
        sectionMaskCache[key] = mask
        return mask
    }

    /** Incrementally OR a section bit into the cached mask for a
     *  chunk. Called after a biome cell write to keep the cache
     *  in sync without a 1536-cell rescan.
     *
     *  This relies on the invariant that **Wohlon biome only ever
     *  spreads — never retracts**. Setting a Wohlon cell always
     *  monotonically grows the mask; clearing one would be the
     *  only operation that could shrink it, and we don't have a
     *  code path for that. So an OR is exactly what's needed:
     *  whatever previously-cached bits were correct, this just
     *  adds the new section's bit on top.
     *
     *  Pre-fix: `invalidateSectionMask` cleared the entry on
     *  every flip and the next `sectionMaskFor` call re-scanned
     *  all sections — 1068 ms of self-time in the spark profile
     *  with random-tick spread at default settings. After this
     *  change, the mask lookup is a `ConcurrentHashMap.get`. */
    private fun bumpSectionMask(level: ServerLevel, chunkPos: ChunkPos, sectionIdx: Int) {
        val dimHash = level.dimension().location().hashCode()
        val key = chunkCacheKey(dimHash, chunkPos.toLong())
        val bit = 1L shl sectionIdx
        sectionMaskCache.compute(key) { _, existing ->
            if (existing == null) bit else existing or bit
        }
    }

    /** Map a block-state to its conversion target via the
     *  converts-to tags, or null if no tag matches. The supplier
     *  receives the source state so it can inherit compatible
     *  properties (axis, persistent, distance, waterlogged, etc.). */
    private fun pickConversion(state: BlockState): BlockState? {
        for ((tag, supplier) in CONVERSIONS) {
            if (state.`is`(tag)) return supplier(state)
        }
        return null
    }

    /** Write [biome] into the biome cell at world-quarter
     *  coordinates [(qx, qy, qz)]. Forces a chunk load if needed
     *  (called from the force-load tick), marks the chunk unsaved,
     *  and broadcasts a full-chunk packet so clients see the
     *  colour change immediately.
     *
     *  **Note**: this is the immediate-broadcast variant, used
     *  only by paths that fire infrequently. The high-volume
     *  random-tick spread uses [setBiomeCellSilent] + the
     *  per-server-tick coalesce loop in [tickConversionsTainted]
     *  to avoid one chunk packet per flip. */
    private fun setBiomeCellTo(
        level: ServerLevel, qx: Int, qy: Int, qz: Int, biome: Holder<Biome>,
    ): Boolean {
        val cx = qx shr 2
        val cz = qz shr 2
        val chunk = level.getChunk(cx, cz) as? LevelChunk ?: return false
        val sectionIdx = (qy shr 2) - chunk.minSection
        if (sectionIdx < 0 || sectionIdx >= chunk.sections.size) return false
        val localQx = qx and 3
        val localQy = qy and 3
        val localQz = qz and 3
        val section: LevelChunkSection = chunk.sections[sectionIdx]
        val existing = section.getNoiseBiome(localQx, localQy, localQz)
        if (existing === biome || isWohlon(existing)) return false
        @Suppress("UNCHECKED_CAST")
        val biomes = section.biomes as PalettedContainer<Holder<Biome>>
        biomes.getAndSet(localQx, localQy, localQz, biome)
        chunk.setUnsaved(true)
        markTainted(getTaintedData(level), chunk.pos)
        bumpSectionMask(level, chunk.pos, sectionIdx)
        broadcastChunkResync(level, chunk)
        return true
    }

    /** Mutating sibling of [setBiomeCellTo] that does NOT
     *  broadcast — used by [tickSeedJobs] which coalesces every
     *  cell write per tick into one resync packet per chunk. The
     *  caller adds the touched chunk pos to [touchedChunks];
     *  the seed job loop sends a single resync per chunk after
     *  all writes are done. */
    private fun setBiomeCellSilent(
        level: ServerLevel,
        qx: Int, qy: Int, qz: Int,
        biome: Holder<Biome>,
        touchedChunks: MutableSet<ChunkPos>,
    ): Boolean {
        val cx = qx shr 2
        val cz = qz shr 2
        val chunk = level.chunkSource.getChunkNow(cx, cz) ?: return false
        val sectionIdx = (qy shr 2) - chunk.minSection
        if (sectionIdx < 0 || sectionIdx >= chunk.sections.size) return false
        val localQx = qx and 3
        val localQy = qy and 3
        val localQz = qz and 3
        val section: LevelChunkSection = chunk.sections[sectionIdx]
        val existing = section.getNoiseBiome(localQx, localQy, localQz)
        if (existing === biome || isWohlon(existing)) return false
        @Suppress("UNCHECKED_CAST")
        val biomes = section.biomes as PalettedContainer<Holder<Biome>>
        biomes.getAndSet(localQx, localQy, localQz, biome)
        chunk.setUnsaved(true)
        // First touch of this chunk this tick? Mark tainted now;
        // subsequent flips in the same chunk this tick skip the
        // HashSet.add roundtrip on the persisted set. Pre-fix this
        // was 4 s of profile time at default settings.
        if (touchedChunks.add(chunk.pos)) {
            markTainted(getTaintedData(level), chunk.pos)
        }
        bumpSectionMask(level, chunk.pos, sectionIdx)
        // World-root grower trigger — every cell flip. The grower
        // applies an "is this cell surrounded by Wohlon?" gate to
        // pick interior cells over edge cells (so the root anchors
        // inside the biome footprint, not at the spreading boundary)
        // plus its own region-level dedup so we only ever spawn one
        // march per region. Cheap — 4 biome lookups per call, no
        // allocation on the no-trigger path.
        WohlonnogondoniaWorldRootGrower.maybeEnqueueCell(level, qx, qy, qz)
        return true
    }

    /** Resend the chunk to nearby players so they see the new
     *  biome colour without a relog. Vanilla 1.20.1 has no
     *  per-cell biome packet — full chunk resync is the
     *  canonical path. Sent at most ~3× per minute via the
     *  spread cadence so the bandwidth cost is negligible. */
    private fun broadcastChunkResync(level: ServerLevel, chunk: LevelChunk) {
        val packet = ClientboundLevelChunkWithLightPacket(chunk, level.lightEngine, null, null)
        val players = level.chunkSource.chunkMap.getPlayers(chunk.pos, false)
        for (player in players) player.connection.send(packet)
    }

    private fun isWohlon(biome: Holder<Biome>): Boolean = biome.`is`(Wohlonnogondonia.BIOME_KEY)

    private fun lookupBiome(level: ServerLevel): Holder<Biome>? {
        return level.registryAccess()
            .registryOrThrow(Registries.BIOME)
            .getHolder(Wohlonnogondonia.BIOME_KEY)
            .orElse(null)
    }

    private fun markTainted(data: TaintedChunkData, pos: ChunkPos) {
        if (data.positions.add(pos.toLong())) data.setDirty()
    }

    private fun getTaintedData(level: ServerLevel): TaintedChunkData {
        return level.dataStorage.computeIfAbsent(
            { tag -> TaintedChunkData.load(tag) },
            { TaintedChunkData() },
            TAINTED_DATA_NAME,
        )
    }

    /** Compact (qx, qy, qz) tuple used while building the cell
     *  pool inside [trySpread]. Inline-`data class` lets us avoid
     *  manual hashCode/equals for the same flat-array effort. */
    private data class CellCoord(val qx: Int, val qy: Int, val qz: Int)

    /** Per-portal seed job: a queue of biome cells to convert
     *  + a ticks-remaining counter. Created by [startPortalSeed]
     *  and ticked by [tickSeedJobs]. */
    private data class SeedJob(val cells: ArrayDeque<CellCoord>, var ticksRemaining: Int)

    /** Pre-computed mask "all 64 possible section indexes set" —
     *  used as the section-eligibility mask in the Wohlon-dim
     *  path where every section is biome-Wohlon by construction. */
    private const val ALL_SECTIONS_MASK: Long = -1L

    /** Persistent set of chunks (by `ChunkPos.toLong`) that have
     *  ever held a Wohlon biome cell in this dimension. */
    class TaintedChunkData : SavedData() {

        @JvmField val positions: MutableSet<Long> = HashSet()

        override fun save(tag: CompoundTag): CompoundTag {
            tag.put("positions", LongArrayTag(positions.toLongArray()))
            return tag
        }

        companion object {
            fun load(tag: CompoundTag): TaintedChunkData {
                val data = TaintedChunkData()
                if (tag.contains("positions", Tag.TAG_LONG_ARRAY.toInt())) {
                    for (v in tag.getLongArray("positions")) data.positions.add(v)
                }
                return data
            }
        }
    }
}

