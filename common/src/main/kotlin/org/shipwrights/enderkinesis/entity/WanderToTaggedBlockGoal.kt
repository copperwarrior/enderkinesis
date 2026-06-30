package org.shipwrights.enderkinesis.entity

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import java.util.EnumSet
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.village.poi.PoiManager
import net.minecraft.world.entity.ai.village.poi.PoiTypes
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKPoiTypes

/**
 * Goal: pick a random POI from the cataloger's target set (custom POI plus
 * vanilla LECTERN) within [searchRadius] blocks, path to it, and once arrived
 * stand still and stare at it until [STARE_DURATION_TICKS] elapses and a new
 * target is picked.
 *
 * The lookup goes through [PoiManager], which keeps a per-chunk-section index
 * of POIs and returns matches in O(records-in-range) rather than O(blocks-in-
 * range). A wide search radius is essentially free with this approach,
 * whereas a `BlockPos.betweenClosed` scan over the same range was the
 * dominant tick cost for catalogers.
 */
class WanderToTaggedBlockGoal(
    private val mob: PathfinderMob,
    private val searchRadius: Int,
) : Goal() {

    private enum class Phase { MOVING, STARING }

    private var target: BlockPos? = null
    /** Most recently chosen target — excluded from the next pick so the
     *  cataloger never ping-pongs between the same two POIs. */
    private var lastTarget: BlockPos? = null
    /** Path computed inside [pickTarget] (so we never commit to a target the
     *  pathfinder can't reach). Consumed by [start]. */
    private var pendingPath: Path? = null

    /** Recent-failure cache keyed by `BlockPos.asLong()` → game-tick at which
     *  the cooldown expires. Mirrors villager `AcquirePoi`'s `JitteredLinearRetry`
     *  idea (without the jitter): when a batch pathfind fails for a POI, that
     *  POI is skipped during selection for [RETRY_COOLDOWN_TICKS]. Keeps us
     *  from spending pathfinder cycles repeatedly probing the same unreachable
     *  POI — and from picking it as a target only to abort a few hundred ticks
     *  later. Cleaned periodically in [pickTarget] to bound memory. */
    private val retryTimes: Long2LongOpenHashMap = Long2LongOpenHashMap().apply {
        defaultReturnValue(0L)
    }

    /** Next game-tick at which [retryTimes] gets a sweep for expired entries. */
    private var nextRetryCleanup: Long = 0L
    /** World-space point the cataloger stares at while in [Phase.STARING].
     *  Computed once on arrival from the target block's voxel shape so the
     *  gaze lands on the *visible* part of the target (sign face, lectern
     *  book, etc.) rather than the geometric centre of the block — which
     *  for thin POIs like wall signs is buried inside the supporting wall. */
    private var lookAt: Vec3 = Vec3.ZERO
    private var phase: Phase = Phase.MOVING
    private var ticksUntilScan = 0
    private var ticksInPhase = 0
    /** When the navigation reports `isDone` but we haven't actually
     *  arrived (partial path, blocked node…), wait this many ticks
     *  before asking the pathfinder to try again. */
    private var ticksUntilRecompute = 0
    /** Consecutive failed recomputes since the last successful path
     *  step. Drops the target once it crosses [MAX_RECOMPUTE_ATTEMPTS]
     *  so we don't burn the whole movement budget on a target the
     *  pathfinder genuinely can't reach. */
    private var recomputeAttempts = 0
    /** Guards the one-shot lectern translation so it fires a single time
     *  per stare, on the tick the stare completes. Reset when a new
     *  stare begins. */
    private var scribed = false

    init {
        flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
    }

    override fun canUse(): Boolean {
        if (ticksUntilScan > 0) {
            ticksUntilScan--
            return false
        }
        if (mob.navigation.isInProgress) return false
        val pick = pickTarget() ?: run {
            ticksUntilScan = SCAN_COOLDOWN_TICKS
            return false
        }
        target = pick
        return true
    }

    override fun start() {
        val path = pendingPath
        pendingPath = null
        if (path != null) {
            mob.navigation.moveTo(path, MOVE_SPEED)
        }
        phase = Phase.MOVING
        ticksInPhase = 0
        ticksUntilRecompute = 0
        recomputeAttempts = 0
    }

    override fun canContinueToUse(): Boolean {
        val t = target ?: return false
        return when (phase) {
            Phase.MOVING -> ticksInPhase < MAX_MOVE_TICKS
            Phase.STARING -> ticksInPhase < STARE_DURATION_TICKS
        }
    }

    override fun tick() {
        val t = target ?: return
        ticksInPhase++
        when (phase) {
            Phase.MOVING -> {
                val withinReach = mob.distanceToSqr(
                    t.x + 0.5, t.y.toDouble(), t.z + 0.5
                ) <= ARRIVE_DIST_SQ
                if (withinReach) {
                    mob.navigation.stop()
                    lookAt = voxelShapeCenter(t)
                    phase = Phase.STARING
                    ticksInPhase = 0
                    scribed = false
                    return
                }
                // Path finished but we're still far from the target —
                // recompute periodically. Pathfinder often returns
                // partial paths in Sselith's tight corridors, so without
                // this the cataloger would stop short and slip straight
                // to STARING from far away.
                if (mob.navigation.isDone) {
                    if (ticksUntilRecompute > 0) {
                        ticksUntilRecompute--
                        return
                    }
                    val path = mob.navigation.createPath(t, NAVIGATION_REGION_OFFSET)
                    if (path != null) {
                        mob.navigation.moveTo(path, MOVE_SPEED)
                        recomputeAttempts = 0
                    } else if (++recomputeAttempts >= MAX_RECOMPUTE_ATTEMPTS) {
                        // Unreachable; drop the target and let canContinueToUse
                        // tear the goal down so a fresh POI gets picked.
                        target = null
                        return
                    }
                    ticksUntilRecompute = RECOMPUTE_INTERVAL
                }
            }
            Phase.STARING -> {
                mob.lookControl.setLookAt(lookAt.x, lookAt.y, lookAt.z)
                // Stare complete: file the target. If it's a lectern with
                // a book-and-quill or written book, the Cataloger rewrites
                // it into Sselith. No-op for any other POI. Fires once,
                // on the final stare tick.
                if (!scribed && ticksInPhase >= STARE_DURATION_TICKS) {
                    scribed = true
                    (mob.level() as? ServerLevel)?.let { CatalogerScribe.fileLectern(it, t) }
                }
            }
        }
    }

    /** World-space centre of the target block's render voxel shape. For
     *  thin POIs like bamboo wall signs the visual shape is a slab pressed
     *  against the supporting wall — its centre sits a few tenths of a
     *  block off the block-centre toward the wall face, so the cataloger's
     *  gaze lands on the sign instead of inside the wall behind it. */
    private fun voxelShapeCenter(pos: BlockPos): Vec3 {
        val level = mob.level()
        val state = level.getBlockState(pos)
        val shape = state.getShape(level, pos)
        if (shape.isEmpty) {
            return Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        }
        val bb = shape.bounds()
        return Vec3(
            pos.x + (bb.minX + bb.maxX) * 0.5,
            pos.y + (bb.minY + bb.maxY) * 0.5,
            pos.z + (bb.minZ + bb.maxZ) * 0.5,
        )
    }

    override fun stop() {
        mob.navigation.stop()
        target = null
        phase = Phase.MOVING
        ticksUntilScan = SCAN_COOLDOWN_TICKS / 2
    }

    /** Pull matching POIs in range from the chunk-section index, sample a small
     *  random batch, and ask the pathfinder for a single path that reaches ANY
     *  of them. Mirrors vanilla `AcquirePoi`'s batch-pathfind pattern:
     *
     *   - **One** `createPath(Set<BlockPos>, regionOffset)` call instead of N
     *     individual ones — the pathfinder expands once and returns the cheapest
     *     reachable target in the set, so we get reachability + cost-ordering
     *     in a single expansion instead of paying for the same expansion N times.
     *   - **`path.canReach()`** rejects partial paths. Before, any non-null path
     *     was accepted, which meant the cataloger would commit to a target it
     *     could only walk *toward* and then stop short — visible as "stares at
     *     a wall after wandering halfway there".
     *   - **[retryTimes] cooldown** marks every POI in a failed batch so we
     *     don't waste pathfinder cycles probing the same unreachable targets
     *     again for [RETRY_COOLDOWN_TICKS]. POIs become eligible again after
     *     the cooldown — handles cases like "blocked by another cataloger
     *     temporarily" or "chunk just unloaded".
     *
     *  The previous in-pool elimination loop is gone — one batch pathfind does
     *  the job of the loop, more cheaply. */
    private fun pickTarget(): BlockPos? {
        val level = mob.level() as? ServerLevel ?: return null
        val poiManager: PoiManager = level.poiManager
        val origin = mob.blockPosition()
        val now = level.gameTime

        // Periodic sweep of expired retry entries so the map can't grow without
        // bound in a long-running cataloger that explores a lot of POIs.
        if (now >= nextRetryCleanup) {
            retryTimes.long2LongEntrySet().removeIf { it.longValue <= now }
            nextRetryCleanup = now + RETRY_CACHE_CLEANUP_INTERVAL_TICKS
        }

        // PoiTypes.LIBRARIAN is the villager-profession POI key whose matching
        // states are the lectern blockstates — vanilla already indexes every
        // lectern there, so we OR it into our predicate.
        val candidates = ArrayList<BlockPos>()
        val originY = origin.y
        poiManager.getInSquare(
            { holder ->
                holder.`is`(EKPoiTypes.CATALOGER_TARGET_KEY)
                    || holder.`is`(PoiTypes.LIBRARIAN)
                    || holder.`is`(EKPoiTypes.STATUE_KEY)
            },
            origin,
            searchRadius,
            PoiManager.Occupancy.ANY,
        ).forEach { record ->
            val pos = record.pos
            if (Math.abs(pos.y - originY) > SAME_FLOOR_Y) return@forEach
            // Skip POIs that are still in their failed-batch cooldown.
            if (retryTimes.get(pos.asLong()) > now) return@forEach
            if (candidates.size < MAX_CANDIDATES) candidates.add(pos.immutable())
        }
        if (candidates.isEmpty()) return null

        // Exclude last target to keep the cataloger from immediately re-staring
        // at the same POI. If filtering empties the pool, fall back to keeping
        // it (single-POI islands shouldn't make the cataloger sit forever).
        val prev = lastTarget
        val pool: MutableList<BlockPos> =
            if (candidates.size > 1 && prev != null) {
                val filtered = ArrayList<BlockPos>(candidates.size)
                for (c in candidates) if (c != prev) filtered.add(c)
                if (filtered.isEmpty()) candidates else filtered
            } else candidates

        // Sample a random batch (without replacement) from the pool. Randomness
        // gives the cataloger varied destinations across ticks; the pathfinder
        // then picks the cheapest reachable POI WITHIN this random subset.
        val batch = HashSet<BlockPos>(BATCH_SIZE)
        while (batch.size < BATCH_SIZE && pool.isNotEmpty()) {
            val idx = mob.random.nextInt(pool.size)
            batch.add(pool[idx])
            pool[idx] = pool[pool.size - 1]
            pool.removeAt(pool.size - 1)
        }

        // Batch pathfind — vanilla AcquirePoi's optimisation. ONE expansion
        // instead of N. The path's target is whichever batch member was found
        // cheapest-reachable.
        val path = mob.navigation.createPath(batch, NAVIGATION_REGION_OFFSET)
        if (path != null && path.canReach()) {
            val pathTarget = path.target?.immutable()
            if (pathTarget != null) {
                lastTarget = pathTarget
                // If the target has a facing (lectern, sign, chiseled bookshelf…),
                // try to walk to the tile in *front* of that face so we read it
                // head-on; keep the original adjacent approach if the front is
                // blocked or unreachable.
                pendingPath = frontApproachPath(pathTarget) ?: path
                return pathTarget
            }
        }

        // Batch failed (no path, or only partial). Mark every batch member as
        // recently-tried so the next scan skips them — same pattern villagers
        // use, just without the jittered linear backoff.
        val expiry = now + RETRY_COOLDOWN_TICKS
        for (pos in batch) {
            retryTimes.put(pos.asLong(), expiry)
        }
        return null
    }

    /** Path that stands on the tile in front of [poi]'s facing (region offset 0),
     *  or null if [poi] has no facing or its front is unreachable. */
    private fun frontApproachPath(poi: BlockPos): Path? {
        val front = frontTileOf(mob.level().getBlockState(poi), poi) ?: return null
        val p = mob.navigation.createPath(front, 0)
        return if (p != null && p.canReach()) p else null
    }

    companion object {

        /** Block immediately in front of [state]'s horizontal facing — the side a
         *  reader/viewer stands on (lecterns, signs, chiseled bookshelves…). Null
         *  when the block carries no horizontal facing. Shared with the client
         *  menu-walk (`SselithMenuWalk`) so a possessed player approaches faced
         *  POIs the same way a Cataloger does. */
        fun frontTileOf(state: BlockState, pos: BlockPos): BlockPos? {
            val facing = when {
                state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ->
                    state.getValue(BlockStateProperties.HORIZONTAL_FACING)
                state.hasProperty(BlockStateProperties.FACING) -> {
                    val f = state.getValue(BlockStateProperties.FACING)
                    if (f.axis.isHorizontal) f else return null
                }
                else -> return null
            }
            return pos.relative(facing)
        }
        private const val MOVE_SPEED = 1.0
        /** Squared distance from the target's block centre at which the
         *  cataloger stops pathing and switches to STARING. 2.25 → about
         *  1.5 blocks centre-to-centre, so the cataloger gets up close
         *  to the POI before staring. Combined with the tightened
         *  [NAVIGATION_REGION_OFFSET], the pathfinder is asked to stop
         *  1 block short of the POI block itself; this fudge accounts
         *  for the diagonal between a non-walkable target block and the
         *  walkable cell next to it. */
        private const val ARRIVE_DIST_SQ = 2.25
        /** Hard cap on time spent pathing toward a target before giving up. */
        private const val MAX_MOVE_TICKS = 20 * 45   // 45s
        /** How long the cataloger stares at a reached target before picking
         *  a new one. */
        private const val STARE_DURATION_TICKS = 20 * 12  // 12s
        /** Cooldown between failed POI scans. */
        private const val SCAN_COOLDOWN_TICKS = 20 * 2
        /** Upper bound on POI candidates collected per scan, mostly to cap
         *  picker-loop work; POI queries are already cheap. */
        private const val MAX_CANDIDATES = 256
        /** Max |ΔY| from the cataloger's own Y for a POI to be considered.
         *  Set to 2 maze-cell heights (`2 × MAZE_CELL_Y = 48`) so the cataloger
         *  considers POIs up to two floors away — the ground navigation handles
         *  ladders (`BlockPathTypes.LADDER`, malus 0) and trapdoors
         *  (`BlockPathTypes.DOOR_OPEN`) on its own, so vertical traversal just
         *  works once we stop filtering candidates by floor. Multi-floor paths
         *  visit more pathing nodes; [Cataloger.PATHFIND_NODE_MULTIPLIER] bumps
         *  the budget to cover them. */
        private const val SAME_FLOOR_Y = 48
        /** Random POIs included in each batch passed to `createPath(Set, …)`.
         *  Matches vanilla `AcquirePoi.BATCH_SIZE`. The pathfinder runs ONE
         *  expansion and returns the cheapest reachable target in the batch —
         *  so this caps how many POIs each scan competes over without paying
         *  per-POI pathfind cost. */
        private const val BATCH_SIZE = 5

        /** Game ticks a POI stays in [retryTimes] after a failed batch pathfind.
         *  60 s gives geometry / occupancy changes time to settle before we
         *  re-test the target, and matches the rough lower bound of vanilla
         *  villager `JitteredLinearRetry` cooldowns. */
        private const val RETRY_COOLDOWN_TICKS: Long = 60L * 20L

        /** How often [pickTarget] sweeps [retryTimes] for expired entries. */
        private const val RETRY_CACHE_CLEANUP_INTERVAL_TICKS: Long = 30L * 20L
        /** Region offset for `navigation.createPath`. The pathfinder is
         *  asked to stop this many blocks short of the POI block. POIs
         *  are themselves solid (lecterns, chiseled bookshelves, signs
         *  on posts), so the cataloger can't stand *on* them — but it
         *  can stand adjacent. 1 puts it on an immediately-adjacent
         *  cell; the [ARRIVE_DIST_SQ] fudge handles diagonals. */
        private const val NAVIGATION_REGION_OFFSET = 1
        /** Ticks to wait between recompute attempts when the navigation
         *  reports done but we're not within reach. 10 ≈ 0.5 s — long
         *  enough not to thrash the pathfinder, short enough that a
         *  partial-path stop reads as a tiny hesitation rather than a
         *  visible pause. */
        private const val RECOMPUTE_INTERVAL = 10
        /** After this many consecutive failed recomputes, abandon the
         *  current target. The goal will pick a new POI on the next
         *  scan. */
        private const val MAX_RECOMPUTE_ATTEMPTS = 4
    }
}
