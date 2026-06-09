package org.shipwrights.enderkinesis.block

import com.mojang.logging.LogUtils
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
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import org.shipwrights.enderkinesis.dimension.TreeSegment
import org.shipwrights.enderkinesis.dimension.WogorTreeSkeleton
import org.shipwrights.enderkinesis.dimension.WohlonnogondoniaChunkGenerator
import org.shipwrights.enderkinesis.registry.EKBlocks
import java.util.BitSet
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Heart-candle tree grower — paints **exactly** a chunkgen
 * Child Tree at the ritual position, then places voxels in
 * **center-out distance order** at [MAX_PLACES_PER_TICK]
 * blocks per server tick.
 *
 * **Child Tree blueprint** (chunkgen
 * `WohlonnogondoniaChunkGenerator.buildChildTreeSkeleton`,
 * lines ~680–730):
 *  - Per-tree parameter draws from six independent `[0,1]`
 *    random hashes — size, aspect, lean, gravity, bias, flare —
 *    feed the same closed-form mix the chunkgen uses (full
 *    `scaleFactor` range `0.55–0.90`).
 *  - Single ellipsoidal attractor cloud `55 * scaleFactor` wide,
 *    `0.18–0.40` aspect ratio.
 *  - `WogorTreeSkeleton.build` with chunkgen-derived
 *    `attractionDist / killDist / stepSize / maxIterations /
 *    branchBias / maxThickness / buttressFlare / buttressRange /
 *    trunkLean / gravity` parameters.
 *  - **No hand-coded buttress roots** — child trees rely on the
 *    skeleton's `buttressFlare` for their trunk-base flare;
 *    they don't get the Mother Tree's 14 arc-and-plunge arms.
 *  - Wood painted via the chunkgen's SDF capsule painter **with
 *    bark noise** (`valueNoise3D` at `BARK_FREQ`).
 *  - Leaf blobs at every canopy tip via the chunkgen's SDF
 *    ellipsoid painter **with domain warp + threshold noise**,
 *    `rxz=7, ry=4` (the chunkgen child-tree blob radii).
 *
 * **Placement.** Every painted voxel is sorted by squared
 * Euclidean distance from the trunk origin. The queue is then
 * drained at [MAX_PLACES_PER_TICK] voxels per server tick, in
 * order. Wood voxels place as `WOGOR_WOOD`; leaf voxels place
 * as persistent Mangrove Leaves. No buds, no canSurvive gate —
 * center-out ordering means the tree visibly fills in radially
 * from the trunk base, but each voxel is just a direct
 * `setBlock` (wood has no support requirement).
 *
 * **Persistence.** Voxel array + leaf bitset + next-index
 * cursor go to `SavedData`. A tree mid-grow survives save /
 * reload, the next tick picks up at the persisted cursor.
 */
object WohlonnogondoniaTreeGrower {

    private val LOG = LogUtils.getLogger()

    private const val DATA_NAME = "enderkinesis_wohlon_trees"

    // ===================================================
    //   Child Tree leaf-blob radii — chunkgen verbatim
    // ===================================================

    /** Chunkgen `paintLeafTips(blobRxz=7, blobRy=4)` for child
     *  trees. Flatter than tall — leaves cluster around branch
     *  tips in pancake clumps, not spheres. */
    private const val LEAF_BLOB_RXZ = 7
    private const val LEAF_BLOB_RY = 4

    /** Continuous radius of each child-tree root arm at its trunk
     *  end — chunkgen `CHILD_TREE_ROOT_BASE_RADIUS`. Scales with
     *  the buttressed trunk so the root blends in cleanly. */
    private const val CHILD_TREE_ROOT_BASE_RADIUS = 3.5

    // ===================================================
    //   Terrain-plane fit (gentle trunk-direction influence)
    // ===================================================

    /** Heightmap-scan disc radius around the candle for the
     *  plane fit. Same value as the original heart-candle design. */
    private const val SCAN_RADIUS = 16
    private const val SCAN_STRIDE = 2

    /** Cap on each XZ component of the plane normal (radians-ish:
     *  `0.20` ≈ 11°). Without the clamp, a cliff-side ritual could
     *  hand the skeleton a 30°+ tilt and the trunk would lean
     *  enough to break the chunkgen child-tree silhouette. With
     *  the clamp the influence stays subtle — flat-ground trees
     *  remain pure-vertical, sloped-ground trees lean *into* the
     *  hillside by a few degrees, the trunk's natural curve from
     *  `trunkLean` smooth-noise drift is unchanged, the roots
     *  (which use `cosA`/`sinA` + heightmap surface tracking,
     *  not `defaultDir`) are unaffected. */
    private const val MAX_TILT = 0.20

    // ===================================================
    //   Hollow zone around the portal anchor
    // ===================================================

    /** Oblate spheroid (rx=rz, ry shorter) carved out of the
     *  tree's voxel set, centred on the portal position so the
     *  player has a clear alcove around the candle. 9 × 9 × 7
     *  blocks → rx = rz = 4.5, ry = 3.5. */
    private const val HOLLOW_RX = 4.5
    private const val HOLLOW_RY = 3.5
    private const val HOLLOW_RZ = 4.5

    /** Length in blocks of the 2×2 exit tunnel carved horizontally
     *  out from the candle bubble in a random yaw direction.
     *  ≥ max canopyRx (55 × 0.9 ≈ 50) so the tunnel always exits
     *  the tree's footprint at the far end. */
    private const val TUNNEL_LENGTH = 55

    /** Bark / leaf noise constants — chunkgen companion
     *  `private const val`s, inlined here. */
    private const val BARK_FREQ = 0.35
    private const val LEAF_LOW_FREQ = 0.14
    private const val LEAF_HIGH_FREQ = 0.65
    private const val LEAF_LOW_AMP = 0.30
    private const val LEAF_HIGH_AMP = 0.25
    private const val LEAF_THRESHOLD_MAX = 1.0 + LEAF_LOW_AMP + LEAF_HIGH_AMP
    private const val LEAF_WARP_FREQ = 0.10
    private const val LEAF_WARP_AMP = 4.0
    private const val LEAF_WARP_AMP_INT = 4

    // ===================================================
    //   Growth control
    // ===================================================

    /** Frontier voxels scanned per server tick. The frontier
     *  for a typical child tree is ~3–15 k voxels in steady
     *  state; we want to scan all of it every tick so a
     *  voxel waiting on a maturing neighbour gets re-checked
     *  the moment that neighbour matures. The cap is here as a
     *  safety against pathological cases (a 100 k-voxel
     *  frontier would spend ~10 ms / tick scanning).
     *
     *  **Why this matters for the roots.** Child trees have a
     *  buttress-flared trunk base (`maxThickness + buttressFlare`
     *  → ~10-block-radius base) that contributes ~3 k voxels
     *  at distances 0–10 from the candle origin. The
     *  root-emergence voxels live in roughly the same distance
     *  band (radius 3 from trunk centre, Y ≈ candle.y + 1..4
     *  → distance ~3–5). At `MAX_SCAN_PER_TICK = 2000` the
     *  scan would exhaust on early trunk-base voxels (most
     *  still waiting on the bottom layer to mature) before
     *  ever reaching the root-emergence voxels — they stayed
     *  in the frontier but never got a sturdy-face check. */
    private const val MAX_SCAN_PER_TICK = 50_000

    /** Bud / leaf placements per server tick. Slow pacing
     *  knob — combined with the 15–21 tick bud maturation
     *  window, this is what makes the tree feel like it's
     *  taking its time. For a typical 30–80 k voxel child tree
     *  at 25/tick, the placement work is spread over
     *  1 200–3 200 ticks = ~1–3 minutes, with the visible
     *  bud-to-wood maturation chain extending that further as
     *  the wave climbs the canopy. */
    private const val MAX_PLACES_PER_TICK = 25

    /** Max ticks any single wood/leaves layer can be the "current"
     *  layer before the next layer starts opening up regardless of
     *  whether the previous one has fully placed. This is the
     *  fallback for layers that contain voxels which can't ever
     *  place (no support, stuck on a non-cardinal-adjacent
     *  centerline gap, etc.) — without it, one stuck voxel
     *  freezes the whole tree mid-build. Also produces the
     *  intentional "filling out starts shortly after the centerline
     *  begins, not after it fully reaches the top" overlap. */
    private const val LAYER_ADVANCE_INTERVAL = 20

    /** Sanity ceiling on total voxel count per tree. */
    private const val MAX_VOXELS_PER_TREE = 800_000

    /** Radius of the heightmap disc cached on the main thread
     *  before the off-thread tree-build kicks off. Child trees'
     *  buttress roots reach up to `~45` blocks from the trunk
     *  centre (`ROOT_LENGTH_MAX` + emergence offset); 64 gives a
     *  comfortable buffer so the off-thread root painter never
     *  needs a heightmap lookup outside the pre-cached area. */
    private const val HEIGHTMAP_PRECACHE_RADIUS = 64

    /** Off-thread tree builder. Single thread so two
     *  simultaneously-completed rituals queue rather than
     *  competing on the same CPU; daemon thread so JVM shutdown
     *  isn't blocked. */
    private val BACKGROUND_EXECUTOR: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "EnderkinesisWohlonTreeBuilder").apply { isDaemon = true }
        }

    private val CONVERTS_TO_MUD: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "converts_to_mud"))

    /** Wider replaceable rule used only for root voxels —
     *  covers `#converts_to_mud` plus water, lava,
     *  `#minecraft:leaves`, and `#minecraft:base_stone_overworld`,
     *  per `data/enderkinesis/tags/blocks/root_replaceable.json`.
     *  Trunk and canopy stay on the stricter [isTreeReplaceable]. */
    private val ROOT_REPLACEABLE: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "root_replaceable"))

    private val SIX_DIRECTIONS: Array<Direction> = Direction.values()

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::tickLevel)
    }

    @JvmStatic
    fun startTree(level: ServerLevel, portalPos: BlockPos) {
        // ============================================================
        //   Main-thread phase — collect everything the off-thread
        //   build will need that requires level / chunk access.
        // ============================================================
        val candlePos = portalPos.below(2)
        val baseY = level.getHeight(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, candlePos.x, candlePos.z,
        )
        // No early-abort on low ground: `setBlock` below
        // `level.minBuildHeight` is already a no-op, so any
        // buttress / root voxels that would land outside the
        // build range just silently drop and the rest of the
        // tree grows normally.

        // Plane fit needs `level.getHeight` over a 16-radius
        // disc → main thread.
        val terrainNormal = fitTerrainNormal(level, candlePos)

        // Per-tree random draws need `level.random` → main thread.
        val random = level.random
        val sizeHash = random.nextDouble()
        val aspectHash = random.nextDouble()
        val leanHash = random.nextDouble()
        val gravityHash = random.nextDouble()
        val biasHash = random.nextDouble()
        val flareHash = random.nextDouble()
        val tunnelYaw = random.nextDouble() * (2.0 * Math.PI)
        val seed = random.nextInt()

        // Heightmap cache for the root painter — pre-cached on
        // main thread so the off-thread paint doesn't need to
        // touch the level. `paintChildTreeRoots` queries surface
        // height at each step along each arc arm, well inside
        // the [HEIGHTMAP_PRECACHE_RADIUS] disc.
        val heightCache = preCacheSurfaceY(level, candlePos, HEIGHTMAP_PRECACHE_RADIUS)

        val params = TreeBuildParams(
            candlePos = candlePos,
            portalPos = portalPos,
            baseY = baseY,
            terrainNormal = terrainNormal,
            sizeHash = sizeHash, aspectHash = aspectHash,
            leanHash = leanHash, gravityHash = gravityHash,
            biasHash = biasHash, flareHash = flareHash,
            tunnelYaw = tunnelYaw,
            seed = seed,
        )

        // ============================================================
        //   Off-thread phase — skeleton + SDF paint + sort + job
        //   build. None of this touches the level.
        // ============================================================
        val server = level.server
        BACKGROUND_EXECUTOR.execute {
            val job = try {
                buildTreeJob(params, heightCache)
            } catch (t: Throwable) {
                LOG.error("WohlonTree: off-thread build failed at {}", params.candlePos, t)
                null
            } ?: return@execute

            // ============================================================
            //   Back on the main thread — register the finished job
            //   so the tick loop starts placing voxels next tick.
            // ============================================================
            server.execute {
                installTreeJob(level, params, job)
            }
        }
    }

    private data class TreeBuildParams(
        val candlePos: BlockPos,
        val portalPos: BlockPos,
        val baseY: Int,
        val terrainNormal: DoubleArray,
        val sizeHash: Double,
        val aspectHash: Double,
        val leanHash: Double,
        val gravityHash: Double,
        val biasHash: Double,
        val flareHash: Double,
        /** Yaw (radians, world XZ plane) of the 2×2 exit tunnel
         *  carved from the candle bubble out through the tree. */
        val tunnelYaw: Double,
        val seed: Int,
    )

    /** Pre-cache the surface block Y for every column in a disc
     *  of [radius] around [center]. Stored as packed `(x,z) →
     *  surfaceY` so the off-thread root painter can do constant-
     *  time lookups without touching the level. */
    private fun preCacheSurfaceY(level: ServerLevel, center: BlockPos, radius: Int): HeightmapCache {
        val r2 = radius * radius
        val cache = HeightmapCache((r2 * 4))
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                if (dx * dx + dz * dz > r2) continue
                val x = center.x + dx
                val z = center.z + dz
                val surfaceY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z,
                ) - 1
                cache.put(x, z, surfaceY)
            }
        }
        return cache
    }

    /** Packed `(x, z) → surfaceY` cache. Used by the off-thread
     *  root painter as a stand-in for `level.getHeight`. */
    private class HeightmapCache(initialCapacity: Int) {
        private val data = HashMap<Long, Int>(initialCapacity)
        fun put(x: Int, z: Int, y: Int) {
            data[(x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)] = y
        }
        fun get(x: Int, z: Int): Int {
            return data[(x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)] ?: 0
        }
    }

    /** All the heavy work: skeleton build + SDF paint of canopy /
     *  roots / leaves + hollow carve + three-phase sort + job
     *  construction. Runs entirely off-thread. No level access. */
    private fun buildTreeJob(params: TreeBuildParams, heightCache: HeightmapCache): TreeJob? {
        val candlePos = params.candlePos
        val portalPos = params.portalPos
        val baseX = candlePos.x.toDouble()
        val baseZ = candlePos.z.toDouble()
        val baseYD = params.baseY.toDouble()
        val seed = params.seed

        val scaleFactor = 0.55 + params.sizeHash * 0.35
        val ryRatio = 0.18 + params.aspectHash * 0.22
        val canopyRx = 55.0 * scaleFactor
        val canopyRy = canopyRx * ryRatio
        val canopyCy = baseYD + 60.0 * scaleFactor + canopyRy * 0.7
        val canopyVolume = 4.19 * canopyRx * canopyRx * canopyRy
        val attractorCount = (canopyVolume / 22.0).toInt().coerceIn(400, 2500)
        val maxThickness = (4.0 + params.sizeHash * 4.0).toInt()
        // Full buttress flare — same values the chunkgen child trees
        // use. The previous "tumorous sphere" complaint was about
        // *growth pattern*, not the buttress's final shape: when the
        // wood phase placed an entire cross-section per tick (no layer
        // phasing), the flare appeared all at once as a sphere-shaped
        // bulb. With layer phasing in place the buttress now builds
        // up through the same centerline → rings outward wave the rest
        // of the trunk uses, so the flared bottom emerges naturally
        // rather than popping into existence.
        val buttressFlare = (maxThickness * (1.6 + params.flareHash * 0.6)).toInt()
        val buttressRange = canopyRy * 1.2 + 8.0
        val attractionDist = 14.0 + scaleFactor * 10.0
        val killDist = 3.0 + scaleFactor * 2.0
        val stepSize = 3.0 + scaleFactor * 1.2
        val maxIterations = (80 + scaleFactor * 50).toInt()
        val branchBias = 0.10 + params.biasHash * 0.15
        val trunkLean = 0.20 + params.leanHash * 0.25
        val gravity = 0.08 + params.gravityHash * 0.12

        val attractors = WogorTreeSkeleton.ellipsoidAttractors(
            cx = baseX, cy = canopyCy, cz = baseZ,
            rx = canopyRx, ry = canopyRy, rz = canopyRx,
            count = attractorCount,
            seed = seed,
        )
        val canopy = WogorTreeSkeleton.build(
            originX = baseX, originY = baseYD, originZ = baseZ,
            attractors = attractors,
            defaultDir = params.terrainNormal,
            attractionDist = attractionDist,
            killDist = killDist,
            stepSize = stepSize,
            maxIterations = maxIterations,
            branchBias = branchBias,
            maxThickness = maxThickness,
            thicknessScale = 0.4,
            buttressFlare = buttressFlare,
            buttressRange = buttressRange,
            trunkLean = trunkLean,
            gravity = gravity,
        )

        // ----- Paint canopy + trunk + roots + leaves -----
        val woodVoxels = HashSet<Long>(1 shl 15)
        for (seg in canopy.segments) paintSegmentSdf(seg, woodVoxels)
        // Child-tree buttress roots — 8..11 hand-coded arc arms,
        // chunkgen `paintChildTreeRoots`. Painted into a
        // separate set first so we can flag each root voxel
        // later (the root path uses the wider
        // `root_replaceable` rule that lets it push through
        // water / lava / leaves / stone), then unioned into
        // `woodVoxels` so the hollow carve and the
        // distance-sort treat the canopy + trunk + roots as
        // one wood pass.
        val rootPositions = HashSet<Long>()
        val rootSegments = mutableListOf<TreeSegment>()
        paintChildTreeRoots(
            { x, z -> heightCache.get(x, z) },
            baseX, baseYD, baseZ, seed, rootPositions, rootSegments,
        )
        woodVoxels.addAll(rootPositions)

        val leafVoxels = HashSet<Long>(1 shl 15)
        for (tip in canopy.tips) {
            paintLeafBlobSdf(tip.x, tip.y, tip.z, LEAF_BLOB_RXZ, LEAF_BLOB_RY, leafVoxels)
        }
        leafVoxels.removeAll(woodVoxels)

        // ----- Carve hollow zone around the portal anchor -----
        // Oblate spheroid 9 × 9 × 7 centred on portalPos. Wood
        // (canopy + trunk + root voxels) and leaves inside this
        // volume are scrubbed so the player has a clean alcove
        // around the ritual candle. Roots emerge at radius 3
        // from the trunk centre and rise up to ~candle.y + 6,
        // which overlaps the hollow's footprint; the arms get
        // their first few voxels scooped, so what's left walks
        // out of the alcove's edge instead of from the trunk
        // base inside it.
        val carved = carveHollow(woodVoxels, leafVoxels, portalPos)
        // Exit tunnel: 2×2 cross-section, [TUNNEL_LENGTH] blocks long,
        // extending from the candle bubble outward in
        // `params.tunnelYaw`. Removes tree-voxels in the path; the
        // returned cells are applied as air at install time so any
        // existing terrain in the path is cleared too (the off-thread
        // build can't touch the level).
        val tunnelPositions = carveExitTunnel(
            woodVoxels, leafVoxels, candlePos, params.tunnelYaw,
        )
        // The carve removes voxels from `woodVoxels` but not
        // from `rootPositions`. Any root-side voxel that landed
        // inside the hollow OR the tunnel needs to come off
        // `rootPositions` too — otherwise `rootCount + nonRootWoodCount`
        // ends up larger than `woodVoxels.size`, the leaf phase's
        // `leafStart` overshoots `totalVoxels`, and the sort
        // packing throws IndexOutOfBounds when it tries to
        // write `sortedVoxels[totalVoxels]`.
        rootPositions.retainAll(woodVoxels)

        val totalVoxels = woodVoxels.size + leafVoxels.size
        if (totalVoxels == 0) {
            LOG.info("WohlonTree: empty paint result at {}", candlePos)
            return null
        }
        if (totalVoxels > MAX_VOXELS_PER_TREE) {
            LOG.warn(
                "WohlonTree: {} voxels exceeds cap {} at {}, aborting",
                totalVoxels, MAX_VOXELS_PER_TREE, candlePos,
            )
            return null
        }

        // ----- Three-phase sort: roots → non-root wood (trunk
        //       first, then branches) → leaves.
        //
        // The frontier scan walks set bits via `nextSetBit` in
        // ascending index order, and the per-tick place budget
        // exhausts before the scan can reach the trunk's wood
        // when the queue head is full of root voxels. So just by
        // putting all the root indices ahead of all the non-
        // root-wood indices, we get the desired growth phasing:
        // the wave starts on roots, then once those are placed
        // the trunk picks up, then branches, then leaves.
        //
        // Within each wood phase, sort by squared distance from
        // the candle origin so the center-out shape is
        // preserved — for non-root wood, that means the
        // buttressed trunk base (distance ~0–15) precedes the
        // canopy (distance ~30+), which is the chunkgen
        // "trunk → branches" order naturally.
        //
        // Leaves sort by distance-to-nearest-skeleton-segment,
        // unchanged from before — when a tip bud matures, its
        // innermost leaves fire that same tick rather than
        // waiting for a global-distance walk through tens of
        // thousands of higher-distance wood voxels.
        //
        // Final layout: `[0, R)` roots → `[R, R+W)` non-root
        // wood → `[R+W, total)` leaves.
        val rootList = rootPositions.toLongArray()
        val rootCount = rootList.size
        val nonRootWoodSet = HashSet(woodVoxels)
        nonRootWoodSet.removeAll(rootPositions)
        val nonRootWoodList = nonRootWoodSet.toLongArray()
        val nonRootWoodCount = nonRootWoodList.size
        val leafCount = leafVoxels.size
        val originX = candlePos.x
        val originY = candlePos.y
        val originZ = candlePos.z

        // **Two-key root sort: origin distance (primary) then
        // root-skeleton distance (secondary).** Skeleton-only
        // sort had a subtle problem — every voxel on any arm's
        // centerline has segment-distance 0, regardless of
        // whether it's at the arm's emergence point near the
        // trunk or way out at the tip. So a skeleton-only sort
        // interleaved tip-centerline voxels with emergence-
        // centerline voxels at the head of the queue, and the
        // per-tick 25-voxel budget burned scan slots on tip
        // voxels before the wave reached the emergence
        // neighbourhood, which read as roots growing inward
        // from the tips instead of outward from the trunk.
        //
        // Primary key (origin distance) restores the trunk-out
        // wave: lower origin-distance → earlier in queue → an
        // arm's emergence segments fire first, then walk-phase
        // segments at the next origin-distance band, then tip
        // segments last. Secondary key (segment distance)
        // preserves the "centerline first within each band"
        // behaviour — voxels right on the arc spine come before
        // the bark-noise rim voxels at the same origin distance.
        //
        // Bit layout for the packed sort key:
        //   [63:48]  origin distSq  (16 bits, capped at 0x7FFF
        //                            — bit 47 cleared to keep
        //                            the signed-long sort
        //                            monotonic; typical child
        //                            tree maxes out around
        //                            ~2500 = 0x9C4, well inside)
        //   [47:32]  segment distSq (16 bits — max capsule
        //                            radius is ~4 → distSq up
        //                            to ~16, room to spare)
        //   [31: 0]  original index (32 bits — covers any
        //                            sensible voxel count)
        // Layer-by-layer growth: distance from segment centerline is
        // the *primary* sort key, so every voxel at the same
        // "concentric ring" places in one wave before the next ring
        // begins. segDistSq = 0 (the centerline) places first across
        // every root arm — that's the thin outline. Then segDistSq = 1
        // (one block off the line) places, then 2, etc., out to the
        // full chunkgen radius. The frontier-based placement still
        // requires support, so within a ring voxels cascade
        // out-from-trunk along each arm; the secondary
        // origin-distance key tiebreaks toward base-first per arm.
        //
        // Bit layout:
        //   [63:48]  segment distSq (16 bits — max capsule radius ~4,
        //                            so distSq stays well inside)
        //   [47:32]  origin distSq  (16 bits, capped at 0x7FFF)
        //   [31: 0]  original index (32 bits)
        val rootPacked = LongArray(rootCount)
        for (i in 0 until rootCount) {
            val pos = BlockPos.of(rootList[i])
            val dx = pos.x - originX
            val dy = pos.y - originY
            val dz = pos.z - originZ
            val originDistSq = (dx * dx + dy * dy + dz * dz).coerceIn(0, 0x7FFF).toLong()
            val px = pos.x + 0.5; val py = pos.y + 0.5; val pz = pos.z + 0.5
            var minSegDistSqD = Double.MAX_VALUE
            for (seg in rootSegments) {
                val d = distSqPointToSegment(px, py, pz, seg)
                if (d < minSegDistSqD) minSegDistSqD = d
            }
            val segDistSq = minSegDistSqD.toInt().coerceIn(0, 0xFFFF).toLong()
            rootPacked[i] = (segDistSq shl 48) or
                (originDistSq shl 32) or
                (i.toLong() and 0xFFFFFFFFL)
        }
        java.util.Arrays.sort(rootPacked)

        // Layer-by-layer trunk + branch growth — same key shape as
        // the root sort above. Distance from the nearest skeleton
        // segment is the primary key, so every voxel at "ring N"
        // around a centerline places before any voxel at ring N+1.
        // The trunk's centerline (a vertical column up the axis)
        // and every branch's centerline appear first as thin lines,
        // then the trunk thickens one block at a time outward while
        // each branch thickens in parallel; thinner branches finish
        // their layer count first and the thicker trunk keeps going.
        // Within a ring, origin distance tiebreaks toward base-first.
        //
        // Bit layout:
        //   [63:48]  segment distSq (16 bits)
        //   [47:32]  origin distSq  (16 bits, capped at 0x7FFF)
        //   [31: 0]  original index (32 bits)
        val woodSegments = canopy.segments
        val woodPacked = LongArray(nonRootWoodCount)
        for (i in 0 until nonRootWoodCount) {
            val pos = BlockPos.of(nonRootWoodList[i])
            val dx = pos.x - originX; val dy = pos.y - originY; val dz = pos.z - originZ
            val originDistSq = (dx * dx + dy * dy + dz * dz).coerceIn(0, 0x7FFF).toLong()
            val px = pos.x + 0.5; val py = pos.y + 0.5; val pz = pos.z + 0.5
            var minSegDistSqD = Double.MAX_VALUE
            for (seg in woodSegments) {
                val d = distSqPointToSegment(px, py, pz, seg)
                if (d < minSegDistSqD) minSegDistSqD = d
            }
            val segDistSq = minSegDistSqD.toInt().coerceIn(0, 0xFFFF).toLong()
            woodPacked[i] = (segDistSq shl 48) or
                (originDistSq shl 32) or
                (i.toLong() and 0xFFFFFFFFL)
        }
        java.util.Arrays.sort(woodPacked)

        val leafList = leafVoxels.toLongArray()
        val leafPacked = LongArray(leafCount)
        val segments = canopy.segments
        for (i in 0 until leafCount) {
            val pos = BlockPos.of(leafList[i])
            val px = pos.x + 0.5; val py = pos.y + 0.5; val pz = pos.z + 0.5
            var minDistSq = Double.MAX_VALUE
            for (seg in segments) {
                val d = distSqPointToSegment(px, py, pz, seg)
                if (d < minDistSq) minDistSq = d
            }
            val intDist = minDistSq.toInt().coerceIn(0, 0x7FFFFFFF)
            leafPacked[i] = (intDist.toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)
        }
        java.util.Arrays.sort(leafPacked)

        val sortedVoxels = LongArray(totalVoxels)
        val sortedIsLeaf = BitSet(totalVoxels)
        val sortedIsRoot = BitSet(totalVoxels)
        // Per-voxel "distance from nearest segment line" extracted from
        // the same packed sort keys. Used immediately below to compute
        // layer boundaries; not stored on the job past that.
        val segDistSqArr = IntArray(totalVoxels)
        // Phase 1 — roots at indices `[0, rootCount)`.
        for (i in 0 until rootCount) {
            sortedVoxels[i] = rootList[(rootPacked[i] and 0xFFFFFFFFL).toInt()]
            segDistSqArr[i] = ((rootPacked[i] ushr 48) and 0xFFFF).toInt()
            sortedIsRoot.set(i)
        }
        // Phase 2 — non-root wood at indices `[rootCount, rootCount+nonRootWoodCount)`.
        for (i in 0 until nonRootWoodCount) {
            sortedVoxels[rootCount + i] = nonRootWoodList[(woodPacked[i] and 0xFFFFFFFFL).toInt()]
            segDistSqArr[rootCount + i] = ((woodPacked[i] ushr 48) and 0xFFFF).toInt()
        }
        // Phase 3 — leaves at indices `[rootCount+nonRootWoodCount, total)`.
        val leafStart = rootCount + nonRootWoodCount
        for (i in 0 until leafCount) {
            sortedVoxels[leafStart + i] = leafList[(leafPacked[i] and 0xFFFFFFFFL).toInt()]
            // Leaf packing uses `intDist shl 32` (no segDistSq slot above
            // bit 47), so extract from bits 32–62. Capped to 16 bits so
            // it composes with the root / wood values in `segDistSqArr`.
            segDistSqArr[leafStart + i] = ((leafPacked[i] ushr 32) and 0xFFFF).toInt()
            sortedIsLeaf.set(leafStart + i)
        }

        // Layer boundaries: every index where `segDistSqArr` value
        // changes within the WOOD / LEAVES phases. `layerEnds[N]` is
        // the exclusive end of layer N; layer 0 is `[0, layerEnds[0])`,
        // layer N is `[layerEnds[N-1], layerEnds[N])`.
        //
        // The ROOTS phase is intentionally NOT subdivided here — it's
        // collapsed into a single layer `[0, rootCount)`. Roots walk
        // along arbitrary 3D segments whose centerline voxels are
        // often non-cardinal-adjacent (diagonal stepping); strict
        // layer-N → layer-N cascade can't chain them, so layer 0
        // would stall mid-tube. The natural cascade (without per-ring
        // gating) already produces a layered look for tubular roots,
        // and that's what the user's been seeing.
        val layerEndsList = ArrayList<Int>()
        if (rootCount > 0) layerEndsList.add(rootCount)
        var lastSeg = -1
        for (i in rootCount until totalVoxels) {
            if (segDistSqArr[i] != lastSeg) {
                if (i > rootCount) layerEndsList.add(i)
                lastSeg = segDistSqArr[i]
            }
        }
        if (layerEndsList.isEmpty() || layerEndsList.last() < totalVoxels) {
            layerEndsList.add(totalVoxels)
        }
        val layerEnds = layerEndsList.toIntArray()

        val job = TreeJob(
            voxels = sortedVoxels,
            isLeaf = sortedIsLeaf,
            isRoot = sortedIsRoot,
            placed = BitSet(totalVoxels),
            layerEnds = layerEnds,
            tunnelCarvePositions = tunnelPositions.toLongArray(),
        )
        // Builds the position→index map and the initial
        // frontier from the painted set. Pure data work, no
        // level access — safe off-thread.
        job.rebuildRuntimeState()

        LOG.info(
            "WohlonTree: Child Tree painted at {} (scale={} maxThk={}) → roots={} trunk+branches={} leaves={} carved={} (seg={}, tip={})",
            candlePos, "%.2f".format(scaleFactor), maxThickness,
            rootCount, nonRootWoodCount, leafCount, carved,
            canopy.segments.size, canopy.tips.size,
        )
        return job
    }

    /** Main-thread install — register the finished job so the
     *  per-tick loop picks it up next tick. The off-thread
     *  builder already filled in the runtime indices, so all
     *  we have to do here is hand the job to SavedData and
     *  mark it dirty. */
    private fun installTreeJob(level: ServerLevel, params: TreeBuildParams, job: TreeJob) {
        val data = getData(level)
        data.jobs.add(job)
        data.setDirty()
        // Apply the exit-tunnel air carve once, on the main thread —
        // the off-thread builder couldn't touch the level, so any
        // terrain blocks in the tunnel's path are still solid right
        // now. Flag 2 (UPDATE_CLIENTS only) on each setBlock to skip
        // neighbour-update cascades — same convention the placement
        // path uses.
        if (job.tunnelCarvePositions.isNotEmpty()) {
            val air = Blocks.AIR.defaultBlockState()
            val mutPos = BlockPos.MutableBlockPos()
            for (packed in job.tunnelCarvePositions) {
                mutPos.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed))
                level.setBlock(mutPos, air, 2)
            }
        }
        // Plant a Heart of the Wild one block above the portal anchor.
        // The portal sits at `params.portalPos` (2 above the candle);
        // the heart goes one above that, where the player sees it as
        // the focus of the alcove. Flag 3 (UPDATE_CLIENTS + neighbour)
        // so the block-entity attaches and starts ticking immediately.
        // Heart sits *at* the portal position so its 1×1×1
        // collision occupies the same cell the
        // [WohlonnogondoniaPortalManager] scans for teleport. The
        // teleport AABB is `pos ± TELEPORT_RADIUS = ±0.7`, so an
        // entity in any of the 6 face-adjacent cells (the alcove's
        // floor next to the candle, the air pocket above the heart,
        // the carved-out tunnel mouth) still triggers — but they
        // can't stand inside the heart itself.
        //
        // Plain (non-Mother) heart — this is the ritual tree's
        // decorative heart, not the Mother Tree's. The Mother flag
        // is reserved for the Wohlonnogondonia chunkgen's central
        // heart (placed by `WohlonnogondoniaChunkGenerator` at
        // `(0, HEART_Y, 0)`); putting it here would arm the
        // dim-wipe catastrophe trigger on every ritual portal.
        level.setBlock(params.portalPos, EKBlocks.HEART_OF_THE_WILD.get().defaultBlockState(), 3)
        LOG.info(
            "WohlonTree: installed job at {} — growth begins next tick " +
                "(tunnel cells={}, heart={})",
            params.candlePos, job.tunnelCarvePositions.size, params.portalPos,
        )
    }

    /** Remove every voxel whose centre lies inside the oblate
     *  spheroid `( (dx/rx)² + (dy/ry)² + (dz/rz)² ≤ 1 )`
     *  centred on [portalPos]. Returns the number of voxels
     *  removed across both sets. */
    private fun carveHollow(
        woodVoxels: MutableSet<Long>, leafVoxels: MutableSet<Long>, portalPos: BlockPos,
    ): Int {
        val px = portalPos.x
        val py = portalPos.y
        val pz = portalPos.z
        val rx2 = HOLLOW_RX * HOLLOW_RX
        val ry2 = HOLLOW_RY * HOLLOW_RY
        val rz2 = HOLLOW_RZ * HOLLOW_RZ
        var removed = 0
        val isInside = { packed: Long ->
            val pos = BlockPos.of(packed)
            val dx = (pos.x - px).toDouble()
            val dy = (pos.y - py).toDouble()
            val dz = (pos.z - pz).toDouble()
            val nd = (dx * dx) / rx2 + (dy * dy) / ry2 + (dz * dz) / rz2
            nd <= 1.0
        }
        val woodIter = woodVoxels.iterator()
        while (woodIter.hasNext()) {
            if (isInside(woodIter.next())) { woodIter.remove(); removed++ }
        }
        val leafIter = leafVoxels.iterator()
        while (leafIter.hasNext()) {
            if (isInside(leafIter.next())) { leafIter.remove(); removed++ }
        }
        return removed
    }

    /** Carve a 2×2 cross-section tunnel from [candlePos] outward in
     *  the [yawRadians] horizontal direction for [TUNNEL_LENGTH]
     *  blocks. Tunnel floor is at `candlePos.y`, ceiling at
     *  `candlePos.y + 1` (two-tall). Width is two voxels
     *  perpendicular to the yaw, centred on the axis.
     *
     *  Removes every tunnel cell from [woodVoxels] and [leafVoxels]
     *  so the tree won't place there. Returns the set of cells —
     *  the caller hands these to [installTreeJob] which applies
     *  air at install time, so any existing terrain in the path is
     *  carved out too (the off-thread build has no level access). */
    private fun carveExitTunnel(
        woodVoxels: MutableSet<Long>,
        leafVoxels: MutableSet<Long>,
        candlePos: BlockPos,
        yawRadians: Double,
    ): HashSet<Long> {
        val cells = HashSet<Long>(TUNNEL_LENGTH * 8)
        val fx = cos(yawRadians)
        val fz = sin(yawRadians)
        // Perpendicular horizontal (90° CCW from forward).
        val rx = -fz
        val rz = fx
        val cx = candlePos.x + 0.5
        val cz = candlePos.z + 0.5
        val cy = candlePos.y
        // Step along the axis at 0.5 to catch every voxel even when
        // the yaw is diagonal. The HashSet dedupes overlapping samples.
        var t = 0.0
        while (t <= TUNNEL_LENGTH) {
            val baseX = cx + fx * t
            val baseZ = cz + fz * t
            for (w in 0..1) {
                // Perpendicular offset of (-0.5, +0.5) keeps the two-
                // voxel cross-section centred on the axis.
                val offset = w - 0.5
                val wx = baseX + rx * offset
                val wz = baseZ + rz * offset
                val voxelX = floor(wx).toInt()
                val voxelZ = floor(wz).toInt()
                for (h in 0..1) {
                    cells.add(BlockPos.asLong(voxelX, cy + h, voxelZ))
                }
            }
            t += 0.5
        }
        woodVoxels.removeAll(cells)
        leafVoxels.removeAll(cells)
        return cells
    }

    // ===================================================
    //   Per-tick center-out placement
    // ===================================================

    private fun tickLevel(level: ServerLevel) {
        val data = level.dataStorage.get(
            { tag -> TreeJobsData.load(tag) }, DATA_NAME,
        ) ?: return
        if (data.jobs.isEmpty()) return
        val iter = data.jobs.iterator()
        var changed = false
        while (iter.hasNext()) {
            val job = iter.next()
            if (tickJob(level, job)) changed = true
            if (job.placed.cardinality() == job.voxels.size) {
                iter.remove()
                changed = true
            }
        }
        if (changed) data.setDirty()
    }

    /** Center-out frontier scan. The voxel array is sorted by
     *  squared distance from origin (closest first), so a
     *  `BitSet.nextSetBit` traversal walks the frontier in
     *  center-outward order. Each iteration:
     *   1. **Wood voxels** check the 6 cardinal neighbours for a
     *      sturdy face; if any exists, a Wogor Bud lands with
     *      `FACING = supportDir`. Buds cannot land without that
     *      sturdy face — they would drop on placement otherwise.
     *   2. **Leaf voxels** check the 6 neighbours for an
     *      already-placed Wogor Wood block or another already-
     *      placed Mangrove Leaves block. Leaves don't need a
     *      sturdy face, but they wait until *something* of the
     *      tree has reached them.
     *
     *  Placement also checks [isTreeReplaceable] — buds and
     *  leaves replace **only** air, vanilla-replaceable blocks,
     *  Mud, and `enderkinesis:converts_to_mud`-tagged blocks.
     *  Anything else (existing tree wood, structures, stone
     *  someone built up to) stops that voxel from ever placing.
     *
     *  Voxels that fail their per-tick check stay in the
     *  frontier — they're re-tested next tick. As the wood-set
     *  buds mature into Wogor Wood (15–21 ticks per stage chain
     *  via vanilla scheduled ticks), the sturdy-face / wood-
     *  neighbour checks start passing for voxels further from
     *  the origin, the wave propagates outward. */
    private fun tickJob(level: ServerLevel, job: TreeJob): Boolean {
        var changed = false
        var scanBudget = MAX_SCAN_PER_TICK
        var placeBudget = MAX_PLACES_PER_TICK
        val newlyPlaced = ArrayList<Int>(64)
        var i = job.frontier.nextSetBit(0)
        while (i >= 0 && scanBudget > 0 && placeBudget > 0) {
            scanBudget--
            val pos = BlockPos.of(job.voxels[i])
            val isLeaf = job.isLeaf.get(i)

            var didPlace = false
            if (isLeaf) {
                // Leaves wait for a branch (Wogor Wood) OR an
                // already-placed Mangrove Leaves voxel to be
                // adjacent. That keeps the foliage tethered to
                // its tip's branch — each tip's leaf blob bursts
                // only after the wave's wood has matured to its
                // crown, then propagates outward through the
                // blob one voxel per tick.
                var hasNeighbour = false
                for (dir in SIX_DIRECTIONS) {
                    val nState = level.getBlockState(pos.relative(dir))
                    if (
                        nState.`is`(EKBlocks.WOGOR_WOOD.get()) ||
                        nState.`is`(EKBlocks.WOGOR_LEAVES.get())
                    ) {
                        hasNeighbour = true
                        break
                    }
                }
                if (hasNeighbour) {
                    val existing = level.getBlockState(pos)
                    if (isTreeReplaceable(existing)) {
                        val state = EKBlocks.WOGOR_LEAVES.get().defaultBlockState()
                            .setValue(LeavesBlock.PERSISTENT, true)
                        // Flag 2 (UPDATE_CLIENTS only) — leaves
                        // are persistent and have no canSurvive
                        // requirement, and we want to avoid
                        // their placement triggering neighbour
                        // updateShape that could drop adjacent
                        // buds.
                        level.setBlock(pos, state, 2)
                    }
                    didPlace = true
                }
            } else {
                // Wood (root, trunk, or canopy) — all gated on
                // a sturdy cardinal-face neighbour for the bud
                // animation to work, with `FACING = supportDir`
                // so vanilla `canSurvive` stays happy.
                //
                // For roots specifically: even when an arm
                // crosses water or lava, the wave's chain of
                // previously-matured wood reaches each voxel.
                // The SDF capsules of consecutive arc segments
                // overlap, so a step-S voxel always has at
                // least one cardinal neighbour from step S−1.
                // That neighbour starts as an unplaced voxel,
                // becomes a bud, matures into Wogor Wood —
                // sturdy — at which point the step-S voxel's
                // `supportDir` resolves to it. The chain
                // anchors at the emergence: the capsule's
                // lower voxels reach into the terrain below
                // `baseY`, where `DOWN` is already sturdy
                // ground from tick 1.
                //
                // Roots and trunk diverge only on the
                // **replaceable** rule: roots use the wider
                // `isRootReplaceable` (water, lava, leaves,
                // stone in addition to the canopy's set), so
                // an underwater root voxel that finds a sturdy
                // face will still get past the gate and replace
                // the water; the canopy stays on the stricter
                // `isTreeReplaceable` so player structures
                // outside the natural-terrain tags aren't
                // bulldozed by branches.
                // **Prefer a Wogor Wood neighbour for FACING.**
                // Two-pass search:
                //
                //  1. First pass scans the 6 cardinals for an
                //     existing Wogor Wood block. If found, that
                //     direction wins.
                //
                //  2. If no Wogor Wood neighbour exists yet, fall
                //     back to any sturdy face — terrain ground,
                //     base stone, already-matured nearby wood
                //     blocks via the second-pass `isFaceSturdy`
                //     branch.
                //
                // Why prefer wood: every Wogor Wood block is
                // permanent and full-cube, so a bud's `canSurvive`
                // pointing at it is bulletproof — the FACING
                // neighbour can't get *replaced* by a later
                // ritual setBlock (e.g. a `converts_to_mud`
                // soil block that gets overwritten by a deeper
                // capsule voxel later). A bud anchored to
                // terrain stays alive only as long as that
                // terrain stays terrain; anchored to wood, it's
                // good for as long as the wood exists, which
                // for matured Wogor Wood is forever.
                // Visually: every bud is tethered into the
                // tree's own matured skeleton rather than into
                // whatever happens to be sturdy at placement time.
                var supportDir: Direction? = null
                for (dir in SIX_DIRECTIONS) {
                    val nPos = pos.relative(dir)
                    val nState = level.getBlockState(nPos)
                    if (nState.`is`(EKBlocks.WOGOR_WOOD.get())) {
                        supportDir = dir
                        break
                    }
                }
                if (supportDir == null) {
                    for (dir in SIX_DIRECTIONS) {
                        val nPos = pos.relative(dir)
                        val nState = level.getBlockState(nPos)
                        if (nState.isFaceSturdy(level, nPos, dir.opposite)) {
                            supportDir = dir
                            break
                        }
                    }
                }
                if (supportDir != null) {
                    val existing = level.getBlockState(pos)
                    val replaceable = if (job.isRoot.get(i))
                        isRootReplaceable(existing) else isTreeReplaceable(existing)
                    if (replaceable) {
                        // **Pick bud vs direct Wogor Wood based
                        // on what we're replacing.**
                        //
                        // - **Air / canBeReplaced** (water,
                        //   grass tops, etc.): plant a bud. The
                        //   bud's onPlace schedules its growth
                        //   tick and we get the visible
                        //   bud → wood maturation animation
                        //   over the next 15–21 ticks.
                        //
                        // - **Mud / converts_to_mud blocks**
                        //   (dirt, grass_block, podzol, etc.,
                        //   i.e. the surface soil the root /
                        //   buttress capsule extends into):
                        //   place Wogor Wood directly, **not**
                        //   a bud.
                        //
                        // The reason for the direct-wood
                        // branch: when a surrounding bud was
                        // placed earlier with its FACING
                        // pointing at the soil block we're now
                        // about to overwrite, the original
                        // bud's canSurvive would have been
                        // re-checked at the next neighbour
                        // update fired in this region —
                        // typically when another nearby bud
                        // matures and fires a flag-3 setBlock —
                        // and it would have found a (still-
                        // immature) bud on its FACING side
                        // instead of the soil it was banking
                        // on, failing canSurvive and dropping
                        // to AIR. Visible result: a hole where
                        // the original bud was, and a fresh
                        // bud growing in the soil's slot —
                        // exactly the "blocks removed to put
                        // buds in but no buds end up there"
                        // symptom.
                        //
                        // Placing Wogor Wood directly closes
                        // that cascade entirely: Wogor Wood has
                        // no canSurvive logic, and crucially
                        // its placement also makes the slot
                        // **immediately sturdy**, so any
                        // adjacent bud whose FACING was the
                        // soil here re-evaluates to a sturdy
                        // wood face on the next neighbour
                        // update. Everything stays put.
                        // Soil displacement: when a bud is about to land
                        // on a mud / converts_to_mud cell, push that
                        // soil sideways into an adjacent air pocket
                        // (preferring supported air) rather than erasing
                        // it. The displaced block lands as mud at its
                        // new spot — preserves the dirt the tree is
                        // pushing through rather than vaporising it.
                        // Mirrors the world-root grower's behaviour.
                        val displaceable = existing.`is`(Blocks.MUD) || existing.`is`(CONVERTS_TO_MUD)
                        val didDisplace = displaceable && tryDisplaceToAdjacentAir(level, pos)
                        val plantingBud = didDisplace || existing.isAir || existing.canBeReplaced()
                        if (plantingBud && !job.hasSeed) {
                            // Seed bud — bypass tag check. Always allow.
                        } else if (plantingBud) {
                            // Subsequent buds must anchor onto a block in
                            // WOGOR_BUD_GROWABLE. If the chosen supportDir's
                            // block isn't tagged, skip placement; the wave
                            // will retry once a tagged block (matured wood)
                            // shows up as a neighbour.
                            val supportState = level.getBlockState(pos.relative(supportDir))
                            if (!supportState.`is`(WogorBudBlock.WOGOR_BUD_GROWABLE)) {
                                // Skip this voxel this tick; leave frontier bit
                                // set so the next tick re-tries (don't mark
                                // didPlace=true).
                                i = job.frontier.nextSetBit(i + 1)
                                continue
                            }
                        }
                        val state = if (plantingBud) {
                            EKBlocks.WOGOR_BUD.get().defaultBlockState()
                                .setValue(WogorBudBlock.AGE, 0)
                                .setValue(WogorBudBlock.FACING, supportDir)
                        } else {
                            EKBlocks.WOGOR_WOOD.get().defaultBlockState()
                        }
                        // Flag 2 (UPDATE_CLIENTS only) on both
                        // paths — same reason as the leaves'
                        // setBlock: avoid the neighbour-update
                        // cascade that could fire updateShape
                        // on nearby buds.
                        level.setBlock(pos, state, 2)
                        if (plantingBud) job.hasSeed = true
                    }
                    didPlace = true
                }
            }

            if (didPlace) {
                job.placed.set(i)
                job.frontier.clear(i)
                newlyPlaced.add(i)
                placeBudget--
                changed = true
            }
            i = job.frontier.nextSetBit(i + 1)
        }

        // Propagate: every unplaced voxel-set neighbour of a
        // freshly placed voxel becomes a frontier candidate.
        // Wood placement can unlock both wood and leaf
        // neighbours; leaf placement unlocks adjacent leaves.
        //
        // Also flip the **biome** at each freshly-placed voxel
        // to Wohlonnogondonia. The cells are 4 × 4 × 4 blocks
        // and the spreader dedupes shared cells / coalesces
        // one client resync per touched chunk, so the cost is
        // bounded even at the upper end of [MAX_PLACES_PER_TICK].
        // The tree's painted shape carries the biome with it
        // as the wave moves outward.
        if (newlyPlaced.isNotEmpty()) {
            // Cascade gated on the current layer: only neighbours whose
            // index is within `[0, layerEnds[currentLayer])` enter the
            // frontier here. Higher-layer neighbours enter only when
            // `currentLayer` advances, which happens below once every
            // bit in the current layer's range is placed. Leaves
            // bypass the gate so they fire the instant their wood
            // neighbour is placed, regardless of wood-layer progress.
            val frontierEnd = if (job.layerEnds.isEmpty()) job.voxels.size
                else job.layerEnds[job.currentLayer.coerceAtMost(job.layerEnds.size - 1)]
            val placedPositions = ArrayList<BlockPos>(newlyPlaced.size)
            for (idx in newlyPlaced) {
                val pos = BlockPos.of(job.voxels[idx])
                placedPositions.add(pos)
                for (dir in SIX_DIRECTIONS) {
                    val nKey = pos.relative(dir).asLong()
                    val nIdx = job.voxelIdx[nKey] ?: continue
                    if (job.placed.get(nIdx)) continue
                    if (nIdx < frontierEnd || job.isLeaf.get(nIdx)) {
                        job.frontier.set(nIdx)
                    }
                }
            }
            WohlonnogondoniaSpreader.convertCellsToWohlon(level, placedPositions)
        }

        // Layer advance: two triggers.
        //   1. Completion — every voxel in the current layer is placed.
        //      Free, instantaneous; loops to consume consecutive
        //      already-complete layers (e.g. when a layer happens to
        //      have zero unplaced voxels left over from a previous
        //      tick).
        //   2. Timeout — [LAYER_ADVANCE_INTERVAL] ticks have elapsed
        //      with the layer still incomplete. Lets the *next* layer
        //      start filling in even if some voxels in the current
        //      layer are stuck (e.g. a branch centerline that the
        //      cascade can't reach because consecutive centerline
        //      voxels are non-cardinal-adjacent). The stuck voxels
        //      stay in frontier and may still place later via cascade
        //      from the new layer's wood.
        // The wood phases overlap as intended — layer N+1 begins
        // filling out 1 second after layer N began, regardless of
        // whether N has reached the trunk's top yet.
        job.layerTimer++
        if (job.layerEnds.isNotEmpty()) {
            while (job.currentLayer < job.layerEnds.size - 1 &&
                job.placed.nextClearBit(0) >= job.layerEnds[job.currentLayer]) {
                advanceWoodLayer(job)
                changed = true
            }
            if (job.currentLayer < job.layerEnds.size - 1 &&
                job.layerTimer >= LAYER_ADVANCE_INTERVAL) {
                advanceWoodLayer(job)
                changed = true
            }
        }
        return changed
    }

    /** Step [job] one layer forward: reset the timer, seed any voxel
     *  in the newly-opened range whose cardinal neighbours include a
     *  placed voxel or an out-of-set cell into the frontier. */
    private fun advanceWoodLayer(job: TreeJob) {
        val prevEnd = job.layerEnds[job.currentLayer]
        job.currentLayer++
        job.layerTimer = 0
        val newEnd = job.layerEnds[job.currentLayer]
        for (idx in prevEnd until newEnd) {
            if (job.placed.get(idx)) continue
            val pos = BlockPos.of(job.voxels[idx])
            for (dir in SIX_DIRECTIONS) {
                val nKey = pos.relative(dir).asLong()
                val nIdx = job.voxelIdx[nKey]
                if (nIdx == null || job.placed.get(nIdx)) {
                    job.frontier.set(idx)
                    break
                }
            }
        }
    }

    private fun isTreeReplaceable(state: BlockState): Boolean {
        if (state.isAir) return true
        if (state.canBeReplaced()) return true
        if (state.`is`(Blocks.MUD)) return true
        if (state.`is`(CONVERTS_TO_MUD)) return true
        // Barrier — test/observation aid. Replaceable by every grower
        // placement path but NOT in the spreader's `converts_to_mud`
        // tag, so a barrier arena stays as barriers while the trunk
        // and branches grow visibly through it.
        if (state.`is`(Blocks.BARRIER)) return true
        return false
    }

    /** Used only when the voxel was painted by
     *  [paintChildTreeRoots] (tracked via [TreeJob.isRoot]).
     *  Superset of [isTreeReplaceable] — the root tag covers
     *  water, lava, leaves, and base stone in addition. */
    private fun isRootReplaceable(state: BlockState): Boolean {
        if (isTreeReplaceable(state)) return true
        if (state.`is`(ROOT_REPLACEABLE)) return true
        return false
    }

    /** Push the soil at `pos` into a cardinal-adjacent air cell as
     *  mud. Two-pass scan: first prefer supported air (a sturdy
     *  block beneath the candidate, and that "beneath" is not `pos`
     *  itself — which is about to be overwritten with a non-sturdy
     *  bud and would leave the displaced block floating). Falls
     *  back to any adjacent air. Mirrors
     *  [WohlonnogondoniaWorldRootGrower]'s helper so the visual
     *  reads the same on both growers: the tree pushes the dirt
     *  out of the way rather than vaporising it. */
    private fun tryDisplaceToAdjacentAir(level: ServerLevel, pos: BlockPos): Boolean {
        val mud = Blocks.MUD.defaultBlockState()
        for (dir in SIX_DIRECTIONS) {
            val adj = pos.relative(dir)
            if (!level.getBlockState(adj).isAir) continue
            val below = adj.below()
            if (below == pos) continue
            val belowState = level.getBlockState(below)
            if (belowState.isFaceSturdy(level, below, Direction.UP)) {
                level.setBlock(adj, mud, 2)
                return true
            }
        }
        for (dir in SIX_DIRECTIONS) {
            val adj = pos.relative(dir)
            if (level.getBlockState(adj).isAir) {
                level.setBlock(adj, mud, 2)
                return true
            }
        }
        return false
    }

    // ===================================================
    //   SDF painters — faithful ports of chunkgen
    // ===================================================

    /** Port of `paintTreeSegment` with bark noise. */
    private fun paintSegmentSdf(seg: TreeSegment, out: MutableSet<Long>) {
        val p0x = seg.startX; val p0y = seg.startY; val p0z = seg.startZ
        val p1x = seg.endX; val p1y = seg.endY; val p1z = seg.endZ
        val r0 = seg.startRadius; val r1 = seg.endRadius
        val rMax = max(r0, r1)
        val barkAmpMax = barkAmplitudeFor(rMax)
        val pad = rMax + barkAmpMax + 1.0

        val minWX = floor(min(p0x, p1x) - pad).toInt()
        val maxWX = ceil(max(p0x, p1x) + pad).toInt()
        val minWY = floor(min(p0y, p1y) - pad).toInt()
        val maxWY = ceil(max(p0y, p1y) + pad).toInt()
        val minWZ = floor(min(p0z, p1z) - pad).toInt()
        val maxWZ = ceil(max(p0z, p1z) + pad).toInt()

        val ex = p1x - p0x; val ey = p1y - p0y; val ez = p1z - p0z
        val segLenSq = ex * ex + ey * ey + ez * ez
        if (segLenSq < 1e-6) {
            paintSphereWithBark(p0x, p0y, p0z, r0, out)
            return
        }
        val invSegLenSq = 1.0 / segLenSq

        for (wy in minWY..maxWY) {
            val vy = wy + 0.5
            val py = vy - p0y
            val pyEy = py * ey
            for (wz in minWZ..maxWZ) {
                val vz = wz + 0.5
                val pz = vz - p0z
                val pyEyPlusPzEz = pyEy + pz * ez
                for (wx in minWX..maxWX) {
                    val vx = wx + 0.5
                    val px = vx - p0x
                    val tRaw = (px * ex + pyEyPlusPzEz) * invSegLenSq
                    val t = if (tRaw < 0.0) 0.0 else if (tRaw > 1.0) 1.0 else tRaw
                    val cX = p0x + ex * t
                    val cY = p0y + ey * t
                    val cZ = p0z + ez * t
                    val ddx = vx - cX; val ddy = vy - cY; val ddz = vz - cZ
                    val distSq = ddx * ddx + ddy * ddy + ddz * ddz
                    val r = r0 + (r1 - r0) * t
                    val barkAmp = barkAmplitudeFor(r)
                    val rPlusMax = r + barkAmp
                    if (distSq > rPlusMax * rPlusMax) continue
                    val rMinusMin = r - barkAmp
                    if (rMinusMin > 0.0 && distSq <= rMinusMin * rMinusMin) {
                        out.add(BlockPos(wx, wy, wz).asLong())
                        continue
                    }
                    val dist = sqrt(distSq)
                    val sX: Double; val sY: Double; val sZ: Double
                    if (dist > 1e-6) {
                        val inv = r / dist
                        sX = cX + ddx * inv
                        sY = cY + ddy * inv
                        sZ = cZ + ddz * inv
                    } else {
                        sX = cX; sY = cY; sZ = cZ
                    }
                    val bark = (WohlonnogondoniaChunkGenerator.valueNoise3D(
                        sX * BARK_FREQ, sY * BARK_FREQ, sZ * BARK_FREQ,
                    ) - 0.5) * 2.0 * barkAmp
                    val rEff = r + bark
                    if (distSq <= rEff * rEff) {
                        out.add(BlockPos(wx, wy, wz).asLong())
                    }
                }
            }
        }
    }

    private fun paintSphereWithBark(cx: Double, cy: Double, cz: Double, r: Double, out: MutableSet<Long>) {
        val barkAmp = barkAmplitudeFor(r)
        val pad = r + barkAmp + 1.0
        val minWX = floor(cx - pad).toInt()
        val maxWX = ceil(cx + pad).toInt()
        val minWY = floor(cy - pad).toInt()
        val maxWY = ceil(cy + pad).toInt()
        val minWZ = floor(cz - pad).toInt()
        val maxWZ = ceil(cz + pad).toInt()
        for (wy in minWY..maxWY) {
            for (wz in minWZ..maxWZ) {
                for (wx in minWX..maxWX) {
                    val ddx = wx + 0.5 - cx
                    val ddy = wy + 0.5 - cy
                    val ddz = wz + 0.5 - cz
                    val distSq = ddx * ddx + ddy * ddy + ddz * ddz
                    val rPlusMax = r + barkAmp
                    if (distSq > rPlusMax * rPlusMax) continue
                    val dist = sqrt(distSq)
                    val sX: Double; val sY: Double; val sZ: Double
                    if (dist > 1e-6) {
                        val inv = r / dist
                        sX = cx + ddx * inv
                        sY = cy + ddy * inv
                        sZ = cz + ddz * inv
                    } else {
                        sX = cx; sY = cy; sZ = cz
                    }
                    val bark = (WohlonnogondoniaChunkGenerator.valueNoise3D(
                        sX * BARK_FREQ, sY * BARK_FREQ, sZ * BARK_FREQ,
                    ) - 0.5) * 2.0 * barkAmp
                    val rEff = r + bark
                    if (distSq <= rEff * rEff) {
                        out.add(BlockPos(wx, wy, wz).asLong())
                    }
                }
            }
        }
    }

    private fun barkAmplitudeFor(r: Double): Double =
        max(0.0, r - 0.5) * 0.18 + 0.25

    /** Port of `paintLeafBlob` with domain warp + threshold noise. */
    private fun paintLeafBlobSdf(
        cx: Int, cy: Int, cz: Int, rxz: Int, ry: Int, out: MutableSet<Long>,
    ) {
        val ryEff = ry.coerceAtLeast(1)
        val xzMargin = ((rxz * 0.25).toInt() + LEAF_WARP_AMP_INT).coerceAtLeast(1)
        val yMargin = ((ryEff * 0.25).toInt() + LEAF_WARP_AMP_INT).coerceAtLeast(1)
        val xzRange = rxz + xzMargin
        val yRange = ryEff + yMargin
        val rxzD = rxz.toDouble()
        val ryD = ryEff.toDouble()

        for (dy in -yRange..yRange) {
            val wy = cy + dy
            for (dz in -xzRange..xzRange) {
                val wz = cz + dz
                for (dx in -xzRange..xzRange) {
                    val wx = cx + dx
                    val warpX = (WohlonnogondoniaChunkGenerator.valueNoise3D(
                        wx * LEAF_WARP_FREQ, wy * LEAF_WARP_FREQ, wz * LEAF_WARP_FREQ,
                    ) - 0.5) * 2.0 * LEAF_WARP_AMP
                    val warpY = (WohlonnogondoniaChunkGenerator.valueNoise3D(
                        wx * LEAF_WARP_FREQ + 71.3, wy * LEAF_WARP_FREQ, wz * LEAF_WARP_FREQ,
                    ) - 0.5) * 2.0 * LEAF_WARP_AMP
                    val warpZ = (WohlonnogondoniaChunkGenerator.valueNoise3D(
                        wx * LEAF_WARP_FREQ, wy * LEAF_WARP_FREQ + 71.3, wz * LEAF_WARP_FREQ,
                    ) - 0.5) * 2.0 * LEAF_WARP_AMP
                    val ndx = (dx + warpX) / rxzD
                    val ndy = (dy + warpY) / ryD
                    val ndz = (dz + warpZ) / rxzD
                    val nd2 = ndx * ndx + ndy * ndy + ndz * ndz
                    if (nd2 > LEAF_THRESHOLD_MAX) continue
                    val low = WohlonnogondoniaChunkGenerator.valueNoise3D(
                        wx * LEAF_LOW_FREQ, wy * LEAF_LOW_FREQ, wz * LEAF_LOW_FREQ,
                    )
                    val high = WohlonnogondoniaChunkGenerator.valueNoise3D(
                        wx * LEAF_HIGH_FREQ, wy * LEAF_HIGH_FREQ, wz * LEAF_HIGH_FREQ,
                    )
                    val perturb = (low - 0.5) * 2.0 * LEAF_LOW_AMP +
                        (high - 0.5) * 2.0 * LEAF_HIGH_AMP
                    val threshold = 1.0 + perturb
                    if (nd2 > threshold) continue
                    out.add(BlockPos(wx, wy, wz).asLong())
                }
            }
        }
    }

    // ===================================================
    //   Child Tree buttress roots — port of paintChildTreeRoots
    // ===================================================

    /** 8..11 hand-coded arc-and-plunge arms radiating from the
     *  trunk base. Same parametric `rise → walk → plunge`
     *  silhouette as the Mother Tree's roots, but at the
     *  child-tree's smaller scale: shorter reach (25–43 vs
     *  80–129), shallower plunge (18–33 vs 40–69), smaller
     *  wobble (1.0–2.5 vs 2.0–6.0), thinner trunk-side radius
     *  (3.5 vs 9.0), tighter emergence offset (3 vs 7 blocks
     *  from the trunk centre). */
    private fun paintChildTreeRoots(
        heightAt: (Int, Int) -> Int,
        baseX: Double, baseY: Double, baseZ: Double, seed: Int,
        out: MutableSet<Long>,
        segmentsOut: MutableList<TreeSegment>,
    ) {
        val rootCount = 8 + ((hash32(seed, 0, 51) ushr 1) and 0x3)
        for (i in 0 until rootCount) {
            val angleHash = hash01(seed, i, 51)
            val angle = i * (PI * 2.0 / rootCount) +
                (angleHash - 0.5) * (PI * 2.0 / rootCount) * 0.4
            val cosA = cos(angle); val sinA = sin(angle)
            val perpX = -sinA; val perpZ = cosA

            val reach = 25 + ((hash32(seed, i, 52) ushr 1) and 0x13)
            val plungeDepth = 18 + ((hash32(seed, i, 53) ushr 1) and 0xF)
            val wobblePhase = hash01(seed, i, 55) * PI * 2.0
            val wobbleAmp = 1.0 + hash01(seed, i, 56) * 1.5
            val walkUndulationPhase = hash01(seed, i, 57) * PI * 2.0
            val emergenceDY = (hash32(seed, i, 58) ushr 1 and 0x3).toDouble()
            val riseEnd = 0.12 + hash01(seed, i, 60) * 0.06
            val walkEnd = 0.70 + hash01(seed, i, 61) * 0.08

            val startX = baseX + cosA * 3.0
            val startZ = baseZ + sinA * 3.0
            val startY = baseY + emergenceDY + 1.0

            val steps = reach
            var prevX = startX; var prevY = startY; var prevZ = startZ
            var prevR = CHILD_TREE_ROOT_BASE_RADIUS
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                val r = t * reach
                val wob = sin(t * PI * 3.0 + wobblePhase) * wobbleAmp
                val px = startX + r * cosA + perpX * wob
                val pz = startZ + r * sinA + perpZ * wob

                val py = when {
                    t < riseEnd -> {
                        val rT = t / riseEnd
                        startY + 2.5 * (1.0 - (1.0 - rT) * (1.0 - rT))
                    }
                    t < walkEnd -> {
                        val hillY = heightAt(px.toInt(), pz.toInt()).toDouble()
                        val wT = (t - riseEnd) / (walkEnd - riseEnd)
                        val bumpRaw = sin(wT * PI * 5.0 + walkUndulationPhase)
                        val bump = max(0.0, bumpRaw) * 4.0
                        hillY + 1.0 + bump
                    }
                    else -> {
                        val hillHere = heightAt(px.toInt(), pz.toInt()).toDouble()
                        val pT = (t - walkEnd) / (1.0 - walkEnd)
                        hillHere + 1.0 - pT * pT * plungeDepth
                    }
                }

                val curR = when {
                    t < walkEnd * 0.5 -> CHILD_TREE_ROOT_BASE_RADIUS
                    t < walkEnd -> CHILD_TREE_ROOT_BASE_RADIUS *
                        (1.0 - (t - walkEnd * 0.5) / (walkEnd * 0.5) * 0.4)
                    else -> CHILD_TREE_ROOT_BASE_RADIUS * 0.6 *
                        (1.0 - (t - walkEnd) / (1.0 - walkEnd) * 0.9)
                }

                val seg = TreeSegment(prevX, prevY, prevZ, px, py, pz, prevR, curR)
                segmentsOut.add(seg)
                paintSegmentSdf(seg, out)
                prevX = px; prevY = py; prevZ = pz; prevR = curR
            }
        }
    }

    /** Squared distance from a point `(px, py, pz)` (typically a
     *  voxel centre) to the nearest point on the tapered
     *  capsule's centerline segment. Used by the leaf sort to
     *  key each leaf by its distance to the nearest skeleton
     *  segment instead of the global candle origin. */
    private fun distSqPointToSegment(
        px: Double, py: Double, pz: Double, seg: TreeSegment,
    ): Double {
        val sx = seg.startX; val sy = seg.startY; val sz = seg.startZ
        val ex = seg.endX; val ey = seg.endY; val ez = seg.endZ
        val dx = ex - sx; val dy = ey - sy; val dz = ez - sz
        val lenSq = dx * dx + dy * dy + dz * dz
        val pdx0 = px - sx; val pdy0 = py - sy; val pdz0 = pz - sz
        if (lenSq < 1e-9) {
            return pdx0 * pdx0 + pdy0 * pdy0 + pdz0 * pdz0
        }
        var t = (pdx0 * dx + pdy0 * dy + pdz0 * dz) / lenSq
        if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
        val cx = sx + dx * t; val cy = sy + dy * t; val cz = sz + dz * t
        val pdx = px - cx; val pdy = py - cy; val pdz = pz - cz
        return pdx * pdx + pdy * pdy + pdz * pdz
    }

    // ===================================================
    //   Plane fit
    // ===================================================

    /** Sample the heightmap on a `2`-block-stride disc of radius
     *  [SCAN_RADIUS] around [center], least-squares-fit
     *  `y = a·x + b·z + c` (in centred (dx, dz) coords so the
     *  free term collapses), derive the upward normal
     *  `(-a, 1, -b)`, clamp its XZ components by [MAX_TILT] to
     *  keep the influence subtle on steep terrain, normalise. */
    private fun fitTerrainNormal(level: ServerLevel, center: BlockPos): DoubleArray {
        var n = 0
        var sumXX = 0.0; var sumZZ = 0.0; var sumXZ = 0.0
        var sumXY = 0.0; var sumZY = 0.0
        var sumX = 0.0; var sumZ = 0.0; var sumY = 0.0
        val r2 = SCAN_RADIUS * SCAN_RADIUS
        var dx = -SCAN_RADIUS
        while (dx <= SCAN_RADIUS) {
            var dz = -SCAN_RADIUS
            while (dz <= SCAN_RADIUS) {
                if (dx * dx + dz * dz <= r2) {
                    val y = level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        center.x + dx, center.z + dz,
                    ).toDouble()
                    val xd = dx.toDouble(); val zd = dz.toDouble()
                    sumX += xd; sumZ += zd; sumY += y
                    sumXX += xd * xd; sumZZ += zd * zd
                    sumXZ += xd * zd
                    sumXY += xd * y; sumZY += zd * y
                    n++
                }
                dz += SCAN_STRIDE
            }
            dx += SCAN_STRIDE
        }
        if (n < 3) return doubleArrayOf(0.0, 1.0, 0.0)
        val meanX = sumX / n
        val meanZ = sumZ / n
        val meanY = sumY / n
        val sxx = sumXX - n * meanX * meanX
        val szz = sumZZ - n * meanZ * meanZ
        val sxz = sumXZ - n * meanX * meanZ
        val sxy = sumXY - n * meanX * meanY
        val szy = sumZY - n * meanZ * meanY
        val det = sxx * szz - sxz * sxz
        if (kotlin.math.abs(det) < 1e-6) return doubleArrayOf(0.0, 1.0, 0.0)
        val a = (szz * sxy - sxz * szy) / det
        val b = (sxx * szy - sxz * sxy) / det
        val nx = (-a).coerceIn(-MAX_TILT, MAX_TILT)
        val nz = (-b).coerceIn(-MAX_TILT, MAX_TILT)
        val ny = 1.0
        val mag = sqrt(nx * nx + ny * ny + nz * nz)
        return doubleArrayOf(nx / mag, ny / mag, nz / mag)
    }

    // ===================================================
    //   Hash helpers — ports of chunkgen private fns
    // ===================================================

    private fun hash32(a: Int, b: Int, salt: Int): Int {
        var h = a * 0x9E3779B1.toInt() xor (b * 0x85EBCA77.toInt()) xor (salt * 0xC2B2AE3D.toInt())
        h = (h xor (h ushr 15)) * 0x2C1B3C6D.toInt()
        h = (h xor (h ushr 12)) * 0x297A2D39.toInt()
        h = h xor (h ushr 15)
        return h
    }

    private fun hash01(seed: Int, k1: Int, k2: Int): Double =
        (hash32(seed, k1, k2) and 0x7FFFFFFF) / 2147483648.0

    // ===================================================
    //   SavedData
    // ===================================================

    private fun getData(level: ServerLevel): TreeJobsData {
        return level.dataStorage.computeIfAbsent(
            { tag -> TreeJobsData.load(tag) }, { TreeJobsData() }, DATA_NAME,
        )
    }

    private class TreeJob(
        val voxels: LongArray,      // sorted by (segDistSq, originDistSq) ascending
        val isLeaf: BitSet,         // index → leaf? (else wood)
        val isRoot: BitSet,         // index → painted by paintChildTreeRoots? (controls which replaceable rule applies)
        val placed: BitSet,         // index → already placed (or skipped permanently)
        var hasSeed: Boolean = false, // first bud placed? gates WOGOR_BUD_GROWABLE tag check
        /** Layer boundary indices: `layerEnds[N]` is the exclusive end
         *  of layer N. Voxels in `[layerEnds[N-1], layerEnds[N])` share
         *  the same `segDistSq` ring around their nearest skeleton
         *  segment. Used by [tickJob] to gate placement strictly per
         *  layer — every voxel in layer N places before any layer-N+1
         *  voxel enters the frontier. Empty for legacy jobs loaded
         *  from saves predating this field; in that case all voxels
         *  are treated as one big layer (matches the prior behaviour). */
        val layerEnds: IntArray = intArrayOf(),
        /** Index into [layerEnds] of the currently-active layer.
         *  Advances when every voxel in `[0, layerEnds[currentLayer])`
         *  is placed OR when [layerTimer] hits [LAYER_ADVANCE_INTERVAL]
         *  (whichever first). The time-based fallback keeps stuck
         *  voxels from blocking the rest of the tree. */
        var currentLayer: Int = 0,
        /** Ticks elapsed since this layer became current. Reset on
         *  layer advance. Drives the [LAYER_ADVANCE_INTERVAL]
         *  fallback. */
        var layerTimer: Int = 0,
        /** One-shot list of cells the exit tunnel must clear to air.
         *  Applied once during [installTreeJob] and then irrelevant —
         *  not persisted, so a reloaded job has this empty (the air
         *  is already in the world). */
        val tunnelCarvePositions: LongArray = LongArray(0),
    ) {
        // Runtime-only — rebuilt on construction / save load.
        val voxelIdx: HashMap<Long, Int> = HashMap(voxels.size + voxels.size / 2)
        val frontier: BitSet = BitSet(voxels.size)

        /** Build the position→index lookup and seed the
         *  frontier with every unplaced voxel that **could**
         *  fire on the next tick — either one of its neighbours
         *  is outside the voxel set (touching world ground /
         *  air, where the trunk-base + root-base voxels begin),
         *  or one of its neighbours has been already placed
         *  (resume-after-save case where buds have been
         *  maturing while the world was loaded). */
        fun rebuildRuntimeState() {
            voxelIdx.clear()
            for (i in voxels.indices) voxelIdx[voxels[i]] = i

            frontier.clear()
            // Layer-gated initial frontier: only voxels in the current
            // layer are eligible to seed the frontier — *except* leaves,
            // which bypass the layer gate entirely. Leaves place
            // whenever their wood / leaf neighbour appears in the
            // world, independent of how far the wood-layer progression
            // has advanced. Legacy jobs with empty `layerEnds` fall
            // back to the full range so their behaviour is unchanged.
            val frontierEnd = if (layerEnds.isEmpty()) voxels.size
                else layerEnds[currentLayer.coerceAtMost(layerEnds.size - 1)]
            for (i in voxels.indices) {
                if (placed.get(i)) continue
                if (i >= frontierEnd && !isLeaf.get(i)) continue
                val pos = BlockPos.of(voxels[i])
                for (dir in SIX_DIRECTIONS) {
                    val nKey = pos.relative(dir).asLong()
                    val nIdx = voxelIdx[nKey]
                    if (nIdx == null || placed.get(nIdx)) {
                        frontier.set(i)
                        break
                    }
                }
            }
        }
    }

    private class TreeJobsData : SavedData() {
        @JvmField val jobs: MutableList<TreeJob> = mutableListOf()

        override fun save(tag: CompoundTag): CompoundTag {
            val jobsList = ListTag()
            for (job in jobs) {
                val t = CompoundTag()
                t.putLongArray("v", job.voxels)
                t.putLongArray("leaf", job.isLeaf.toLongArray())
                t.putLongArray("root", job.isRoot.toLongArray())
                t.putLongArray("placed", job.placed.toLongArray())
                t.putBoolean("seed", job.hasSeed)
                t.putIntArray("layerEnds", job.layerEnds)
                t.putInt("layer", job.currentLayer)
                t.putInt("layerTimer", job.layerTimer)
                jobsList.add(t)
            }
            tag.put("jobs", jobsList)
            return tag
        }

        companion object {
            fun load(tag: CompoundTag): TreeJobsData {
                val data = TreeJobsData()
                if (!tag.contains("jobs", Tag.TAG_LIST.toInt())) return data
                val jobsList = tag.getList("jobs", Tag.TAG_COMPOUND.toInt())
                for (i in 0 until jobsList.size) {
                    val t = jobsList.getCompound(i)
                    val voxels = t.getLongArray("v")
                    if (voxels.isEmpty()) continue
                    val leafBits = t.getLongArray("leaf")
                    val isLeaf = if (leafBits.isEmpty()) BitSet(voxels.size)
                        else BitSet.valueOf(leafBits)
                    val rootBits = t.getLongArray("root")
                    val isRoot = if (rootBits.isEmpty()) BitSet(voxels.size)
                        else BitSet.valueOf(rootBits)
                    val placedBits = t.getLongArray("placed")
                    val placed = if (placedBits.isEmpty()) BitSet(voxels.size)
                        else BitSet.valueOf(placedBits)
                    val layerEnds = t.getIntArray("layerEnds")
                    val job = TreeJob(
                        voxels = voxels,
                        isLeaf = isLeaf,
                        isRoot = isRoot,
                        placed = placed,
                        hasSeed = t.getBoolean("seed"),
                        layerEnds = layerEnds,
                        currentLayer = t.getInt("layer"),
                        layerTimer = t.getInt("layerTimer"),
                    )
                    job.rebuildRuntimeState()
                    data.jobs.add(job)
                }
                return data
            }
        }
    }

    @Suppress("unused")
    private fun unusedReferenceForImportSurvival(level: Level, key: ResourceKey<Level>): Boolean =
        level.dimension() == key
}
