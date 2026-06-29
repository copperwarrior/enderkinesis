package org.shipwrights.enderkinesis.util

import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SculkShriekerBlock
import net.minecraft.world.level.block.SculkSpreader
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Catalyst-style sculk spread, used by the Scroll of Sculk Catastrophe
 * and reserved for future block-driven catalysts.
 *
 *  Two phases:
 *   1. **Instant bloom.** When [start] is called the 7×7×7 sphere
 *      around the centre is converted on the spot — each replaceable
 *      block becomes [Blocks.SCULK] or, with the same per-tile
 *      probabilities a vanilla cursor uses, a sensor / summoning
 *      shrieker / catalyst. Sculk veins drape on every exposed face
 *      of the sphere. Unsupported sculk falls as a [FallingBlockEntity].
 *   2. **Cursor extension.** Three per-cursor [SculkSpreader]s are
 *      seeded at the outer edge of the bloom — positions that are now
 *      sculk but border still-replaceable blocks — and walk outward
 *      over many server ticks, placing the same mix of blocks vanilla
 *      catalyst spread does. A mutual-distance prune kills the lower-
 *      charge cursor when two stray within [MIN_CURSOR_SEPARATION] of
 *      each other, so they don't converge into the same patch.
 *
 *  Active spreads are NOT persisted across save/load — a scroll fired
 *  the instant before a server restart loses its in-progress spread.
 *  The instant-bloom blocks are saved as plain world data, so what
 *  the player saw before save remains intact.
 */
object SculkSpread {

    /** Allow-list block tag for sculk-catastrophe replaceable blocks.
     *  Wider than vanilla `BlockTags.SCULK_REPLACEABLE`; same datapack-
     *  overridable shape. */
    val CATASTROPHE_REPLACEABLE: TagKey<Block> =
        TagKey.create(Registries.BLOCK, ResourceLocation("enderkinesis", "sculk_catastrophe_replaceable"))

    /** Vanilla-level cursor defaults (see `SculkSpreader.createLevelSpreader`),
     *  parameterised with our wider replaceable tag. `noGrowthRadius`
     *  is bumped from vanilla 4 → 8 so a single cursor avoids growing
     *  on its own recent placements at a longer reach, pushing the
     *  bloom outward instead of pooling near the seed. */
    private fun newSpreader(): SculkSpreader = SculkSpreader(
        /* isWorldGeneration = */ false,
        /* replaceableBlocks = */ CATASTROPHE_REPLACEABLE,
        /* growthSpawnCost   = */ 10,
        /* noGrowthRadius    = */ 8,
        /* chargeDecayRate   = */ 10,
        /* additionalDecayRate = */ 5,
    )

    /** Per-cursor track: its dedicated spreader and its start position.
     *  Separate spreaders per cursor mean vanilla's
     *  [SculkSpreader.ChargeCursor.mergeWith] can't collapse two of
     *  ours into one. */
    private class CursorTrack(
        val spreader: SculkSpreader,
        val start: BlockPos,
    )

    private class ActiveSpread(
        val tracks: MutableList<CursorTrack>,
        val center: BlockPos,
        val totalCharge: Int,
        val cursorCount: Int,
        var ticksRemaining: Int,
        var catalystsSpawned: Int = 0,
        /** Phase-1 state. [bloomShellIndex] is the NEXT Chebyshev-
         *  radius shell to place (0 = just the centre block,
         *  [INSTANT_BLOOM_RADIUS] = outermost shell); -1 means phase
         *  1 has finished and the cursors are loose. */
        var bloomShellIndex: Int = 0,
        var bloomTicksUntilNextShell: Int = 0,
        val converted: MutableSet<BlockPos> = HashSet(),
    )

    private val byDimension: ConcurrentHashMap<ResourceLocation, CopyOnWriteArrayList<ActiveSpread>> =
        ConcurrentHashMap()

    /** Register the per-tick driver. Called from common mod init. */
    fun init() {
        TickEvent.SERVER_LEVEL_POST.register { level ->
            tick(level)
        }
    }

    fun start(
        level: ServerLevel,
        center: BlockPos,
        totalCharge: Int = 600,
        cursorCount: Int = 3,
        maxTicks: Int = DEFAULT_MAX_TICKS,
    ) {
        val active = ActiveSpread(
            tracks = mutableListOf(),
            center = center.immutable(),
            totalCharge = totalCharge,
            cursorCount = cursorCount,
            ticksRemaining = maxTicks,
            // Tick driver places shell 0 on the first tick (no upfront
            // wait), then each subsequent shell after [TICKS_PER_SHELL]
            // ticks. The visible bloom expands outward from the centre.
            bloomShellIndex = 0,
            bloomTicksUntilNextShell = 0,
        )
        val list = byDimension.computeIfAbsent(level.dimension().location()) {
            CopyOnWriteArrayList()
        }
        list.add(active)
        level.playSound(
            null, center, SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.BLOCKS,
            1.5f, 0.7f + level.random.nextFloat() * 0.2f,
        )
    }

    /** Advance the phase-1 staged bloom. Places one shell per
     *  [TICKS_PER_SHELL] ticks; when the outermost shell has been
     *  placed, runs the vein + falling-block finalise and seeds the
     *  phase-2 cursors. */
    private fun tickBloomPhase(level: ServerLevel, spread: ActiveSpread, rng: RandomSource) {
        if (spread.bloomTicksUntilNextShell > 0) {
            spread.bloomTicksUntilNextShell--
            return
        }
        placeShell(level, spread, spread.bloomShellIndex, rng)
        spread.bloomShellIndex++
        if (spread.bloomShellIndex > INSTANT_BLOOM_RADIUS) {
            finalizeBloomAndSeedCursors(level, spread)
            // -1 marks phase 1 complete so the tick driver switches
            // to the per-cursor logic.
            spread.bloomShellIndex = -1
        } else {
            spread.bloomTicksUntilNextShell = TICKS_PER_SHELL
        }
    }

    /** Place one Chebyshev shell of the sphere — every (dx, dy, dz)
     *  with `max(|dx|, |dy|, |dz|) == shell` AND
     *  `dx² + dy² + dz² ≤ INSTANT_BLOOM_RADIUS²`. Catalyst budget is
     *  shared with the cursor phase via [ActiveSpread.catalystsSpawned].
     *  Each placed tile emits a few sculk-charge-pop particles, and a
     *  single sculk-spread sound plays at the centre per shell so the
     *  bloom both looks and sounds like it's chewing outward.  */
    private fun placeShell(
        level: ServerLevel, spread: ActiveSpread, shell: Int, rng: RandomSource,
    ) {
        val r = INSTANT_BLOOM_RADIUS
        val rSq = r * r
        val scratch = BlockPos.MutableBlockPos()
        val center = spread.center
        var anyPlaced = false
        for (dy in -shell..shell) {
            for (dx in -shell..shell) {
                for (dz in -shell..shell) {
                    if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) != shell) continue
                    if (dx * dx + dy * dy + dz * dz > rSq) continue
                    scratch.set(center.x + dx, center.y + dy, center.z + dz)
                    val state = level.getBlockState(scratch)
                    if (!state.`is`(CATASTROPHE_REPLACEABLE)) continue
                    if (state.hasBlockEntity()) continue
                    if (!state.fluidState.isEmpty) continue
                    val placed = pickCatastropheBlock(rng, spread)
                    val pos = scratch.immutable()
                    level.setBlock(pos, placed, Block.UPDATE_ALL)
                    spread.converted += pos
                    // Couple of charge-pop puffs per placed tile —
                    // same particle vanilla catalyst spread uses, so
                    // the catastrophe reads as the same effect at
                    // higher amplitude.
                    level.sendParticles(
                        ParticleTypes.SCULK_CHARGE_POP,
                        pos.x + 0.5, pos.y + 0.5, pos.z + 0.5,
                        SHELL_PARTICLES_PER_BLOCK, 0.2, 0.2, 0.2, 0.0,
                    )
                    anyPlaced = true
                }
            }
        }
        if (anyPlaced) {
            level.playSound(
                null, center, SoundEvents.SCULK_BLOCK_SPREAD, SoundSource.BLOCKS,
                1.0f, 0.7f + rng.nextFloat() * 0.3f,
            )
        }
    }

    /** Phase-1 finaliser: drops floating sculk as falling entities,
     *  then seeds the phase-2 cursors at the bloom's outer frontier.
     *  Runs exactly once per active spread, when the last shell
     *  finishes placing. Vein draping is intentionally NOT done in
     *  the initial bloom — vanilla catalyst spread drapes veins as
     *  cursors walk, so phase 2 will produce them naturally on the
     *  expanding frontier and the initial sphere stays a clean
     *  uninterrupted surface. */
    private fun finalizeBloomAndSeedCursors(level: ServerLevel, spread: ActiveSpread) {
        val converted = spread.converted
        for (sculkPos in converted) {
            val state = level.getBlockState(sculkPos)
            if (!state.`is`(Blocks.SCULK)) continue
            val below = level.getBlockState(sculkPos.below())
            if (below.isAir) {
                FallingBlockEntity.fall(level, sculkPos, state)
            }
        }
        // Phase-2 cursor seeding.
        val seeds = pickEdgeSeeds(level, spread.center, converted, spread.cursorCount)
        if (seeds.isNotEmpty()) {
            val perCursor = spread.totalCharge / seeds.size
            for (seed in seeds) {
                val spreader = newSpreader()
                spreader.addCursors(seed, perCursor)
                spread.tracks += CursorTrack(spreader, seed)
            }
        }
    }

    /** Picks the catastrophe's replacement block for a single tile —
     *  the same kind of decision vanilla `SculkBlock.attemptUseCharge`
     *  makes when a cursor lands somewhere. Catalyst budget is shared
     *  across the whole active spread via [ActiveSpread.catalystsSpawned]. */
    private fun pickCatastropheBlock(rng: RandomSource, active: ActiveSpread): BlockState {
        if (active.catalystsSpawned < MAX_NEW_CATALYSTS &&
            rng.nextFloat() < CATALYST_CHANCE_PER_SCAN
        ) {
            active.catalystsSpawned++
            return Blocks.SCULK_CATALYST.defaultBlockState()
        }
        if (rng.nextFloat() < SHRIEKER_CHANCE_PER_BLOOM) {
            return Blocks.SCULK_SHRIEKER.defaultBlockState()
                .setValue(SculkShriekerBlock.CAN_SUMMON, true)
        }
        if (rng.nextFloat() < SENSOR_CHANCE_PER_BLOOM) {
            return Blocks.SCULK_SENSOR.defaultBlockState()
        }
        return Blocks.SCULK.defaultBlockState()
    }

    /** Find the frontier of the instant bloom: positions that ARE
     *  sculk (from phase 1) but still border at least one replaceable
     *  block. Picks [count] of them maximally spread apart so the
     *  cursors start at distinct compass points around the centre. */
    private fun pickEdgeSeeds(
        level: ServerLevel, center: BlockPos, converted: Set<BlockPos>, count: Int,
    ): List<BlockPos> {
        if (converted.isEmpty() || count <= 0) return emptyList()
        val edge = ArrayList<BlockPos>()
        for (pos in converted) {
            val state = level.getBlockState(pos)
            // Only frontier sculk — not catalysts / sensors / shriekers —
            // gets a cursor seed. Spreader cursors expect a sculk-bearing
            // block at their start.
            if (!state.`is`(Blocks.SCULK)) continue
            var hasReplaceableNeighbour = false
            for (dir in Direction.values()) {
                val n = pos.relative(dir)
                if (converted.contains(n)) continue
                if (level.getBlockState(n).`is`(CATASTROPHE_REPLACEABLE)) {
                    hasReplaceableNeighbour = true
                    break
                }
            }
            if (hasReplaceableNeighbour) edge += pos
        }
        if (edge.isEmpty()) return emptyList()
        // Greedy farthest-first: seed with the frontier point furthest
        // from the catastrophe centre, then each subsequent pick
        // maximises the minimum distance to the picks already made.
        // Bias toward the outer edge gives each cursor a strong
        // outbound direction without us having to manage a fallback.
        edge.sortByDescending { it.distSqr(center).toLong() }
        val selected = ArrayList<BlockPos>(count)
        selected += edge.removeAt(0)
        while (selected.size < count && edge.isNotEmpty()) {
            var bestIdx = 0
            var bestMinDist = Long.MIN_VALUE
            for (i in edge.indices) {
                val c = edge[i]
                var minDist = Long.MAX_VALUE
                for (s in selected) {
                    val d = c.distSqr(s).toLong()
                    if (d < minDist) minDist = d
                }
                if (minDist > bestMinDist) {
                    bestMinDist = minDist
                    bestIdx = i
                }
            }
            selected += edge.removeAt(bestIdx)
        }
        return selected
    }

    private fun tick(level: ServerLevel) {
        val key = level.dimension().location()
        val list = byDimension[key] ?: return
        if (list.isEmpty()) return
        val rng = level.random
        val done = ArrayList<ActiveSpread>()
        for (spread in list) {
            spread.ticksRemaining--
            // Phase 1: staged shell bloom. Cursors aren't seeded
            // until the last shell finishes, so the per-cursor loop
            // below is a no-op during this phase.
            if (spread.bloomShellIndex >= 0) {
                tickBloomPhase(level, spread, rng)
                if (spread.ticksRemaining <= 0) done += spread
                continue
            }
            // Phase 2: cursor walks.
            val finishedTracks = ArrayList<CursorTrack>()
            for (track in spread.tracks) {
                track.spreader.updateCursors(level, track.start, rng, true)
                postTickScan(level, spread, track.spreader, rng)
                if (track.spreader.cursors.isEmpty()) {
                    finishedTracks += track
                }
            }
            if (finishedTracks.isNotEmpty()) spread.tracks.removeAll(finishedTracks)
            // Mutual-distance convergence prune.
            pruneConverging(spread)
            val pruned = spread.tracks.filter { it.spreader.cursors.isEmpty() }
            if (pruned.isNotEmpty()) spread.tracks.removeAll(pruned)
            if (spread.tracks.isEmpty() || spread.ticksRemaining <= 0) {
                done += spread
            }
        }
        if (done.isNotEmpty()) list.removeAll(done)
    }

    private fun pruneConverging(spread: ActiveSpread) {
        val n = spread.tracks.size
        if (n < 2) return
        val minSepSq = (MIN_CURSOR_SEPARATION * MIN_CURSOR_SEPARATION).toLong()
        for (i in 0 until n - 1) {
            val a = spread.tracks[i]
            val aCursor = a.spreader.cursors.firstOrNull() ?: continue
            for (j in i + 1 until n) {
                val b = spread.tracks[j]
                val bCursor = b.spreader.cursors.firstOrNull() ?: continue
                if (aCursor.pos.distSqr(bCursor.pos) >= minSepSq) continue
                // Tied charges fall through to the second cursor losing,
                // arbitrarily; cleaner than a random tiebreak and good
                // enough for what's a rare collision in practice.
                if (aCursor.charge < bCursor.charge) {
                    a.spreader.cursors.clear()
                    break  // a is gone; next outer iteration
                } else {
                    b.spreader.cursors.clear()
                }
            }
        }
    }

    /** Per-cursor post-tick sweep. Walks ±[CURSOR_SCAN_RADIUS] around
     *  the live cursor and:
     *   1. Flips `can_summon=true` on any sculk shrieker the spreader
     *      placed — vanilla defaults shrieker `can_summon` to false
     *      outside ancient cities.
     *   2. Rolls [CATALYST_CHANCE_PER_SCAN] per sculk block to promote
     *      it to [Blocks.SCULK_CATALYST], capped at [MAX_NEW_CATALYSTS]
     *      per active spread (shared with the instant bloom budget).
     *   3. Spawns a [FallingBlockEntity] for any plain sculk whose
     *      below-neighbour is air. */
    private fun postTickScan(
        level: ServerLevel, spread: ActiveSpread, spreader: SculkSpreader, rng: RandomSource,
    ) {
        val r = CURSOR_SCAN_RADIUS
        val scratch = BlockPos.MutableBlockPos()
        for (cursor in spreader.cursors) {
            val cp = cursor.pos
            for (dx in -r..r) {
                for (dy in -r..r) {
                    for (dz in -r..r) {
                        scratch.set(cp.x + dx, cp.y + dy, cp.z + dz)
                        val state = level.getBlockState(scratch)
                        if (state.`is`(Blocks.SCULK_SHRIEKER)) {
                            if (!state.getValue(SculkShriekerBlock.CAN_SUMMON)) {
                                level.setBlock(
                                    scratch,
                                    state.setValue(SculkShriekerBlock.CAN_SUMMON, true),
                                    Block.UPDATE_ALL,
                                )
                            }
                            continue
                        }
                        if (!state.`is`(Blocks.SCULK)) continue
                        if (spread.catalystsSpawned < MAX_NEW_CATALYSTS &&
                            rng.nextFloat() < CATALYST_CHANCE_PER_SCAN
                        ) {
                            level.setBlock(
                                scratch,
                                Blocks.SCULK_CATALYST.defaultBlockState(),
                                Block.UPDATE_ALL,
                            )
                            spread.catalystsSpawned++
                            continue
                        }
                        val below = level.getBlockState(scratch.below())
                        if (below.isAir) {
                            FallingBlockEntity.fall(level, scratch.immutable(), state)
                        }
                    }
                }
            }
        }
    }

    /** Five minutes at 20 TPS — well past any plausible cursor decay
     *  arc; here so a wedged cursor doesn't pin the tick driver. */
    private const val DEFAULT_MAX_TICKS = 6000
    /** Half-edge of the 7×7×7 phase-1 sphere. Shells are placed by
     *  Chebyshev distance from the centre — 0 → 1 → 2 → 3 — filtered
     *  against this Euclidean radius so the corners stay rounded. */
    private const val INSTANT_BLOOM_RADIUS = 3
    /** Server-tick gap between successive shell placements during
     *  phase 1. One tick per shell ≈ 0.2 s total for the four shells
     *  — long enough to be visible / audible, short enough to feel
     *  like a single catastrophic event. */
    private const val TICKS_PER_SHELL = 1
    /** Charge-pop particles emitted per converted tile during phase 1. */
    private const val SHELL_PARTICLES_PER_BLOCK = 3
    /** Absolute cap on new catalysts seeded per spread. Catalysts
     *  cascade — each one triggers further sculk-spread off the next
     *  mob death — so three per scroll is generous but bounded. */
    private const val MAX_NEW_CATALYSTS = 3
    /** Per-block chance to roll a placed sculk into a catalyst,
     *  applied both during the instant bloom and during the cursor
     *  walk. Shared budget. */
    private const val CATALYST_CHANCE_PER_SCAN = 0.005f
    /** Per-block chance to place a summoning shrieker during the
     *  instant bloom. Roughly matches vanilla cursor behaviour. */
    private const val SHRIEKER_CHANCE_PER_BLOOM = 0.005f
    /** Per-block chance to place a sculk sensor during the instant
     *  bloom. */
    private const val SENSOR_CHANCE_PER_BLOOM = 0.02f
    /** Mutual-distance threshold for the convergence prune. Two
     *  cursors within this distance trigger the prune. */
    private const val MIN_CURSOR_SEPARATION = 3
    /** Half-edge of the per-cursor neighbourhood swept each tick by
     *  [postTickScan]. */
    private const val CURSOR_SCAN_RADIUS = 2
}
