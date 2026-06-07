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
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.blending.Blender
import org.shipwrights.enderkinesis.registry.EKBlocks

/**
 * Chunk generator for [Wohlonnogondonia] — an infinite dismal swamp.
 *
 * ## Column layout
 *
 *  - **y=0**: bedrock floor (single layer).
 *  - **y=1..surface**: mud. Surface height varies in a narrow band around
 *    [SURFACE_BASE_Y] driven by [valueNoise2].
 *  - **y=surface+1..SEA_LEVEL_Y**: water, only where `surface < SEA_LEVEL_Y` — the
 *    murky pools.
 *  - **y > max(surface, SEA_LEVEL_Y)**: air.
 *
 * ## The Mother Tree hill
 *
 *  A smooth radial bump centred at world origin lifts the surface by up to
 *  [HILL_PEAK_LIFT] over [HILL_RADIUS] blocks, cradling the [MotherTree] at its
 *  crown.
 *
 * ## Wogor trees
 *
 *  Trees are placed deterministically by sampling a Poisson-ish hash on chunk
 *  coords plus a per-chunk salt — each loaded chunk scans every chunk within
 *  [TREE_REACH_CHUNKS] for candidate trunk roots whose canopy might intrude into
 *  itself, and paints only the blocks that fall inside its own XZ footprint. The
 *  same approach handles the Mother Tree at origin.
 *
 * ## Threading
 *
 *  All worldgen helpers are pure functions of `(wx, wy, wz)` and the world is
 *  stateless aside from the noise hash, so C2ME can run this concurrently across
 *  many chunks against a single generator instance — no per-chunk scratch on
 *  the generator, no [ThreadLocal] needed. See [[sselith-chunkgen-threading]] for
 *  the cautionary tale.
 */
class WohlonnogondoniaChunkGenerator(
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
        captureHeartTunnelAngle(randomState)
        val chunkX0 = chunk.pos.minBlockX
        val chunkZ0 = chunk.pos.minBlockZ
        val minY = chunk.minBuildHeight
        val maxY = chunk.maxBuildHeight

        // Prime the heightmaps the chunk-status pipeline expects so `setBlockState`
        // updates them automatically (same trick the Sselith generator uses).
        chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG)
        chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG)

        val mutable = BlockPos.MutableBlockPos()

        // Per-chunk column array: every position not claimed by a feature (trees,
        // Mother Tree) reads its block from the base terrain (bedrock / mud / water
        // / air). Features are painted into this array first so a tree log overrides
        // the air or water that would otherwise occupy that voxel.
        val span = maxY - minY
        val featureCache = Array(16) { Array(16) { arrayOfNulls<BlockState>(span) } }

        // Mother Tree at origin (touches only chunks within its footprint).
        //
        // NOTE: Child Trees *and* surface roots are intentionally *not*
        // painted here. Both run in [applyBiomeDecoration] after the
        // vanilla forest features, so they overwrite anything the vanilla
        // `wogor_forest` placed in their footprint instead of fighting it.
        // The pipeline is:
        //   fillFromNoise         → mud hill + Mother Tree
        //   applyBiomeDecoration  → vanilla forest, then Surface Roots
        //                           (carve through everything), then Child
        //                           Trees on top.
        paintMotherTreeInto(featureCache, chunkX0, chunkZ0, minY, span)

        // 3. Per-column fill: bedrock floor, mud body, water cap (where
        //    applicable), air above, then overlay the feature cache on
        //    top. The cave at origin is the only thing that hollows out
        //    feature blocks; the moat is folded into `effectiveSurfaceYAt`
        //    (so it just looks like a lowered surface to the rest of the
        //    pipeline) and the heart tunnel is now a walked path painted
        //    in [applyBiomeDecoration] alongside the rivers + roots.
        for (localX in 0..15) {
            val worldX = chunkX0 + localX
            val xPlane = featureCache[localX]
            for (localZ in 0..15) {
                val worldZ = chunkZ0 + localZ
                val featCol = xPlane[localZ]
                // Effective surface = max(natural hill, Mother Tree
                // mound), with the moat U-shape depression carved in.
                // Below this Y is mud; above to sea level is water;
                // above sea level is air. Feature cache then layers
                // wood / leaves / lanterns on top.
                val surface = effectiveSurfaceYAt(worldX, worldZ)

                // Cave is the only per-voxel override — and only inside
                // its noise-perturbed XZ footprint.
                val caveXZ = isInsideCaveFootprint(worldX, worldZ)

                for (i in 0 until span) {
                    val y = minY + i
                    val feature = featCol[i]
                    val baseBlock = baseColumnBlock(y, surface)

                    val caveOverride = if (caveXZ) {
                        centralCaveBlock(worldX, y, worldZ)
                    } else null

                    // Cave hollows out everything (including the
                    // feature cache, so Mother Tree roots end cleanly
                    // at the cave walls). Outside the cave, feature
                    // cache wins over base — that's what preserves the
                    // canopy where the moat lowers the surface beneath
                    // it. Bedrock is sacred at y=0.
                    val block = when {
                        baseBlock === BEDROCK -> baseBlock
                        caveOverride != null -> caveOverride
                        feature != null -> feature
                        else -> baseBlock
                    }
                    if (block === AIR) continue
                    chunk.setBlockState(mutable.set(worldX, y, worldZ), block, false)
                }
            }
        }
        return CompletableFuture.completedFuture(chunk)
    }

    // ------------------------------------------------------------------------
    //   central geometric features: moat (folded into surface) / cave
    // ------------------------------------------------------------------------

    /**
     * U-shaped surface depression around the Mother Tree's hill foot.
     * Returns the moat-blended surface Y for `(wx, wz)`, or
     * [Int.MIN_VALUE] if this column isn't inside the moat ring.
     *
     * The blend uses a smoothstep curve: at the moat centre the
     * surface drops to [MOAT_FLOOR_Y]; at either edge it returns to the
     * passed-in `surfaceWithoutMoat` (whichever of the natural / mound
     * surface would have applied here without the moat). Inner / outer
     * edges of the ring are warped by a low-frequency value-noise so
     * the ring meanders rather than reading as a perfect circle.
     */
    private fun moatBlendedSurface(wx: Int, wz: Int, surfaceWithoutMoat: Int): Int {
        val dxz = Math.sqrt(wx.toDouble() * wx + wz.toDouble() * wz)
        // Cheap reject for columns far from the ring.
        if (dxz < MOAT_INNER_R - MOAT_BOUNDARY_NOISE_AMP ||
            dxz > MOAT_OUTER_R + MOAT_BOUNDARY_NOISE_AMP) return Int.MIN_VALUE
        val noise = (valueNoise2(
            wx * MOAT_BOUNDARY_NOISE_FREQ,
            wz * MOAT_BOUNDARY_NOISE_FREQ,
        ) - 0.5) * 2.0 * MOAT_BOUNDARY_NOISE_AMP
        val effDist = dxz - noise
        if (effDist < MOAT_INNER_R || effDist > MOAT_OUTER_R) return Int.MIN_VALUE

        // smoothstep: 0 at the moat's centre line, 1 at either edge.
        val midR = (MOAT_INNER_R + MOAT_OUTER_R) * 0.5
        val halfWidth = (MOAT_OUTER_R - MOAT_INNER_R) * 0.5
        val edgeT = (Math.abs(effDist - midR) / halfWidth).coerceIn(0.0, 1.0)
        val smoothT = edgeT * edgeT * (3.0 - 2.0 * edgeT)

        val moatFloor = MOAT_FLOOR_Y.toDouble()
        return ((1.0 - smoothT) * moatFloor + smoothT * surfaceWithoutMoat).toInt()
    }

    /**
     * Cheap XZ-only test: is this column inside the noise-perturbed
     * cave footprint? The Y-bounds + per-voxel boundary test happen
     * inside [centralCaveBlock].
     *
     * Conservative over-approximation: includes the worst-case wall
     * noise *plus* the tunnel-direction bulge, so the per-voxel check
     * inside [centralCaveBlock] is the authoritative one.
     */
    private fun isInsideCaveFootprint(wx: Int, wz: Int): Boolean {
        val maxRadius = CAVE_BASE_RADIUS + CAVE_BOUNDARY_NOISE_AMP +
            CAVE_TUNNEL_BULGE_AMP
        val dSq = wx.toDouble() * wx + wz.toDouble() * wz
        return dSq <= maxRadius * maxRadius
    }

    /**
     * Override block for the central cave, or null if `(wx, wy, wz)`
     * is outside the cave volume.
     *
     * Geometry is a *round* base radius perturbed by two effects:
     *
     *  1. **Angle-symmetric noise** — sampled at `(cos angle, sin
     *     angle)` so the radius wobble is continuous around the cave
     *     instead of biased to one Cartesian axis. The wall undulates
     *     in big lobes without making the cave look off-centre.
     *  2. **Tunnel-direction bulge** — a Gaussian extension toward
     *     [heartTunnelAngleRad]. At the tunnel angle the effective
     *     radius grows by [CAVE_TUNNEL_BULGE_AMP], tapering off as the
     *     angle diverges, so the tunnel mouth blends smoothly into the
     *     cave wall instead of meeting it at a hard joint.
     *
     * The floor + ceiling Y are also mildly noise-perturbed per XZ
     * column. The "heart" is the sea lantern at `(0, SEA_LEVEL_Y, 0)`
     * — the cave's geometric centre.
     */
    private fun centralCaveBlock(wx: Int, wy: Int, wz: Int): BlockState? {
        // Per-column ceiling / floor with noise-driven undulation.
        val floorN = valueNoise2(
            wx * 0.08 + 17.0, wz * 0.08 + 23.0,
        ) - 0.5
        val ceilN = valueNoise2(
            wx * 0.08 + 53.0, wz * 0.08 + 41.0,
        ) - 0.5
        val floorHere = CAVE_FLOOR_BASE_Y + (floorN * 2.0 * CAVE_Y_NOISE_AMP).toInt()
        val ceilingHere = CAVE_CEILING_BASE_Y + (ceilN * 2.0 * CAVE_Y_NOISE_AMP).toInt()
        if (wy < floorHere || wy > ceilingHere) return null

        val dxz = Math.sqrt(wx.toDouble() * wx + wz.toDouble() * wz)
        // Origin column — let the centre always be inside the cave.
        if (dxz < 0.5) {
            if (wx == 0 && wz == 0 && wy == HEART_Y) return SEA_LANTERN
            return if (wy < SEA_LEVEL_Y) WATER else AIR
        }
        val angle = Math.atan2(wz.toDouble(), wx.toDouble())

        // Angle-symmetric noise — sample at `(cos, sin)` so it wraps
        // continuously around the cave. Y term adds 3D bumpiness.
        val angleNoise = (
            valueNoise2(
                Math.cos(angle) * 3.0 + wy * 0.13,
                Math.sin(angle) * 3.0 + wy * 0.13 + 7.0,
            ) - 0.5
        ) * 2.0 * CAVE_BOUNDARY_NOISE_AMP

        // Gaussian bulge toward the heart-tunnel direction. Smoothly
        // tapers from `CAVE_TUNNEL_BULGE_AMP` at the tunnel angle to
        // ~0 outside the σ envelope.
        var angleDiff = angle - heartTunnelAngleRad
        while (angleDiff > Math.PI) angleDiff -= 2.0 * Math.PI
        while (angleDiff < -Math.PI) angleDiff += 2.0 * Math.PI
        val bulge = Math.exp(
            -(angleDiff * angleDiff) /
                (2.0 * CAVE_TUNNEL_BULGE_SIGMA * CAVE_TUNNEL_BULGE_SIGMA)
        ) * CAVE_TUNNEL_BULGE_AMP

        val effRadius = CAVE_BASE_RADIUS + angleNoise + bulge
        if (dxz > effRadius) return null

        // Lantern (the "heart") sits at the cave's geometric centre
        // [HEART_Y] — 4 blocks above sea level — and reads as a
        // glowing block raised over the pool. Water still fills only
        // up to sea level so the cave's pool surface aligns with the
        // moat / river / heart-tunnel water outside; air fills the
        // gap between sea level and the lantern, then continues up
        // to the cave ceiling.
        if (wx == 0 && wz == 0 && wy == HEART_Y) return SEA_LANTERN
        if (wy < SEA_LEVEL_Y) return WATER
        return AIR
    }

    /** World-stable random heart-tunnel direction in `[0, 2π)`.
     *  Derived from a [net.minecraft.world.level.levelgen.PositionalRandomFactory]
     *  keyed to this mod's namespace, which Minecraft seeds with the
     *  world seed internally — so each world rolls a genuinely
     *  different direction. Without seed entropy every world rolled
     *  the same SW-ish angle from `hash32(0, 0, 'CORE')`.
     *
     *  Captured on the first [fillFromNoise] call via
     *  [captureHeartTunnelAngle]; accesses before that point fall
     *  back to the old unseeded hash so the value is still
     *  deterministic on the rare paths that beat `fillFromNoise`
     *  to the punch. */
    private val heartTunnelAngleRad: Double
        get() {
            val captured = cachedHeartTunnelAngle
            if (!java.lang.Double.isNaN(captured)) return captured
            // Unseeded fallback — kept deterministic but world-invariant.
            val s = hash32(0, 0, 0x434F5245.toInt())  // 'CORE'
            return (s.toLong() and 0xFFFFFFFFL).toDouble() /
                ((1L shl 32).toDouble()) * 2.0 * Math.PI
        }

    @Volatile private var cachedHeartTunnelAngle: Double = Double.NaN

    /** Capture the world-seeded heart-tunnel angle on first
     *  [fillFromNoise] call. Idempotent — the first writer wins;
     *  concurrent C2ME workers can race here harmlessly since they'd
     *  all compute the same value from the same `randomState`. */
    private fun captureHeartTunnelAngle(randomState: RandomState) {
        if (!java.lang.Double.isNaN(cachedHeartTunnelAngle)) return
        val factory = randomState.getOrCreateRandomFactory(
            net.minecraft.resources.ResourceLocation("enderkinesis", "heart_tunnel_angle")
        )
        val rng = factory.at(0, 0, 0)
        cachedHeartTunnelAngle = rng.nextDouble() * 2.0 * Math.PI
    }

    /**
     * Paint our procedural features into this chunk, then let
     * vanilla's biome decoration run on top of them. The pipeline:
     *
     *   1. [fillFromNoise] paints the mud hill + Mother Tree.
     *   2. *This method* (before `super`) paints Rivers, Heart
     *      Tunnel, Surface Roots, and Child Trees into a
     *      `featureCache`, then flushes the cache into the chunk
     *      via `level.setBlock`.
     *   3. `super.applyBiomeDecoration` runs the vanilla feature
     *      list — only `wogor_forest` / `wogor_forest_water`
     *      configured on this biome. Their placement filter
     *      requires `minecraft:mud` at `pos − 1`, so they REJECT
     *      every column where step 1 or step 2 painted wood /
     *      leaves / water — Mother Tree, Child Trees, Surface
     *      Roots, painted rivers all become exclusion zones
     *      automatically. Vanilla trees only spawn on virgin mud
     *      between our painted features.
     *
     * Why this order matters (changed v8): if `super` ran first,
     * vanilla would do the full `TreeFeature.place()` work inside
     * Child Tree / Surface Root footprints, only to have it
     * overwritten by our paint flush moments later. By flushing
     * our paints *before* `super`, the same `mud-at-pos-1` filter
     * that already protects the Mother Tree now also protects
     * Child Trees, Surface Roots, and painted rivers — vanilla
     * never starts the doomed placement at all.
     *
     * Each chunk paints only its own 16 × 16 slice, so cross-chunk
     * writes never happen. Flush uses flag = 2 (UPDATE_CLIENTS
     * only) — the same flag vanilla tree features use after
     * `force` writes — so neighbour updates don't fire during
     * worldgen.
     *
     * Internal paint-cache write order (same as before): rivers →
     * heart tunnel → surface roots → child trees, so later
     * paints overwrite earlier ones at conflicting voxels.
     */
    override fun applyBiomeDecoration(
        level: net.minecraft.world.level.WorldGenLevel,
        chunk: ChunkAccess,
        structureManager: StructureManager,
    ) {
        val chunkX0 = chunk.pos.minBlockX
        val chunkZ0 = chunk.pos.minBlockZ
        val minY = chunk.minBuildHeight
        val maxY = chunk.maxBuildHeight
        val span = maxY - minY

        val featureCache = Array(16) { Array(16) { arrayOfNulls<BlockState>(span) } }
        // Order matters when two paints want to write the same
        // voxel:
        //   1. Rivers — vanilla-style 2D water channels radiating
        //      from the moat. Painted first; later overlays win
        //      at intersections.
        //   2. Heart tunnel — walked sphere-carver from the cave
        //      outward.
        //   3. Child Trees — focused tree features (trunk + canopy).
        //      Painted BEFORE surface roots (v9 reorder) so root
        //      paths reaching a child tree footprint see its wood
        //      already in the cache and the next pass skips them
        //      instead of gouging through.
        //   4. Surface Roots — thick dimension-wide log carving.
        //      Its paintTunnelPathInto skips cells that already
        //      hold a non-water block (child tree wood/leaves),
        //      but still overwrites water at river / heart-tunnel
        //      crossings so roots continue to read as bridges.
        paintRiversInto(featureCache, chunkX0, chunkZ0, minY, span)
        paintHeartTunnelInto(featureCache, chunkX0, chunkZ0, minY, span)
        paintChildTreesInto(featureCache, chunkX0, chunkZ0, minY, span)
        paintSurfaceRootsInto(featureCache, chunkX0, chunkZ0, minY, span)

        // Flush BEFORE vanilla decoration runs so the wogor_forest
        // placement filter sees our painted features as occupied.
        val mutable = BlockPos.MutableBlockPos()
        for (lx in 0..15) {
            val worldX = chunkX0 + lx
            val xPlane = featureCache[lx]
            for (lz in 0..15) {
                val worldZ = chunkZ0 + lz
                val col = xPlane[lz]
                for (i in 0 until span) {
                    val block = col[i] ?: continue
                    level.setBlock(mutable.set(worldX, minY + i, worldZ), block, 2)
                }
            }
        }

        // Now vanilla runs — sees our painted blocks via
        // `level.getBlockState`, and the placement filter rejects
        // every column where our paints occupy `pos − 1`.
        super.applyBiomeDecoration(level, chunk, structureManager)
    }

    /**
     * Surface mud height at `(wx, wz)`. Three layered terms:
     *
     *  - **Regional** — a coarse value-noise (period ~67 blocks) at large
     *    amplitude. Drives the big picture: broad regions of dry mud bars
     *    sitting above sea level vs. broad pools dipping well below it.
     *  - **Fine** — two-octave value-noise (periods ~22 / ~8) at small
     *    amplitude. Stops the regional shape from reading like flat plateaus.
     *  - **Hill** — smooth radial bump at the world origin carrying the Mother
     *    Tree mound up to [HILL_PEAK_Y].
     */
    private fun surfaceYAt(wx: Int, wz: Int): Int {
        val nRegional = valueNoise2(wx * 0.015, wz * 0.015) * REGIONAL_AMPLITUDE
        val n0 = valueNoise2(wx * 0.045, wz * 0.045)
        val n1 = valueNoise2(wx * 0.12 + 7.3, wz * 0.12 + 11.1) * 0.5
        val noiseFine = (n0 + n1) * FINE_AMPLITUDE
        val d = Math.sqrt((wx.toDouble() * wx + wz.toDouble() * wz))
        val hillLift = if (d >= HILL_RADIUS) 0.0
        else {
            val t = 1.0 - (d / HILL_RADIUS)
            val s = t * t * (3.0 - 2.0 * t)
            s * HILL_PEAK_LIFT
        }
        return (SURFACE_BASE_Y + nRegional + noiseFine + hillLift)
            .toInt().coerceIn(2, MAX_BUILD_Y - 4)
    }

    /** Base-column block at world Y `y`, given the local surface
     *  height. Water surface sits at the top of `y = SEA_LEVEL_Y − 1`
     *  block (i.e. the `y = SEA_LEVEL_Y` line) so the water surface IS
     *  sea level. All other water systems (moat, cave pool, rivers,
     *  heart tunnel) use the same convention. */
    private fun baseColumnBlock(y: Int, surface: Int): BlockState = when {
        y == 0 -> BEDROCK
        y <= surface -> MUD
        y < SEA_LEVEL_Y -> WATER
        else -> AIR
    }

    /**
     * The Mother Tree's hill, as a smooth function of distance from the
     * tree origin **plus noise** — a real hill the tree sits on, not a
     * sterile mathematical circle.
     *
     *   - **Rim domain-warp**: the effective distance from origin is
     *     perturbed by low-frequency 2D value-noise (±~9 blocks),
     *     deforming the otherwise-perfect circular plateau into an
     *     irregular natural outline.
     *   - **Plateau bumpiness**: higher-frequency noise adds ±1.5 blocks
     *     of undulation to the plateau top, so the inner area isn't dead
     *     flat.
     *   - **Wide smoothstep taper** from [TREE_MOUND_INNER_R] to
     *     [TREE_MOUND_OUTER_R] — the smoothstep curve plus the wider gap
     *     gives a long gentle blend into the natural terrain at the rim.
     *
     * Used by the column fill as `max(surfaceYAt, treeMoundContribution)`
     * and by the root painter to find the local hill surface as the
     * roots walk across it.
     */
    private fun treeMoundContribution(wx: Int, wz: Int): Int {
        val dx = wx - MOTHER_TREE_CX
        val dz = wz - MOTHER_TREE_CZ
        val baseDist = Math.sqrt(dx.toDouble() * dx + dz.toDouble() * dz)

        // Cheap pre-noise reject: a column further than (outer + worst-
        // case rim warp) can never have a mound contribution, so skip
        // even the noise call.
        if (baseDist > TREE_MOUND_OUTER_R + TREE_MOUND_RIM_NOISE_AMP) return Int.MIN_VALUE

        // Rim domain warp — break the perfect circle. Low-freq 2D
        // value-noise on world coords gives a globally consistent
        // perturbation, so adjacent chunks paint a continuous irregular
        // rim. Effective distance can shrink (pulling the plateau out)
        // or grow (clamping into the natural terrain earlier).
        val rimWarp = (valueNoise2(wx * TREE_MOUND_RIM_NOISE_FREQ,
            wz * TREE_MOUND_RIM_NOISE_FREQ) - 0.5) * 2.0 * TREE_MOUND_RIM_NOISE_AMP
        val effDist = (baseDist + rimWarp).coerceAtLeast(0.0)

        val rOuter = TREE_MOUND_OUTER_R
        if (effDist >= rOuter) return Int.MIN_VALUE

        // Plateau bumpiness — small higher-freq noise added to the peak
        // surface so the inner plateau has some natural undulation.
        val plateauBump = (valueNoise2(wx * TREE_MOUND_PLATEAU_NOISE_FREQ,
            wz * TREE_MOUND_PLATEAU_NOISE_FREQ) - 0.5) * 2.0 * TREE_MOUND_PLATEAU_NOISE_AMP
        val peakSurface = (HILL_PEAK_Y + TREE_MOUND_BONUS) + plateauBump

        if (effDist <= TREE_MOUND_INNER_R) return peakSurface.toInt()
        val t = (effDist - TREE_MOUND_INNER_R) / (rOuter - TREE_MOUND_INNER_R)
        val s = t * t * (3.0 - 2.0 * t)   // smoothstep
        val natural = surfaceYAt(wx, wz)
        return ((1.0 - s) * peakSurface + s * natural).toInt()
    }

    /**
     * Effective surface = max(natural hill, Mother Tree mound), with
     * the moat U-shape depression carved into the result.
     *
     * The moat is the only "geometric" central feature folded directly
     * into the surface field — that way it blends smoothly with the
     * rest of the terrain via the same baseColumnBlock / feature-cache
     * pipeline as everything else, and feature-cache writes (canopy,
     * roots, leaves) still win over baseBlock above the depressed
     * surface so the Mother Tree's branches and leaves survive intact
     * even though the moat overlaps part of the mound's outer slope.
     */
    private fun effectiveSurfaceYAt(wx: Int, wz: Int): Int {
        val natural = surfaceYAt(wx, wz)
        val mound = treeMoundContribution(wx, wz)
        val withoutMoat = if (mound > natural) mound else natural
        val moatY = moatBlendedSurface(wx, wz, withoutMoat)
        // Moat lowers the surface — never raises it. Picking the
        // minimum keeps the moat purely subtractive on the mound's
        // slope.
        return if (moatY != Int.MIN_VALUE && moatY < withoutMoat) moatY
        else withoutMoat
    }

    // ------------------------------------------------------------------------
    //   trees
    // ------------------------------------------------------------------------

    /**
     * For chunk `(nChunkX, nChunkZ)` decide which tree roots it seeds and paint
     * each tree's blocks into [target] (clipped to the target chunk's
     * footprint `[chunkX0..+15, chunkZ0..+15]`).
     *
     * Seeds `TREES_PER_CHUNK_MIN..TREES_PER_CHUNK_MAX` candidate trunk roots
     * per chunk; each candidate is independently rejected if it lands in a
     * pool (surface below sea level) or inside the Mother Tree exclusion
     * radius. The result is dense dry-land coverage with pools left open.
     */
    /**
     * Place **Child Trees** — dispersed scaled-down Mother Trees — across
     * the biome. Deterministic region-grid placement: the world is
     * divided into `CHILD_TREE_REGION_SIZE_CHUNKS × CHILD_TREE_REGION_SIZE_CHUNKS`
     * cells; each cell may host at most one Child Tree at a hash-picked
     * column within it (~80 % spawn rate per cell, the rest left as plain
     * forest). Each chunk checks itself + its 8 region neighbours so a
     * child tree near a region boundary still gets painted into adjacent
     * chunks.
     *
     * Per-tree skeleton is **cached** by world (x, z) so the same tree
     * is built exactly once and reused as multiple chunks paint slices
     * of it.
     */
    private fun paintChildTreesInto(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
    ) {
        val cChunkX = chunkX0 shr 4
        val cChunkZ = chunkZ0 shr 4
        val regionX = Math.floorDiv(cChunkX, CHILD_TREE_REGION_SIZE_CHUNKS)
        val regionZ = Math.floorDiv(cChunkZ, CHILD_TREE_REGION_SIZE_CHUNKS)

        for (dRegX in -1..1) for (dRegZ in -1..1) {
            val rX = regionX + dRegX
            val rZ = regionZ + dRegZ
            val regionSeed = hash32(rX, rZ, 7777)
            // ~80 % of regions spawn a child tree (skip when low nibble > 0xC).
            if ((regionSeed and 0xF) > 0xC) continue
            val withinX = (hash32(rX, rZ, 7778) ushr 1) and (CHILD_TREE_REGION_SIZE - 1)
            val withinZ = (hash32(rX, rZ, 7779) ushr 1) and (CHILD_TREE_REGION_SIZE - 1)
            val treeX = rX * CHILD_TREE_REGION_SIZE + withinX
            val treeZ = rZ * CHILD_TREE_REGION_SIZE + withinZ

            // **Inter-tree spacing rejection.** A neighbour region's
            // candidate within CHILD_TREE_MIN_SPACING blocks of ours
            // wins if it has the lower seed — deterministic per region
            // pair, doesn't depend on iteration order.
            if (!isChildTreeCandidateAccepted(rX, rZ, treeX, treeZ, regionSeed)) continue

            // Reject if too close to the Mother Tree (with margin).
            val originDistSq = treeX.toLong() * treeX + treeZ.toLong() * treeZ
            if (originDistSq < MOTHER_TREE_EXCLUSION_RADIUS_SQ.toLong() + 100L * 100) continue

            // Quick chunk-AABB reject.
            val nearestX = treeX.coerceIn(chunkX0, chunkX0 + 15)
            val nearestZ = treeZ.coerceIn(chunkZ0, chunkZ0 + 15)
            val ddx = nearestX - treeX
            val ddz = nearestZ - treeZ
            if (ddx * ddx + ddz * ddz > CHILD_TREE_MAX_REACH * CHILD_TREE_MAX_REACH) continue

            val surface = surfaceYAt(treeX, treeZ)
            // Skip extremely deep pools so the canopy isn't completely
            // submerged; trees still allowed in shallow water.
            if (surface < SEA_LEVEL_Y - 12) continue
            val baseY = surface + 1

            val skel = getChildTreeSkeleton(treeX, baseY, treeZ, regionSeed)

            paintSkeleton(target, chunkX0, chunkZ0, minY, span, skel, WOGOR_WOOD_Y)
            paintLeafTips(target, chunkX0, chunkZ0, minY, span, skel,
                blobRxz = 7, blobRy = 4)
            paintHangingVines(target, chunkX0, chunkZ0, minY, span,
                treeX, treeZ, skel.maxXZReach + 2,
                maxLen = 7, seed = regionSeed xor 0x73A1F4C7.toInt())
            paintChildTreeRoots(target, chunkX0, chunkZ0, minY, span,
                treeX, treeZ, baseY, regionSeed)
        }
    }

    /** Skeleton cache for Child Trees, keyed by world `(x, z)`. C2ME runs
     *  chunk gen concurrently, and many chunks paint slices of the same
     *  child tree — caching avoids redundant ~½-Mother-Tree skeleton
     *  builds. Entries are pure of caller state (deterministic from seed)
     *  so concurrent races on `computeIfAbsent` just dedupe the work. */
    private val childTreeSkeletonCache:
        java.util.concurrent.ConcurrentHashMap<Long, TreeSkeleton> =
            java.util.concurrent.ConcurrentHashMap()

    private fun getChildTreeSkeleton(
        treeX: Int, baseY: Int, treeZ: Int, seed: Int,
    ): TreeSkeleton {
        val key = (treeX.toLong() and 0xFFFFFFFFL) shl 32 or
            (treeZ.toLong() and 0xFFFFFFFFL)
        return childTreeSkeletonCache.computeIfAbsent(key) {
            buildChildTreeSkeleton(treeX, baseY, treeZ, seed)
        }
    }

    /** Acceptance check for a candidate Child Tree position. Walks the 8
     *  neighbour regions; if any has a candidate within
     *  [CHILD_TREE_MIN_SPACING] of our position AND a lower region seed,
     *  our candidate loses. Deterministic per (rX, rZ) pair regardless
     *  of evaluation order. */
    private fun isChildTreeCandidateAccepted(
        rX: Int, rZ: Int, candX: Int, candZ: Int, candSeed: Int,
    ): Boolean {
        val minDistSq = CHILD_TREE_MIN_SPACING.toLong() * CHILD_TREE_MIN_SPACING
        for (dx in -1..1) for (dz in -1..1) {
            if (dx == 0 && dz == 0) continue
            val nrX = rX + dx
            val nrZ = rZ + dz
            val nSeed = hash32(nrX, nrZ, 7777)
            if ((nSeed and 0xF) > 0xC) continue
            val nWithinX = (hash32(nrX, nrZ, 7778) ushr 1) and (CHILD_TREE_REGION_SIZE - 1)
            val nWithinZ = (hash32(nrX, nrZ, 7779) ushr 1) and (CHILD_TREE_REGION_SIZE - 1)
            val nX = nrX * CHILD_TREE_REGION_SIZE + nWithinX
            val nZ = nrZ * CHILD_TREE_REGION_SIZE + nWithinZ
            val ddx = (nX - candX).toLong()
            val ddz = (nZ - candZ).toLong()
            if (ddx * ddx + ddz * ddz >= minDistSq) continue
            // Neighbour is too close — lower seed wins; we lose if their
            // seed is smaller.
            if (nSeed < candSeed) return false
        }
        return true
    }

    /**
     * Build a Child Tree skeleton — a smaller variant of the Mother Tree
     * with **per-tree size, aspect, lean, gravity, and branching
     * variation** derived from independent seed hashes. So child trees
     * range from compact narrow specimens to broad sprawling ones, with
     * different leans and droop characteristics.
     */
    private fun buildChildTreeSkeleton(
        treeX: Int, baseY: Int, treeZ: Int, seed: Int,
    ): TreeSkeleton {
        val baseX = treeX.toDouble()
        val baseZ = treeZ.toDouble()
        val baseYD = baseY.toDouble()

        val sizeHash = hash01(seed, 0, 1000)
        val aspectHash = hash01(seed, 0, 1001)
        val leanHash = hash01(seed, 0, 1002)
        val gravityHash = hash01(seed, 0, 1003)
        val biasHash = hash01(seed, 0, 1004)
        val flareHash = hash01(seed, 0, 1005)

        // Overall scale: 0.55–0.90 of the Mother Tree's canopy radii.
        val scaleFactor = 0.55 + sizeHash * 0.35
        // Aspect: how flat or tall the canopy is. Tall trees have small
        // ryRatio (narrow tall crown); broad trees have larger ratio.
        val ryRatio = 0.18 + aspectHash * 0.22

        val canopyRx = 55.0 * scaleFactor
        val canopyRy = canopyRx * ryRatio
        val canopyCy = baseYD + 60.0 * scaleFactor + canopyRy * 0.7

        // Ellipsoid volume → attractor density target ~one per 22 blocks.
        val canopyVolume = 4.19 * canopyRx * canopyRx * canopyRy
        val attractorCount = (canopyVolume / 22.0).toInt().coerceIn(400, 2500)

        val maxThickness = (4.0 + sizeHash * 4.0).toInt()   // 4..8
        val buttressFlare = (maxThickness * (1.6 + flareHash * 0.6)).toInt()
        val buttressRange = canopyRy * 1.2 + 8.0

        return WogorTreeSkeleton.build(
            originX = baseX, originY = baseYD, originZ = baseZ,
            attractors = WogorTreeSkeleton.ellipsoidAttractors(
                cx = baseX, cy = canopyCy, cz = baseZ,
                rx = canopyRx, ry = canopyRy, rz = canopyRx,
                count = attractorCount, seed = seed,
            ),
            defaultDir = doubleArrayOf(0.0, 1.0, 0.0),
            attractionDist = 14.0 + scaleFactor * 10.0,
            killDist = 3.0 + scaleFactor * 2.0,
            stepSize = 3.0 + scaleFactor * 1.2,
            maxIterations = (80 + scaleFactor * 50).toInt(),
            branchBias = 0.10 + biasHash * 0.15,
            maxThickness = maxThickness,
            thicknessScale = 0.4,
            buttressFlare = buttressFlare,
            buttressRange = buttressRange,
            trunkLean = 0.20 + leanHash * 0.25,
            gravity = 0.08 + gravityHash * 0.12,
        )
    }

    /** Hand-coded buttress root arms for Child Trees — a half-scale
     *  version of the Mother Tree's roots (three-phase rise / walk /
     *  plunge), rendered through the SDF capsule rasterizer. */
    private fun paintChildTreeRoots(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        treeX: Int, treeZ: Int, baseY: Int, seed: Int,
    ) {
        val rootCount = 8 + ((hash32(seed, 0, 51) ushr 1) and 0x3)   // 8..11
        val baseXD = treeX.toDouble()
        val baseZD = treeZ.toDouble()
        val baseYD = baseY.toDouble()
        for (i in 0 until rootCount) {
            val angleHash = hash01(seed, i, 51)
            val angle = i * (Math.PI * 2.0 / rootCount) +
                (angleHash - 0.5) * (Math.PI * 2.0 / rootCount) * 0.4
            val cosA = Math.cos(angle); val sinA = Math.sin(angle)
            val perpX = -sinA; val perpZ = cosA

            val reach = 25 + ((hash32(seed, i, 52) ushr 1) and 0x13)   // 25..43
            val plungeDepth = 18 + ((hash32(seed, i, 53) ushr 1) and 0xF) // 18..33
            val wobblePhase = hash01(seed, i, 55) * Math.PI * 2.0
            val wobbleAmp = 1.0 + hash01(seed, i, 56) * 1.5
            val walkUndulationPhase = hash01(seed, i, 57) * Math.PI * 2.0
            val emergenceDY = (hash32(seed, i, 58) ushr 1 and 0x3).toDouble()
            val riseEnd = 0.12 + hash01(seed, i, 60) * 0.06
            val walkEnd = 0.70 + hash01(seed, i, 61) * 0.08

            val startX = baseXD + cosA * 3.0
            val startZ = baseZD + sinA * 3.0
            val startY = baseYD + emergenceDY + 1.0

            val steps = reach
            var prevX = startX; var prevY = startY; var prevZ = startZ
            var prevR = CHILD_TREE_ROOT_BASE_RADIUS
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                val r = t * reach
                val wob = Math.sin(t * Math.PI * 3.0 + wobblePhase) * wobbleAmp
                val px = startX + r * cosA + perpX * wob
                val pz = startZ + r * sinA + perpZ * wob

                val py = when {
                    t < riseEnd -> {
                        val rT = t / riseEnd
                        startY + 2.5 * (1.0 - (1.0 - rT) * (1.0 - rT))
                    }
                    t < walkEnd -> {
                        val hillY = effectiveSurfaceYAt(px.toInt(), pz.toInt()).toDouble()
                        val wT = (t - riseEnd) / (walkEnd - riseEnd)
                        val bumpRaw = Math.sin(wT * Math.PI * 5.0 + walkUndulationPhase)
                        val bump = Math.max(0.0, bumpRaw) * 4.0
                        hillY + 1.0 + bump
                    }
                    else -> {
                        val hillHere = effectiveSurfaceYAt(px.toInt(), pz.toInt()).toDouble()
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

                paintTreeSegment(target, chunkX0, chunkZ0, minY, span,
                    TreeSegment(prevX, prevY, prevZ, px, py, pz, prevR, curR),
                    WOGOR_WOOD_Y)
                prevX = px; prevY = py; prevZ = pz; prevR = curR
            }
        }
    }

    // ------------------------------------------------------------------------
    //   skeleton painting (used by both small Wogor trees and the Mother Tree)
    // ------------------------------------------------------------------------

    /** Rasterise every [TreeSegment] in [skeleton] into [target] as thick
     *  woody blocks. */
    private fun paintSkeleton(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        skeleton: TreeSkeleton, wood: BlockState,
    ) {
        for (seg in skeleton.segments) {
            paintTreeSegment(target, chunkX0, chunkZ0, minY, span, seg, wood)
        }
    }

    /**
     * Rasterize a tree segment as a **tapered-capsule SDF with bark noise**
     * (Cepero / Voxel Farm). For each voxel in the segment's bounding box
     * clipped to the chunk:
     *
     *  1. Project the voxel centre onto the segment line → `t ∈ [0, 1]`,
     *     closest-point `C` on the line.
     *  2. Lerp the segment radius `r(t) = r0 + (r1 - r0)·t` (the
     *     pipe-model continuous taper).
     *  3. Compute the **cone-surface point** `S = C + (V - C)/|V - C| · r`
     *     — where the shortest line from `V` toward `C` meets the smooth
     *     cone surface.
     *  4. Sample 3D smooth-noise at `S` and add its perturbation to the
     *     radius: `r_eff = r + bark(S)`.
     *  5. Voxel is wood if `|V - C| ≤ r_eff`.
     *
     * Sampling noise at the **surface point** (not at the voxel) is the
     * key: voxels at the same angular position around the segment get the
     * same noise sample → bark varies both **longitudinally** (along the
     * segment) and **angularly** (around the trunk), producing realistic
     * vertical-grain bark rather than random freckles.
     */
    private fun paintTreeSegment(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        seg: TreeSegment, wood: BlockState,
    ) {
        val p0x = seg.startX; val p0y = seg.startY; val p0z = seg.startZ
        val p1x = seg.endX; val p1y = seg.endY; val p1z = seg.endZ
        val r0 = seg.startRadius
        val r1 = seg.endRadius
        val rMax = Math.max(r0, r1)
        // Worst-case bark amplitude — needed to size the AABB so we don't
        // miss any voxel the perturbation could push the surface to.
        val barkAmpMax = barkAmplitudeFor(rMax)

        // World-space AABB of the segment + radius + worst-case bark.
        val pad = rMax + barkAmpMax + 1.0
        val minWX = Math.floor(Math.min(p0x, p1x) - pad).toInt()
        val maxWX = Math.ceil(Math.max(p0x, p1x) + pad).toInt()
        val minWY = Math.floor(Math.min(p0y, p1y) - pad).toInt()
        val maxWY = Math.ceil(Math.max(p0y, p1y) + pad).toInt()
        val minWZ = Math.floor(Math.min(p0z, p1z) - pad).toInt()
        val maxWZ = Math.ceil(Math.max(p0z, p1z) + pad).toInt()

        // Clip against chunk XZ window and build-height span.
        val cMinX = Math.max(minWX, chunkX0)
        val cMaxX = Math.min(maxWX, chunkX0 + 15)
        val cMinZ = Math.max(minWZ, chunkZ0)
        val cMaxZ = Math.min(maxWZ, chunkZ0 + 15)
        val cMinY = Math.max(minWY, minY)
        val cMaxY = Math.min(maxWY, minY + span - 1)
        if (cMinX > cMaxX || cMinY > cMaxY || cMinZ > cMaxZ) return

        // Segment direction & length².
        val ex = p1x - p0x; val ey = p1y - p0y; val ez = p1z - p0z
        val segLenSq = ex * ex + ey * ey + ez * ez

        if (segLenSq < 1.0e-6) {
            // Degenerate point — render as a single bumpy sphere.
            paintSdfSphere(target, chunkX0, chunkZ0, minY, span,
                cMinX, cMaxX, cMinY, cMaxY, cMinZ, cMaxZ,
                p0x, p0y, p0z, r0, wood)
            return
        }

        // Per-thread bark-hash cache acquired ONCE for the whole
        // segment, shared across every inner-voxel valueNoise3D
        // call (see [paintLeafBlob] for the same hoist). Also
        // precompute the divide reciprocal so the inner loop's
        // segment-projection is multiplies only.
        val noiseCache = barkCache.get()
        val invSegLenSq = 1.0 / segLenSq

        for (wy in cMinY..cMaxY) {
            val vy = wy + 0.5
            // Per-wy hoist: the segment-projection dot product
            // px·ex + py·ey + pz·ez splits into (px·ex) +
            // (py·ey) + (pz·ez). The latter two depend only on
            // wy and wz, so we lift them out of the innermost loop.
            val py = vy - p0y
            val pyEy = py * ey
            for (wz in cMinZ..cMaxZ) {
                val vz = wz + 0.5
                val pz = vz - p0z
                val pyEyPlusPzEz = pyEy + pz * ez
                for (wx in cMinX..cMaxX) {
                    val vx = wx + 0.5
                    // Project voxel centre onto segment.
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
                    if (distSq > rPlusMax * rPlusMax) continue   // cheap reject (outside)

                    // Inside-certain skip: voxels with distSq ≤
                    // (r − barkAmp)² are inside the cylinder even
                    // at the LEAST favorable bark sample (one full
                    // amplitude inward). For thick trunk segments
                    // this is the majority of survivors of the
                    // cheap reject — skipping sqrt + valueNoise3D
                    // for them is pure free time. Visual output
                    // bit-identical: any voxel that would have
                    // been placed under any noise realisation is
                    // still placed here, and any voxel rejected
                    // under any noise realisation still gets the
                    // noise check below.
                    val rMinusMin = r - barkAmp
                    if (rMinusMin > 0.0 && distSq <= rMinusMin * rMinusMin) {
                        placeWoody(target, chunkX0, chunkZ0, minY, span, wx, wy, wz, wood)
                        continue
                    }

                    // Cone-surface point (perpendicular direction × radius).
                    val dist = Math.sqrt(distSq)
                    val sX: Double; val sY: Double; val sZ: Double
                    if (dist > 1.0e-6) {
                        val inv = r / dist
                        sX = cX + ddx * inv
                        sY = cY + ddy * inv
                        sZ = cZ + ddz * inv
                    } else {
                        sX = cX; sY = cY; sZ = cZ
                    }
                    val bark = (valueNoise3D(noiseCache, sX * BARK_FREQ, sY * BARK_FREQ, sZ * BARK_FREQ)
                        - 0.5) * 2.0 * barkAmp
                    val rEff = r + bark
                    if (distSq <= rEff * rEff) {
                        placeWoody(target, chunkX0, chunkZ0, minY, span, wx, wy, wz, wood)
                    }
                }
            }
        }
    }

    /** SDF-rendered bumpy sphere — degenerate-segment fallback for
     *  [paintTreeSegment] when the parent and child positions collapse. */
    private fun paintSdfSphere(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        cMinX: Int, cMaxX: Int, cMinY: Int, cMaxY: Int, cMinZ: Int, cMaxZ: Int,
        cx: Double, cy: Double, cz: Double, r: Double, wood: BlockState,
    ) {
        val barkAmp = barkAmplitudeFor(r)
        val maxR = r + barkAmp
        val maxRSq = maxR * maxR
        for (wy in cMinY..cMaxY) for (wz in cMinZ..cMaxZ) for (wx in cMinX..cMaxX) {
            val vx = wx + 0.5; val vy = wy + 0.5; val vz = wz + 0.5
            val ddx = vx - cx; val ddy = vy - cy; val ddz = vz - cz
            val distSq = ddx * ddx + ddy * ddy + ddz * ddz
            if (distSq > maxRSq) continue
            val dist = Math.sqrt(distSq)
            val sX: Double; val sY: Double; val sZ: Double
            if (dist > 1.0e-6) {
                val inv = r / dist
                sX = cx + ddx * inv
                sY = cy + ddy * inv
                sZ = cz + ddz * inv
            } else {
                sX = cx; sY = cy; sZ = cz
            }
            val bark = (valueNoise3D(sX * BARK_FREQ, sY * BARK_FREQ, sZ * BARK_FREQ)
                - 0.5) * 2.0 * barkAmp
            val rEff = r + bark
            if (distSq <= rEff * rEff) {
                placeWoody(target, chunkX0, chunkZ0, minY, span, wx, wy, wz, wood)
            }
        }
    }

    /** Bark amplitude scaled to the local radius so a 10-block-radius trunk
     *  gets visible 1-2-block knobs while a 1-block-radius twig gets only
     *  a sub-block jitter (preventing erasure of thin tips). */
    private fun barkAmplitudeFor(r: Double): Double =
        Math.max(0.0, r - 0.5) * 0.18 + 0.25

    /** 3D spherical cluster. `radius` 0 = single block; ≥1 = sphere of that
     *  radius. Used to give branches/trunk their thickness. */
    private fun paintWoodCluster3D(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        cx: Int, cy: Int, cz: Int, radius: Int, block: BlockState,
    ) {
        if (radius <= 0) {
            placeWoody(target, chunkX0, chunkZ0, minY, span, cx, cy, cz, block)
            return
        }
        // Fuzz factor: r*r + r softens the cubic look of pure integer radii.
        val rSq = radius * radius + radius
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                for (dz in -radius..radius) {
                    if (dx * dx + dy * dy + dz * dz > rSq) continue
                    placeWoody(target, chunkX0, chunkZ0, minY, span,
                        cx + dx, cy + dy, cz + dz, block)
                }
            }
        }
    }

    /** Paint a leaf-blob cluster at each terminal tip of [skeleton]. The
     *  tips are spread organically through the canopy volume by the
     *  attractor cloud, so together they produce a wide branching crown
     *  rather than a single dense mass. */
    private fun paintLeafTips(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        skeleton: TreeSkeleton, blobRxz: Int, blobRy: Int,
    ) {
        for (tip in skeleton.tips) {
            paintLeafBlob(target, chunkX0, chunkZ0, minY, span,
                tip.x, tip.y, tip.z, blobRxz, blobRy)
        }
    }

    /** Paint a noise-perturbed ellipsoidal blob of mangrove leaves
     *  (Cepero's crown approach, adapted for voxels). The ideal
     *  ellipsoid surface `nd² ≤ 1` is perturbed by **two octaves** of
     *  3D smooth-noise:
     *
     *  - **Low frequency** breaks the smooth ellipsoid into clumps the
     *    size of a few blocks — so the canopy reads as a cluster of
     *    overlapping foliage masses, not a single glossy sphere.
     *  - **High frequency** breaks the surface at the per-voxel level,
     *    so the rim of the canopy reads as individual leaves (some
     *    voxels in, some out) rather than a clean ellipsoid edge.
     *
     *  The expanded AABB margin covers the +0.5 perturbation envelope.
     *  Leaves still only write into empty cells.
     */
    private fun paintLeafBlob(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        cx: Int, cy: Int, cz: Int, rxz: Int, ry: Int,
    ) {
        val ryEff = ry.coerceAtLeast(1)
        // Margin = noise-rim allowance (~25% of radius for threshold
        // perturbation) + domain-warp allowance (LEAF_WARP_AMP — the warp
        // can pull a voxel's test position inward, so voxels that far
        // outside the nominal radius can still pass the threshold).
        val xzMargin = ((rxz * 0.25).toInt() + LEAF_WARP_AMP_INT).coerceAtLeast(1)
        val yMargin = ((ryEff * 0.25).toInt() + LEAF_WARP_AMP_INT).coerceAtLeast(1)
        // Clip the blob AABB against the chunk's XZ window so we don't
        // iterate distant voxels just to throw them away — important now
        // that the blob radius is bigger.
        val xzRange = rxz + xzMargin
        val yRange = ryEff + yMargin
        val dxMin = Math.max(-xzRange, chunkX0 - cx)
        val dxMax = Math.min(xzRange, chunkX0 + 15 - cx)
        val dzMin = Math.max(-xzRange, chunkZ0 - cz)
        val dzMax = Math.min(xzRange, chunkZ0 + 15 - cz)
        if (dxMin > dxMax || dzMin > dzMax) return

        // ---- Pre-warp early-out --------------------------------------
        // The dominant cost in this loop is the 3 valueNoise3D calls
        // that compute the per-voxel warp displacement plus the 2
        // valueNoise3D calls that compute the threshold perturbation —
        // 5 calls × 8 bark3Hash lookups = 40 hash evaluations per
        // voxel we iterate, regardless of whether the voxel ends up
        // placing a leaf. Spark consistently shows valueNoise3D +
        // bark3Hash dominating Worker-Main self-time during chunkgen.
        //
        // Idea: bound below the post-warp nd² over ALL possible warp
        // choices in [-LEAF_WARP_AMP, +LEAF_WARP_AMP]³. For a single
        // axis with coordinate c, radius r, and warp range W:
        //     min_{w ∈ [-W,W]} ((c + w) / r)² = max(0, |c| − W)² / r²
        // Summing across axes yields nd2Min, the tightest possible
        // post-warp nd². If nd2Min already exceeds LEAF_THRESHOLD_MAX,
        // no warp realization can pull this voxel inside the rim —
        // skip without computing any noise. Correctness: this is a
        // strictly weaker condition than the existing post-warp check
        // below, so it never excludes a voxel that would have been
        // placed.
        //
        // The dx- and dy-independent parts are hoisted so an entire
        // dz row (or dy slice) can short-circuit before entering the
        // inner loop. Reciprocals are precomputed to keep the early-
        // out fully multiplies-only.
        val rxzRecipSq = 1.0 / (rxz.toDouble() * rxz.toDouble())
        val ryRecipSq = 1.0 / (ryEff.toDouble() * ryEff.toDouble())
        val warpAmpInt = LEAF_WARP_AMP_INT

        // Acquire the per-thread bark-hash cache ONCE for the
        // whole blob; pass it to every valueNoise3D call below.
        // The cache-taking overload (added alongside this hoist)
        // skips its own barkCache.get(), so each candidate voxel
        // saves 5 ThreadLocal lookups vs paying them per noise
        // eval. Across the chunkgen pool this collapses ~200M
        // TL.get's per ~200 s sample into a few thousand blob
        // calls.
        val noiseCache = barkCache.get()

        for (dx in dxMin..dxMax) {
            val effDx = Math.max(0, Math.abs(dx) - warpAmpInt)
            val xComponent = effDx * effDx * rxzRecipSq
            // If the X-axis component alone exceeds the threshold,
            // every (dy, dz) pair in this dx slice is doomed.
            if (xComponent > LEAF_THRESHOLD_MAX) continue

            for (dy in -yRange..yRange) {
                val effDy = Math.max(0, Math.abs(dy) - warpAmpInt)
                val xyComponent = xComponent + effDy * effDy * ryRecipSq
                // Whole dz row short-circuit.
                if (xyComponent > LEAF_THRESHOLD_MAX) continue

                for (dz in dzMin..dzMax) {
                    val effDz = Math.max(0, Math.abs(dz) - warpAmpInt)
                    val nd2Min = xyComponent + effDz * effDz * rxzRecipSq
                    if (nd2Min > LEAF_THRESHOLD_MAX) continue

                    val wx = cx + dx; val wy = cy + dy; val wz = cz + dz

                    // Domain warp — sample three independent low-freq
                    // noises to displace the voxel's test position by up
                    // to ±LEAF_WARP_AMP blocks. The apparent centre of
                    // the ellipsoid shifts per voxel, pulling the blob
                    // into lobes / concavities / peninsulas instead of a
                    // smooth ovular shape with a noisy rim.
                    val warpX = (valueNoise3D(noiseCache,
                        wx * LEAF_WARP_FREQ, wy * LEAF_WARP_FREQ, wz * LEAF_WARP_FREQ
                    ) - 0.5) * 2.0 * LEAF_WARP_AMP
                    val warpY = (valueNoise3D(noiseCache,
                        wx * LEAF_WARP_FREQ + 71.3, wy * LEAF_WARP_FREQ, wz * LEAF_WARP_FREQ
                    ) - 0.5) * 2.0 * LEAF_WARP_AMP
                    val warpZ = (valueNoise3D(noiseCache,
                        wx * LEAF_WARP_FREQ, wy * LEAF_WARP_FREQ + 71.3, wz * LEAF_WARP_FREQ
                    ) - 0.5) * 2.0 * LEAF_WARP_AMP

                    val ndx = (dx + warpX) / rxz
                    val ndy = (dy + warpY) / ryEff
                    val ndz = (dz + warpZ) / rxz
                    val nd2 = ndx * ndx + ndy * ndy + ndz * ndz
                    // Post-warp early-out: anything outside the worst-case rim.
                    if (nd2 > LEAF_THRESHOLD_MAX) continue

                    val low = valueNoise3D(noiseCache, wx * LEAF_LOW_FREQ, wy * LEAF_LOW_FREQ, wz * LEAF_LOW_FREQ)
                    val high = valueNoise3D(noiseCache, wx * LEAF_HIGH_FREQ, wy * LEAF_HIGH_FREQ, wz * LEAF_HIGH_FREQ)
                    val perturb = (low - 0.5) * 2.0 * LEAF_LOW_AMP +
                        (high - 0.5) * 2.0 * LEAF_HIGH_AMP
                    val threshold = 1.0 + perturb
                    if (nd2 > threshold) continue
                    placeLeaf(target, chunkX0, chunkZ0, minY, span, wx, wy, wz)
                }
            }
        }
    }

    /**
     * Hang vine columns below the lowest leaves of a canopy whose XZ footprint
     * is centred at `(cx, cz)` with radius [footprintR]. For each rim column
     * (outer ring of the footprint) that has a leaf, hangs a deterministic
     * vine column of 1..[maxLen] blocks below. The cardinal face of each vine
     * is chosen from the deterministic hash so neighbouring vines vary.
     */
    private fun paintHangingVines(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        cx: Int, cz: Int, footprintR: Int, maxLen: Int, seed: Int,
    ) {
        // No rim filter — branching canopies have leaves at irregular XZ
        // positions, so we scan the full footprint and let the per-column
        // "has a leaf above?" check do the gating. Density is still sparse
        // via the per-column hash.
        val outerR2 = footprintR * footprintR
        for (dx in -footprintR..footprintR) {
            for (dz in -footprintR..footprintR) {
                val d2 = dx * dx + dz * dz
                if (d2 > outerR2) continue
                val wx = cx + dx
                val wz = cz + dz
                val lx = wx - chunkX0
                val lz = wz - chunkZ0
                if (lx !in 0..15 || lz !in 0..15) continue
                val h = hash32(wx, wz, seed)
                if (h and 0x7 != 0) continue   // ~12.5% of leaf columns get vines
                val col = target[lx][lz]
                // Find the lowest leaf in this column — that's where we hang from.
                var lowestLeafLY = -1
                for (ly in 0 until span) {
                    if (col[ly] === MANGROVE_LEAVES) { lowestLeafLY = ly; break }
                }
                if (lowestLeafLY < 0) continue
                // Effective surface for this column. Vines below the
                // water surface would overwrite water in the merge pass,
                // since features override base blocks. Stop the vine
                // column when it would hang into water/terrain.
                val effSurface = effectiveSurfaceYAt(wx, wz)
                val waterTop = if (effSurface >= SEA_LEVEL_Y) effSurface else SEA_LEVEL_Y
                val len = (1 + ((h ushr 3) and 0x7)).coerceAtMost(maxLen)
                val vineState = VINE_BLOCKS[((h ushr 8) and 0x3)]
                for (v in 1..len) {
                    val vy = lowestLeafLY - v
                    if (vy < 0) break
                    if (col[vy] != null) break
                    val worldY = vy + minY
                    if (worldY <= waterTop) break   // would overwrite water/mud
                    col[vy] = vineState
                }
            }
        }
    }

    /**
     * Write a woody-feature block (log, wood, root) into [target]. Woody
     * features override leaves (so a branch passing through a leaf wins) but
     * lose to other woody features (first writer wins on logs vs. logs). The
     * mud/water/air merge happens later in the per-column fill.
     */
    private fun placeWoody(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        wx: Int, wy: Int, wz: Int, block: BlockState,
    ) {
        val lx = wx - chunkX0
        val lz = wz - chunkZ0
        val ly = wy - minY
        if (lx !in 0..15 || lz !in 0..15) return
        if (ly !in 0 until span) return
        val col = target[lx][lz]
        val existing = col[ly]
        if (existing == null || existing === MANGROVE_LEAVES) {
            col[ly] = block
        }
    }

    /** Write a leaf block into [target]. Leaves are the lowest-priority
     *  feature — they only fill empty cells. **0.1 % of leaves are sea
     *  lanterns** (1/1024 ≈ 0.098 %, the closest power of two to 0.1 %),
     *  decided by hashing the world position so the placement is
     *  deterministic and stable across runs / chunk seeds. */
    private fun placeLeaf(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        wx: Int, wy: Int, wz: Int,
    ) {
        val lx = wx - chunkX0
        val lz = wz - chunkZ0
        val ly = wy - minY
        if (lx !in 0..15 || lz !in 0..15) return
        if (ly !in 0 until span) return
        val col = target[lx][lz]
        if (col[ly] != null) return
        // 0.1 % chance of a sea lantern lighting up the canopy.
        col[ly] = if ((hash32(wx, wy, wz) and 0x3FF) == 0) SEA_LANTERN
        else MANGROVE_LEAVES
    }

    /** Deterministic hash → [0, 1). */
    private fun hash01(seed: Int, k1: Int, k2: Int): Double =
        (hash32(seed, k1, k2) and 0x7FFFFFFF) / 2147483648.0

    // ------------------------------------------------------------------------
    //   surface roots
    // ------------------------------------------------------------------------

    /**
     * Paint Mother-Tree-style sprawling surface roots as **path-walking
     * sphere carvers**, identical in spirit to vanilla `CaveWorldCarver`
     * — except the carved volume is *filled* with wogor wood instead of
     * being emptied.
     *
     * Why path walking rather than noise-thresholding (the previous
     * attempt): a ridged-noise field gives "is this cell a root?" as a
     * 0/1 mask, and the mask is only as continuous as the noise allows.
     * In practice multi-octave ridge masks fragment into isolated dashes
     * (two ridge fields must peak simultaneously — they rarely co-align
     * for more than a few blocks at a time), and even single-octave
     * masks form a regular fence of parallel curves. Neither reads as
     * tunnels — they read as "scattered blobs" or "walls". Vanilla
     * cave carvers solve this by *walking* a 3D path one short step at
     * a time, with smooth yaw + pitch perturbation, and carving a
     * sphere at every step. Adjacent steps are always one step apart
     * (here 1.5 blocks) so their radius-3 spheres always overlap into
     * one continuous tube — no gap is possible.
     *
     * ## Per-region paths
     *
     * The world is tiled by [TUNNEL_REGION_SIZE_CHUNKS]-chunk square
     * regions. Each region deterministically spawns one main tunnel
     * plus 0–3 branches that fork off at random midpoints via
     * [getTunnelPaths] / [buildTunnelPaths]; every path's start XZ /
     * start Y / start direction / per-step direction nudges, plus its
     * Y oscillation amplitude / period / phase, are all hashes of
     * `(regionX, regionZ, pathIdx)`. Paths are cached on the generator
     * so multiple chunks painting slices of the same tunnel pay the
     * build cost once. Concurrent C2ME workers race harmlessly on
     * `putIfAbsent` — the build function is pure of caller state.
     *
     * ## Per-chunk painting
     *
     * Each chunk scans the ±[TUNNEL_REGION_SEARCH] regions around it
     * (the worst-case reach of a path can stretch that far), fetches
     * the cached path, and rasterises a radius-[ROOT_TUBE_RADIUS]
     * sphere at every path point that falls within an AABB of this
     * chunk. Points outside the AABB are O(1) rejected.
     *
     * ## Pipeline placement
     *
     * Runs in [applyBiomeDecoration] after the vanilla forest features
     * have placed, so a tube passing through a vanilla canopy
     * overwrites the canopy's leaves with wood — the cave-tunnel
     * "carves through everything" semantics the user asked for. The
     * Mother Tree exclusion below stops tunnels from gouging up the
     * central trunk; a soft fade-in mask near the exclusion edge keeps
     * the boundary from being a hard cliff.
     *
     * Finally [paintRootVines] hangs short vine curtains off the
     * topmost airborne sphere caps.
     */
    private fun paintSurfaceRootsInto(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
    ) {
        val cChunkX = chunkX0 shr 4
        val cChunkZ = chunkZ0 shr 4
        val regionX = Math.floorDiv(cChunkX, TUNNEL_REGION_SIZE_CHUNKS)
        val regionZ = Math.floorDiv(cChunkZ, TUNNEL_REGION_SIZE_CHUNKS)

        for (dRegX in -TUNNEL_REGION_SEARCH..TUNNEL_REGION_SEARCH) {
            for (dRegZ in -TUNNEL_REGION_SEARCH..TUNNEL_REGION_SEARCH) {
                val rX = regionX + dRegX
                val rZ = regionZ + dRegZ
                val paths = getTunnelPaths(rX, rZ)
                for (path in paths) {
                    paintTunnelPathInto(target, chunkX0, chunkZ0, minY, span, path)
                }
            }
        }

        paintRootVines(target, chunkX0, chunkZ0, minY, span)
    }

    /** Cached tunnel paths, keyed by region (x, z). The value is the
     *  array of all paths for that region — index 0 is the main tunnel,
     *  the rest are branches that fork off mid-walk. An empty array
     *  represents a "sparse gap" region with no tunnel network.
     *  [ConcurrentHashMap] so C2ME workers can race harmlessly. */
    private val tunnelPathCache:
        java.util.concurrent.ConcurrentHashMap<Long, Array<TunnelPath>> =
            java.util.concurrent.ConcurrentHashMap()

    /** Get every tunnel path that originates in region `(regionX,
     *  regionZ)` — the main tunnel plus its branches. Empty array means
     *  the region is a sparse gap with no tunnels. */
    private fun getTunnelPaths(regionX: Int, regionZ: Int): Array<TunnelPath> {
        val key = (regionX.toLong() and 0xFFFFFFFFL) shl 32 or
            (regionZ.toLong() and 0xFFFFFFFFL)
        val cached = tunnelPathCache[key]
        if (cached != null) return cached
        val built = buildTunnelPaths(regionX, regionZ)
        val race = tunnelPathCache.putIfAbsent(key, built) ?: built
        return race
    }

    /**
     * Construct every tunnel path that originates in region `(regionX,
     *  regionZ)` — one main tunnel plus a handful of branches that fork
     *  off the main at random midpoints.
     *
     * Pure function of `(regionX, regionZ)`: deterministic from the
     * seed, thread-safe to call concurrently.
     *
     * Returns an empty array when the region is a "sparse gap" so the
     * dimension reads as a busy *network* of tunnels with occasional
     * clear patches, not a perfectly uniform grid.
     */
    private fun buildTunnelPaths(regionX: Int, regionZ: Int): Array<TunnelPath> {
        val seed = hash32(regionX, regionZ, 0x70F1FA52.toInt())
        if ((seed and 0xF) == 0) return EMPTY_TUNNEL_ARRAY   // ~6 % blank

        // Start XZ uniformly within the region.
        val startWX = regionX * TUNNEL_REGION_SIZE +
            (hash32(regionX, regionZ, 1) and (TUNNEL_REGION_SIZE - 1))
        val startWZ = regionZ * TUNNEL_REGION_SIZE +
            (hash32(regionX, regionZ, 2) and (TUNNEL_REGION_SIZE - 1))

        // Tunnels never originate inside the Mother Tree exclusion — that
        // column belongs to the trunk + canopy painting in fillFromNoise.
        val startDistSq = startWX.toLong() * startWX + startWZ.toLong() * startWZ
        if (startDistSq < TUNNEL_MOTHER_TREE_EXCLUSION_SQ) return EMPTY_TUNNEL_ARRAY

        val startSurface = surfaceYAt(startWX, startWZ)
        val startX = startWX.toDouble() + 0.5
        val startY = startSurface.toDouble()
        val startZ = startWZ.toDouble() + 0.5
        val startYaw = hash01(seed, 3, 0) * Math.PI * 2.0
        val startPitch = (hash01(seed, 4, 0) - 0.5) * 0.4

        val main = walkPath(
            regionX, regionZ, pathIdx = 0,
            startX, startY, startZ, startYaw, startPitch,
            maxSteps = TUNNEL_MAX_STEPS,
        )

        // Try to fork a branch every TUNNEL_BRANCH_STRIDE main-path
        // steps after a small warm-up. Each candidate's seed gates
        // whether it actually spawns and supplies the new direction,
        // so branches are deterministic in both presence and shape.
        val results = ArrayList<TunnelPath>(1 + TUNNEL_MAX_STEPS / TUNNEL_BRANCH_STRIDE)
        results.add(main)
        val mainPts = main.points
        var stepIdx = TUNNEL_BRANCH_FIRST_STEP
        var branchIdx = 0
        while (stepIdx < main.count - 4) {
            val branchSeed = hash32(regionX, regionZ, 300 + branchIdx)
            // Roughly 1-in-4 candidate steps spawn a branch — enough to
            // make the network feel branched without flooding the chunk
            // with redundant tube paint.
            if ((branchSeed and 0x3) == 0) {
                val bx = mainPts[stepIdx * 3 + 0].toDouble() + 0.5
                val by = mainPts[stepIdx * 3 + 1].toDouble()
                val bz = mainPts[stepIdx * 3 + 2].toDouble() + 0.5

                // Local direction of main path AT this step (use the
                // segment to the next point). Branch fans off with a
                // hard yaw offset of ±π/3 .. ±2π/3 so it visibly
                // diverges from the trunk rather than running parallel.
                val nx = mainPts[(stepIdx + 1) * 3 + 0]
                val nz = mainPts[(stepIdx + 1) * 3 + 2]
                val baseYaw = Math.atan2(
                    (nz - mainPts[stepIdx * 3 + 2]).toDouble(),
                    (nx - mainPts[stepIdx * 3 + 0]).toDouble(),
                )
                val sign = if ((branchSeed and 0x10) == 0) 1.0 else -1.0
                val yawOffset = sign * (Math.PI / 3.0 +
                    ((branchSeed ushr 5) and 0xFF) / 256.0 * (Math.PI / 3.0))
                val branchYaw = baseYaw + yawOffset
                val branchPitch = (((branchSeed ushr 13) and 0xFFFF) /
                    65536.0 - 0.5) * 0.4

                val branchLen = TUNNEL_BRANCH_MIN_STEPS +
                    ((branchSeed ushr 21) and 0xFF) %
                    (TUNNEL_BRANCH_MAX_STEPS - TUNNEL_BRANCH_MIN_STEPS + 1)

                results.add(
                    walkPath(
                        regionX, regionZ, pathIdx = 1 + branchIdx,
                        bx, by, bz, branchYaw, branchPitch,
                        maxSteps = branchLen,
                    )
                )
            }
            stepIdx += TUNNEL_BRANCH_STRIDE
            branchIdx++
        }
        return results.toTypedArray()
    }

    /**
     * Walk one tunnel path from a starting position + direction.
     * Extracted so the main path and each branch share the exact same
     * step semantics — they differ only in start state and length.
     *
     * `pathIdx` distinguishes the different paths within the same
     * region for the per-step turn-nudge hash so two paths sharing the
     * same region don't replay identical direction wobbles.
     *
     * The Y soft-target oscillation has its **amplitude, period, and
     * phase varied per-path** via a seed derived from
     * `(regionX, regionZ, pathIdx)`. Tunnels therefore reach a range of
     * heights instead of all tracing the same sine wave — some stay
     * mostly buried, some arc much higher than the canopy, and they
     * peak at different positions along their length.
     */
    private fun walkPath(
        regionX: Int, regionZ: Int, pathIdx: Int,
        startX: Double, startY: Double, startZ: Double,
        startYaw: Double, startPitch: Double,
        maxSteps: Int,
    ): TunnelPath {
        // Per-path Y oscillation parameters. Amplitude 12..43, period
        // 30..61 steps, phase 0..2π — generous spread so adjacent
        // tunnels' Y profiles never line up.
        val paramSeed = hash32(regionX, regionZ, 0x57F1CE17.toInt() xor pathIdx)
        val yAmp = 12.0 + (paramSeed and 0x1F)
        val yPeriod = 30.0 + ((paramSeed ushr 5) and 0x1F)
        val yPhase = ((paramSeed ushr 10) and 0xFF) / 256.0 * 2.0 * Math.PI

        var x = startX
        var y = startY
        var z = startZ
        var yaw = startYaw
        var pitch = startPitch.coerceIn(-TUNNEL_PITCH_LIMIT, TUNNEL_PITCH_LIMIT)

        val points = IntArray(maxSteps * 3)
        var count = 0
        for (step in 0 until maxSteps) {
            val cosp = Math.cos(pitch)
            x += Math.cos(yaw) * cosp * TUNNEL_STEP_LEN
            y += Math.sin(pitch) * TUNNEL_STEP_LEN
            z += Math.sin(yaw) * cosp * TUNNEL_STEP_LEN

            // Per-step turn nudge — vanilla-CaveWorldCarver-style smooth
            // random walk. The hash mixes pathIdx so the main path and
            // each branch have independent turn sequences.
            val turnSeed = hash32(
                regionX * 31 + pathIdx, regionZ, 100 + step,
            )
            yaw += ((turnSeed and 0xFFFF) / 65536.0 - 0.5) * TUNNEL_YAW_TURN
            pitch = (
                pitch + (((turnSeed ushr 16) and 0xFFFF) / 65536.0 - 0.5) * TUNNEL_PITCH_TURN
                ).coerceIn(-TUNNEL_PITCH_LIMIT, TUNNEL_PITCH_LIMIT)

            val localSurface = surfaceYAt(x.toInt(), z.toInt())
            val targetY = localSurface + Math.sin(
                yPhase + step * (2.0 * Math.PI / yPeriod)
            ) * yAmp
            y += (targetY - y) * TUNNEL_Y_TRACK_BIAS

            points[count * 3 + 0] = x.toInt()
            points[count * 3 + 1] = y.toInt().coerceIn(2, MAX_BUILD_Y - 2)
            points[count * 3 + 2] = z.toInt()
            count++
        }

        // Tip-in-open detection: if the final point sits *above* the
        // local effective surface, the root tip is emerging into air
        // or water rather than burrowing through mud. Such tips taper
        // to a point so the open end reads as a thinned root rather
        // than a hard stub.
        val tipX = points[(count - 1) * 3 + 0]
        val tipY = points[(count - 1) * 3 + 1]
        val tipZ = points[(count - 1) * 3 + 2]
        val tipSurface = surfaceYAt(tipX, tipZ)
        val taperTail = if (tipY > tipSurface) ROOT_TIP_TAPER_STEPS else 0

        return TunnelPath(points, count, taperTailSteps = taperTail)
    }

    /**
     * Rasterise a sphere of [ROOT_TUBE_RADIUS] at every path point,
     * applying the path's tip-taper window if its tip emerges into
     * open air or water. Points whose XZ AABB misses the chunk are
     * O(1) rejected before the inner loop. Path points inside the
     * Mother Tree exclusion are skipped per-point so a tunnel that
     * wanders through the trunk doesn't gouge it.
     */
    private fun paintTunnelPathInto(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        path: TunnelPath,
    ) {
        val baseR = ROOT_TUBE_RADIUS
        val pts = path.points
        val n = path.count
        for (i in 0 until n) {
            val r = heartPathRadiusAt(path, baseR, i)
            val rSqMax = r * r
            val px = pts[i * 3 + 0]
            val py = pts[i * 3 + 1]
            val pz = pts[i * 3 + 2]
            // O(1) chunk-AABB reject.
            if (px + r < chunkX0 || px - r > chunkX0 + 15) continue
            if (pz + r < chunkZ0 || pz - r > chunkZ0 + 15) continue
            // Don't carve through the Mother Tree's trunk.
            val pDistSq = px.toLong() * px + pz.toLong() * pz
            if (pDistSq < TUNNEL_MOTHER_TREE_EXCLUSION_SQ) continue

            // Per-point skip: if the path point's CENTRE lands
            // inside an existing non-water cache cell (child tree
            // trunk / canopy / surface roots from an earlier path),
            // skip the entire sphere from this point. The per-voxel
            // skip below still leaves a thin wood ring around the
            // intersected trunk wall because voxels JUST outside the
            // trunk pass the existing-cell check. Skipping the whole
            // point makes the root visibly end *before* entering the
            // tree's interior instead of wrapping around it. Path
            // points outside this chunk fall through to the per-
            // voxel check (we can't see other chunks' caches).
            val pLx = px - chunkX0
            val pLz = pz - chunkZ0
            val pLy = py - minY
            if (pLx in 0..15 && pLz in 0..15 && pLy in 0 until span) {
                val centerCell = target[pLx][pLz][pLy]
                if (centerCell != null && centerCell !== WATER) continue
            }

            for (dx in -r..r) {
                val nx = px + dx
                val lx = nx - chunkX0
                if (lx !in 0..15) continue
                val dxSq = dx * dx
                for (dz in -r..r) {
                    val nz = pz + dz
                    val lz = nz - chunkZ0
                    if (lz !in 0..15) continue
                    val dxzSq = dxSq + dz * dz
                    if (dxzSq > rSqMax) continue
                    val col = target[lx][lz]
                    for (dy in -r..r) {
                        if (dxzSq + dy * dy > rSqMax) continue
                        val ny = py + dy
                        if (ny < 1 || ny > MAX_BUILD_Y - 1) continue
                        val ly = ny - minY
                        if (ly !in 0 until span) continue
                        // Overwrite the cache cell IF it's empty or
                        // currently water — empty cells become the
                        // root's wood (normal carving), water cells
                        // become wood (the "roots read as bridges"
                        // semantic at river / heart-tunnel crossings).
                        // Skip otherwise so a root path passing
                        // through a child tree footprint doesn't
                        // gouge through its trunk / canopy — child
                        // trees are painted earlier in the cache
                        // and their wood/leaves stay intact here.
                        val existing = col[ly]
                        if (existing != null && existing !== WATER) continue
                        col[ly] = WOGOR_WOOD_Y
                    }
                }
            }
        }
    }

    /** A walked 3D path through the dimension, packed as
     *  `[x0,y0,z0, x1,y1,z1, …]` so we don't pay a `Vec3i` per step.
     *  Used now by root tunnels and rivers — the heart tunnel uses
     *  its own [HeartSkeleton].
     *
     *  - `taperHeadSteps` ≥ 1: the FIRST that many sphere radii grow
     *    from 0 → full (used by world-span roots whose head sprouts
     *    from a parent thicker than the path itself).
     *  - `taperTailSteps` ≥ 1: the LAST that many sphere radii shrink
     *    from full → 1 (used by world-span roots whose tips emerge
     *    into open air or water — so the root narrows to a point
     *    instead of dead-ending as a stub). */
    private class TunnelPath(
        val points: IntArray,
        val count: Int,
        val taperHeadSteps: Int = 0,
        val taperTailSteps: Int = 0,
    )

    /** Shared empty array returned for sparse-gap regions. Reusing one
     *  instance lets the cache store "no paths here" without per-region
     *  allocation. */
    private val EMPTY_TUNNEL_ARRAY: Array<TunnelPath> = emptyArray()

    // ------------------------------------------------------------------------
    //   rivers radiating from the moat
    // ------------------------------------------------------------------------

    /**
     * [RIVER_COUNT] deterministic water channels radiating from the moat
     * outward. Each one starts at a point on `MOAT_OUTER_R` evenly spaced
     * around the circle, walks outward with a soft pull toward the
     * radial direction (so the river generally heads away from origin),
     * and meanders with a per-step yaw random walk. The path is painted
     * as a chain of water/air spheres of radius [RIVER_RADIUS]: water
     * below sea level, air above, so the river cuts a clean swim-
     * through channel through whatever terrain it crosses.
     *
     * Lazy-initialised so the paths are built once on first chunk-decoration
     * and reused for every subsequent chunk. C2ME workers race harmlessly
     * on the lazy delegate's synchronized init.
     */
    private val rivers: Array<TunnelPath> by lazy {
        Array(RIVER_COUNT) { idx -> buildRiverPath(idx) }
    }

    /** Build one river path starting on the moat's outer rim at angle
     *  `idx * 2π / RIVER_COUNT` and walking outward. */
    private fun buildRiverPath(idx: Int): TunnelPath {
        val angle = idx * (2.0 * Math.PI / RIVER_COUNT)
        // Start just outside the moat's outer rim so the river visibly
        // emerges from the ring rather than overlapping it.
        val startRadius = MOAT_OUTER_R + 2.0
        var x = startRadius * Math.cos(angle)
        var y = (SEA_LEVEL_Y - 1).toDouble()
        var z = startRadius * Math.sin(angle)
        var yaw = angle

        val points = IntArray(RIVER_MAX_STEPS * 3)
        var count = 0
        for (step in 0 until RIVER_MAX_STEPS) {
            // Step forward. Pitch is fixed at 0 — rivers stay flat,
            // their Y is controlled by [RIVER_Y_TRACK_BIAS] instead.
            x += Math.cos(yaw) * RIVER_STEP_LEN
            z += Math.sin(yaw) * RIVER_STEP_LEN

            // Soft pull toward sea level − 1 so accumulated drift never
            // moves the river vertically. Strong tracking bias because
            // rivers shouldn't carve up/down between sphere centres.
            val targetY = (SEA_LEVEL_Y - 1).toDouble()
            y += (targetY - y) * RIVER_Y_TRACK_BIAS

            // Yaw random walk gives meander, then a soft pull back
            // toward the outward radial direction so the river doesn't
            // curl back toward origin.
            val turnSeed = hash32(idx, 0, 100 + step)
            yaw += ((turnSeed and 0xFFFF) / 65536.0 - 0.5) * RIVER_YAW_TURN
            val radialYaw = Math.atan2(z, x)
            var diff = radialYaw - yaw
            // Wrap angle diff to [-π, π] so the bias pulls the short way.
            while (diff > Math.PI) diff -= 2.0 * Math.PI
            while (diff < -Math.PI) diff += 2.0 * Math.PI
            yaw += diff * RIVER_RADIAL_BIAS

            points[count * 3 + 0] = x.toInt()
            points[count * 3 + 1] = y.toInt().coerceIn(2, MAX_BUILD_Y - 2)
            points[count * 3 + 2] = z.toInt()
            count++
        }
        return TunnelPath(points, count)
    }

    /**
     * Paint every river that overlaps this chunk as a vanilla-style 2D
     * water channel: for each path point we rasterise a *2D circle* in
     * XZ (radius [RIVER_RADIUS]) and fill the full column from
     * [RIVER_BED_Y] up to [SEA_LEVEL_Y] with water, plus an air buffer
     * from [SEA_LEVEL_Y]+1 up to [RIVER_TOP_Y] to carve through any
     * minor ridges the meander crosses. Above the air buffer the
     * natural terrain is untouched, so banks emerge naturally.
     */
    private fun paintRiversInto(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
    ) {
        // Chunk-AABB-vs-origin reject: rivers can't reach further than
        // [RIVER_REACH_PLUS_PAD_SQ] from origin.
        val nearestCornerX = if (chunkX0 + 15 < 0) chunkX0 + 15
        else if (chunkX0 > 0) chunkX0
        else 0
        val nearestCornerZ = if (chunkZ0 + 15 < 0) chunkZ0 + 15
        else if (chunkZ0 > 0) chunkZ0
        else 0
        val cornerDistSq =
            nearestCornerX.toLong() * nearestCornerX +
            nearestCornerZ.toLong() * nearestCornerZ
        if (cornerDistSq > RIVER_REACH_PLUS_PAD_SQ) return

        val R = RIVER_RADIUS
        val rSqMax = R * R
        // Pre-compute Y windows in cache coordinates. waterTopLY is the
        // *topmost water block*, one below SEA_LEVEL_Y, so the water
        // surface sits at the SEA_LEVEL_Y line — matching baseColumnBlock,
        // the moat, the cave pool, and the heart tunnel.
        val waterTopLY = (SEA_LEVEL_Y - 1) - minY
        val waterBottomLY = RIVER_BED_Y - minY
        val airTopLY = RIVER_TOP_Y - minY
        for (river in rivers) {
            val pts = river.points
            val n = river.count
            for (i in 0 until n) {
                val px = pts[i * 3 + 0]
                val pz = pts[i * 3 + 2]
                if (px + R < chunkX0 || px - R > chunkX0 + 15) continue
                if (pz + R < chunkZ0 || pz - R > chunkZ0 + 15) continue

                for (dx in -R..R) {
                    val nx = px + dx
                    val lx = nx - chunkX0
                    if (lx !in 0..15) continue
                    val dxSq = dx * dx
                    for (dz in -R..R) {
                        val nz = pz + dz
                        val lz = nz - chunkZ0
                        if (lz !in 0..15) continue
                        val dxzSq = dxSq + dz * dz
                        if (dxzSq > rSqMax) continue
                        val col = target[lx][lz]
                        // Water column: bed → sea level.
                        val waterLo = Math.max(0, waterBottomLY)
                        val waterHi = Math.min(span - 1, waterTopLY)
                        for (ly in waterLo..waterHi) col[ly] = WATER
                        // Air buffer above water — carves through low
                        // ridges so the channel reads as open river.
                        val airLo = Math.max(0, waterTopLY + 1)
                        val airHi = Math.min(span - 1, airTopLY)
                        for (ly in airLo..airHi) col[ly] = AIR
                    }
                }
            }
        }
    }

    /**
     * Heart-tunnel skeleton, built in the same spirit as the Mother
     * Tree's tapered-capsule skeleton — a chain of `(x, y, z, r)`
     * nodes plus a list of dead-end pool capsules — then rasterised
     * via the SDF capsule painter ([paintHeartCapsuleSegment]).
     *
     * Build algorithm ([buildHeartSkeleton]):
     *  1. Start at the heart `(0, SEA_LEVEL_Y, 0)` with the
     *     world-stable random initial yaw used by the cave's
     *     wall-bulge logic, so the tunnel exits the cave cleanly
     *     through that bulge.
     *  2. Each step, generate **two** candidate (yaw, pitch) offsets.
     *     Toss a coin to pick one as the main continuation; the other
     *     becomes the seed direction for a rejected-branch pool.
     *  3. The rejected branch terminates immediately in a craggy
     *     oblong pool — an elongated SDF capsule with a high
     *     boundary-noise amp so it reads as a found grotto, not a
     *     sphere on a stub.
     *  4. Walk until the main path crosses [MOAT_INNER_R] (so the
     *     last capsule opens into the moat) or the
     *     [HEART_SKELETON_MAX_STEPS] safety cap fires.
     *
     * Y is clamped to ±[HEART_Y_RANGE] of sea level so the tunnel
     * meanders vertically without escaping the around-sea-level
     * band. Radius comes from value noise bounded to
     * `[HEART_R_MIN, HEART_R_MAX]`; the SDF rasteriser linearly
     * interpolates radius between adjacent nodes for a smooth taper.
     *
     * Cached lazily; concurrent C2ME workers race harmlessly on the
     * `lazy` delegate's synchronized init.
     */
    private val heartTunnel: HeartTunnelSkeleton.Skeleton by lazy { buildHeartTunnelSkeleton() }

    private fun buildHeartTunnelSkeleton(): HeartTunnelSkeleton.Skeleton {
        val seaLow = SEA_LEVEL_Y - HEART_Y_RANGE.toDouble()
        val seaHigh = SEA_LEVEL_Y + HEART_Y_RANGE.toDouble()

        // Per-world deterministic seed integer derived from the
        // world-stable heart-tunnel direction angle. The angle
        // already mixes the world seed via the captured
        // PositionalRandomFactory; multiplying by a large constant
        // and truncating gives us an int the attractor sampler can
        // use without losing per-world entropy.
        val attractorSeed = (heartTunnelAngleRad * 1.2e9).toLong().toInt()

        val attractors = HeartTunnelSkeleton.buildAttractors(
            cx = 0.5, cy = SEA_LEVEL_Y.toDouble(), cz = 0.5,
            seaLow = seaLow, seaHigh = seaHigh,
            startRing = HEART_INTERMEDIATE_INNER_R,
            moatInnerR = MOAT_INNER_R,
            moatOuterR = MOAT_OUTER_R,
            moatCount = HEART_MOAT_ATTRACTOR_COUNT,
            intermediateCount = HEART_INTERMEDIATE_ATTRACTOR_COUNT,
            starterCount = HEART_STARTER_ATTRACTOR_COUNT,
            starterMaxRadius = HEART_STARTER_MAX_RADIUS,
            seed = attractorSeed,
        )

        // Bias direction: a unit vector along the world-stable
        // heart-tunnel angle in the horizontal plane (no Y
        // component). Gets the trunk pointed roughly toward the
        // moat ring on the side of the world the cave's wall-bulge
        // already exits, before any attractor latches on.
        val biasX = Math.cos(heartTunnelAngleRad) * HEART_BIAS_STRENGTH
        val biasZ = Math.sin(heartTunnelAngleRad) * HEART_BIAS_STRENGTH

        return HeartTunnelSkeleton.build(
            originX = 0.5, originY = SEA_LEVEL_Y.toDouble(), originZ = 0.5,
            attractors = attractors,
            biasX = biasX, biasY = 0.0, biasZ = biasZ,
            attractionDist = HEART_ATTRACTION_DIST,
            killDist = HEART_KILL_DIST,
            stepSize = HEART_STEP_LEN,
            maxIterations = HEART_MAX_ITERATIONS,
            rMin = HEART_R_MIN, rMax = HEART_R_MAX,
            thicknessScale = HEART_THICKNESS_SCALE,
            seaLow = seaLow, seaHigh = seaHigh,
        )
    }

    /**
     * Voxelise the [HeartTunnelSkeleton] into [target] — air-carve
     * interior with a one-block wogor-wood shell lining. The
     * skeleton is the SCA-grown tree of capsule segments that
     * connects the heart to the moat; every segment in the tree
     * gets painted by [paintHeartTunnelSegment] (which mirrors the
     * tree's [paintTreeSegment] voxel-for-voxel — same tapered-
     * capsule SDF, same bark perturbation — only the writes differ:
     * tree places wood, this carves air/water and paints a wood
     * shell). At every leaf-tip the chunk generator drops a craggy
     * pool capsule.
     */
    private fun paintHeartTunnelInto(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
    ) {
        val nearestCornerX = if (chunkX0 + 15 < 0) chunkX0 + 15
        else if (chunkX0 > 0) chunkX0
        else 0
        val nearestCornerZ = if (chunkZ0 + 15 < 0) chunkZ0 + 15
        else if (chunkZ0 > 0) chunkZ0
        else 0
        val cornerDistSq =
            nearestCornerX.toLong() * nearestCornerX +
            nearestCornerZ.toLong() * nearestCornerZ
        if (cornerDistSq > HEART_TUNNEL_REACH_PLUS_PAD_SQ) return

        val skel = heartTunnel
        val tunnelShell = HEART_TUNNEL_SHELL_THICK.toDouble()

        // Pass 1 — every segment (trunk + branches) painted with the
        // tree-style voxeliser: tapered capsule + bark-noise on the
        // surface, carve interior, line shell with wogor wood.
        for (seg in skel.segments) {
            paintHeartTunnelSegment(
                target, chunkX0, chunkZ0, minY, span,
                seg, tunnelShell,
            )
        }

        // Pass 2 — craggy dead-end pool at every leaf-tip. Anchored
        // on the tip, extending one [HEART_POOL_LEN_AVG]-ish step in
        // the tip's forward direction so the chamber sits past the
        // dead-end rather than concentrically over it.
        for (tip in skel.tips) {
            val poolLength = HEART_POOL_LEN_MIN + 0.5 *
                (HEART_POOL_LEN_MAX - HEART_POOL_LEN_MIN)
            val poolR = (tip.radius + HEART_POOL_R_BONUS)
                .coerceIn(HEART_POOL_R_MIN, HEART_POOL_R_MAX)
            val endX = tip.x + tip.dirX * poolLength
            val endY = tip.y + tip.dirY * poolLength
            val endZ = tip.z + tip.dirZ * poolLength
            paintHeartCapsuleSegment(
                target, chunkX0, chunkZ0, minY, span,
                tip.x, tip.y, tip.z, poolR,
                endX, endY, endZ, poolR,
                tunnelShell,
                boundaryNoiseAmp = HEART_POOL_NOISE_AMP,
            )
        }

        // Pass 3 — vines. Scan every voxel in the chunk for the
        // AIR-with-WOGOR_WOOD-above pattern and hang a vine column
        // downward with deterministic probability. Single chunk
        // pass catches every painted ceiling regardless of which
        // segment carved the air below it.
        paintHeartVines(target, chunkX0, chunkZ0, minY, span)
    }

    /**
     * Voxelise a single [HeartTunnelSegment] — the heart-tunnel
     * counterpart to the Mother Tree's [paintTreeSegment]. Same
     * tapered-capsule SDF, same bark perturbation, same chunk-AABB
     * clip and degenerate-segment handling; the **only** difference
     * is what gets written:
     *
     *  - voxels inside the bark-perturbed effective inner radius
     *    are carved as `AIR` (or `WATER` below sea level);
     *  - voxels in the one-block ring just outside (in the
     *    `[rEff, rEff + shellThick]` band) are painted
     *    [WOGOR_WOOD_Y], but only on cells where the cache is still
     *    `null` (so we never bisect a neighbour segment's interior)
     *    AND `wy ≤ surfaceHere` (so the shell never floats above
     *    the natural mud surface).
     *
     * Cells inside the central cave volume are always skipped so
     * the cave's hand-placed lantern + water stay intact.
     */
    private fun paintHeartTunnelSegment(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        seg: HeartTunnelSegment, shellThick: Double,
    ) {
        val p0x = seg.startX; val p0y = seg.startY; val p0z = seg.startZ
        val p1x = seg.endX; val p1y = seg.endY; val p1z = seg.endZ
        val r0 = seg.startRadius
        val r1 = seg.endRadius
        val rMax = Math.max(r0, r1)
        val barkAmpMax = barkAmplitudeFor(rMax)

        // World-space AABB inflated for radius + worst-case bark
        // excursion + shell band.
        val pad = rMax + barkAmpMax + shellThick + 1.0
        val minWX = Math.floor(Math.min(p0x, p1x) - pad).toInt()
        val maxWX = Math.ceil(Math.max(p0x, p1x) + pad).toInt()
        val minWY = Math.floor(Math.min(p0y, p1y) - pad).toInt()
        val maxWY = Math.ceil(Math.max(p0y, p1y) + pad).toInt()
        val minWZ = Math.floor(Math.min(p0z, p1z) - pad).toInt()
        val maxWZ = Math.ceil(Math.max(p0z, p1z) + pad).toInt()

        val cMinX = Math.max(minWX, chunkX0)
        val cMaxX = Math.min(maxWX, chunkX0 + 15)
        val cMinZ = Math.max(minWZ, chunkZ0)
        val cMaxZ = Math.min(maxWZ, chunkZ0 + 15)
        val cMinY = Math.max(minWY, minY)
        val cMaxY = Math.min(maxWY, minY + span - 1)
        if (cMinX > cMaxX || cMinY > cMaxY || cMinZ > cMaxZ) return

        val ex = p1x - p0x; val ey = p1y - p0y; val ez = p1z - p0z
        val segLenSq = ex * ex + ey * ey + ez * ez
        val degenerate = segLenSq < 1.0e-6

        for (wz in cMinZ..cMaxZ) {
            val vz = wz + 0.5
            val lz = wz - chunkZ0
            for (wx in cMinX..cMaxX) {
                val vx = wx + 0.5
                val lx = wx - chunkX0
                val caveXZHere = isInsideCaveFootprint(wx, wz)
                val surfaceHere = effectiveSurfaceYAt(wx, wz)
                val col = target[lx][lz]
                for (wy in cMinY..cMaxY) {
                    val vy = wy + 0.5

                    val t = if (degenerate) 0.0
                    else {
                        val tRaw = ((vx - p0x) * ex + (vy - p0y) * ey + (vz - p0z) * ez) / segLenSq
                        if (tRaw < 0.0) 0.0 else if (tRaw > 1.0) 1.0 else tRaw
                    }
                    val cX = p0x + ex * t
                    val cY = p0y + ey * t
                    val cZ = p0z + ez * t
                    val ddx = vx - cX; val ddy = vy - cY; val ddz = vz - cZ
                    val distSq = ddx * ddx + ddy * ddy + ddz * ddz

                    val r = r0 + (r1 - r0) * t
                    val barkAmp = barkAmplitudeFor(r)
                    val rMaxOuter = r + barkAmp + shellThick
                    if (distSq > rMaxOuter * rMaxOuter) continue   // cheap reject

                    // Cone-surface point — sample bark noise at the
                    // projected surface point so the perturbation is
                    // C¹-continuous around the capsule (identical
                    // pattern to paintTreeSegment).
                    val dist = Math.sqrt(distSq)
                    val sX: Double; val sY: Double; val sZ: Double
                    if (dist > 1.0e-6) {
                        val inv = r / dist
                        sX = cX + ddx * inv
                        sY = cY + ddy * inv
                        sZ = cZ + ddz * inv
                    } else {
                        sX = cX; sY = cY; sZ = cZ
                    }
                    val bark = (valueNoise3D(sX * BARK_FREQ, sY * BARK_FREQ, sZ * BARK_FREQ)
                        - 0.5) * 2.0 * barkAmp
                    // Inner (carve) and outer (shell-out) radii both
                    // shift by the same bark sample so the shell stays
                    // a uniform thickness — the wall is craggy but
                    // its inner and outer surfaces ripple together.
                    val rInner = r + bark
                    val rOuter = rInner + shellThick

                    if (caveXZHere && centralCaveBlock(wx, wy, wz) != null) continue
                    val ly = wy - minY
                    if (ly !in 0 until span) continue

                    if (distSq <= rInner * rInner) {
                        col[ly] = if (wy < SEA_LEVEL_Y) WATER else AIR
                    } else if (distSq <= rOuter * rOuter && col[ly] == null && wy <= surfaceHere) {
                        col[ly] = WOGOR_WOOD_Y
                    }
                }
            }
        }
    }

    /**
     * Hang random vine columns from every wogor-wood ceiling cell in
     * the chunk — **but only over water-filled stretches of the
     * tunnel**. Triggers on a cell where:
     *
     *  - the cache cell is `AIR` (carved by a capsule above), AND
     *  - the cell directly above is [WOGOR_WOOD_Y] (the shell
     *    that capsule painted), AND
     *  - the cell at `y = SEA_LEVEL_Y − 1` in the same column is
     *    [WATER] — i.e. this column is part of a tunnel section
     *    that crosses sea level with water filling the bottom.
     *
     * The water requirement keeps vines out of dry stretches where
     * the tunnel sits entirely above sea level (no water at the
     * floor), and out of fully submerged stretches (no AIR cells to
     * hang into) — so vines only ever appear on the walls of
     * tunnels that the player would actually wade or swim through.
     *
     * Density and per-column length are deterministic-per-position
     * hashes, so reload / C2ME concurrent gen always produces the
     * same visible curtain.
     *
     * **Vines do not replace anything.** The downward extension
     * breaks the instant it would overwrite a non-AIR cell — water
     * (sub-sea-level interior), wogor wood (the shell itself), mud
     * (null cache → unmodified level), or even another vine column
     * from a different ceiling cell. The top vine slot starts on the
     * trigger AIR cell, but writing VINE_UP there isn't replacing
     * anything — AIR is the absence of a block.
     */
    private fun paintHeartVines(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
    ) {
        val waterCheckLy = (SEA_LEVEL_Y - 1) - minY
        if (waterCheckLy !in 0 until span) return

        // ---- Pre-fill heart-cave volume into the cache ----
        // The cave's air / water / lantern blocks are normally
        // applied during the main per-voxel fill via
        // [centralCaveBlock]; they're NOT in the chunk cache at
        // this point. Without them, the vine scan below can't see
        // cave-interior AIR cells and can't include the cave walls.
        // Pre-fill the cave footprint with its block-state contents
        // wherever the cache is still null so the scan finds them.
        for (lx in 0..15) {
            val wx = chunkX0 + lx
            for (lz in 0..15) {
                val wz = chunkZ0 + lz
                if (!isInsideCaveFootprint(wx, wz)) continue
                val col = target[lx][lz]
                for (ly in 0 until span) {
                    if (col[ly] != null) continue
                    val wy = minY + ly
                    val caveBlock = centralCaveBlock(wx, wy, wz)
                    if (caveBlock != null) col[ly] = caveBlock
                }
            }
        }

        // ---- Side-wall vine scan ----
        // For every AIR cell in the chunk, check the four horizontal
        // neighbours (N/S/E/W). If any one is a wall block (tunnel
        // wogor-wood shell, cave mud, cave lantern, or any other
        // solid the cache holds), build a face-mask and write the
        // matching [VINE_STATES] entry. Vines now only ever attach
        // to SIDE walls — the previous ceiling-hang gesture is
        // gone. Multi-wall cells (corner pockets) get a vine
        // attached to every adjacent wall.
        for (lx in 0..15) {
            val wx = chunkX0 + lx
            for (lz in 0..15) {
                val wz = chunkZ0 + lz
                val col = target[lx][lz]
                // Water-exclusive: skip columns that don't have
                // water at sea-surface − 1. The cave's pool + the
                // tunnel's submerged sections both qualify; dry
                // tunnel stretches and the surrounding terrain
                // don't.
                if (col[waterCheckLy] !== WATER) continue
                for (ly in 0 until span) {
                    if (col[ly] !== AIR) continue
                    val wy = minY + ly

                    var mask = 0
                    if (isHeartWallAt(target, chunkX0, chunkZ0, minY, span, wx, wy, wz - 1)) mask = mask or 0x1
                    if (isHeartWallAt(target, chunkX0, chunkZ0, minY, span, wx, wy, wz + 1)) mask = mask or 0x2
                    if (isHeartWallAt(target, chunkX0, chunkZ0, minY, span, wx + 1, wy, wz)) mask = mask or 0x4
                    if (isHeartWallAt(target, chunkX0, chunkZ0, minY, span, wx - 1, wy, wz)) mask = mask or 0x8
                    if (mask == 0) continue

                    val seed = hash32(wx, wy * 31 + wz, 0xC4FE_117E.toInt())
                    if ((seed and 0xFF) >= HEART_VINE_DENSITY_THRESHOLD) continue

                    col[ly] = VINE_STATES[mask]
                }
            }
        }
    }

    /**
     * Is the block at `(wx, wy, wz)` a wall that the vine painter
     * should attach a vine to? Used by [paintHeartVines] to scan
     * the four horizontal neighbours of every air cell.
     *
     * Three branches in priority order:
     *
     *  1. **Neighbour in THIS chunk with a populated cache cell** —
     *     the cache is the truth. Air, water, and vines aren't
     *     walls; everything else is.
     *  2. **Neighbour in ANOTHER chunk** — the cache isn't
     *     available, so query the full [heartTunnel] skeleton via
     *     [isVoxelInsideHeartTunnel]. If any segment or pool
     *     capsule contains the voxel, it's tunnel interior in the
     *     adjacent chunk and NOT a wall. Without this check the
     *     vine painter would treat chunk-boundary voxels of the
     *     tunnel itself as mud, producing a strip of vines along
     *     every chunk seam the tunnel crosses.
     *  3. **Otherwise** — natural-terrain inference: cave volume
     *     first (the lantern is the only wall inside it), then mud
     *     (`y in 1..surface`).
     */
    private fun isHeartWallAt(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        wx: Int, wy: Int, wz: Int,
    ): Boolean {
        val lx = wx - chunkX0
        val lz = wz - chunkZ0
        val ly = wy - minY
        val inChunk = lx in 0..15 && lz in 0..15 && ly in 0 until span
        if (inChunk) {
            val cell = target[lx][lz][ly]
            if (cell != null) {
                if (cell === AIR || cell === WATER) return false
                if (cell.block === Blocks.VINE) return false
                return true
            }
            // Cache null in-chunk → segment painter saw this voxel
            // and left it unwritten, so the SDF said exterior. Fall
            // through to cave / mud inference.
        } else if (isVoxelInsideHeartTunnel(wx + 0.5, wy + 0.5, wz + 0.5)) {
            // Cross-chunk: the global skeleton says this voxel is
            // inside a tunnel segment or pool capsule. The adjacent
            // chunk will paint it as carved air/water, so it's NOT
            // a wall — even though our local cave / mud inference
            // would otherwise classify it as mud below the surface.
            return false
        }
        if (isInsideCaveFootprint(wx, wz)) {
            val caveBlock = centralCaveBlock(wx, wy, wz)
            if (caveBlock != null) {
                return caveBlock !== AIR && caveBlock !== WATER
            }
        }
        val surfaceHere = effectiveSurfaceYAt(wx, wz)
        return wy in 1..surfaceHere
    }

    /**
     * SDF inverse: is voxel centre `(vx, vy, vz)` inside any
     * heart-tunnel segment's tapered capsule or any leaf-tip's pool
     * capsule? Used by [isHeartWallAt] to correctly classify
     * cross-chunk neighbours (whose cache isn't available) instead
     * of falling back to "mud below surface = wall".
     *
     * For the main segments we use the exact same capsule SDF the
     * painter does, minus the per-voxel bark perturbation — the
     * extra ±[barkAmplitudeFor] excursion is negligible at the
     * resolution we care about (one cell of slop near a chunk
     * boundary is invisible). For pool capsules we use the base
     * radius PLUS the bulge-noise amplitude — the bulge-only noise
     * can extend `rEff` up to `r + amp`, so checking against the
     * outer envelope conservatively classifies bulge-zone voxels
     * as "could be tunnel" → not wall.
     */
    private fun isVoxelInsideHeartTunnel(vx: Double, vy: Double, vz: Double): Boolean {
        val skel = heartTunnel
        for (seg in skel.segments) {
            if (isVoxelInsideCapsule(
                vx, vy, vz,
                seg.startX, seg.startY, seg.startZ,
                seg.endX, seg.endY, seg.endZ,
                seg.startRadius, seg.endRadius,
                outerSlack = 0.0,
            )) return true
        }
        val poolLength = HEART_POOL_LEN_MIN + 0.5 *
            (HEART_POOL_LEN_MAX - HEART_POOL_LEN_MIN)
        for (tip in skel.tips) {
            val poolR = (tip.radius + HEART_POOL_R_BONUS)
                .coerceIn(HEART_POOL_R_MIN, HEART_POOL_R_MAX)
            val endX = tip.x + tip.dirX * poolLength
            val endY = tip.y + tip.dirY * poolLength
            val endZ = tip.z + tip.dirZ * poolLength
            if (isVoxelInsideCapsule(
                vx, vy, vz,
                tip.x, tip.y, tip.z,
                endX, endY, endZ,
                poolR, poolR,
                outerSlack = HEART_POOL_NOISE_AMP,
            )) return true
        }
        return false
    }

    /** Standard tapered-capsule SDF interior test — same projection
     *  + linear-radius-lerp the painter uses. [outerSlack] is added
     *  to the effective radius for pool capsules whose bulge-only
     *  boundary noise can extend the wall outward. */
    private fun isVoxelInsideCapsule(
        vx: Double, vy: Double, vz: Double,
        x0: Double, y0: Double, z0: Double,
        x1: Double, y1: Double, z1: Double,
        r0: Double, r1: Double,
        outerSlack: Double,
    ): Boolean {
        val ex = x1 - x0; val ey = y1 - y0; val ez = z1 - z0
        val segLenSq = ex * ex + ey * ey + ez * ez
        val t = if (segLenSq < 1.0e-6) 0.0
        else {
            val tRaw = ((vx - x0) * ex + (vy - y0) * ey + (vz - z0) * ez) / segLenSq
            if (tRaw < 0.0) 0.0 else if (tRaw > 1.0) 1.0 else tRaw
        }
        val cX = x0 + ex * t
        val cY = y0 + ey * t
        val cZ = z0 + ez * t
        val ddx = vx - cX; val ddy = vy - cY; val ddz = vz - cZ
        val distSq = ddx * ddx + ddy * ddy + ddz * ddz
        val r = r0 + (r1 - r0) * t + outerSlack
        return distSq <= r * r
    }

    /** Compute the effective sphere radius at path step `i` as a
     *  Double — used by the SDF capsule rasteriser so the radius
     *  interpolates smoothly along each path segment between
     *  consecutive steps. Applies the per-path radius noise wobble
     *  first, then the head + tail tapers (tapers always cap the
     *  noise so the head still narrows cleanly out of the cave and
     *  the tail still narrows into a pool / open-air tip). */
    private fun heartPathRadiusD(path: TunnelPath, baseR: Double, i: Int): Double {
        var r = baseR
        if (path.taperHeadSteps > 0 && i < path.taperHeadSteps) {
            val t = (i + 1).toDouble() / (path.taperHeadSteps + 1)
            val tapered = (baseR * t).coerceAtLeast(0.5)
            if (tapered < r) r = tapered
        }
        if (path.taperTailSteps > 0) {
            val fromEnd = path.count - 1 - i
            if (fromEnd < path.taperTailSteps) {
                val t = (fromEnd + 1).toDouble() / (path.taperTailSteps + 1)
                val tapered = (baseR * t).coerceAtLeast(0.5)
                if (tapered < r) r = tapered
            }
        }
        return r
    }

    /** Integer wrapper kept for the root-tunnel sphere rasteriser and
     *  the vine painter, which both want a discrete radius per step. */
    private fun heartPathRadiusAt(path: TunnelPath, baseR: Int, i: Int): Int =
        heartPathRadiusD(path, baseR.toDouble(), i).toInt().coerceAtLeast(1)

    /**
     * SDF-rasterise one tapered-capsule segment of the heart-tunnel
     * system. Same pattern as the Mother Tree's [paintTreeSegment],
     * adapted for "carve interior + paint shell" semantics:
     *
     *  - Build a world-space AABB around the segment with enough pad
     *    for the largest endpoint radius + the shell thickness + the
     *    worst-case boundary-noise excursion.
     *  - Clip the AABB to this chunk.
     *  - For each voxel inside the clipped AABB: project the voxel
     *    centre onto the segment to get the parameter `t ∈ [0, 1]`,
     *    compute the linearly-interpolated radius
     *    `r(t) = r0 + (r1 − r0) · t`, optionally perturb by
     *    [boundaryNoiseAmp], and classify:
     *      * `dist ≤ r`     → interior → water (below sea level) or air.
     *      * `r < dist ≤ r + shellThick` → shell → wogor wood, but
     *                                     only where the cache cell is
     *                                     still null AND the natural
     *                                     terrain at this voxel would
     *                                     be mud. Air-side and
     *                                     water-side shell voxels stay
     *                                     untouched so the wood wall
     *                                     never floats.
     *      * `dist > r + shellThick` → outside → skip.
     *
     * Cells inside the central cave volume are always skipped so the
     * cave's lantern + water stay intact.
     */
    private fun paintHeartCapsuleSegment(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        x0: Double, y0: Double, z0: Double, r0: Double,
        x1: Double, y1: Double, z1: Double, r1: Double,
        shellThick: Double,
        boundaryNoiseAmp: Double = 0.0,
    ) {
        val rMaxOuter = Math.max(r0, r1) + shellThick + boundaryNoiseAmp + 0.5
        val minWX = Math.floor(Math.min(x0, x1) - rMaxOuter).toInt()
        val maxWX = Math.ceil(Math.max(x0, x1) + rMaxOuter).toInt()
        val minWY = Math.floor(Math.min(y0, y1) - rMaxOuter).toInt()
        val maxWY = Math.ceil(Math.max(y0, y1) + rMaxOuter).toInt()
        val minWZ = Math.floor(Math.min(z0, z1) - rMaxOuter).toInt()
        val maxWZ = Math.ceil(Math.max(z0, z1) + rMaxOuter).toInt()

        val cMinX = Math.max(minWX, chunkX0)
        val cMaxX = Math.min(maxWX, chunkX0 + 15)
        val cMinZ = Math.max(minWZ, chunkZ0)
        val cMaxZ = Math.min(maxWZ, chunkZ0 + 15)
        val cMinY = Math.max(minWY, minY)
        val cMaxY = Math.min(maxWY, minY + span - 1)
        if (cMinX > cMaxX || cMinY > cMaxY || cMinZ > cMaxZ) return

        val ex = x1 - x0; val ey = y1 - y0; val ez = z1 - z0
        val segLenSq = ex * ex + ey * ey + ez * ez
        val degenerate = segLenSq < 1.0e-6

        for (wz in cMinZ..cMaxZ) {
            val vz = wz + 0.5
            val lz = wz - chunkZ0
            for (wx in cMinX..cMaxX) {
                val vx = wx + 0.5
                val lx = wx - chunkX0
                val caveXZHere = isInsideCaveFootprint(wx, wz)
                val surfaceHere = effectiveSurfaceYAt(wx, wz)
                val col = target[lx][lz]
                for (wy in cMinY..cMaxY) {
                    val vy = wy + 0.5

                    // Project voxel centre onto segment, clamp t ∈ [0, 1].
                    val t = if (degenerate) 0.0
                    else {
                        val tRaw = ((vx - x0) * ex + (vy - y0) * ey + (vz - z0) * ez) / segLenSq
                        if (tRaw < 0.0) 0.0 else if (tRaw > 1.0) 1.0 else tRaw
                    }
                    val cX = x0 + ex * t
                    val cY = y0 + ey * t
                    val cZ = z0 + ez * t
                    val ddx = vx - cX; val ddy = vy - cY; val ddz = vz - cZ
                    val distSq = ddx * ddx + ddy * ddy + ddz * ddz

                    val r = r0 + (r1 - r0) * t
                    // Bulge-only 3D noise on the boundary. Two reasons,
                    // both about killing the vertical scarring the
                    // previous (anisotropic, pinch-and-bulge) noise was
                    // producing inside the pool volume:
                    //
                    //  - **3D isotropic** — valueNoise3D samples the
                    //    same frequency in X, Y, Z so consecutive Y
                    //    voxels share nearly-identical noise. The
                    //    previous valueNoise2 with wy×0.17 + wy×0.13
                    //    perturbation made Y its own noise axis, so
                    //    rEff could flip from "interior" to "exterior"
                    //    between adjacent Y voxels at the same XZ —
                    //    leaving narrow strips of unwritten cache
                    //    cells that flushed to mud as vertical stripes
                    //    through the pool ("vertical scarring").
                    //  - **Bulge-only** — `rEff = r + noiseRaw·amp`
                    //    where `noiseRaw ∈ [0, 1]`, so the effective
                    //    radius is always ≥ the base r. The pool core
                    //    out to r is guaranteed interior regardless of
                    //    noise; the wall can only stick OUT into the
                    //    surrounding mud, never pinch IN to create
                    //    holes inside the pool. The previous
                    //    `(noise − 0.5) × 2 × amp` allowed pinches as
                    //    deep as `−amp`, which is what carved the
                    //    intrusions in the first place.
                    val noiseOff = if (boundaryNoiseAmp > 0.0) {
                        valueNoise3D(
                            wx * HEART_POOL_BOUNDARY_NOISE_FREQ,
                            wy * HEART_POOL_BOUNDARY_NOISE_FREQ,
                            wz * HEART_POOL_BOUNDARY_NOISE_FREQ,
                        ) * boundaryNoiseAmp
                    } else 0.0
                    val rEff = (r + noiseOff).coerceAtLeast(0.5)
                    val outerR = rEff + shellThick
                    if (distSq > outerR * outerR) continue

                    if (caveXZHere && centralCaveBlock(wx, wy, wz) != null) continue
                    val ly = wy - minY
                    if (ly !in 0 until span) continue

                    if (distSq <= rEff * rEff) {
                        col[ly] = if (wy < SEA_LEVEL_Y) WATER else AIR
                    } else if (col[ly] == null && wy <= surfaceHere) {
                        col[ly] = WOGOR_WOOD_Y
                    }
                }
            }
        }
    }

    /**
     * For each chunk XZ that holds an *airborne* root segment tall enough
     * to be interesting, drop a curtain of 2-7 vines off one of its four
     * cardinal faces. Deterministic-by-position hash so visuals are
     * stable across reload / C2ME concurrent gen.
     */
    private fun paintRootVines(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
    ) {
        for (lx in 0..15) {
            val wx = chunkX0 + lx
            for (lz in 0..15) {
                val wz = chunkZ0 + lz
                val surface = surfaceYAt(wx, wz)
                val surfaceLY = surface - minY
                val col = target[lx][lz]
                // Empty-column gate: every wood-bearing tree painted
                // by this generator has a contiguous trunk from the
                // surface upward, so if the cell directly above the
                // surface is empty, the entire above-surface portion
                // of this column is empty — no wood, no leaves, no
                // airborne section. One array access here saves the
                // full top-down `for (ly in span − 1 downTo …)` walk
                // through nulls that the old code did per column.
                // Spark had paintRootVines self-time at ~1.8 s
                // cumulative on Worker-Main; the empty-column walk
                // was the entirety of it.
                val checkLY = surfaceLY + 1
                if (checkLY < 0 || checkLY >= span || col[checkLY] == null) continue
                // Find the topmost wood block in this column.
                var topLY = -1
                for (ly in span - 1 downTo Math.max(0, surfaceLY)) {
                    val b = col[ly] ?: continue
                    if (b === WOGOR_WOOD_Y || b === WOGOR_WOOD_X || b === WOGOR_WOOD_Z) {
                        topLY = ly
                        break
                    }
                }
                if (topLY < 0) continue
                // Only hang vines from clearly elevated airborne sections.
                if (topLY - surfaceLY < 5) continue

                val h = hash32(wx, wz, 0x73A1F4C7.toInt())
                // ~25 % of elevated cells get vines.
                if ((h and 0x3) != 0) continue
                val len = 2 + ((h ushr 2) and 0x7)  // 2..9 blocks
                // Pick one cardinal face this cell will drip from.
                // Each face index maps to (adjDx, adjDz, vineBlockIdx
                // whose face property points *back* at this column).
                val faceIdx = (h ushr 5) and 0x3
                val (adjDx, adjDz, vineIdx) = when (faceIdx) {
                    0 -> Triple(0, 1, 0)    // drip south, vine faces NORTH
                    1 -> Triple(0, -1, 1)   // drip north, vine faces SOUTH
                    2 -> Triple(-1, 0, 2)   // drip west,  vine faces EAST
                    else -> Triple(1, 0, 3) // drip east,  vine faces WEST
                }
                val adjLx = lx + adjDx
                val adjLz = lz + adjDz
                if (adjLx !in 0..15 || adjLz !in 0..15) continue
                val adjCol = target[adjLx][adjLz]
                val vineState = VINE_BLOCKS[vineIdx]
                for (v in 0 until len) {
                    val vy = topLY - v
                    if (vy < 0) break
                    // Don't replace cache wood with vine.
                    val existing = adjCol[vy]
                    if (existing != null) break
                    adjCol[vy] = vineState
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    //   ChunkGenerator overrides
    // ------------------------------------------------------------------------

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
    ): Int = surfaceYAt(x, z) + 1

    override fun getBaseColumn(
        x: Int, z: Int, level: LevelHeightAccessor, randomState: RandomState,
    ): NoiseColumn {
        val surface = surfaceYAt(x, z)
        val states: Array<BlockState> = Array(WORLD_HEIGHT) { i ->
            baseColumnBlock(MIN_Y + i, surface)
        }
        return NoiseColumn(MIN_Y, states)
    }

    override fun addDebugScreenInfo(
        info: MutableList<String>, randomState: RandomState, pos: BlockPos,
    ) {
        info.add("Wohlonnogondonia chunk generator")
        info.add("surface=" + surfaceYAt(pos.x, pos.z))
    }

    @Suppress("unused")
    fun getBiomeSourceForCodec(): BiomeSource = this.biomeSource

    /**
     * Lazily-built Mother Tree skeletons: `first` is the canopy (branches
     * + leaves), `second` is the roots. The two share the trunk-base
     * origin at `(0, HILL_PEAK_Y, 0)` so trunk segments emerge naturally
     * as the first growth phase of the canopy (bias-only growth, no
     * attractor pull yet) and roots branch in the same algorithmic style
     * — one organic system instead of three glued-together pieces.
     *
     * Deterministic from [MOTHER_TREE_SEED]: every generator instance and
     * every C2ME worker thread initialises this to bit-identical content.
     * The `lazy` synchronization mode is `SYNCHRONIZED` by default, so
     * concurrent first reads are safe.
     */
    private val motherTreeSkeleton: TreeSkeleton by lazy {
        val baseX = MOTHER_TREE_CX.toDouble()
        val baseY = HILL_PEAK_Y.toDouble()
        val baseZ = MOTHER_TREE_CZ.toDouble()

        // CANOPY: wide flat ellipsoid centred at y=130 — attractors span
        // y≈115..145. Origin y=42 → ~30 iterations of bias-only growth
        // before attractors latch on, then branching into the canopy
        // volume. Tree top reaches ~y=150 (108 blocks above the hill
        // peak).
        //
        // rx=rz=70, ry=15 — a wide flat crown. Volume ≈ 308k blocks,
        // count 1300 → ~one attractor per 240 blocks.
        //
        // **trunkLean=0.35** drives bias-only growth through smooth 1D
        // noise on iter, with different X/Z frequencies — the trunk leans
        // in a slowly-rotating direction as it rises (natural lean +
        // twist, not zigzag).
        //
        // thicknessScale=0.4 keeps internal branches off the cap so we
        // see a real thickness gradient from trunk to twigs.
        //
        // buttressFlare=5 over buttressRange=14 — the trunk's bottom 14
        // blocks are visibly chunkier (radius up to 10 ≈ 21-block
        // diameter at the very base), so the trunk reads as a flared
        // buttress meeting the ground, not a pole.
        // Main canopy cloud + two mid-trunk attractor clusters that pull
        // out major branches off the trunk *below* the main canopy. Each
        // mid-cluster carries ~70 attractors at radius 14, positioned ~22
        // blocks off-axis at trunk-height — the trunk's bias-only growth
        // is pulled toward them when its tip enters their attractionDist
        // window, producing two distinct large limbs at different heights
        // and on opposite sides before reaching the main canopy.
        val mainCanopy = WogorTreeSkeleton.ellipsoidAttractors(
            cx = baseX, cy = 252.0, cz = baseZ,
            rx = 140.0, ry = 30.0, rz = 140.0,
            count = 5200, seed = MOTHER_TREE_SEED,
        )
        val branch1 = WogorTreeSkeleton.ellipsoidAttractors(
            cx = baseX + MAJOR_BRANCH_OFFSET, cy = baseY + 80.0, cz = baseZ + 12.0,
            rx = 28.0, ry = 18.0, rz = 28.0,
            count = 280, seed = MOTHER_TREE_SEED + 10,
        )
        val branch2 = WogorTreeSkeleton.ellipsoidAttractors(
            cx = baseX - MAJOR_BRANCH_OFFSET, cy = baseY + 110.0, cz = baseZ - 12.0,
            rx = 28.0, ry = 18.0, rz = 28.0,
            count = 280, seed = MOTHER_TREE_SEED + 11,
        )
        val attractors = DoubleArray(mainCanopy.size + branch1.size + branch2.size)
        System.arraycopy(mainCanopy, 0, attractors, 0, mainCanopy.size)
        System.arraycopy(branch1, 0, attractors, mainCanopy.size, branch1.size)
        System.arraycopy(branch2, 0, attractors, mainCanopy.size + branch1.size, branch2.size)

        WogorTreeSkeleton.build(
            originX = baseX, originY = baseY, originZ = baseZ,
            attractors = attractors,
            defaultDir = doubleArrayOf(0.0, 1.0, 0.0),
            attractionDist = 36.0,
            killDist = 8.0,
            stepSize = 5.0,
            maxIterations = 180,
            branchBias = 0.12,
            maxThickness = MOTHER_TREE_MAX_THICKNESS,
            thicknessScale = 0.4,
            buttressFlare = 26,
            buttressRange = 45.0,
            trunkLean = 0.35,
            gravity = 0.15,
        )
    }

    /**
     * Paint the Mother Tree into the given chunk's slice using the lazily-
     * computed canopy and root skeletons (see [motherTreeSkeleton]). The
     * tree is one organic system: same space-colonization algorithm grows
     * the upward branches and the downward roots from a shared trunk-base
     * node, with Da Vinci's rule giving each segment a thickness
     * proportional to the number of leaf descendants it supports.
     *
     * Called from every chunk in [fillFromNoise]; an O(1) footprint reject
     * up front bails on chunks far enough from the tree to contribute
     * nothing.
     */
    private fun paintMotherTreeInto(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
    ) {
        val canopy = motherTreeSkeleton

        // Quick reject: distance from chunk's nearest XZ corner to origin
        // compared to the actual computed reach (canopy or hand-coded roots,
        // whichever sprawls further).
        val nearestX = (MOTHER_TREE_CX - chunkX0).coerceIn(0, 15) + chunkX0
        val nearestZ = (MOTHER_TREE_CZ - chunkZ0).coerceIn(0, 15) + chunkZ0
        val ddx = nearestX - MOTHER_TREE_CX
        val ddz = nearestZ - MOTHER_TREE_CZ
        val maxReach = Math.max(canopy.maxXZReach, MOTHER_TREE_ROOT_REACH_MAX) +
            MOTHER_TREE_MAX_THICKNESS + 2
        if (ddx * ddx + ddz * ddz > maxReach * maxReach) return

        // Canopy + trunk via the space-colonization skeleton (with smooth
        // trunk lean baked into the bias-only growth).
        paintSkeleton(target, chunkX0, chunkZ0, minY, span, canopy, WOGOR_WOOD_Y)

        // Mangrove-style buttress roots — hand-coded arc-and-plunge arms.
        // Space colonization doesn't produce the distinctive buttress
        // silhouette (the right shape is parametric: rise above ground,
        // sweep out, accelerate into the mud), so each arm is painted
        // explicitly here.
        paintMotherTreeRoots(target, chunkX0, chunkZ0, minY, span)

        // Foliage at every canopy tip — wide overlapping blobs with
        // domain warp + two-octave threshold noise. Scaled for the 2×
        // tree.
        paintLeafTips(target, chunkX0, chunkZ0, minY, span, canopy,
            blobRxz = 12, blobRy = 7)

        // Vines off the canopy rim.
        paintHangingVines(target, chunkX0, chunkZ0, minY, span,
            MOTHER_TREE_CX, MOTHER_TREE_CZ,
            footprintR = canopy.maxXZReach + 2,
            maxLen = 12,
            seed = MOTHER_TREE_SEED xor 0x73A1F4C7.toInt())
    }

    /**
     * Paint the Mother Tree's mangrove-style buttress roots — `MOTHER_TREE_ROOT_COUNT`
     * arms radiating from the trunk base, each a parametric arc rasterised
     * through the same **SDF tapered-capsule + bark-noise** rendering path
     * as the canopy branches: the parametric arc is emitted as a chain of
     * `TreeSegment`s, fed one-by-one into [paintTreeSegment]. So the root
     * gets the same Cepero treatment — continuous-Double radius taper,
     * cone-surface bark perturbation, no integer-radius stepping artefacts.
     *
     * Each arm follows a three-phase profile:
     *  1. **Rise** (≈15 %) — quick parabolic climb up to `ARC_PEAK` above
     *     the trunk base.
     *  2. **Walk** (≈55 %) — extended sinusoidal undulation across the
     *     mound, gripping the ground.
     *  3. **Plunge** (≈30 %) — accelerating quadratic descent into the
     *     mud, ending `plungeDepth` below the trunk base.
     *
     * Reach 36..55 blocks (was 24..39) so the roots really walk.
     */
    private fun paintMotherTreeRoots(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
    ) {
        val baseY = HILL_PEAK_Y.toDouble()
        val baseX = MOTHER_TREE_CX.toDouble()
        val baseZ = MOTHER_TREE_CZ.toDouble()
        for (i in 0 until MOTHER_TREE_ROOT_COUNT) {
            val angleHash = hash01(MOTHER_TREE_SEED, i, 51)
            val angle = i * (Math.PI * 2.0 / MOTHER_TREE_ROOT_COUNT) +
                (angleHash - 0.5) * (Math.PI * 2.0 / MOTHER_TREE_ROOT_COUNT) * 0.4
            val cosA = Math.cos(angle)
            val sinA = Math.sin(angle)
            val perpX = -sinA
            val perpZ = cosA

            val reach = 80 + (hash32(MOTHER_TREE_SEED, i, 52) ushr 1 and 0x31)
            val plungeDepth = 40 + (hash32(MOTHER_TREE_SEED, i, 53) ushr 1 and 0x1D)
            val wobblePhase = hash01(MOTHER_TREE_SEED, i, 55) * Math.PI * 2.0
            val wobbleAmp = 2.0 + hash01(MOTHER_TREE_SEED, i, 56) * 4.0
            val walkUndulationPhase = hash01(MOTHER_TREE_SEED, i, 57) * Math.PI * 2.0
            val emergenceDY = (hash32(MOTHER_TREE_SEED, i, 58) ushr 1 and 0x3).toDouble()

            val riseEnd = 0.10 + hash01(MOTHER_TREE_SEED, i, 60) * 0.06
            val walkEnd = 0.70 + hash01(MOTHER_TREE_SEED, i, 61) * 0.08

            val startX = baseX + cosA * ROOT_EMERGE_R
            val startZ = baseZ + sinA * ROOT_EMERGE_R
            // Emerge above the tree mound's plateau surface so the root
            // is visibly above the hill at the trunk.
            val moundAtStart = treeMoundContribution(startX.toInt(), startZ.toInt())
            val moundBaseY = if (moundAtStart > Int.MIN_VALUE) moundAtStart.toDouble() else baseY
            val startY = moundBaseY + emergenceDY + 1.0

            // Emit one TreeSegment per arc-step, fed straight to the
            // SDF capsule rasterizer.
            //   - 1-block step granularity (cheap; SDF handles thick caps)
            //   - per-arm chunk-AABB reject via [paintTreeSegment]'s clip
            val steps = reach
            var prevX = startX; var prevY = startY; var prevZ = startZ
            var prevR = ROOT_BASE_RADIUS
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                val r = t * reach
                val wob = Math.sin(t * Math.PI * 3.0 + wobblePhase) * wobbleAmp
                val px = startX + r * cosA + perpX * wob
                val pz = startZ + r * sinA + perpZ * wob

                val py = when {
                    t < riseEnd -> {
                        // RISE: from startY (just above tree mound surface
                        // at the emergence point) up to startY + ARC_PEAK.
                        val rT = t / riseEnd
                        startY + ARC_PEAK * (1.0 - (1.0 - rT) * (1.0 - rT))
                    }
                    t < walkEnd -> {
                        // WALK: track the LOCAL hill surface and add a
                        // half-rectified sinusoidal gnarly bump. The root
                        // mostly sits 1 block above the hill, but
                        // periodically rises up to GNARLY_BUMP_AMP higher
                        // before settling back — the "rising up from the
                        // hill sometimes" effect.
                        val hillY = effectiveSurfaceYAt(px.toInt(), pz.toInt()).toDouble()
                        val wT = (t - riseEnd) / (walkEnd - riseEnd)
                        val bumpRaw = Math.sin(wT * Math.PI * 5.0 + walkUndulationPhase)
                        val bump = Math.max(0.0, bumpRaw) * GNARLY_BUMP_AMP
                        hillY + 1.0 + bump
                    }
                    else -> {
                        // PLUNGE: dive from the local hill surface down
                        // into the mud. Hill-relative so a root that
                        // ended its walk on a tall mound plunges from
                        // there, not from a constant baseY.
                        val hillHere = effectiveSurfaceYAt(px.toInt(), pz.toInt()).toDouble()
                        val pT = (t - walkEnd) / (1.0 - walkEnd)
                        hillHere + 1.0 - pT * pT * plungeDepth
                    }
                }

                // Continuous-Double radius profile: stays near base
                // through the rise + first half of the walk, tapers
                // smoothly to the tip during the late walk + plunge.
                val curR = when {
                    t < walkEnd * 0.5 -> ROOT_BASE_RADIUS
                    t < walkEnd -> ROOT_BASE_RADIUS *
                        (1.0 - (t - walkEnd * 0.5) / (walkEnd * 0.5) * 0.4)
                    else -> ROOT_BASE_RADIUS * 0.6 *
                        (1.0 - (t - walkEnd) / (1.0 - walkEnd) * 0.9)
                }

                paintTreeSegment(target, chunkX0, chunkZ0, minY, span,
                    TreeSegment(prevX, prevY, prevZ, px, py, pz, prevR, curR),
                    WOGOR_WOOD_Y)

                prevX = px; prevY = py; prevZ = pz; prevR = curR
            }
        }
    }


    companion object {
        // ---- world extent ----
        const val MIN_Y = 0
        const val WORLD_HEIGHT = 320
        const val MAX_BUILD_Y = MIN_Y + WORLD_HEIGHT - 1
        /** Sea level — raised to vanilla 64 for correct sky rendering. */
        const val SEA_LEVEL_Y = 64

        // ---- terrain shaping ----
        /** Surface baseline 2 blocks below sea level, so the median
         *  terrain is shallowly underwater (pools) but high points rise
         *  above. */
        private const val SURFACE_BASE_Y = 62
        /** Regional (coarse-noise) lift, ±blocks. Drives the big-picture
         *  "deep pool here, mud bar there" partition. 12 → pools floor
         *  near y=47 (17-block-deep water beneath y=64 sea level), dry
         *  highs near y=77. */
        private const val REGIONAL_AMPLITUDE = 12.0
        /** Fine (octave-stack) lift, ±blocks. Keeps the regional surface
         *  from reading as flat plateaus. */
        private const val FINE_AMPLITUDE = 2.0
        private const val HILL_RADIUS = 48
        private const val HILL_PEAK_LIFT = 12.0    // peak surface ~y=74
        const val HILL_PEAK_Y = SURFACE_BASE_Y + HILL_PEAK_LIFT.toInt()  // 74

        // ---- Central geometric features (moat / cave / heart tunnel) ----
        // The moat is modelled as a U-shape depression in the *effective
        // surface*, not as a per-voxel column override. That's what keeps
        // the Mother Tree (canopy at y≈130, roots up to radius 145)
        // intact: the depressed surface only changes what counts as mud
        // vs. water vs. air in the natural-terrain sense, and the
        // feature cache (canopy/roots/leaves) still wins over that
        // surface via the existing priority chain.

        /** Moat inner radius — well inside the mound's outer rim
         *  ([TREE_MOUND_OUTER_R] = 140), so the depression takes a real
         *  bite out of the hill's outer slope rather than just nicking it. */
        private const val MOAT_INNER_R = 110.0
        /** Moat outer radius — past the mound's outer rim and past the
         *  Mother Tree's [MOTHER_TREE_ROOT_REACH_MAX] = 145, with enough
         *  margin that the outer side of the moat sits well inside
         *  normal terrain. 110 → 185 is a 75-block-wide ring. */
        private const val MOAT_OUTER_R = 185.0
        /** Mud floor at the deepest point of the U-shape (sea level −
         *  6 = 58). At the moat edges this blends smoothly back up to
         *  the natural / mound surface. */
        private const val MOAT_FLOOR_Y = SEA_LEVEL_Y - 6
        /** Moat boundary noise frequency. Long wavelength so the ring's
         *  inner/outer edges undulate in big lobes rather than wobble
         *  on a per-block scale. */
        private const val MOAT_BOUNDARY_NOISE_FREQ = 0.04
        /** Moat boundary noise amplitude (blocks). ±8 lets the ring
         *  pinch in and bulge out organically. */
        private const val MOAT_BOUNDARY_NOISE_AMP = 8.0

        /** Central cave base radius. Round in XZ — single radius makes
         *  the cave read as a sphere, not an oblong. The noise-warped
         *  wall + the tunnel-direction bulge then break the perfect
         *  circle organically. */
        private const val CAVE_BASE_RADIUS = 13.0
        /** Y of the cave's geometric centre — the "heart" itself. Sits
         *  4 blocks above sea level so the cave reads as an elevated
         *  chamber, with the lantern lifted up to match. The mound
         *  surface at origin is at [HILL_PEAK_Y] + [TREE_MOUND_BONUS]
         *  ≈ 79, so the cave ceiling at `HEART_Y + 8 = 76` still sits
         *  below the trunk base. */
        private const val HEART_Y = SEA_LEVEL_Y + 4
        /** Cave floor base Y. Centred symmetrically around [HEART_Y]
         *  with the ceiling, so the cave's geometric centre is exactly
         *  the heart at (0, HEART_Y, 0). */
        private const val CAVE_FLOOR_BASE_Y = HEART_Y - 8
        /** Cave ceiling base Y. Symmetric with the floor around
         *  [HEART_Y]. */
        private const val CAVE_CEILING_BASE_Y = HEART_Y + 8
        /** Cave wall noise amplitude in *blocks*. Smaller value (was
         *  18 %) — the cave wants to read as round-but-natural, not
         *  lumpy. */
        private const val CAVE_BOUNDARY_NOISE_AMP = 1.8
        /** Per-XZ floor / ceiling Y noise amplitude (blocks). Mild
         *  undulation only. */
        private const val CAVE_Y_NOISE_AMP = 2.0
        /** Width of the Gaussian bulge in the cave wall *toward the
         *  heart-tunnel direction* (radians). Smaller σ = sharper
         *  pinch around the tunnel angle; 0.45 ≈ ±26°. */
        private const val CAVE_TUNNEL_BULGE_SIGMA = 0.45
        /** Peak height of the tunnel-direction bulge (blocks). The
         *  bulge extends the effective cave radius by this much at the
         *  tunnel angle, tapering off exponentially as the angle
         *  diverges — that's how the cave wall blends smoothly into
         *  the tunnel mouth instead of meeting it at a hard joint. */
        private const val CAVE_TUNNEL_BULGE_AMP = 7.0

        // ---- Rivers radiating from the moat ----
        /** How many evenly-angled rivers spawn off the moat. 8 → one
         *  river per 45° of the moat circumference. */
        private const val RIVER_COUNT = 8
        /** Steps per river. 200 · 1.5 = 300 blocks of reach from start. */
        private const val RIVER_MAX_STEPS = 200
        private const val RIVER_STEP_LEN = 1.5
        /** Radius of the 2D water-circle painted at each path point —
         *  5-block-wide channel, same width as vanilla rivers. */
        private const val RIVER_RADIUS = 3
        /** Mud bed Y. Water column runs from here up to [SEA_LEVEL_Y]. */
        private const val RIVER_BED_Y = SEA_LEVEL_Y - 4
        /** Air-buffer top Y. Above-water columns are carved out to here
         *  so low ridges along the meander don't dam the channel. */
        private const val RIVER_TOP_Y = SEA_LEVEL_Y + 4
        /** Per-step yaw random-walk amplitude (radians). */
        private const val RIVER_YAW_TURN = 0.35
        /** Fraction of the yaw → radial-direction difference applied per
         *  step. Soft bias — keeps the river heading outward without
         *  killing the meander. */
        private const val RIVER_RADIAL_BIAS = 0.05
        /** Each step pulls Y toward sea level − 1 by this fraction. */
        private const val RIVER_Y_TRACK_BIAS = 0.4

        /** Reject chunks whose nearest corner is past this squared
         *  distance from origin — beyond it no river can possibly reach. */
        private const val RIVER_REACH_PLUS_PAD_SQ: Long =
            ((MOAT_OUTER_R + 2.0 + RIVER_MAX_STEPS * RIVER_STEP_LEN +
                RIVER_RADIUS).toLong() + 4) *
            ((MOAT_OUTER_R + 2.0 + RIVER_MAX_STEPS * RIVER_STEP_LEN +
                RIVER_RADIUS).toLong() + 4)

        // ---- Heart tunnel skeleton (cave → moat) ----
        // Space-colonisation algorithm (Runions et al. 2007) — same
        // machinery as the Mother Tree's [WogorTreeSkeleton], tuned
        // for an underground tunnel network constrained to a band
        // around sea level. The trunk grows from the heart at world
        // origin toward an attractor cloud densely packed around
        // the moat ring; branches emerge naturally where intermediate
        // attractors pull tips sideways, and dead-end tips host
        // craggy pool chambers.
        //
        // See [HeartTunnelSkeleton] for the algorithm.

        /** Y range half-width around sea level the skeleton can
         *  occupy. Tunnels stay within ±5 blocks of sea level. */
        private const val HEART_Y_RANGE = 5

        /** Skeleton step length in blocks — the length of every
         *  segment grown by space colonisation. */
        private const val HEART_STEP_LEN = 8.0
        /** Hard safety cap on SCA iterations. The algorithm
         *  terminates earlier whenever the attractor cloud is
         *  exhausted; this is the cliff if a degenerate attractor
         *  placement would otherwise loop. */
        private const val HEART_MAX_ITERATIONS = 250
        /** Max distance from a node at which an attractor can pull
         *  on it. Bigger → fewer, longer branches; smaller → more,
         *  shorter branches. */
        private const val HEART_ATTRACTION_DIST = 36.0
        /** Distance at which a node consumes an attractor. Must be
         *  ≥ step size or the algorithm overshoots. */
        private const val HEART_KILL_DIST = 12.0
        /** Magnitude of the bias vector added to attractor pulls and
         *  used for bias-only growth. Small enough that attractors
         *  dominate the heading once they latch on; large enough
         *  that bias-only fallback points the trunk roughly toward
         *  the moat. */
        private const val HEART_BIAS_STRENGTH = 0.15

        // ---- Attractor cloud ----
        /** Inner radius of the intermediate scatter — set comfortably
         *  past `HEART_ATTRACTION_DIST + HEART_STEP_LEN` so the
         *  heart can't be nearest to any attractor before the trunk
         *  has walked a few bias-only steps outward. Without this
         *  buffer the heart sprouts multiple children simultaneously
         *  (one per attractor cluster pulling on it from different
         *  directions on iteration one) — visible as a "fan of
         *  branches off the cave" rather than a single trunk that
         *  branches further out. */
        private const val HEART_INTERMEDIATE_INNER_R = 60.0
        /** Number of attractors in the moat ring `[MOAT_INNER_R,
         *  MOAT_OUTER_R]`. Dense — this is the goal cloud that pulls
         *  the trunk and its terminating branches all the way to the
         *  moat. */
        private const val HEART_MOAT_ATTRACTOR_COUNT = 90
        /** Intermediate scatter count between
         *  [HEART_INTERMEDIATE_INNER_R] and [MOAT_INNER_R]. These
         *  are the "branching opportunities" — clusters pulling
         *  tips sideways off the trunk into side passages. */
        private const val HEART_INTERMEDIATE_ATTRACTOR_COUNT = 50
        /** Starter attractor count — KEPT AT ZERO. Earlier versions
         *  put 6 starter attractors near the heart so the SCA
         *  latched on iteration one (no bias-only ramp-up). Result
         *  was the heart being the nearest node to multiple
         *  attractors simultaneously, growing one child per
         *  attractor direction over the first few iterations — the
         *  "5 tunnels coming off the cave" fan. Setting this to
         *  zero forces the first several iterations into bias-only
         *  growth (which only ever grows ONE child per iteration,
         *  from the most recent tip), so a clean single trunk
         *  emerges before any attractor can split it. */
        private const val HEART_STARTER_ATTRACTOR_COUNT = 0
        /** Outer radius of the starter cluster — unused while
         *  [HEART_STARTER_ATTRACTOR_COUNT] is zero. Kept as a
         *  named constant so re-enabling the starter cluster (if
         *  attractor density ever needs to bias one way to handle
         *  a sparse RNG roll) is a single number-change. */
        private const val HEART_STARTER_MAX_RADIUS = 25.0

        /** Minimum tunnel radius (blocks). The pipe-model rule
         *  clamps every node's radius to `[rMin, rMax]`; tips end
         *  up at `rMin`, the trunk at `rMax`. */
        private const val HEART_R_MIN = 3.0
        /** Maximum tunnel radius (blocks). The trunk saturates here. */
        private const val HEART_R_MAX = 5.5
        /** Multiplier applied to the raw `√(descendantLeaves)`
         *  pipe-model radius before [HEART_R_MIN]..[HEART_R_MAX]
         *  clamping. Set so the trunk saturates and tips clamp up
         *  from below — values < 1 widen the radius range, values
         *  > 1 push more nodes to the upper clamp. */
        private const val HEART_THICKNESS_SCALE = 1.4

        /** Thickness of the wogor-wood shell painted just outside the
         *  carved interior (in blocks of distance). 1 → the wall is
         *  one block thick. */
        private const val HEART_TUNNEL_SHELL_THICK = 1

        // ---- Dead-end pool chambers ----
        /** Length of the pool capsule past each leaf-tip's dead-end. */
        private const val HEART_POOL_LEN_MIN = 5.0
        private const val HEART_POOL_LEN_MAX = 10.0
        /** Pool radius range. Pool radius is computed as the tip's
         *  own radius + [HEART_POOL_R_BONUS], clamped here, so the
         *  chamber is a visible widening of the passage rather than
         *  a giant cavern. */
        private const val HEART_POOL_R_MIN = 3.0
        private const val HEART_POOL_R_MAX = 5.5
        /** Bonus added to the leaf-tip's radius to compute the pool
         *  capsule radius. */
        private const val HEART_POOL_R_BONUS = 1.5
        /** Per-voxel boundary-noise amplitude on the pool capsule
         *  SDF. With the noise now applied **bulge-only** (see
         *  [paintHeartCapsuleSegment]), the effective radius rides
         *  in `[r, r + amp]` — the pool wall sticks out by up to
         *  3 blocks but never pinches inward, so the inner volume
         *  is solid carve with no vertical-scarring mud intrusions. */
        private const val HEART_POOL_NOISE_AMP = 3.0
        /** Frequency of the pool boundary noise (in voxel units).
         *  0.18 → wavelength ≈ 5–6 blocks, so the wall shows broad
         *  lobes rather than tight ripples. Isotropic (same in X, Y,
         *  Z) via [valueNoise3D]. */
        private const val HEART_POOL_BOUNDARY_NOISE_FREQ = 0.18

        // ---- Vines ----
        /** Per-cell density threshold (0..255). Lower → sparser
         *  curtain. 80 → ~31 % of qualifying ceiling cells get a
         *  vine column, which reads as a generous but not solid
         *  drape. */
        private const val HEART_VINE_DENSITY_THRESHOLD = 80

        // ---- World-span root tip taper ----
        /** Number of trailing path steps over which the tip taper
         *  shrinks the sphere radius from full down to 1 voxel. */
        private const val ROOT_TIP_TAPER_STEPS = 8

        /** Reject chunks past this squared distance from origin.
         *  SCA grows to wherever attractors live; the moat ring
         *  attractors cap the network at `MOAT_OUTER_R`. Worst
         *  case: trunk reaches a moat-ring attractor at
         *  `MOAT_OUTER_R + HEART_KILL_DIST` (a node within kill
         *  distance of an outer-ring attractor), plus the tunnel's
         *  own [HEART_R_MAX] radius, plus the pool extending
         *  `HEART_POOL_LEN_MAX` past that with [HEART_POOL_R_MAX]
         *  of additional radius. Padded an extra 16 for safety. */
        private const val HEART_TUNNEL_REACH_PLUS_PAD_SQ: Long =
            ((MOAT_OUTER_R + HEART_KILL_DIST + HEART_R_MAX +
                HEART_POOL_LEN_MAX + HEART_POOL_R_MAX).toLong() + 16) *
            ((MOAT_OUTER_R + HEART_KILL_DIST + HEART_R_MAX +
                HEART_POOL_LEN_MAX + HEART_POOL_R_MAX).toLong() + 16)

        // ---- Child Tree dispersal ----
        /** Region grid in chunks. Each `N × N` region of chunks may host
         *  at most one Child Tree. 6 → one tree per 96×96-block cell on
         *  average. */
        const val CHILD_TREE_REGION_SIZE_CHUNKS = 6
        const val CHILD_TREE_REGION_SIZE = CHILD_TREE_REGION_SIZE_CHUNKS * 16
        /** Worst-case XZ extent of a Child Tree (canopy reach + buttress
         *  + root walk) for the per-chunk AABB reject. */
        const val CHILD_TREE_MAX_REACH = 80
        /** Minimum centre-to-centre distance between two Child Trees in
         *  adjacent regions. Closer-than-this candidates are rejected via
         *  the lower-seed-wins rule, so child trees stay visually
         *  separated even when adjacent regions both spawn one. */
        const val CHILD_TREE_MIN_SPACING = 90
        /** Continuous radius of each Child Tree root arm at its trunk
         *  end. Scaled to overlap the buttressed trunk so the root
         *  blends in cleanly. */
        const val CHILD_TREE_ROOT_BASE_RADIUS = 3.5

        // ---- Mother Tree (singleton at world origin) ----
        const val MOTHER_TREE_CX = 0
        const val MOTHER_TREE_CZ = 0
        /** Deterministic seed for the Mother Tree's skeleton. */
        const val MOTHER_TREE_SEED = 0x4D6F7468.toInt()  // 'Moth'
        /** Hard cap on Da-Vinci thickness at the trunk base of the Mother
         *  Tree. With thicknessScale=0.4 and ~5000 leaf tips,
         *  0.4·√5000 ≈ 28, clamped to this cap. Combined with the
         *  buttress flare bonus, the trunk base radius can reach
         *  maxThickness + buttressFlare. */
        const val MOTHER_TREE_MAX_THICKNESS = 10
        /** Small trees rejected inside this radius of origin so they don't
         *  spawn inside the Mother Tree's footprint. Computed as the
         *  square of [TREE_MOUND_OUTER_R] — the mound terrain is reserved
         *  for the Mother Tree and her roots. Defined down below as a
         *  `val` so it can reference the const declared further down. */
        @JvmField
        val MOTHER_TREE_EXCLUSION_RADIUS_SQ = 160 * 160

        // ---- Mother Tree buttress roots ----
        /** Major root arms radiating from the trunk base. */
        const val MOTHER_TREE_ROOT_COUNT = 14
        /** Greatest outward reach (in blocks) any root arm can cover, for
         *  the chunk-AABB quick reject. Max per-arm reach is 80 + 50 = 130
         *  (hash range), plus wobble amp ≤ 6.0 and [ROOT_EMERGE_R]. */
        const val MOTHER_TREE_ROOT_REACH_MAX = 145
        /** Height the arching root rises above the trunk base at its peak. */
        const val ARC_PEAK = 5.0
        /** Radial offset *inside* the trunk's buttress at which each root
         *  arm starts. With the trunk's base radius up to 24 (max=10 +
         *  buttress=14), emergence at radius 7 puts the root's first SDF
         *  capsule cluster well inside the trunk. */
        const val ROOT_EMERGE_R = 7.0
        /** Continuous-Double radius at the trunk-side end of each root
         *  arm. Scaled to match the 2× trunk so the first cluster (radius
         *  9 at offset 7) overlaps the buttressed trunk meaningfully.
         *  Tapers to ~0.6 of this by the tip. */
        const val ROOT_BASE_RADIUS = 9.0

        // ---- Major branches off the trunk ----
        /** XZ offset of the two mid-trunk attractor clusters that pull
         *  major limbs off the trunk. 44 sits past the buttressed trunk's
         *  base radius (≤ 24) so the resulting limb visibly emerges from
         *  the trunk surface. */
        const val MAJOR_BRANCH_OFFSET = 44.0

        // ---- Tree-centred smooth hill ----
        /** Inner radius of the flat-top plateau of the tree's hill. */
        const val TREE_MOUND_INNER_R = 35
        /** Outer radius where the mound has tapered fully to natural
         *  terrain. Wide zone → long gentle blend with surroundings. */
        const val TREE_MOUND_OUTER_R = 140
        /** Height of the plateau above the natural hill peak. */
        const val TREE_MOUND_BONUS = 5
        /** Rim-domain-warp noise frequency. Longer wavelength for the
         *  bigger hill. */
        const val TREE_MOUND_RIM_NOISE_FREQ = 0.018
        /** Rim-warp amplitude — ±14 blocks of irregular outline. */
        const val TREE_MOUND_RIM_NOISE_AMP = 14.0
        /** Plateau-bumpiness noise frequency. */
        const val TREE_MOUND_PLATEAU_NOISE_FREQ = 0.07
        /** Plateau bump amplitude — ±2.5 blocks of natural undulation. */
        const val TREE_MOUND_PLATEAU_NOISE_AMP = 2.5
        /** Amplitude of the gnarly bump on root walk: the root rises up
         *  to this many blocks above the hill surface periodically. */
        const val GNARLY_BUMP_AMP = 10.0

        // ---- surface-root tunnel carvers ----
        /** Radius (in blocks) of the sphere rasterised at every path step.
         *  3 → tubes are ~7 voxels across, comparable to a vanilla
         *  cave-carver passage. With [TUNNEL_STEP_LEN] = 1.5 every pair of
         *  adjacent path steps' spheres overlaps by ~60 % of volume — that
         *  overlap is what makes the painted tube *continuous*. */
        private const val ROOT_TUBE_RADIUS = 3

        /** Region tile size in chunks. The world is tiled by this-sized
         *  squares and every region deterministically spawns one tunnel.
         *  3 chunks (48 blocks) keeps the tunnel network dense enough
         *  that every chunk gets touched by at least one path while still
         *  paying a manageable cache footprint. */
        private const val TUNNEL_REGION_SIZE_CHUNKS = 3
        /** Region tile size in *blocks*. Power-of-2-friendly width (48 →
         *  64 alignment isn't important here — we use the value to mask the
         *  start offset, see [buildTunnelPaths]). */
        private const val TUNNEL_REGION_SIZE = TUNNEL_REGION_SIZE_CHUNKS * 16
        /** How many adjacent regions a chunk must scan to find every
         *  tunnel that could possibly touch it. Worst-case reach is
         *  `TUNNEL_MAX_STEPS · TUNNEL_STEP_LEN + TUNNEL_BRANCH_MAX_STEPS
         *  · TUNNEL_STEP_LEN ≈ 120 + 60 = 180 blocks` ⇒ 3.75 regions →
         *  round up to 4. */
        private const val TUNNEL_REGION_SEARCH = 4

        /** Number of forward steps in each tunnel path. 80 · 1.5 ≈ 120
         *  blocks total length — long enough to bridge several neighbour
         *  regions, short enough that the cached `IntArray` is small. */
        private const val TUNNEL_MAX_STEPS = 80
        /** Distance moved per step (blocks). Combined with [ROOT_TUBE_RADIUS]
         *  this controls the sphere overlap that produces continuity. */
        private const val TUNNEL_STEP_LEN = 1.5

        /** Per-step yaw random-walk amplitude (radians). ±0.20 is the
         *  same order vanilla `CaveWorldCarver` uses for its angle nudges. */
        private const val TUNNEL_YAW_TURN = 0.40
        /** Per-step pitch random-walk amplitude (radians). Smaller than
         *  yaw so tunnels lean toward staying near horizontal — Y is
         *  mostly driven by [TUNNEL_Y_TRACK_BIAS] instead. */
        private const val TUNNEL_PITCH_TURN = 0.12
        /** Hard limit on the pitch magnitude so a tunnel never points
         *  straight up or down. */
        private const val TUNNEL_PITCH_LIMIT = 0.5

        /** Each step pulls Y toward its sine target by this fraction. A
         *  small bias gives smooth elastic tracking instead of clamped
         *  snapping — the tunnel curves into and out of the target Y
         *  rather than oscillating around it stiffly. The sine
         *  amplitude, period, and phase are picked per-path inside
         *  `walkPath` so different tunnels reach different heights at
         *  different rates. */
        private const val TUNNEL_Y_TRACK_BIAS = 0.07

        // ---- Branching ----
        /** Step index at which branch attempts begin — small warm-up so
         *  branches don't spawn right at the trunk's mouth. */
        private const val TUNNEL_BRANCH_FIRST_STEP = 12
        /** Branch attempts happen every this-many main-path steps. With
         *  [TUNNEL_MAX_STEPS] = 80 and stride 16, that's ~4 candidate
         *  spawn points along each main path; roughly 1 in 4 actually
         *  branches, so each region averages ~1 branch — adds variety
         *  without overwhelming the chunk paint budget. */
        private const val TUNNEL_BRANCH_STRIDE = 16
        /** Lower bound on branch length, in steps. */
        private const val TUNNEL_BRANCH_MIN_STEPS = 20
        /** Upper bound on branch length, in steps. 40 · 1.5 = 60 blocks
         *  — long enough to feel like a real fork rather than a stub,
         *  short enough to stay roughly within its parent region's
         *  neighbours. */
        private const val TUNNEL_BRANCH_MAX_STEPS = 40

        /** Squared radius around world origin where tunnels are
         *  suppressed — both as a start exclusion and per-point inside
         *  [paintTunnelPathInto]. Sized to cover the Mother Tree's main
         *  trunk + buttressed base. */
        private const val TUNNEL_MOTHER_TREE_EXCLUSION_SQ = 30L * 30L

        private fun smoothstep01(x: Double): Double {
            val t = x.coerceIn(0.0, 1.0)
            return t * t * (3.0 - 2.0 * t)
        }

        // ---- bark noise ----
        /** Spatial frequency of the bark perturbation. 0.35 → wavelength
         *  ≈ 2.9 blocks, giving bark-scale knobs and grooves. */
        private const val BARK_FREQ = 0.35

        // ---- leaf-blob noise (Cepero crown) ----
        /** Low-frequency noise on the leaf-blob threshold — period
         *  ≈ 7 blocks. Breaks the smooth ellipsoid into clumps. */
        private const val LEAF_LOW_FREQ = 0.14
        /** High-frequency noise — period ≈ 1.5 blocks, per-voxel detail
         *  at the rim. */
        private const val LEAF_HIGH_FREQ = 0.65
        /** Amplitude of the low-freq perturbation in normalized-distance²
         *  units. ±0.30 → clumps that can pull the rim ~15% in or out. */
        private const val LEAF_LOW_AMP = 0.30
        /** Amplitude of the per-voxel high-freq perturbation. ±0.25 → the
         *  rim breaks into individual leaf-pixels. */
        private const val LEAF_HIGH_AMP = 0.25
        /** Worst-case threshold for the AABB cheap-reject — the maximum
         *  value `1.0 + LEAF_LOW_AMP + LEAF_HIGH_AMP` can reach. */
        private const val LEAF_THRESHOLD_MAX = 1.0 + LEAF_LOW_AMP + LEAF_HIGH_AMP
        /** Domain-warp frequency — low (period ≈ 10 blocks) so the warp
         *  is smooth over the canopy and produces large lobes, not
         *  speckle. */
        private const val LEAF_WARP_FREQ = 0.10
        /** Maximum warp displacement in blocks. The voxel's test position
         *  shifts by up to ±LEAF_WARP_AMP in each axis, pulling the blob
         *  into lobes and concavities instead of an ellipsoid. */
        private const val LEAF_WARP_AMP = 4.0
        /** Integer rounding of [LEAF_WARP_AMP] for the AABB margin. */
        private const val LEAF_WARP_AMP_INT = 4

        // ---- bark3Hash corner cache ----------------------------------
        //
        // Per-worker-thread direct-mapped cache fronting [bark3Hash]. The
        // observation: valueNoise3D evaluates bark3Hash at 8 integer
        // corners per call, and the corners overlap massively between
        // adjacent voxels — especially in the low-frequency warp
        // octave (LEAF_WARP_FREQ = 0.10 ⇒ integer floor changes every
        // ~10 blocks, so a 25-block-wide leaf blob hits only ~3³ = 27
        // unique corners despite tens of thousands of bark3Hash calls).
        //
        // Direct-mapped (no probing). 8192 slots × 16 bytes = 128 KiB per
        // worker thread; with 15 Worker-Main threads that's ~2 MB of
        // total chunkgen-side memory. Sized to fit the high-frequency
        // octave's working set (~4000 unique corners across a typical
        // leaf blob) with comfortable headroom, so the warp + low
        // entries stop being evicted by the high-freq churn — the
        // v3→v4 trace showed the cache was hitting ~84 % overall but
        // was bottlenecked by ~4000 unique high-freq corners colliding
        // with ~150 warp+low corners in 4096 slots.
        //
        // Sentinel key Long.MIN_VALUE marks empty slots so the first
        // lookup at coords (0,0,0) isn't confused with a zero-init slot.
        //
        // Key packs three signed ints into 64 bits, 21 bits per axis.
        // Coordinates beyond ±1 M wrap; that's well outside any single
        // chunk's working set, and the per-slot key check catches the
        // collision as a miss (recompute) — never as incorrect data.
        //
        // The cache is read-only across threads; each Worker-Main has
        // its own copy via ThreadLocal so no synchronisation is needed.

        private const val BARK_CACHE_SIZE = 8192
        private const val BARK_CACHE_MASK = BARK_CACHE_SIZE - 1   // 0x1FFF
        private const val BARK_KEY_AXIS_MASK = 0x1FFFFFL          // 21 bits
        private const val BARK_KEY_EMPTY = Long.MIN_VALUE
        /** 64-bit golden-ratio prime for Fibonacci hashing. Multiplying
         *  the packed key by this and taking the top bits gives a
         *  uniform slot distribution regardless of the key's bit
         *  layout — the previous `lo32 ^ hi32` mix collided badly
         *  given the 21-bit-per-axis packing. */
        private const val BARK_FIBONACCI_HASH = -7046029254386353133L  // 0x9E3779B97F4A7C15
        /** Bit-position offsets for the Y and Z axes in the packed
         *  3-tuple key. Used by [valueNoise3D] to derive the 7
         *  neighbour corner keys from a precomputed base key
         *  (additive arithmetic only — no per-corner repack). */
        private const val KEY_Y_INC: Long = 1L shl 21
        private const val KEY_Z_INC: Long = 1L shl 42

        /** Visibility: package-private (`internal` in Kotlin) so
         *  outer-class members like [paintLeafBlob] and
         *  [paintTreeSegment] can hoist the [barkCache].get()
         *  call to the function entry and pass the same cache
         *  reference into the cache-taking [valueNoise3D]
         *  overload. Not part of the public API. */
        internal class BarkCache {
            @JvmField val keys = LongArray(BARK_CACHE_SIZE) { BARK_KEY_EMPTY }
            @JvmField val vals = DoubleArray(BARK_CACHE_SIZE)
        }

        internal val barkCache: ThreadLocal<BarkCache> = ThreadLocal.withInitial { BarkCache() }

        /** Cache-fronted [bark3Hash]. Takes the per-thread cache
         *  AS A PARAMETER so the caller acquires it once (via
         *  [barkCache].get()) and reuses it across all 8 corner
         *  lookups in a single [valueNoise3D] evaluation. The
         *  previous design did the ThreadLocal.get inside this
         *  function — spark saw 14+ s of `ThreadLocal.get` /
         *  `ThreadLocalMap.getEntry*` self-time across a 213 s
         *  sample, dwarfing the bark3Hash savings the cache was
         *  meant to deliver. Hoisting it out drops the 8× TL
         *  access per noise eval to 1×. */
        /** Cache-fronted [bark3Hash] taking the precomputed packed
         *  key plus the raw integer coords (needed for the miss
         *  path). The cache arrays are passed in directly so the
         *  caller can hoist `cache.keys` and `cache.vals` field
         *  accesses out of the inner loop. Used from [valueNoise3D]
         *  where every call computes 8 corner keys by incrementing
         *  a base key in fixed bit positions instead of re-packing
         *  three int→long conversions per corner. */
        @JvmStatic
        private fun bark3HashCached(
            keys: LongArray, vals: DoubleArray,
            key: Long, x: Int, y: Int, z: Int,
        ): Double {
            // Fibonacci hashing — `key * φ ushr (64 − log2(SIZE))`
            // takes the top log2(SIZE) bits of the product, which
            // depend on every input bit and give a near-uniform slot
            // distribution even when the input key has structured bits
            // (which ours does — three 21-bit fields).
            val slot = ((key * BARK_FIBONACCI_HASH) ushr (64 - 13)).toInt() and BARK_CACHE_MASK
            if (keys[slot] == key) return vals[slot]
            val v = bark3Hash(x, y, z)
            keys[slot] = key
            vals[slot] = v
            return v
        }

        /** xxhash mix on 3 integer coords for bark noise. Direct
         *  computation; for cache-fronted lookups call
         *  [bark3HashCached] instead.
         *
         *  `@JvmStatic` matters here: this is called 8× per
         *  [valueNoise3D] (through [bark3HashCached]), and the cache
         *  miss path needs to invoke this without going through the
         *  synthetic Companion accessor. */
        @JvmStatic
        private fun bark3Hash(x: Int, y: Int, z: Int): Double {
            var h = x * 0x9E3779B1.toInt() xor
                (y * 0x85EBCA77.toInt()) xor
                (z * 0xC2B2AE3D.toInt())
            h = (h xor (h ushr 15)) * 0x2C1B3C6D.toInt()
            h = (h xor (h ushr 12)) * 0x297A2D39.toInt()
            h = h xor (h ushr 15)
            return (h and 0x7FFFFFFF) / 2147483648.0
        }

        /** Trilinear-interpolated smooth value-noise in 3D → [0, 1).
         *  Used by the bark-rasterizer to perturb the cone surface; the
         *  smoothstep curve on the interpolation parameters ensures the
         *  perturbation is C¹-continuous so the bark looks like bumps,
         *  not aliasing. */
        /** Convenience overload — acquires the per-thread cache
         *  via [barkCache].get() and forwards. Use this from
         *  cold callers (heart tunnel/capsule, the degenerate
         *  sphere fallback). Hot callers (paintLeafBlob,
         *  paintTreeSegment) should acquire the cache themselves
         *  ONCE at function entry and call the [BarkCache]-taking
         *  overload below — that hoist eliminates a
         *  ThreadLocal.get for every noise evaluation, which
         *  with ~200M noise evals per ~200s sample is millions
         *  of saved TL lookups per second. */
        @JvmStatic
        fun valueNoise3D(x: Double, y: Double, z: Double): Double =
            valueNoise3D(barkCache.get(), x, y, z)

        /** The real value-noise evaluation. Caller supplies the
         *  cache; one [BarkCache] reference is shared across all
         *  8 corner lookups in this call. Marked `internal`
         *  because [BarkCache] is an internal type — public
         *  callers go through the no-arg overload above. */
        @JvmStatic
        internal fun valueNoise3D(cache: BarkCache, x: Double, y: Double, z: Double): Double {
            val ix = Math.floor(x).toInt(); val fx = x - ix
            val iy = Math.floor(y).toInt(); val fy = y - iy
            val iz = Math.floor(z).toInt(); val fz = z - iz
            val sx = fx * fx * (3.0 - 2.0 * fx)
            val sy = fy * fy * (3.0 - 2.0 * fy)
            val sz = fz * fz * (3.0 - 2.0 * fz)
            // Hoist `cache.keys` and `cache.vals` to locals so we
            // don't reread the object field 16 times across the 8
            // lookups, and precompute the (ix, iy, iz) corner's
            // packed key once. The other 7 corner keys are derived
            // by adding fixed bit offsets — incrementing ix by 1
            // is just `+ 1L`, incrementing iy by 1 is `+ KEY_Y_INC`,
            // incrementing iz by 1 is `+ KEY_Z_INC`. This collapses
            // 8 redundant int→long-pack sequences (3 ANDs + 2 shifts
            // + 2 ORs each) into 1 pack + 7 add-immediates.
            val cacheKeys = cache.keys
            val cacheVals = cache.vals
            val key000 = (ix.toLong() and BARK_KEY_AXIS_MASK) or
                ((iy.toLong() and BARK_KEY_AXIS_MASK) shl 21) or
                ((iz.toLong() and BARK_KEY_AXIS_MASK) shl 42)
            val c000 = bark3HashCached(cacheKeys, cacheVals, key000,                                  ix,     iy,     iz    )
            val c100 = bark3HashCached(cacheKeys, cacheVals, key000 + 1L,                              ix + 1, iy,     iz    )
            val c010 = bark3HashCached(cacheKeys, cacheVals, key000 + KEY_Y_INC,                       ix,     iy + 1, iz    )
            val c110 = bark3HashCached(cacheKeys, cacheVals, key000 + 1L + KEY_Y_INC,                  ix + 1, iy + 1, iz    )
            val c001 = bark3HashCached(cacheKeys, cacheVals, key000 + KEY_Z_INC,                       ix,     iy,     iz + 1)
            val c101 = bark3HashCached(cacheKeys, cacheVals, key000 + 1L + KEY_Z_INC,                  ix + 1, iy,     iz + 1)
            val c011 = bark3HashCached(cacheKeys, cacheVals, key000 + KEY_Y_INC + KEY_Z_INC,           ix,     iy + 1, iz + 1)
            val c111 = bark3HashCached(cacheKeys, cacheVals, key000 + 1L + KEY_Y_INC + KEY_Z_INC,      ix + 1, iy + 1, iz + 1)
            val cx00 = c000 + (c100 - c000) * sx
            val cx10 = c010 + (c110 - c010) * sx
            val cx01 = c001 + (c101 - c001) * sx
            val cx11 = c011 + (c111 - c011) * sx
            val cxy0 = cx00 + (cx10 - cx00) * sy
            val cxy1 = cx01 + (cx11 - cx01) * sy
            return cxy0 + (cxy1 - cxy0) * sz
        }

        // ---- block constants ----
        private val AIR: BlockState = Blocks.AIR.defaultBlockState()
        private val BEDROCK: BlockState = Blocks.BEDROCK.defaultBlockState()
        private val MUD: BlockState = Blocks.MUD.defaultBlockState()
        private val WATER: BlockState = Blocks.WATER.defaultBlockState()
        private val MANGROVE_LEAVES: BlockState = Blocks.MANGROVE_LEAVES.defaultBlockState()
            .setValue(BlockStateProperties.PERSISTENT, true)
        /** Sea lantern — vanilla emissive block. Sprinkled at ~0.1 % of
         *  canopy leaves so the tree glows at night. */
        private val SEA_LANTERN: BlockState = Blocks.SEA_LANTERN.defaultBlockState()

        // Lazy because EKBlocks registration runs at mod-init; these references must
        // not be resolved before that. Object companion `val` initialisers fire on
        // class-load (codec resolution), which is too early on Forge.
        private val WOGOR_LOG_Y: BlockState by lazy {
            EKBlocks.WOGOR_LOG.get().defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.Y)
        }
        private val WOGOR_LOG_X: BlockState by lazy {
            EKBlocks.WOGOR_LOG.get().defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.X)
        }
        private val WOGOR_LOG_Z: BlockState by lazy {
            EKBlocks.WOGOR_LOG.get().defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.Z)
        }
        private val WOGOR_WOOD_Y: BlockState by lazy {
            EKBlocks.WOGOR_WOOD.get().defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.Y)
        }
        private val WOGOR_WOOD_X: BlockState by lazy {
            EKBlocks.WOGOR_WOOD.get().defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.X)
        }
        private val WOGOR_WOOD_Z: BlockState by lazy {
            EKBlocks.WOGOR_WOOD.get().defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.Z)
        }

        /** Vine block states with one of the four cardinal-face properties
         *  set true. Worldgen sets the face directly rather than relying on
         *  the post-placement update tick — the rendered vine strip is the
         *  same regardless of which cardinal is chosen, so picking by hash
         *  gives visual variety along a canopy's rim. */
        private val VINE_BLOCKS: Array<BlockState> by lazy {
            arrayOf(
                Blocks.VINE.defaultBlockState().setValue(VineBlock.NORTH, true),
                Blocks.VINE.defaultBlockState().setValue(VineBlock.SOUTH, true),
                Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, true),
                Blocks.VINE.defaultBlockState().setValue(VineBlock.WEST, true),
            )
        }

        /** Vine attached to the block ABOVE — kept as a single-tile
         *  variant for legacy callers. The heart-tunnel vine
         *  painter no longer uses it (vines now attach to side
         *  walls only). */
        private val VINE_UP: BlockState by lazy {
            Blocks.VINE.defaultBlockState().setValue(VineBlock.UP, true)
        }

        /** All 16 combinations of the four horizontal VineBlock face
         *  properties, keyed by a bitmask: `bit 0 = NORTH`,
         *  `bit 1 = SOUTH`, `bit 2 = EAST`, `bit 3 = WEST`. The
         *  heart-tunnel + heart-cave vine painter samples horizontal
         *  walls around an air cell, builds the mask, and writes
         *  `VINE_STATES[mask]` — so a single block can attach to
         *  multiple walls at once (e.g. an air cell tucked in a wall
         *  corner gets two faces ablaze). Index 0 is the no-face
         *  state, never written (the painter skips when no wall is
         *  found). */
        private val VINE_STATES: Array<BlockState> by lazy {
            Array(16) { mask ->
                var s = Blocks.VINE.defaultBlockState()
                if ((mask and 0x1) != 0) s = s.setValue(VineBlock.NORTH, true)
                if ((mask and 0x2) != 0) s = s.setValue(VineBlock.SOUTH, true)
                if ((mask and 0x4) != 0) s = s.setValue(VineBlock.EAST, true)
                if ((mask and 0x8) != 0) s = s.setValue(VineBlock.WEST, true)
                s
            }
        }

        // ---- hash / value noise -------------------------------------------
        /** Deterministic 2D int hash (xxhash32-ish bit mixing). */
        private fun hash32(a: Int, b: Int, salt: Int): Int {
            var h = a * 0x9E3779B1.toInt() xor (b * 0x85EBCA77.toInt()) xor (salt * 0xC2B2AE3D.toInt())
            h = (h xor (h ushr 15)) * 0x2C1B3C6D.toInt()
            h = (h xor (h ushr 12)) * 0x297A2D39.toInt()
            h = h xor (h ushr 15)
            return h
        }

        /** [-1, 1] valley noise via bilinear interpolation of per-lattice hashes. */
        private fun valueNoise2(x: Double, z: Double): Double {
            val ix = Math.floor(x).toInt()
            val iz = Math.floor(z).toInt()
            val fx = x - ix
            val fz = z - iz
            val sx = fx * fx * (3.0 - 2.0 * fx)
            val sz = fz * fz * (3.0 - 2.0 * fz)
            val a = (hash32(ix, iz, 1) and 0xFFFF) / 32768.0 - 1.0
            val b = (hash32(ix + 1, iz, 1) and 0xFFFF) / 32768.0 - 1.0
            val c = (hash32(ix, iz + 1, 1) and 0xFFFF) / 32768.0 - 1.0
            val d = (hash32(ix + 1, iz + 1, 1) and 0xFFFF) / 32768.0 - 1.0
            val ab = a + (b - a) * sx
            val cd = c + (d - c) * sx
            return ab + (cd - ab) * sz
        }

        val CODEC: Codec<WohlonnogondoniaChunkGenerator> =
            RecordCodecBuilder.create { instance ->
                instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source")
                        .forGetter(WohlonnogondoniaChunkGenerator::getBiomeSourceForCodec),
                ).apply(instance, ::WohlonnogondoniaChunkGenerator)
            }
    }
}
