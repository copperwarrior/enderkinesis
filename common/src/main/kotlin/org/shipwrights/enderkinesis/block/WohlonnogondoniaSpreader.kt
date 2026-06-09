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
 *     - `enderkinesis:converts_to_wogor_leaves` → wogor
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
        CONVERTS_TO_WOGOR_LEAVES to { src -> inheritedState(src, EKBlocks.WOGOR_LEAVES.get().defaultBlockState()) },
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

    /** **Visual de-grid #2.** Face / edge / vertex offsets packed
     *  as `IntArray` triples (3 ints per offset). [tickFrontierSpread]
     *  picks a tier (face / edge / vertex) by a 70/20/10 roll, then
     *  a random offset within the chosen tier. Result: spread is no
     *  longer aligned to the three orthogonal planes, so the front
     *  silhouette stops reading as Minecraft-cell-shaped.
     *
     *  Face offsets dominate by design — biome neighbourhood is
     *  formally defined by face-adjacency and we don't want spread
     *  to become so diagonal-heavy that it visibly "leaps" past
     *  cells. Edge (12 entries, 2-coord nonzero) and vertex (8
     *  entries, 3-coord nonzero) provide the de-alignment without
     *  taking over. */
    private val FACE_OFFSETS: IntArray = intArrayOf(
        -1, 0, 0,   1, 0, 0,
        0, -1, 0,   0, 1, 0,
        0, 0, -1,   0, 0, 1,
    )
    private val EDGE_OFFSETS: IntArray = intArrayOf(
        -1, -1, 0,   1, -1, 0,   -1, 1, 0,   1, 1, 0,
        -1, 0, -1,   1, 0, -1,   -1, 0, 1,   1, 0, 1,
        0, -1, -1,   0, 1, -1,   0, -1, 1,   0, 1, 1,
    )
    private val VERTEX_OFFSETS: IntArray = intArrayOf(
        -1, -1, -1,   1, -1, -1,   -1, 1, -1,   1, 1, -1,
        -1, -1, 1,    1, -1, 1,    -1, 1, 1,    1, 1, 1,
    )

    /** **Visual de-grid #5: per-block distance-banded fringe.**
     *
     *  In a non-Wohlon biome cell, conversion rate depends on the
     *  individual block's *block-level* Manhattan distance to the
     *  nearest block in a Wohlon biome cell — not on the cell's
     *  overall Wohlon-adjacency count.
     *
     *  Two tiers visible:
     *   - **Tier 1** (`minDist = 1`): the block is physically
     *     touching a Wohlon-cell block on one of its 6 faces.
     *     Inside a non-Wohlon cell, these are exactly the blocks
     *     on the cell-face that meets a Wohlon cell — the outer
     *     1-block slab pinned to the biome line. High rate.
     *   - **Tier 2** (`minDist = 2`): the block is one step
     *     deeper into the non-Wohlon cell, still pointing toward
     *     the Wohlon cell. Much lower rate.
     *   - **Tier 3+** (`minDist ≥ 3`): skipped. The fringe is
     *     at most 2 blocks thick.
     *
     *  Result: the fringe band is sharply concentrated at the
     *  Wohlon-cell boundary rather than spread uniformly over the
     *  whole adjacent non-Wohlon cell. Visually: the converted
     *  blocks form a thin rim that follows the biome line one
     *  block out, with a sparse skim of secondary conversions one
     *  block deeper. */
    private const val BOUNDARY_TIER1_RATE: Double = 0.30

    /** Tier-2 rate — much lower than [BOUNDARY_TIER1_RATE]. Blocks
     *  in this tier are one block deeper into a non-Wohlon cell
     *  than tier 1, so should be sparsely scattered to suggest
     *  the fringe is dying out as you move away from the biome. */
    private const val BOUNDARY_TIER2_RATE: Double = 0.05

    /** **Visual de-grid #1: noise gate on neighbour selection.**
     *  Each spread sample evaluates a 3D Perlin field at the
     *  candidate neighbour's quarter-cell coords; if the value is
     *  below [SPREAD_NOISE_THRESHOLD], the sample is rejected and
     *  no enqueue happens. Over many samples the boundary follows
     *  noise iso-lines rather than the 4-block biome grid — cells
     *  with high noise value convert quickly; cells with low value
     *  hold out, creating organic perimeter shape.
     *
     *  Seed is fixed so the noise field is world-stable (same
     *  cells resist conversion every time, giving the boundary a
     *  consistent character). Feature size ≈ 8 cells = 32 blocks,
     *  large enough to read as biome-scale undulation rather than
     *  per-cell jitter. */
    private val SPREAD_BOUNDARY_NOISE: net.minecraft.world.level.levelgen.synth.ImprovedNoise =
        net.minecraft.world.level.levelgen.synth.ImprovedNoise(
            net.minecraft.util.RandomSource.create(SPREAD_NOISE_SEED),
        )

    /** Seed for the [SPREAD_BOUNDARY_NOISE] field. Constant so the
     *  noise pattern is the same across all worlds — gives the
     *  Wohlon front a recognisable character. */
    private const val SPREAD_NOISE_SEED: Long = 0x57E_AD_BD_DEEDL

    /** Coordinate multiplier for the noise sample. `1 / 8` per
     *  quarter-cell ⇒ 8-cell feature wavelength ⇒ 32-block
     *  feature size, matching the typical Wohlon ground roll. */
    private const val SPREAD_NOISE_FREQUENCY: Double = 0.125

    /** Pass threshold for the noise gate. Perlin output is in
     *  `[-1, 1]` with a Gaussian-ish distribution centred near 0;
     *  `-0.3` admits roughly 65 % of cells, rejecting the 35 %
     *  with the most negative noise value. Result: spread visibly
     *  slows in noise-trough regions and speeds up in noise-peaks,
     *  giving organic boundary curvature. */
    private const val SPREAD_NOISE_THRESHOLD: Double = -0.3

    /** **Visual de-grid #4: block-level noise gate on frontier cells.**
     *
     *  When a random-tick lands on a wogor-tagged block inside a
     *  Wohlon cell that's on the frontier (i.e. has at least one
     *  non-Wohlon face-adjacent neighbour cell), the block converts
     *  only if [SPREAD_BOUNDARY_NOISE] at *block* coords is above
     *  [BLOCK_NOISE_THRESHOLD]. Interior Wohlon cells (no non-Wohlon
     *  neighbours) convert at full rate — the gradient is only
     *  needed at the moving front.
     *
     *  Effect: a frontier cell ends up with ~50 % of its
     *  wogor-source blocks converted in a noise-clustered pattern,
     *  rather than 100 % painted. Combined with the existing 5 %
     *  boundary fringe outside the biome line, the perceived
     *  transition is a two-cell-wide noise gradient instead of a
     *  pixel-sharp 4-block step.
     *
     *  Uses the same [SPREAD_BOUNDARY_NOISE] field as the cell-level
     *  gate, so noise-peak regions are *both* faster to spread *and*
     *  more heavily converted — coherent noise-shaped infection
     *  "veins" rather than two unrelated noise overlays.
     *
     *  Cells transition out of the frontier when their last
     *  non-Wohlon neighbour converts; the bit clears in
     *  [updateFrontierForFlip], and the cell's remaining ~50 % of
     *  vanilla blocks finishes converting at full rate. The
     *  scattered look is transient *per cell* but always visible
     *  *somewhere* on the moving front. */

    /** Coordinate multiplier for the block-level noise sample.
     *  `0.3` per block ⇒ wavelength ≈ 3.3 blocks ⇒ features ≈ 1.5
     *  blocks. Small enough that adjacent blocks see different
     *  values (per-block variation), large enough that a 2×2 cluster
     *  of "all converted" or "all skipped" blocks can naturally
     *  form. */
    private const val BLOCK_NOISE_FREQUENCY: Double = 0.3

    /** Pass threshold for the block-level noise gate. `0.0` admits
     *  roughly 50 % of blocks — wogor-tagged sources in a frontier
     *  cell convert at half rate, distributed by the noise field. */
    private const val BLOCK_NOISE_THRESHOLD: Double = 0.0

    /** Per-(dim, chunk) runtime state for the spread engine.
     *  **Pure in-memory cache** — every field is derivable from
     *  authoritative chunk data plus the persistent
     *  [TaintedChunkData], so eviction at any time is harmless
     *  (next access lazily rebuilds whatever it needs).
     *
     *  Phase 1 fields (live now):
     *   - [sectionMask] — 64-bit mask, bit `i` set iff section
     *     index `i` of the chunk contains a Wohlon biome cell.
     *     `0L` short-circuits the chunk's conversion loop.
     *
     *  Phase 2+ fields (reserved, currently unused; left here so
     *  later passes can populate them without re-plumbing the
     *  whole spread path):
     *   - frontier bitmask (1536 bits ≈ 24 longs covering every
     *     biome quarter-cell in the chunk) for frontier-only
     *     spread sampling
     *   - frontier-cell count for O(1) weighted reservoir
     *     force-load picks
     *   - last-spread / last-ticked game times for
     *     hot/warm/cold tiering and lazy catch-up
     *
     *  The container is a class (not just `Long`) so growing it
     *  in later phases doesn't require touching every read site.
     *  Keep it small — see [evictUnloadedChunkStates] for the
     *  per-tick eviction sweep that prevents unbounded growth. */
    private class ChunkSpreadState {
        @Volatile var sectionMask: Long = 0L

        /** Phase 2: per-biome-cell frontier bitmask. 24 longs cover
         *  the chunk's 24 sections × 64 biome cells (4×4×4 per
         *  section). Bit layout per long:
         *  `(qy_local << 4) | (qx_local << 2) | qz_local` — 6 bits,
         *  fits one long per section.
         *
         *  A bit is set iff the corresponding biome cell is Wohlon
         *  *and* has at least one non-Wohlon face-adjacent neighbor.
         *  Maintained by [updateFrontierForFlip] on every biome
         *  flip; consumed by [tickFrontierSpread] as the spread
         *  sampler's source.
         *
         *  Lazily allocated — `null` until this chunk first touches
         *  the frontier (most chunks never do, so this saves
         *  ~192 bytes × untouched-chunk-count). */
        @Volatile var frontierBits: LongArray? = null

        /** Running popcount of [frontierBits]. Maintained as bits
         *  are flipped so we don't have to re-popcount on every
         *  sampling pass. When this drops to 0, the chunk leaves
         *  the per-dim [activeFrontierChunks] set; when it rises
         *  from 0, the chunk joins. */
        @Volatile var frontierCount: Int = 0

        /** Phase 3: gameTime of the most recent "activity event" in
         *  this chunk. Set by [markChunkActivity] on biome flips
         *  and successful block conversions. Read by
         *  [tickConversionsTainted] to bucket the chunk into the
         *  hot / warm / cold tier — chunks with recent activity
         *  tick every server tick; chunks that have been quiet
         *  for [WARM_THRESHOLD_TICKS] tick on a slower cadence;
         *  chunks quiet for [HOT_AND_WARM_THRESHOLD_TICKS] tick
         *  only once a minute.
         *
         *  Default `0L` is treated as "unset" in the tier check —
         *  fresh states are initialised to the current gameTime
         *  on their first tick pass so a freshly-loaded chunk
         *  doesn't get instantly demoted to cold by elapsed-time
         *  math.  */
        @Volatile var lastActivityGameTime: Long = 0L

        /** Phase 5: `true` once we've scanned every Wohlon biome
         *  cell in the chunk and populated its frontier bit.
         *  `false` for states freshly created via [bumpSectionMask]
         *  (the incremental-flip path) — those start with at most
         *  one frontier bit set, the one for the just-flipped cell,
         *  and need a full re-scan to catch up on any pre-existing
         *  Wohlon cells the flip path didn't touch.
         *
         *  This matters for chunks that load with already-tainted
         *  biome (saved from a previous session or unloaded long
         *  enough for the in-memory state to be evicted): without
         *  the re-scan, their Wohlon cells never enter the frontier
         *  sampler and spread effectively stalls at them. */
        @Volatile var frontierPopulated: Boolean = false
    }

    /** Per-dim set of chunk-pos-longs with `frontierCount > 0`.
     *  [tickFrontierSpread] picks from this set instead of walking
     *  every tainted chunk — the spread cost becomes
     *  `O(|frontier|)` ≈ `O(r)` (boundary), not `O(r²)` (volume).
     *
     *  Wraps a plain `HashSet<Long>` to match the auto-boxing
     *  pattern used elsewhere in this file; the per-tick sampling
     *  cost is dominated by the `toLongArray()` snapshot, which is
     *  O(|activeFrontierChunks|) per spread tick — typically a
     *  few hundred entries, negligible.
     *
     *  Cleared on [SERVER_STOPPED]. Chunks self-evict from the set
     *  when their `frontierCount` returns to 0 via the maintenance
     *  hook in [updateFrontierForFlip]. */
    private val activeFrontierChunks: ConcurrentHashMap<ResourceKey<Level>, MutableSet<Long>> =
        ConcurrentHashMap()

    /** Per-(dim, chunk) cache, keyed by `(level.dimension hash,
     *  ChunkPos.asLong)` — see [chunkCacheKey]. Lives in a
     *  [ConcurrentHashMap] because the server tick is
     *  single-threaded but chunk unload can fire off-tick on
     *  some platforms; we don't want lock contention.
     *
     *  Eviction policy:
     *   - [evictUnloadedChunkStates] runs every
     *     [STATE_EVICTION_INTERVAL_TICKS] ticks per dimension and
     *     drops entries whose chunk is no longer loaded. The cache
     *     is purely a runtime accelerator (see [ChunkSpreadState]),
     *     so dropped entries lazily rebuild on next access.
     *   - [SERVER_STOPPED] clears everything.
     *   - [STATE_CACHE_SOFT_CAP] is a noisy ceiling — if the cache
     *     grows past it (e.g. a very wide infection on a server
     *     that's been up for weeks), [tickLevel] logs a warning so
     *     the operator can investigate. It does **not** auto-trim;
     *     trimming without a usage-recency signal could evict a
     *     hot chunk's state mid-spread and cause a re-scan storm.
     *     Phase 3 tiering adds the timestamps needed for safe LRU. */
    private val chunkStates: ConcurrentHashMap<Long, ChunkSpreadState> = ConcurrentHashMap()

    /** How often to sweep [chunkStates] and evict entries whose
     *  chunks are no longer loaded. 600 ticks = 30 s — eviction
     *  doesn't need to be quick, just steady. Cost: one
     *  `getChunkNow` probe per entry, hash lookups in chunk source. */
    private const val STATE_EVICTION_INTERVAL_TICKS: Long = 600L

    /** Upper-32-bit mask, used by [evictUnloadedChunkStates] to
     *  isolate the dim-hash portion of a [chunkCacheKey] long.
     *  Kotlin's `0xFFFFFFFF00000000L` would overflow the literal
     *  parser; build it as `0xFFFFFFFFL shl 32` instead. */
    private const val UPPER_32_MASK: Long = 0xFFFFFFFFL shl 32

    /** Per-dim observability threshold for the chunk-state cache.
     *  Crossing this size produces a one-shot warning log per
     *  rising-edge; the cache keeps working, this just tells the
     *  operator the eviction sweep isn't keeping up with the
     *  growth rate. 50_000 entries × ~32 bytes ≈ 1.6 MB per dim —
     *  not catastrophic, but a useful smoke signal. */
    private const val STATE_CACHE_SOFT_CAP: Int = 50_000

    /** One-shot flag per dim: have we already logged the soft-cap
     *  warning since the cache last dropped under the cap? Avoids
     *  spamming the log every tick while large. Cleared on
     *  [SERVER_STOPPED] with the rest of the per-session state. */
    private val cacheCapWarned: ConcurrentHashMap<ResourceKey<Level>, Boolean> = ConcurrentHashMap()

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

    /** Base per-second cell-flip budget for the queue drain (per
     *  dimension). Multiplied by `wohlonSpreadSpeed` inside
     *  [tickLevel] so the gamerule actually scales end-to-end —
     *  the frontier sampler enqueues at `2 × speed` candidates per
     *  tick and the drain matches that throughput at `5 × speed`
     *  flips per second. Without the multiplier the drain was a
     *  hard ceiling that nullified high gamerule values. */
    private const val SPREAD_DRAIN_PER_SEC: Int = 5

    /** Base per-second cell-flip budget for the vertical-cascade
     *  queue drain. Same multiplier shape as
     *  [SPREAD_DRAIN_PER_SEC] — `VERTICAL_DRAIN_PER_SEC × speed`
     *  effective rate. Tuned high because vertical fills are
     *  uncontested (no random-tick competition, no neighbour
     *  checks, no cascading expansion of their own beyond the
     *  same XZ column) and because each horizontal flip seeds
     *  the entire column above it — the vertical queue needs to
     *  drain faster than the horizontal feeds it or the column
     *  visibly fills in upward at a crawl. */
    private const val VERTICAL_DRAIN_PER_SEC: Int = 500

    /** Per-dimension FIFO of biome cells waiting to be flipped
     *  to Wohlon. Populated by [enqueueCellSpread] from inside
     *  [tickChunkConversions]: every random-tick inside a Wohlon
     *  biome cell picks one cardinal-adjacent block, and if
     *  *that block* is in a `converts_to_*` tag (i.e. **can be
     *  converted** — not air, not stone, not a player-placed
     *  block) and its biome cell is not yet Wohlon, the cell
     *  enqueues. Drained by [drainQueue] up to
     *  [SPREAD_DRAIN_PER_SEC] cells per second. `LinkedHashSet`
     *  gives O(1) add / contains (so duplicate enqueues coalesce)
     *  while preserving insertion order for the drain. Not
     *  persisted — on server restart the queue starts empty and
     *  refills naturally as random-ticks fire. */
    private val spreadQueue: MutableMap<ResourceKey<Level>, LinkedHashSet<Long>> = HashMap()

    /** Per-dimension FIFO of biome cells waiting to be flipped to
     *  Wohlon via the column cascade (sky cells above a fresh
     *  horizontal flip). Kept distinct from [spreadQueue] so the
     *  paced horizontal-spread budget isn't burned on bulk
     *  vertical fills that don't need pacing. */
    private val verticalSpreadQueue: MutableMap<ResourceKey<Level>, LinkedHashSet<Long>> = HashMap()

    /** Per-dimension fractional accumulator for the queue
     *  drain. Each tick we add `SPREAD_DRAIN_PER_SEC / 20.0`
     *  and consume the floor; the remainder carries over so
     *  the long-run drain rate matches exactly. */
    private val spreadDrainBudget: MutableMap<ResourceKey<Level>, Double> = HashMap()

    /** Parallel of [spreadDrainBudget] for the vertical-cascade
     *  drain — separate accumulator so the two rates compose
     *  independently. */
    private val verticalDrainBudget: MutableMap<ResourceKey<Level>, Double> = HashMap()

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::tickLevel)
        // Clear all per-dimension static state on server stop. The
        // integrated server stops when the player quits to title;
        // loading a *different* world afterwards otherwise inherits
        // the previous world's queues, seed jobs, and budgets via
        // these singleton maps (ResourceKey<Level> equality is by
        // location, so two unrelated worlds' minecraft:overworld
        // keys collide).
        dev.architectury.event.events.common.LifecycleEvent.SERVER_STOPPED.register { _ ->
            chunkStates.clear()
            cacheCapWarned.clear()
            activeFrontierChunks.clear()
            seedJobs.clear()
            pendingResyncs.clear()
            spreadQueue.clear()
            verticalSpreadQueue.clear()
            spreadDrainBudget.clear()
            verticalDrainBudget.clear()
        }
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
        val hasPendingVertical = !verticalSpreadQueue[level.dimension()].isNullOrEmpty()
        if (tainted.positions.isEmpty() && !hasActiveSeeds && !hasPendingSpread && !hasPendingVertical) return

        val speed = spreadSpeed(level)
        tickSeedJobs(level)
        val forceLoadInterval = max(1L, FORCE_LOAD_INTERVAL_TICKS / speed)

        // Drain the spread queue. Rate scales with
        // [wohlonSpreadSpeed]: base [SPREAD_DRAIN_PER_SEC] × speed
        // cells per second. Without the speed multiplier the drain
        // becomes a hard ceiling on biome flips/sec regardless of
        // how aggressively the frontier sampler enqueues — at
        // speed 100 the sampler would generate ~4 000 candidates/s
        // but only 5/s ever made it through. The multiplier lets
        // the gamerule actually scale end-to-end.
        val drainPerSec = SPREAD_DRAIN_PER_SEC * speed
        val prev = spreadDrainBudget.getOrDefault(level.dimension(), 0.0)
        val budget = prev + (drainPerSec.toDouble() / 20.0)
        val toDrain = budget.toInt()
        spreadDrainBudget[level.dimension()] = budget - toDrain
        if (toDrain > 0) drainQueue(level, toDrain, spreadQueue)

        // Separate budget / queue for the column-cascade. Drained
        // at [VERTICAL_DRAIN_PER_SEC] × speed so the sky portion of
        // fresh columns catches up quickly without spending the
        // slow horizontal budget on it.
        val verticalDrainPerSec = VERTICAL_DRAIN_PER_SEC * speed
        val prevVert = verticalDrainBudget.getOrDefault(level.dimension(), 0.0)
        val budgetVert = prevVert + (verticalDrainPerSec.toDouble() / 20.0)
        val toDrainVert = budgetVert.toInt()
        verticalDrainBudget[level.dimension()] = budgetVert - toDrainVert
        if (toDrainVert > 0) drainQueue(level, toDrainVert, verticalSpreadQueue)

        if (gameTime % 600L == 0L) {
            val queueSize = spreadQueue[level.dimension()]?.size ?: 0
            val vQueueSize = verticalSpreadQueue[level.dimension()]?.size ?: 0
            LOG.info(
                "WohlonSpreader: dim={} speed={} (ticks {}× random-density), drain={}/s (vert {}/s), queue={} (vert {}), forceLoadInterval={}t, tainted: {}",
                level.dimension().location(), speed, speed,
                drainPerSec, verticalDrainPerSec,
                queueSize, vQueueSize,
                forceLoadInterval, tainted.positions.size,
            )
        }
        if (gameTime % forceLoadInterval == 0L) tryForceLoadTaintedChunk(level)
        // Phase 2: spread sampling now reads from the frontier
        // bitmask instead of from random ticks across every
        // tainted chunk. Block conversion still runs through
        // [tickConversionsTainted] below.
        tickFrontierSpread(level)
        tickConversionsTainted(level)
        if (gameTime % BROADCAST_FLUSH_INTERVAL_TICKS == 0L) flushPendingResyncs(level)
        if (gameTime % STATE_EVICTION_INTERVAL_TICKS == 0L) evictUnloadedChunkStates(level)
    }

    /** Walk [chunkStates] and drop entries whose keys belong to
     *  the current dim AND whose chunk is no longer loaded. The
     *  state is purely a runtime cache (see [ChunkSpreadState]),
     *  so dropping unloaded-chunk entries is harmless — the next
     *  time the chunk loads and the spread tick touches it,
     *  [sectionMaskFor] will lazily rebuild the entry.
     *
     *  Bounded cost: O(chunkStates.size) per sweep, dominated by
     *  one [ChunkSource.getChunkNow] hash lookup per entry.
     *  At [STATE_EVICTION_INTERVAL_TICKS] = 600 t = 30 s, this is
     *  cheap even at the [STATE_CACHE_SOFT_CAP] ceiling.
     *
     *  Also emits a one-shot operator warning if cache size for
     *  this dim crosses [STATE_CACHE_SOFT_CAP] — gated by
     *  [cacheCapWarned] so the log isn't spammed every sweep
     *  while the cache is large. Resets when size drops back
     *  under the cap, so the next rising edge re-warns. */
    private fun evictUnloadedChunkStates(level: ServerLevel) {
        val dimHash = level.dimension().location().hashCode()
        val chunkSource = level.chunkSource
        val keyDimMask = dimHash.toLong() shl 32
        var evicted = 0
        var dimEntries = 0
        val iter = chunkStates.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            // Cheap filter: only inspect entries from this dim.
            // Key layout is `(dimHash << 32) xor chunkLong`; the
            // chunkLong's upper 32 bits are usually zero (chunk
            // coords are small `int` values), so xor'ing the dim
            // mask in/out is reliable in practice. We still
            // double-check by recovering the chunk pos and
            // probing chunkSource, so an occasional cross-dim
            // false positive in the filter just means one extra
            // getChunkNow lookup — not a correctness bug.
            if ((e.key and UPPER_32_MASK) != keyDimMask) continue
            dimEntries++
            val chunkLong = e.key xor keyDimMask
            val cx = ChunkPos.getX(chunkLong)
            val cz = ChunkPos.getZ(chunkLong)
            if (chunkSource.getChunkNow(cx, cz) == null) {
                iter.remove()
                evicted++
            }
        }
        // Soft-cap observability — rising-edge warning only.
        val dimKey = level.dimension()
        val overCap = dimEntries > STATE_CACHE_SOFT_CAP
        val previouslyWarned = cacheCapWarned[dimKey] == true
        if (overCap && !previouslyWarned) {
            LOG.warn(
                "WohlonSpreader: chunk-state cache for dim={} crossed soft cap ({} > {}). Eviction is keeping up if this number plateaus; if it grows steadily, investigate.",
                dimKey.location(), dimEntries, STATE_CACHE_SOFT_CAP,
            )
            cacheCapWarned[dimKey] = true
        } else if (!overCap && previouslyWarned) {
            cacheCapWarned[dimKey] = false
        }
        if (evicted > 0) {
            LOG.debug(
                "WohlonSpreader: evicted {} stale chunk-state entries for dim={} ({} remaining)",
                evicted, dimKey.location(), dimEntries - evicted,
            )
        }
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

    /** Vertical-cascade counterpart of [enqueueCellSpread].
     *  Drains through [drainQueue] on the [verticalSpreadQueue] /
     *  [verticalDrainBudget] pair, independent of the horizontal
     *  random-tick spread. */
    private fun enqueueVerticalSpread(level: ServerLevel, qx: Int, qy: Int, qz: Int) {
        val queue = verticalSpreadQueue.getOrPut(level.dimension()) { LinkedHashSet() }
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
    private fun drainQueue(
        level: ServerLevel,
        count: Int,
        queueMap: MutableMap<ResourceKey<Level>, LinkedHashSet<Long>>,
    ) {
        if (count <= 0) return
        val queue = queueMap[level.dimension()] ?: return
        if (queue.isEmpty()) return
        val wohlonBiome = lookupBiome(level) ?: return
        val touchedChunks = HashSet<ChunkPos>()
        // Each loop turn opens a fresh iterator so [setBiomeCellSilent]'s
        // column cascade (which writes back into [verticalSpreadQueue]
        // when this drain is the vertical one) doesn't trip a
        // ConcurrentModificationException on a held iterator. Cost is
        // one O(1) iterator allocation per drained cell — fine at the
        // configured drain rates.
        var drained = 0
        while (drained < count) {
            val iter = queue.iterator()
            if (!iter.hasNext()) break
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

    /** Pick one unloaded chunk and force-load it (vanilla
     *  `getChunk` is synchronous), giving its biome-spread + block-
     *  conversion logic a turn even when no player is nearby. We
     *  don't explicitly unload — vanilla's chunk-keep-alive will
     *  drop the chunk again on its normal cadence.
     *
     *  Phase 4: prefer unloaded chunks face-adjacent to a chunk
     *  in [activeFrontierChunks]. Those are exactly the chunks
     *  the spread sampler is enqueuing cells into right now — the
     *  drain currently no-ops on them because the chunk is
     *  unloaded; force-loading lets the queued cells actually
     *  flip. Falls back to uniform random pick from tainted
     *  unloaded chunks when no frontier-adjacent candidates exist
     *  (cold start before the spread has built a frontier, or a
     *  dim where the only Wohlon is interior). */
    private fun tryForceLoadTaintedChunk(level: ServerLevel) {
        val data = getTaintedData(level)
        if (data.positions.isEmpty()) return
        val chunkSource = level.chunkSource
        val random = level.random

        // Primary: frontier-adjacent unloaded picks.
        val active = activeFrontierChunks[level.dimension()]
        if (active != null && active.isNotEmpty()) {
            val adjacent = collectFrontierAdjacentUnloaded(active, chunkSource)
            if (adjacent.isNotEmpty()) {
                val pick = adjacent[random.nextInt(adjacent.size)]
                val pos = ChunkPos(pick)
                level.getChunk(pos.x, pos.z)
                LOG.debug(
                    "WohlonSpread: force-loaded frontier-adjacent chunk {} in {}",
                    pos, level.dimension().location(),
                )
                return
            }
        }

        // Fallback: uniform random pick from unloaded tainted
        // chunks (the original behaviour, kept for cold-start and
        // no-frontier cases).
        val unloaded = ArrayList<Long>()
        for (packed in data.positions) {
            val pos = ChunkPos(packed)
            if (chunkSource.getChunkNow(pos.x, pos.z) == null) unloaded.add(packed)
        }
        if (unloaded.isEmpty()) return
        val pick = unloaded[random.nextInt(unloaded.size)]
        val pos = ChunkPos(pick)
        // Touching the chunk via getChunk pulls it through the
        // chunkgen pipeline (or loads from disk) to ChunkStatus.FULL
        // and registers it as loaded for subsequent ticks.
        level.getChunk(pos.x, pos.z)
        LOG.debug(
            "WohlonSpread: force-loaded tainted chunk {} (uniform fallback) in {}",
            pos, level.dimension().location(),
        )
    }

    /** Walk the active frontier chunk set and collect every
     *  face-adjacent neighbour (4 horizontal sides) whose chunk
     *  isn't loaded. These are the "spread is enqueuing into me
     *  but I'm not loaded" candidates — force-loading any of them
     *  immediately lets the next drain tick make progress.
     *
     *  Returns a dedup'd snapshot — the same neighbour can be
     *  adjacent to several frontier chunks; the `HashSet` collapses
     *  those into one entry so the uniform random pick at the call
     *  site doesn't over-weight intersections.
     *
     *  Cost: O(|activeFrontierChunks| × 4) hash lookups, ≤ a few
     *  thousand operations even on a mature biome. Allocates one
     *  `HashSet<Long>` + one `LongArray` per force-load; force-load
     *  runs ≤ once per hour by default, so the allocation rate is
     *  negligible. */
    private fun collectFrontierAdjacentUnloaded(
        active: MutableSet<Long>,
        chunkSource: net.minecraft.server.level.ServerChunkCache,
    ): LongArray {
        val seen = HashSet<Long>()
        // Snapshot first — `active` is a live set that
        // `updateFrontierForFlip` may mutate from the same tick.
        val snapshot = active.toLongArray()
        for (chunkLong in snapshot) {
            val cx = ChunkPos.getX(chunkLong)
            val cz = ChunkPos.getZ(chunkLong)
            // 4 face-adjacent chunk positions (horizontal). Spread
            // happens in 3D but chunks are columns, so face-adjacent
            // for chunk-load purposes is only horizontal.
            val n1 = ChunkPos.asLong(cx - 1, cz)
            val n2 = ChunkPos.asLong(cx + 1, cz)
            val n3 = ChunkPos.asLong(cx, cz - 1)
            val n4 = ChunkPos.asLong(cx, cz + 1)
            if (chunkSource.getChunkNow(cx - 1, cz) == null) seen.add(n1)
            if (chunkSource.getChunkNow(cx + 1, cz) == null) seen.add(n2)
            if (chunkSource.getChunkNow(cx, cz - 1) == null) seen.add(n3)
            if (chunkSource.getChunkNow(cx, cz + 1) == null) seen.add(n4)
        }
        return seen.toLongArray()
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
            // Wohlon dim has no frontier (every cell is Wohlon),
            // so pass null — the in-cell conversion path treats
            // null as "all interior" and converts at full rate.
            tickChunkConversions(level, chunk, ALL_SECTIONS_MASK, effectiveTickRate, random, mutPos, null)
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

        val gameTime = level.gameTime
        for (packed in taintedSnapshot) {
            val pos = ChunkPos(packed)
            val chunk = chunkSource.getChunkNow(pos.x, pos.z) ?: continue
            val mask = sectionMaskFor(level, chunk, dimHash)
            if (mask == 0L) continue

            // Phase 3: per-chunk tier gate. Chunks bucket into
            // hot / warm / cold based on elapsed time since last
            // activity; warm and cold tick on a slower cadence
            // (every 10 or every 1200 ticks), staggered by a
            // per-chunk phase so the whole tier doesn't fire on
            // the same tick. A freshly-created state with
            // `lastActivityGameTime == 0L` is bootstrapped to the
            // current gameTime so it doesn't get demoted to cold
            // by zero-vs-large math on its first appearance.
            val key = chunkCacheKey(dimHash, packed)
            val state = chunkStates[key]
            if (state != null && state.lastActivityGameTime == 0L) {
                state.lastActivityGameTime = gameTime
            }
            val lastActivity = state?.lastActivityGameTime ?: gameTime
            val elapsed = gameTime - lastActivity
            val interval = when {
                elapsed < HOT_THRESHOLD_TICKS -> HOT_TICK_INTERVAL
                elapsed < WARM_THRESHOLD_TICKS -> WARM_TICK_INTERVAL
                else -> COLD_TICK_INTERVAL
            }
            if (interval > 1L) {
                // Stagger same-tier chunks across ticks. The phase
                // is a deterministic hash of the chunk pos so a
                // single chunk's tick schedule is stable across
                // ticks (no drifting).
                val phase = (packed xor (packed ushr 32)).let { it - (it / interval) * interval }
                if (((gameTime % interval) + interval) % interval != phase) continue
            }

            val converted = tickChunkConversions(
                level, chunk, mask, effectiveTickRate, random, mutPos,
                // Visual de-grid #4: pass the chunk's frontier
                // bitmask so the in-cell conversion path can
                // distinguish frontier cells (noise-gated rate)
                // from interior cells (full rate). May be null —
                // [tickChunkConversions] treats null as "all
                // interior" and skips the gate entirely.
                state?.frontierBits,
            )
            if (converted > 0) {
                // Promote back to hot via the standard activity
                // stamp. computeIfAbsent because state may be
                // null here if sectionMaskFor didn't materialise
                // one (unlikely — mask != 0 implies state exists
                // — but harmless to be safe).
                markChunkActivity(level, pos)
            }
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
     *  Per random tick on a block in a Wohlon biome cell:
     *
     *   1. **Source-block conversion** — if the source block is
     *      non-air and in a `converts_to_*` tag (dirt → mud,
     *      oak_log → wogor_log, etc.), it converts. Independent
     *      of the spread step. Air sources skip this branch.
     *
     *   2. **trySpread** — pick one of the 6 cardinal block
     *      neighbours, enqueue its biome cell for the drain if
     *      it isn't already Wohlon. Runs regardless of the source
     *      block type so that airborne Wohlon cells (sky columns
     *      above the surface, open cave volumes inside the
     *      footprint) still expand the biome.
     *
     *  Sources in non-Wohlon cells route to the boundary-block
     *  conversion branch instead — it requires a non-air source
     *  since you can't convert air to anything. */
    private fun tickChunkConversions(
        level: ServerLevel,
        chunk: LevelChunk,
        sectionMask: Long,
        randomTickSpeed: Int,
        random: net.minecraft.util.RandomSource,
        mutPos: BlockPos.MutableBlockPos,
        frontierBits: LongArray?,
    ): Int {
        val minSection = chunk.minSection
        val sections = chunk.sections
        val chunkBaseX = chunk.pos.x shl 4
        val chunkBaseZ = chunk.pos.z shl 4
        val chunkSource = level.chunkSource
        // Phase 3: caller stamps activity (and thus tier promotion
        // to hot) only if at least one conversion fired; the return
        // value is that count.
        var converted = 0
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
                val cell = section.getNoiseBiome(lx shr 2, ly shr 2, lz shr 2)
                if (!isWohlon(cell)) {
                    // Visual de-grid #5 — per-block distance-banded
                    // fringe. Conversion rate is keyed on the
                    // block-level Manhattan distance from this
                    // block to the nearest block in a Wohlon cell.
                    //
                    // For a block inside a non-Wohlon cell, the
                    // nearest Wohlon block lives across one of the
                    // 6 cell-face boundaries that touch a Wohlon
                    // cell. Distance via a given face equals the
                    // block's local coordinate on that axis plus 1
                    // (for the negative face) or 4 minus the local
                    // (for the positive face) — see the comment on
                    // [BOUNDARY_TIER1_RATE] for the visual rationale.
                    if (state.isAir) return@repeat
                    val sourceTarget = pickConversion(state) ?: return@repeat
                    val bx = chunkBaseX + lx
                    val by = sectionBaseY + ly
                    val bz = chunkBaseZ + lz
                    val cellQx = bx shr 2
                    val cellQy = by shr 2
                    val cellQz = bz shr 2
                    val localX = bx and 3
                    val localY = by and 3
                    val localZ = bz and 3
                    var minDist = Int.MAX_VALUE
                    if (isWohlon(level.getNoiseBiome(cellQx - 1, cellQy, cellQz))) {
                        if (localX + 1 < minDist) minDist = localX + 1
                    }
                    if (isWohlon(level.getNoiseBiome(cellQx + 1, cellQy, cellQz))) {
                        if (4 - localX < minDist) minDist = 4 - localX
                    }
                    if (isWohlon(level.getNoiseBiome(cellQx, cellQy - 1, cellQz))) {
                        if (localY + 1 < minDist) minDist = localY + 1
                    }
                    if (isWohlon(level.getNoiseBiome(cellQx, cellQy + 1, cellQz))) {
                        if (4 - localY < minDist) minDist = 4 - localY
                    }
                    if (isWohlon(level.getNoiseBiome(cellQx, cellQy, cellQz - 1))) {
                        if (localZ + 1 < minDist) minDist = localZ + 1
                    }
                    if (isWohlon(level.getNoiseBiome(cellQx, cellQy, cellQz + 1))) {
                        if (4 - localZ < minDist) minDist = 4 - localZ
                    }
                    // Outside the fringe band — skip. Bulk of
                    // non-Wohlon ticks fall through here.
                    if (minDist > 2) return@repeat
                    val baseRate = if (minDist == 1) BOUNDARY_TIER1_RATE else BOUNDARY_TIER2_RATE
                    // Noise gate keeps the band organic. Without
                    // it, every tier-1 block on a flat face would
                    // convert at exactly the same rate and the
                    // band would read as a paint stripe along the
                    // cell boundary.
                    val blockNoise = SPREAD_BOUNDARY_NOISE.noise(
                        bx.toDouble() * BLOCK_NOISE_FREQUENCY,
                        by.toDouble() * BLOCK_NOISE_FREQUENCY,
                        bz.toDouble() * BLOCK_NOISE_FREQUENCY,
                        0.0, 0.0,
                    )
                    if (blockNoise < BLOCK_NOISE_THRESHOLD) return@repeat
                    val rate = (baseRate * spreadSpeed(level)).coerceAtMost(1.0)
                    if (random.nextDouble() >= rate) return@repeat
                    mutPos.set(bx, by, bz)
                    level.setBlock(mutPos, sourceTarget, 2)
                    converted++
                    return@repeat
                }

                // Convert the source block if it's in a converts_to_*
                // tag. Independent of spread. Wohlon cells convert
                // at full rate so the biome interior reads as
                // uniformly painted — the boundary gradient lives
                // *outside* the Wohlon line via the k-scaled fringe
                // in the non-Wohlon branch above, not by subtracting
                // density from this branch. Air sources skip the
                // conversion path entirely.
                if (!state.isAir) {
                    val sourceTarget = pickConversion(state)
                    if (sourceTarget != null) {
                        mutPos.set(chunkBaseX + lx, sectionBaseY + ly, chunkBaseZ + lz)
                        converted++
                        // Flag 2 (UPDATE_CLIENTS only): no neighbour-
                        // notify chain — both blocks are inert solids.
                        level.setBlock(mutPos, sourceTarget, 2)
                    }
                }

                // Phase 2 removed the per-random-tick spread
                // enqueue that used to live here. The spread step
                // is now [tickFrontierSpread], which samples
                // directly from the per-chunk frontier bitmask in
                // [ChunkSpreadState] — cost scales with the
                // boundary cell count, not the tainted volume.
            }
        }
        return converted
    }

    /** Get-or-compute the per-chunk section bitmask. A `0L` entry
     *  means "no Wohlon cells anywhere in this chunk" and the
     *  conversion loop short-circuits. The cache is invalidated
     *  on biome spread via [invalidateSectionMask]. */
    private fun sectionMaskFor(level: ServerLevel, chunk: LevelChunk, dimHash: Int): Long {
        val key = chunkCacheKey(dimHash, chunk.pos.toLong())
        val existing = chunkStates[key]
        if (existing != null) {
            // Phase 5: state may have been created by
            // [bumpSectionMask] with only one frontier bit set (the
            // just-flipped cell). Re-scan now so any pre-existing
            // Wohlon cells the flip path didn't touch get their
            // frontier bits. Idempotent: subsequent calls short-
            // circuit on the `frontierPopulated` flag.
            if (!existing.frontierPopulated && existing.sectionMask != 0L) {
                populateFrontier(level, chunk, existing)
            }
            return existing.sectionMask
        }
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
        // Race-free even though sectionMask is non-final on the
        // class: concurrent computers either both write the same
        // mask (same chunk content) or the second-arriving one
        // overwrites with the correct mask anyway.
        val state = chunkStates.computeIfAbsent(key) { ChunkSpreadState() }
        state.sectionMask = mask
        if (mask != 0L && !state.frontierPopulated) {
            populateFrontier(level, chunk, state)
        }
        return mask
    }

    /** Phase 5: walk every Wohlon biome cell in [chunk] and set
     *  its frontier bit if at least one face-adjacent neighbour
     *  is non-Wohlon. Called from [sectionMaskFor] the first time
     *  a `ChunkSpreadState` is encountered with `sectionMask != 0`
     *  but `frontierPopulated == false`.
     *
     *  Without this, a chunk that loads with already-tainted
     *  biome (saved from a previous session, or unloaded long
     *  enough for [evictUnloadedChunkStates] to drop its state)
     *  would have an empty frontier mask, never enter
     *  [activeFrontierChunks], and never get picked by
     *  [tickFrontierSpread] — spread silently stalls.
     *
     *  Idempotent — the per-bit set checks `(bits[idx] and mask) == 0L`
     *  so any frontier bits already set by [updateFrontierForFlip]
     *  (the incremental-flip path) aren't double-counted.
     *
     *  Worst-case cost: 1536 biome reads + up to 1536 × 6 neighbour
     *  reads ≈ 10 000 reads per chunk on first scan. One-time per
     *  chunk per session; tens of microseconds even on a
     *  fully-Wohlon chunk. */
    private fun populateFrontier(level: ServerLevel, chunk: LevelChunk, state: ChunkSpreadState) {
        val mask = state.sectionMask
        if (mask == 0L) {
            state.frontierPopulated = true
            return
        }
        val sections = chunk.sections
        val chunkBaseQx = chunk.pos.x shl 2
        val chunkBaseQz = chunk.pos.z shl 2
        var bits = state.frontierBits
        var count = state.frontierCount
        for (idx in sections.indices) {
            if ((mask ushr idx) and 1L == 0L) continue
            val section = sections[idx]
            val sectionWorldQy = (chunk.minSection + idx) shl 2
            for (qy in 0..3) for (qx in 0..3) for (qz in 0..3) {
                if (!isWohlon(section.getNoiseBiome(qx, qy, qz))) continue
                val qxW = chunkBaseQx or qx
                val qyW = sectionWorldQy or qy
                val qzW = chunkBaseQz or qz
                if (!hasNonWohlonNeighbor(level, qxW, qyW, qzW)) continue
                if (bits == null) {
                    bits = LongArray(24)
                    state.frontierBits = bits
                }
                val bit = frontierBitMask(qxW, qyW, qzW)
                if ((bits[idx] and bit) == 0L) {
                    bits[idx] = bits[idx] or bit
                    count++
                }
            }
        }
        state.frontierCount = count
        state.frontierPopulated = true
        if (count > 0) {
            activeFrontierChunks.computeIfAbsent(level.dimension()) { HashSet() }.add(chunk.pos.toLong())
        }
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
        val state = chunkStates.computeIfAbsent(key) { ChunkSpreadState() }
        // The mask is OR-only — Wohlon biome never retracts — so a
        // racing concurrent OR is idempotent. No need for a lock or
        // an atomic write.
        state.sectionMask = state.sectionMask or bit
    }

    /** Phase 3: stamp the current gameTime onto the chunk's
     *  [ChunkSpreadState.lastActivityGameTime]. Called from
     *  paths that flip a biome cell or convert a block — i.e.
     *  any moment that should keep the chunk in the hot tier.
     *  Also used to bootstrap a freshly-created state with a
     *  non-zero timestamp so the tier check doesn't immediately
     *  demote brand-new chunks to cold.
     *
     *  Cheap: one hash lookup, one volatile-long write. No
     *  allocation on the common path. */
    private fun markChunkActivity(level: ServerLevel, chunkPos: ChunkPos) {
        val dimHash = level.dimension().location().hashCode()
        val key = chunkCacheKey(dimHash, chunkPos.toLong())
        val state = chunkStates.computeIfAbsent(key) { ChunkSpreadState() }
        state.lastActivityGameTime = level.gameTime
    }

    // ---------------- Frontier maintenance (Phase 2) ---------------

    /** The 6 face-adjacent biome-cell offsets. Cached so we don't
     *  allocate a fresh array per [updateFrontierForFlip] call —
     *  this runs hundreds of times per second during a seed job. */
    private val FRONTIER_NEIGHBOR_OFFSETS: IntArray = intArrayOf(
        -1, 0, 0,   1, 0, 0,
        0, -1, 0,   0, 1, 0,
        0, 0, -1,   0, 0, 1,
    )

    /** Encode a biome cell's local position within its chunk's
     *  [ChunkSpreadState.frontierBits] long for the cell's section.
     *  The long index is the section index in the chunk; the bit
     *  within that long is `(qy_local << 4) | (qx_local << 2) | qz_local`.
     *  Returns `1L shl bitInLong`. */
    private fun frontierBitMask(qxWorld: Int, qyWorld: Int, qzWorld: Int): Long {
        val qyLocal = qyWorld and 3
        val qxLocal = qxWorld and 3
        val qzLocal = qzWorld and 3
        val bitInLong = (qyLocal shl 4) or (qxLocal shl 2) or qzLocal
        return 1L shl bitInLong
    }

    /** Read the biome cell at world quarter-coords `(qx, qy, qz)`
     *  without forcing a chunk load. Returns `null` if the owning
     *  chunk isn't loaded or the Y is outside the chunk's section
     *  range — both states treated as "unknown, assume non-Wohlon"
     *  by [hasNonWohlonNeighbor] (we conservatively keep the
     *  frontier bit set rather than risk losing the cell). */
    private fun getBiomeCellNonLoading(level: ServerLevel, qx: Int, qy: Int, qz: Int): Holder<Biome>? {
        val chunk = level.chunkSource.getChunkNow(qx shr 2, qz shr 2) ?: return null
        val sectionIdx = (qy shr 2) - chunk.minSection
        if (sectionIdx < 0 || sectionIdx >= chunk.sections.size) return null
        return chunk.sections[sectionIdx].getNoiseBiome(qx and 3, qy and 3, qz and 3)
    }

    /** True iff any of the 6 face-adjacent biome cells of
     *  `(qx, qy, qz)` is non-Wohlon (or in an unloaded chunk —
     *  conservatively treated as non-Wohlon so a chunk-load race
     *  doesn't strand a frontier cell as "interior" by mistake). */
    private fun hasNonWohlonNeighbor(level: ServerLevel, qx: Int, qy: Int, qz: Int): Boolean {
        var i = 0
        while (i < FRONTIER_NEIGHBOR_OFFSETS.size) {
            val dx = FRONTIER_NEIGHBOR_OFFSETS[i]
            val dy = FRONTIER_NEIGHBOR_OFFSETS[i + 1]
            val dz = FRONTIER_NEIGHBOR_OFFSETS[i + 2]
            val neighbor = getBiomeCellNonLoading(level, qx + dx, qy + dy, qz + dz)
            if (neighbor == null || !isWohlon(neighbor)) return true
            i += 3
        }
        return false
    }

    /** Set a frontier bit on a chunk's [ChunkSpreadState], lazily
     *  allocating the bitmask array on first use. Increments
     *  `frontierCount` and adds the chunk to
     *  [activeFrontierChunks] on rising-edge transitions (count
     *  went from 0 to 1). Idempotent — re-setting an already-set
     *  bit is a no-op. */
    private fun setFrontierBit(
        dimKey: ResourceKey<Level>, state: ChunkSpreadState,
        chunkLong: Long, sectionIdx: Int, mask: Long,
    ) {
        if (sectionIdx < 0 || sectionIdx >= 24) return
        var bits = state.frontierBits
        if (bits == null) {
            bits = LongArray(24)
            state.frontierBits = bits
        }
        if ((bits[sectionIdx] and mask) != 0L) return
        bits[sectionIdx] = bits[sectionIdx] or mask
        state.frontierCount++
        if (state.frontierCount == 1) {
            activeFrontierChunks.computeIfAbsent(dimKey) { HashSet() }.add(chunkLong)
        }
    }

    /** Inverse of [setFrontierBit] — clears a bit and decrements
     *  the count. On falling-edge (count → 0) removes the chunk
     *  from [activeFrontierChunks]. Idempotent on already-clear
     *  bits. */
    private fun clearFrontierBit(
        dimKey: ResourceKey<Level>, state: ChunkSpreadState,
        chunkLong: Long, sectionIdx: Int, mask: Long,
    ) {
        if (sectionIdx < 0 || sectionIdx >= 24) return
        val bits = state.frontierBits ?: return
        if ((bits[sectionIdx] and mask) == 0L) return
        bits[sectionIdx] = bits[sectionIdx] and mask.inv()
        state.frontierCount--
        if (state.frontierCount == 0) {
            activeFrontierChunks[dimKey]?.remove(chunkLong)
        }
    }

    /** Update the frontier bitmasks of the just-flipped cell *and*
     *  its six previously-Wohlon neighbours. Called from
     *  [setBiomeCellTo] and [setBiomeCellSilent] after the biome
     *  write succeeds.
     *
     *  Two-part maintenance:
     *   - **Self**: the new Wohlon cell joins the frontier iff
     *     it has at least one non-Wohlon neighbour.
     *   - **Each Wohlon neighbour**: may have just lost its last
     *     non-Wohlon side. Re-check; clear its frontier bit if so.
     *
     *  Worst-case cost per flip: 1 self-check + 6 neighbour-checks
     *  × 6 sub-neighbour reads = ~43 biome reads. At typical
     *  spread rates (≤ 20/s after seed) this is well under 1 ms/s. */
    private fun updateFrontierForFlip(level: ServerLevel, qx: Int, qy: Int, qz: Int) {
        val dimKey = level.dimension()
        val dimHash = dimKey.location().hashCode()

        // Self
        val selfChunk = level.chunkSource.getChunkNow(qx shr 2, qz shr 2) ?: return
        val selfSection = (qy shr 2) - selfChunk.minSection
        if (selfSection < 0 || selfSection >= selfChunk.sections.size) return
        val selfMask = frontierBitMask(qx, qy, qz)
        val selfKey = chunkCacheKey(dimHash, selfChunk.pos.toLong())
        val selfState = chunkStates.computeIfAbsent(selfKey) { ChunkSpreadState() }
        if (hasNonWohlonNeighbor(level, qx, qy, qz)) {
            setFrontierBit(dimKey, selfState, selfChunk.pos.toLong(), selfSection, selfMask)
        } else {
            clearFrontierBit(dimKey, selfState, selfChunk.pos.toLong(), selfSection, selfMask)
        }

        // Neighbours that were already Wohlon may have just become
        // fully surrounded — recheck and clear if so. Non-Wohlon
        // neighbours don't have frontier bits set (they're not
        // Wohlon), so we skip them.
        var i = 0
        while (i < FRONTIER_NEIGHBOR_OFFSETS.size) {
            val dx = FRONTIER_NEIGHBOR_OFFSETS[i]
            val dy = FRONTIER_NEIGHBOR_OFFSETS[i + 1]
            val dz = FRONTIER_NEIGHBOR_OFFSETS[i + 2]
            val nqx = qx + dx
            val nqy = qy + dy
            val nqz = qz + dz
            i += 3
            val neighbor = getBiomeCellNonLoading(level, nqx, nqy, nqz) ?: continue
            if (!isWohlon(neighbor)) continue
            val nChunk = level.chunkSource.getChunkNow(nqx shr 2, nqz shr 2) ?: continue
            val nSection = (nqy shr 2) - nChunk.minSection
            if (nSection < 0 || nSection >= nChunk.sections.size) continue
            val nMask = frontierBitMask(nqx, nqy, nqz)
            val nKey = chunkCacheKey(dimHash, nChunk.pos.toLong())
            val nState = chunkStates[nKey] ?: continue
            val nBits = nState.frontierBits ?: continue
            if ((nBits[nSection] and nMask) == 0L) continue
            if (!hasNonWohlonNeighbor(level, nqx, nqy, nqz)) {
                clearFrontierBit(dimKey, nState, nChunk.pos.toLong(), nSection, nMask)
            }
        }
    }

    // ---------------- Frontier-driven spread sampler (Phase 2) ----

    /** How many spread samples to attempt per tick. Each sample
     *  picks a random frontier cell and tries to enqueue one
     *  random non-Wohlon neighbour for conversion. Set to track
     *  the [SPREAD_DRAIN_PER_SEC] downstream throughput at typical
     *  hit-rate (~50 % of picks land on a non-Wohlon neighbour);
     *  the spread queue absorbs the difference and the drain
     *  meters everything out at the bounded rate. Multiplied by
     *  the `wohlonSpreadSpeed` gamerule so the front advances
     *  faster at higher speeds. */
    private const val FRONTIER_SAMPLES_PER_TICK_BASE: Int = 2

    // ---------------- Tiering thresholds (Phase 3) -----------------

    /** Ticks-since-last-activity boundary between hot and warm
     *  tiers. 200 ticks ≈ 10 s — long enough that a chunk that
     *  just got a conversion stays hot through a few follow-up
     *  ticks; short enough that a chunk where nothing's happening
     *  drops to warm within a single in-game minute. */
    private const val HOT_THRESHOLD_TICKS: Long = 200L

    /** Ticks-since-last-activity boundary between warm and cold
     *  tiers. 12 000 ticks = 10 minutes of game time. Chunks past
     *  this are assumed to be saturated (all their convertible
     *  source blocks have already been flipped) and tick at
     *  the cold cadence. */
    private const val WARM_THRESHOLD_TICKS: Long = 12_000L

    /** Per-tier tick intervals. Cold ticks every 1200 ticks = 1
     *  minute — rare enough that the cost reduction is real,
     *  frequent enough that a player who places a new convertible
     *  block in a saturated chunk doesn't wait more than a minute
     *  for the conversion. */
    private const val HOT_TICK_INTERVAL: Long = 1L
    private const val WARM_TICK_INTERVAL: Long = 10L
    private const val COLD_TICK_INTERVAL: Long = 1200L

    /** Pick `FRONTIER_SAMPLES_PER_TICK_BASE × spreadSpeed` random
     *  frontier cells from [activeFrontierChunks] and enqueue a
     *  random non-Wohlon neighbour for each. Cost per tick:
     *   - O(|activeFrontierChunks|) for the `toLongArray()`
     *     snapshot (a few hundred entries on a mature biome).
     *   - O(24) per sample for the bit pick within a chunk.
     *   - 6 `getBiomeCellNonLoading` reads per sample for the
     *     neighbour direction roll.
     *
     *  Replaces the old random-tick spread enqueue inside
     *  [tickChunkConversions] — that path scanned every loaded
     *  tainted chunk and was the dominant `O(volume)` cost as the
     *  biome grew. */
    private fun tickFrontierSpread(level: ServerLevel) {
        val dimKey = level.dimension()
        val active = activeFrontierChunks[dimKey] ?: return
        if (active.isEmpty()) return
        val samples = FRONTIER_SAMPLES_PER_TICK_BASE * spreadSpeed(level)
        if (samples <= 0) return
        val dimHash = dimKey.location().hashCode()
        val random = level.random
        // Snapshot the active set so concurrent mutations from a
        // spread enqueue don't trip us mid-iteration. The set's
        // typical size is the boundary cell count divided by
        // (boundary cells per chunk) — a few hundred at most for
        // even a r=500 biome.
        val snapshot = active.toLongArray()
        if (snapshot.isEmpty()) return

        repeat(samples) {
            val chunkLong = snapshot[random.nextInt(snapshot.size)]
            val key = chunkCacheKey(dimHash, chunkLong)
            val state = chunkStates[key] ?: return@repeat
            val bits = state.frontierBits ?: return@repeat
            val count = state.frontierCount
            if (count <= 0) return@repeat
            // Pick a random set bit, weighted by per-long popcount.
            var target = random.nextInt(count)
            var sectionIdx = -1
            var bitInLong = -1
            for (s in 0 until 24) {
                val popCount = java.lang.Long.bitCount(bits[s])
                if (target < popCount) {
                    // Walk to the `target`-th set bit in this long.
                    var w = bits[s]
                    var remaining = target
                    while (remaining > 0) {
                        w = w and (w - 1L)
                        remaining--
                    }
                    bitInLong = java.lang.Long.numberOfTrailingZeros(w)
                    sectionIdx = s
                    break
                }
                target -= popCount
            }
            if (sectionIdx < 0) return@repeat

            // Decode (sectionIdx, bitInLong) → world quarter-coords.
            val cPos = ChunkPos(chunkLong)
            val chunk = level.chunkSource.getChunkNow(cPos.x, cPos.z) ?: return@repeat
            val sectionWorldY = (chunk.minSection + sectionIdx) shl 2
            val qyLocal = (bitInLong shr 4) and 3
            val qxLocal = (bitInLong shr 2) and 3
            val qzLocal = bitInLong and 3
            val qx = (cPos.x shl 2) or qxLocal
            val qy = sectionWorldY or qyLocal
            val qz = (cPos.z shl 2) or qzLocal

            // Visual de-grid #2: pick a neighbour from the
            // 26-direction set with face / edge / vertex weighting
            // (70 / 20 / 10 %). The result is a spread front that
            // doesn't align to the three orthogonal planes, so
            // boundaries stop reading as Minecraft-cell-shaped.
            val tier = random.nextInt(10)
            val dx: Int; val dy: Int; val dz: Int
            when {
                tier < 7 -> {
                    val i = random.nextInt(6) * 3
                    dx = FACE_OFFSETS[i]; dy = FACE_OFFSETS[i + 1]; dz = FACE_OFFSETS[i + 2]
                }
                tier < 9 -> {
                    val i = random.nextInt(12) * 3
                    dx = EDGE_OFFSETS[i]; dy = EDGE_OFFSETS[i + 1]; dz = EDGE_OFFSETS[i + 2]
                }
                else -> {
                    val i = random.nextInt(8) * 3
                    dx = VERTEX_OFFSETS[i]; dy = VERTEX_OFFSETS[i + 1]; dz = VERTEX_OFFSETS[i + 2]
                }
            }
            val nqx = qx + dx
            val nqy = qy + dy
            val nqz = qz + dz
            val neighbor = getBiomeCellNonLoading(level, nqx, nqy, nqz) ?: return@repeat
            if (isWohlon(neighbor)) return@repeat
            // Visual de-grid #1: noise gate. Reject if the noise
            // field at this neighbour's coords is below
            // [SPREAD_NOISE_THRESHOLD]. The same cell will get
            // resampled later (still in the frontier sampler's
            // pool), so this just delays its conversion, weighting
            // spread toward noise-high regions. Boundary follows
            // noise iso-lines rather than the 4-block biome grid.
            val noiseValue = SPREAD_BOUNDARY_NOISE.noise(
                nqx.toDouble() * SPREAD_NOISE_FREQUENCY,
                nqy.toDouble() * SPREAD_NOISE_FREQUENCY,
                nqz.toDouble() * SPREAD_NOISE_FREQUENCY,
                0.0, 0.0,
            )
            if (noiseValue < SPREAD_NOISE_THRESHOLD) return@repeat
            enqueueCellSpread(level, nqx, nqy, nqz)
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
        updateFrontierForFlip(level, qx, qy, qz)
        markChunkActivity(level, chunk.pos)
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
        updateFrontierForFlip(level, qx, qy, qz)
        markChunkActivity(level, chunk.pos)
        // World-root grower trigger — every cell flip. The grower
        // applies an "is this cell surrounded by Wohlon?" gate to
        // pick interior cells over edge cells (so the root anchors
        // inside the biome footprint, not at the spreading boundary)
        // plus its own region-level dedup so we only ever spawn one
        // march per region. Cheap — 4 biome lookups per call, no
        // allocation on the no-trigger path.
        WohlonnogondoniaWorldRootGrower.maybeEnqueueCell(level, qx, qy, qz)

        // Cascade upward through the column above this cell. Goes to
        // the dedicated [verticalSpreadQueue] (paced via
        // [VERTICAL_DRAIN_PER_SEC]) so the bulk column fill doesn't
        // burn the slow horizontal [SPREAD_DRAIN_PER_SEC] budget.
        // Drain naturally skips cells already Wohlon, so column-above
        // adds atop an existing Wohlon tower are cheap no-ops on the
        // dedupe set.
        val maxQy = (level.maxBuildHeight - 1) shr 2
        var above = qy + 1
        while (above <= maxQy) {
            enqueueVerticalSpread(level, qx, above, qz)
            above++
        }
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

