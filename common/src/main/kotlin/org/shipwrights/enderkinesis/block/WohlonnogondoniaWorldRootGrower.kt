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
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.registry.EKBlocks
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Overworld-side world-root marching grower.
 *
 * One "march" per 3×3-chunk region, anchored at the chunk that first
 * flips to Wohlon biome inside that region. Each march has a head
 * that walks forward by one step per second, painting a sphere of
 * voxels around its current position. The head reads the level
 * state on every step — looking ahead to swerve around player
 * builds, looking down to follow the surface, looking up to track
 * its own previous wood for the vine drop.
 *
 * ## Visual contract
 *
 * Identical sphere geometry + path math as the chunkgen's surface
 * roots: yaw / pitch smooth random-walk, sine-targeted Y oscillation,
 * radius-3 sphere per step, identical branch rules, identical vine
 * drop rules. Only the *timing* differs — voxels emerge step by
 * step instead of all-at-once on install.
 *
 * ## What's different from the precomputed-path grower this replaces
 *
 * - **No precomputed voxel set.** The head holds its own state; the
 *   next step's sphere is rasterised and placed on the boundary tick,
 *   then the head idles for 19 ticks before advancing.
 * - **No off-thread build.** Path math is per-step, cheap, on the
 *   server tick thread. No `BACKGROUND_EXECUTOR`.
 * - **No `RootJob` voxel array.** Persistence is just the marching
 *   head state (position, direction, step index, the head's
 *   per-march sine parameters). ~50 bytes per head vs ~150 KB per job.
 * - **Reactive swerve.** Each step's proposed centerline is tested
 *   against [isRootReplaceable]; if it lands on a player-built block
 *   the head tries yaw / pitch offsets and takes the first open
 *   direction. The root visibly curves around builds.
 * - **Real-time vines.** Vine columns drop step-by-step from the
 *   tail wherever the head's sphere top sits high above local
 *   surface — no post-completion pass.
 */
object WohlonnogondoniaWorldRootGrower {

    private val LOG = LogUtils.getLogger()

    // ============================================================
    //   Region tiling — must match the chunkgen's surface-root tile
    // ============================================================
    private const val REGION_SIZE_CHUNKS = 3

    // ============================================================
    //   Path walker
    // ============================================================
    private const val TUNNEL_MAX_STEPS = 80
    private const val TUNNEL_STEP_LEN = 1.5
    /** Path-shape parameters restored to the **exact chunkgen values**
     *  so the XZ winding of the path looks like the Wohlonnogondonia
     *  dimension's surface roots. Yaw/pitch nudges, pitch limit, and
     *  Y-track elastic constant are the dimension's defaults; only
     *  the Y-range clamp + yAmp are Overworld-shrunk to keep the apex
     *  reasonable for surface terrain. */
    private const val TUNNEL_YAW_TURN = 0.40
    private const val TUNNEL_PITCH_TURN = 0.12
    private const val TUNNEL_PITCH_LIMIT = 0.5

    /** Chunkgen elastic Y-tracking constant. */
    private const val TUNNEL_Y_TRACK_BIAS = 0.07

    // Branching
    private const val TUNNEL_BRANCH_FIRST_STEP = 12
    private const val TUNNEL_BRANCH_STRIDE = 16
    private const val TUNNEL_BRANCH_MIN_STEPS = 20
    private const val TUNNEL_BRANCH_MAX_STEPS = 40

    // Sphere paint
    private const val ROOT_TUBE_RADIUS = 3
    /** Length of the head/tail taper window in steps. The first /
     *  last N steps of the main path have a radius that ramps
     *  linearly from 1 → [ROOT_TUBE_RADIUS] (start) and
     *  [ROOT_TUBE_RADIUS] → 1 (tail). Branches only get the tail
     *  taper — their head sits inside the parent root and doesn't
     *  need a visual taper. */
    private const val ROOT_TAPER_STEPS = 5

    // Vines
    private const val VINE_ELEVATION_THRESHOLD = 5
    private const val VINE_MIN_LEN = 2
    private const val VINE_MAX_LEN = 9

    // ============================================================
    //   Pacing
    // ============================================================
    /** Hard cap on the sphere-fill wait. A sphere voxel that can't
     *  find sturdy support waits for its neighbour buds to mature
     *  into Wogor Wood, then retries — this is the "wave" mechanism.
     *  After this many ticks of retries, any still-unplaced voxels
     *  are abandoned and the head advances. */
    private const val MAX_SPHERE_FILL_TICKS = 100

    /** Soft cap on total simultaneous marching heads per dimension. */
    private const val MAX_CONCURRENT_HEADS_PER_DIM = 32

    private const val PENDING_QUEUE_CAP = 64

    /** Region tile size in blocks — matches the chunkgen's
     *  `TUNNEL_REGION_SIZE` (= TUNNEL_REGION_SIZE_CHUNKS × 16). */
    private const val REGION_SIZE = REGION_SIZE_CHUNKS * 16

    // ============================================================
    //   Player-build swerve
    // ============================================================
    /** Yaw offsets tried in order when the proposed centerline lands
     *  on a non-replaceable block. ±π/6, ±π/3, ±π/2 — the head tries
     *  small corrections first and only takes a hard turn if the
     *  small ones don't open up. */
    private val SWERVE_YAW_OFFSETS = doubleArrayOf(
        PI / 6, -PI / 6, PI / 3, -PI / 3, PI / 2, -PI / 2,
    )

    /** Pitch offsets tried after yaw — for vertical "go over / under"
     *  evasion when yaw alone doesn't open up. */
    private val SWERVE_PITCH_OFFSETS = doubleArrayOf(0.2, -0.2, 0.4, -0.4)

    // ============================================================
    //   SavedData keys
    // ============================================================
    private const val MARCHES_DATA_NAME = "enderkinesis_wohlon_world_root_marches"
    private const val GROWN_DATA_NAME = "enderkinesis_wohlon_world_root_grown_regions"

    // ============================================================
    //   Tags + cached blocks
    // ============================================================
    private val CONVERTS_TO_MUD: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "converts_to_mud"))
    private val ROOT_REPLACEABLE: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "root_replaceable"))

    private val SIX_DIRECTIONS: Array<Direction> = Direction.values()

    private val VINE_BLOCKS: Array<BlockState> by lazy {
        arrayOf(
            Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, true),
            Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true),
            Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, true),
            Blocks.VINE.defaultBlockState().setValue(VineBlock.WEST, true),
        )
    }

    // ============================================================
    //   In-memory state
    // ============================================================
    /** Pending trigger queue per dimension. Each entry is a packed
     *  `(chunkX, chunkZ)`. */
    private val pendingTriggers: ConcurrentHashMap<ResourceKey<Level>, ArrayDeque<Long>> =
        ConcurrentHashMap()

    // ============================================================
    //   Public API
    // ============================================================
    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::tickLevel)
    }

    /** Spreader-side trigger. Called on every cell flip — the
     *  grower's filter is the **deterministic-region** check:
     *  given the cell's region `(rx, rz)`, compute the region's
     *  fixed deterministic anchor position via the same hash the
     *  chunkgen uses (so the same regional path geometry would have
     *  rendered if this were a Wohlonnogondonia chunk). If the
     *  biome at that anchor position is Wohlon, the region's path
     *  is enqueued. Otherwise the flip is ignored.
     *
     *  Consequence: each region has at most one root, in a
     *  deterministic position fixed by `hash32(rx, rz, …)`. Adjacent
     *  regions have independent paths but the anchors are 48 blocks
     *  apart, so overlap is bounded — same density and layout as
     *  the Wohlon chunkgen. The trigger only fires once the anchor
     *  cell has flipped to Wohlon, so the start is always inside
     *  the biome by construction. */
    /** Cascade brake. While this is true, [maybeEnqueueCell] is a
     *  no-op. The grower's own per-tick `convertCellsToWohlon` call
     *  flips this on so root-induced biome paint can't bootstrap
     *  fresh marches in neighbouring regions — otherwise a single
     *  ritual's roots could expand outward indefinitely, painting
     *  Wohlon as they walk and triggering new roots at every region
     *  anchor they cross. Only "natural" spreader writes (random
     *  tick, ritual seed sphere, debug `/wohlon setcell`) leave the
     *  brake off and produce trigger events.
     *  Server thread only — there's no contention to worry about. */
    private var suppressTrigger: Boolean = false

    @JvmStatic
    fun maybeEnqueueCell(level: ServerLevel, qx: Int, qy: Int, qz: Int) {
        if (suppressTrigger) return
        if (level.dimension() == Wohlonnogondonia.LEVEL_KEY) return

        val chunkX = qx shr 2   // 4 quart cells per chunk axis
        val chunkZ = qz shr 2
        val regionX = Math.floorDiv(chunkX, REGION_SIZE_CHUNKS)
        val regionZ = Math.floorDiv(chunkZ, REGION_SIZE_CHUNKS)
        val regionPacked = packRegion(regionX, regionZ)

        if (getGrownData(level).regions.contains(regionPacked)) return

        // Region's deterministic seed — same hash + sparse-gap mask
        // the chunkgen's `buildTunnelPaths` uses for region seeding.
        // ~6% of regions are sparse-gap (no roots ever).
        val pathSeed = hash32(regionX, regionZ, 0x70F1FA52.toInt())
        if ((pathSeed and 0xF) == 0) {
            getGrownData(level).markGrown(regionPacked)
            return
        }

        // Region's deterministic anchor XZ — same formula the
        // chunkgen uses. Within the region's 48 × 48 block footprint.
        val startWX = regionX * REGION_SIZE +
            (hash32(regionX, regionZ, 1) and (REGION_SIZE - 1))
        val startWZ = regionZ * REGION_SIZE +
            (hash32(regionX, regionZ, 2) and (REGION_SIZE - 1))
        val startQx = startWX shr 2
        val startQz = startWZ shr 2

        // Is the anchor inside Wohlon biome yet? If not, the spread
        // hasn't reached the anchor — wait for a future flip.
        val source = level.chunkSource
        val startCx = startQx shr 2
        val startCz = startQz shr 2
        val startChunk = source.getChunkNow(startCx, startCz) ?: return
        val startBiome = startChunk.getNoiseBiome(startQx, qy, startQz)
        if (!startBiome.`is`(Wohlonnogondonia.BIOME_KEY)) return

        // Already being marched?
        val marches = level.dataStorage.get({ tag -> MarchesData.load(tag) }, MARCHES_DATA_NAME)
        if (marches != null) {
            for (h in marches.heads) {
                if (h.regionPacked == regionPacked) return
            }
        }

        val pending = pendingTriggers.computeIfAbsent(level.dimension()) { ArrayDeque() }
        if (pending.size >= PENDING_QUEUE_CAP) return
        if (pending.contains(regionPacked)) return

        pending.addLast(regionPacked)
    }

    // ============================================================
    //   Tick loop
    // ============================================================
    private fun tickLevel(level: ServerLevel) {
        if (level.dimension() == Wohlonnogondonia.LEVEL_KEY) return
        drainPending(level)
        tickMarches(level)
    }

    private fun drainPending(level: ServerLevel) {
        val pending = pendingTriggers[level.dimension()] ?: return
        if (pending.isEmpty()) return

        val marches = getMarchesData(level)
        while (pending.isNotEmpty() && marches.heads.size < MAX_CONCURRENT_HEADS_PER_DIM) {
            val regionPacked = pending.removeFirst()
            val regionX = unpackRegionX(regionPacked)
            val regionZ = unpackRegionZ(regionPacked)

            if (getGrownData(level).regions.contains(regionPacked)) continue

            var alreadyActive = false
            for (h in marches.heads) {
                if (h.regionPacked == regionPacked) { alreadyActive = true; break }
            }
            if (alreadyActive) continue

            spawnMainHead(level, regionX, regionZ, regionPacked, marches)
        }
    }

    private fun spawnMainHead(
        level: ServerLevel, regionX: Int, regionZ: Int,
        regionPacked: Long, marches: MarchesData,
    ) {
        val pathSeed = hash32(regionX, regionZ, 0x70F1FA52.toInt())
        if ((pathSeed and 0xF) == 0) {
            getGrownData(level).markGrown(regionPacked)
            return
        }

        val startWX = regionX * REGION_SIZE +
            (hash32(regionX, regionZ, 1) and (REGION_SIZE - 1))
        val startWZ = regionZ * REGION_SIZE +
            (hash32(regionX, regionZ, 2) and (REGION_SIZE - 1))
        val startSurface = level.getHeight(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, startWX, startWZ,
        ) - 1

        // Build the main path's geometry NOW, while no wood has been
        // placed yet. Heightmap reads here are the original surface —
        // self-contour is impossible because nothing has been written.
        val mainPath = buildPath(
            level, pathSeed, pathIdx = 0,
            startX = startWX.toDouble() + 0.5,
            startY = startSurface.toDouble(),
            startZ = startWZ.toDouble() + 0.5,
            startYaw = hash01(pathSeed, 3, 0) * PI * 2.0,
            startPitch = (hash01(pathSeed, 4, 0) - 0.5) * 0.4,
            maxSteps = TUNNEL_MAX_STEPS,
        )

        // Rasterise the sphere voxels for the main path, with taper
        // applied at both ends (head + tail). Sort by distance² from
        // the anchor so the per-tick scan grows outward.
        val voxels = sortVoxelsByAnchor(
            sphereVoxels = computeSphereVoxelsForPath(
                mainPath, level, applyHeadTaper = true, applyTailTaper = true,
            ),
            anchorX = startWX, anchorY = startSurface, anchorZ = startWZ,
        )

        // Resolve every potential branch's fork into the voxel index
        // at the main path's centerline-at-fork-step position. Branches
        // spawn when their fork voxel actually places.
        val pendingBranches = ArrayList<PendingBranch>()
        var branchIdx = 0
        var stepIdx = TUNNEL_BRANCH_FIRST_STEP
        while (stepIdx < mainPath.count - 4) {
            val branchSeed = hash32(pathSeed, branchIdx, 300)
            if ((branchSeed and 0x3) == 0) {
                val forkX = mainPath.points[stepIdx * 3 + 0]
                val forkY = mainPath.points[stepIdx * 3 + 1]
                val forkZ = mainPath.points[stepIdx * 3 + 2]
                val forkVoxelKey = BlockPos.asLong(forkX, forkY, forkZ)
                val forkVoxelIdx = voxels.indexOfFirst { it == forkVoxelKey }
                if (forkVoxelIdx >= 0) {
                    pendingBranches.add(PendingBranch(
                        branchIdx = branchIdx,
                        forkVoxelIdx = forkVoxelIdx,
                        forkX = forkX, forkY = forkY, forkZ = forkZ,
                        parentYaw = mainPath.endingYawAtStep(stepIdx),
                    ))
                }
            }
            stepIdx += TUNNEL_BRANCH_STRIDE
            branchIdx++
        }

        val root = MarchingRoot(
            pathSeed = pathSeed,
            pathIdx = 0,
            regionPacked = regionPacked,
            anchorX = startWX,
            anchorY = startSurface,
            anchorZ = startWZ,
            voxels = voxels,
            placed = java.util.BitSet(voxels.size),
            scanCursor = 0,
            pendingBranches = pendingBranches,
            vinesDropped = false,
        )

        marches.heads.add(root)
        marches.setDirty()

        LOG.info(
            "WorldRoot: spawned region ({}, {}) anchor BlockPos{{x={}, y={}, z={}}} voxels={} pendingBranches={}",
            regionX, regionZ, startWX, startSurface, startWZ, voxels.size, pendingBranches.size,
        )
    }

    private fun tickMarches(level: ServerLevel) {
        val marches = level.dataStorage.get({ tag -> MarchesData.load(tag) }, MARCHES_DATA_NAME)
            ?: return
        if (marches.heads.isEmpty()) return

        var changed = false
        val newRoots = ArrayList<MarchingRoot>()
        val iter = marches.heads.iterator()
        while (iter.hasNext()) {
            val root = iter.next()
            val tickResult = tickRoot(level, root, newRoots)
            if (tickResult.changed) changed = true
            if (tickResult.completed) {
                iter.remove()
                changed = true
                var stillActive = false
                for (r in marches.heads) {
                    if (r.regionPacked == root.regionPacked) { stillActive = true; break }
                }
                for (r in newRoots) {
                    if (r.regionPacked == root.regionPacked) { stillActive = true; break }
                }
                if (!stillActive) {
                    getGrownData(level).markGrown(root.regionPacked)
                    LOG.info(
                        "WorldRoot: completed region ({}, {})",
                        unpackRegionX(root.regionPacked),
                        unpackRegionZ(root.regionPacked),
                    )
                }
            }
        }
        if (newRoots.isNotEmpty()) {
            marches.heads.addAll(newRoots)
            changed = true
        }
        if (changed) marches.setDirty()
    }

    private data class TickResult(val changed: Boolean, val completed: Boolean)

    /** Place at most one voxel from the root's sorted queue this tick.
     *  Scan starts at `scanCursor` (the lowest unplaced index seen so
     *  far), advances past any already-placed entries, then tries to
     *  place the next one. A failed placement (no sturdy support yet)
     *  is left in the queue and the scan continues to the next index
     *  — but the cursor stays at the failed one so future ticks
     *  retry from there.
     *
     *  When the cursor reaches the end of the queue without finding
     *  anything to place, the root has fully grown: drop its vines
     *  and retire. */
    private fun tickRoot(
        level: ServerLevel, root: MarchingRoot, newRootsBuf: MutableList<MarchingRoot>,
    ): TickResult {
        // Skip past any already-placed voxels at the cursor.
        while (root.scanCursor < root.voxels.size && root.placed.get(root.scanCursor)) {
            root.scanCursor++
        }

        // Try to place one voxel — scanning from cursor forward.
        val mutPos = BlockPos.MutableBlockPos()
        var i = root.scanCursor
        while (i < root.voxels.size) {
            if (root.placed.get(i)) { i++; continue }
            val packed = root.voxels[i]
            val pos = BlockPos.of(packed)
            mutPos.set(pos)
            val existing = level.getBlockState(mutPos)
            if (existing.`is`(EKBlocks.WOGOR_WOOD.get()) ||
                existing.`is`(EKBlocks.WOGOR_BUD.get())) {
                // Already placed (likely by another sibling root's
                // overlap). Mark placed, continue.
                root.placed.set(i)
                i++
                continue
            }
            val state = placeOneWood(level, pos)
            if (state != null) {
                root.placed.set(i)
                // Suppress the cascade-trigger hook for the duration
                // of this biome write — see [suppressTrigger] for why.
                suppressTrigger = true
                try {
                    WohlonnogondoniaSpreader.convertCellsToWohlon(level, listOf(pos))
                } finally {
                    suppressTrigger = false
                }
                // Branch fork check: did this voxel just open a fork?
                val pbIter = root.pendingBranches.iterator()
                while (pbIter.hasNext()) {
                    val pb = pbIter.next()
                    if (pb.forkVoxelIdx == i) {
                        spawnBranch(level, root, pb, newRootsBuf)
                        pbIter.remove()
                    }
                }
                return TickResult(true, false)
            }
            // Couldn't place yet (no sturdy support). Skip to next
            // candidate; this voxel stays for a future retry.
            i++
        }

        // Reached the end of the queue without placing. Either we're
        // fully done, or every remaining voxel is permanently blocked.
        if (!root.vinesDropped) {
            dropVinesAlongRoot(level, root)
            root.vinesDropped = true
            return TickResult(true, false)
        }
        return TickResult(false, true)
    }

    private fun spawnBranch(
        level: ServerLevel, parent: MarchingRoot, pb: PendingBranch,
        newRootsBuf: MutableList<MarchingRoot>,
    ) {
        val branchSeed = hash32(parent.pathSeed, pb.branchIdx, 300)
        val sign = if ((branchSeed and 0x10) == 0) 1.0 else -1.0
        val yawOffset = sign * (PI / 3.0 +
            ((branchSeed ushr 5) and 0xFF) / 256.0 * (PI / 3.0))
        val branchYaw = pb.parentYaw + yawOffset
        val branchPitch = (((branchSeed ushr 13) and 0xFFFF) /
            65536.0 - 0.5) * 0.4
        val branchLen = TUNNEL_BRANCH_MIN_STEPS +
            ((branchSeed ushr 21) and 0xFF) %
            (TUNNEL_BRANCH_MAX_STEPS - TUNNEL_BRANCH_MIN_STEPS + 1)

        val branchPath = buildPath(
            level, parent.pathSeed, pathIdx = 1 + pb.branchIdx,
            startX = pb.forkX.toDouble() + 0.5,
            startY = pb.forkY.toDouble(),
            startZ = pb.forkZ.toDouble() + 0.5,
            startYaw = branchYaw,
            startPitch = branchPitch,
            maxSteps = branchLen,
        )

        val branchVoxels = sortVoxelsByAnchor(
            sphereVoxels = computeSphereVoxelsForPath(
                branchPath, level, applyHeadTaper = false, applyTailTaper = true,
            ),
            anchorX = pb.forkX, anchorY = pb.forkY, anchorZ = pb.forkZ,
        )

        val branch = MarchingRoot(
            pathSeed = parent.pathSeed,
            pathIdx = 1 + pb.branchIdx,
            regionPacked = parent.regionPacked,
            anchorX = pb.forkX,
            anchorY = pb.forkY,
            anchorZ = pb.forkZ,
            voxels = branchVoxels,
            placed = java.util.BitSet(branchVoxels.size),
            scanCursor = 0,
            pendingBranches = ArrayList(),
            vinesDropped = false,
        )
        newRootsBuf.add(branch)
    }

    // ============================================================
    //   Path generation (chunkgen-faithful, run once at spawn)
    // ============================================================
    /**
     * Walk a winding path from `(startX, startY, startZ)` for
     * `maxSteps` steps, returning the per-step `(x, y, z)` centreline.
     * Same yaw/pitch random-walk + sine-targeted Y elastic the
     * chunkgen uses. Heightmap reads here are the original surface
     * because spawn-time precedes any wood placement, so the Y target
     * never re-includes wood we're about to place. Player-build swerve
     * also happens here once-per-step; the resulting path bends
     * around obstacles permanently.
     */
    private fun buildPath(
        level: ServerLevel, pathSeed: Int, pathIdx: Int,
        startX: Double, startY: Double, startZ: Double,
        startYaw: Double, startPitch: Double,
        maxSteps: Int,
    ): TunnelPath {
        val paramSeed = hash32(pathSeed, pathIdx, 0x57F1CE17.toInt())
        val yAmp = 12.0 + (paramSeed and 0x1F)     // chunkgen 12-43 range
        val yPeriod = 30.0 + ((paramSeed ushr 5) and 0x1F)
        val yPhase = ((paramSeed ushr 10) and 0xFF) / 256.0 * 2.0 * PI

        var x = startX
        var y = startY
        var z = startZ
        var yaw = startYaw
        var pitch = startPitch.coerceIn(-TUNNEL_PITCH_LIMIT, TUNNEL_PITCH_LIMIT)

        val points = IntArray(maxSteps * 3)
        val yaws = DoubleArray(maxSteps)
        val yMin = level.minBuildHeight + 2
        val yMax = level.maxBuildHeight - 2

        for (step in 0 until maxSteps) {
            val turnSeed = hash32(pathSeed, pathIdx, 100 + step)
            val nudgedYaw = yaw + ((turnSeed and 0xFFFF) / 65536.0 - 0.5) * TUNNEL_YAW_TURN
            val nudgedPitch = (pitch + (((turnSeed ushr 16) and 0xFFFF) / 65536.0 - 0.5) * TUNNEL_PITCH_TURN)
                .coerceIn(-TUNNEL_PITCH_LIMIT, TUNNEL_PITCH_LIMIT)

            // Reactive swerve runs here so the precomputed path
            // already curves around player builds. Once spawned,
            // the path is frozen — newly built obstructions just
            // leave gaps in the placement.
            val (chosenYaw, chosenPitch) = pickOpenDirection(
                level, x, y, z, nudgedYaw, nudgedPitch,
            )
            yaw = chosenYaw
            pitch = chosenPitch

            val cosp = cos(pitch)
            x += cos(yaw) * cosp * TUNNEL_STEP_LEN
            y += sin(pitch) * TUNNEL_STEP_LEN
            z += sin(yaw) * cosp * TUNNEL_STEP_LEN

            // Heightmap read uses ORIGINAL surface (no wood yet),
            // so the Y target never compounds upward step-after-step.
            val localSurface = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt(),
            ) - 1
            val targetY = localSurface + sin(yPhase + step * (2.0 * PI / yPeriod)) * yAmp
            y += (targetY - y) * TUNNEL_Y_TRACK_BIAS

            points[step * 3 + 0] = x.toInt()
            points[step * 3 + 1] = y.toInt().coerceIn(yMin, yMax)
            points[step * 3 + 2] = z.toInt()
            yaws[step] = yaw
        }
        return TunnelPath(points, maxSteps, yaws)
    }

    /** Look-ahead swerve test for `buildPath` — same as the previous
     *  per-tick version, but reads the path's current `(x, y, z)`
     *  directly rather than from a MarchingHead. */
    private fun pickOpenDirection(
        level: ServerLevel,
        x: Double, y: Double, z: Double,
        baseYaw: Double, basePitch: Double,
    ): Pair<Double, Double> {
        if (isOpenCenterline(level, x, y, z, baseYaw, basePitch)) return baseYaw to basePitch
        for (offset in SWERVE_YAW_OFFSETS) {
            val tryYaw = baseYaw + offset
            if (isOpenCenterline(level, x, y, z, tryYaw, basePitch)) return tryYaw to basePitch
        }
        for (offset in SWERVE_PITCH_OFFSETS) {
            val tryPitch = (basePitch + offset).coerceIn(-TUNNEL_PITCH_LIMIT, TUNNEL_PITCH_LIMIT)
            if (isOpenCenterline(level, x, y, z, baseYaw, tryPitch)) return baseYaw to tryPitch
        }
        return baseYaw to basePitch
    }

    private fun isOpenCenterline(
        level: ServerLevel,
        x: Double, y: Double, z: Double,
        yaw: Double, pitch: Double,
    ): Boolean {
        val cosp = cos(pitch)
        val px = (x + cos(yaw) * cosp * TUNNEL_STEP_LEN).toInt()
        val py = (y + sin(pitch) * TUNNEL_STEP_LEN).toInt()
        val pz = (z + sin(yaw) * cosp * TUNNEL_STEP_LEN).toInt()
        if (py < level.minBuildHeight + 2 || py > level.maxBuildHeight - 2) return false
        return isRootReplaceable(level.getBlockState(BlockPos(px, py, pz)))
    }

    // ============================================================
    //   Sphere rasterise (precomputed, with both-end taper)
    // ============================================================
    /** Rasterise every step's sphere into a deduplicated set, with
     *  head/tail taper applied to the sphere radius near the path
     *  ends. Result is a `Set<Long>` of unique `BlockPos.asLong`
     *  positions ready to be sorted by anchor distance. */
    private fun computeSphereVoxelsForPath(
        path: TunnelPath, level: ServerLevel,
        applyHeadTaper: Boolean, applyTailTaper: Boolean,
    ): Set<Long> {
        val voxels = HashSet<Long>(path.count * 64)
        val yMin = level.minBuildHeight + 2
        val yMax = level.maxBuildHeight - 2
        for (step in 0 until path.count) {
            val r = radiusAt(step, path.count, applyHeadTaper, applyTailTaper)
            val rSqMax = r * r
            val px = path.points[step * 3 + 0]
            val py = path.points[step * 3 + 1]
            val pz = path.points[step * 3 + 2]
            for (dy in -r..r) {
                val ny = py + dy
                if (ny < yMin || ny > yMax) continue
                val dySq = dy * dy
                for (dx in -r..r) {
                    val dxSq = dx * dx
                    for (dz in -r..r) {
                        val distSq = dxSq + dySq + dz * dz
                        if (distSq > rSqMax) continue
                        voxels.add(BlockPos.asLong(px + dx, ny, pz + dz))
                    }
                }
            }
        }
        return voxels
    }

    /** Sphere radius at step `i` with optional taper at one or both
     *  ends. Linear ramp from 1 → [ROOT_TUBE_RADIUS] over
     *  [ROOT_TAPER_STEPS] near the relevant end, full radius in the
     *  middle. */
    private fun radiusAt(
        i: Int, totalSteps: Int,
        applyHeadTaper: Boolean, applyTailTaper: Boolean,
    ): Int {
        if (applyHeadTaper && i < ROOT_TAPER_STEPS) {
            val frac = (i + 1).toDouble() / (ROOT_TAPER_STEPS + 1)
            return max(1, (ROOT_TUBE_RADIUS * frac).toInt())
        }
        if (applyTailTaper && i > totalSteps - 1 - ROOT_TAPER_STEPS) {
            val fromTail = totalSteps - 1 - i
            val frac = (fromTail + 1).toDouble() / (ROOT_TAPER_STEPS + 1)
            return max(1, (ROOT_TUBE_RADIUS * frac).toInt())
        }
        return ROOT_TUBE_RADIUS
    }

    /** Pack a set of voxels into a `LongArray` sorted by squared
     *  distance from `(anchorX, anchorY, anchorZ)` ascending — so the
     *  per-tick scan grows the root outward from its anchor block by
     *  block. */
    private fun sortVoxelsByAnchor(
        sphereVoxels: Set<Long>,
        anchorX: Int, anchorY: Int, anchorZ: Int,
    ): LongArray {
        val packed = LongArray(sphereVoxels.size)
        var idx = 0
        for (v in sphereVoxels) {
            packed[idx++] = v
        }
        // Sort by distance²-from-anchor packed in the high 32 bits;
        // voxel index in the low 32 bits. After sorting we unpack
        // the voxels by their indices to preserve original positions.
        val keyed = LongArray(packed.size)
        for (i in packed.indices) {
            val pos = BlockPos.of(packed[i])
            val dx = pos.x - anchorX
            val dy = pos.y - anchorY
            val dz = pos.z - anchorZ
            val distSq = (dx * dx + dy * dy + dz * dz).coerceAtMost(0x7FFFFFFF)
            keyed[i] = (distSq.toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)
        }
        java.util.Arrays.sort(keyed)
        val sorted = LongArray(packed.size)
        for (i in keyed.indices) {
            val origIdx = (keyed[i] and 0xFFFFFFFFL).toInt()
            sorted[i] = packed[origIdx]
        }
        return sorted
    }

    // ============================================================
    //   Vine drop (post-completion, scans actual placed wood)
    // ============================================================
    /** Walk every voxel position in the root's sorted list and treat
     *  each unique XZ column as a candidate for a vine drop. The
     *  topmost actual-wood block in that column is found via a level
     *  scan; if it sits at least [VINE_ELEVATION_THRESHOLD] blocks
     *  above local surface, the chunkgen-shaped hash gate runs and a
     *  cardinal-attached vine column descends from the cap into
     *  whatever air is below. */
    private fun dropVinesAlongRoot(level: ServerLevel, root: MarchingRoot) {
        val seenXZ = HashSet<Long>(root.voxels.size / 4)
        val mutPos = BlockPos.MutableBlockPos()
        val vineSeed = root.pathSeed xor 0x73A1F4C7.toInt()
        val yMin = level.minBuildHeight + 2
        val yMax = level.maxBuildHeight - 2

        for (packed in root.voxels) {
            val pos = BlockPos.of(packed)
            val xzKey = (pos.x.toLong() and 0xFFFFFFFFL) or
                ((pos.z.toLong() and 0xFFFFFFFFL) shl 32)
            if (!seenXZ.add(xzKey)) continue

            val wx = pos.x
            val wz = pos.z

            // Find the topmost actually-placed Wogor Wood in this
            // column. Scan from a generous ceiling above the anchor's
            // expected ceiling down to yMin.
            var topWoodY = Int.MIN_VALUE
            val scanTop = (root.anchorY + 60).coerceAtMost(yMax)
            for (sy in scanTop downTo yMin) {
                mutPos.set(wx, sy, wz)
                if (level.getBlockState(mutPos).`is`(EKBlocks.WOGOR_WOOD.get())) {
                    topWoodY = sy
                    break
                }
            }
            if (topWoodY == Int.MIN_VALUE) continue

            val surface = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz,
            ) - 1
            if (topWoodY - surface < VINE_ELEVATION_THRESHOLD) continue

            val h = hash32(wx, wz, vineSeed)
            if ((h and 0x3) != 0) continue

            val len = (VINE_MIN_LEN + ((h ushr 2) and 0x7))
                .coerceAtMost(VINE_MAX_LEN)
            val faceIdx = (h ushr 5) and 0x3
            val adjDx: Int
            val adjDz: Int
            val vineIdx: Int
            when (faceIdx) {
                0 -> { adjDx = 0; adjDz = 1; vineIdx = 0 }
                1 -> { adjDx = 0; adjDz = -1; vineIdx = 1 }
                2 -> { adjDx = -1; adjDz = 0; vineIdx = 2 }
                else -> { adjDx = 1; adjDz = 0; vineIdx = 3 }
            }
            val vx = wx + adjDx
            val vz = wz + adjDz
            val vineState = VINE_BLOCKS[vineIdx]
            for (v in 0 until len) {
                val vy = topWoodY - v
                if (vy < yMin) break
                val vinePos = BlockPos(vx, vy, vz)
                val existing = level.getBlockState(vinePos)
                if (!existing.isAir) break
                level.setBlock(vinePos, vineState, 2)
            }
        }
    }

    /** Bud → wood pipeline placement for a single voxel. Returns the
     *  state that was actually written, or `null` if nothing was
     *  placed (no sturdy face, or existing block not replaceable). */
    private fun placeOneWood(level: ServerLevel, pos: BlockPos): BlockState? {
        var supportDir: Direction? = null
        for (dir in SIX_DIRECTIONS) {
            val nState = level.getBlockState(pos.relative(dir))
            if (nState.`is`(EKBlocks.WOGOR_WOOD.get())) { supportDir = dir; break }
        }
        if (supportDir == null) {
            for (dir in SIX_DIRECTIONS) {
                val nPos = pos.relative(dir)
                val nState = level.getBlockState(nPos)
                if (nState.isFaceSturdy(level, nPos, dir.opposite)) {
                    supportDir = dir; break
                }
            }
        }
        if (supportDir == null) return null

        val existing = level.getBlockState(pos)
        if (!isRootReplaceable(existing)) return null

        val state = if (existing.isAir || existing.canBeReplaced()) {
            EKBlocks.WOGOR_BUD.get().defaultBlockState()
                .setValue(WogorBudBlock.AGE, 0)
                .setValue(WogorBudBlock.FACING, supportDir)
        } else {
            EKBlocks.WOGOR_WOOD.get().defaultBlockState()
        }
        level.setBlock(pos, state, 2)
        return state
    }


    // ============================================================
    //   Replaceable rules — mirror the tree grower
    // ============================================================
    private fun isTreeReplaceable(state: BlockState): Boolean {
        if (state.isAir) return true
        if (state.canBeReplaced()) return true
        if (state.`is`(Blocks.MUD)) return true
        if (state.`is`(CONVERTS_TO_MUD)) return true
        return false
    }

    private fun isRootReplaceable(state: BlockState): Boolean {
        if (isTreeReplaceable(state)) return true
        if (state.`is`(ROOT_REPLACEABLE)) return true
        return false
    }

    // ============================================================
    //   Hash helpers
    // ============================================================
    private fun hash32(a: Int, b: Int, salt: Int): Int {
        var h = a * 0x9E3779B1.toInt() xor (b * 0x85EBCA77.toInt()) xor (salt * 0xC2B2AE3D.toInt())
        h = (h xor (h ushr 15)) * 0x2C1B3C6D.toInt()
        h = (h xor (h ushr 12)) * 0x297A2D39.toInt()
        h = h xor (h ushr 15)
        return h
    }

    private fun hash01(seed: Int, k1: Int, k2: Int): Double =
        (hash32(seed, k1, k2) and 0x7FFFFFFF) / 2147483648.0

    // ============================================================
    //   Data classes
    // ============================================================
    /** Walked path centreline. `points` is interleaved `[x, y, z]`
     *  per step (`step * 3 + 0..2`). `yaws[step]` is the yaw the
     *  walker held at the end of step `step` — used when forking a
     *  branch so the branch's initial yaw is offset relative to the
     *  parent's actual heading at the fork point. */
    private class TunnelPath(
        val points: IntArray,
        val count: Int,
        val yaws: DoubleArray,
    ) {
        fun endingYawAtStep(step: Int): Double = yaws[step]
    }

    /** Branch fork resolved at spawn time but not yet activated.
     *  The branch becomes a real `MarchingRoot` once its
     *  `forkVoxelIdx` in the parent's voxel list actually places. */
    private class PendingBranch(
        val branchIdx: Int,
        val forkVoxelIdx: Int,
        val forkX: Int,
        val forkY: Int,
        val forkZ: Int,
        val parentYaw: Double,
    )

    /** The new precomputed-voxel root. `voxels` is sorted by
     *  squared distance from `(anchorX, anchorY, anchorZ)` so the
     *  per-tick scan paints outward from the anchor block-by-block.
     *  `scanCursor` is the cached low-water mark of unplaced indices
     *  so we don't re-scan the early voxels every tick once they're
     *  all placed. */
    private class MarchingRoot(
        val pathSeed: Int,
        val pathIdx: Int,
        val regionPacked: Long,
        val anchorX: Int,
        val anchorY: Int,
        val anchorZ: Int,
        val voxels: LongArray,
        val placed: java.util.BitSet,
        var scanCursor: Int,
        val pendingBranches: MutableList<PendingBranch>,
        var vinesDropped: Boolean,
    )

    // ============================================================
    //   SavedData
    // ============================================================
    private fun getMarchesData(level: ServerLevel): MarchesData {
        return level.dataStorage.computeIfAbsent(
            { tag -> MarchesData.load(tag) }, { MarchesData() }, MARCHES_DATA_NAME,
        )
    }

    private fun getGrownData(level: ServerLevel): GrownRegionsData {
        return level.dataStorage.computeIfAbsent(
            { tag -> GrownRegionsData.load(tag) }, { GrownRegionsData() }, GROWN_DATA_NAME,
        )
    }

    private class MarchesData : SavedData() {
        @JvmField val heads: MutableList<MarchingRoot> = mutableListOf()

        override fun save(tag: CompoundTag): CompoundTag {
            val list = ListTag()
            for (h in heads) {
                val t = CompoundTag()
                t.putInt("seed", h.pathSeed)
                t.putInt("idx", h.pathIdx)
                t.putLong("region", h.regionPacked)
                t.putInt("ax", h.anchorX)
                t.putInt("ay", h.anchorY)
                t.putInt("az", h.anchorZ)
                t.putLongArray("v", h.voxels)
                t.putLongArray("placed", h.placed.toLongArray())
                t.putInt("cursor", h.scanCursor)
                t.putBoolean("vines", h.vinesDropped)
                if (h.pendingBranches.isNotEmpty()) {
                    val branches = ListTag()
                    for (pb in h.pendingBranches) {
                        val bt = CompoundTag()
                        bt.putInt("bi", pb.branchIdx)
                        bt.putInt("fv", pb.forkVoxelIdx)
                        bt.putInt("fx", pb.forkX)
                        bt.putInt("fy", pb.forkY)
                        bt.putInt("fz", pb.forkZ)
                        bt.putDouble("py", pb.parentYaw)
                        branches.add(bt)
                    }
                    t.put("pending", branches)
                }
                list.add(t)
            }
            tag.put("heads", list)
            return tag
        }

        companion object {
            fun load(tag: CompoundTag): MarchesData {
                val data = MarchesData()
                if (!tag.contains("heads", Tag.TAG_LIST.toInt())) return data
                val list = tag.getList("heads", Tag.TAG_COMPOUND.toInt())
                for (i in 0 until list.size) {
                    val t = list.getCompound(i)
                    val voxels = t.getLongArray("v")
                    if (voxels.isEmpty()) continue
                    val placedBits = t.getLongArray("placed")
                    val placed = if (placedBits.isEmpty()) java.util.BitSet(voxels.size)
                        else java.util.BitSet.valueOf(placedBits)
                    val pendingBranches = ArrayList<PendingBranch>()
                    if (t.contains("pending", Tag.TAG_LIST.toInt())) {
                        val branches = t.getList("pending", Tag.TAG_COMPOUND.toInt())
                        for (j in 0 until branches.size) {
                            val bt = branches.getCompound(j)
                            pendingBranches.add(PendingBranch(
                                branchIdx = bt.getInt("bi"),
                                forkVoxelIdx = bt.getInt("fv"),
                                forkX = bt.getInt("fx"),
                                forkY = bt.getInt("fy"),
                                forkZ = bt.getInt("fz"),
                                parentYaw = bt.getDouble("py"),
                            ))
                        }
                    }
                    data.heads.add(MarchingRoot(
                        pathSeed = t.getInt("seed"),
                        pathIdx = t.getInt("idx"),
                        regionPacked = t.getLong("region"),
                        anchorX = t.getInt("ax"),
                        anchorY = t.getInt("ay"),
                        anchorZ = t.getInt("az"),
                        voxels = voxels,
                        placed = placed,
                        scanCursor = t.getInt("cursor"),
                        pendingBranches = pendingBranches,
                        vinesDropped = t.getBoolean("vines"),
                    ))
                }
                return data
            }
        }
    }

    private class GrownRegionsData : SavedData() {
        @JvmField val regions: MutableSet<Long> = HashSet()

        fun markGrown(packed: Long) {
            if (regions.add(packed)) setDirty()
        }

        override fun save(tag: CompoundTag): CompoundTag {
            tag.putLongArray("r", regions.toLongArray())
            return tag
        }

        companion object {
            fun load(tag: CompoundTag): GrownRegionsData {
                val data = GrownRegionsData()
                if (tag.contains("r", Tag.TAG_LONG_ARRAY.toInt())) {
                    for (v in tag.getLongArray("r")) data.regions.add(v)
                }
                return data
            }
        }
    }

    // ============================================================
    //   Pack helpers
    // ============================================================
    private fun packRegion(rx: Int, rz: Int): Long =
        (rx.toLong() and 0xFFFFFFFFL) or ((rz.toLong() and 0xFFFFFFFFL) shl 32)

    private fun unpackRegionX(packed: Long): Int = packed.toInt()
    private fun unpackRegionZ(packed: Long): Int = (packed ushr 32).toInt()
}
