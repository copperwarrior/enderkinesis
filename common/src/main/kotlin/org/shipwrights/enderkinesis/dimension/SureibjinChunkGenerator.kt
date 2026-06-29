package org.shipwrights.enderkinesis.dimension

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import net.minecraft.core.BlockPos
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.NoiseColumn
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.blending.Blender

/**
 * Chunk generator for [Sureibjin], the dream-coast ulder dimension.
 *
 * ## Column layout
 *
 *  The world is one infinite N/S beach. The coast line runs roughly down the X=0
 *  meridian with a low-frequency Z wobble so it doesn't read as a straight ruler.
 *  Looking east (+X) → ocean. Looking west (−X) → sand wall.
 *
 *   - **Beach band** (|x − wobble(z)| ≤ [BEACH_HALF_WIDTH]): sand at [BEACH_BASE_Y]
 *     with sub-block jitter — the walkable strip.
 *   - **East of beach**: surface plunges from [BEACH_BASE_Y] to [OCEAN_FLOOR_Y] over
 *     [DROPOFF_FULL_X] blocks via smoothstep — a long gentle drop into the deep.
 *     Water fills from the floor up to [SEA_LEVEL_Y].
 *   - **West of beach**: surface rises from [BEACH_BASE_Y] to [PILE_TOP_Y] over
 *     [PILE_FULL_X] blocks via smoothstep — sloping sand dune that caps just below
 *     the barrier ceiling.
 *   - **y = [BARRIER_Y]** (world top, one layer): barrier — hard cap, prevents
 *     anything from poking through the dream sky.
 *   - **y = [MIN_Y]**: bedrock floor.
 *
 * ## Obsidian tendril carvers
 *
 *  Dark obsidian fractals lift through the world — region-grid placement, one
 *  candidate root per [TENDRIL_REGION_SIZE]² cell, placed everywhere the local
 *  surface has enough headroom to grow. Each tendril is an SDF capsule fractal
 *  (vertical trunk + three forking primary branches) walked from the local
 *  surface up past [TRUNK_Y_CAP] — skyline-piercing, very prominent against
 *  the dream sky. Trunks are deterministic per region seed.
 *
 * ## Threading
 *
 *  All helpers are pure functions of `(wx, wy, wz)` plus the region seed — no
 *  per-chunk scratch on the generator, safe for C2ME concurrent chunk fill.
 *  See `sselith-chunkgen-threading` for the cautionary tale.
 */
class SureibjinChunkGenerator(
    biomeSource: BiomeSource,
) : ChunkGenerator(biomeSource) {

    override fun codec(): Codec<out ChunkGenerator> = CODEC

    override fun fillFromNoise(
        executor: Executor,
        blender: Blender,
        randomState: RandomState,
        structureManager: StructureManager,
        chunk: ChunkAccess,
    ): CompletableFuture<ChunkAccess> {
        val chunkX0 = chunk.pos.minBlockX
        val chunkZ0 = chunk.pos.minBlockZ

        chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG)
        chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG)

        val mutable = BlockPos.MutableBlockPos()

        // 1. Base columns: bedrock floor / sand body / water above ocean
        //    floor / air / barrier ceiling. Each column is independent.
        //
        //    Terrain (bedrock+sand+water) goes through chunk.setBlockState
        //    so MOTION_BLOCKING / WORLD_SURFACE heightmaps reflect the
        //    real walkable surface.
        //
        //    Barrier (Y >= BARRIER_START_Y, ~80 % of all base-column writes
        //    per chunk) is written directly to the LevelChunkSection,
        //    bypassing ChunkAccess.setBlockState's per-call
        //    Heightmap.update — which spark profiling pinned at ~3.6 s of
        //    chunkgen self-time before this split. Heightmaps ignoring the
        //    barrier is the desired behaviour anyway: the silhouette
        //    scanner and other surface walkers already skip barrier blocks.
        for (lx in 0..15) {
            val wx = chunkX0 + lx
            for (lz in 0..15) {
                val wz = chunkZ0 + lz
                val surface = coastSurfaceYAt(wx, wz)

                // Terrain pass — only up to where terrain actually exists.
                // Sea-level cap keeps us writing the water layer even when
                // the column's surface is below it.
                val terrainTop = if (surface >= SEA_LEVEL_Y) surface else SEA_LEVEL_Y
                for (y in MIN_Y..terrainTop) {
                    val block = baseColumnBlock(y, surface) ?: continue
                    chunk.setBlockState(mutable.set(wx, y, wz), block, false)
                }

                // Barrier pass — direct section write, no heightmap.
                // Starts at the higher of BARRIER_START_Y and surface+1
                // so western-pile sand (which can reach PILE_TOP_Y=253)
                // is never overwritten with barrier.
                val barrierFrom = if (surface + 1 > BARRIER_START_Y) surface + 1 else BARRIER_START_Y
                if (barrierFrom <= BARRIER_Y) {
                    var sectionIdx = chunk.getSectionIndex(barrierFrom)
                    var section = chunk.getSection(sectionIdx)
                    var sectionTopY = (sectionIdx shl 4) + MIN_Y + 15
                    for (y in barrierFrom..BARRIER_Y) {
                        if (y > sectionTopY) {
                            sectionIdx++
                            section = chunk.getSection(sectionIdx)
                            sectionTopY = (sectionIdx shl 4) + MIN_Y + 15
                        }
                        section.setBlockState(lx, y and 15, lz, BARRIER, false)
                    }
                }
            }
        }

        // 2. Obsidian tendril fractals. Each chunk scans its own region cell plus
        //    the 8 neighbours so a tendril root near a cell boundary still paints
        //    into adjacent chunks.
        paintTendrilsInto(chunk, chunkX0, chunkZ0, mutable)

        // 3. Small obsidian rock clusters scattered through the sand. Smaller
        //    region grid than the tendrils so they appear denser.
        paintRocksInto(chunk, chunkX0, chunkZ0, mutable)

        // 4. Sunken, tilted, cracked stone-brick towers. Sparse landmarks.
        paintTowersInto(chunk, chunkX0, chunkZ0, mutable)

        return CompletableFuture.completedFuture(chunk)
    }

    override fun applyCarvers(
        level: WorldGenRegion, seed: Long, randomState: RandomState,
        biomeManager: BiomeManager, structureManager: StructureManager,
        chunk: ChunkAccess, step: GenerationStep.Carving,
    ) {}

    override fun buildSurface(
        level: WorldGenRegion, structureManager: StructureManager,
        randomState: RandomState, chunk: ChunkAccess,
    ) {}

    override fun spawnOriginalMobs(level: WorldGenRegion) {}

    override fun getGenDepth(): Int = WORLD_HEIGHT
    override fun getSeaLevel(): Int = SEA_LEVEL_Y
    override fun getMinY(): Int = MIN_Y

    override fun getBaseHeight(
        x: Int, z: Int, type: Heightmap.Types, level: LevelHeightAccessor, randomState: RandomState,
    ): Int = coastSurfaceYAt(x, z) + 1

    override fun getBaseColumn(
        x: Int, z: Int, level: LevelHeightAccessor, randomState: RandomState,
    ): NoiseColumn {
        val surface = coastSurfaceYAt(x, z)
        val states: Array<BlockState> = Array(WORLD_HEIGHT) { i ->
            baseColumnBlock(MIN_Y + i, surface) ?: AIR
        }
        return NoiseColumn(MIN_Y, states)
    }

    override fun addDebugScreenInfo(
        info: MutableList<String>, randomState: RandomState, pos: BlockPos,
    ) {
        info.add("Sureibjin chunk generator")
        info.add("surface=" + coastSurfaceYAt(pos.x, pos.z))
    }

    @Suppress("unused")
    fun getBiomeSourceForCodec(): BiomeSource = this.biomeSource


    /**
     * Effective sand surface Y at world `(wx, wz)`. Composite of:
     *
     *   - [coastBaseSurfaceYAt] — the slope geometry.
     *   - [sandSurfaceNoise] — additive upward dune noise.
     *   - [nearbyTendrilMoundLiftAt] — sand piled around tendrils that pierce
     *     above sea level.
     *
     * Capped at [PILE_TOP_Y] so nothing pokes through the barrier ceiling.
     */
    private fun coastSurfaceYAt(wx: Int, wz: Int): Int {
        val base = coastBaseSurfaceYAt(wx, wz)
        val noise = sandSurfaceNoise(wx, wz)
        // Tendril, rock, and tower mounds all contribute. Use max so adjacent
        // contributions don't stack into a freakishly tall pile.
        val mound = Math.max(
            nearbyTendrilMoundLiftAt(wx, wz),
            Math.max(
                nearbyRockMoundLiftAt(wx, wz),
                nearbyTowerMoundLiftAt(wx, wz),
            ),
        )
        return (base + noise + mound).coerceAtMost(PILE_TOP_Y)
    }

    /**
     * Slope-only surface Y at world `(wx, wz)`, no noise, no tendril mounds.
     *
     * The coast is a wobble line `xCoast(z)` running N/S. Distance east of that
     * line `dx = wx − xCoast(z)`:
     *
     *  - `dx ≤ −BEACH_HALF_WIDTH` → west: smoothstep from beach Y up to the
     *    barrier-adjacent pile cap.
     *  - `|dx| < BEACH_HALF_WIDTH` → on the beach: base Y plus small per-column
     *    sub-block jitter.
     *  - `dx ≥ +BEACH_HALF_WIDTH` → east: smoothstep from beach Y down to ocean
     *    floor.
     *
     * Used by the tendril placer to find a stable anchor — tendril positions
     * must NOT depend on the tendril-mound output, or we'd have a circular
     * definition.
     */
    private fun coastBaseSurfaceYAt(wx: Int, wz: Int): Int {
        val coastX = coastCenterX(wz)
        val dx = wx - coastX
        return when {
            dx <= -BEACH_HALF_WIDTH -> {
                val t = ((-dx - BEACH_HALF_WIDTH).toDouble() / PILE_FULL_X)
                    .coerceIn(0.0, 1.0)
                val s = smoothstep(t)
                val rise = (PILE_TOP_Y - BEACH_BASE_Y) * s
                (BEACH_BASE_Y + rise).toInt()
            }
            dx >= BEACH_HALF_WIDTH -> {
                val t = ((dx - BEACH_HALF_WIDTH).toDouble() / DROPOFF_FULL_X)
                    .coerceIn(0.0, 1.0)
                val s = smoothstep(t)
                val drop = (BEACH_BASE_Y - OCEAN_FLOOR_Y) * s
                (BEACH_BASE_Y - drop).toInt()
            }
            else -> {
                val jitter = (valueNoise2(wx * 0.21, wz * 0.21) - 0.5) * 2.0
                (BEACH_BASE_Y + jitter).toInt()
            }
        }
    }

    /** Additive upward sand noise. Three octaves clamped to ≥ 0:
     *
     *   - **large** (period ~40, amp 12) — slow dune undulation.
     *   - **mid**   (period ~13, amp 4)  — wind-ripple-scale variation that
     *     breaks up the otherwise monotonous slope surfaces.
     *   - **small** (period ~6,  amp 1.5) — fine grain on top of everything.
     *
     *  Each octave is offset in noise-space so the rolls don't all peak at
     *  the same coordinates. */
    private fun sandSurfaceNoise(wx: Int, wz: Int): Int {
        val large = (valueNoise2(wx * 0.025 + 31.7, wz * 0.025 + 19.1) - 0.3)
            .coerceAtLeast(0.0) * 12.0
        val mid = (valueNoise2(wx * 0.075 + 11.4, wz * 0.075 + 47.2) - 0.4)
            .coerceAtLeast(0.0) * 4.0
        val small = (valueNoise2(wx * 0.180 + 5.1, wz * 0.180 + 23.7) - 0.5)
            .coerceAtLeast(0.0) * 1.5
        return (large + mid + small).toInt()
    }

    /** Local downhill direction at world `(x, z)`, in XZ. Returns a unit
     *  vector or (0, 0) if the local surface is flat enough that there's
     *  no meaningful downhill (the beach interior). Used by the mound
     *  helpers to bias sand displacement asymmetrically — more
     *  accumulation on the downhill side, as if the wind blew it there.  */
    private fun downhillDirAt(x: Int, z: Int): DoubleArray {
        val sampleDist = 8
        val baseE = coastBaseSurfaceYAt(x + sampleDist, z)
        val baseW = coastBaseSurfaceYAt(x - sampleDist, z)
        val baseN = coastBaseSurfaceYAt(x, z - sampleDist)
        val baseS = coastBaseSurfaceYAt(x, z + sampleDist)
        val gradX = (baseE - baseW).toDouble() / (2.0 * sampleDist)
        val gradZ = (baseS - baseN).toDouble() / (2.0 * sampleDist)
        val mag = Math.sqrt(gradX * gradX + gradZ * gradZ)
        return if (mag > 0.05) {
            doubleArrayOf(-gradX / mag, -gradZ / mag)
        } else {
            doubleArrayOf(0.0, 0.0)
        }
    }

    /** Ken Perlin's smootherstep — C² continuous (zero slope AND zero
     *  curvature at endpoints). Gives cleaner mound transitions than the
     *  cubic [smoothstep], which is only C¹ continuous. */
    private fun smootherstep(t: Double): Double {
        val x = if (t < 0.0) 0.0 else if (t > 1.0) 1.0 else t
        return x * x * x * (x * (x * 6.0 - 15.0) + 10.0)
    }

    /** Shared mound-lift computation used by every sand-displacement source
     *  (tendrils, rocks, towers).
     *
     *  Falloff is a small plateau + **smootherstep** (C² continuous so the
     *  slope eases in and out without a visible inflection at the boundary).
     *  A per-cell smooth-noise dither is added before integer truncation so
     *  the discrete `lift=0` → `lift=1` boundary feathers into a stippled
     *  edge instead of a hard circle — that's what was reading as "the
     *  mound has an obvious edge" no matter how smooth the underlying curve.
     *
     *  Directional bias scales the effective radius along the source's
     *  downhill direction: `(1 + DOWNHILL_BIAS)` downhill, `(1 − BIAS)`
     *  uphill — sand "blown" into place. */
    private fun directionalMoundLift(
        queryX: Int, queryZ: Int,
        sourceX: Int, sourceZ: Int,
        baseRadius: Double,
        peakHeight: Double,
        plateau: Double,
    ): Int {
        val ddx = (queryX - sourceX).toDouble()
        val ddz = (queryZ - sourceZ).toDouble()
        val dist = Math.sqrt(ddx * ddx + ddz * ddz)

        val dh = downhillDirAt(sourceX, sourceZ)
        val align = if (dist > 1e-6 && (dh[0] != 0.0 || dh[1] != 0.0))
            (ddx * dh[0] + ddz * dh[1]) / dist
        else 0.0

        val effRadius = baseRadius * (1.0 + MOUND_DOWNHILL_BIAS * align)
        if (effRadius <= 0.0 || dist >= effRadius) return 0

        val t = dist / effRadius
        val falloff = if (t < plateau) 1.0
            else {
                val u = (t - plateau) / (1.0 - plateau)
                1.0 - smootherstep(u)
            }

        // Sub-block noise dither: shifts the integer boundary by per-cell
        // amount in [0, 1]. The smooth noise (period ~1.4 blocks) ensures
        // the dither varies between adjacent cells but isn't pure
        // salt-and-pepper.
        val rawLift = peakHeight * falloff
        val dither = valueNoise2(queryX * 0.7 + 137.0, queryZ * 0.7 + 211.0)
        return (rawLift + dither).toInt()
    }

    /** Wobbling coast meridian X as a function of Z — two-octave value noise so
     *  the coast meanders rather than reading as a perfect straight line. */
    private fun coastCenterX(wz: Int): Double {
        val nLow = (valueNoise2(0.0, wz * COAST_WOBBLE_FREQ) - 0.5) * 2.0 *
            COAST_WOBBLE_AMP
        val nHigh = (valueNoise2(7.31, wz * COAST_FINE_FREQ) - 0.5) * 2.0 *
            COAST_FINE_AMP
        return nLow + nHigh
    }

    /** Base column block at world Y `y` given the local surface height.
     *  Returns null for cells that should remain unset (saves a setBlockState
     *  call when air is already the default).
     *
     *  Terrain (bedrock / sand / water) wins over barrier — order matters.
     *  Barrier fills any remaining air at or above [BARRIER_START_Y] up to
     *  the world top, so nothing can leave the dream from above and the sky
     *  is sealed from the player's reach. */
    private fun baseColumnBlock(y: Int, surface: Int): BlockState? = when {
        y > MAX_Y_INCL -> null
        y == MIN_Y -> BEDROCK
        y <= surface -> SAND
        y <= SEA_LEVEL_Y -> WATER
        y >= BARRIER_START_Y -> BARRIER
        else -> null
    }

    private fun smoothstep(t: Double): Double = t * t * (3.0 - 2.0 * t)


    /** A single capsule of a tendril: tapered cylinder from `(x0,y0,z0)` →
     *  `(x1,y1,z1)`, radius `r0` at start tapering to `r1` at end. */
    private data class TendrilSegment(
        val x0: Double, val y0: Double, val z0: Double,
        val x1: Double, val y1: Double, val z1: Double,
        val r0: Double, val r1: Double,
    )

    /** Shared metadata for a tendril candidate in a region cell — both the
     *  tendril painter (segment build) and the sand-mound calculator
     *  (surface lift) derive from the same numbers, so we compute once.
     *
     *  Returns null when the region rolled empty (~30 %). */
    private data class TendrilCandidate(
        val rootX: Int, val rootZ: Int, val seed: Int, val thickness: Double,
    )

    private fun tendrilCandidateInRegion(rgX: Int, rgZ: Int): TendrilCandidate? {
        val rootSeed = hash32(rgX, rgZ, 0x7E_4D_69_75.toInt())
        if ((rootSeed and 0xF) > 0xA) return null   // ~70 % spawn rate per region
        val withinX = (hash32(rgX, rgZ, 0x33A1) ushr 1) and (TENDRIL_REGION_SIZE - 1)
        val withinZ = (hash32(rgX, rgZ, 0x771B) ushr 1) and (TENDRIL_REGION_SIZE - 1)
        val rootX = rgX * TENDRIL_REGION_SIZE + withinX
        val rootZ = rgZ * TENDRIL_REGION_SIZE + withinZ
        val thickness = TENDRIL_THICKNESS_MIN +
            hash01(rootSeed, 0, 0x9C) *
            (TENDRIL_THICKNESS_MAX - TENDRIL_THICKNESS_MIN)
        return TendrilCandidate(rootX, rootZ, rootSeed, thickness)
    }

    private fun tendrilMoundPeak(thickness: Double): Int =
        (TRUNK_BASE_RADIUS * thickness * MOUND_HEIGHT_MULT).toInt()

    private fun tendrilMoundRadius(thickness: Double): Double =
        TRUNK_BASE_RADIUS * thickness * MOUND_RADIUS_MULT

    /** Maximum sand lift contributed by any nearby tendril at world `(wx, wz)`.
     *
     *  Distance is measured to the **trunk's XZ breach polyline** (its first
     *  few centerline points projected onto the XZ plane), not to a fixed
     *  root point. So when the trunk leans east as it emerges, the displaced
     *  sand bulges east; when it goes straight up, the mound is circular.
     *  Shape follows direction.
     *
     *  Profile is flat-top + sharp linear drop ([MOUND_INNER_PLATEAU] of the
     *  radius is fully raised, then cliffs back to ground) so the sand reads
     *  as *displaced* by the tendril, not as a hill it sits on.
     *
     *  Max (not sum) so two tendrils close together don't stack into a
     *  freakishly tall pile. */
    private fun nearbyTendrilMoundLiftAt(wx: Int, wz: Int): Int {
        val rgX = Math.floorDiv(wx, TENDRIL_REGION_SIZE)
        val rgZ = Math.floorDiv(wz, TENDRIL_REGION_SIZE)
        var maxLift = 0
        for (dRgX in -1..1) for (dRgZ in -1..1) {
            val cand = tendrilCandidateInRegion(rgX + dRgX, rgZ + dRgZ) ?: continue
            // Mound only where the tendril visibly pierces sand — skip if the
            // root sits underwater (sand can't pile in the deep) or if the
            // tendril wasn't placed (root too close to the ceiling).
            val rootBase = coastBaseSurfaceYAt(cand.rootX, cand.rootZ)
            if (rootBase < SEA_LEVEL_Y - 1) continue
            if (rootBase >= TRUNK_Y_CAP - 4) continue

            val lift = directionalMoundLift(
                wx, wz, cand.rootX, cand.rootZ,
                tendrilMoundRadius(cand.thickness),
                tendrilMoundPeak(cand.thickness).toDouble(),
                MOUND_INNER_PLATEAU,
            )
            if (lift > maxLift) maxLift = lift
        }
        return maxLift
    }


    /** Paint every tendril fractal whose root sits in the 5×5 region
     *  neighbourhood of this chunk. Each tendril is rebuilt per chunk —
     *  deterministic from region seed, so cross-chunk paints land on identical
     *  voxels without any shared state. */
    private fun paintTendrilsInto(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos,
    ) {
        val rgX = Math.floorDiv(chunkX0, TENDRIL_REGION_SIZE)
        val rgZ = Math.floorDiv(chunkZ0, TENDRIL_REGION_SIZE)

        // 5×5 scan — tendrils reach further than a single region radius.
        for (dRgX in -2..2) for (dRgZ in -2..2) {
            val cand = tendrilCandidateInRegion(rgX + dRgX, rgZ + dRgZ) ?: continue

            val rootBase = coastBaseSurfaceYAt(cand.rootX, cand.rootZ)
            val noiseAtRoot = sandSurfaceNoise(cand.rootX, cand.rootZ)
            // Tendrils piercing above sea level get a sand mound piled around
            // them and start on top of that mound. Underwater tendrils rise
            // straight from the ocean floor — no visible mound.
            val moundPeak = if (rootBase >= SEA_LEVEL_Y - 1)
                tendrilMoundPeak(cand.thickness) else 0
            val startY = rootBase + noiseAtRoot + moundPeak + 1
            if (startY >= TRUNK_Y_CAP - 4) continue

            // Quick chunk-AABB reject vs the tendril's worst-case XZ reach.
            val nearestX = cand.rootX.coerceIn(chunkX0, chunkX0 + 15)
            val nearestZ = cand.rootZ.coerceIn(chunkZ0, chunkZ0 + 15)
            val ddx = nearestX - cand.rootX
            val ddz = nearestZ - cand.rootZ
            if (ddx * ddx + ddz * ddz > TENDRIL_MAX_REACH * TENDRIL_MAX_REACH) continue

            val segments = buildTendrilFractal(
                cand.rootX, startY.toDouble(), cand.rootZ, cand.seed, cand.thickness,
            )
            for (seg in segments) {
                paintCapsuleIntoChunk(chunk, chunkX0, chunkZ0, mutable, seg)
            }
        }
    }


    /** Silhouette palette. [paintRocksInto] dispatches to the matching
     *  painter for each candidate's [RockCandidate.shape]. */
    private enum class RockShape { CLUSTER, SLAB, SHARD, PILLAR, DOME }

    /** Metadata for one rock candidate in a region cell. [sizeBase] is the
     *  rock's "scale" parameter — drives all radii, mound height/radius,
     *  burial depth, and AABB reach. [isBoulder] scales sizeBase up by
     *  [ROCK_BOULDER_SCALE] for landmark-sized rocks. */
    private data class RockCandidate(
        val rootX: Int, val rootZ: Int, val seed: Int,
        val sizeBase: Double,
        val shape: RockShape,
        val isBoulder: Boolean,
        val burialDepth: Int,
    )

    /** Per-region rock candidate. Smaller cells than tendrils and a higher
     *  spawn rate so the sand is visibly studded with rocks rather than
     *  dotted. Each candidate rolls a [RockShape] and a chance of being a
     *  landmark boulder. */
    private fun rockCandidateInRegion(rgX: Int, rgZ: Int): RockCandidate? {
        val rootSeed = hash32(rgX, rgZ, 0x52_4F_43_4B.toInt())   // 'ROCK'
        if ((rootSeed and 0xF) > 0x8) return null                // ~56 % spawn
        val withinX = (hash32(rgX, rgZ, 0x55B1) ushr 1) and (ROCK_REGION_SIZE - 1)
        val withinZ = (hash32(rgX, rgZ, 0x77D1) ushr 1) and (ROCK_REGION_SIZE - 1)
        val rootX = rgX * ROCK_REGION_SIZE + withinX
        val rootZ = rgZ * ROCK_REGION_SIZE + withinZ
        val baseSize = ROCK_BASE_RADIUS * (ROCK_THICKNESS_MIN +
            hash01(rootSeed, 0, 0x5A) *
            (ROCK_THICKNESS_MAX - ROCK_THICKNESS_MIN))
        val isBoulder = ((rootSeed ushr 28) and ROCK_BOULDER_CHANCE_MASK) == 0
        val sizeBase = if (isBoulder) baseSize * ROCK_BOULDER_SCALE else baseSize
        // A landmark-scale slab is just a wide flat plate — reads as a
        // dropped tile, not a landmark — so the SLAB slot redirects to a
        // SHARD spire when this rock is a boulder.
        val shape = when ((rootSeed ushr 24) and 0x7) {
            0, 1 -> RockShape.CLUSTER
            2 -> if (isBoulder) RockShape.SHARD else RockShape.SLAB
            3, 4 -> RockShape.SHARD
            5, 6 -> RockShape.PILLAR
            else -> RockShape.DOME
        }
        val burialFraction = ROCK_BURIAL_MIN +
            hash01(rootSeed, 0, 0x6B) * ROCK_BURIAL_RAND
        val burialDepth = (sizeBase * burialFraction).toInt()
        return RockCandidate(rootX, rootZ, rootSeed, sizeBase, shape, isBoulder, burialDepth)
    }

    private fun rockMoundPeak(sizeBase: Double): Int =
        (sizeBase * ROCK_MOUND_HEIGHT_MULT).toInt()

    private fun rockMoundRadius(sizeBase: Double): Double =
        sizeBase * ROCK_MOUND_RADIUS_MULT

    /** Maximum sand lift contributed by any nearby rock at world `(wx, wz)`.
     *  Smooth-falloff plateau with downhill bias — see
     *  [directionalMoundLift] for the shared shape. */
    private fun nearbyRockMoundLiftAt(wx: Int, wz: Int): Int {
        val rgX = Math.floorDiv(wx, ROCK_REGION_SIZE)
        val rgZ = Math.floorDiv(wz, ROCK_REGION_SIZE)
        var maxLift = 0
        for (dRgX in -1..1) for (dRgZ in -1..1) {
            val cand = rockCandidateInRegion(rgX + dRgX, rgZ + dRgZ) ?: continue
            val rootBase = coastBaseSurfaceYAt(cand.rootX, cand.rootZ)
            if (rootBase < SEA_LEVEL_Y - 1) continue
            if (rootBase >= TRUNK_Y_CAP - 4) continue

            val lift = directionalMoundLift(
                wx, wz, cand.rootX, cand.rootZ,
                rockMoundRadius(cand.sizeBase),
                rockMoundPeak(cand.sizeBase).toDouble(),
                ROCK_MOUND_INNER_PLATEAU,
            )
            if (lift > maxLift) maxLift = lift
        }
        return maxLift
    }

    /** Paint every rock whose root sits in the 3×3 region neighbourhood of
     *  this chunk. Dispatches by shape and burial: the rock sits below the
     *  local mound surface by [RockCandidate.burialDepth], and a debris
     *  field of single-block chips is scattered around its base. */
    private fun paintRocksInto(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos,
    ) {
        val rgX = Math.floorDiv(chunkX0, ROCK_REGION_SIZE)
        val rgZ = Math.floorDiv(chunkZ0, ROCK_REGION_SIZE)
        for (dRgX in -1..1) for (dRgZ in -1..1) {
            val cand = rockCandidateInRegion(rgX + dRgX, rgZ + dRgZ) ?: continue
            val rootBase = coastBaseSurfaceYAt(cand.rootX, cand.rootZ)
            // Sand-area only — skip underwater and the very top of the dune.
            if (rootBase < SEA_LEVEL_Y - 1) continue
            if (rootBase >= TRUNK_Y_CAP - 4) continue

            val noiseAtRoot = sandSurfaceNoise(cand.rootX, cand.rootZ)
            val moundPeak = rockMoundPeak(cand.sizeBase)
            val rootSurfaceY = rootBase + noiseAtRoot + moundPeak
            // Burial sinks the rock into the sand mound; the painted volume
            // overwrites sand below the surface so the visible portion is
            // whatever protrudes above the local mound height.
            val baseY = rootSurfaceY - cand.burialDepth

            // Quick chunk-AABB reject — generous bound covers slabs (×2.2
            // XZ reach) and tall boulder shards (×4.5 height).
            val maxReach = (cand.sizeBase * 5.0).toInt()
            val nearestX = cand.rootX.coerceIn(chunkX0, chunkX0 + 15)
            val nearestZ = cand.rootZ.coerceIn(chunkZ0, chunkZ0 + 15)
            val ddx = nearestX - cand.rootX
            val ddz = nearestZ - cand.rootZ
            if (ddx * ddx + ddz * ddz > maxReach * maxReach) continue

            when (cand.shape) {
                RockShape.CLUSTER -> paintRockCluster(chunk, chunkX0, chunkZ0, mutable, cand, baseY)
                RockShape.SLAB -> paintRockSlab(chunk, chunkX0, chunkZ0, mutable, cand, baseY)
                RockShape.SHARD -> paintRockShard(chunk, chunkX0, chunkZ0, mutable, cand, baseY)
                RockShape.PILLAR -> paintRockPillar(chunk, chunkX0, chunkZ0, mutable, cand, baseY)
                RockShape.DOME -> paintRockDome(chunk, chunkX0, chunkZ0, mutable, cand, baseY)
            }
            paintRockDebris(chunk, chunkX0, chunkZ0, mutable, cand)
        }
    }

    /** 2-5 small spheres at slight offsets around the rock root — reads as a
     *  cluster of stones rather than a single boulder. Each sphere has its
     *  own radius drawn from the hash, biased toward the cluster's
     *  `sizeBase`. */
    private fun paintRockCluster(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos,
        cand: RockCandidate, baseY: Int,
    ) {
        val sphereCount = 2 + ((cand.seed ushr 13) and 0x3)   // 2..5
        for (i in 0 until sphereCount) {
            val dx = (hash01(cand.seed, i, 0x41) - 0.5) * 2.0 * cand.sizeBase
            val dz = (hash01(cand.seed, i, 0x42) - 0.5) * 2.0 * cand.sizeBase
            val dy = hash01(cand.seed, i, 0x43) * cand.sizeBase * 0.7
            val r = cand.sizeBase * (0.55 + hash01(cand.seed, i, 0x44) * 0.45)
            paintRockSphere(
                chunk, chunkX0, chunkZ0, mutable,
                cand.rootX + dx, baseY + dy, cand.rootZ + dz, r,
            )
        }
    }

    /** Rasterize a single sphere into the chunk as obsidian / crying obsidian
     *  using the same per-voxel crying-obsidian intersperse as the tendril
     *  painter. */
    private fun paintRockSphere(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos,
        cx: Double, cy: Double, cz: Double, radius: Double,
    ) {
        val rPad = radius + 0.5
        val cMinX = Math.max(Math.floor(cx - rPad).toInt(), chunkX0)
        val cMaxX = Math.min(Math.ceil(cx + rPad).toInt(), chunkX0 + 15)
        val cMinZ = Math.max(Math.floor(cz - rPad).toInt(), chunkZ0)
        val cMaxZ = Math.min(Math.ceil(cz + rPad).toInt(), chunkZ0 + 15)
        val cMinY = Math.max(Math.floor(cy - rPad).toInt(), MIN_Y)
        val cMaxY = Math.min(Math.ceil(cy + rPad).toInt(), BARRIER_Y - 1)
        if (cMinX > cMaxX || cMinY > cMaxY || cMinZ > cMaxZ) return

        val rSq = radius * radius
        for (wy in cMinY..cMaxY) {
            val vy = wy + 0.5
            val dy = vy - cy
            val dySq = dy * dy
            for (wz in cMinZ..cMaxZ) {
                val vz = wz + 0.5
                val dz = vz - cz
                val dzSq = dz * dz
                if (dySq + dzSq > rSq) continue
                for (wx in cMinX..cMaxX) {
                    val vx = wx + 0.5
                    val dx = vx - cx
                    val distSq = dx * dx + dySq + dzSq
                    if (distSq > rSq) continue
                    mutable.set(wx, wy, wz)
                    val existing = chunk.getBlockState(mutable)
                    if (existing.`is`(Blocks.BEDROCK)) continue
                    val block = if (isCryingPatchAt(wx, wy, wz)) CRYING_OBSIDIAN else OBSIDIAN
                    chunk.setBlockState(mutable, block, false)
                }
            }
        }
    }

    /** Flat horizontal disc — wide in XZ, short in Y. X and Z radii roll
     *  independently so slabs can be round tables, elongated loaves, or
     *  uneven plates. */
    private fun paintRockSlab(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos,
        cand: RockCandidate, baseY: Int,
    ) {
        val rX = cand.sizeBase * (1.3 + hash01(cand.seed, 0, 0x57) * 0.9)   // 1.3..2.2
        val rZ = cand.sizeBase * (1.3 + hash01(cand.seed, 0, 0x58) * 0.9)   // 1.3..2.2
        val rY = cand.sizeBase * (0.28 + hash01(cand.seed, 0, 0x59) * 0.27) // 0.28..0.55
        paintRockEllipsoid(
            chunk, chunkX0, chunkZ0, mutable,
            cand.rootX.toDouble(), baseY + rY, cand.rootZ.toDouble(),
            rX, rY, rZ,
        )
    }

    /** Sharp upward cone — tall narrow tip, often leaning. Proportions and
     *  tilt direction roll per rock so a shard field reads as varied
     *  spires rather than a cookie-cut row. */
    private fun paintRockShard(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos,
        cand: RockCandidate, baseY: Int,
    ) {
        val baseR = cand.sizeBase * (0.60 + hash01(cand.seed, 0, 0x61) * 0.55) // 0.60..1.15
        val height = cand.sizeBase * (1.9 + hash01(cand.seed, 0, 0x62) * 2.6)  // 1.9..4.5
        // Tilt amplitude as XZ offset per unit height: ±0.30 ⇒ apex up to
        // ~1.3 sizeBase off-vertical at max height.
        val tiltX = (hash01(cand.seed, 0, 0x63) - 0.5) * 0.6
        val tiltZ = (hash01(cand.seed, 0, 0x64) - 0.5) * 0.6
        paintRockTiltedCone(
            chunk, chunkX0, chunkZ0, mutable,
            cand.rootX.toDouble(), baseY.toDouble(), cand.rootZ.toDouble(),
            baseR, height, tiltX, tiltZ,
        )
    }

    /** Columnar pillar — slight taper from base to top, gentle lean. Less
     *  tilt than shards so pillars still read as upright. */
    private fun paintRockPillar(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos,
        cand: RockCandidate, baseY: Int,
    ) {
        val baseR = cand.sizeBase * (0.55 + hash01(cand.seed, 0, 0x65) * 0.40) // 0.55..0.95
        val topR = cand.sizeBase * (0.30 + hash01(cand.seed, 0, 0x66) * 0.35)  // 0.30..0.65
        val height = cand.sizeBase * (1.7 + hash01(cand.seed, 0, 0x67) * 1.8)  // 1.7..3.5
        val tiltX = (hash01(cand.seed, 0, 0x68) - 0.5) * 0.35
        val tiltZ = (hash01(cand.seed, 0, 0x69) - 0.5) * 0.35
        paintRockTiltedTaperedCylinder(
            chunk, chunkX0, chunkZ0, mutable,
            cand.rootX.toDouble(), baseY.toDouble(), cand.rootZ.toDouble(),
            baseR, topR, height, tiltX, tiltZ,
        )
    }

    /** Lumpy weathered boulder — 1–3 overlapping squashed ellipsoids at
     *  small XZ offsets. The non-uniform XZ radii and the per-lump offsets
     *  break the perfect-hemisphere silhouette and read as a wind-worn
     *  mass rather than a half-ball. */
    private fun paintRockDome(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos,
        cand: RockCandidate, baseY: Int,
    ) {
        val lumpCount = 1 + ((cand.seed ushr 17) and 0x3)   // 1..4
        for (i in 0 until lumpCount) {
            val ox = (hash01(cand.seed, i, 0x51) - 0.5) * cand.sizeBase * 0.9
            val oz = (hash01(cand.seed, i, 0x52) - 0.5) * cand.sizeBase * 0.9
            val rX = cand.sizeBase * (0.85 + hash01(cand.seed, i, 0x53) * 0.70)
            val rZ = cand.sizeBase * (0.85 + hash01(cand.seed, i, 0x54) * 0.70)
            val rY = cand.sizeBase * (0.45 + hash01(cand.seed, i, 0x55) * 0.45)
            paintRockUpperHalfEllipsoid(
                chunk, chunkX0, chunkZ0, mutable,
                cand.rootX + ox, baseY.toDouble(), cand.rootZ + oz,
                rX, rY, rZ,
            )
        }
    }

    /** Scatter of single-block obsidian chips radiating outward from the
     *  rock's base. Each chip resolves its own column's local sand surface
     *  via [coastSurfaceYAt] so boulder debris — which scatters past the
     *  rock's mound — still lands ON sand instead of floating. */
    private fun paintRockDebris(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos,
        cand: RockCandidate,
    ) {
        val count = ROCK_DEBRIS_COUNT_MIN +
            ((cand.seed ushr 19) and 0xF) % ROCK_DEBRIS_COUNT_RAND
        for (i in 0 until count) {
            val angle = hash01(cand.seed, i, 0x71) * Math.PI * 2.0
            val distFactor = ROCK_DEBRIS_DIST_MIN +
                hash01(cand.seed, i, 0x72) * ROCK_DEBRIS_DIST_RAND
            val dist = cand.sizeBase * distFactor
            val wx = (cand.rootX + Math.cos(angle) * dist).toInt()
            val wz = (cand.rootZ + Math.sin(angle) * dist).toInt()
            if (wx < chunkX0 || wx > chunkX0 + 15) continue
            if (wz < chunkZ0 || wz > chunkZ0 + 15) continue
            // Local sand top — base + per-column noise + every mound
            // contribution. Replaces the topmost sand block with a chip
            // so the chip sits flush, never floating.
            val wy = coastSurfaceYAt(wx, wz)
            if (wy <= SEA_LEVEL_Y - 1) continue
            if (wy <= MIN_Y || wy >= BARRIER_Y - 1) continue
            mutable.set(wx, wy, wz)
            val existing = chunk.getBlockState(mutable)
            if (existing.`is`(Blocks.BEDROCK)) continue
            val block = if (isCryingPatchAt(wx, wy, wz)) CRYING_OBSIDIAN else OBSIDIAN
            chunk.setBlockState(mutable, block, false)
        }
    }

    /** Ellipsoid rasterizer — axis-aligned, used by the slab painter. */
    private fun paintRockEllipsoid(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos,
        cx: Double, cy: Double, cz: Double,
        rX: Double, rY: Double, rZ: Double,
    ) {
        val cMinX = Math.max(Math.floor(cx - rX - 0.5).toInt(), chunkX0)
        val cMaxX = Math.min(Math.ceil(cx + rX + 0.5).toInt(), chunkX0 + 15)
        val cMinZ = Math.max(Math.floor(cz - rZ - 0.5).toInt(), chunkZ0)
        val cMaxZ = Math.min(Math.ceil(cz + rZ + 0.5).toInt(), chunkZ0 + 15)
        val cMinY = Math.max(Math.floor(cy - rY - 0.5).toInt(), MIN_Y)
        val cMaxY = Math.min(Math.ceil(cy + rY + 0.5).toInt(), BARRIER_Y - 1)
        if (cMinX > cMaxX || cMinY > cMaxY || cMinZ > cMaxZ) return

        for (wy in cMinY..cMaxY) {
            val dyN = (wy + 0.5 - cy) / rY
            val dyNSq = dyN * dyN
            if (dyNSq > 1.0) continue
            for (wz in cMinZ..cMaxZ) {
                val dzN = (wz + 0.5 - cz) / rZ
                val dzNSq = dzN * dzN
                if (dyNSq + dzNSq > 1.0) continue
                for (wx in cMinX..cMaxX) {
                    val dxN = (wx + 0.5 - cx) / rX
                    if (dxN * dxN + dyNSq + dzNSq > 1.0) continue
                    mutable.set(wx, wy, wz)
                    val existing = chunk.getBlockState(mutable)
                    if (existing.`is`(Blocks.BEDROCK)) continue
                    val block = if (isCryingPatchAt(wx, wy, wz)) CRYING_OBSIDIAN else OBSIDIAN
                    chunk.setBlockState(mutable, block, false)
                }
            }
        }
    }

    /** Cone rasterizer with per-Y XZ tilt. [tiltX]/[tiltZ] are offsets per
     *  unit height (`apexOffset = tilt × height`), so the apex leans off
     *  vertical while the base stays put. */
    private fun paintRockTiltedCone(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos,
        cx: Double, baseY: Double, cz: Double,
        baseR: Double, height: Double,
        tiltX: Double, tiltZ: Double,
    ) {
        val apexDX = tiltX * height
        val apexDZ = tiltZ * height
        val pad = baseR + 0.5
        val cMinX = Math.max(Math.floor(cx + Math.min(0.0, apexDX) - pad).toInt(), chunkX0)
        val cMaxX = Math.min(Math.ceil(cx + Math.max(0.0, apexDX) + pad).toInt(), chunkX0 + 15)
        val cMinZ = Math.max(Math.floor(cz + Math.min(0.0, apexDZ) - pad).toInt(), chunkZ0)
        val cMaxZ = Math.min(Math.ceil(cz + Math.max(0.0, apexDZ) + pad).toInt(), chunkZ0 + 15)
        val cMinY = Math.max(baseY.toInt(), MIN_Y)
        val cMaxY = Math.min(Math.ceil(baseY + height).toInt(), BARRIER_Y - 1)
        if (cMinX > cMaxX || cMinY > cMaxY || cMinZ > cMaxZ) return

        for (wy in cMinY..cMaxY) {
            val t = (wy + 0.5 - baseY) / height
            if (t < 0.0 || t > 1.0) continue
            val r = baseR * (1.0 - t)
            val rSq = r * r
            val centerX = cx + apexDX * t
            val centerZ = cz + apexDZ * t
            for (wz in cMinZ..cMaxZ) {
                val dz = wz + 0.5 - centerZ
                val dzSq = dz * dz
                if (dzSq > rSq) continue
                for (wx in cMinX..cMaxX) {
                    val dx = wx + 0.5 - centerX
                    if (dx * dx + dzSq > rSq) continue
                    mutable.set(wx, wy, wz)
                    val existing = chunk.getBlockState(mutable)
                    if (existing.`is`(Blocks.BEDROCK)) continue
                    val block = if (isCryingPatchAt(wx, wy, wz)) CRYING_OBSIDIAN else OBSIDIAN
                    chunk.setBlockState(mutable, block, false)
                }
            }
        }
    }

    /** Frustum (tapered cylinder) rasterizer with per-Y XZ tilt. Tilt
     *  semantics match [paintRockTiltedCone]. */
    private fun paintRockTiltedTaperedCylinder(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos,
        cx: Double, baseY: Double, cz: Double,
        baseR: Double, topR: Double, height: Double,
        tiltX: Double, tiltZ: Double,
    ) {
        val topDX = tiltX * height
        val topDZ = tiltZ * height
        val maxR = Math.max(baseR, topR)
        val pad = maxR + 0.5
        val cMinX = Math.max(Math.floor(cx + Math.min(0.0, topDX) - pad).toInt(), chunkX0)
        val cMaxX = Math.min(Math.ceil(cx + Math.max(0.0, topDX) + pad).toInt(), chunkX0 + 15)
        val cMinZ = Math.max(Math.floor(cz + Math.min(0.0, topDZ) - pad).toInt(), chunkZ0)
        val cMaxZ = Math.min(Math.ceil(cz + Math.max(0.0, topDZ) + pad).toInt(), chunkZ0 + 15)
        val cMinY = Math.max(baseY.toInt(), MIN_Y)
        val cMaxY = Math.min(Math.ceil(baseY + height).toInt(), BARRIER_Y - 1)
        if (cMinX > cMaxX || cMinY > cMaxY || cMinZ > cMaxZ) return

        for (wy in cMinY..cMaxY) {
            val t = (wy + 0.5 - baseY) / height
            if (t < 0.0 || t > 1.0) continue
            val r = baseR + (topR - baseR) * t
            val rSq = r * r
            val centerX = cx + topDX * t
            val centerZ = cz + topDZ * t
            for (wz in cMinZ..cMaxZ) {
                val dz = wz + 0.5 - centerZ
                val dzSq = dz * dz
                if (dzSq > rSq) continue
                for (wx in cMinX..cMaxX) {
                    val dx = wx + 0.5 - centerX
                    if (dx * dx + dzSq > rSq) continue
                    mutable.set(wx, wy, wz)
                    val existing = chunk.getBlockState(mutable)
                    if (existing.`is`(Blocks.BEDROCK)) continue
                    val block = if (isCryingPatchAt(wx, wy, wz)) CRYING_OBSIDIAN else OBSIDIAN
                    chunk.setBlockState(mutable, block, false)
                }
            }
        }
    }

    /** Upper-half-ellipsoid rasterizer — only `dy ≥ 0` voxels are painted.
     *  Used by the dome painter for each lump. Allowing rX ≠ rZ produces
     *  squashed, elongated bulges instead of perfect hemispheres. */
    private fun paintRockUpperHalfEllipsoid(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos,
        cx: Double, baseY: Double, cz: Double,
        rX: Double, rY: Double, rZ: Double,
    ) {
        val cMinX = Math.max(Math.floor(cx - rX - 0.5).toInt(), chunkX0)
        val cMaxX = Math.min(Math.ceil(cx + rX + 0.5).toInt(), chunkX0 + 15)
        val cMinZ = Math.max(Math.floor(cz - rZ - 0.5).toInt(), chunkZ0)
        val cMaxZ = Math.min(Math.ceil(cz + rZ + 0.5).toInt(), chunkZ0 + 15)
        val cMinY = Math.max(baseY.toInt(), MIN_Y)
        val cMaxY = Math.min(Math.ceil(baseY + rY + 0.5).toInt(), BARRIER_Y - 1)
        if (cMinX > cMaxX || cMinY > cMaxY || cMinZ > cMaxZ) return

        for (wy in cMinY..cMaxY) {
            val dy = wy + 0.5 - baseY
            if (dy < 0.0) continue
            val dyN = dy / rY
            val dyNSq = dyN * dyN
            if (dyNSq > 1.0) continue
            for (wz in cMinZ..cMaxZ) {
                val dzN = (wz + 0.5 - cz) / rZ
                val dzNSq = dzN * dzN
                if (dyNSq + dzNSq > 1.0) continue
                for (wx in cMinX..cMaxX) {
                    val dxN = (wx + 0.5 - cx) / rX
                    if (dxN * dxN + dyNSq + dzNSq > 1.0) continue
                    mutable.set(wx, wy, wz)
                    val existing = chunk.getBlockState(mutable)
                    if (existing.`is`(Blocks.BEDROCK)) continue
                    val block = if (isCryingPatchAt(wx, wy, wz)) CRYING_OBSIDIAN else OBSIDIAN
                    chunk.setBlockState(mutable, block, false)
                }
            }
        }
    }


    /** Metadata for one tower candidate.
     *  `yaw` rotates the cross-section around the local up axis; `tiltAng` is
     *  the angle off vertical; `tiltAzi` is the world-XZ direction the tower
     *  leans toward. `sink` is how deep below the local sand surface the
     *  tower base sits. */
    /** Per-layer footprint patterns. `0` = air/sand, `1` = stone brick. */
    private val FOOTPRINT_3_BODY = arrayOf(
        intArrayOf(0, 1, 0),
        intArrayOf(1, 0, 1),
        intArrayOf(0, 1, 0),
    )
    private val FOOTPRINT_3_TOP = arrayOf(
        intArrayOf(1, 1, 1),
        intArrayOf(1, 0, 1),
        intArrayOf(1, 1, 1),
    )
    private val FOOTPRINT_3_CROWN = arrayOf(
        intArrayOf(1, 0, 1),
        intArrayOf(0, 0, 0),
        intArrayOf(1, 0, 1),
    )
    private val FOOTPRINT_5_BODY = arrayOf(
        intArrayOf(0, 1, 1, 1, 0),
        intArrayOf(1, 0, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 1),
        intArrayOf(0, 1, 1, 1, 0),
    )
    private val FOOTPRINT_5_TOP = arrayOf(
        intArrayOf(1, 1, 1, 1, 1),
        intArrayOf(1, 1, 1, 1, 1),
        intArrayOf(1, 1, 0, 1, 1),
        intArrayOf(1, 1, 1, 1, 1),
        intArrayOf(1, 1, 1, 1, 1),
    )
    private val FOOTPRINT_5_CROWN = arrayOf(
        intArrayOf(1, 0, 1, 0, 1),
        intArrayOf(0, 0, 0, 0, 0),
        intArrayOf(1, 0, 0, 0, 1),
        intArrayOf(0, 0, 0, 0, 0),
        intArrayOf(1, 0, 1, 0, 1),
    )
    // 7×7 extrapolates the same conventions as 3×3 / 5×5: body is a hollow
    // ring with the four corners missing, top is solid with a single
    // centre-hole, crown is the sparse "every-other-cell on outer rows,
    // outer-corners-only on inner rows" pattern.
    private val FOOTPRINT_7_BODY = arrayOf(
        intArrayOf(0, 1, 1, 1, 1, 1, 0),
        intArrayOf(1, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 1),
        intArrayOf(1, 0, 0, 0, 0, 0, 1),
        intArrayOf(0, 1, 1, 1, 1, 1, 0),
    )
    private val FOOTPRINT_7_TOP = arrayOf(
        intArrayOf(1, 1, 1, 1, 1, 1, 1),
        intArrayOf(1, 1, 1, 1, 1, 1, 1),
        intArrayOf(1, 1, 1, 1, 1, 1, 1),
        intArrayOf(1, 1, 1, 0, 1, 1, 1),
        intArrayOf(1, 1, 1, 1, 1, 1, 1),
        intArrayOf(1, 1, 1, 1, 1, 1, 1),
        intArrayOf(1, 1, 1, 1, 1, 1, 1),
    )
    private val FOOTPRINT_7_CROWN = arrayOf(
        intArrayOf(1, 0, 1, 0, 1, 0, 1),
        intArrayOf(0, 0, 0, 0, 0, 0, 0),
        intArrayOf(1, 0, 0, 0, 0, 0, 1),
        intArrayOf(0, 0, 0, 0, 0, 0, 0),
        intArrayOf(1, 0, 0, 0, 0, 0, 1),
        intArrayOf(0, 0, 0, 0, 0, 0, 0),
        intArrayOf(1, 0, 1, 0, 1, 0, 1),
    )

    /** Footprint-driven tower. `footprintSize` picks 3×3 or 5×5; layers
     *  switch to TOP / CROWN footprints near the top. Tilt is decoupled
     *  into [pitchAng] (0..π/2, off vertical) and [tiltAzi] (which yaw axis
     *  the tower leans toward); the cross-section pattern itself is NOT
     *  yaw-rotated. Crack is one spherical chunk shattered from the body,
     *  with the boundary turned into cracked stone brick. */
    private data class TowerCandidate(
        val rootX: Int, val rootZ: Int,
        val footprintSize: Int,        // 3 or 5
        val bodyLayers: Int,
        val sink: Int,
        val pitchAng: Double,          // 0..π/2 — off vertical
        val tiltAzi: Double,           // 0..2π — yaw of the tilt axis
        val crackCx: Double,           // crack centre, tower-local coords
        val crackCy: Double,
        val crackCz: Double,
        val crackR: Double,
        val seed: Int,
    )

    private fun towerCandidateInRegion(rgX: Int, rgZ: Int): TowerCandidate? {
        val seed = hash32(rgX, rgZ, 0x54_57_52_00.toInt())  // 'TWR\0'
        if ((seed and 0xF) > 0x4) return null               // ~30 % spawn
        val withinX = (hash32(rgX, rgZ, 0x33A2) ushr 1) and (TOWER_REGION_SIZE - 1)
        val withinZ = (hash32(rgX, rgZ, 0x771C) ushr 1) and (TOWER_REGION_SIZE - 1)
        val rootX = rgX * TOWER_REGION_SIZE + withinX
        val rootZ = rgZ * TOWER_REGION_SIZE + withinZ
        // 25 % 3×3, 50 % 5×5, 25 % 7×7. Keeps 5×5 the most common while
        // making both the tiny crosses and the larger keeps regular sights.
        val footprintSize = when ((seed ushr 4) and 0x3) {
            0 -> 3
            3 -> 7
            else -> 5
        }
        val bodyLayers = TOWER_BODY_LAYERS_MIN +
            ((seed ushr 5) and 0xFF) % TOWER_BODY_LAYERS_RAND
        val sink = TOWER_SINK_MIN + ((seed ushr 13) and 0xFF) % TOWER_SINK_RAND
        // Sample local surface gradient — the dune slopes east by ≈0.24
        // blocks per X-block, so a tower's "natural" lay direction is the
        // downhill direction at its root. We pick that as the tilt axis and
        // bias the pitch heavily toward 90° so most towers read as fallen
        // flat rather than just leaning.
        val baseE = coastBaseSurfaceYAt(rootX + 8, rootZ)
        val baseW = coastBaseSurfaceYAt(rootX - 8, rootZ)
        val baseN = coastBaseSurfaceYAt(rootX, rootZ - 8)
        val baseS = coastBaseSurfaceYAt(rootX, rootZ + 8)
        val gradX = (baseE - baseW).toDouble() / 16.0
        val gradZ = (baseS - baseN).toDouble() / 16.0
        val gradMag = Math.sqrt(gradX * gradX + gradZ * gradZ)

        val tiltAzi = if (gradMag > TOWER_SLOPE_THRESHOLD) {
            // Downhill direction = −gradient. The tower's top falls
            // downhill, so tiltAzi (which yaws the tilt axis) lines up
            // with downhill.
            val downhillAzi = Math.atan2(-gradZ, -gradX)
            val variance = (hash01(seed, 0, 0x2) - 0.5) * 2.0 * TOWER_TILT_AZI_VARIANCE
            downhillAzi + variance
        } else {
            // Flat ground (beach interior) — no slope to follow, pick freely.
            hash01(seed, 0, 0x2) * Math.PI * 2.0
        }

        // Power-curve pitch: with exponent ≈0.22, hash=0.5 → ≈0.86×(π/2) ≈ 78°.
        // Most towers fall in the 60-90° band; a small tail keeps a few at
        // less-tipped angles so the field isn't uniform.
        val pitchAng = (Math.PI / 2.0) *
            Math.pow(hash01(seed, 0, 0x1), TOWER_PITCH_POWER)
        val halfSize = (footprintSize - 1) / 2.0
        // Crack centred somewhere inside the body — biased toward the middle
        // height so the shatter cuts through the body, not the top crown.
        val crackCx = (hash01(seed, 0, 0x3) - 0.5) * halfSize * 1.6
        val crackCy = (0.15 + hash01(seed, 0, 0x4) * 0.65) * bodyLayers
        val crackCz = (hash01(seed, 0, 0x5) - 0.5) * halfSize * 1.6
        val crackR = TOWER_CRACK_RADIUS_MIN +
            hash01(seed, 0, 0x6) * (TOWER_CRACK_RADIUS_MAX - TOWER_CRACK_RADIUS_MIN)
        return TowerCandidate(
            rootX, rootZ, footprintSize, bodyLayers, sink,
            pitchAng, tiltAzi, crackCx, crackCy, crackCz, crackR, seed,
        )
    }

    /** Paint every tower whose root sits in the 3×3 region neighbourhood of
     *  this chunk. */
    private fun paintTowersInto(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos,
    ) {
        val rgX = Math.floorDiv(chunkX0, TOWER_REGION_SIZE)
        val rgZ = Math.floorDiv(chunkZ0, TOWER_REGION_SIZE)
        for (dRgX in -1..1) for (dRgZ in -1..1) {
            val cand = towerCandidateInRegion(rgX + dRgX, rgZ + dRgZ) ?: continue
            paintOneTower(chunk, chunkX0, chunkZ0, mutable, cand)
        }
    }

    /** Rasterize one tower into the chunk.
     *
     *  Per voxel: project into the tower's local frame (orthonormal basis
     *  built by rotating world axes around the horizontal pitch axis), round
     *  to the nearest cell, look up the layer's footprint, and place
     *  STONE_BRICKS / CRACKED_STONE_BRICKS / AIR according to the crack
     *  sphere. Bedrock is never overwritten. */
    private fun paintOneTower(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos,
        cand: TowerCandidate,
    ) {
        val rootSurface = coastBaseSurfaceYAt(cand.rootX, cand.rootZ)
        // Sand-area only.
        if (rootSurface < SEA_LEVEL_Y) return
        if (rootSurface >= TRUNK_Y_CAP - 4) return
        val baseY = rootSurface - cand.sink

        // Rotation around horizontal axis (cos(azi), 0, sin(azi)) by pitch.
        val tiltAxisX = Math.cos(cand.tiltAzi)
        val tiltAxisZ = Math.sin(cand.tiltAzi)
        val cTilt = Math.cos(cand.pitchAng); val sTilt = Math.sin(cand.pitchAng)
        val omc = 1.0 - cTilt

        fun rotate(vx: Double, vy: Double, vz: Double): DoubleArray {
            val dot = tiltAxisX * vx + tiltAxisZ * vz
            val cX = -tiltAxisZ * vy
            val cY = tiltAxisZ * vx - tiltAxisX * vz
            val cZ = tiltAxisX * vy
            return doubleArrayOf(
                vx * cTilt + cX * sTilt + tiltAxisX * dot * omc,
                vy * cTilt + cY * sTilt + 0.0,
                vz * cTilt + cZ * sTilt + tiltAxisZ * dot * omc,
            )
        }

        // World-aligned cross-section is NOT yaw-rotated; only the up axis
        // is tilted via Rodrigues.
        val right = rotate(1.0, 0.0, 0.0)
        val up    = rotate(0.0, 1.0, 0.0)
        val fwd   = rotate(0.0, 0.0, 1.0)
        val rightX = right[0]; val rightY = right[1]; val rightZ = right[2]
        val upX    = up[0];    val upY    = up[1];    val upZ    = up[2]
        val fwdX   = fwd[0];   val fwdY   = fwd[1];   val fwdZ   = fwd[2]

        val size = cand.footprintSize
        val halfSize = (size - 1) / 2.0
        val totalLayers = cand.bodyLayers + 2
        val bodyFp = when (size) {
            3 -> FOOTPRINT_3_BODY
            5 -> FOOTPRINT_5_BODY
            else -> FOOTPRINT_7_BODY
        }
        val topFp = when (size) {
            3 -> FOOTPRINT_3_TOP
            5 -> FOOTPRINT_5_TOP
            else -> FOOTPRINT_7_TOP
        }
        val crownFp = when (size) {
            3 -> FOOTPRINT_3_CROWN
            5 -> FOOTPRINT_5_CROWN
            else -> FOOTPRINT_7_CROWN
        }

        // World AABB. With pitch ≤ 90°, any cell's offset from the base in
        // each world axis is bounded by `halfSize × (|R| + |F|) + H × |U|`.
        val W = halfSize + 0.5
        val H = totalLayers.toDouble()
        val extX = W * (Math.abs(rightX) + Math.abs(fwdX)) + H * Math.abs(upX) + 1.0
        val extY = W * (Math.abs(rightY) + Math.abs(fwdY)) + H * Math.abs(upY) + 1.0
        val extZ = W * (Math.abs(rightZ) + Math.abs(fwdZ)) + H * Math.abs(upZ) + 1.0

        val cMinX = Math.max(Math.floor(cand.rootX - extX).toInt(), chunkX0)
        val cMaxX = Math.min(Math.ceil(cand.rootX + extX).toInt(), chunkX0 + 15)
        val cMinZ = Math.max(Math.floor(cand.rootZ - extZ).toInt(), chunkZ0)
        val cMaxZ = Math.min(Math.ceil(cand.rootZ + extZ).toInt(), chunkZ0 + 15)
        val cMinY = Math.max(Math.floor(baseY - extY).toInt(), MIN_Y)
        val cMaxY = Math.min(Math.ceil(baseY + H + extY).toInt(), BARRIER_Y - 1)
        if (cMinX > cMaxX || cMinY > cMaxY || cMinZ > cMaxZ) return

        val crackInner = cand.crackR
        val crackOuter = cand.crackR + TOWER_CRACK_OUTLINE_THICKNESS

        for (wy in cMinY..cMaxY) {
            val vy = wy + 0.5
            val dy = vy - baseY
            for (wz in cMinZ..cMaxZ) {
                val vz = wz + 0.5
                val dz = vz - cand.rootZ
                for (wx in cMinX..cMaxX) {
                    val vx = wx + 0.5
                    val dx = vx - cand.rootX

                    val lx = dx * rightX + dy * rightY + dz * rightZ
                    val ly = dx * upX    + dy * upY    + dz * upZ
                    val lz = dx * fwdX   + dy * fwdY   + dz * fwdZ

                    // Nearest cell in the tower's local grid.
                    val li = Math.floor(lx + halfSize + 0.5).toInt()
                    val lj = Math.floor(lz + halfSize + 0.5).toInt()
                    val layer = Math.floor(ly + 0.5).toInt()
                    if (li < 0 || li >= size || lj < 0 || lj >= size) continue
                    if (layer < 0 || layer >= totalLayers) continue

                    val fp = when {
                        layer == totalLayers - 1 -> crownFp
                        layer == totalLayers - 2 -> topFp
                        else -> bodyFp
                    }
                    if (fp[li][lj] == 0) continue

                    mutable.set(wx, wy, wz)
                    val existing = chunk.getBlockState(mutable)
                    if (existing.`is`(Blocks.BEDROCK)) continue

                    // Crack region — spherical chunk shattered out, with the
                    // boundary shell turned into cracked stone brick.
                    val crackDx = lx - cand.crackCx
                    val crackDy = ly - cand.crackCy
                    val crackDz = lz - cand.crackCz
                    val crackDistSq = crackDx * crackDx + crackDy * crackDy + crackDz * crackDz

                    val block = when {
                        crackDistSq < crackInner * crackInner -> AIR
                        crackDistSq < crackOuter * crackOuter -> CRACKED_STONE_BRICKS
                        else -> STONE_BRICKS
                    }
                    chunk.setBlockState(mutable, block, false)
                }
            }
        }
    }

    /** Sand mound around the tower base. Smooth-falloff plateau with
     *  downhill bias — see [directionalMoundLift] for the shared shape. */
    private fun nearbyTowerMoundLiftAt(wx: Int, wz: Int): Int {
        val rgX = Math.floorDiv(wx, TOWER_REGION_SIZE)
        val rgZ = Math.floorDiv(wz, TOWER_REGION_SIZE)
        var maxLift = 0
        for (dRgX in -1..1) for (dRgZ in -1..1) {
            val cand = towerCandidateInRegion(rgX + dRgX, rgZ + dRgZ) ?: continue
            val rootBase = coastBaseSurfaceYAt(cand.rootX, cand.rootZ)
            if (rootBase < SEA_LEVEL_Y) continue
            if (rootBase >= TRUNK_Y_CAP - 4) continue

            val lift = directionalMoundLift(
                wx, wz, cand.rootX, cand.rootZ,
                TOWER_MOUND_RADIUS, TOWER_MOUND_HEIGHT,
                TOWER_MOUND_INNER_PLATEAU,
            )
            if (lift > maxLift) maxLift = lift
        }
        return maxLift
    }

    /** Build a fractal: vertical-dominant trunk from the local surface up past
     *  the trunk cap, plus three primary branches that fork off at fixed
     *  fractions of the trunk height.
     *
     *  `thickness` is the per-tendril radius multiplier the caller already
     *  rolled (and used to size the sand mound) — applied uniformly to trunk
     *  and branch radii. */
    private fun buildTendrilFractal(
        rootX: Int, startY: Double, rootZ: Int, seed: Int, thickness: Double,
    ): List<TendrilSegment> {
        val out = ArrayList<TendrilSegment>(TENDRIL_TRUNK_STEPS + TENDRIL_BRANCH_STEPS * 3)
        val topY = TRUNK_Y_CAP.toDouble()
        val trunk = walkTendrilStrand(
            startX = rootX.toDouble(),
            startY = startY,
            startZ = rootZ.toDouble(),
            dirX = 0.0, dirY = 1.0, dirZ = 0.0,
            steps = TENDRIL_TRUNK_STEPS,
            stepLen = TRUNK_STEP_LEN,
            radius0 = TRUNK_BASE_RADIUS * thickness,
            radius1 = TRUNK_TIP_RADIUS * thickness,
            yCap = topY,
            lateralWobbleAmp = TRUNK_LATERAL_WOBBLE,
            dyMin = TRUNK_DY_MIN,
            seed = seed,
            walkSalt = 0,
        )
        out += trunk

        // Three branches fork at evenly-spread trunk fractions. Each gets a
        // tilted direction (mostly horizontal) and a smaller starting radius
        // — they peel off the trunk and arc up to the cap.
        val branchFractions = doubleArrayOf(0.25, 0.50, 0.75)
        for ((i, frac) in branchFractions.withIndex()) {
            val idx = (TENDRIL_TRUNK_STEPS * frac).toInt().coerceIn(0, trunk.size - 1)
            val from = trunk[idx]
            // Skip if the branch root is already too close to the cap.
            if (from.y1 >= topY - 4.0) continue
            val angle = hash01(seed, i, 0x11) * Math.PI * 2.0
            // tilt = horizontal fraction of the unit heading; vertical is the
            // complement. Branches start mostly horizontal so they read as
            // arms peeling off the trunk silhouette.
            val tilt = 0.65 + hash01(seed, i, 0x12) * 0.20   // 0.65..0.85 horiz
            val dx = Math.cos(angle) * tilt
            val dz = Math.sin(angle) * tilt
            val dy = Math.sqrt((1.0 - tilt * tilt).coerceAtLeast(0.0))
            val r0 = from.r1 * 0.95   // already scaled via trunk
            out += walkTendrilStrand(
                startX = from.x1, startY = from.y1, startZ = from.z1,
                dirX = dx, dirY = dy, dirZ = dz,
                steps = TENDRIL_BRANCH_STEPS,
                stepLen = BRANCH_STEP_LEN,
                radius0 = r0,
                radius1 = BRANCH_TIP_RADIUS * thickness,
                yCap = topY,
                lateralWobbleAmp = BRANCH_LATERAL_WOBBLE,
                dyMin = BRANCH_DY_MIN,
                seed = seed,
                walkSalt = 0x100 + i,
            )
        }

        return out
    }

    /** Random-walk a single strand: each step adds noise to the heading and
     *  emits one capsule segment from the previous tip to the new one. Trunks
     *  and branches share this — they only differ in step length, radius
     *  schedule, wobble amplitude, and minimum vertical heading. */
    private fun walkTendrilStrand(
        startX: Double, startY: Double, startZ: Double,
        dirX: Double, dirY: Double, dirZ: Double,
        steps: Int, stepLen: Double, radius0: Double, radius1: Double, yCap: Double,
        lateralWobbleAmp: Double, dyMin: Double,
        seed: Int, walkSalt: Int,
    ): List<TendrilSegment> {
        val out = ArrayList<TendrilSegment>(steps)
        var px = startX; var py = startY; var pz = startZ
        var hx = dirX; var hy = dirY; var hz = dirZ
        var pr = radius0
        for (s in 0 until steps) {
            val t = (s + 1).toDouble() / steps
            val r1 = radius0 + (radius1 - radius0) * t

            // Heading wobble — lateral wobble around current heading; vertical
            // bias kept at dyMin or higher so strands climb on net.
            val wobAng = hash01(seed, s, walkSalt or 0x21) * Math.PI * 2.0
            hx += Math.cos(wobAng) * lateralWobbleAmp
            hz += Math.sin(wobAng) * lateralWobbleAmp
            hy += (hash01(seed, s, walkSalt or 0x22) - 0.5) * 0.20
            hy = hy.coerceAtLeast(dyMin)
            val mag = Math.sqrt(hx * hx + hy * hy + hz * hz)
            if (mag > 1.0e-6) { hx /= mag; hy /= mag; hz /= mag }

            val nx = px + hx * stepLen
            val ny = (py + hy * stepLen).coerceAtMost(yCap)
            val nz = pz + hz * stepLen

            out += TendrilSegment(px, py, pz, nx, ny, nz, pr, r1)
            if (ny >= yCap) break
            px = nx; py = ny; pz = nz; pr = r1
        }
        return out
    }

    /** Rasterize a single tendril capsule into the chunk via tapered-cylinder
     *  SDF. Only voxels currently water (or air, near the tip) get overwritten
     *  to obsidian — sand and barrier are sacred. */
    private fun paintCapsuleIntoChunk(
        chunk: ChunkAccess, chunkX0: Int, chunkZ0: Int,
        mutable: BlockPos.MutableBlockPos, seg: TendrilSegment,
    ) {
        val rMax = Math.max(seg.r0, seg.r1)
        val pad = rMax + 1.0
        val minWX = Math.floor(Math.min(seg.x0, seg.x1) - pad).toInt()
        val maxWX = Math.ceil(Math.max(seg.x0, seg.x1) + pad).toInt()
        val minWY = Math.floor(Math.min(seg.y0, seg.y1) - pad).toInt()
        val maxWY = Math.ceil(Math.max(seg.y0, seg.y1) + pad).toInt()
        val minWZ = Math.floor(Math.min(seg.z0, seg.z1) - pad).toInt()
        val maxWZ = Math.ceil(Math.max(seg.z0, seg.z1) + pad).toInt()

        val cMinX = Math.max(minWX, chunkX0)
        val cMaxX = Math.min(maxWX, chunkX0 + 15)
        val cMinZ = Math.max(minWZ, chunkZ0)
        val cMaxZ = Math.min(maxWZ, chunkZ0 + 15)
        val cMinY = Math.max(minWY, MIN_Y)
        val cMaxY = Math.min(maxWY, BARRIER_Y - 1)   // never punch through the ceiling
        if (cMinX > cMaxX || cMinY > cMaxY || cMinZ > cMaxZ) return

        val ex = seg.x1 - seg.x0; val ey = seg.y1 - seg.y0; val ez = seg.z1 - seg.z0
        val segLenSq = ex * ex + ey * ey + ez * ez
        if (segLenSq < 1.0e-6) return
        val invSegLenSq = 1.0 / segLenSq

        for (wy in cMinY..cMaxY) {
            val vy = wy + 0.5
            val py = vy - seg.y0
            val pyEy = py * ey
            for (wz in cMinZ..cMaxZ) {
                val vz = wz + 0.5
                val pz = vz - seg.z0
                val pyEyPlusPzEz = pyEy + pz * ez
                for (wx in cMinX..cMaxX) {
                    val vx = wx + 0.5
                    val px = vx - seg.x0
                    val tRaw = (px * ex + pyEyPlusPzEz) * invSegLenSq
                    val t = if (tRaw < 0.0) 0.0 else if (tRaw > 1.0) 1.0 else tRaw
                    val cX = seg.x0 + ex * t
                    val cY = seg.y0 + ey * t
                    val cZ = seg.z0 + ez * t
                    val ddx = vx - cX; val ddy = vy - cY; val ddz = vz - cZ
                    val distSq = ddx * ddx + ddy * ddy + ddz * ddz
                    val r = seg.r0 + (seg.r1 - seg.r0) * t
                    if (distSq > r * r) continue

                    mutable.set(wx, wy, wz)
                    val existing = chunk.getBlockState(mutable)
                    // Bedrock is sacred; everything else (sand / water / air)
                    // gets pierced — the tendril carves through the dune.
                    if (existing.`is`(Blocks.BEDROCK)) continue
                    val block = if (isCryingPatchAt(wx, wy, wz)) CRYING_OBSIDIAN else OBSIDIAN
                    chunk.setBlockState(mutable, block, false)
                }
            }
        }
    }


    /** 2D bilinear-interpolated value noise, output in `[0, 1]`. Same pattern
     *  the Wohlon generator uses. */
    private fun valueNoise2(x: Double, z: Double): Double {
        val ix = Math.floor(x).toInt()
        val iz = Math.floor(z).toInt()
        val fx = x - ix
        val fz = z - iz
        val sx = fx * fx * (3.0 - 2.0 * fx)
        val sz = fz * fz * (3.0 - 2.0 * fz)
        val a = (hash32(ix, iz, 1) and 0xFFFF) / 65535.0
        val b = (hash32(ix + 1, iz, 1) and 0xFFFF) / 65535.0
        val c = (hash32(ix, iz + 1, 1) and 0xFFFF) / 65535.0
        val d = (hash32(ix + 1, iz + 1, 1) and 0xFFFF) / 65535.0
        val ab = a + (b - a) * sx
        val cd = c + (d - c) * sx
        return ab + (cd - ab) * sz
    }

    /** Splitmix-style integer hash. Same shape as the other generators. */
    private fun hash32(a: Int, b: Int, c: Int): Int {
        var h = a * 0x9E3779B1.toInt()
        h = h xor (b + 0x85EBCA77.toInt() + (h shl 6) + (h shr 2))
        h = h xor (c + 0xC2B2AE3D.toInt() + (h shl 6) + (h shr 2))
        h = (h xor (h ushr 16)) * 0x7FEB352D.toInt()
        h = (h xor (h ushr 15)) * 0x846CA68B.toInt()
        return h xor (h ushr 16)
    }

    /** Hash → `[0, 1)`. */
    private fun hash01(a: Int, b: Int, c: Int): Double =
        (hash32(a, b, c).toLong() and 0xFFFFFFFFL).toDouble() / (1L shl 32).toDouble()

    /** Per-voxel check for individual crying-obsidian blocks scattered through
     *  the tendril mass. */
    private fun isCryingPatchAt(wx: Int, wy: Int, wz: Int): Boolean =
        hash01(wx, wy, wz) < CRYING_OBSIDIAN_DENSITY

    companion object {
        const val MIN_Y = -64
        const val WORLD_HEIGHT = 320
        const val MAX_Y_INCL = MIN_Y + WORLD_HEIGHT - 1     // = 255
        const val BARRIER_Y = MAX_Y_INCL                    // top layer = barrier
        const val SEA_LEVEL_Y = 63
        const val BEACH_BASE_Y = 63

        // Coast geometry.
        const val BEACH_HALF_WIDTH = 8
        const val COAST_WOBBLE_AMP = 24.0
        const val COAST_WOBBLE_FREQ = 0.012
        const val COAST_FINE_AMP = 5.0
        const val COAST_FINE_FREQ = 0.071

        // East dropoff into deep ocean — long gentle slope.
        const val OCEAN_FLOOR_Y = -55
        const val DROPOFF_FULL_X = 400

        // West sand pile up to just below the barrier — long gentle dune.
        const val PILE_TOP_Y = MAX_Y_INCL - 2               // = 253
        const val PILE_FULL_X = 800

        // Obsidian tendrils — skyline-piercing fractals everywhere.
        const val TENDRIL_REGION_SIZE = 64
        const val TRUNK_Y_CAP = BARRIER_Y - 4               // = 251, just under ceiling
        const val TENDRIL_TRUNK_STEPS = 60
        const val TENDRIL_BRANCH_STEPS = 28
        const val TRUNK_STEP_LEN = 5.0
        const val BRANCH_STEP_LEN = 4.0
        const val TRUNK_BASE_RADIUS = 3.2
        const val TRUNK_TIP_RADIUS = 0.85
        const val BRANCH_TIP_RADIUS = 0.45
        // Per-tendril multiplier on every radius. Spread of 0.6× to 2.0× gives
        // bases between ≈4 and ≈13 blocks wide — varied skyline.
        const val TENDRIL_THICKNESS_MIN = 0.6
        const val TENDRIL_THICKNESS_MAX = 2.0
        // Tendril sand mound: peak height = base radius × HEIGHT, mound radius =
        // base radius × RADIUS. A thick (≈6.4) tendril gets a ≈10-block peak
        // spreading ≈19 blocks; a thin (≈1.9) tendril gets a ≈3-block bump over
        // ≈6 blocks. Mound only applies above sea level.
        const val MOUND_HEIGHT_MULT = 1.6
        const val MOUND_RADIUS_MULT = 3.2
        // Fraction of the mound radius that's a flat plateau at full peak
        // height — beyond it the lift smootherstep-falls to zero. Kept small
        // so most of the radius is in the smooth gradient zone, which is
        // what makes the mound blend cleanly into the surrounding terrain.
        const val MOUND_INNER_PLATEAU = 0.20
        // How asymmetrically the mound stretches along the local downhill
        // direction. 0 → perfectly radial; 0.6 → mound reaches 1.6× its
        // base radius downhill and 0.4× uphill — sand "blown" into place.
        const val MOUND_DOWNHILL_BIAS = 0.6

        // Small obsidian rock clusters in the sand. Denser grid + smaller
        // scale than the tendrils, so the sand reads as studded with rocks.
        const val ROCK_REGION_SIZE = 24
        const val ROCK_BASE_RADIUS = 1.6
        const val ROCK_THICKNESS_MIN = 0.65
        const val ROCK_THICKNESS_MAX = 1.50
        // Rock sand mound: peak height = sizeBase × HEIGHT, mound radius =
        // sizeBase × RADIUS. Bumped from the initial subtle values — the
        // mound now reads as a real displacement pile around the rock.
        const val ROCK_MOUND_HEIGHT_MULT = 1.10
        const val ROCK_MOUND_RADIUS_MULT = 3.0
        // Flat plateau fraction — inside this slice of the radius the mound
        // is at full peak; beyond it linearly cliffs to zero. Same trick
        // the tendril mounds use to read as displaced sand instead of a
        // smooth hill.
        const val ROCK_MOUND_INNER_PLATEAU = 0.15

        // Shape-variety roll: every rock picks one of five silhouette types
        // (sphere cluster, flat slab, sharp shard, columnar pillar, smooth
        // dome). Weights tilt toward CLUSTER + SHARD + PILLAR for variety
        // density; SLAB and DOME are the rarer ones.
        // Landmark boulder roll: ~6 % of rocks ignore the normal size band
        // and roll a much larger silhouette. Pairs with shape variety so
        // a slab-boulder reads as a giant table and a shard-boulder reads
        // as a true landmark spire.
        const val ROCK_BOULDER_CHANCE_MASK = 0xF              // (seed>>28)&MASK == 0 → 1/16
        const val ROCK_BOULDER_SCALE = 3.5
        // Burial fraction — how deep the rock sits IN the sand vs ON it.
        // Final burialDepth = sizeBase × (MIN + RAND × hash). The mound
        // around the rock surrounds the buried portion naturally; the
        // exposed portion sticks up out of the sand.
        const val ROCK_BURIAL_MIN = 0.30
        const val ROCK_BURIAL_RAND = 0.30
        // Debris field — small single-block fragments scattered around the
        // rock's base, suggesting erosion has been pulling chips off.
        const val ROCK_DEBRIS_COUNT_MIN = 4
        const val ROCK_DEBRIS_COUNT_RAND = 8                  // 4..11 pieces
        const val ROCK_DEBRIS_DIST_MIN = 1.6
        const val ROCK_DEBRIS_DIST_RAND = 1.6                 // 1.6..3.2 × sizeBase

        // Sparse — landmarks, not dressing. 3×3 or 5×5 footprint, body
        // built from the layered patterns in FOOTPRINT_*_BODY/TOP/CROWN.
        // Tilted up to 90° pitch on a hash-random yaw axis, then a spherical
        // chunk is shattered through it (the boundary cracked-brick).
        const val TOWER_REGION_SIZE = 192
        // Body height in layers (one block per layer). +2 layers (top + crown)
        // are added on top of the body in paintOneTower.
        const val TOWER_BODY_LAYERS_MIN = 7
        const val TOWER_BODY_LAYERS_RAND = 12          // body ∈ [7..18]
        // How deep the tower base sinks below the local sand surface.
        const val TOWER_SINK_MIN = 4
        const val TOWER_SINK_RAND = 7                  // sink ∈ [4..10]
        // Crack region — a spherical chunk of the body shattered out, with
        // the boundary turned into cracked stone brick. RADIUS picks the
        // inner-air radius; OUTLINE is the cracked-brick shell thickness.
        const val TOWER_CRACK_RADIUS_MIN = 1.8
        const val TOWER_CRACK_RADIUS_MAX = 3.5
        const val TOWER_CRACK_OUTLINE_THICKNESS = 1.5
        // Sand mound at the tower base. Smaller than the tendril mounds —
        // the tower SITS in the sand, the body doesn't fan-pierce it.
        const val TOWER_MOUND_HEIGHT = 1.6
        const val TOWER_MOUND_RADIUS = 9.0
        const val TOWER_MOUND_INNER_PLATEAU = 0.20

        // Slope-aware tilt. Towers are biased toward "fallen flat on the
        // dune" rather than at random angles — pitch heavily skewed near
        // 90° (power-curve exponent < 1 pushes the distribution toward 1),
        // and tiltAzi follows the local downhill direction when there's
        // enough slope to register.
        const val TOWER_PITCH_POWER = 0.22
        const val TOWER_SLOPE_THRESHOLD = 0.05
        const val TOWER_TILT_AZI_VARIANCE = Math.PI / 5.0    // ±36° around downhill
        // Trunk steps to sample for the mound's "shape follows tendril" path.
        // Each step is TRUNK_STEP_LEN long → 4 steps covers ~20 blocks of
        // trunk in the breach zone.
        const val MOUND_PATH_STEPS = 4
        // Worst-case lateral XZ drift of the breach path away from the root.
        // Per step ≤ TRUNK_STEP_LEN × √(1 − TRUNK_DY_MIN²) ≈ 4.9; over
        // MOUND_PATH_STEPS that's ~20. Used as a cheap reject pad.
        const val MOUND_PATH_DRIFT_MAX = 20.0
        // Barrier sky-fill bottom Y. Air at or above this height (with no
        // terrain or water already in place) becomes barrier so the player
        // can't ascend past it — the dream is sealed at the top.
        const val BARRIER_START_Y = 100
        // Crying-obsidian intersperse density. ~2 % of individual voxels inside
        // the tendrils become crying obsidian — rare scattered single blocks.
        const val CRYING_OBSIDIAN_DENSITY = 0.02
        // Heading control. Trunks bias upward (dyMin=0.20 floors horizontal-ish);
        // branches free to dip (dyMin=-0.45 allows ~27° below horizontal) so the
        // skyline has arms arcing back down, not just up.
        const val TRUNK_LATERAL_WOBBLE = 0.22
        const val TRUNK_DY_MIN = 0.20
        const val BRANCH_LATERAL_WOBBLE = 0.18
        const val BRANCH_DY_MIN = -0.45
        // Worst-case XZ reach from root: trunk's accumulated lateral drift +
        // one branch's full lateral run. With wobble accumulating roughly
        // linearly in the worst case, trunk drift ≤ steps × stepLen × wobble.
        const val TENDRIL_MAX_REACH = 128

        private val BEDROCK: BlockState = Blocks.BEDROCK.defaultBlockState()
        // Sureibjin uses its own re-skinned obsidian / crying-obsidian /
        // sand variants — same behaviour as vanilla but different
        // textures (and dust colour for the sand). See [EKBlocks].
        private val SAND: BlockState =
            org.shipwrights.enderkinesis.registry.EKBlocks.DREAM_SAND.get().defaultBlockState()
        private val WATER: BlockState = Blocks.WATER.defaultBlockState()
        private val BARRIER: BlockState = Blocks.BARRIER.defaultBlockState()
        private val OBSIDIAN: BlockState =
            org.shipwrights.enderkinesis.registry.EKBlocks.DREAM_OBSIDIAN.get().defaultBlockState()
        private val CRYING_OBSIDIAN: BlockState =
            org.shipwrights.enderkinesis.registry.EKBlocks.DREAM_CRYING_OBSIDIAN.get().defaultBlockState()
        private val STONE_BRICKS: BlockState = Blocks.STONE_BRICKS.defaultBlockState()
        private val CRACKED_STONE_BRICKS: BlockState =
            Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
        private val AIR: BlockState = Blocks.AIR.defaultBlockState()

        val CODEC: Codec<SureibjinChunkGenerator> =
            RecordCodecBuilder.create { instance ->
                instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source")
                        .forGetter(SureibjinChunkGenerator::getBiomeSourceForCodec),
                ).apply(instance, ::SureibjinChunkGenerator)
            }
    }
}
