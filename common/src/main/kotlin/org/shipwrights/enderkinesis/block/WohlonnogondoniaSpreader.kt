package org.shipwrights.enderkinesis.block

import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.LongArrayTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.tags.TagKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.mixin.ChunkMapGetChunksAccessor
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKGameRules
import org.shipwrights.enderkinesis.util.ClassWarmup
import org.shipwrights.enderkinesis.worldgen.WohlonWetlandZones
import org.joml.Vector3d
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.isChunkInShipyard
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.concurrent.ConcurrentHashMap
import java.util.function.LongConsumer
import kotlin.math.max
import org.valkyrienskies.core.api.util.GameTickOnly

/**
 * Wohlon biome spreader — four wired features:
 *
 *  1. Spread: every [SPREAD_INTERVAL_TICKS], one Wohlon cell per loaded chunk converts a
 *     random face-or-vertex neighbour (14-neighbourhood: 6 faces + 8 vertices, skipping
 *     the 12 edge midpoints of the 26-neighbourhood).
 *  2. Force-load: per-dim [TaintedChunkData] tracks every chunk that has ever held Wohlon
 *     biome; every in-game hour (1000 ticks) one unloaded tainted chunk and its neighbours
 *     get a ticket for [FORCE_LOAD_HOLD_TICKS], so spread + block conversion continue
 *     without a nearby player.
 *  3. Conversion tags `converts_to_wogor_wood` / `_mud` / `_wogor_leaves` drive replacement.
 *  4. Random-tick: each tick, `randomTickSpeed` positions per section — if biome cell is
 *     Wohlon and the block matches a converts-to tag, it's replaced.
 *
 * Tainted set grows only — once Wohlon touches a chunk it stays in the economy forever.
 */
@OptIn(GameTickOnly::class)
object WohlonnogondoniaSpreader {

    /** 1 in-game hour = 1000 ticks (vanilla day = 24000 ticks ÷ 24). */
    private const val FORCE_LOAD_INTERVAL_TICKS: Long = 1000L

    /** One load-and-drop tick gives the sampler no time to cross into the chunk. */
    private const val FORCE_LOAD_HOLD_TICKS: Long = 600L

    /** Radius 1 also loads the neighbours, or the taint cannot leave the tainted chunk. */
    private const val FORCE_LOAD_RADIUS: Int = 1

    /** A high `wohlonSpreadSpeed` shortens the interval; this bounds the chunks held at one time. */
    private const val FORCE_LOAD_MAX_HELD: Int = 4

    /** Random draws from the tainted set for each force-load pick, so the cost does not grow with the taint. */
    private const val FORCE_LOAD_PICK_TRIES: Int = 32

    private val FORCE_LOAD_TICKET: TicketType<ChunkPos> = TicketType.create(
        "enderkinesis_wohlon_spread", Comparator.comparingLong(ChunkPos::toLong),
    )

    /** Per-dim chunk → game time of ticket release. Server thread only. */
    private val heldForceLoads: MutableMap<ResourceKey<Level>, Long2LongOpenHashMap> = HashMap()

    private const val TAINTED_DISCOVERY_INTERVAL_TICKS: Long = 20L

    private const val SHIP_TRANSFER_INTERVAL_TICKS: Long = 20L
    private const val SHIP_TRANSFER_SAMPLES: Int = 4

    // Scratch for [tickShipTransfer]. Server thread only.
    private val shipTransferScratch = Vector3d()

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
    private val CONVERTS_TO_WOHLON_UNDERGROWTH: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "converts_to_wohlon_undergrowth"))
    private val CONVERTS_TO_WOHLON_TALL_FERN: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "converts_to_wohlon_tall_fern"))

    /** Hard-stop tag: any block in this set is exempt from every
     *  conversion regardless of which `converts_to_*` tag would have
     *  matched. Lets us keep the broad mod-compat tags like
     *  `#minecraft:mineable/shovel` in [CONVERTS_TO_MUD] (so modded
     *  shovel-mineable ground reliably converts) while explicitly
     *  excluding members of those tags that don't fit the Wohlon
     *  aesthetic — vanilla snow / snow_block / powder_snow are the
     *  initial entries; modpack authors can extend the data file. */
    private val WOHLON_CONVERSION_BLOCKLIST: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "wohlon_conversion_blocklist"))

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
        CONVERTS_TO_MUD to { src -> inheritedState(src, EKBlocks.WOHLON_MUD.get().defaultBlockState()) },
        CONVERTS_TO_WOGOR_LEAVES to { src -> inheritedState(src, EKBlocks.WOGOR_LEAVES.get().defaultBlockState()) },
        CONVERTS_TO_WOHLON_UNDERGROWTH to { src ->
            val fern = java.util.concurrent.ThreadLocalRandom.current().nextFloat() < UNDERGROWTH_FERN_SHARE
            inheritedState(src, (if (fern) EKBlocks.WOHLON_FERN else EKBlocks.WOHLON_GRASS).get().defaultBlockState())
        },
        CONVERTS_TO_WOHLON_TALL_FERN to { src -> inheritedState(src, EKBlocks.WOHLON_TALL_FERN.get().defaultBlockState()) },
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


    /** Share of converted grass that becomes Wohlon fern; the rest becomes Wohlon grass. */
    private const val UNDERGROWTH_FERN_SHARE: Float = 0.5f

    // Ecology of the spread: each frontier candidate is accepted with the product of these factors.
    // The floor keeps every cell reachable: the infection slows, but it never stops.
    private const val ACCEPT_FLOOR = 0.05f
    private const val DRY_TARGET = 0.55f
    private const val UPHILL = 0.45f
    private const val LEVEL_GROUND = 0.85f
    private const val FROZEN = 0.35f
    private const val ARID = 0.4f
    // Blocks above sea level where high ground starts to resist, and resists more.
    private const val HIGH_GROUND = 48
    private const val HIGHER_GROUND = 96
    private const val HIGH_FACTOR = 0.6f
    private const val HIGHER_FACTOR = 0.35f
    // Biome base temperature under which the ground is frozen.
    private const val FROZEN_TEMPERATURE = 0.15f
    // Water to water: always taken level or downstream, slower upstream; then a hop along the water run.
    private const val UPSTREAM = 0.6f
    private const val WATER_HOP_MAX = 3

    // Succession at the front: chance per random tick on a frontier cell that a pioneer plant takes a free spot.
    private const val PIONEER_CHANCE = 0.12
    // Blocks of depth into the swamp: ground shallower than DAMP_DEPTH keeps its front stage, and
    // pioneer plants grow shallower than PIONEER_DEPTH. DEPTH_NOISE moves both edges by up to that much.
    private const val DAMP_DEPTH = 3.0
    private const val PIONEER_DEPTH = 4.0
    private const val DEPTH_FAR = 8.0
    private const val DEPTH_NOISE = 1.5
    private const val DEPTH_NOISE_FREQUENCY = 0.21
    private const val ROLL_REED = 0x5EED
    private const val ROLL_DUCKWEED = 0xD0C3
    private const val ROLL_LILY = 0x1117
    private const val ROLL_LITTER = 0x1177

    /** Total ticks the portal-creation seed spreads over. 300
     *  ticks = 15 s at 20 tps. */
    private const val SEED_TOTAL_TICKS: Int = 300

    /** Block-radius of the portal-creation seed sphere. The
     *  sphere is sampled in block-space then snapped to biome
     *  quarter-cells (4 blocks per cell) so we touch roughly
     *  (4/3)π(16/4)³ ≈ 270 unique biome cells around the portal. */
    private const val SEED_RADIUS_BLOCKS: Int = 16

    /** Ritual tree spread: blocks around the tree's centre line, and past its top and bottom. */
    private const val TREE_SPREAD_RADIUS: Int = 48

    /** The ritual tree spread fills its ellipsoid in 3 s. */
    private const val TREE_SPREAD_TICKS: Int = 60

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
     *  Chunks leave when `frontierCount` returns to 0 or on eviction. */
    private val activeFrontierChunks: ConcurrentHashMap<ResourceKey<Level>, IndexedLongSet> =
        ConcurrentHashMap()

    /** Per-dim runtime cache, keyed by `ChunkPos.asLong`. Holds loaded chunks only:
     *  [evictUnloadedChunkStates] drops the others, and a dropped entry rebuilds lazily. */
    private val chunkStates: ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<Long, ChunkSpreadState>> =
        ConcurrentHashMap()

    private fun statesFor(dim: ResourceKey<Level>): ConcurrentHashMap<Long, ChunkSpreadState> =
        chunkStates.computeIfAbsent(dim) { ConcurrentHashMap() }

    /** How often to sweep [chunkStates] and evict entries whose
     *  chunks are no longer loaded. 600 ticks = 30 s — eviction
     *  doesn't need to be quick, just steady. Cost: one
     *  `getChunkNow` probe per entry, hash lookups in chunk source. */
    private const val STATE_EVICTION_INTERVAL_TICKS: Long = 600L

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

    /** Per-dimension batched biome-resync queue. Each biome write adds its chunk, and the
     *  [BROADCAST_FLUSH_INTERVAL_TICKS] beat sends one biome packet for each chunk. Server
     *  thread only. */
    private val pendingResyncs: MutableMap<ResourceKey<Level>, LongOpenHashSet> = HashMap()

    /** Per-pass dedupe scratch for [setBiomeCellSilent] callers. Server thread only. */
    private val touchedChunksScratch = LongOpenHashSet()
    private val seenCellsScratch = LongOpenHashSet()

    /** Ticks between batched chunk-resync flushes. 20 = once per
     *  second at 20 tps. Visual latency is bounded by this. */
    private const val BROADCAST_FLUSH_INTERVAL_TICKS: Long = 20L

    /** Base per-second cell-flip budget for the queue drain (per
     *  dimension), multiplied by `wohlonSpreadSpeed` in [tickLevel].
     *  The frontier sampler tries `40 × speed` candidates per second,
     *  more than the drain takes; the drain sets the spread rate, and
     *  [SPREAD_QUEUE_MAX] holds the surplus. */
    private const val SPREAD_DRAIN_PER_SEC: Int = 5

    /** The sampler refills a dropped cell later, because the cell stays next to the frontier. */
    private const val SPREAD_QUEUE_MAX: Int = 8192

    /** Pops for each budgeted flip. Cells in unloaded chunks or already Wohlon use no budget. */
    private const val DRAIN_POPS_PER_FLIP: Int = 8

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
     *  [SPREAD_DRAIN_PER_SEC] cells per second. The linked set
     *  gives O(1) add / contains (so duplicate enqueues coalesce)
     *  while preserving insertion order for the drain. Not
     *  persisted — on server restart the queue starts empty and
     *  refills naturally as random-ticks fire. */
    private val spreadQueue: MutableMap<ResourceKey<Level>, LongLinkedOpenHashSet> = HashMap()

    /** Per-dimension FIFO of biome cells waiting to be flipped to
     *  Wohlon via the column cascade (sky cells above a fresh
     *  horizontal flip). Kept distinct from [spreadQueue] so the
     *  paced horizontal-spread budget isn't burned on bulk
     *  vertical fills that don't need pacing. */
    private val verticalSpreadQueue: MutableMap<ResourceKey<Level>, LongLinkedOpenHashSet> = HashMap()

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
            activeFrontierChunks.clear()
            seedJobs.clear()
            pendingResyncs.clear()
            heldForceLoads.clear()
            spreadQueue.clear()
            verticalSpreadQueue.clear()
            spreadDrainBudget.clear()
            verticalDrainBudget.clear()
        }
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
        // Wohlonnogondonia's chunkgen places wogor blocks directly — there's
        // no convertible source for the spreader to ever flip, and every
        // biome cell is already Wohlon. Random-ticking sections here just
        // burned 30% of mspt in pickConversion lookups that always missed.
        if (level.dimension() == Wohlonnogondonia.LEVEL_KEY) return

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

        releaseExpiredForceLoads(level, gameTime)
        if (gameTime % forceLoadInterval == 0L) tryForceLoadTaintedChunk(level, gameTime)
        if (gameTime % SHIP_TRANSFER_INTERVAL_TICKS == 0L) tickShipTransfer(level)
        // Phase 2: spread sampling now reads from the frontier
        // bitmask instead of from random ticks across every
        // tainted chunk. Block conversion still runs through
        // [tickConversionsTainted] below.
        tickFrontierSpread(level)
        if (gameTime % TAINTED_DISCOVERY_INTERVAL_TICKS == 0L) discoverLoadedTainted(level)
        tickConversionsTainted(level)
        if (gameTime % BROADCAST_FLUSH_INTERVAL_TICKS == 0L) flushPendingResyncs(level)
        if (gameTime % STATE_EVICTION_INTERVAL_TICKS == 0L) evictUnloadedChunkStates(level)
    }

    /** Drop the state of each chunk that is no longer loaded, and take it out of the frontier
     *  sampler. Cost: one `getChunkNow` for each entry, each [STATE_EVICTION_INTERVAL_TICKS]. */
    private fun evictUnloadedChunkStates(level: ServerLevel) {
        val dimKey = level.dimension()
        val states = chunkStates[dimKey] ?: return
        val chunkSource = level.chunkSource
        val active = activeFrontierChunks[dimKey]
        val iter = states.keys.iterator()
        while (iter.hasNext()) {
            val chunkLong = iter.next()
            if (chunkSource.getChunkNow(ChunkPos.getX(chunkLong), ChunkPos.getZ(chunkLong)) != null) continue
            iter.remove()
            active?.remove(chunkLong)
        }
    }

    /** Enqueue a biome cell for spread. Idempotent — repeated
     *  calls for the same cell collapse into one queue entry
     *  via the linked set. Called from [tickChunkConversions]
     *  by [tickFrontierSpread] and [tickShipTransfer].
     *
     *  The queue stops at [SPREAD_QUEUE_MAX] entries. */
    private fun enqueueCellSpread(level: ServerLevel, qx: Int, qy: Int, qz: Int) {
        val queue = spreadQueue.getOrPut(level.dimension()) { LongLinkedOpenHashSet() }
        if (queue.size >= SPREAD_QUEUE_MAX) return
        queue.add(BlockPos.asLong(qx, qy, qz))
    }

    /** Vertical-cascade counterpart of [enqueueCellSpread].
     *  Drains through [drainQueue] on the [verticalSpreadQueue] /
     *  [verticalDrainBudget] pair, independent of the horizontal
     *  random-tick spread. */
    private fun enqueueVerticalSpread(level: ServerLevel, qx: Int, qy: Int, qz: Int) {
        val queue = verticalSpreadQueue.getOrPut(level.dimension()) { LongLinkedOpenHashSet() }
        queue.add(BlockPos.asLong(qx, qy, qz))
    }

    /** Drain up to [count] cells off the spread queue and flip
     *  each to Wohlon. Cells that are already Wohlon or in an
     *  unloaded chunk are dropped and don't count against the
     *  budget; the pops stop at `count × [DRAIN_POPS_PER_FLIP]`.
     *
     *  [setBiomeCellSilent] queues the client resync of each touched chunk. */
    private fun drainQueue(
        level: ServerLevel,
        count: Int,
        queueMap: MutableMap<ResourceKey<Level>, LongLinkedOpenHashSet>,
    ) {
        if (count <= 0) return
        val queue = queueMap[level.dimension()] ?: return
        if (queue.isEmpty()) return
        val wohlonBiome = lookupBiome(level) ?: return
        val touchedChunks = touchedChunksScratch
        touchedChunks.clear()
        // No held iterator: the column cascade in [setBiomeCellSilent] adds to the vertical queue.
        var drained = 0
        var popsLeft = count.toLong() * DRAIN_POPS_PER_FLIP
        while (drained < count && popsLeft > 0 && !queue.isEmpty()) {
            popsLeft--
            val packed = queue.removeFirstLong()
            val qx = BlockPos.getX(packed)
            val qy = BlockPos.getY(packed)
            val qz = BlockPos.getZ(packed)
            if (setBiomeCellSilent(level, qx, qy, qz, wohlonBiome, touchedChunks)) {
                drained++
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
    fun convertCellsToWohlon(level: ServerLevel, blockPositions: Collection<BlockPos>) {
        if (blockPositions.isEmpty()) return
        val wohlonBiome = lookupBiome(level) ?: return
        val touchedChunks = touchedChunksScratch
        touchedChunks.clear()
        val seenCells = seenCellsScratch
        seenCells.clear()
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
    }

    /**
     * Spreads over [TREE_SPREAD_TICKS], outward from the centre line, every biome cell in the ellipsoid
     * around a ritual tree: [TREE_SPREAD_RADIUS] around its centre line, and the same distance past its top
     * and bottom. Cells in chunks unloaded when their turn comes are skipped.
     */
    fun spreadAroundTree(level: ServerLevel, centerX: Int, centerZ: Int, bottomY: Int, topY: Int) {
        require(topY >= bottomY) { "tree top $topY below bottom $bottomY" }
        val radius = TREE_SPREAD_RADIUS.toDouble()
        val halfHeight = (topY - bottomY) / 2.0 + radius
        val centerY = (topY + bottomY) / 2.0
        val minY = maxOf(level.minBuildHeight, Math.floor(centerY - halfHeight).toInt())
        val maxY = minOf(level.maxBuildHeight - 1, Math.ceil(centerY + halfHeight).toInt())
        val cells = ArrayList<Pair<CellCoord, Double>>()
        for (qx in ((centerX - TREE_SPREAD_RADIUS) shr 2)..((centerX + TREE_SPREAD_RADIUS) shr 2)) {
            val dx = (qx * 4 + 2 - centerX) / radius
            for (qz in ((centerZ - TREE_SPREAD_RADIUS) shr 2)..((centerZ + TREE_SPREAD_RADIUS) shr 2)) {
                val dz = (qz * 4 + 2 - centerZ) / radius
                val horizontal = dx * dx + dz * dz
                if (horizontal > 1.0) continue
                for (qy in (minY shr 2)..(maxY shr 2)) {
                    val dy = (qy * 4 + 2 - centerY) / halfHeight
                    val d2 = horizontal + dy * dy
                    if (d2 <= 1.0) cells.add(CellCoord(qx, qy, qz) to d2)
                }
            }
        }
        if (cells.isEmpty()) return
        cells.sortBy { it.second }
        val job = SeedJob(ArrayDeque(cells.map { it.first }), TREE_SPREAD_TICKS)
        seedJobs.computeIfAbsent(level.dimension()) { mutableListOf() }.add(job)
    }

    /** Runs the seed path on a tiny sphere, so the first ritual does not load its classes. */
    fun warmClasses() {
        ClassWarmup.load(SeedJob::class.java, CellCoord::class.java)
        SeedJob(ArrayDeque(computeSphereCells(BlockPos.ZERO, 2)), 1)
    }

    fun startPortalSeed(level: ServerLevel, portalPos: BlockPos) {
        val cells = computeSphereCells(portalPos, SEED_RADIUS_BLOCKS)
        if (cells.isEmpty()) return
        // Scale the seed window by the gamerule multiplier.
        // wohlonSpreadSpeed=10 ⇒ 1.5 s sphere instead of 15 s.
        val speed = spreadSpeed(level)
        val totalTicks = max(1, SEED_TOTAL_TICKS / speed)
        val job = SeedJob(ArrayDeque(cells), totalTicks)
        seedJobs.computeIfAbsent(level.dimension()) { mutableListOf() }.add(job)
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
     *  - Client resyncs go through [pendingResyncs]. */
    private fun tickSeedJobs(level: ServerLevel) {
        val list = seedJobs[level.dimension()] ?: return
        if (list.isEmpty()) {
            seedJobs.remove(level.dimension())
            return
        }
        val wohlonBiome = lookupBiome(level) ?: return
        val touchedChunks = touchedChunksScratch
        touchedChunks.clear()
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
        if (list.isEmpty()) seedJobs.remove(level.dimension())
    }

/** Give one unloaded tainted chunk and its neighbours a ticket for [FORCE_LOAD_HOLD_TICKS],
     *  so spread and conversion get a turn with no player nearby. The ticket loads off the
     *  server thread; a synchronous `getChunk` generated terrain on it.
     *
     *  A chunk at the edge of the tainted set is preferred: the taint advances there. Shipyard
     *  chunks are never picked — a ship loads with its ship. */
    private fun tryForceLoadTaintedChunk(level: ServerLevel, gameTime: Long) {
        val data = getTaintedData(level)
        val positions = data.positions
        val order = data.order
        if (order.isEmpty()) return
        val held = heldForceLoads.getOrPut(level.dimension()) { Long2LongOpenHashMap() }
        if (held.size >= FORCE_LOAD_MAX_HELD) return
        val chunkSource = level.chunkSource
        val random = level.random

        var anyPick = 0L
        var anyFound = false
        var edgePick = 0L
        var edgeFound = false
        repeat(FORCE_LOAD_PICK_TRIES) {
            if (edgeFound) return@repeat
            val packed = order.getLong(random.nextInt(order.size))
            if (held.containsKey(packed)) return@repeat
            val cx = ChunkPos.getX(packed)
            val cz = ChunkPos.getZ(packed)
            if (chunkSource.getChunkNow(cx, cz) != null) return@repeat
            if (level.isChunkInShipyard(cx, cz)) return@repeat
            if (!anyFound) {
                anyPick = packed
                anyFound = true
            }
            if (isTaintEdge(positions, cx, cz)) {
                edgePick = packed
                edgeFound = true
            }
        }
        if (!anyFound) return
        val pick = if (edgeFound) edgePick else anyPick
        val pos = ChunkPos(pick)
        chunkSource.addRegionTicket(FORCE_LOAD_TICKET, pos, FORCE_LOAD_RADIUS, pos)
        held.put(pick, gameTime + FORCE_LOAD_HOLD_TICKS)
    }

    private fun isTaintEdge(positions: LongOpenHashSet, cx: Int, cz: Int): Boolean =
        !positions.contains(ChunkPos.asLong(cx - 1, cz)) ||
            !positions.contains(ChunkPos.asLong(cx + 1, cz)) ||
            !positions.contains(ChunkPos.asLong(cx, cz - 1)) ||
            !positions.contains(ChunkPos.asLong(cx, cz + 1))

    private fun releaseExpiredForceLoads(level: ServerLevel, gameTime: Long) {
        val held = heldForceLoads[level.dimension()] ?: return
        if (held.isEmpty()) return
        val iter = held.long2LongEntrySet().fastIterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (gameTime < entry.longValue) continue
            val pos = ChunkPos(entry.longKey)
            level.chunkSource.removeRegionTicket(FORCE_LOAD_TICKET, pos, FORCE_LOAD_RADIUS, pos)
            iter.remove()
        }
    }

    /** World → ship transfer, the only way taint reaches a ship. Ship blocks live in shipyard
     *  chunks far from the world cells they float in, so coordinate adjacency never gets there.
     *  A ship cell that holds a block and whose WORLD position is in a Wohlon cell is queued. */
    private fun tickShipTransfer(level: ServerLevel) {
        val dimId = level.dimensionId
        val samples = SHIP_TRANSFER_SAMPLES * spreadSpeed(level)
        val random = level.random
        val world = shipTransferScratch
        for (ship in level.shipObjectWorld.loadedShips) {
            if (ship.chunkClaimDimension != dimId) continue
            val aabb = ship.shipAABB ?: continue
            repeat(samples) {
                val sx = aabb.minX() + random.nextInt(aabb.maxX() - aabb.minX() + 1)
                val sy = aabb.minY() + random.nextInt(aabb.maxY() - aabb.minY() + 1)
                val sz = aabb.minZ() + random.nextInt(aabb.maxZ() - aabb.minZ() + 1)
                val qx = sx shr 2
                val qy = sy shr 2
                val qz = sz shr 2
                val shipCell = getBiomeCellNonLoading(level, qx, qy, qz) ?: return@repeat
                if (isWohlon(shipCell)) return@repeat
                if (cellIsAllAir(level, qx, qy, qz)) return@repeat
                ship.shipToWorld.transformPosition(world.set(sx + 0.5, sy + 0.5, sz + 0.5))
                val worldCell = getBiomeCellNonLoading(
                    level,
                    Math.floor(world.x).toInt() shr 2,
                    Math.floor(world.y).toInt() shr 2,
                    Math.floor(world.z).toInt() shr 2,
                ) ?: return@repeat
                if (isWohlon(worldCell)) enqueueCellSpread(level, qx, qy, qz)
            }
        }
    }

    /** Coordinate adjacency must not carry taint into a shipyard claim, out of one, or between
     *  two ships. Inside one ship it is free. */
    private fun sameSpreadScope(level: ServerLevel, cxA: Int, czA: Int, cxB: Int, czB: Int): Boolean {
        if (cxA == cxB && czA == czB) return true
        val yardA = level.isChunkInShipyard(cxA, czA)
        val yardB = level.isChunkInShipyard(cxB, czB)
        if (!yardA && !yardB) return true
        if (yardA != yardB) return false
        val shipA = level.getShipManagingPos(cxA, czA) ?: return false
        val shipB = level.getShipManagingPos(cxB, czB) ?: return false
        return shipA.id == shipB.id
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
        val speed = spreadSpeed(level)
        val effectiveTickRate = randomTickSpeed * speed
        val random = level.random
        val mutPos = BlockPos.MutableBlockPos()
        val chunkSource = level.chunkSource
        val states = statesFor(level.dimension())
        val gameTime = level.gameTime

        // The states hold loaded chunks only, so the cost follows the loaded part of the taint.
        for (entry in states.entries) {
            val state = entry.value
            val mask = state.sectionMask
            if (mask == 0L) continue
            val packed: Long = entry.key

            // Phase 3: per-chunk tier gate, before the chunk lookup. Chunks bucket into
            // hot / warm / cold by time since last activity; warm and cold tick on a
            // slower cadence, staggered by a per-chunk phase.
            if (state.lastActivityGameTime == 0L) state.lastActivityGameTime = gameTime
            val elapsed = gameTime - state.lastActivityGameTime
            val interval = when {
                elapsed < HOT_THRESHOLD_TICKS -> HOT_TICK_INTERVAL
                elapsed < WARM_THRESHOLD_TICKS -> WARM_TICK_INTERVAL
                else -> COLD_TICK_INTERVAL
            }
            if (interval > 1L) {
                // floorMod: the packed key is negative for a chunk with negative Z.
                val phase = Math.floorMod(packed xor (packed ushr 32), interval)
                if (((gameTime % interval) + interval) % interval != phase) continue
            }
            val chunk = chunkSource.getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed)) ?: continue
            if (!state.frontierPopulated) populateFrontier(level, chunk, state)

            val converted = tickChunkConversions(level, chunk, mask, effectiveTickRate, speed, random, mutPos)
            if (converted > 0) {
                state.lastActivityGameTime = gameTime
            } else if (stagedWaiting > 0 && gameTime - state.lastActivityGameTime > HOT_THRESHOLD_TICKS) {
                // Ground waiting for its next stage keeps the chunk warm; a cold chunk froze its stage
                // and left a straight chunk edge against its neighbours.
                state.lastActivityGameTime = gameTime - HOT_THRESHOLD_TICKS - 1
            }
        }
    }

    /** Make a state for each tainted chunk that loaded since the last pass. Walks the smaller of
     *  the tainted set and the loaded chunks, because the tainted set never gets smaller. */
    private fun discoverLoadedTainted(level: ServerLevel) {
        val positions = getTaintedData(level).positions
        if (positions.isEmpty()) return
        val states = statesFor(level.dimension())
        val chunkSource = level.chunkSource
        val chunkMap = chunkSource.chunkMap
        if (positions.size <= chunkMap.size()) {
            val iter = positions.iterator()
            while (iter.hasNext()) discoverIfLoaded(level, states, iter.nextLong())
        } else {
            for (holder in (chunkMap as ChunkMapGetChunksAccessor).`enderkinesis$getChunks`()) {
                val packed = holder.pos.toLong()
                if (positions.contains(packed)) discoverIfLoaded(level, states, packed)
            }
        }
    }

    private fun discoverIfLoaded(level: ServerLevel, states: ConcurrentHashMap<Long, ChunkSpreadState>, packed: Long) {
        if (states.containsKey(packed)) return
        val chunk = level.chunkSource.getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed)) ?: return
        sectionMaskFor(level, chunk)
    }

    /** Send the queued biome changes, one biome packet for each chunk. Vanilla `/fillbiome`
     *  uses the same path; it carries no block or light data. */
    private fun flushPendingResyncs(level: ServerLevel) {
        val pending = pendingResyncs[level.dimension()] ?: return
        if (pending.isEmpty()) return
        val chunkSource = level.chunkSource
        val chunks = ArrayList<ChunkAccess>(pending.size)
        val iter = pending.iterator()
        while (iter.hasNext()) {
            val packed = iter.nextLong()
            chunks.add(chunkSource.getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed)) ?: continue)
        }
        pending.clear()
        if (chunks.isNotEmpty()) chunkSource.chunkMap.resendBiomesForChunks(chunks)
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
        spreadSpeed: Int,
        random: net.minecraft.util.RandomSource,
        mutPos: BlockPos.MutableBlockPos,
    ): Int {
        stagedWaiting = 0
        val minSection = chunk.minSection
        val sections = chunk.sections
        val chunkBaseX = chunk.pos.x shl 4
        val chunkBaseZ = chunk.pos.z shl 4
        val chunkSource = level.chunkSource
        // Phase 3: caller stamps activity (and thus tier promotion
        // to hot) only if at least one conversion fired; the return
        // value is that count.
        var converted = 0
        val mud = EKBlocks.WOHLON_MUD.get()
        val damp = Blocks.PODZOL.defaultBlockState()
        val pioneerRate = (PIONEER_CHANCE * spreadSpeed).coerceAtMost(1.0)
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
                    // A neighbour in an unloaded chunk counts as non-Wohlon; level.getNoiseBiome
                    // would compute it from the biome source instead.
                    var minDist = Int.MAX_VALUE
                    if (isWohlonCell(level, cellQx - 1, cellQy, cellQz)) {
                        if (localX + 1 < minDist) minDist = localX + 1
                    }
                    if (isWohlonCell(level, cellQx + 1, cellQy, cellQz)) {
                        if (4 - localX < minDist) minDist = 4 - localX
                    }
                    if (isWohlonCell(level, cellQx, cellQy - 1, cellQz)) {
                        if (localY + 1 < minDist) minDist = localY + 1
                    }
                    if (isWohlonCell(level, cellQx, cellQy + 1, cellQz)) {
                        if (4 - localY < minDist) minDist = 4 - localY
                    }
                    if (isWohlonCell(level, cellQx, cellQy, cellQz - 1)) {
                        if (localZ + 1 < minDist) minDist = localZ + 1
                    }
                    if (isWohlonCell(level, cellQx, cellQy, cellQz + 1)) {
                        if (4 - localZ < minDist) minDist = 4 - localZ
                    }
                    // Outside the fringe band — skip. Bulk of
                    // non-Wohlon ticks fall through here. Wet ground carries the fringe one block further.
                    if (minDist > 3 || (minDist == 3 && !cellWet(level, cellQx, cellQy, cellQz))) return@repeat
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
                    )
                    if (blockNoise < BLOCK_NOISE_THRESHOLD) return@repeat
                    val rate = (baseRate * spreadSpeed).coerceAtMost(1.0)
                    if (random.nextDouble() >= rate) return@repeat
                    // The fringe is ahead of the front: the pioneer stage only.
                    val staged = frontStage(state, sourceTarget, mud, damp) ?: return@repeat
                    mutPos.set(bx, by, bz)
                    // Flag 2 | 16 = UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE.
                    // Without the 16 bit Level.setBlock still fires the
                    // updateNeighbourShapes + updateIndirectNeighbourShapes
                    // cascade through CollectingNeighborUpdater — 90% of the
                    // spreader's cost in profiling. Targets here are full
                    // solid cubes with no neighbor-dependent shape, so the
                    // cascade is wasted.
                    setShapeAware(level, mutPos, state, staged, 2 or 16)
                    converted++
                    return@repeat
                }

                val bx = chunkBaseX + lx
                val by = sectionBaseY + ly
                val bz = chunkBaseZ + lz
                if (state.isAir || isStillWater(state)) {
                    if (random.nextDouble() < pioneerRate && frontDepth(level, bx, by, bz) < PIONEER_DEPTH &&
                        placePioneer(level, bx, by, bz, state)
                    ) converted++
                    return@repeat
                }

                val sourceTarget = pickConversion(state) ?: return@repeat
                // Already final: no depth test, so the search runs only on ground still to convert.
                if (state.`is`(sourceTarget.block)) return@repeat
                // The stage follows each block's own depth into the swamp, not its 4-block biome cell.
                val front = frontDepth(level, bx, by, bz) < DAMP_DEPTH
                val staged = if (front) frontStage(state, sourceTarget, mud, damp) else sourceTarget
                if (staged == null) {
                    stagedWaiting++
                    return@repeat
                }
                mutPos.set(bx, by, bz)
                converted++
                // Flag 2 | 16: see the boundary-fringe setBlock above.
                setShapeAware(level, mutPos, state, staged, 2 or 16)

                // Spread happens in [tickFrontierSpread], not per-random-tick — sampled
                // directly from the per-chunk frontier bitmask in [ChunkSpreadState] so
                // cost scales with boundary cell count, not tainted volume.
            }
        }
        return converted
    }

    /**
     * The pioneer stage of a conversion at the front: ground goes damp before it goes to mud, and the
     * wogor wood and leaves wait for the interior. Undergrowth swaps go ahead. Null: wait.
     */
    private fun frontStage(source: BlockState, target: BlockState, mud: Block, damp: BlockState): BlockState? = when {
        // Wohlon mud is shovel ground too, so it matches its own conversion; it is already final.
        source.`is`(target.block) -> null
        target.`is`(mud) -> if (source.`is`(damp.block)) null else damp
        target.`is`(BlockTags.LOGS) || target.`is`(BlockTags.LEAVES) -> null
        else -> target
    }

    private fun isStillWater(state: BlockState): Boolean = state.`is`(Blocks.WATER) && state.fluidState.isSource

    /** Ground conversions of the last [tickChunkConversions] call that wait for a later stage. Server thread only. */
    private var stagedWaiting = 0

    /**
     * Horizontal blocks from a block to the nearest non-Wohlon cell among the 8 around its cell, moved by
     * block-scale noise so the stage edges wander. [DEPTH_FAR] when every one of them is Wohlon. Only
     * sideways cells count: the cells under the ground surface may not be Wohlon yet.
     */
    private fun frontDepth(level: ServerLevel, bx: Int, by: Int, bz: Int): Double {
        val qx = bx shr 2; val qy = by shr 2; val qz = bz shr 2
        val px = bx + 0.5; val pz = bz + 0.5
        var best = DEPTH_FAR
        for (dqx in -1..1) for (dqz in -1..1) {
            if (dqx == 0 && dqz == 0) continue
            val cell = getBiomeCellNonLoading(level, qx + dqx, qy, qz + dqz) ?: continue
            if (isWohlon(cell)) continue
            val minX = (qx + dqx) * 4.0; val minZ = (qz + dqz) * 4.0
            val dx = if (px < minX) minX - px else if (px > minX + 4.0) px - minX - 4.0 else 0.0
            val dz = if (pz < minZ) minZ - pz else if (pz > minZ + 4.0) pz - minZ - 4.0 else 0.0
            val d = Math.sqrt(dx * dx + dz * dz)
            if (d < best) best = d
        }
        val wobble = SPREAD_BOUNDARY_NOISE.noise(
            bx * DEPTH_NOISE_FREQUENCY, by * DEPTH_NOISE_FREQUENCY, bz * DEPTH_NOISE_FREQUENCY,
        )
        return best + wobble * DEPTH_NOISE
    }

    // Server thread only.
    private val pioneerPos = BlockPos.MutableBlockPos()

    private fun sturdyTop(level: ServerLevel, state: BlockState, x: Int, y: Int, z: Int): Boolean =
        state.fluidState.isEmpty && state.isFaceSturdy(level, pioneerPos.set(x, y, z), Direction.UP)

    // A block read that never loads a chunk; null when the chunk is not loaded.
    private fun stateNow(level: ServerLevel, x: Int, y: Int, z: Int): BlockState? {
        val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return null
        pioneerPos.set(x, y, z)
        return chunk.getBlockState(pioneerPos)
    }

    /**
     * One pioneer plant at the front, by the dimension's wetland zones ([WohlonWetlandZones]): reeds in the
     * shore band, duckweed on enclosed pools, lily pads on open water under open sky, leaf litter on shaded
     * damp ground. Each column's roll is a hash of its position, not a fresh random, so repeated tries settle
     * at the dimension's density instead of filling every spot. True when a plant went in.
     */
    private fun placePioneer(level: ServerLevel, x: Int, y: Int, z: Int, state: BlockState): Boolean {
        val above = stateNow(level, x, y + 1, z) ?: return false
        if (!above.isAir) return false
        if (isStillWater(state)) {
            val grid = wetAround.at(level, x, y, z)
            val depth = waterDepthNow(level, x, y, z)
            val landDistance = WohlonWetlandZones.landDistance(grid)
            if (WohlonWetlandZones.reedInWater(depth, landDistance) && columnRoll(x, z, ROLL_REED) < WohlonWetlandZones.reedChance(x, z)) {
                return placeReed(level, x, y, z, waterlogged = true)
            }
            val enclosure = WohlonWetlandZones.enclosure(grid)
            if (columnRoll(x, z, ROLL_DUCKWEED) < WohlonWetlandZones.duckweedChance(enclosure, landDistance)) {
                return placeIfSurvives(level, x, y + 1, z, EKBlocks.WOHLON_DUCKWEED.get().defaultBlockState())
            }
            if (!WohlonWetlandZones.lilyWater(depth, enclosure) || !openSky(level, x, y, z)) return false
            if (columnRoll(x, z, ROLL_LILY) >= WohlonWetlandZones.lilyChance(x, z)) return false
            return placeIfSurvives(level, x, y + 1, z, EKBlocks.WOHLON_LILY_PAD.get().defaultBlockState())
        }
        val below = stateNow(level, x, y - 1, z) ?: return false
        if (!sturdyTop(level, below, x, y - 1, z)) return false
        // Ground flush with water beside it is the shore: only the reed band grows there, as in the dimension.
        val shore = wetAround.at(level, x, y - 1, z)
        if (WohlonWetlandZones.waterDistance(shore) <= WohlonWetlandZones.WATER_REACH) {
            if (!WohlonWetlandZones.reedOnLand(WohlonWetlandZones.waterDistance(shore))) return false
            if (columnRoll(x, z, ROLL_REED) >= WohlonWetlandZones.reedChance(x, z)) return false
            return placeReed(level, x, y, z, waterlogged = false)
        }
        if (!isLitterGround(below)) return false
        if (columnRoll(x, z, ROLL_LITTER) >= WohlonWetlandZones.litterChance(x, z, shadedNow(level, x, y - 1, z))) return false
        return placeIfSurvives(level, x, y, z, EKBlocks.WOHLON_LEAF_LITTER.get().defaultBlockState())
    }

    // The dimension's floor is mud; at the front the ground may still be in its damp stage.
    private fun isLitterGround(state: BlockState): Boolean =
        state.`is`(EKBlocks.WOHLON_MUD.get()) || state.`is`(Blocks.MUD) || state.`is`(Blocks.PODZOL)

    /** Standing water around a column at one height, read without loading chunks. Server thread only. */
    private class WetAround : WohlonWetlandZones.WetGrid {
        private var level: ServerLevel? = null
        private var x = 0
        private var y = 0
        private var z = 0

        fun at(level: ServerLevel, x: Int, y: Int, z: Int): WetAround {
            this.level = level; this.x = x; this.y = y; this.z = z
            return this
        }

        override fun wet(dx: Int, dz: Int): Boolean {
            val lvl = level ?: return false
            val s = stateNow(lvl, x + dx, y, z + dz) ?: return false
            return s.fluidState.`is`(FluidTags.WATER)
        }
    }

    private val wetAround = WetAround()

    private fun waterDepthNow(level: ServerLevel, x: Int, y: Int, z: Int): Int {
        var depth = 0
        while (depth <= WohlonWetlandZones.LILY_MAX_DEPTH) {
            val s = stateNow(level, x, y - depth, z) ?: break
            if (!s.`is`(Blocks.WATER)) break
            depth++
        }
        return depth
    }

    // LevelChunk.getHeight is the top block; Level.getHeight, which worldgen reads, is one above it.
    private fun openSky(level: ServerLevel, x: Int, y: Int, z: Int): Boolean {
        val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return false
        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x and 15, z and 15) <= y
    }

    private fun shadedNow(level: ServerLevel, x: Int, groundY: Int, z: Int): Boolean {
        val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: return false
        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x and 15, z and 15) > groundY
    }

    /** A fixed value in [0, 1) for a column and a plant. */
    private fun columnRoll(x: Int, z: Int, salt: Int): Float {
        @Suppress("DEPRECATION") // Mth.getSeed is the vanilla position hash; 1.20.1 has no replacement.
        var h = net.minecraft.util.Mth.getSeed(x, salt, z)
        h = (h xor (h ushr 31)) * -0x4b47d5b1L
        h = h xor (h ushr 29)
        return ((h ushr 40).toFloat() / (1L shl 24).toFloat())
    }

    private fun placeIfSurvives(level: ServerLevel, x: Int, y: Int, z: Int, plant: BlockState): Boolean {
        pioneerPos.set(x, y, z)
        if (!plant.canSurvive(level, pioneerPos)) return false
        return level.setBlock(pioneerPos, plant, 2 or 16)
    }

    private fun placeReed(level: ServerLevel, x: Int, y: Int, z: Int, waterlogged: Boolean): Boolean {
        val reed = EKBlocks.WOHLON_REED.get().defaultBlockState()
        val lower = reed.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER).setValue(WohlonReedBlock.WATERLOGGED, waterlogged)
        pioneerPos.set(x, y, z)
        if (!lower.canSurvive(level, pioneerPos)) return false
        if (!level.setBlock(pioneerPos, lower, 2 or 16)) return false
        pioneerPos.set(x, y + 1, z)
        level.setBlock(pioneerPos, reed.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), 2 or 16)
        return true
    }

    /** Get-or-compute the per-chunk section bitmask. A `0L` entry
     *  means "no Wohlon cells anywhere in this chunk" and the
     *  conversion loop short-circuits. The cache is invalidated
     *  on biome spread via [invalidateSectionMask]. */
    private fun sectionMaskFor(level: ServerLevel, chunk: LevelChunk): Long {
        val states = statesFor(level.dimension())
        val key = chunk.pos.toLong()
        val existing = states[key]
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
        val state = states.computeIfAbsent(key) { ChunkSpreadState() }
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
                    bits = LongArray(sections.size)
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
            activeFrontierChunks.computeIfAbsent(level.dimension()) { IndexedLongSet() }.add(chunk.pos.toLong())
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
        val bit = 1L shl sectionIdx
        val state = statesFor(level.dimension()).computeIfAbsent(chunkPos.toLong()) { ChunkSpreadState() }
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
        val state = statesFor(level.dimension()).computeIfAbsent(chunkPos.toLong()) { ChunkSpreadState() }
        state.lastActivityGameTime = level.gameTime
    }


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

    /** True iff every block in the 4×4×4 volume of biome cell
     *  `(qx, qy, qz)` is air. Used by [tickFrontierSpread] to suppress
     *  horizontal / diagonal spread into pure-sky cells — biome paint
     *  with no blocks to convert just bleeds the colour and fog into
     *  empty space. Vertical-up climb via the [enqueueVerticalSpread]
     *  cascade is intentionally exempt, so a column above a freshly-
     *  tainted cell still claims its sky.
     *
     *  Fast path: [LevelChunkSection.hasOnlyAir] short-circuits the
     *  per-block scan for sections vanilla already knows are empty.
     *  Slow path: 64 `getBlockState` reads against the section's
     *  palette — cheap relative to the spread-drain cost. Returns
     *  `false` (let the cell through) if the chunk isn't loaded; the
     *  spread budget is small enough that the occasional optimistic
     *  paint of an unloaded cell is fine. */
    private fun cellIsAllAir(level: ServerLevel, qx: Int, qy: Int, qz: Int): Boolean {
        val chunk = level.chunkSource.getChunkNow(qx shr 2, qz shr 2) ?: return false
        val sectionIdx = (qy shr 2) - chunk.minSection
        if (sectionIdx < 0 || sectionIdx >= chunk.sections.size) return false
        val section = chunk.sections[sectionIdx]
        if (section.hasOnlyAir()) return true
        val bxBase = qx shl 2
        val byBase = qy shl 2
        val bzBase = qz shl 2
        for (lx in 0..3) for (ly in 0..3) for (lz in 0..3) {
            val state = section.getBlockState(bxBase + lx and 15, byBase + ly and 15, bzBase + lz and 15)
            if (!state.isAir) return false
        }
        return true
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
        chunkLong: Long, sectionIdx: Int, sectionCount: Int, mask: Long,
    ) {
        if (sectionIdx < 0 || sectionIdx >= sectionCount) return
        var bits = state.frontierBits
        if (bits == null) {
            bits = LongArray(sectionCount)
            state.frontierBits = bits
        }
        if ((bits[sectionIdx] and mask) != 0L) return
        bits[sectionIdx] = bits[sectionIdx] or mask
        state.frontierCount++
        if (state.frontierCount == 1) {
            activeFrontierChunks.computeIfAbsent(dimKey) { IndexedLongSet() }.add(chunkLong)
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
        val bits = state.frontierBits ?: return
        if (sectionIdx < 0 || sectionIdx >= bits.size) return
        if ((bits[sectionIdx] and mask) == 0L) return
        bits[sectionIdx] = bits[sectionIdx] and mask.inv()
        state.frontierCount--
        if (state.frontierCount == 0) {
            activeFrontierChunks[dimKey]?.remove(chunkLong)
        }
    }

    /** Update the frontier bitmasks of the just-flipped cell *and*
     *  its six previously-Wohlon neighbours. Called from
     *  [setBiomeCellSilent] after the biome
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
        val states = statesFor(dimKey)

        // Self
        val selfChunk = level.chunkSource.getChunkNow(qx shr 2, qz shr 2) ?: return
        val selfSection = (qy shr 2) - selfChunk.minSection
        if (selfSection < 0 || selfSection >= selfChunk.sections.size) return
        val selfMask = frontierBitMask(qx, qy, qz)
        val selfState = states.computeIfAbsent(selfChunk.pos.toLong()) { ChunkSpreadState() }
        if (hasNonWohlonNeighbor(level, qx, qy, qz)) {
            setFrontierBit(
                dimKey, selfState, selfChunk.pos.toLong(), selfSection, selfChunk.sections.size, selfMask,
            )
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
            val nState = states[nChunk.pos.toLong()] ?: continue
            val nBits = nState.frontierBits ?: continue
            if ((nBits[nSection] and nMask) == 0L) continue
            if (!hasNonWohlonNeighbor(level, nqx, nqy, nqz)) {
                clearFrontierBit(dimKey, nState, nChunk.pos.toLong(), nSection, nMask)
            }
        }
    }


    /** How many spread samples to attempt per tick. Each sample
     *  picks a random frontier cell and tries to enqueue one
     *  random non-Wohlon neighbour for conversion. The spread queue
     *  holds the surplus over [SPREAD_DRAIN_PER_SEC], and the drain
     *  meters everything out at the bounded rate. Multiplied by
     *  the `wohlonSpreadSpeed` gamerule so the front advances
     *  faster at higher speeds. */
    private const val FRONTIER_SAMPLES_PER_TICK_BASE: Int = 2


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
        val states = statesFor(dimKey)
        val random = level.random

        // Nothing in the loop changes [active]: the enqueue only fills the spread queue.
        repeat(samples) {
            val chunkLong = active.get(random.nextInt(active.size))
            val state = states[chunkLong] ?: return@repeat
            val bits = state.frontierBits ?: return@repeat
            val count = state.frontierCount
            if (count <= 0) return@repeat
            // Pick a random set bit, weighted by per-long popcount.
            var target = random.nextInt(count)
            var sectionIdx = -1
            var bitInLong = -1
            for (s in bits.indices) {
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
            val cx = ChunkPos.getX(chunkLong)
            val cz = ChunkPos.getZ(chunkLong)
            val chunk = level.chunkSource.getChunkNow(cx, cz) ?: return@repeat
            val sectionWorldY = (chunk.minSection + sectionIdx) shl 2
            val qyLocal = (bitInLong shr 4) and 3
            val qxLocal = (bitInLong shr 2) and 3
            val qzLocal = bitInLong and 3
            val qx = (cx shl 2) or qxLocal
            val qy = sectionWorldY or qyLocal
            val qz = (cz shl 2) or qzLocal

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
            )
            if (noiseValue < SPREAD_NOISE_THRESHOLD) return@repeat
            // Air-only cells get spread only from the vertical-up cascade
            // ([enqueueVerticalSpread] from [setBiomeCellSilent]). The
            // frontier sampler covers horizontal / diagonal / down moves
            // and a +Y face / edge / vertex pick; gating all of them here
            // means the biome doesn't bleed into open sky around tainted
            // ground, only climbs the column directly above it.
            if (cellIsAllAir(level, nqx, nqy, nqz)) return@repeat
            if (!sameSpreadScope(level, cx, cz, nqx shr 2, nqz shr 2)) return@repeat
            val sourceWet = cellWet(level, qx, qy, qz)
            val targetWet = cellWet(level, nqx, nqy, nqz)
            if (random.nextFloat() >= acceptance(level, neighbor, nqy, dy, sourceWet, targetWet)) return@repeat
            enqueueCellSpread(level, nqx, nqy, nqz)
            if (sourceWet && targetWet && dy <= 0 && (dx != 0 || dz != 0)) hopAlongWater(level, cx, cz, nqx, nqy, nqz, dx, dz)
        }
    }

    /**
     * How readily the swamp takes a cell: wet, low and temperate cells first; frozen, arid and high
     * ground resist. Water to water runs as a corridor: always level or downstream, slower upstream.
     */
    @Suppress("LongParameterList") // The candidate cell and how it relates to its source.
    private fun acceptance(level: ServerLevel, target: Holder<Biome>, nqy: Int, dy: Int, sourceWet: Boolean, targetWet: Boolean): Float {
        if (sourceWet && targetWet) return if (dy > 0) UPSTREAM else 1f
        var a = if (targetWet) 1f else DRY_TARGET
        a *= when {
            dy > 0 -> UPHILL
            dy == 0 -> LEVEL_GROUND
            else -> 1f
        }
        val biome = target.value()
        if (biome.baseTemperature < FROZEN_TEMPERATURE) a *= FROZEN else if (!biome.hasPrecipitation()) a *= ARID
        val above = (nqy shl 2) - level.seaLevel
        if (above > HIGHER_GROUND) a *= HIGHER_FACTOR else if (above > HIGH_GROUND) a *= HIGH_FACTOR
        return a.coerceIn(ACCEPT_FLOOR, 1f)
    }

    /** Carries the spread further along a water run, at the same level or one cell down. Bounded by [WATER_HOP_MAX]. */
    @Suppress("LongParameterList") // A cell, its chunk and a direction.
    private fun hopAlongWater(level: ServerLevel, cx: Int, cz: Int, startQx: Int, startQy: Int, startQz: Int, dx: Int, dz: Int) {
        var qx = startQx
        var qy = startQy
        var qz = startQz
        repeat(WATER_HOP_MAX) {
            qx += dx
            qz += dz
            if (!cellWet(level, qx, qy, qz)) {
                if (!cellWet(level, qx, qy - 1, qz)) return
                qy--
            }
            val cell = getBiomeCellNonLoading(level, qx, qy, qz) ?: return
            if (isWohlon(cell)) return
            if (!sameSpreadScope(level, cx, cz, qx shr 2, qz shr 2)) return
            enqueueCellSpread(level, qx, qy, qz)
        }
    }

    // Three spread-out sample blocks; a wet cell holds water in any of them. Never loads a chunk.
    private fun cellWet(level: ServerLevel, qx: Int, qy: Int, qz: Int): Boolean {
        val chunk = level.chunkSource.getChunkNow(qx shr 2, qz shr 2) ?: return false
        val sectionIdx = (qy shr 2) - chunk.minSection
        if (sectionIdx < 0 || sectionIdx >= chunk.sections.size) return false
        val section = chunk.sections[sectionIdx]
        if (section.hasOnlyAir()) return false
        val bx = (qx shl 2) and 15
        val by = (qy shl 2) and 15
        val bz = (qz shl 2) and 15
        return section.getFluidState(bx + 1, by, bz + 2).`is`(FluidTags.WATER) ||
            section.getFluidState(bx + 2, by + 2, bz + 1).`is`(FluidTags.WATER) ||
            section.getFluidState(bx + 1, by + 3, bz + 1).`is`(FluidTags.WATER)
    }

    /** Map a block-state to its conversion target via the
     *  converts-to tags, or null if no tag matches. The supplier
     *  receives the source state so it can inherit compatible
     *  properties (axis, persistent, distance, waterlogged, etc.).
     *
     *  Blocks in [WOHLON_CONVERSION_BLOCKLIST] short-circuit to null
     *  even when they match a converts_to tag — used to carve snow
     *  and other thematically-wrong blocks out of the broad
     *  `#minecraft:mineable/shovel` set without having to enumerate
     *  every wanted block by hand. */
    private fun pickConversion(state: BlockState): BlockState? {
        if (state.`is`(WOHLON_CONVERSION_BLOCKLIST)) return null
        for ((tag, supplier) in CONVERSIONS) {
            if (state.`is`(tag)) return supplier(state)
        }
        return null
    }

    /** Place [target] at [pos] with a shape-preserving detour: if [target] is
     *  [EKBlocks.WOGOR_WOOD], route through [WogorVariantPicker] so a destroyed
     *  vanilla stair / slab / fence / wall / pane / button / pillar regrows into
     *  the matching wogor shape variant instead of a full cube. The picker's
     *  fallback is plain wogor_wood, so non-shape sources behave identically to
     *  the pre-picker code path. Other conversions (wogor_log / mud / wogor_leaves)
     *  pass through unchanged. */
    private fun setShapeAware(
        level: ServerLevel, pos: BlockPos, source: BlockState, target: BlockState, flags: Int,
    ) {
        if (target.`is`(EKBlocks.WOGOR_WOOD.get())) {
            // The picker's full-cube fallback is the default state; [target] carries the axis.
            val picked = WogorVariantPicker.pick(source)
            level.setBlock(pos, if (picked.`is`(EKBlocks.WOGOR_WOOD.get())) target else picked, flags)
            return
        }
        if (target.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            // A lone converted half breaks on its next neighbour update, so convert the pair together.
            val half = target.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
            val lower = half == DoubleBlockHalf.LOWER
            val otherPos = if (lower) pos.above() else pos.below()
            if (level.getBlockState(otherPos).`is`(source.block)) {
                val otherHalf = if (lower) DoubleBlockHalf.UPPER else DoubleBlockHalf.LOWER
                level.setBlock(otherPos, target.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, otherHalf), flags)
            }
        }
        level.setBlock(pos, target, flags)
    }

    /** Writes one biome cell. Never loads a chunk, and queues the client resync in
     *  [pendingResyncs]. [touchedChunks] dedupes the per-chunk work inside one caller pass. */
    private fun setBiomeCellSilent(
        level: ServerLevel,
        qx: Int, qy: Int, qz: Int,
        biome: Holder<Biome>,
        touchedChunks: LongOpenHashSet,
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
        if (touchedChunks.add(chunk.pos.toLong())) {
            markTainted(getTaintedData(level), chunk.pos)
            pendingResyncs.getOrPut(level.dimension()) { LongOpenHashSet() }.add(chunk.pos.toLong())
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
        // A world root has no place in the shipyard.
        if (!level.isChunkInShipyard(cx, cz)) WohlonnogondoniaWorldRootGrower.maybeEnqueueCell(level, qx, qy, qz)

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

    private fun isWohlon(biome: Holder<Biome>): Boolean = biome.`is`(Wohlonnogondonia.BIOME_KEY)

    private fun isWohlonCell(level: ServerLevel, qx: Int, qy: Int, qz: Int): Boolean =
        getBiomeCellNonLoading(level, qx, qy, qz)?.let(::isWohlon) == true

    private fun lookupBiome(level: ServerLevel): Holder<Biome>? {
        return level.registryAccess()
            .registryOrThrow(Registries.BIOME)
            .getHolder(Wohlonnogondonia.BIOME_KEY)
            .orElse(null)
    }

    private fun markTainted(data: TaintedChunkData, pos: ChunkPos) {
        val packed = pos.toLong()
        if (!data.positions.add(packed)) return
        data.order.add(packed)
        data.setDirty()
    }

    private fun getTaintedData(level: ServerLevel): TaintedChunkData {
        return level.dataStorage.computeIfAbsent(
            { tag -> TaintedChunkData.load(tag) },
            { TaintedChunkData() },
            TAINTED_DATA_NAME,
        )
    }

    /** Each loaded chunk of [level] that holds a Wohlon cell, as `ChunkPos.toLong()`. A chunk
     *  that loaded less than [TAINTED_DISCOVERY_INTERVAL_TICKS] ago can be missing. */
    fun forEachLoadedWohlonChunk(level: ServerLevel, action: LongConsumer) {
        val states = chunkStates[level.dimension()] ?: return
        val chunkSource = level.chunkSource
        for ((packed, state) in states) {
            if (state.sectionMask == 0L) continue
            if (chunkSource.getChunkNow(ChunkPos.getX(packed), ChunkPos.getZ(packed)) == null) continue
            action.accept(packed)
        }
    }

    /** Compact (qx, qy, qz) tuple used while building the cell
     *  pool inside [trySpread]. Inline-`data class` lets us avoid
     *  manual hashCode/equals for the same flat-array effort. */
    private data class CellCoord(val qx: Int, val qy: Int, val qz: Int)

    /** Long set with O(1) add, remove and random index read. Swap-remove changes the order. */
    private class IndexedLongSet {
        private val values = LongArrayList()
        private val indexOf = Long2IntOpenHashMap().apply { defaultReturnValue(-1) }

        val size: Int get() = values.size

        fun isEmpty(): Boolean = values.isEmpty()

        fun get(index: Int): Long = values.getLong(index)

        fun add(value: Long): Boolean {
            if (indexOf.containsKey(value)) return false
            indexOf.put(value, values.size)
            values.add(value)
            return true
        }

        fun remove(value: Long): Boolean {
            val index = indexOf.remove(value)
            if (index < 0) return false
            val last = values.removeLong(values.size - 1)
            if (index < values.size) {
                values.set(index, last)
                indexOf.put(last, index)
            }
            return true
        }
    }

    /** Per-portal seed job: a queue of biome cells to convert
     *  + a ticks-remaining counter. Created by [startPortalSeed]
     *  and ticked by [tickSeedJobs]. */
    private data class SeedJob(val cells: ArrayDeque<CellCoord>, var ticksRemaining: Int)

/** Persistent set of chunks (by `ChunkPos.toLong`) that have
     *  ever held a Wohlon biome cell in this dimension. */
    class TaintedChunkData : SavedData() {

        @JvmField val positions: LongOpenHashSet = LongOpenHashSet()

        /** [positions] in index form for random picks. Not saved; [load] rebuilds it. */
        @JvmField val order: LongArrayList = LongArrayList()

        override fun save(tag: CompoundTag): CompoundTag {
            tag.put("positions", LongArrayTag(positions.toLongArray()))
            return tag
        }

        companion object {
            fun load(tag: CompoundTag): TaintedChunkData {
                val data = TaintedChunkData()
                if (tag.contains("positions", Tag.TAG_LONG_ARRAY.toInt())) {
                    for (v in tag.getLongArray("positions")) {
                        if (data.positions.add(v)) data.order.add(v)
                    }
                }
                return data
            }
        }
    }
}

