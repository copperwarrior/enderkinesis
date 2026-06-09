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
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.dimension.WohlonnogondoniaSurfaceRoots
import org.shipwrights.enderkinesis.registry.EKBlocks
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Overworld-side root grower.
 *
 * The chunkgen's [WohlonnogondoniaSurfaceRoots] is the **source of truth**
 * for path geometry — same `buildPaths` call, same `paintPath` rasteriser,
 * byte-identical voxel set to what the chunkgen would have painted in
 * the Wohlonnogondonia dimension at the same `(regionX, regionZ)`.
 * What this grower adds on top is:
 *
 *  - **Trigger gate**: the region's deterministic anchor must already
 *    be Wohlon biome before its march spawns (`maybeEnqueueCell`).
 *  - **Anchor**: the biome cell that triggered the spawn — voxels sort
 *    by squared distance from there, so the wave visibly radiates
 *    outward from the spot the spread first arrived.
 *  - **Heightmap snapshot**: captured at spawn time before any wood is
 *    placed, fed into the utility as its `heightAt` callback. Fixes
 *    the Y self-contour bug — newly placed wood never re-feeds the
 *    target.
 *  - **Bud → wood pipeline**: each emitted voxel routes through
 *    [placeOneWood] which prefers a Wogor Wood neighbour for the
 *    bud's FACING, falls back to any sturdy face, and writes air /
 *    canBeReplaced positions as a bud while writing solid replaceable
 *    positions as direct wood.
 *  - **Cascade brake**: [suppressTrigger] flips on around our own
 *    biome paint so root-induced Wohlon writes can't bootstrap
 *    triggers in neighbour regions.
 */
object WohlonnogondoniaWorldRootGrower {

    private val LOG = LogUtils.getLogger()

    private const val REGION_SIZE_CHUNKS = WohlonnogondoniaSurfaceRoots.TUNNEL_REGION_SIZE_CHUNKS
    private const val REGION_SIZE = WohlonnogondoniaSurfaceRoots.TUNNEL_REGION_SIZE

    private const val MAX_CONCURRENT_HEADS_PER_DIM = 32
    private const val PENDING_QUEUE_CAP = 64

    /** Max horizontal blocks the path is allowed to deflect when its
     *  intended voxel position is occupied by a structure. Small budget
     *  on purpose — the noise-derived chunkgen path is the source of
     *  truth, so we only nudge around single walls and fence-posts;
     *  anything thicker than [MAX_SIDESTEP] blocks the root entirely
     *  (the voxel gives up and the path visibly stops at the obstruction). */
    private const val MAX_SIDESTEP = 2

    /** Cardinal directions probed by [tryPlaceWithSidestep], in the
     *  order they're tried. Stable order so adjacent voxels hitting the
     *  same structure tend to deflect the same way and the path stays
     *  visually continuous. */
    private val SIDESTEP_DIRS: Array<Direction> = arrayOf(
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST,
    )

    private const val MARCHES_DATA_NAME = "enderkinesis_wohlon_world_root_marches"
    private const val GROWN_DATA_NAME = "enderkinesis_wohlon_world_root_grown_regions"

    private val CONVERTS_TO_MUD: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "converts_to_mud"))
    private val ROOT_REPLACEABLE: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "root_replaceable"))

    private val SIX_DIRECTIONS: Array<Direction> = Direction.values()

    private val pendingTriggers: ConcurrentHashMap<ResourceKey<Level>, LinkedHashSet<Long>> =
        ConcurrentHashMap()

    /** Cascade brake. Flipped on around [tickRoot]'s
     *  `convertCellsToWohlon` call so the grower's own biome paint
     *  can't bootstrap fresh marches in neighbouring regions.
     *  Server thread only. */
    private var suppressTrigger: Boolean = false

    fun init() {
        TickEvent.SERVER_LEVEL_POST.register(::tickLevel)
        // Clear pending trigger state on server stop so loading a
        // different world after quitting to title doesn't drain the
        // previous world's queued region triggers into the new one.
        // (ResourceKey<Level> equality is by location, so two worlds'
        // overworlds share the same key in this static map.)
        dev.architectury.event.events.common.LifecycleEvent.SERVER_STOPPED.register { _ ->
            pendingTriggers.clear()
            suppressTrigger = false
        }
    }

    /**
     * Spreader trigger. The region's deterministic anchor cell must
     * be Wohlon biome by the time of the call; if not, the trigger
     * is ignored and we wait for a future flip.
     */
    @JvmStatic
    fun maybeEnqueueCell(level: ServerLevel, qx: Int, qy: Int, qz: Int) {
        if (suppressTrigger) return
        if (level.dimension() == Wohlonnogondonia.LEVEL_KEY) return

        val chunkX = qx shr 2
        val chunkZ = qz shr 2
        val regionX = Math.floorDiv(chunkX, REGION_SIZE_CHUNKS)
        val regionZ = Math.floorDiv(chunkZ, REGION_SIZE_CHUNKS)
        val regionPacked = packRegion(regionX, regionZ)

        if (getGrownData(level).regions.contains(regionPacked)) return

        val pathSeed = WohlonnogondoniaSurfaceRoots.hash32(regionX, regionZ, 0x70F1FA52.toInt())
        if ((pathSeed and 0xF) == 0) {
            getGrownData(level).markGrown(regionPacked)
            return
        }

        val startWX = regionX * REGION_SIZE +
            (WohlonnogondoniaSurfaceRoots.hash32(regionX, regionZ, 1) and (REGION_SIZE - 1))
        val startWZ = regionZ * REGION_SIZE +
            (WohlonnogondoniaSurfaceRoots.hash32(regionX, regionZ, 2) and (REGION_SIZE - 1))
        val startQx = startWX shr 2
        val startQz = startWZ shr 2

        // Bulk gate: the start cell must be Wohlon AND all 6 face-adjacent
        // cells must also be Wohlon. Roots are an interior-of-biome
        // phenomenon — a single Wohlon cell at the biome's perimeter
        // shouldn't be enough to fire a region march. We wait for the
        // biome spreader to bulk up around the hub before triggering.
        // Surface Y is used (not trigger Y) for the reasons documented
        // on the per-voxel gate below: spread arrives at the surface
        // first; trigger flips happen at various Y depths.
        val startSurfaceY = level.getHeight(
            Heightmap.Types.OCEAN_FLOOR, startWX, startWZ,
        ) - 1
        val startSurfaceQy = startSurfaceY shr 2
        if (!isCellInBulkWohlon(level, startQx, startSurfaceQy, startQz)) return

        val marches = level.dataStorage.get({ tag -> MarchesData.load(tag) }, MARCHES_DATA_NAME)
        if (marches != null) {
            for (h in marches.heads) {
                if (h.regionPacked == regionPacked) return
            }
        }

        val pending = pendingTriggers.computeIfAbsent(level.dimension()) { LinkedHashSet() }
        if (pending.contains(regionPacked)) return
        if (pending.size >= PENDING_QUEUE_CAP) return
        pending.add(regionPacked)
    }

    private fun tickLevel(level: ServerLevel) {
        if (level.dimension() == Wohlonnogondonia.LEVEL_KEY) return
        drainPending(level)
        tickMarches(level)
    }

    private fun drainPending(level: ServerLevel) {
        val pending = pendingTriggers[level.dimension()] ?: return
        if (pending.isEmpty()) return

        val marches = getMarchesData(level)
        val iter = pending.iterator()
        while (iter.hasNext() && marches.heads.size < MAX_CONCURRENT_HEADS_PER_DIM) {
            val regionPacked = iter.next()
            iter.remove()
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
        level: ServerLevel,
        regionX: Int, regionZ: Int,
        regionPacked: Long, marches: MarchesData,
    ) {
        // Snapshot the original surface heightmap for the region's
        // tile + a 33-block pad (sphere radius + walk reach margin)
        // so the chunkgen utility's walkPath never reads back a
        // height that includes our own placed wood.
        //
        // `OCEAN_FLOOR` (predicate: `state.blocksMotion()` only) is
        // used instead of `MOTION_BLOCKING_NO_LEAVES` so the
        // heightmap returns the seabed under lakes, oceans, and
        // rivers — fluids don't count as a stopper here. Trade-off:
        // leaves *do* count, so roots emerging under a pre-existing
        // forest canopy would anchor on the leaves; in practice the
        // grower stays under tainted Wohlon biomes where the trees
        // are sparse, and the lake-dive behaviour is the dominant
        // gain.
        val pad = 33
        val x0 = regionX * REGION_SIZE - pad
        val z0 = regionZ * REGION_SIZE - pad
        val w = REGION_SIZE + 2 * pad
        val snapshot = IntArray(w * w)
        for (dz in 0 until w) {
            for (dx in 0 until w) {
                snapshot[dz * w + dx] = level.getHeight(
                    Heightmap.Types.OCEAN_FLOOR,
                    x0 + dx, z0 + dz,
                ) - 1
            }
        }
        val heightAt: (Int, Int) -> Int = { wx, wz ->
            val lx = wx - x0
            val lz = wz - z0
            if (lx in 0 until w && lz in 0 until w) snapshot[lz * w + lx]
            else level.getHeight(Heightmap.Types.OCEAN_FLOOR, wx, wz) - 1
        }

        val yMin = level.minBuildHeight + 2
        val yMax = level.maxBuildHeight - 2

        val paths = WohlonnogondoniaSurfaceRoots.buildPaths(
            regionX, regionZ, heightAt, yMin, yMax,
            motherTreeExclusionSq = 0L,
            // Spread-driven world roots are no longer ground-locked
            // — letting the path's Y wander above the surface gives
            // the roots the same arc-and-plunge silhouette the
            // chunkgen Mother Tree's roots use, instead of clinging
            // flat to the heightmap. Roots that drift up still wrap
            // back down through the painted sphere geometry, so the
            // final voxel set still anchors to terrain at most
            // points.
            groundOnly = false,
        )
        if (paths.isEmpty()) {
            getGrownData(level).markGrown(regionPacked)
            return
        }

        // Run the source-of-truth sphere paint into a deduplicating
        // set. Voxels outside `[yMin, yMax]` get culled here so the
        // chunkgen behaviour (clip at world build bounds) matches.
        // Paints at the chunkgen default radius — same sinuous + branch
        // geometry the Wohlon dimension uses.
        val voxelSet = HashSet<Long>(4096)
        for (path in paths) {
            WohlonnogondoniaSurfaceRoots.paintPath(
                path,
                { x, y, z -> if (y in yMin..yMax) voxelSet.add(BlockPos.asLong(x, y, z)) },
            )
        }
        if (voxelSet.isEmpty()) {
            getGrownData(level).markGrown(regionPacked)
            return
        }

        // Hub = the deterministic origin of every path in this region —
        // the same `(startWX, startSurface, startWZ)` the utility uses
        // internally as the common emanation point. Sorting by distance
        // from the hub makes the wave radiate through every path
        // concurrently (step-1 voxels of every path are all roughly
        // equidistant from the hub), so the visual reads as "root
        // system emerging from its own centre and growing outward in
        // all directions". Recomputed via the same public hash so we
        // don't need to thread the value through `buildPaths`.
        val raw = voxelSet.toLongArray()
        val hubWX = regionX * REGION_SIZE +
            (WohlonnogondoniaSurfaceRoots.hash32(regionX, regionZ, 1) and (REGION_SIZE - 1))
        val hubWZ = regionZ * REGION_SIZE +
            (WohlonnogondoniaSurfaceRoots.hash32(regionX, regionZ, 2) and (REGION_SIZE - 1))
        val hubY = heightAt(hubWX, hubWZ)

        val keyed = LongArray(raw.size)
        for (i in raw.indices) {
            val pos = BlockPos.of(raw[i])
            val dx = pos.x - hubWX
            val dy = pos.y - hubY
            val dz = pos.z - hubWZ
            val distSq = (dx * dx + dy * dy + dz * dz).coerceAtMost(0x7FFFFFFF)
            keyed[i] = (distSq.toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)
        }
        java.util.Arrays.sort(keyed)
        val voxels = LongArray(raw.size)
        for (i in keyed.indices) {
            voxels[i] = raw[(keyed[i] and 0xFFFFFFFFL).toInt()]
        }

        val root = MarchingRoot(
            regionPacked = regionPacked,
            anchorX = hubWX, anchorY = hubY, anchorZ = hubWZ,
            voxels = voxels,
            placed = BitSet(voxels.size),
            scanCursor = 0,
        )
        marches.heads.add(root)
        marches.setDirty()

        LOG.info(
            "WorldRoot: spawned region ({}, {}) hub BlockPos{{x={}, y={}, z={}}} voxels={}",
            regionX, regionZ, hubWX, hubY, hubWZ, voxels.size,
        )
    }

    private fun tickMarches(level: ServerLevel) {
        val marches = level.dataStorage.get({ tag -> MarchesData.load(tag) }, MARCHES_DATA_NAME)
            ?: return
        if (marches.heads.isEmpty()) return

        var changed = false
        val iter = marches.heads.iterator()
        while (iter.hasNext()) {
            val root = iter.next()
            val result = tickRoot(level, root)
            if (result.changed) changed = true
            if (result.completed) {
                iter.remove()
                getGrownData(level).markGrown(root.regionPacked)
                LOG.info(
                    "WorldRoot: completed region ({}, {})",
                    unpackRegionX(root.regionPacked), unpackRegionZ(root.regionPacked),
                )
                changed = true
            }
        }
        if (changed) marches.setDirty()
    }

    private data class TickResult(val changed: Boolean, val completed: Boolean)

    /** Place at most one voxel this tick. Scans from `scanCursor`,
     *  advancing past any already-placed entries (which can happen
     *  when neighbour-root overlap pre-fills our positions). Failed
     *  placements (no sturdy face yet) stay in the queue for next
     *  tick's retry — as adjacent buds mature into wood the failed
     *  voxel becomes eligible. Voxels whose intended position is
     *  occupied by a structure get a horizontal-deflect attempt via
     *  [tryPlaceWithSidestep] before being given up on. */
    private fun tickRoot(level: ServerLevel, root: MarchingRoot): TickResult {
        while (root.scanCursor < root.voxels.size && root.placed.get(root.scanCursor)) {
            root.scanCursor++
        }

        val mutPos = BlockPos.MutableBlockPos()
        var i = root.scanCursor
        while (i < root.voxels.size) {
            if (root.placed.get(i)) { i++; continue }
            val pos = BlockPos.of(root.voxels[i])
            mutPos.set(pos)
            val existing = level.getBlockState(mutPos)
            if (existing.`is`(EKBlocks.WOGOR_WOOD.get()) ||
                existing.`is`(EKBlocks.WOGOR_BUD.get())) {
                root.placed.set(i)
                i++
                continue
            }
            val isSeed = !root.hasSeed
            val warp = tryPlaceWithSidestep(level, pos, isSeed)
            if (warp.placedAt != null) {
                root.placed.set(i)
                if (isSeed) root.hasSeed = true
                suppressTrigger = true
                try {
                    WohlonnogondoniaSpreader.convertCellsToWohlon(level, listOf(warp.placedAt))
                } finally {
                    suppressTrigger = false
                }
                return TickResult(true, false)
            }
            if (warp.gaveUp) {
                // Structure too tall for our warp budget — abandon this voxel
                // so we don't rescan it every tick forever.
                root.placed.set(i)
            }
            i++
        }

        return TickResult(false, true)
    }

    private data class WarpAttempt(val placedAt: BlockPos?, val gaveUp: Boolean)

    /** Try placement at [pos]. If a non-replaceable block (a player
     *  wall, fence, etc.) occupies the intended position, probe an
     *  expanding horizontal ring around it for the first reachable
     *  candidate within [MAX_SIDESTEP] blocks — the path locally
     *  deflects around the obstruction.
     *
     *  Y is never changed. The noise-derived path's vertical profile
     *  is part of its source-of-truth shape; only XZ nudges, and only
     *  within a few blocks, so the overall path-line stays close to
     *  what chunkgen would have painted.
     *
     *  Anything thicker than the budget (a wall ≥ 3 blocks deep, an
     *  enclosed room) returns gaveUp=true and the voxel is abandoned —
     *  the path visibly stops at the obstruction rather than burrowing
     *  through. */
    private fun tryPlaceWithSidestep(level: ServerLevel, pos: BlockPos, isSeed: Boolean): WarpAttempt {
        // Try the intended position first.
        val originalState = level.getBlockState(pos)
        if (isRootReplaceable(originalState)) {
            val placed = placeOneWood(level, pos, isSeed)
            if (placed != null) return WarpAttempt(pos, gaveUp = false)
            // Replaceable but no support — leave for cascade. Airborne
            // voxels wait for adjacent buds to mature into wood that
            // can carry them; retrying at sideways offsets wouldn't
            // help (the offset is still airborne).
            return WarpAttempt(null, gaveUp = false)
        }

        // Structure occupies the intended position. Probe horizontal
        // neighbours at distances 1..MAX_SIDESTEP for a clear cell.
        for (distance in 1..MAX_SIDESTEP) {
            for (dir in SIDESTEP_DIRS) {
                val tryPos = BlockPos(
                    pos.x + dir.stepX * distance,
                    pos.y,
                    pos.z + dir.stepZ * distance,
                )
                val tryState = level.getBlockState(tryPos)
                if (!isRootReplaceable(tryState)) continue
                val placed = placeOneWood(level, tryPos, isSeed)
                if (placed != null) return WarpAttempt(tryPos, gaveUp = false)
                // Replaceable but no support — fall through to next
                // candidate. Don't return early; another sidestep
                // direction may anchor onto already-placed wood.
            }
        }
        return WarpAttempt(null, gaveUp = true)
    }

    private fun placeOneWood(level: ServerLevel, pos: BlockPos, isSeed: Boolean): BlockState? {
        // Tree-bounds exclusion: the world-root grower must not paint
        // inside the cylinder around any registered ritual portal.
        // Trees and world roots are separate systems with their own
        // shapes; overlap reads as incoherent "tumour" growth. The
        // voxel stays in the queue but never qualifies — it's just
        // permanently dropped from this march.
        if (WohlonnogondoniaPortalManager.isInsideAnyTreeBounds(level, pos)) return null

        // Bulk gate: roots are only allowed inside biome *interior*, not
        // on the leading edge. The voxel's own cell plus all six
        // face-adjacent cells must be Wohlon. An isolated Wohlon cell
        // poking out into Plains keeps the root from painting there.
        // Failed placements stay in the queue and retry every tick — as
        // the spreader thickens the biome around this voxel, it
        // eventually qualifies.
        val qx = pos.x shr 2
        val qy = pos.y shr 2
        val qz = pos.z shr 2
        if (!isCellInBulkWohlon(level, qx, qy, qz)) return null

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

        // If the position currently holds mud (or a block that the
        // spreader would convert into mud), try to push it sideways
        // into an adjacent air pocket rather than overwriting it.
        // The soil is preserved, just one step over — visually reads
        // as "the root displaced the dirt" rather than erasing it.
        val displaceable = existing.`is`(Blocks.MUD) || existing.`is`(CONVERTS_TO_MUD)
        val didDisplace = displaceable && tryDisplaceToAdjacentAir(level, pos)

        // Successful displacement means the source position is about to
        // be re-set; treat as effectively empty so we plant a bud rather
        // than a hard wood log (matches the air/canBeReplaced branch).
        val placingIntoEmpty = didDisplace || existing.isAir || existing.canBeReplaced()
        val state = if (placingIntoEmpty) {
            // Bud branch — gate on WOGOR_BUD_GROWABLE unless this is the
            // root's seed voxel. The seed gets a one-time bypass so the
            // first bud can anchor onto whatever block the trigger landed
            // on (typically natural terrain, not yet any wogor block).
            // After the seed matures into WOGOR_WOOD, every subsequent
            // bud chains off that and the tag check is satisfied.
            if (!isSeed) {
                val supportState = level.getBlockState(pos.relative(supportDir))
                if (!supportState.`is`(WogorBudBlock.WOGOR_BUD_GROWABLE)) return null
            }
            EKBlocks.WOGOR_BUD.get().defaultBlockState()
                .setValue(WogorBudBlock.AGE, 0)
                .setValue(WogorBudBlock.FACING, supportDir)
        } else {
            EKBlocks.WOGOR_WOOD.get().defaultBlockState()
        }
        level.setBlock(pos, state, 2)
        return state
    }

    /** Drop a mud block into a cardinal-adjacent air cell, displacing
     *  the soil that the root just overwrote at `pos`. Always writes
     *  mud regardless of what the original block was — the Wohlon
     *  biome's `converts_to_mud` rule applies to displaced soil at
     *  the moment of relocation rather than waiting for the spreader
     *  to catch up.
     *
     *  Two-pass scan over SIX_DIRECTIONS:
     *
     *   1. **Supported air** first. A candidate qualifies if it's air
     *      AND the block directly beneath it (the candidate's `below()`)
     *      has a sturdy upper face. The candidate-below = `pos` case is
     *      excluded, since `pos` is about to be overwritten with a
     *      non-sturdy bud — the displaced block would visually float.
     *      This makes the common case (mud on flat ground with air
     *      above and dirt sideways) push the mud *sideways onto the
     *      neighbouring ground* rather than UP into the air column.
     *
     *   2. **Any air** as a fallback. If pass 1 found nothing, accept
     *      the first adjacent air we see — at least the soil is
     *      preserved somewhere, even if visually floating.
     *
     *  Returns true if a destination was found.
     */
    private fun tryDisplaceToAdjacentAir(level: ServerLevel, pos: BlockPos): Boolean {
        val mud = Blocks.MUD.defaultBlockState()
        // Pass 1: supported air.
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
        // Pass 2: any air.
        for (dir in SIX_DIRECTIONS) {
            val adj = pos.relative(dir)
            if (level.getBlockState(adj).isAir) {
                level.setBlock(adj, mud, 2)
                return true
            }
        }
        return false
    }

    /** True iff the biome cell `(qx, qy, qz)` is Wohlon AND every one
     *  of its six face-adjacent cells is also Wohlon. This is the
     *  "interior-of-biome" gate — used at both the region-trigger
     *  decision and at every voxel placement so roots never paint at
     *  the biome's leading edge.
     *
     *  Returns false on unloaded chunks (treat-as-non-Wohlon) so
     *  region triggers don't fire across unloaded territory. */
    private fun isCellInBulkWohlon(level: ServerLevel, qx: Int, qy: Int, qz: Int): Boolean {
        if (!isCellWohlon(level, qx, qy, qz)) return false
        if (!isCellWohlon(level, qx - 1, qy, qz)) return false
        if (!isCellWohlon(level, qx + 1, qy, qz)) return false
        if (!isCellWohlon(level, qx, qy - 1, qz)) return false
        if (!isCellWohlon(level, qx, qy + 1, qz)) return false
        if (!isCellWohlon(level, qx, qy, qz - 1)) return false
        if (!isCellWohlon(level, qx, qy, qz + 1)) return false
        return true
    }

    private fun isCellWohlon(level: ServerLevel, qx: Int, qy: Int, qz: Int): Boolean {
        val chunk = level.chunkSource.getChunkNow(qx shr 2, qz shr 2) ?: return false
        return chunk.getNoiseBiome(qx, qy, qz).`is`(Wohlonnogondonia.BIOME_KEY)
    }

    private fun isTreeReplaceable(state: BlockState): Boolean {
        if (state.isAir) return true
        if (state.canBeReplaced()) return true
        if (state.`is`(Blocks.MUD)) return true
        if (state.`is`(CONVERTS_TO_MUD)) return true
        // Barrier — test/observation aid. Replaceable by every grower
        // placement path but NOT in the spreader's `converts_to_mud`
        // tag, so a barrier arena stays as barriers while the roots
        // and trunk grow visibly through it.
        if (state.`is`(Blocks.BARRIER)) return true
        return false
    }

    private fun isRootReplaceable(state: BlockState): Boolean {
        if (isTreeReplaceable(state)) return true
        if (state.`is`(ROOT_REPLACEABLE)) return true
        return false
    }

    private class MarchingRoot(
        val regionPacked: Long,
        val anchorX: Int, val anchorY: Int, val anchorZ: Int,
        val voxels: LongArray,
        val placed: BitSet,
        var scanCursor: Int,
        var hasSeed: Boolean = false,
    )

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
                t.putLong("region", h.regionPacked)
                t.putInt("ax", h.anchorX)
                t.putInt("ay", h.anchorY)
                t.putInt("az", h.anchorZ)
                t.putLongArray("v", h.voxels)
                t.putLongArray("placed", h.placed.toLongArray())
                t.putInt("cursor", h.scanCursor)
                t.putBoolean("seed", h.hasSeed)
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
                    val placed = if (placedBits.isEmpty()) BitSet(voxels.size)
                        else BitSet.valueOf(placedBits)
                    data.heads.add(MarchingRoot(
                        regionPacked = t.getLong("region"),
                        anchorX = t.getInt("ax"),
                        anchorY = t.getInt("ay"),
                        anchorZ = t.getInt("az"),
                        voxels = voxels,
                        placed = placed,
                        scanCursor = t.getInt("cursor"),
                        hasSeed = t.getBoolean("seed"),
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

    private fun packRegion(rx: Int, rz: Int): Long =
        (rx.toLong() and 0xFFFFFFFFL) or ((rz.toLong() and 0xFFFFFFFFL) shl 32)

    private fun unpackRegionX(packed: Long): Int = packed.toInt()
    private fun unpackRegionZ(packed: Long): Int = (packed ushr 32).toInt()
}
