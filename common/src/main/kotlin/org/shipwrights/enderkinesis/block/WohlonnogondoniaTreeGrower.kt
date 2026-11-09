package org.shipwrights.enderkinesis.block

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.LongArrayList
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import org.shipwrights.enderkinesis.blockentity.HeartOfTheWildBlockEntity
import org.shipwrights.enderkinesis.dimension.TreeSegment
import org.shipwrights.enderkinesis.dimension.TreeTip
import org.shipwrights.enderkinesis.util.ClassWarmup
import org.shipwrights.enderkinesis.dimension.WogorTreeSkeleton
import org.shipwrights.enderkinesis.dimension.WohlonnogondoniaChunkGenerator
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKSounds
import java.util.BitSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.shipwrights.enderkinesis.dimension.wohlonHash01
import org.shipwrights.enderkinesis.dimension.wohlonHash32
import org.shipwrights.enderkinesis.util.TICKS_PER_SECOND

/**
 * Grows a chunkgen-style Child Tree at the heart-candle ritual. Every voxel is planned off-thread, then
 * grows in order of growth time: the trunk rises and the branches reach outward first, thickening follows
 * behind them, and leaf clusters open after their branch. All wood goes in as [WogorBudBlock] buds.
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
    private const val HOLLOW_DOME = 1.3
    private const val HOLLOW_ROUGHNESS = 0.3
    private const val HOLLOW_ROUGH_FREQ = 0.45

    // Exit passage: the most it may run, in blocks, and how far past the last wood it goes before it ends.
    private const val TUNNEL_MAX_LENGTH = 70
    private const val TUNNEL_CLEAR_RUN = 3.0
    private const val TUNNEL_STEP = 0.5
    // The floor rises or falls at most this much for each block along, so it stays walkable.
    private const val TUNNEL_SLOPE = 0.5
    // Meander: radians of drift from the ritual's heading, on two slow waves (blocks per wave).
    private const val TUNNEL_MEANDER = 0.55
    private const val TUNNEL_WAVE_LONG = 22.0
    private const val TUNNEL_WAVE_SHORT = 9.0
    // Half width and full height, in blocks; both vary along the passage and flare at the mouth.
    private const val TUNNEL_HALF_WIDTH = 1.25
    private const val TUNNEL_HEIGHT = 2.8
    private const val TUNNEL_SIZE_JITTER = 0.25
    private const val TUNNEL_WALL_ROUGHNESS = 0.18
    private const val TUNNEL_WALL_FREQ = 0.35
    // Smooth value noise rarely reaches its ends, so this restores about the old sine amplitude.
    private const val TUNNEL_NOISE_GAIN = 1.6
    private const val TUNNEL_MOUTH_FLARE = 1.35
    private const val TUNNEL_MOUTH_RUN = 6.0

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
     *  knob — combined with the 20–28 tick bud maturation
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

    /** A job with a voxel that can never be placed must end: 5 minutes with no placement. */
    private const val JOB_IDLE_LIMIT_TICKS = 6000

    private const val HEART_ARRIVE_VOLUME: Float = 4f
    private const val COMPLETE_VOLUME: Float = 4f
    private const val GROW_DETAIL_VOLUME: Float = 2.5f
    private const val GROW_DETAIL_MIN_TICKS: Int = 20
    private const val GROW_DETAIL_SPREAD_TICKS: Int = 40
    private const val GROW_DETAIL_PITCH_MIN: Float = 0.9f
    private const val GROW_DETAIL_PITCH_SPREAD: Float = 0.2f

    /** Builds that run now. A pending record with no entry here is from before a restart. */
    private val buildsInFlight: MutableSet<TreeBuildParams> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    /** Game tick of each fresh ignite, by portal, until its build installs. Server thread only. */
    private val igniteTicks = Long2LongOpenHashMap().apply { defaultReturnValue(NO_IGNITE) }
    /** Candle tendrils of each fresh ignite, by portal, for [WohlonRitualTimeline.riseEnd]. Server thread only. */
    private val igniteCandles = Long2IntOpenHashMap()
    private const val NO_IGNITE = Long.MIN_VALUE

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::tickLevel)
        LifecycleEvent.SERVER_STOPPED.register { _ ->
            buildsInFlight.clear()
            igniteTicks.clear()
            igniteCandles.clear()
        }
        // The first ritual of a session loaded these on its ignite tick.
        LifecycleEvent.SERVER_STARTED.register { _ ->
            ClassWarmup.load(
                TreeBuildParams::class.java, WohlonTreeSite::class.java, TreeJob::class.java, TreeJobsData::class.java,
                WohlonnogondoniaPortalRitual::class.java, WohlonRitualNetwork::class.java, WohlonRitualTimeline::class.java,
                org.shipwrights.enderkinesis.blockentity.HeartOfTheWildBlockEntity::class.java,
            )
            WohlonnogondoniaSpreader.warmClasses()
            WohlonnogondoniaPortalManager.warmClasses()
        }
    }

    fun startTree(level: ServerLevel, portalPos: BlockPos, igniteCandleCount: Int) {
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
        // Saved until the job installs, or a server stop during the build loses the tree.
        val data = getData(level)
        data.pending.add(params)
        data.setDirty()
        igniteTicks.put(portalPos.asLong(), level.gameTime)
        igniteCandles.put(portalPos.asLong(), igniteCandleCount)
        launchBuild(level, params, announce = true)
    }

    /** Floats for each ghost segment: start, end, start radius, end radius, and 1 for a root. */
    const val GHOST_SEGMENT_FLOATS = 9
    // Keeps the ghost packet well under the payload limit; a tree has some hundreds to a few thousand.
    private const val MAX_GHOST_SEGMENTS = 6000
    private const val MAX_GHOST_TIPS = 2000

    private fun ghostSegments(
        canopy: List<TreeSegment>, roots: List<TreeSegment>, ox: Double, oy: Double, oz: Double,
    ): FloatArray {
        val count = minOf(canopy.size + roots.size, MAX_GHOST_SEGMENTS)
        val out = FloatArray(count * GHOST_SEGMENT_FLOATS)
        var i = 0
        fun put(seg: TreeSegment, root: Boolean) {
            if (i >= count) return
            val b = i++ * GHOST_SEGMENT_FLOATS
            out[b] = (seg.startX - ox).toFloat(); out[b + 1] = (seg.startY - oy).toFloat(); out[b + 2] = (seg.startZ - oz).toFloat()
            out[b + 3] = (seg.endX - ox).toFloat(); out[b + 4] = (seg.endY - oy).toFloat(); out[b + 5] = (seg.endZ - oz).toFloat()
            out[b + 6] = seg.startRadius.toFloat(); out[b + 7] = seg.endRadius.toFloat()
            out[b + 8] = if (root) 1f else 0f
        }
        for (seg in roots) put(seg, true)
        for (seg in canopy) put(seg, false)
        return out
    }

    private fun ghostTips(tips: List<TreeTip>, candle: BlockPos): IntArray {
        val count = minOf(tips.size, MAX_GHOST_TIPS)
        val out = IntArray(count * 3)
        for (i in 0 until count) {
            out[i * 3] = tips[i].x - candle.x
            out[i * 3 + 1] = tips[i].y - candle.y
            out[i * 3 + 2] = tips[i].z - candle.z
        }
        return out
    }

    /** Placed part of each growth phase (roots, wood, leaves) of the job at [trunk], or null. */
    fun progress(level: ServerLevel, trunk: Long, out: FloatArray): Boolean {
        val data = level.dataStorage.get({ tag -> TreeJobsData.load(tag) }, DATA_NAME) ?: return false
        val job = data.jobs.firstOrNull { it.trunk == trunk } ?: return false
        // Roots, wood and leaves interleave in growth order, so count by flag, not by index range.
        val roots = job.isRoot.cardinality()
        val leaves = job.isLeaf.cardinality()
        val wood = job.voxels.size - roots - leaves
        var rootsPlaced = 0
        var leavesPlaced = 0
        var woodPlaced = 0
        var i = job.placed.nextSetBit(0)
        while (i >= 0) {
            when {
                job.isRoot.get(i) -> rootsPlaced++
                job.isLeaf.get(i) -> leavesPlaced++
                else -> woodPlaced++
            }
            i = job.placed.nextSetBit(i + 1)
        }
        out[0] = share(rootsPlaced, roots)
        out[1] = share(woodPlaced, wood)
        out[2] = share(leavesPlaced, leaves)
        return true
    }

    private fun share(placed: Int, of: Int): Float = if (of <= 0) 1f else placed.toFloat() / of

    // Site survey: blocks copied below and above the candle; the deepest root plunge and the crown top fit inside.
    private const val SITE_BELOW = 48
    private const val SITE_ABOVE = 128
    // A root arm may turn this share of its slot either way, so the arms keep apart.
    private const val ROOT_TURN_SHARE = 0.45
    private const val ROOT_WATER_ARCH = 2.0
    // Growth time is in blocks of tip growth; each layer is one block, opened by completion or timeout.
    private const val THICKEN_LAG = 0.5
    private const val LEAF_LAG = 12.0
    private const val LEAF_SPREAD = 1.0
    private const val RING_NOISE = 3.0
    private const val RING_NOISE_FREQ = 0.35
    private const val GROWTH_TIME_SCALE = 16L
    private const val MAX_GROWTH_TIME = 100_000.0
    private const val KIND_WOOD: Byte = 0
    private const val KIND_ROOT: Byte = 1
    private const val KIND_LEAF: Byte = 2
    private const val ROOT_SMOOTH_SAMPLES = 5
    private const val ROOT_SMOOTH_RADIUS = 2.0
    private const val ROOT_REACH_MIN = 25
    private const val ROOT_REACH_SPREAD = 20
    // A drop past the walk's bump and arch is a wall or cliff: the arm crawls down its face.
    private const val CASCADE_MIN_DROP = 7.0
    private const val CASCADE_STEP = 2.0
    private const val CASCADE_THIN = 0.55
    private const val CASCADE_FOOT = 1.3
    private const val CASCADE_SWAY_PER_BLOCK = 0.1
    private const val CASCADE_SWAY_MAX = 2.5
    // Elevation over the ground ring at which the trunk flare starts to shrink, and is down to RAISED_FLARE_SHARE.
    private const val RAISED_MIN = 6.0
    private const val RAISED_FULL = 24.0
    private const val RAISED_FLARE_SHARE = 0.25
    // The crown leans this far, as a share of its radius, for each unit of uneven light; at most LEAN_MAX.
    private const val LEAN_PER_LIGHT = 0.15
    private const val LEAN_MAX = 0.3
    // An attractor in a dark sector stays with this chance; a fully lit one always stays.
    private const val KEEP_IN_SHADE = 0.3
    private const val KEEP_AT_LEAST = 0.4
    // Growth rate over the build: slow as a seedling, fastest at half grown, slow near full size.
    // RATE_MIN + RATE_SPAN · p(1 − p): the mean of 1 / rate over p is 1, so at quality 1 the whole growth takes as long as before.
    private const val RATE_MIN = 0.396f
    private const val RATE_SPAN = 4.41f
    private const val QUALITY_MIN = 0.7f
    private const val QUALITY_MAX = 1.3f
    private const val QUALITY_RADIUS = 40

    private fun crownLean(light: DoubleArray, canopyRx: Double): DoubleArray {
        val mean = light.average()
        var vx = 0.0; var vz = 0.0
        for (s in light.indices) {
            val a = (s + 0.5) * 2.0 * PI / light.size
            vx += (light[s] - mean) * cos(a)
            vz += (light[s] - mean) * sin(a)
        }
        val len = sqrt(vx * vx + vz * vz)
        val shift = minOf(len * LEAN_PER_LIGHT, LEAN_MAX) * canopyRx
        return if (len < 1.0e-6) doubleArrayOf(0.0, 0.0) else doubleArrayOf(vx / len * shift, vz / len * shift)
    }

    private fun keepLitAttractors(points: DoubleArray, light: DoubleArray, baseX: Double, baseZ: Double, seed: Int): DoubleArray {
        val count = points.size / 3
        val kept = DoubleArray(points.size)
        var n = 0
        for (i in 0 until count) {
            val x = points[i * 3]; val z = points[i * 3 + 2]
            val angle = (atan2(z - baseZ, x - baseX) + 2.0 * PI) % (2.0 * PI)
            val sector = (angle / (2.0 * PI) * light.size).toInt().coerceIn(0, light.size - 1)
            if (wohlonHash01(seed, i, 77) >= KEEP_IN_SHADE + (1.0 - KEEP_IN_SHADE) * light[sector]) continue
            kept[n * 3] = x; kept[n * 3 + 1] = points[i * 3 + 1]; kept[n * 3 + 2] = z
            n++
        }
        // A site shaded all round still grows a crown, only a smaller one.
        return if (n < count * KEEP_AT_LEAST) points else kept.copyOf(n * 3)
    }

    /** Water near the trunk and open sky make a good site, which grows faster. */
    private fun siteQuality(site: WohlonTreeSite, light: DoubleArray, baseX: Double, baseZ: Double): Float {
        var wet = 0; var all = 0
        for (dz in -QUALITY_RADIUS..QUALITY_RADIUS step 4) for (dx in -QUALITY_RADIUS..QUALITY_RADIUS step 4) {
            if (dx * dx + dz * dz > QUALITY_RADIUS * QUALITY_RADIUS) continue
            all++
            if (site.isWater((baseX + dx).toInt(), (baseZ + dz).toInt())) wet++
        }
        val wetShare = if (all == 0) 0.0 else wet.toDouble() / all
        val q = 0.7 + 0.35 * minOf(1.0, wetShare * 4.0) + 0.25 * light.average()
        return q.toFloat().coerceIn(QUALITY_MIN, QUALITY_MAX)
    }

    private fun placeBudgetFor(job: TreeJob): Int {
        val p = job.placed.cardinality().toFloat() / job.voxels.size.coerceAtLeast(1)
        val rate = (RATE_MIN + RATE_SPAN * p * (1f - p)) * job.quality
        val budget = MAX_PLACES_PER_TICK * rate + job.placeCarry
        val whole = budget.toInt()
        job.placeCarry = budget - whole
        return whole
    }

    // What even roots cannot break; the branches and roots bend around it. Off-thread: reads only the state.
    @Suppress("DEPRECATION") // blocksMotion is the vanilla heightmap predicate; Mojang marks it internal only.
    private fun isBendObstacle(state: BlockState): Boolean = state.blocksMotion() && !isRootReplaceable(state)

    // Canopy wood cannot replace leaves or stone, so branches bend around them instead of leaving gaps.
    @Suppress("DEPRECATION") // blocksMotion is the vanilla heightmap predicate; Mojang marks it internal only.
    private fun isCanopyObstacle(state: BlockState): Boolean = state.blocksMotion() && !isTreeReplaceable(state)

    /** The ignite tendrils reach the root tips first; the roots then grow along them. */
    private fun holdForIgniteAnimation(level: ServerLevel, job: TreeJob, igniteTick: Long, candles: Int) {
        val sinceIgnite = (level.gameTime - igniteTick) / TICKS_PER_SECOND.toDouble()
        val end = WohlonRitualTimeline.animationEnd(WohlonRitualTimeline.spreadStart(sinceIgnite, candles))
        job.startTick = level.gameTime + Math.ceil((end - sinceIgnite) * TICKS_PER_SECOND).toLong()
    }

    /** [announce] sends the root tips to the clients for the ignite tendrils; a resumed build has no ignite. */
    private fun launchBuild(level: ServerLevel, params: TreeBuildParams, announce: Boolean) {
        buildsInFlight.add(params)
        // Heightmap cache for the root painter — pre-cached on
        // main thread so the off-thread paint doesn't need to
        // touch the level. `paintChildTreeRoots` queries surface
        // height at each step along each arc arm, well inside
        // the [HEIGHTMAP_PRECACHE_RADIUS] disc.
        val site = WohlonTreeSite.survey(level, params.candlePos, HEIGHTMAP_PRECACHE_RADIUS, SITE_BELOW, SITE_ABOVE)

        // ============================================================
        //   Off-thread phase — skeleton + SDF paint + sort + job
        //   build. None of this touches the level.
        // ============================================================
        val server = level.server
        BACKGROUND_EXECUTOR.execute {
            val job = try {
                buildTreeJob(params, site)
            } catch (t: Throwable) {
                LOG.error("WohlonTree: off-thread build failed at {}", params.candlePos, t)
                null
            }

            // ============================================================
            //   Back on the main thread — register the finished job
            //   so the tick loop starts placing voxels next tick.
            // ============================================================
            server.execute {
                buildsInFlight.remove(params)
                val data = getData(level)
                data.pending.remove(params)
                data.setDirty()
                val ignite = igniteTicks.remove(params.portalPos.asLong())
                val candles = igniteCandles.remove(params.portalPos.asLong())
                if (job != null) {
                    if (announce && ignite != NO_IGNITE) holdForIgniteAnimation(level, job, ignite, candles)
                    installTreeJob(level, params, job)
                    if (announce) {
                        spreadAroundTree(level, params, job)
                        WohlonRitualNetwork.sendRoots(level, params.portalPos, job.rootTips)
                        WohlonRitualNetwork.sendGhost(level, params.portalPos, params.candlePos, job.ghostSegments, job.ghostTips)
                    }
                }
            }
        }
    }

    /** The tree's height is known only once its build is done, so the ritual spread waits for it. */
    private fun spreadAroundTree(level: ServerLevel, params: TreeBuildParams, job: TreeJob) {
        var bottom = Int.MAX_VALUE
        var top = Int.MIN_VALUE
        for (i in job.voxels.indices) {
            if (job.isRoot.get(i)) continue
            val y = BlockPos.getY(job.voxels[i])
            if (y < bottom) bottom = y
            if (y > top) top = y
        }
        if (bottom > top) bottom = params.baseY.also { top = it }
        WohlonnogondoniaSpreader.spreadAroundTree(level, params.candlePos.x, params.candlePos.z, bottom, top)
    }

    /** Start again each build that a server stop cut off. Waits for the chunks of the height cache. */
    private fun resumePendingBuilds(level: ServerLevel, data: TreeJobsData) {
        for (params in data.pending.toList()) {
            if (params in buildsInFlight) continue
            val c = params.candlePos
            if (!isAreaLoaded(
                    level,
                    c.x - HEIGHTMAP_PRECACHE_RADIUS, c.z - HEIGHTMAP_PRECACHE_RADIUS,
                    c.x + HEIGHTMAP_PRECACHE_RADIUS, c.z + HEIGHTMAP_PRECACHE_RADIUS,
                )
            ) continue
            launchBuild(level, params, announce = false)
        }
    }

    /** `getChunkNow`, not `hasChunk`: `hasChunk` is true for a chunk that still generates. */
    private fun isAreaLoaded(level: ServerLevel, minX: Int, minZ: Int, maxX: Int, maxZ: Int): Boolean {
        val chunks = level.chunkSource
        for (cx in (minX shr 4)..(maxX shr 4)) {
            for (cz in (minZ shr 4)..(maxZ shr 4)) {
                if (chunks.getChunkNow(cx, cz) == null) return false
            }
        }
        return true
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
    ) {
        fun save(): CompoundTag {
            val t = CompoundTag()
            t.put("candle", NbtUtils.writeBlockPos(candlePos))
            t.put("portal", NbtUtils.writeBlockPos(portalPos))
            t.putInt("baseY", baseY)
            t.putLongArray("normal", LongArray(terrainNormal.size) { terrainNormal[it].toRawBits() })
            t.putLongArray(
                "hashes",
                longArrayOf(
                    sizeHash.toRawBits(), aspectHash.toRawBits(), leanHash.toRawBits(),
                    gravityHash.toRawBits(), biasHash.toRawBits(), flareHash.toRawBits(),
                    tunnelYaw.toRawBits(),
                ),
            )
            t.putInt("seed", seed)
            return t
        }

        companion object {
            private const val HASH_COUNT = 7

            fun load(t: CompoundTag): TreeBuildParams? {
                val normal = t.getLongArray("normal")
                val hashes = t.getLongArray("hashes")
                if (normal.isEmpty() || hashes.size != HASH_COUNT) return null
                return TreeBuildParams(
                    candlePos = NbtUtils.readBlockPos(t.getCompound("candle")),
                    portalPos = NbtUtils.readBlockPos(t.getCompound("portal")),
                    baseY = t.getInt("baseY"),
                    terrainNormal = DoubleArray(normal.size) { Double.fromBits(normal[it]) },
                    sizeHash = Double.fromBits(hashes[0]), aspectHash = Double.fromBits(hashes[1]),
                    leanHash = Double.fromBits(hashes[2]), gravityHash = Double.fromBits(hashes[3]),
                    biasHash = Double.fromBits(hashes[4]), flareHash = Double.fromBits(hashes[5]),
                    tunnelYaw = Double.fromBits(hashes[6]),
                    seed = t.getInt("seed"),
                )
            }
        }
    }

    /** Pre-cache the surface block Y for every column in a disc
     *  of [radius] around [center]. Stored as packed `(x,z) →
     *  surfaceY` so the off-thread root painter can do constant-
     *  time lookups without touching the level. */
    /** All the heavy work: skeleton build + SDF paint of canopy /
     *  roots / leaves + hollow carve + three-phase sort + job
     *  construction. Runs entirely off-thread. No level access. */
    private fun buildTreeJob(params: TreeBuildParams, site: WohlonTreeSite): TreeJob? {
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
        // A trunk flares where it meets the ground; on a raised site the roots flare at their feet instead.
        val raised = ((site.elevation(baseX, baseZ, params.baseY - 1) - RAISED_MIN) / (RAISED_FULL - RAISED_MIN)).coerceIn(0.0, 1.0)
        val flareShare = 1.0 - raised * (1.0 - RAISED_FLARE_SHARE)
        val buttressFlare = (maxThickness * (1.6 + params.flareHash * 0.6) * flareShare).toInt()
        val buttressRange = canopyRy * 1.2 + 8.0
        val attractionDist = 14.0 + scaleFactor * 10.0
        val killDist = 3.0 + scaleFactor * 2.0
        val stepSize = 3.0 + scaleFactor * 1.2
        val maxIterations = (80 + scaleFactor * 50).toInt()
        val branchBias = 0.10 + params.biasHash * 0.15
        val trunkLean = 0.20 + params.leanHash * 0.25
        val gravity = 0.08 + params.gravityHash * 0.12

        // Phototropism: the crown leans to the open sky and grows little into shaded sectors.
        val light = site.sectorLight(baseX, baseZ, canopyCy - canopyRy)
        val lean = crownLean(light, canopyRx)
        val attractors = keepLitAttractors(
            WogorTreeSkeleton.ellipsoidAttractors(
                cx = baseX + lean[0], cy = canopyCy, cz = baseZ + lean[1],
                rx = canopyRx, ry = canopyRy, rz = canopyRx,
                count = attractorCount,
                seed = seed,
            ),
            light, baseX, baseZ, seed,
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
        // Grow around what even roots cannot break: the branches bend away, and their children follow.
        val bent = site.bendAround(canopy.segments, canopy.tips, ::isCanopyObstacle)
        val canopySegments = bent.first
        val canopyTips = bent.second
        val woodVoxels = HashSet<Long>(1 shl 15)
        for (seg in canopySegments) paintSegmentSdf(seg, woodVoxels)
        // Roots stay flagged: they replace by the wider `root_replaceable` rule.
        val rootPositions = HashSet<Long>()
        val rootSegments = mutableListOf<TreeSegment>()
        val rootTips = LongArrayList()
        paintChildTreeRoots(site, baseX, baseYD, baseZ, seed, rootPositions, rootSegments, rootTips)
        woodVoxels.addAll(rootPositions)

        val leafVoxels = HashSet<Long>(1 shl 15)
        for (tip in canopyTips) {
            paintLeafBlobSdf(tip.x, tip.y, tip.z, LEAF_BLOB_RXZ, LEAF_BLOB_RY, leafVoxels)
        }
        leafVoxels.removeAll(woodVoxels)

        carveHollow(woodVoxels, leafVoxels, portalPos)
        // The cells are applied as air at install time, so terrain in the path is cleared too
        // (the off-thread build can't touch the level).
        val tunnelPositions = carveExitTunnel(woodVoxels, leafVoxels, candlePos, params.tunnelYaw, seed, site)
        rootPositions.retainAll(woodVoxels)

        val totalVoxels = woodVoxels.size + leafVoxels.size
        if (totalVoxels == 0) {
            return null
        }
        if (totalVoxels > MAX_VOXELS_PER_TREE) {
            return null
        }

        val order = growthOrder(woodVoxels, rootPositions, leafVoxels, canopySegments, rootSegments, rootTips.toLongArray(), seed)
        val sortedVoxels = order.voxels
        val sortedIsLeaf = order.isLeaf
        val sortedIsRoot = order.isRoot
        val layerEnds = order.layerEnds

        val job = TreeJob(
            voxels = sortedVoxels,
            isLeaf = sortedIsLeaf,
            isRoot = sortedIsRoot,
            placed = BitSet(totalVoxels),
            layerEnds = layerEnds,
            tunnelCarvePositions = tunnelPositions.toLongArray(),
            trunk = portalPos.asLong(),
            rootTips = rootTips.toLongArray(),
            ghostSegments = ghostSegments(canopySegments, rootSegments, baseX, baseYD, baseZ),
            ghostTips = ghostTips(canopyTips, candlePos),
            quality = siteQuality(site, light, baseX, baseZ),
        )
        // Builds the position→index map and the initial
        // frontier from the painted set. Pure data work, no
        // level access — safe off-thread.
        job.rebuildRuntimeState()

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
            val candle = params.candlePos.asLong()
            for (packed in job.tunnelCarvePositions) {
                // The tunnel starts in the candle cell; the lit candle must stay.
                if (packed == candle) continue
                mutPos.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed))
                // Same rule as a root: natural terrain only, never bedrock or a player block.
                if (!isRootReplaceable(level.getBlockState(mutPos))) continue
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
        level.blockEvent(params.portalPos, EKBlocks.HEART_OF_THE_WILD.get(), HeartOfTheWildBlockEntity.EVENT_GROW, 0)
        playAt(level, params.portalPos.asLong(), EKSounds.WOHLON_RITUAL_HEART_ARRIVE.get(), HEART_ARRIVE_VOLUME, 1f)
    }

    /** The heart alcove around [portalPos]: a dome over a flat floor, its wall roughened like a
     *  child tree's hollow. Returns the number of voxels removed across both sets. */
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
            // The roof rises higher than the floor sinks, so the alcove reads as a dome.
            val dyScaled = if (dy > 0.0) dy / HOLLOW_DOME else dy
            val nd = (dx * dx) / rx2 + (dyScaled * dyScaled) / ry2 + (dz * dz) / rz2
            val rough = 1.0 + HOLLOW_ROUGHNESS * (WohlonnogondoniaChunkGenerator.valueNoise3D(
                pos.x * HOLLOW_ROUGH_FREQ, pos.y * HOLLOW_ROUGH_FREQ, pos.z * HOLLOW_ROUGH_FREQ,
            ) - 0.5) * 2.0
            nd <= rough * rough
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

    /**
     * A winding passage out of the heart chamber, like a hollow between roots. It starts at the candle,
     * drifts on two slow waves about the ritual's heading, and keeps a walkable floor that follows the
     * ground outside the tree. The rounded, arched section varies in size and flares at the mouth. It
     * ends once it is out of the wood and back at the ground, or at [TUNNEL_MAX_LENGTH].
     *
     * Removes every passage cell from [woodVoxels] and [leafVoxels]; returns the cells, which
     * [installTreeJob] sets to air.
     */
    @Suppress("LongParameterList", "LongMethod") // One walk along the passage; the state is local to it.
    private fun carveExitTunnel(
        woodVoxels: MutableSet<Long>,
        leafVoxels: MutableSet<Long>,
        candlePos: BlockPos,
        yawRadians: Double,
        seed: Int,
        site: WohlonTreeSite,
    ): HashSet<Long> {
        val cells = HashSet<Long>(TUNNEL_MAX_LENGTH * 40)
        val phaseLong = wohlonHash01(seed, 0, 90) * 2.0 * PI
        val phaseShort = wohlonHash01(seed, 0, 91) * 2.0 * PI
        val phaseSize = wohlonHash01(seed, 0, 92) * 2.0 * PI
        var x = candlePos.x + 0.5
        var z = candlePos.z + 0.5
        var floorY = candlePos.y.toDouble()
        var clearRun = 0.0
        var mouthAt = -1.0
        var t = 0.0
        while (t <= TUNNEL_MAX_LENGTH) {
            val heading = yawRadians + TUNNEL_MEANDER *
                (0.6 * tunnelNoise(t / TUNNEL_WAVE_LONG, phaseLong) + 0.4 * tunnelNoise(t / TUNNEL_WAVE_SHORT, phaseShort))
            val fx = cos(heading); val fz = sin(heading)
            val rx = -fz; val rz = fx

            // Through the trunk the floor keeps the chamber's level; outside it follows the ground.
            val ground = site.groundY(floor(x).toInt(), floor(z).toInt()) + 1.0
            val inWood = woodVoxels.contains(BlockPos.asLong(floor(x).toInt(), floor(floorY).toInt() + 1, floor(z).toInt()))
            val target = if (inWood) floorY else ground
            floorY += (target - floorY).coerceIn(-TUNNEL_SLOPE * TUNNEL_STEP, TUNNEL_SLOPE * TUNNEL_STEP)

            val size = 1.0 + TUNNEL_SIZE_JITTER * tunnelNoise(t / (TUNNEL_WAVE_SHORT * 1.3), phaseSize)
            val wall = 1.0 + TUNNEL_WALL_ROUGHNESS * tunnelNoise(t * TUNNEL_WALL_FREQ, phaseSize + PI)
            val mouth = if (mouthAt < 0.0) 1.0 else 1.0 + (TUNNEL_MOUTH_FLARE - 1.0) * ((t - mouthAt) / TUNNEL_MOUTH_RUN).coerceIn(0.0, 1.0)
            carveSection(cells, x, floorY, z, rx, rz, TUNNEL_HALF_WIDTH * size * wall * mouth, TUNNEL_HEIGHT * (0.9 + 0.1 * size) * mouth)

            // Out of the wood and down at the ground: the passage has reached the open, so it ends.
            val open = !inWood && floorY <= ground + 0.5
            clearRun = if (open) clearRun + TUNNEL_STEP else 0.0
            if (open && mouthAt < 0.0) mouthAt = t
            if (!open) mouthAt = -1.0
            if (clearRun >= TUNNEL_CLEAR_RUN + TUNNEL_MOUTH_RUN) break
            x += fx * TUNNEL_STEP
            z += fz * TUNNEL_STEP
            t += TUNNEL_STEP
        }
        woodVoxels.removeAll(cells)
        leafVoxels.removeAll(cells)
        return cells
    }

    /** Smooth noise in about −1..1 along the passage; [phase] separates the different waves. */
    private fun tunnelNoise(t: Double, phase: Double): Double =
        (WohlonnogondoniaChunkGenerator.valueNoise3D(t * 2.0, phase * 7.0, 0.5) - 0.5) * 2.0 * TUNNEL_NOISE_GAIN

    /** A rounded arch on a flat floor: half an ellipse over the floor, across the passage. */
    private fun carveSection(
        cells: MutableSet<Long>, x: Double, floorY: Double, z: Double, rx: Double, rz: Double, halfWidth: Double, height: Double,
    ) {
        val baseY = floor(floorY).toInt()
        var w = -halfWidth
        while (w <= halfWidth) {
            val across = w / halfWidth
            // The roof falls toward the walls; the floor stays flat for walking.
            val roof = height * sqrt(maxOf(0.0, 1.0 - across * across))
            val vx = floor(x + rx * w).toInt()
            val vz = floor(z + rz * w).toInt()
            for (h in 0 until maxOf(2, ceil(roof).toInt())) cells.add(BlockPos.asLong(vx, baseY + h, vz))
            w += TUNNEL_STEP
        }
    }

    // ===================================================
    //   Per-tick center-out placement
    // ===================================================

    private fun tickLevel(level: ServerLevel) {
        if (WohlonnogondoniaCatastrophe.isDraining(level)) return
        val data = level.dataStorage.get(
            { tag -> TreeJobsData.load(tag) }, DATA_NAME,
        ) ?: return
        if (data.pending.isNotEmpty()) resumePendingBuilds(level, data)
        if (data.jobs.isEmpty()) return
        val iter = data.jobs.iterator()
        var changed = false
        while (iter.hasNext()) {
            val job = iter.next()
            // Not growing yet, so the grow loop does not start either.
            if (level.gameTime < job.startTick) continue
            // The scan reads each frontier voxel and its neighbours; a read loads its chunk.
            if (!isAreaLoaded(level, job.minX - 1, job.minZ - 1, job.maxX + 1, job.maxZ + 1)) continue
            job.lastGrowTick = level.gameTime
            val placedBefore = job.placed.cardinality()
            if (tickJob(level, job)) changed = true
            tickGrowDetail(level, job)
            val placedNow = job.placed.cardinality()
            job.idleTicks = if (placedNow > placedBefore) 0 else job.idleTicks + 1
            if (placedNow == job.voxels.size) {
                job.trunk?.let { playAt(level, it, EKSounds.WOHLON_RITUAL_COMPLETE.get(), COMPLETE_VOLUME, 1f) }
                iter.remove()
                changed = true
            } else if (job.idleTicks >= JOB_IDLE_LIMIT_TICKS) {
                iter.remove()
                changed = true
            }
        }
        if (changed) data.setDirty()
    }

    private fun tickGrowDetail(level: ServerLevel, job: TreeJob) {
        if (--job.detailCountdown > 0) return
        job.detailCountdown = GROW_DETAIL_MIN_TICKS + level.random.nextInt(GROW_DETAIL_SPREAD_TICKS + 1)
        val idx = job.lastSetIdx
        if (idx < 0) return
        val event = when {
            job.isRoot.get(idx) -> EKSounds.WOHLON_RITUAL_GROW_ROOTS
            job.isLeaf.get(idx) -> EKSounds.WOHLON_RITUAL_GROW_LEAVES
            else -> EKSounds.WOHLON_RITUAL_GROW_WOOD
        }
        val pitch = GROW_DETAIL_PITCH_MIN + level.random.nextFloat() * GROW_DETAIL_PITCH_SPREAD
        playAt(level, job.voxels[idx], event.get(), GROW_DETAIL_VOLUME, pitch)
    }

    private fun playAt(level: ServerLevel, packed: Long, event: SoundEvent, volume: Float, pitch: Float) {
        level.playSound(
            null, BlockPos.getX(packed) + 0.5, BlockPos.getY(packed) + 0.5, BlockPos.getZ(packed) + 0.5,
            event, SoundSource.BLOCKS, volume, pitch,
        )
    }

    /** Calls [action] with the trunk of each job, and true while the job ticks. */
    fun forEachTrunk(level: ServerLevel, action: (trunk: Long, growing: Boolean) -> Unit) {
        val data = level.dataStorage.get({ tag -> TreeJobsData.load(tag) }, DATA_NAME) ?: return
        for (job in data.jobs) {
            val trunk = job.trunk ?: continue
            // The sync can run before or after the grow tick in the same game tick.
            action(trunk, level.gameTime - job.lastGrowTick <= 1L)
        }
    }

    /** Scans the frontier in growth order. Wood needs a sturdy neighbour face for its bud; leaves need
     *  placed wood or leaves next to them. Voxels that fail stay in the frontier and are tried again. */
    private fun tickJob(level: ServerLevel, job: TreeJob): Boolean {
        // Architectury's RegistrySupplier.get checks the registry on each call; once per tick, not once per voxel.
        val wood = EKBlocks.WOGOR_WOOD.get()
        val leaves = EKBlocks.WOGOR_LEAVES.get()
        val bud = EKBlocks.WOGOR_BUD.get()
        var changed = false
        var scanBudget = MAX_SCAN_PER_TICK
        var placeBudget = placeBudgetFor(job)
        val newlyPlaced = ArrayList<Int>(64)
        // Scratch: the scan runs up to [MAX_SCAN_PER_TICK] times each tick.
        val pos = BlockPos.MutableBlockPos()
        val nPos = BlockPos.MutableBlockPos()
        job.lastSetIdx = -1
        var i = job.frontier.nextSetBit(0)
        while (i >= 0 && scanBudget > 0 && placeBudget > 0) {
            scanBudget--
            pos.set(job.voxels[i])
            val isLeaf = job.isLeaf.get(i)

            var didPlace = false
            if (isLeaf) {
                // Leaves stay tethered to their branch: wood or an already-placed leaf must touch them.
                var hasNeighbour = false
                for (dir in SIX_DIRECTIONS) {
                    val nState = level.getBlockState(nPos.setWithOffset(pos, dir))
                    if (
                        nState.`is`(wood) ||
                        nState.`is`(leaves)
                    ) {
                        hasNeighbour = true
                        break
                    }
                }
                if (hasNeighbour) {
                    val existing = level.getBlockState(pos)
                    if (isTreeReplaceable(existing)) {
                        val state = leaves.defaultBlockState()
                            .setValue(LeavesBlock.PERSISTENT, true)
                        // Flag 2 (UPDATE_CLIENTS only) — leaves
                        // are persistent and have no canSurvive
                        // requirement, and we want to avoid
                        // their placement triggering neighbour
                        // updateShape that could drop adjacent
                        // buds.
                        level.setBlock(pos.immutable(), state, 2)
                        job.lastSetIdx = i
                    }
                    didPlace = true
                }
            } else {
                // Wood (root, trunk, or canopy) — all gated on
                // a sturdy cardinal-face neighbour for the bud
                // animation to work, so `canSurvive` stays happy.
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
                // **Prefer a Wogor Wood neighbour as supportDir** (the tag gate checks it).
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
                // on it is bulletproof — that
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
                    nPos.setWithOffset(pos, dir)
                    val nState = level.getBlockState(nPos)
                    if (nState.`is`(wood)) {
                        supportDir = dir
                        break
                    }
                }
                if (supportDir == null) {
                    for (dir in SIX_DIRECTIONS) {
                        nPos.setWithOffset(pos, dir)
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
                        val soft = existing.isAir || existing.canBeReplaced()
                        // A bud is not sturdy: in a solid cell it takes that face from its neighbours, so wait
                        // while a neighbouring bud still hangs on this cell alone. Once that bud matures, retry.
                        if (!soft && orphansNeighbourBud(level, pos, bud)) {
                            i = job.frontier.nextSetBit(i + 1)
                            continue
                        }
                        // After the seed, a bud anchors only on wood, logs or mud; retry when matured wood is next to it.
                        if (job.hasSeed && !level.getBlockState(nPos.setWithOffset(pos, supportDir)).`is`(WogorBudBlock.WOGOR_BUD_GROWABLE)) {
                            i = job.frontier.nextSetBit(i + 1)
                            continue
                        }
                        // Soil is pushed aside into nearby air as mud when it can be; otherwise the bud takes its place.
                        if (!soft && (existing.`is`(Blocks.MUD) || existing.`is`(CONVERTS_TO_MUD))) {
                            tryDisplaceToAdjacentAir(level, pos)
                        }
                        // Flag 2: no neighbour updates, which could re-check and drop nearby buds.
                        level.setBlock(pos.immutable(), WogorBudBlock.newBud(level, pos, bud), 2)
                        job.lastSetIdx = i
                        job.hasSeed = true
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
            // Only neighbours in an open layer join; later ones join when their layer opens.
            val frontierEnd = job.layerEnds[job.currentLayer.coerceAtMost(job.layerEnds.size - 1)]
            val placedPositions = ArrayList<BlockPos>(newlyPlaced.size)
            for (idx in newlyPlaced) {
                val pos = BlockPos.of(job.voxels[idx])
                placedPositions.add(pos)
                for (dir in SIX_DIRECTIONS) {
                    val nKey = pos.relative(dir).asLong()
                    val nIdx = job.voxelIdx[nKey] ?: continue
                    if (job.placed.get(nIdx)) continue
                    if (nIdx < frontierEnd) job.frontier.set(nIdx)
                }
            }
            WohlonnogondoniaSpreader.convertCellsToWohlon(level, placedPositions)
        }

        // A layer opens when the one before it is complete, or after [LAYER_ADVANCE_INTERVAL] ticks, so
        // voxels the cascade cannot reach yet do not stall the tree.
        job.layerTimer++
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

    /** True when a bud next to [pos] has [pos] as its only sturdy support. */
    private fun orphansNeighbourBud(level: ServerLevel, pos: BlockPos, bud: Block): Boolean {
        val n = BlockPos.MutableBlockPos()
        val scratch = BlockPos.MutableBlockPos()
        for (dir in SIX_DIRECTIONS) {
            n.setWithOffset(pos, dir)
            if (!level.getBlockState(n).`is`(bud)) continue
            if (WogorBudBlock.supportMask(level, n, scratch) == 1 shl dir.opposite.ordinal) return true
        }
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
        val mud = EKBlocks.WOHLON_MUD.get().defaultBlockState()
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

    /** 8..11 rise → walk → plunge root arms, the chunkgen child-tree arms at reach 25–44 before water and slope. */
    private fun paintChildTreeRoots(
        site: WohlonTreeSite,
        baseX: Double, baseY: Double, baseZ: Double, seed: Int,
        out: MutableSet<Long>,
        segmentsOut: MutableList<TreeSegment>,
        tipsOut: LongArrayList,
    ) {
        val rootCount = 8 + ((wohlonHash32(seed, 0, 51) ushr 1) and 0x3)
        val share = PI * 2.0 / rootCount
        // Painted after the bend, so the arms go round what they cannot break.
        val armSegments = ArrayList<TreeSegment>()
        for (i in 0 until rootCount) {
            val angleHash = wohlonHash01(seed, i, 51)
            val baseAngle = i * share + (angleHash - 0.5) * share * 0.4
            // Hydrotropism: each arm turns, inside its own share, toward water and lower ground.
            val arm = site.rootArm(
                baseX, baseZ, baseY.toInt(), baseAngle, share * ROOT_TURN_SHARE,
                ROOT_REACH_MIN + (wohlonHash32(seed, i, 52) ushr 1) % ROOT_REACH_SPREAD,
            )
            val angle = arm[0]
            val cosA = cos(angle); val sinA = sin(angle)
            val perpX = -sinA; val perpZ = cosA

            val reach = arm[1].toInt()
            val plungeDepth = 18 + ((wohlonHash32(seed, i, 53) ushr 1) and 0xF)
            val wobblePhase = wohlonHash01(seed, i, 55) * PI * 2.0
            val wobbleAmp = 1.0 + wohlonHash01(seed, i, 56) * 1.5
            val walkUndulationPhase = wohlonHash01(seed, i, 57) * PI * 2.0
            val emergenceDY = (wohlonHash32(seed, i, 58) ushr 1 and 0x3).toDouble()
            val riseEnd = 0.12 + wohlonHash01(seed, i, 60) * 0.06
            val walkEnd = 0.70 + wohlonHash01(seed, i, 61) * 0.08

            val startX = baseX + cosA * 3.0
            val startZ = baseZ + sinA * 3.0
            val startY = baseY + emergenceDY + 1.0

            val steps = reach
            var prevX = startX; var prevY = startY; var prevZ = startZ
            var prevR = CHILD_TREE_ROOT_BASE_RADIUS
            // Off a narrow ledge the rise starts again from the foot of the wall.
            var riseBase = startY
            // The last walk point over land: where the arm starts to grow and the ignite tendril lands.
            var tipX = startX; var tipY = startY; var tipZ = startZ
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                val r = t * reach
                val wob = sin(t * PI * 3.0 + wobblePhase) * wobbleAmp
                val px = startX + r * cosA + perpX * wob
                val pz = startZ + r * sinA + perpZ * wob

                val footY = site.groundY(px.toInt(), pz.toInt()) + 1.0
                if (t < walkEnd && prevY - footY > CASCADE_MIN_DROP) {
                    val foot = cascadeDown(prevX, prevY, prevZ, prevR, px, footY, pz, perpX, perpZ, wobblePhase, armSegments)
                    prevX = px; prevY = footY; prevZ = pz; prevR = foot
                    riseBase = footY
                }

                val py = when {
                    t < riseEnd -> {
                        val rT = t / riseEnd
                        riseBase + 2.5 * (1.0 - (1.0 - rT) * (1.0 - rT))
                    }
                    t < walkEnd -> {
                        val wT = (t - riseEnd) / (walkEnd - riseEnd)
                        val bumpRaw = sin(wT * PI * 5.0 + walkUndulationPhase)
                        val bump = max(0.0, bumpRaw) * 4.0
                        // Over water the arm arches clear of the surface, as a prop root does; both terms are
                        // averaged over nearby columns so the arm eases over the shore and over terrain steps.
                        smoothGroundY(site, px, pz) + 1.0 + bump + ROOT_WATER_ARCH * waterShare(site, px, pz)
                    }
                    else -> {
                        val hillHere = site.groundY(px.toInt(), pz.toInt()).toDouble()
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

                armSegments.add(TreeSegment(prevX, prevY, prevZ, px, py, pz, prevR, curR))
                prevX = px; prevY = py; prevZ = pz; prevR = curR
                // The arm grows from its tip, and a bud over water has no face to hold. So the tip is the
                // last point over land; an arm over water grows out from the shore along its own wood.
                if (t < walkEnd && !site.isWater(px.toInt(), pz.toInt())) {
                    tipX = px; tipY = py; tipZ = pz
                }
            }
            tipsOut.add(BlockPos.asLong(floor(tipX).toInt(), floor(tipY).toInt(), floor(tipZ).toInt()))
        }
        for (seg in site.bendAround(armSegments, emptyList(), ::isBendObstacle).first) {
            segmentsOut.add(seg)
            paintSegmentSdf(seg, out)
        }
    }

    private fun smoothGroundY(site: WohlonTreeSite, x: Double, z: Double): Double {
        var sum = 0.0
        for (k in 0 until ROOT_SMOOTH_SAMPLES) sum += site.groundY(smoothX(x, k), smoothZ(z, k))
        return sum / ROOT_SMOOTH_SAMPLES
    }

    private fun waterShare(site: WohlonTreeSite, x: Double, z: Double): Double {
        var wet = 0
        for (k in 0 until ROOT_SMOOTH_SAMPLES) if (site.isWater(smoothX(x, k), smoothZ(z, k))) wet++
        return wet.toDouble() / ROOT_SMOOTH_SAMPLES
    }

    // Sample 0 is the column itself; 1–4 sit [ROOT_SMOOTH_RADIUS] away on the four sides.
    private fun smoothX(x: Double, k: Int): Int = (x + ROOT_SMOOTH_RADIUS * (if (k == 1) 1 else if (k == 2) -1 else 0)).toInt()
    private fun smoothZ(z: Double, k: Int): Int = (z + ROOT_SMOOTH_RADIUS * (if (k == 3) 1 else if (k == 4) -1 else 0)).toInt()

    /**
     * Segments from the edge at ([x0], [y0], [z0]) down a wall face to [footY] at ([x1], [z1]), as roots
     * grow down over ruins and rock: thin and swaying on the face, thick at the foot where the root meets
     * soil. Returns the foot radius.
     */
    private fun cascadeDown(
        x0: Double, y0: Double, z0: Double, r0: Double,
        x1: Double, footY: Double, z1: Double,
        perpX: Double, perpZ: Double, phase: Double,
        out: MutableList<TreeSegment>,
    ): Double {
        val drop = y0 - footY
        val steps = ceil(drop / CASCADE_STEP).toInt().coerceAtLeast(2)
        val faceR = r0 * CASCADE_THIN
        val footR = r0 * CASCADE_FOOT
        val sway = min(CASCADE_SWAY_MAX, drop * CASCADE_SWAY_PER_BLOCK)
        var px = x0; var py = y0; var pz = z0; var pr = r0
        for (k in 1..steps) {
            val f = k.toDouble() / steps
            val side = sin(f * PI * 2.0 + phase) * sway * sin(f * PI)
            val nx = x1 + perpX * side
            val nz = z1 + perpZ * side
            val ny = y0 - drop * f
            val nr = if (k == steps) footR else faceR
            out.add(TreeSegment(px, py, pz, nx, ny, nz, pr, nr))
            px = nx; py = ny; pz = nz; pr = nr
        }
        return footR
    }

    private class GrowthOrder(val voxels: LongArray, val isLeaf: BitSet, val isRoot: BitSet, val layerEnds: IntArray)

    /**
     * Sorts every voxel by growth time, in blocks of tip growth. Trunk and branches grow at their distance
     * along the skeleton from the foot, plus [THICKEN_LAG] times the square of their distance off the
     * centreline: the tips reach out first and the oldest wood is the thickest. Roots grow from the surface
     * tip the ignite tendrils reach, back toward the trunk and down into the plunge. Noise breaks each new
     * shell into patches. Leaves open [LEAF_LAG] after their branch and spread out from the twig.
     * Each layer is one block of growth time.
     */
    @Suppress("LongParameterList") // the planned voxel sets and the skeletons they came from
    private fun growthOrder(
        woodVoxels: Set<Long>, rootPositions: Set<Long>, leafVoxels: Set<Long>,
        canopy: List<TreeSegment>, roots: List<TreeSegment>, rootTips: LongArray, seed: Int,
    ): GrowthOrder {
        val canopyArcs = segmentArcs(canopy)
        val rootArcs = segmentArcs(roots)
        val tipArcs = rootTipArcs(roots, rootArcs, rootTips)
        val count = woodVoxels.size + leafVoxels.size
        val packed = LongArray(count)
        val kind = ByteArray(count)
        val keys = LongArray(count)
        val near = DoubleArray(3)
        val noiseOffset = (seed and 0xFFF).toDouble()
        var n = 0
        for (pos in woodVoxels) {
            val isRoot = pos in rootPositions
            nearestOnSkeleton(pos, if (isRoot) roots else canopy, if (isRoot) rootArcs else canopyArcs, near)
            if (isRoot) near[0] = abs(near[0] - tipArcs[near[2].toInt()])
            val patch = (WohlonnogondoniaChunkGenerator.valueNoise3D(
                BlockPos.getX(pos) * RING_NOISE_FREQ, BlockPos.getY(pos) * RING_NOISE_FREQ, BlockPos.getZ(pos) * RING_NOISE_FREQ + noiseOffset,
            ) - 0.5) * 2.0 * RING_NOISE
            keys[n] = growthKey(near[0] + near[1] * near[1] * THICKEN_LAG + patch, n)
            packed[n] = pos
            kind[n] = if (isRoot) KIND_ROOT else KIND_WOOD
            n++
        }
        for (pos in leafVoxels) {
            nearestOnSkeleton(pos, canopy, canopyArcs, near)
            keys[n] = growthKey(near[0] + LEAF_LAG + near[1] * LEAF_SPREAD, n)
            packed[n] = pos
            kind[n] = KIND_LEAF
            n++
        }
        keys.sort()
        val voxels = LongArray(count)
        val isLeaf = BitSet(count)
        val isRoot = BitSet(count)
        val ends = IntArrayList()
        var layer = -1L
        for (i in 0 until count) {
            val src = (keys[i] and 0xFFFFFFFFL).toInt()
            voxels[i] = packed[src]
            when (kind[src]) {
                KIND_ROOT -> isRoot.set(i)
                KIND_LEAF -> isLeaf.set(i)
            }
            val l = (keys[i] ushr 32) / GROWTH_TIME_SCALE
            if (l != layer) {
                if (i > 0) ends.add(i)
                layer = l
            }
        }
        ends.add(count)
        return GrowthOrder(voxels, isLeaf, isRoot, ends.toIntArray())
    }

    private fun growthKey(time: Double, index: Int): Long =
        ((time.coerceIn(0.0, MAX_GROWTH_TIME) * GROWTH_TIME_SCALE).toLong() shl 32) or (index.toLong() and 0xFFFFFFFFL)

    /** Distance along the skeleton from its foot to the start of each segment; parents come before children. */
    private fun segmentArcs(segments: List<TreeSegment>): DoubleArray {
        val arcs = DoubleArray(segments.size)
        val endArc = HashMap<Triple<Double, Double, Double>, Double>(segments.size * 2)
        for ((i, s) in segments.withIndex()) {
            arcs[i] = endArc[Triple(s.startX, s.startY, s.startZ)] ?: 0.0
            val len = sqrt((s.endX - s.startX).let { it * it } + (s.endY - s.startY).let { it * it } + (s.endZ - s.startZ).let { it * it })
            endArc[Triple(s.endX, s.endY, s.endZ)] = arcs[i] + len
        }
        return arcs
    }

    /**
     * For each root segment, the arc length of its arm's surface tip. A segment whose start no earlier
     * segment ends at begins a new arm; each arm takes the tip nearest to it.
     */
    private fun rootTipArcs(roots: List<TreeSegment>, arcs: DoubleArray, tips: LongArray): DoubleArray {
        val out = DoubleArray(roots.size)
        val near = DoubleArray(3)
        var start = 0
        while (start < roots.size) {
            var end = start + 1
            while (end < roots.size && arcs[end] != 0.0) end++
            var bestDist = Double.MAX_VALUE
            var tipArc = 0.0
            for (tip in tips) {
                nearestOnSkeleton(tip, roots, arcs, near, start, end)
                if (near[1] < bestDist) { bestDist = near[1]; tipArc = near[0] }
            }
            for (i in start until end) out[i] = tipArc
            start = end
        }
        return out
    }

    /**
     * Writes the arc length at the nearest skeleton point to `out[0]`, the distance to it to `out[1]`,
     * and the index of its segment to `out[2]`. Only segments `[from, to)` count.
     */
    private fun nearestOnSkeleton(
        pos: Long, segments: List<TreeSegment>, arcs: DoubleArray, out: DoubleArray, from: Int = 0, to: Int = segments.size,
    ) {
        val px = BlockPos.getX(pos) + 0.5
        val py = BlockPos.getY(pos) + 0.5
        val pz = BlockPos.getZ(pos) + 0.5
        var best = Double.MAX_VALUE
        out[0] = 0.0
        out[1] = 0.0
        out[2] = from.toDouble()
        for (i in from until to) {
            val s = segments[i]
            val dx = s.endX - s.startX; val dy = s.endY - s.startY; val dz = s.endZ - s.startZ
            val lenSq = dx * dx + dy * dy + dz * dz
            val t = if (lenSq < 1e-9) 0.0
                else (((px - s.startX) * dx + (py - s.startY) * dy + (pz - s.startZ) * dz) / lenSq).coerceIn(0.0, 1.0)
            val ex = px - s.startX - dx * t; val ey = py - s.startY - dy * t; val ez = pz - s.startZ - dz * t
            val d = ex * ex + ey * ey + ez * ez
            if (d < best) {
                best = d
                out[0] = arcs[i] + t * sqrt(lenSq)
                out[1] = sqrt(d)
                out[2] = i.toDouble()
            }
        }
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
         *  voxel enters the frontier. */
        val layerEnds: IntArray,
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
        /** The heart position. Null for a job saved before this field. Then the job plays no loop or finish sound. */
        val trunk: Long? = null,
        /** Visible end of each root arm, for the ignite tendrils. Not saved. */
        val rootTips: LongArray = LongArray(0),
        /** Site quality from water and light: scales the growth rate. 1 for a job saved before this field. */
        val quality: Float = 1f,
        /** Skeleton for the client growth ghost: [GHOST_SEGMENT_FLOATS] floats a segment, from the candle. Not saved. */
        val ghostSegments: FloatArray = FloatArray(0),
        /** Leaf blob centres for the growth ghost, 3 ints each, from the candle. Not saved. */
        val ghostTips: IntArray = IntArray(0),
    ) {
        // Runtime-only — rebuilt on construction / save load.
        val voxelIdx: HashMap<Long, Int> = HashMap(voxels.size + voxels.size / 2)
        val frontier: BitSet = BitSet(voxels.size)
        var minX = 0; var maxX = 0; var minZ = 0; var maxZ = 0
        var idleTicks = 0
        /** The game time of the last tick with the area loaded. */
        var lastGrowTick = Long.MIN_VALUE
        /** No growth before this game tick: the ignite animation plays first. Not saved; a reload grows at once. */
        var startTick = Long.MIN_VALUE
        /** The fraction of a placement left over from the last tick, so a rate below 1 a tick still grows. */
        var placeCarry = 0f
        /** A voxel that [tickJob] put into the level this tick, or -1. */
        var lastSetIdx = -1
        var detailCountdown = 0

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
            minX = Int.MAX_VALUE; maxX = Int.MIN_VALUE; minZ = Int.MAX_VALUE; maxZ = Int.MIN_VALUE
            for (i in voxels.indices) {
                voxelIdx[voxels[i]] = i
                val x = BlockPos.getX(voxels[i])
                val z = BlockPos.getZ(voxels[i])
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (z < minZ) minZ = z
                if (z > maxZ) maxZ = z
            }

            frontier.clear()
            val frontierEnd = layerEnds[currentLayer.coerceAtMost(layerEnds.size - 1)]
            for (i in voxels.indices) {
                if (placed.get(i)) continue
                if (i >= frontierEnd) continue
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
        @JvmField val pending: MutableList<TreeBuildParams> = mutableListOf()

        override fun save(tag: CompoundTag): CompoundTag {
            val pendingList = ListTag()
            for (params in pending) pendingList.add(params.save())
            tag.put("pending", pendingList)
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
                job.trunk?.let { t.putLong("trunk", it) }
                t.putFloat("quality", job.quality)
                jobsList.add(t)
            }
            tag.put("jobs", jobsList)
            return tag
        }

        companion object {
            fun load(tag: CompoundTag): TreeJobsData {
                val data = TreeJobsData()
                val pendingList = tag.getList("pending", Tag.TAG_COMPOUND.toInt())
                for (i in 0 until pendingList.size) {
                    TreeBuildParams.load(pendingList.getCompound(i))?.let(data.pending::add)
                }
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
                        trunk = if (t.contains("trunk", Tag.TAG_LONG.toInt())) t.getLong("trunk") else null,
                        quality = if (t.contains("quality", Tag.TAG_FLOAT.toInt())) t.getFloat("quality") else 1f,
                    )
                    job.rebuildRuntimeState()
                    data.jobs.add(job)
                }
                return data
            }
        }
    }
}
