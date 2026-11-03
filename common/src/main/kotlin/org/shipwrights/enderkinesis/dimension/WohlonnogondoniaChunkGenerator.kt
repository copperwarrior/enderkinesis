package org.shipwrights.enderkinesis.dimension

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag
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
import org.shipwrights.enderkinesis.block.GuardianSpikeBase
import org.shipwrights.enderkinesis.block.GuardianSpikeBlock
import org.shipwrights.enderkinesis.block.GuardianSpikeOuterBlock
import org.shipwrights.enderkinesis.block.SpikeAttachment
import org.shipwrights.enderkinesis.registry.EKBlocks

private typealias TunnelPath = WohlonnogondoniaSurfaceRoots.TunnelPath

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
 *  C2ME runs this concurrently across many chunks against a single generator
 *  instance. Per-chunk scratch lives in a [ThreadLocal], and the shared caches
 *  are concurrent maps where a race only repeats work. See
 *  [[sselith-chunkgen-threading]].
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
        val featureCache = scratchFeatureCache(span)

        // Mother Tree at origin (touches only chunks within its footprint). Rivers, child trees
        // and surface roots paint in [applyBiomeDecoration], before the vanilla features run.
        paintMotherTreeInto(featureCache, chunkX0, chunkZ0, minY, span)

        // Root cave sites, hoisted once per chunk: a 16-wide chunk against
        // a 40-block lattice touches only a handful of cells, and looping a
        // flat array per block is what keeps the F3-F1 evaluation affordable.
        val rootBand = paintRootNetwork(chunkX0, chunkZ0)
        val childHearts = childHeartsNear(chunkX0, chunkZ0)

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
                val childHeart = childHeartAt(childHearts, worldX, worldZ)

                // Tracks the surface where it drops below the field's own
                // ceiling, so tunnels never open into a deep pool floor.
                val rootDead = WohlonRootField.deadness(worldX, worldZ)
                // Root-tunnel cell just below this one: -1 none, else its kind. Decorations look one cell back.
                var below = WohlonRootField.SOLID
                var siltPending = false
                // A spike in the cell below must not be overwritten by this ceiling's decor.
                var belowSpike = false

                for (i in 0 until span) {
                    val y = minY + i
                    val feature = featCol[i]
                    val baseBlock = baseColumnBlock(y, surface)

                    val caveOverride = if (caveXZ) {
                        centralCaveBlock(worldX, y, worldZ)
                    } else if (childHeart != null) {
                        childChamberBlock(childHeart, worldX, y, worldZ)
                    } else null

                    // Cave hollows out everything (including the
                    // feature cache, so Mother Tree roots end cleanly
                    // at the cave walls). Outside the cave, feature
                    // cache wins over base — that's what preserves the
                    // canopy where the moat lowers the surface beneath
                    // it. Bedrock is sacred at y=0.
                    // Root-cave carve. Only ever eats solid mud — never
                    // the bedrock floor, never an authored feature (Mother
                    // Tree roots, heart cave), and never open air/water
                    // above the surface. Water sources are written HERE, in
                    // the same pass as the carve: if a later pass found air
                    // in these tubes it would seed flowing water into a
                    // globally connected network.
                    val rootCave = if (
                        baseBlock === MUD && feature == null && caveOverride == null &&
                        y >= WohlonRootField.CAVE_BOTTOM_Y && y <= WohlonRootField.BAND_TOP_Y
                    ) {
                        val painted = rootBand[(localX * 16 + localZ) * WohlonRootField.BAND_HEIGHT +
                            (y - WohlonRootField.CAVE_BOTTOM_Y)].toInt()
                        if (painted == WohlonRootField.LINING && !WohlonRootField.liningSurvives(worldX, y, worldZ, rootDead)) {
                            WohlonRootField.SOLID
                        } else painted
                    } else WohlonRootField.SOLID

                    val wet = y < SEA_LEVEL_Y
                    val shaft = heartShaftBlock(worldX, y, worldZ)
                    val anchor = if (caveOverride == null && feature == null) anchorRootBlock(worldX, y, worldZ) else null

                    val block = when {
                        baseBlock === BEDROCK -> baseBlock
                        // The pool keeps its shape: the shaft's root shell never stands in the cave's water.
                        caveOverride != null &&
                            (shaft === ROOT_BLOCK || caveOverride === WATER || caveOverride === AIR || caveOverride === FOUNTAIN) -> caveOverride
                        // Otherwise the shaft wins over Mother Tree roots and anchors: it is the one
                        // guaranteed way down into the root network, and a root arm plugging it would strand the player.
                        shaft != null -> shaft
                        caveOverride != null -> caveOverride
                        feature != null -> feature
                        anchor != null && y <= surface -> anchor
                        rootCave == WohlonRootField.TUNNEL -> if (wet) WATER else AIR
                        rootCave == WohlonRootField.LINING -> ROOT_BLOCK
                        else -> baseBlock
                    }
                    val kind = if ((block === WATER || block === AIR) && rootCave == WohlonRootField.TUNNEL) WohlonRootField.TUNNEL
                    else if (block === ROOT_BLOCK) WohlonRootField.LINING else WohlonRootField.SOLID
                    var siltFloor = false
                    if (siltPending) {
                        // Dead channels silt up: the floor cell becomes mud if the tube is taller than one block.
                        if (kind == WohlonRootField.TUNNEL) {
                            chunk.setBlockState(mutable.set(worldX, y - 1, worldZ), MUD, false)
                            siltFloor = true
                        }
                        siltPending = false
                    } else if (below == WohlonRootField.TUNNEL && kind != WohlonRootField.TUNNEL &&
                        block !== AIR && block !== WATER && !belowSpike
                    ) {
                        rootCeilingDecor(worldX, y - 1, worldZ, rootDead, y - 1 < SEA_LEVEL_Y)?.let {
                            chunk.setBlockState(mutable.set(worldX, y - 1, worldZ), it, false)
                        }
                    }
                    var placed = block
                    if (kind == WohlonRootField.TUNNEL && below != WohlonRootField.TUNNEL) {
                        if (rootDead > SILT_DEADNESS) siltPending = true
                        else placed = rootFloorDecor(worldX, y, worldZ, rootDead, wet) ?: block
                    } else if (siltFloor) {
                        placed = rootFloorDecor(worldX, y, worldZ, rootDead, wet) ?: block
                    }
                    if (placed === block && kind == WohlonRootField.TUNNEL) {
                        placed = rootWallDecor(rootBand, localX, localZ, worldX, y, worldZ, rootDead, wet) ?: placed
                    }
                    if (placed === block && kind == WohlonRootField.TUNNEL && !siltPending) {
                        placed = rootSpikeDecor(rootBand, localX, localZ, worldX, y, worldZ, wet) ?: placed
                    }
                    below = kind
                    belowSpike = placed.block is GuardianSpikeBase
                    if (placed === AIR) continue
                    chunk.setBlockState(mutable.set(worldX, y, worldZ), placed, false)
                    if (placed.hasBlockEntity()) markBlockEntity(chunk, mutable)
                }
            }
        }
        return CompletableFuture.completedFuture(chunk)
    }

    /** Every root-network link that can reach this chunk, painted into this thread's band buffer. */
    private fun paintRootNetwork(chunkX0: Int, chunkZ0: Int): ByteArray {
        val band = rootBandScratch.get()
        val cx = Math.floorDiv(chunkX0, WohlonRootField.CELL_SIZE)
        val cz = Math.floorDiv(chunkZ0, WohlonRootField.CELL_SIZE)
        val cells = ArrayList<Array<WohlonRootField.Segment>>(ROOT_CELLS_PER_CHUNK)
        for (dx in -WohlonRootField.CELL_SEARCH..WohlonRootField.CELL_SEARCH + 1) {
            for (dz in -WohlonRootField.CELL_SEARCH..WohlonRootField.CELL_SEARCH + 1) {
                val links = rootCell(cx + dx, cz + dz)
                if (links.isNotEmpty()) cells += links
            }
        }
        WohlonRootField.paintChunk(cells, chunkX0, chunkZ0, band)
        return band
    }

    /** Root-network links per cell, per world (the build hashes the world seed). Racing builds are identical. */
    private val rootCellCache = java.util.concurrent.ConcurrentHashMap<Long, Array<WohlonRootField.Segment>>()

    private fun rootCell(cx: Int, cz: Int): Array<WohlonRootField.Segment> {
        val key = (cx.toLong() and 0xFFFFFFFFL) shl 32 or (cz.toLong() and 0xFFFFFFFFL)
        rootCellCache[key]?.let { return it }
        val built = WohlonRootField.buildCell(cx, cz, ::childHeartNodes)
        if (rootCellCache.size >= ROOT_CELL_CACHE_CAP) rootCellCache.clear()
        return rootCellCache.putIfAbsent(key, built) ?: built
    }

    private val rootBandScratch: ThreadLocal<ByteArray> =
        ThreadLocal.withInitial { ByteArray(256 * WohlonRootField.BAND_HEIGHT) }

    /** Under a root-tunnel ceiling: hanging roots from living root wood, heartfire on rotting wood. */
    /**
     * Under any root-tunnel ceiling: roots dangle through it everywhere, thickest under the tree;
     * heartfire grows on peat as well as wood, thickest where the wood is rotting.
     */
    /**
     * ProtoChunk.setBlockState makes no block entity. A "DUMMY" entry makes the chunk build one
     * when it becomes a LevelChunk, as WorldGenRegion.setBlock does.
     */
    private fun markBlockEntity(chunk: ChunkAccess, pos: BlockPos) {
        val tag = CompoundTag()
        tag.putInt("x", pos.x)
        tag.putInt("y", pos.y)
        tag.putInt("z", pos.z)
        tag.putString("id", "DUMMY")
        chunk.setBlockEntityNbt(tag)
    }

    private fun rootCeilingDecor(wx: Int, wy: Int, wz: Int, dead: Double, wet: Boolean): BlockState? {
        val roll = (hash32(wx, wy * 31 + wz, ROOT_DECOR_SALT) and 0xFFFF) / 65536.0
        val hanging = ROOT_HANGING_BASE + ROOT_HANGING_NEAR * (1.0 - dead)
        if (roll < hanging) return if (wet) HANGING_ROOTS_WET else HANGING_ROOTS_DRY
        if (roll < hanging + heartfireChance(dead) * heartfirePatch(wx, wy, wz)) {
            return if (wet) HEARTFIRE_CEILING_WET else HEARTFIRE_CEILING_DRY
        }
        return null
    }

    /** On any root-tunnel floor, silt included: heartfire. */
    private fun rootFloorDecor(wx: Int, wy: Int, wz: Int, dead: Double, wet: Boolean): BlockState? {
        val roll = (hash32(wx, wy * 31 + wz, ROOT_DECOR_SALT + 1) and 0xFFFF) / 65536.0
        return if (roll < heartfireChance(dead) * heartfirePatch(wx, wy, wz)) {
            if (wet) HEARTFIRE_FLOOR_WET else HEARTFIRE_FLOOR_DRY
        } else null
    }

    private fun heartfireChance(dead: Double): Double = ROOT_HEARTFIRE_BASE + ROOT_HEARTFIRE_ROTTING * rotting(dead)

    /** Foxfire glows in occasional patches; most stretches have none. 0 outside a patch. */
    private fun heartfirePatch(wx: Int, wy: Int, wz: Int): Double {
        val n = WohlonRootField.noise3(wx * HEARTFIRE_PATCH_FREQ, wy * HEARTFIRE_PATCH_FREQ, wz * HEARTFIRE_PATCH_FREQ, 157)
        return ((n - HEARTFIRE_PATCH_LOW) / HEARTFIRE_PATCH_SPAN).coerceIn(0.0, 1.0)
    }

    /** Heartfire on the wall beside this tunnel cell; neighbours come from this chunk's band. */
    private fun rootWallDecor(
        band: ByteArray, lx: Int, lz: Int, wx: Int, wy: Int, wz: Int, dead: Double, wet: Boolean,
    ): BlockState? {
        val roll = (hash32(wx, wy * 31 + wz, ROOT_DECOR_SALT + 2) and 0xFFFF) / 65536.0
        if (roll >= heartfireChance(dead) * ROOT_HEARTFIRE_WALL_SHARE * heartfirePatch(wx, wy, wz)) return null
        val start = (hash32(wz, wx, wy) and 3)
        for (n in 0..3) {
            val dir = HORIZONTAL_DIRECTIONS[(start + n) and 3]
            val nx = lx + dir.stepX
            val nz = lz + dir.stepZ
            if (nx !in 0..15 || nz !in 0..15) continue
            val idx = (nx * 16 + nz) * WohlonRootField.BAND_HEIGHT + (wy - WohlonRootField.CAVE_BOTTOM_Y)
            // Any wall: living or rotting wood, or bare peat where the lining has gone.
            if (band[idx].toInt() == WohlonRootField.TUNNEL) continue
            return heartfireOn(dir, wet)
        }
        return null
    }

    /**
     * Guardian spikes in rare dense clusters, on a solid face, crease, or outer edge or corner beside this
     * tunnel cell. A face or crease spike can bring others around the same cell corner.
     */
    private fun rootSpikeDecor(band: ByteArray, lx: Int, lz: Int, wx: Int, wy: Int, wz: Int, wet: Boolean): BlockState? {
        val cluster = WohlonRootField.noise3(wx * SPIKE_CLUSTER_FREQ, wy * SPIKE_CLUSTER_FREQ, wz * SPIKE_CLUSTER_FREQ, SPIKE_CLUSTER_SALT)
        if (cluster < SPIKE_CLUSTER_LOW) return null
        val roll = (hash32(wx, wy * 31 + wz, ROOT_DECOR_SALT + 3) and 0xFFFF) / 65536.0
        if (roll >= SPIKE_DENSITY * ((cluster - SPIKE_CLUSTER_LOW) / SPIKE_CLUSTER_SPAN).coerceAtMost(1.0)) return null
        // Reservoir pick, so each fitting attachment is equally likely without a candidate list.
        // An attachment fits as face or crease (its walls solid) or as outer (support solid, flanks open), never both.
        var chosen: SpikeAttachment? = null
        var fits = 0
        for (attach in SPIKE_ATTACHMENTS) {
            if (!wallsSolid(band, lx, wy, lz, attach) && !outerFits(band, lx, wy, lz, attach)) continue
            fits++
            if ((hash32(wx + attach.ordinal * 7919, wy * 31 + wz, ROOT_DECOR_SALT + 4) and 0xFFFF) % fits == 0) chosen = attach
        }
        if (chosen == null) return null
        if (!wallsSolid(band, lx, wy, lz, chosen)) {
            return EKBlocks.GUARDIAN_SPIKE_OUTER.get().defaultBlockState()
                .setValue(GuardianSpikeOuterBlock.ATTACH, chosen)
                .setValue(GuardianSpikeBase.WATERLOGGED, wet)
        }
        val corners = SpikeAttachment.CORNERS.filter { chosen.fitsCorner(it) }
        val corner = corners[(hash32(wz, wy * 31 + wx, ROOT_DECOR_SALT + 5) and 0xFFFF) % corners.size]
        val spikes = GuardianSpikeBlock.SLOTS.map { it.attachment(corner) }.filter { attach ->
            attach == chosen || (
                wallsSolid(band, lx, wy, lz, attach) &&
                    (hash32(wx + attach.ordinal * 104729, wy * 31 + wz, ROOT_DECOR_SALT + 6) and 0xFFFF) / 65536.0 < SPIKE_EXTRA_CHANCE
                )
        }
        return (EKBlocks.GUARDIAN_SPIKE.get() as GuardianSpikeBlock).stateWith(corner, spikes, wet)
    }

    /** Solid wall on every axis the spike leans on: a face spike's support, or both or all three crease walls. */
    private fun wallsSolid(band: ByteArray, lx: Int, y: Int, lz: Int, a: SpikeAttachment): Boolean =
        (a.dx == 0 || bandCell(band, lx + a.dx, y, lz) == BAND_SOLID) &&
            (a.dy == 0 || bandCell(band, lx, y + a.dy, lz) == BAND_SOLID) &&
            (a.dz == 0 || bandCell(band, lx, y, lz + a.dz) == BAND_SOLID)

    /** Solid diagonal support with every other cell around the edge or corner open, so the folded plate shows. */
    private fun outerFits(band: ByteArray, lx: Int, y: Int, lz: Int, a: SpikeAttachment): Boolean {
        if (a.order == 1 || bandCell(band, lx + a.dx, y + a.dy, lz + a.dz) != BAND_SOLID) return false
        return a.flanks.all { bandCell(band, lx + it.x, y + it.y, lz + it.z) == BAND_OPEN }
    }

    /** Solid, open, or unknown outside this chunk's band. Under the band is the bedrock floor. */
    private fun bandCell(band: ByteArray, lx: Int, y: Int, lz: Int): Int {
        if (lx !in 0..15 || lz !in 0..15 || y > WohlonRootField.BAND_TOP_Y) return BAND_UNKNOWN
        if (y < WohlonRootField.CAVE_BOTTOM_Y) return BAND_SOLID
        val idx = (lx * 16 + lz) * WohlonRootField.BAND_HEIGHT + (y - WohlonRootField.CAVE_BOTTOM_Y)
        return if (band[idx].toInt() == WohlonRootField.TUNNEL) BAND_OPEN else BAND_SOLID
    }

    /** Foxfire needs wood that is rotting but not gone: it peaks between living and dead. */
    private fun rotting(dead: Double): Double = 1.0 - Math.abs(dead - 0.5) * 2.0

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
        // [valueNoise2] is in [-1, 1], so the noise term below is in [-3·AMP, AMP].
        if (dxz < MOAT_INNER_R - 3.0 * MOAT_BOUNDARY_NOISE_AMP ||
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
     * column. The "heart" is the Mother Tree's Heart of the Wild
     * (the `mother = true` blockstate) at `(0, HEART_Y, 0)` — the
     * cave's geometric centre, indestructible by any normal means
     * and the trigger for [WohlonnogondoniaCatastrophe] if forcibly
     * removed.
     */
    private fun centralCaveBlock(wx: Int, wy: Int, wz: Int): BlockState? {
        val dxz = Math.sqrt(wx.toDouble() * wx + wz.toDouble() * wz)
        if (dxz < 0.5) {
            if (wy < CAVE_FLOOR_BASE_Y - CAVE_Y_NOISE_AMP.toInt() || wy > CAVE_CEILING_BASE_Y + CAVE_Y_NOISE_AMP.toInt()) {
                return null
            }
            if (wx == 0 && wz == 0 && wy == HEART_Y) return MOTHER_HEART
            if (wx == 0 && wz == 0 && wy == FOUNTAIN_Y) return FOUNTAIN
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

        // Gaussian bulge toward the heart-tunnel direction, so the tunnel mouth blends into the wall.
        var angleDiff = angle - heartTunnelAngleRad
        while (angleDiff > Math.PI) angleDiff -= 2.0 * Math.PI
        while (angleDiff < -Math.PI) angleDiff += 2.0 * Math.PI
        val bulge = Math.exp(
            -(angleDiff * angleDiff) /
                (2.0 * CAVE_TUNNEL_BULGE_SIGMA * CAVE_TUNNEL_BULGE_SIGMA)
        ) * CAVE_TUNNEL_BULGE_AMP

        val effRadius = CAVE_BASE_RADIUS + angleNoise + bulge
        if (dxz > effRadius) return null

        // The void under the root plate: high over the heart, the ceiling dropping toward the
        // wall and the floor rising a little, so the chamber is a lens, not a drum.
        val f = (dxz / effRadius).coerceIn(0.0, 1.0)
        val floorN = valueNoise2(wx * 0.08 + 17.0, wz * 0.08 + 23.0) - 0.5
        val ceilN = valueNoise2(wx * 0.08 + 53.0, wz * 0.08 + 41.0) - 0.5
        val floorHere = CAVE_FLOOR_BASE_Y + (floorN * 2.0 * CAVE_Y_NOISE_AMP + CAVE_BOWL_RISE * f * f).toInt()
        val ceilingHere = CAVE_CEILING_BASE_Y + (ceilN * 2.0 * CAVE_Y_NOISE_AMP - CAVE_DOME_DROP * f * f).toInt()
        if (wy < floorHere || wy > ceilingHere + CAVE_ROOF_THICKNESS) return null
        if (wy > ceilingHere) return WOGOR_WOOD_Y

        if (wx == 0 && wz == 0 && wy == HEART_Y) return MOTHER_HEART
        anchorRootBlock(wx, wy, wz)?.let { return it }
        if (wy < SEA_LEVEL_Y) return WATER
        if (wy == ceilingHere) {
            val roll = (hash32(wx, wz, CAVE_DECOR_SALT) and 0xFFFF) / 65536.0
            if (roll < CAVE_HANGING_CHANCE) return HANGING_ROOTS_DRY
            if (roll < CAVE_HANGING_CHANCE + CAVE_HEARTFIRE_CHANCE) return HEARTFIRE_CEILING_DRY
        }
        return AIR
    }

    /**
     * Anchor roots: 4–6 thick roots that drop from the root plate through the cave and down into
     * the flooded network, leaning outward and narrowing as they go, as a cypress's descending
     * anchor roots do. Wood, or null where there is none.
     */
    private fun anchorRootBlock(wx: Int, wy: Int, wz: Int): BlockState? {
        if (wy < ANCHOR_BOTTOM_Y || wy > CAVE_CEILING_BASE_Y + CAVE_ROOF_THICKNESS) return null
        if (wx.toLong() * wx + wz.toLong() * wz > ANCHOR_REACH_SQ) return null
        val depth = (CAVE_CEILING_BASE_Y - wy).coerceAtLeast(0).toDouble()
        val along = depth / (CAVE_CEILING_BASE_Y - ANCHOR_BOTTOM_Y)
        for (a in anchorRoots) {
            val cx = a[0] + a[2] * depth * ANCHOR_LEAN
            val cz = a[1] + a[3] * depth * ANCHOR_LEAN
            val r = a[4] * (1.0 - ANCHOR_TAPER * along)
            val dx = wx + 0.5 - cx
            val dz = wz + 0.5 - cz
            if (dx * dx + dz * dz <= r * r) return WOGOR_WOOD_Y
        }
        return null
    }

    /** Per anchor: start x, start z, outward unit x, outward unit z, top radius. Clear of the tunnel mouth. */
    private val anchorRoots: List<DoubleArray> by lazy {
        val count = ANCHOR_MIN_COUNT + Math.floorMod(hash32(0, 0, ANCHOR_SALT), ANCHOR_EXTRA_COUNT + 1)
        val out = ArrayList<DoubleArray>(count)
        for (i in 0 until count) {
            val h = hash32(i, 1, ANCHOR_SALT)
            val angle = heartTunnelAngleRad + ANCHOR_MOUTH_CLEARANCE +
                (2.0 * Math.PI - 2.0 * ANCHOR_MOUTH_CLEARANCE) * (i + 0.5 + ((h and 0xFF) / 255.0 - 0.5) * 0.6) / count
            val dist = ANCHOR_MIN_DIST + ((h ushr 8) and 0xFF) / 255.0 * ANCHOR_DIST_SPREAD
            val r = ANCHOR_MIN_RADIUS + ((h ushr 16) and 0xFF) / 255.0 * ANCHOR_RADIUS_SPREAD
            out += doubleArrayOf(Math.cos(angle) * dist, Math.sin(angle) * dist, Math.cos(angle), Math.sin(angle), r)
        }
        out
    }

    /** Heart-tunnel direction in `[0, 2π)`. [hash32] carries the world seed, so each world
     *  has its own direction, and each generation stage reads the same value. */
    private val heartTunnelAngleRad: Double by lazy {
        val s = hash32(0, 0, 0x434F5245.toInt())  // 'CORE'
        (s.toLong() and 0xFFFFFFFFL).toDouble() / ((1L shl 32).toDouble()) * 2.0 * Math.PI
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

        val featureCache = scratchFeatureCache(span)
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
        clearChildChambers(featureCache, chunkX0, chunkZ0, minY, span)

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

    /** One 16×16×span cache for each worker thread, emptied for each use. A new one for each
     *  chunk stage was two arrays of 90,000 references for each chunk. The two stages never
     *  nest on one thread: each stage is done with the cache before it returns or calls `super`. */
    private fun scratchFeatureCache(span: Int): Array<Array<Array<BlockState?>>> {
        var cache = featureCacheScratch.get()
        if (cache == null || cache[0][0].size != span) {
            cache = Array(16) { Array(16) { arrayOfNulls<BlockState>(span) } }
            featureCacheScratch.set(cache)
            return cache
        }
        for (plane in cache) for (col in plane) java.util.Arrays.fill(col, null)
        return cache
    }

    private val featureCacheScratch = ThreadLocal<Array<Array<Array<BlockState?>>>?>()

    /** Top root-wood Y (local) of each column in the one-block ring around the chunk, or -1.
     *  A chunk cannot read the blocks of the next chunk, so the root painter records what it
     *  would write there. [paintRootVines] then hangs the vines of those columns that fall
     *  into this chunk. One array for each worker thread (C2ME runs the stages concurrently). */
    private val rootRingScratch = ThreadLocal.withInitial { IntArray(RING_SIDE * RING_SIDE) }

    private fun ringIndex(lx: Int, lz: Int): Int = (lx + 1) * RING_SIDE + (lz + 1)

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
        val n0 = valueNoise2(wx * 0.045, wz * 0.045, FINE_SALT)
        val n1 = valueNoise2(wx * 0.12 + 7.3, wz * 0.12 + 11.1, FINE_SALT + 1) * 0.5
        val noiseFine = (n0 + n1) * FINE_AMPLITUDE
        val d = Math.sqrt((wx.toDouble() * wx + wz.toDouble() * wz))
        val hillLift = if (d >= HILL_RADIUS) 0.0
        else {
            val t = 1.0 - (d / HILL_RADIUS)
            val s = t * t * (3.0 - 2.0 * t)
            s * HILL_PEAK_LIFT
        }
        val shaped = shapeWaterDepth(coarseSurfaceY(wx, wz) + noiseFine)
        val surface = (shaped + hillLift + reliefLift(wx, wz, shaped))
            .toInt().coerceIn(2, MAX_BUILD_Y - 4)
        return if (surface in HUMMOCK_MIN_Y..HUMMOCK_MAX_Y && isHummock(wx, wz)) surface + 1 else surface
    }

    /**
     * Swamp depth profile: most ground sits at or just above the water, hollows are 1–2 deep,
     * and only the deepest pools keep their full depth.
     */
    private fun shapeWaterDepth(raw: Double): Double = when {
        raw <= DEPTH_KEEP_Y -> raw
        raw <= DEPTH_SHELF_Y ->
            DEPTH_KEEP_Y + (raw - DEPTH_KEEP_Y) * (SHALLOW_FLOOR_Y - DEPTH_KEEP_Y) / (DEPTH_SHELF_Y - DEPTH_KEEP_Y)
        raw <= DEPTH_SHALLOW_END_Y ->
            SHALLOW_FLOOR_Y + (raw - DEPTH_SHELF_Y) * (DRY_FLOOR_Y - SHALLOW_FLOOR_Y) / (DEPTH_SHALLOW_END_Y - DEPTH_SHELF_Y)
        raw <= DEPTH_TOP_ANCHOR_Y ->
            DRY_FLOOR_Y + (raw - DEPTH_SHALLOW_END_Y) * (DEPTH_TOP_ANCHOR_Y - DRY_FLOOR_Y) / (DEPTH_TOP_ANCHOR_Y - DEPTH_SHALLOW_END_Y)
        else -> raw
    }

    /**
     * Terrain character on top of the swamp: broad areas of rolling hills and of terraced bluffs
     * with cliffs, the rest left flat. Only dry ground is lifted, so pools and flooded hollows keep
     * their depth; the centre (mound, moat) is left as authored.
     */
    private fun reliefLift(wx: Int, wz: Int, shaped: Double): Double {
        val dry = smoothstep01((shaped - RELIEF_DRY_FROM) / RELIEF_DRY_SPAN)
        if (dry <= 0.0) return 0.0
        val centre = smoothstep01((Math.sqrt(wx.toDouble() * wx + wz.toDouble() * wz) - RELIEF_CENTRE_CLEAR) / RELIEF_CENTRE_FADE)
        if (centre <= 0.0) return 0.0
        val hillMask = smoothstep01(
            (valueNoise2(wx * RELIEF_MASK_FREQ, wz * RELIEF_MASK_FREQ, RELIEF_SALT) - HILL_MASK_LOW) / HILL_MASK_SPAN,
        )
        val cliffMask = smoothstep01(
            (valueNoise2(wx * RELIEF_MASK_FREQ + 41.7, wz * RELIEF_MASK_FREQ + 13.9, RELIEF_SALT + 1) - CLIFF_MASK_LOW) /
                CLIFF_MASK_SPAN,
        )
        var lift = 0.0
        if (hillMask > 0.0) {
            val roll = 0.5 + 0.35 * valueNoise2(wx * ROLLING_FREQ, wz * ROLLING_FREQ, RELIEF_SALT + 2) +
                0.15 * valueNoise2(wx * ROLLING_FREQ_FINE, wz * ROLLING_FREQ_FINE, RELIEF_SALT + 5)
            lift += hillMask * ROLLING_AMP * roll
        }
        if (cliffMask > 0.0) {
            // Warped so bluff edges wander instead of tracing the noise lattice.
            val warpX = valueNoise2(wx * BLUFF_WARP_FREQ, wz * BLUFF_WARP_FREQ, RELIEF_SALT + 3) * BLUFF_WARP_AMP
            val warpZ = valueNoise2(wx * BLUFF_WARP_FREQ + 7.3, wz * BLUFF_WARP_FREQ + 3.1, RELIEF_SALT + 3) * BLUFF_WARP_AMP
            val level = (0.5 + 0.5 * valueNoise2((wx + warpX) * BLUFF_FREQ, (wz + warpZ) * BLUFF_FREQ, RELIEF_SALT + 4)) *
                BLUFF_TERRACES
            val step = Math.floor(level)
            // Flat terraces joined by short steep risers: the cliffs.
            val riser = smoothstep01((level - step - (1.0 - BLUFF_RISER_SHARE)) / BLUFF_RISER_SHARE)
            lift += cliffMask * BLUFF_STEP_HEIGHT * (step + riser)
        }
        return lift * dry * centre
    }

    /** Hummocks sit on a jittered grid, not on noise: wet-swamp hummocks are regularly spaced. */
    private fun isHummock(wx: Int, wz: Int): Boolean {
        val cx = Math.floorDiv(wx, HUMMOCK_CELL)
        val cz = Math.floorDiv(wz, HUMMOCK_CELL)
        val h = hash32(cx, cz, HUMMOCK_SALT)
        if ((h ushr 24 and 0xFF) >= HUMMOCK_CELL_FILL) return false
        // Point offset 0..CELL-2 on x so the optional east neighbour stays inside the cell.
        val px = cx * HUMMOCK_CELL + Math.floorMod(h, HUMMOCK_CELL - 1)
        val pz = cz * HUMMOCK_CELL + Math.floorMod(h ushr 8, HUMMOCK_CELL)
        if (wz != pz) return false
        return wx == px || (wx == px + 1 && (h and 0x10000) != 0)
    }

    /**
     * The single authored descent: a smooth shaft dropping from the floor
     * of the Mother Tree's heart cave into the flooded root cave network
     * below.
     *
     * Everything else about the root caves is a pure procedural field, but
     * the way IN is deliberately hand-placed — the network's ceiling sits
     * at y54, six blocks under the heart cave floor, and without this the
     * two systems would never meet. The shaft's centre wanders slightly
     * with low-frequency noise so it reads as a bored root channel rather
     * than a drilled cylinder.
     *
     * It opens through the floor of the heart cave's pool and is flooded
     * to the pool's level. Returns null outside the shaft.
     */
    private fun heartShaftBlock(wx: Int, wy: Int, wz: Int): BlockState? {
        if (wy < SHAFT_BOTTOM_Y || wy > SHAFT_TOP_Y) return null
        // Cheap square reject before any noise.
        if (wx < -SHAFT_REJECT || wx > SHAFT_REJECT || wz < -SHAFT_REJECT || wz > SHAFT_REJECT) return null

        // Leans away from the tunnel mouth as it drops, like the anchor roots around it.
        val lean = (SHAFT_TOP_Y - wy) * SHAFT_LEAN
        val wobbleX = valueNoise2(wy * SHAFT_WOBBLE_FREQ, 13.7) * SHAFT_WOBBLE_AMP - Math.cos(heartTunnelAngleRad) * lean
        val wobbleZ = valueNoise2(41.3, wy * SHAFT_WOBBLE_FREQ) * SHAFT_WOBBLE_AMP - Math.sin(heartTunnelAngleRad) * lean
        val dx = wx - wobbleX
        val dz = wz - wobbleZ
        val d = Math.sqrt(dx * dx + dz * dz)
        // Flare the mouth so it opens into the heart cave floor rather
        // than puncturing it as a bare hole.
        val flare = if (wy > SHAFT_TOP_Y - SHAFT_FLARE_SPAN) {
            (wy - (SHAFT_TOP_Y - SHAFT_FLARE_SPAN)).toDouble() / SHAFT_FLARE_SPAN * SHAFT_FLARE
        } else 0.0
        val narrowing = 1.0 - SHAFT_TAPER * (SHAFT_TOP_Y - wy) / (SHAFT_TOP_Y - SHAFT_BOTTOM_Y).toDouble()
        val r = SHAFT_RADIUS * narrowing + flare
        if (d > r + WohlonRootField.ROOT_SHELL) return null
        if (d > r) return ROOT_BLOCK
        return if (wy < SEA_LEVEL_Y) WATER else AIR
    }

    /** Base-column block at world Y `y`, given the local surface
     *  height. Water surface sits at the top of `y = SEA_LEVEL_Y − 1`
     *  block (i.e. the `y = SEA_LEVEL_Y` line) so the water surface IS
     *  sea level. All other water systems (moat, cave pool, rivers,
     *  heart tunnel) use the same convention. */
    private fun baseColumnBlock(y: Int, surface: Int): BlockState = when {
        y == MIN_Y -> BEDROCK
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
        // [valueNoise2] is in [-1, 1], so the rim warp below is in [-3·AMP, AMP].
        if (baseDist > TREE_MOUND_OUTER_R + 3.0 * TREE_MOUND_RIM_NOISE_AMP) return Int.MIN_VALUE

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
            val site = childTreeSite(regionX + dRegX, regionZ + dRegZ) ?: continue
            val treeX = site.x
            val treeZ = site.z
            val baseY = site.baseY
            val regionSeed = site.seed

            // Quick chunk-AABB reject.
            val nearestX = treeX.coerceIn(chunkX0, chunkX0 + 15)
            val nearestZ = treeZ.coerceIn(chunkZ0, chunkZ0 + 15)
            val ddx = nearestX - treeX
            val ddz = nearestZ - treeZ
            if (ddx * ddx + ddz * ddz > CHILD_TREE_MAX_REACH * CHILD_TREE_MAX_REACH) continue

            val skel = getChildTreeSkeleton(site)

            paintSkeleton(target, chunkX0, chunkZ0, minY, span, skel, WOGOR_WOOD_Y)
            if (site.age != AGE_DEAD) {
                paintLeafTips(target, chunkX0, chunkZ0, minY, span, skel,
                    blobRxz = 7, blobRy = 4)
                paintHangingVines(target, chunkX0, chunkZ0, minY, span,
                    treeX, treeZ, skel.maxXZReach + 2,
                    maxLen = 7, seed = regionSeed xor 0x73A1F4C7.toInt())
                val canopy = childCanopy(site)
                paintVineEdge(target, chunkX0, chunkZ0, minY, span,
                    canopy.crownX, canopy.crownZ, canopy.rx * CHILD_VINE_EDGE_RIM_SHARE, skel.maxXZReach + 2,
                    CHILD_VINE_EDGE_MIN, CHILD_VINE_EDGE_MAX, regionSeed xor 0x5A17E5)
            }
            paintChildTreeRoots(target, chunkX0, chunkZ0, minY, span, site)
            if (site.age == AGE_YOUNG) paintChildFins(target, chunkX0, chunkZ0, minY, span, site)
            if (site.age >= AGE_OLD) hollowChildTrunk(target, chunkX0, chunkZ0, minY, span, site)
            paintChildKnees(target, chunkX0, chunkZ0, minY, span, site)
            if (site.age == AGE_MATURE || site.age == AGE_OLD) paintWeyyePods(target, chunkX0, chunkZ0, minY, span, site)
        }
    }

    /** Child trees whose heart chamber or shaft can reach this chunk. */
    private fun childHeartsNear(chunkX0: Int, chunkZ0: Int): List<ChildTreeSite> {
        val regionX = Math.floorDiv(chunkX0 shr 4, CHILD_TREE_REGION_SIZE_CHUNKS)
        val regionZ = Math.floorDiv(chunkZ0 shr 4, CHILD_TREE_REGION_SIZE_CHUNKS)
        var out: MutableList<ChildTreeSite>? = null
        for (dRegX in -1..1) for (dRegZ in -1..1) {
            val site = childTreeSite(regionX + dRegX, regionZ + dRegZ) ?: continue
            val reach = site.reach
            if (site.x + reach < chunkX0 || site.x - reach > chunkX0 + 15) continue
            if (site.z + reach < chunkZ0 || site.z - reach > chunkZ0 + 15) continue
            if (out == null) out = ArrayList(2)
            out += site
        }
        return out ?: emptyList()
    }

    /** The child tree whose chamber footprint covers this column, if any. */
    private fun childHeartAt(sites: List<ChildTreeSite>, wx: Int, wz: Int): ChildTreeSite? {
        for (site in sites) {
            val dx = wx + 0.5 - (site.x + 0.5)
            val dz = wz + 0.5 - (site.z + 0.5)
            val reach = site.reach
            if (dx * dx + dz * dz <= reach * reach) return site
        }
        return null
    }

    /**
     * A child tree's heart chamber: a small lens under the trunk with a root-plate roof, a regular
     * Heart of the Wild hung at its centre like the Mother's, and a root-lined shaft down to its junction in the root
     * network. Flooded below sea level like the Mother's cave. Null outside it.
     */
    private fun childChamberBlock(site: ChildTreeSite, wx: Int, wy: Int, wz: Int): BlockState? {
        if (wx == site.x && wz == site.z && wy == site.chamberY) return if (site.age == AGE_DEAD) null else CHILD_HEART
        if (insideEntrance(site.entrance, wx + 0.5, wy + 0.5, wz + 0.5)) {
            return if (wy < SEA_LEVEL_Y) WATER else AIR
        }
        // Shaft: winds down from the chamber floor into the network, leaning and narrowing.
        if (wy >= site.shaftBottomY && wy <= site.chamberY) {
            val sx = wx + 0.5 - shaftCentreX(site, wy)
            val sz = wz + 0.5 - shaftCentreZ(site, wy)
            val sd = sx * sx + sz * sz
            val drop = (site.chamberY - wy).toDouble() / Math.max(1, site.chamberY - site.shaftBottomY)
            val r = CHILD_SHAFT_RADIUS * (1.0 - CHILD_SHAFT_TAPER * drop)
            if (sd <= r * r) return if (wy < SEA_LEVEL_Y) WATER else AIR
            val shell = r + WohlonRootField.ROOT_SHELL
            if (sd <= shell * shell && wy < site.chamberY - CHILD_CHAMBER_FLOOR_DROP) return ROOT_BLOCK
        }
        val dx = wx + 0.5 - (site.x + 0.5)
        val dz = wz + 0.5 - (site.z + 0.5)
        childAnchorBlock(site, dx, wy, dz)?.let { return it }
        val d = Math.sqrt(dx * dx + dz * dz)
        val angle = Math.atan2(dz, dx)
        val wobble = valueNoise2(Math.cos(angle) * 2.0 + site.seed % 97, Math.sin(angle) * 2.0, CHILD_CHAMBER_SALT)
        val effR = site.chamberR + wobble * CHILD_CHAMBER_WOBBLE
        if (d > effR) return null
        val f = d / effR
        val ceiling = site.chamberY + CHILD_CHAMBER_RISE - (CHILD_CHAMBER_DOME * f * f).toInt()
        val floor = site.chamberY - CHILD_CHAMBER_FLOOR_DROP + (f * f).toInt()
        if (wy < floor || wy > ceiling + CAVE_ROOF_THICKNESS) return null
        if (wy > ceiling) return WOGOR_WOOD_Y
        val wet = wy < SEA_LEVEL_Y
        if (wy == ceiling) {
            val roll = (hash32(wx, wz, CHILD_CHAMBER_SALT) and 0xFFFF) / 65536.0
            if (roll < CAVE_HANGING_CHANCE) return if (wet) HANGING_ROOTS_WET else HANGING_ROOTS_DRY
            if (roll < CAVE_HANGING_CHANCE + CAVE_HEARTFIRE_CHANCE) return if (wet) HEARTFIRE_CEILING_WET else HEARTFIRE_CEILING_DRY
        }
        return if (wet) WATER else AIR
    }

    /** Anchor roots: from the chamber roof down past the shaft foot, leaning outward and narrowing. */
    private fun childAnchorBlock(site: ChildTreeSite, dx: Double, wy: Int, dz: Double): BlockState? {
        val top = site.chamberY + CHILD_CHAMBER_RISE + CAVE_ROOF_THICKNESS
        val bottom = site.shaftBottomY - CHILD_ANCHOR_BELOW_SHAFT
        if (wy > top || wy < bottom) return null
        val depth = (top - wy).toDouble()
        val along = depth / (top - bottom)
        val a = site.anchors
        for (i in 0 until a.size / 5) {
            val cx = a[i * 5] + a[i * 5 + 2] * depth * CHILD_ANCHOR_LEAN
            val cz = a[i * 5 + 1] + a[i * 5 + 3] * depth * CHILD_ANCHOR_LEAN
            val r = a[i * 5 + 4] * (1.0 - ANCHOR_TAPER * along)
            val ex = dx - cx
            val ez = dz - cz
            if (ex * ex + ez * ez <= r * r) return WOGOR_WOOD_Y
        }
        return null
    }

    private fun shaftCentreX(site: ChildTreeSite, y: Int): Double =
        site.shaftX + site.shaftLeanX * (site.chamberY - y) * CHILD_SHAFT_LEAN +
            valueNoise2(y * CHILD_SHAFT_WOBBLE_FREQ, site.seed % 1013 + 0.5, CHILD_CHAMBER_SALT + 1) * CHILD_SHAFT_WOBBLE

    private fun shaftCentreZ(site: ChildTreeSite, y: Int): Double =
        site.shaftZ + site.shaftLeanZ * (site.chamberY - y) * CHILD_SHAFT_LEAN +
            valueNoise2(site.seed % 1013 + 0.5, y * CHILD_SHAFT_WOBBLE_FREQ, CHILD_CHAMBER_SALT + 2) * CHILD_SHAFT_WOBBLE

    private fun insideEntrance(pts: DoubleArray, px: Double, py: Double, pz: Double): Boolean {
        for (i in 0 until pts.size / 3 - 1) {
            if (insideCapsule(px, py, pz, pts, i * 3, CHILD_ENTRANCE_RADIUS)) return true
        }
        return false
    }

    /** Capsule from the point at [o] to the next point in [c], radius [r]. */
    private fun insideCapsule(px: Double, py: Double, pz: Double, c: DoubleArray, o: Int, r: Double): Boolean {
        val ex = c[o + 3] - c[o]; val ey = c[o + 4] - c[o + 1]; val ez = c[o + 5] - c[o + 2]
        val lenSq = ex * ex + ey * ey + ez * ez
        val t = if (lenSq < 1e-6) 0.0
        else (((px - c[o]) * ex + (py - c[o + 1]) * ey + (pz - c[o + 2]) * ez) / lenSq).coerceIn(0.0, 1.0)
        val dx = px - (c[o] + ex * t); val dy = py - (c[o + 1] + ey * t); val dz = pz - (c[o + 2] + ez * t)
        return dx * dx + dy * dy + dz * dz <= r * r
    }

    /** Child trees are painted after the chambers are carved; keep their wood out of the chamber and shaft. */
    private fun clearChildChambers(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
    ) {
        val sites = childHeartsNear(chunkX0, chunkZ0)
        if (sites.isEmpty()) return
        for (lx in 0..15) {
            val wx = chunkX0 + lx
            for (lz in 0..15) {
                val wz = chunkZ0 + lz
                val site = childHeartAt(sites, wx, wz) ?: continue
                val col = target[lx][lz]
                val topY = Math.max(
                    site.chamberY + CHILD_CHAMBER_RISE + CAVE_ROOF_THICKNESS,
                    Math.ceil(site.entrance[site.entrance.size - 2]).toInt() + 2,
                )
                for (wy in site.shaftBottomY - CHILD_ANCHOR_BELOW_SHAFT..topY) {
                    val ly = wy - minY
                    if (ly !in 0 until span || col[ly] == null) continue
                    if (childChamberBlock(site, wx, wy, wz) != null) col[ly] = null
                }
            }
        }
    }

    /** Root-network junctions for the child heart shafts whose foot lies in cell `(cx, cz)`. */
    private fun childHeartNodes(cx: Int, cz: Int): DoubleArray {
        val size = WohlonRootField.CELL_SIZE
        var out = EMPTY_NODES
        for (rX in Math.floorDiv(cx * size, CHILD_TREE_REGION_SIZE)..Math.floorDiv(cx * size + size - 1, CHILD_TREE_REGION_SIZE)) {
            for (rZ in Math.floorDiv(cz * size, CHILD_TREE_REGION_SIZE)..Math.floorDiv(cz * size + size - 1, CHILD_TREE_REGION_SIZE)) {
                val site = childTreeSite(rX, rZ) ?: continue
                val footX = shaftCentreX(site, site.shaftBottomY)
                val footZ = shaftCentreZ(site, site.shaftBottomY)
                if (Math.floorDiv(Math.floor(footX).toInt(), size) != cx) continue
                if (Math.floorDiv(Math.floor(footZ).toInt(), size) != cz) continue
                out = out + doubleArrayOf(footX, site.shaftBottomY.toDouble(), footZ, CHILD_NODE_RADIUS)
            }
        }
        return out
    }

    private class ChildTreeSite(
        val x: Int, val z: Int, val baseY: Int, val seed: Int, val coreRadius: Int,
        /** Heart chamber under the trunk: centre height, radius, and the shaft down to the root network. */
        val chamberY: Int, val chamberR: Double,
        val shaftX: Double, val shaftZ: Double, val shaftBottomY: Int,
        /** [AGE_YOUNG] .. [AGE_DEAD]. A dead tree keeps its chamber but has no heart. */
        val age: Int = AGE_MATURE,
        /** Size multiplier from age and site quality (water and open ground). */
        val scale: Double = 1.0,
        val maxThickness: Int = 0,
        val buttressFlare: Int = 0,
        /** Crown shift toward open sky: away from crowding trees, toward water. */
        val leanX: Double = 0.0, val leanZ: Double = 0.0,
        /** Packed `[x, z, outX, outZ, r]` per anchor root. */
        val anchors: DoubleArray = EMPTY_NODES,
        /** Crawlway to the surface between two buttress roots, as a polyline `[x, y, z]` per point. */
        val entrance: DoubleArray = EMPTY_NODES,
        /** The shaft leans this way (unit XZ) as it drops. */
        val shaftLeanX: Double = 0.0, val shaftLeanZ: Double = 0.0,
        /** Horizontal reach of chamber, anchors and entrance from the trunk centre. */
        val reach: Double = 0.0,
        /** Root arm headings, each already turned toward water inside its slot. */
        val rootAngles: DoubleArray = EMPTY_NODES,
        /** Heading to the nearest open water, or NaN on dry ground. Knees gather on this side. */
        val wetAngle: Double = Double.NaN,
    )

    /** Region → its child tree, or [NO_CHILD_TREE]. Pure of caller state, so a race only
     *  repeats work. */
    private val childTreeSiteCache = java.util.concurrent.ConcurrentHashMap<Long, ChildTreeSite>()

    private fun childTreeSite(rX: Int, rZ: Int): ChildTreeSite? {
        val key = (rX.toLong() and 0xFFFFFFFFL) shl 32 or (rZ.toLong() and 0xFFFFFFFFL)
        var site = childTreeSiteCache[key]
        if (site == null) {
            site = computeChildTreeSite(rX, rZ) ?: NO_CHILD_TREE
            if (childTreeSiteCache.size >= CHILD_TREE_SITE_CACHE_CAP) childTreeSiteCache.clear()
            childTreeSiteCache[key] = site
        }
        return if (site === NO_CHILD_TREE) null else site
    }

    private fun computeChildTreeSite(rX: Int, rZ: Int): ChildTreeSite? {
        val regionSeed = hash32(rX, rZ, 7777)
        if (!isChildTreeCandidate(regionSeed)) return null
        val treeX = rX * CHILD_TREE_REGION_SIZE + childTreeOffset(rX, rZ, 7778)
        val treeZ = rZ * CHILD_TREE_REGION_SIZE + childTreeOffset(rX, rZ, 7779)

        // A neighbouring candidate too close wins if it has the lower seed: deterministic per pair.
        if (!isChildTreeCandidateAccepted(rX, rZ, treeX, treeZ, regionSeed)) return null

        val originDistSq = treeX.toLong() * treeX + treeZ.toLong() * treeZ
        if (originDistSq < MOTHER_TREE_EXCLUSION_RADIUS_SQ.toLong() + 100L * 100) return null

        val surface = surfaceYAt(treeX, treeZ)
        // Skip extremely deep pools so the canopy isn't completely submerged.
        if (surface < SEA_LEVEL_Y - 12) return null

        // Trees gather in groves and favour low, wet ground; bluff tops and hilltops are rare.
        val grove = 0.5 + 0.5 * valueNoise2(rX * GROVE_FREQ, rZ * GROVE_FREQ, GROVE_SALT)
        val wetness = waterAround(treeX, treeZ)
        val lowness = (1.0 - (surface - SEA_LEVEL_Y - 2).toDouble() / CHILD_HIGH_GROUND_SPAN).coerceIn(0.15, 1.0)
        val quality = 0.45 * lowness + 0.55 * wetness
        val acceptChance = (0.45 + 0.55 * grove) * (0.45 + 0.55 * quality)
        if (hash01(regionSeed, 1, 1020) > acceptChance) return null

        val ageRoll = hash01(regionSeed, 1, 1021)
        val age = when {
            ageRoll < AGE_YOUNG_SHARE -> AGE_YOUNG
            ageRoll < AGE_YOUNG_SHARE + AGE_MATURE_SHARE -> AGE_MATURE
            ageRoll < 1.0 - AGE_DEAD_SHARE -> AGE_OLD
            else -> AGE_DEAD
        }
        // Good sites grow bigger trees; young trees are small whatever the site.
        val scale = (if (age == AGE_YOUNG) YOUNG_SCALE else 1.0) * (0.85 + 0.3 * quality)
        val fullThickness = ((4.0 + hash01(regionSeed, 0, 1000) * 4.0) * scale).toInt().coerceAtLeast(2)
        // Young trees are slim; their flare is curved and carried by fins (see [paintChildFins]).
        val maxThickness = if (age == AGE_YOUNG) Math.round(fullThickness * YOUNG_THICKNESS_SHARE).toInt().coerceAtLeast(2) else fullThickness
        val buttressFlare = if (age == AGE_YOUNG) Math.round(maxThickness * YOUNG_FLARE_SHARE).toInt()
            else (maxThickness * (1.6 + hash01(regionSeed, 0, 1005) * 0.6)).toInt()
        val coreRadius = maxThickness + buttressFlare

        val chamberY = surface + 1 - CHILD_CHAMBER_DEPTH
        val chamberR = (CHILD_CHAMBER_R_MIN + hash01(regionSeed, 0, 1010) * CHILD_CHAMBER_R_SPREAD) *
            (if (age == AGE_YOUNG) 0.8 else 1.0)
        // Off-centre, so the heart sits on solid floor beside the shaft mouth.
        val shaftAngle = hash01(regionSeed, 0, 1011) * 2.0 * Math.PI
        val shaftX = treeX + 0.5 + Math.cos(shaftAngle) * CHILD_SHAFT_OFFSET
        val shaftZ = treeZ + 0.5 + Math.sin(shaftAngle) * CHILD_SHAFT_OFFSET
        val shaftBottomY = (chamberY - CHILD_SHAFT_DROP).coerceIn(CHILD_SHAFT_BOTTOM_MIN, CHILD_SHAFT_BOTTOM_MAX)

        val anchors = childAnchors(regionSeed, shaftAngle, chamberR, age)
        val entrance = childEntrance(regionSeed, treeX, treeZ, chamberY, chamberR, coreRadius)
        // Leans outward, away from the heart, like the anchor roots around it.
        val shaftLeanX = Math.cos(shaftAngle)
        val shaftLeanZ = Math.sin(shaftAngle)
        val (leanX, leanZ) = crownLean(rX, rZ, treeX, treeZ)

        val anchorReach = chamberR * ANCHOR_START_SHARE + (chamberY - shaftBottomY + CHILD_CHAMBER_RISE) * CHILD_ANCHOR_LEAN + 2.0
        var entranceReach = 0.0
        for (i in 0 until entrance.size / 3) {
            entranceReach = maxOf(entranceReach, Math.hypot(entrance[i * 3] - treeX, entrance[i * 3 + 2] - treeZ))
        }
        entranceReach += CHILD_ENTRANCE_RADIUS + 1.0
        val shaftReach = CHILD_SHAFT_OFFSET + (chamberY - shaftBottomY) * CHILD_SHAFT_LEAN +
            CHILD_SHAFT_WOBBLE + CHILD_SHAFT_RADIUS + WohlonRootField.ROOT_SHELL + 1.0
        val reach = maxOf(chamberR + CHILD_CHAMBER_SLACK, anchorReach, entranceReach, shaftReach)

        return ChildTreeSite(
            treeX, treeZ, surface + 1, regionSeed, coreRadius,
            chamberY, chamberR, shaftX, shaftZ, shaftBottomY,
            age, scale, maxThickness, buttressFlare, leanX, leanZ, anchors, entrance,
            shaftLeanX, shaftLeanZ, reach,
            rootAngles = DoubleArray(childRootCount(regionSeed)) { childRootAngleToWater(treeX, treeZ, regionSeed, it) },
            wetAngle = wetHeading(treeX, treeZ),
        )
    }

    /** Hydrotropism, as the ritual tree's roots do it: the slot heading that reaches water soonest. */
    private fun childRootAngleToWater(treeX: Int, treeZ: Int, seed: Int, i: Int): Double {
        val base = childRootAngle(seed, i)
        val half = Math.PI * 2.0 / childRootCount(seed) * ROOT_TURN_SHARE
        var best = base
        var bestDist = Int.MAX_VALUE
        for (k in -ROOT_TURN_STEPS..ROOT_TURN_STEPS) {
            val a = base + half * k / ROOT_TURN_STEPS
            var d = ROOT_TURN_PROBE_START
            while (d <= ROOT_MAX_REACH && d < bestDist) {
                if (surfaceYAt(treeX + (Math.cos(a) * d).toInt(), treeZ + (Math.sin(a) * d).toInt()) < SEA_LEVEL_Y - 1) {
                    best = a
                    bestDist = d
                    break
                }
                d += ROOT_TURN_PROBE_STEP
            }
        }
        return best
    }

    private fun wetHeading(x: Int, z: Int): Double {
        var vx = 0.0
        var vz = 0.0
        for (i in 0 until 8) {
            val a = i * Math.PI / 4.0
            for (dist in WATER_PROBE_DISTANCES) {
                if (surfaceYAt(x + (Math.cos(a) * dist).toInt(), z + (Math.sin(a) * dist).toInt()) < SEA_LEVEL_Y - 1) {
                    vx += Math.cos(a)
                    vz += Math.sin(a)
                }
            }
        }
        return if (Math.hypot(vx, vz) < 1e-6) Double.NaN else Math.atan2(vz, vx)
    }

    /** Raw candidate test only (no terrain), so neighbours can be checked without recursion. */
    private fun isChildTreeCandidate(regionSeed: Int): Boolean = (regionSeed and 0xF) <= 0xC

    /** Share of 16 sampled points around a spot that are open water. */
    private fun waterAround(x: Int, z: Int): Double {
        var wet = 0
        for (i in 0 until 8) {
            val a = i * Math.PI / 4.0
            for (dist in WATER_PROBE_DISTANCES) {
                if (surfaceYAt(x + (Math.cos(a) * dist).toInt(), z + (Math.sin(a) * dist).toInt()) < SEA_LEVEL_Y - 1) wet++
            }
        }
        return wet / (8.0 * WATER_PROBE_DISTANCES.size)
    }

    /**
     * Crown lean toward open sky: away from nearby child-tree candidates and toward open water.
     * Uses raw neighbour candidates, not their full sites, so no site depends on another's.
     */
    private fun crownLean(rX: Int, rZ: Int, treeX: Int, treeZ: Int): Pair<Double, Double> {
        var vx = 0.0
        var vz = 0.0
        for (dx in -2..2) for (dz in -2..2) {
            if (dx == 0 && dz == 0) continue
            val nrX = rX + dx
            val nrZ = rZ + dz
            if (!isChildTreeCandidate(hash32(nrX, nrZ, 7777))) continue
            val ox = (nrX * CHILD_TREE_REGION_SIZE + childTreeOffset(nrX, nrZ, 7778) - treeX).toDouble()
            val oz = (nrZ * CHILD_TREE_REGION_SIZE + childTreeOffset(nrX, nrZ, 7779) - treeZ).toDouble()
            val d = Math.hypot(ox, oz)
            if (d < 1.0 || d > CROWD_RANGE) continue
            val push = 1.0 - d / CROWD_RANGE
            vx -= ox / d * push
            vz -= oz / d * push
        }
        for (i in 0 until 8) {
            val a = i * Math.PI / 4.0
            val px = treeX + (Math.cos(a) * LIGHT_PROBE_DISTANCE).toInt()
            val pz = treeZ + (Math.sin(a) * LIGHT_PROBE_DISTANCE).toInt()
            if (surfaceYAt(px, pz) < SEA_LEVEL_Y - 1) {
                vx += Math.cos(a) * WATER_LIGHT_PULL
                vz += Math.sin(a) * WATER_LIGHT_PULL
            }
        }
        val len = Math.hypot(vx, vz)
        if (len < 1e-6) return 0.0 to 0.0
        val strength = Math.min(len, 1.0) / len
        return vx * strength to vz * strength
    }

    /** Anchor roots for a chamber: 2–4, clear of the shaft, leaning outward as they descend. */
    private fun childAnchors(seed: Int, shaftAngle: Double, chamberR: Double, age: Int): DoubleArray {
        val count = CHILD_ANCHOR_MIN + Math.floorMod(hash32(seed, 2, 1030), CHILD_ANCHOR_EXTRA + 1)
        val out = DoubleArray(count * 5)
        for (i in 0 until count) {
            val angle = shaftAngle + CHILD_ANCHOR_SHAFT_CLEARANCE +
                (2.0 * Math.PI - 2.0 * CHILD_ANCHOR_SHAFT_CLEARANCE) * (i + 0.5 + (hash01(seed, i, 1031) - 0.5) * 0.5) / count
            val r = (CHILD_ANCHOR_RADIUS_MIN + hash01(seed, i, 1032) * CHILD_ANCHOR_RADIUS_SPREAD) *
                (if (age == AGE_YOUNG) 0.7 else 1.0)
            out[i * 5] = Math.cos(angle) * chamberR * ANCHOR_START_SHARE
            out[i * 5 + 1] = Math.sin(angle) * chamberR * ANCHOR_START_SHARE
            out[i * 5 + 2] = Math.cos(angle)
            out[i * 5 + 3] = Math.sin(angle)
            out[i * 5 + 4] = r
        }
        return out
    }

    /** A crawlway from the chamber wall up to the ground, midway between two buttress roots. */
    private fun childEntrance(seed: Int, treeX: Int, treeZ: Int, chamberY: Int, chamberR: Double, coreRadius: Int): DoubleArray {
        val roots = childRootCount(seed)
        val slot = Math.floorMod(hash32(seed, 3, 1040), roots)
        val angle = childRootAngle(seed, slot) + Math.PI / roots
        val x0 = treeX + 0.5 + Math.cos(angle) * chamberR * 0.7
        val z0 = treeZ + 0.5 + Math.sin(angle) * chamberR * 0.7
        val out = coreRadius + CHILD_ENTRANCE_BEYOND_CORE
        val x1 = treeX + 0.5 + Math.cos(angle) * out
        val z1 = treeZ + 0.5 + Math.sin(angle) * out
        val y1 = Math.max(surfaceYAt(Math.floor(x1).toInt(), Math.floor(z1).toInt()), SEA_LEVEL_Y - 1) + 1.0
        val y0 = chamberY.toDouble()
        // A sagging, sideways-swinging curve, not a straight bore.
        val swing = (hash01(seed, 3, 1041) - 0.5) * 2.0 * CHILD_ENTRANCE_SWING
        val sideX = -Math.sin(angle) * swing
        val sideZ = Math.cos(angle) * swing
        val cx = (x0 + x1) * 0.5 + sideX
        val cz = (z0 + z1) * 0.5 + sideZ
        val cy = (y0 + y1) * 0.5 - CHILD_ENTRANCE_SAG
        val n = CHILD_ENTRANCE_POINTS
        val pts = DoubleArray(n * 3)
        for (i in 0 until n) {
            val t = i.toDouble() / (n - 1)
            val u = 1.0 - t
            pts[i * 3] = u * u * x0 + 2 * u * t * cx + t * t * x1
            pts[i * 3 + 1] = u * u * y0 + 2 * u * t * cy + t * t * y1
            pts[i * 3 + 2] = u * u * z0 + 2 * u * t * cz + t * t * z1
        }
        return pts
    }

    private fun childRootCount(seed: Int): Int = 8 + ((hash32(seed, 0, 51) ushr 1) and 0x3)

    private fun childRootAngle(seed: Int, i: Int): Double {
        val n = childRootCount(seed)
        return i * (Math.PI * 2.0 / n) + (hash01(seed, i, 51) - 0.5) * (Math.PI * 2.0 / n) * 0.4
    }

    /** Modulo, not a bit mask: the region size is 96, and a mask of 95 never gives 32 to 63. */
    private fun childTreeOffset(rX: Int, rZ: Int, salt: Int): Int =
        (hash32(rX, rZ, salt) ushr 1) % CHILD_TREE_REGION_SIZE

    /** True when the point is inside the trunk base of a child tree. Calculated from the tree
     *  sites, not from the chunk's paint cache, so each chunk that paints a part of one root
     *  sphere makes the same decision. */
    private fun isInsideChildTreeCore(px: Int, py: Int, pz: Int): Boolean {
        val regionX = Math.floorDiv(px shr 4, CHILD_TREE_REGION_SIZE_CHUNKS)
        val regionZ = Math.floorDiv(pz shr 4, CHILD_TREE_REGION_SIZE_CHUNKS)
        for (dRegX in -1..1) for (dRegZ in -1..1) {
            val site = childTreeSite(regionX + dRegX, regionZ + dRegZ) ?: continue
            if (py < site.baseY - CHILD_TREE_CORE_BELOW || py > site.baseY + CHILD_TREE_CORE_ABOVE) continue
            val dx = px - site.x
            val dz = pz - site.z
            if (dx * dx + dz * dz <= site.coreRadius * site.coreRadius) return true
        }
        return false
    }

    /** Skeleton cache for Child Trees, keyed by world `(x, z)`. C2ME runs
     *  chunk gen concurrently, and many chunks paint slices of the same
     *  child tree — caching avoids redundant ~½-Mother-Tree skeleton
     *  builds. Entries are pure of caller state (deterministic from seed)
     *  so concurrent races on `computeIfAbsent` just dedupe the work. */
    private val childTreeSkeletonCache:
        java.util.concurrent.ConcurrentHashMap<Long, TreeSkeleton> =
            java.util.concurrent.ConcurrentHashMap()

    private fun getChildTreeSkeleton(site: ChildTreeSite): TreeSkeleton {
        val key = (site.x.toLong() and 0xFFFFFFFFL) shl 32 or
            (site.z.toLong() and 0xFFFFFFFFL)
        childTreeSkeletonCache[key]?.let { return it }
        // No `computeIfAbsent`: the build is long, and it would hold the map bin for all of it.
        val built = buildChildTreeSkeleton(site)
        if (childTreeSkeletonCache.size >= CHILD_TREE_SKELETON_CACHE_CAP) childTreeSkeletonCache.clear()
        return childTreeSkeletonCache.putIfAbsent(key, built) ?: built
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
            if (!isChildTreeCandidate(nSeed)) continue
            val nX = nrX * CHILD_TREE_REGION_SIZE + childTreeOffset(nrX, nrZ, 7778)
            val nZ = nrZ * CHILD_TREE_REGION_SIZE + childTreeOffset(nrX, nrZ, 7779)
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
    private fun buildChildTreeSkeleton(site: ChildTreeSite): TreeSkeleton {
        val seed = site.seed
        val baseX = site.x.toDouble()
        val baseZ = site.z.toDouble()
        val baseYD = site.baseY.toDouble()

        val leanHash = hash01(seed, 0, 1002)
        val gravityHash = hash01(seed, 0, 1003)
        val biasHash = hash01(seed, 0, 1004)
        val young = site.age == AGE_YOUNG
        val canopy = childCanopy(site)
        val scaleFactor = canopy.scaleFactor
        val canopyRx = canopy.rx
        val canopyRy = canopy.ry
        val canopyCy = canopy.cy
        val crownX = canopy.crownX
        val crownZ = canopy.crownZ

        // Ellipsoid volume → attractor density target ~one per 22 blocks.
        val canopyVolume = 4.19 * canopyRx * canopyRx * canopyRy
        val attractorCount = (canopyVolume / 22.0).toInt().coerceIn(if (young) YOUNG_MIN_ATTRACTORS else 400, 2500)

        val maxThickness = site.maxThickness
        val buttressFlare = site.buttressFlare
        val buttressRange = canopy.buttressRange

        val full = WogorTreeSkeleton.build(
            originX = baseX, originY = baseYD, originZ = baseZ,
            attractors = WogorTreeSkeleton.ellipsoidAttractors(
                cx = crownX, cy = canopyCy, cz = crownZ,
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
            taper = young,
            curvedFlare = young,
        )
        return when (site.age) {
            AGE_OLD -> baldCrown(full, seed, baseYD, canopyCy)
            AGE_DEAD -> TreeSkeleton(keepConnected(full.segments, baseYD + (canopyCy - baseYD) * DEAD_CROWN_KEEP), emptyList(), full.maxXZReach)
            else -> full
        }
    }

    private class ChildCanopy(
        val scaleFactor: Double, val rx: Double, val ry: Double, val cy: Double,
        val crownX: Double, val crownZ: Double, val buttressRange: Double,
    )

    /** Crown ellipsoid of a child tree. Young crowns are narrow and upright. */
    private fun childCanopy(site: ChildTreeSite): ChildCanopy {
        val young = site.age == AGE_YOUNG
        // 0.55–0.90 of the Mother Tree's canopy radii, times age and site quality.
        val scaleFactor = (0.55 + hash01(site.seed, 0, 1000) * 0.35) * site.scale
        // Small ratio: narrow tall crown; large ratio: broad crown.
        val ryRatio = if (young) YOUNG_RY_RATIO else 0.18 + hash01(site.seed, 0, 1001) * 0.22
        val rx = 55.0 * scaleFactor * (if (young) YOUNG_CROWN_SHARE else 1.0)
        val ry = rx * ryRatio
        // Phototropism: the crown sits off-centre toward open sky, and the trunk bends to reach it.
        return ChildCanopy(
            scaleFactor, rx, ry, site.baseY + 60.0 * scaleFactor + ry * 0.7,
            site.x + site.leanX * rx * CROWN_LEAN_SHARE, site.z + site.leanZ * rx * CROWN_LEAN_SHARE,
            ry * 1.2 + 8.0,
        )
    }

    /**
     * An old tree keeps its crown but has lost about a quarter of its big limbs, and its leaves in
     * patches, so bare twigs show. The old clean snap at 0.75 of crown height fell below the first
     * fork and left a bare pole.
     */
    private fun baldCrown(full: TreeSkeleton, seed: Int, baseY: Double, canopyCy: Double): TreeSkeleton {
        val segments = full.segments
        if (segments.isEmpty()) return full
        val forks = HashMap<Triple<Double, Double, Double>, Int>()
        for (s in segments) forks.merge(Triple(s.startX, s.startY, s.startZ), 1, Int::plus)
        val dropAbove = baseY + (canopyCy - baseY) * BALD_MIN_HEIGHT_SHARE
        val joined = HashSet<Triple<Double, Double, Double>>()
        joined += Triple(segments[0].startX, segments[0].startY, segments[0].startZ)
        val kept = ArrayList<TreeSegment>(segments.size)
        val keptEnds = HashSet<Long>()
        for ((i, s) in segments.withIndex()) {
            val start = Triple(s.startX, s.startY, s.startZ)
            if (start !in joined) continue
            val isFork = (forks[start] ?: 0) >= 2
            if (isFork && s.endRadius >= BALD_LIMB_MIN_RADIUS && s.startY > dropAbove && hash01(seed, i, 1090) < BALD_LIMB_DROP) continue
            kept += s
            joined += Triple(s.endX, s.endY, s.endZ)
            keptEnds += BlockPos.asLong(s.endX.toInt(), s.endY.toInt(), s.endZ.toInt())
        }
        val tips = full.tips.filter {
            BlockPos.asLong(it.x, it.y, it.z) in keptEnds &&
                valueNoise3D(it.x * BALD_PATCH_FREQ, it.y * BALD_PATCH_FREQ, it.z * BALD_PATCH_FREQ + BALD_PATCH_SALT) > BALD_PATCH_CUT
        }
        return TreeSkeleton(kept, tips, full.maxXZReach)
    }

    /**
     * Segments still joined to the trunk when the crown snaps at [breakY]. A height filter alone
     * keeps branches that dip back under the break and leaves them floating.
     * Relies on the skeleton listing each parent's segments before its children's.
     */
    private fun keepConnected(segments: List<TreeSegment>, breakY: Double): List<TreeSegment> {
        if (segments.isEmpty()) return segments
        val root = segments[0]
        val joined = HashSet<Triple<Double, Double, Double>>()
        joined += Triple(root.startX, root.startY, root.startZ)
        val kept = ArrayList<TreeSegment>(segments.size)
        for (s in segments) {
            if (s.startY > breakY || Triple(s.startX, s.startY, s.startZ) !in joined) continue
            kept += s
            joined += Triple(s.endX, s.endY, s.endZ)
        }
        return kept
    }

    /** Hand-coded buttress root arms for Child Trees — a half-scale
     *  version of the Mother Tree's roots (three-phase rise / walk /
     *  plunge), rendered through the SDF capsule rasterizer. */
    private fun paintChildTreeRoots(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        site: ChildTreeSite,
    ) {
        val seed = site.seed
        val treeX = site.x
        val treeZ = site.z
        val rootCount = childRootCount(seed)
        val baseXD = treeX.toDouble()
        val baseZD = treeZ.toDouble()
        val baseYD = site.baseY.toDouble()
        val rootScale = Math.sqrt(site.scale)
        for (i in 0 until rootCount) {
            val angle = site.rootAngles.getOrElse(i) { childRootAngle(seed, i) }
            val cosA = Math.cos(angle); val sinA = Math.sin(angle)
            val perpX = -sinA; val perpZ = cosA

            // Hydrotropism: an arm reaches out to water it can find, and falls short on a climb.
            var reach = ((25 + (hash32(seed, i, 52) ushr 1) % 19) * rootScale).toInt()   // 25..43
            for (probe in ROOT_WATER_PROBES) {
                val px = (baseXD + cosA * probe).toInt()
                val pz = (baseZD + sinA * probe).toInt()
                if (surfaceYAt(px, pz) < SEA_LEVEL_Y - 1) {
                    reach = Math.max(reach, Math.min(probe + ROOT_WATER_OVERSHOOT, ROOT_MAX_REACH))
                    break
                }
            }
            val climb = surfaceYAt((baseXD + cosA * ROOT_CLIMB_PROBE).toInt(), (baseZD + sinA * ROOT_CLIMB_PROBE).toInt()) - site.baseY
            if (climb > ROOT_CLIMB_LIMIT) reach = (reach * ROOT_CLIMB_SHRINK).toInt()
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
            var prevR = CHILD_TREE_ROOT_BASE_RADIUS * rootScale
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
                        // Over water the arm arches at the water line instead of sinking to the bed.
                        val hillY = Math.max(effectiveSurfaceYAt(px.toInt(), pz.toInt()), SEA_LEVEL_Y - 1).toDouble()
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

                val baseR = CHILD_TREE_ROOT_BASE_RADIUS * rootScale
                val curR = when {
                    t < walkEnd * 0.5 -> baseR
                    t < walkEnd -> baseR * (1.0 - (t - walkEnd * 0.5) / (walkEnd * 0.5) * 0.4)
                    else -> baseR * 0.6 * (1.0 - (t - walkEnd) / (1.0 - walkEnd) * 0.9)
                }

                paintTreeSegment(target, chunkX0, chunkZ0, minY, span,
                    TreeSegment(prevX, prevY, prevZ, px, py, pz, prevR, curR),
                    WOGOR_WOOD_Y)
                prevX = px; prevY = py; prevZ = pz; prevR = curR
            }
        }
    }

    /**
     * Old and dead trees go hollow at the base: a rough cavity that narrows to a dome, with an
     * arched, ragged opening on one side.
     */
    private fun hollowChildTrunk(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        site: ChildTreeSite,
    ) {
        val coreR = site.maxThickness * HOLLOW_CORE_SHARE
        val height = HOLLOW_MIN_HEIGHT + Math.floorMod(hash32(site.seed, 4, 1050), HOLLOW_EXTRA_HEIGHT + 1)
        val openAngle = hash01(site.seed, 4, 1051) * 2.0 * Math.PI
        val openX = Math.cos(openAngle)
        val openZ = Math.sin(openAngle)
        val openHeight = HOLLOW_OPENING_HEIGHT + hash01(site.seed, 4, 1052) * HOLLOW_OPENING_EXTRA
        val salt = Math.floorMod(site.seed, 9973)
        // The opening must cut through the whole buttress flare, not just the trunk.
        val reach = site.coreRadius + 1
        for (wx in Math.max(site.x - reach, chunkX0)..Math.min(site.x + reach, chunkX0 + 15)) {
            for (wz in Math.max(site.z - reach, chunkZ0)..Math.min(site.z + reach, chunkZ0 + 15)) {
                val col = target[wx - chunkX0][wz - chunkZ0]
                for (wy in site.baseY..site.baseY + height) {
                    val ly = wy - minY
                    if (ly !in 0 until span || col[ly] !== WOGOR_WOOD_Y) continue
                    val rise = (wy - site.baseY).toDouble()
                    // The cavity drifts a little with height, so its walls are not a straight tube.
                    val cx = site.x + HOLLOW_DRIFT * WohlonRootField.noise3(rise * HOLLOW_DRIFT_FREQ, 0.0, salt.toDouble(), 401)
                    val cz = site.z + HOLLOW_DRIFT * WohlonRootField.noise3(salt.toDouble(), 0.0, rise * HOLLOW_DRIFT_FREQ, 402)
                    val dx = wx - cx
                    val dz = wz - cz
                    val d = Math.hypot(dx, dz)
                    val rough = 1.0 + HOLLOW_ROUGHNESS * WohlonRootField.noise3(wx * HOLLOW_ROUGH_FREQ, wy * HOLLOW_ROUGH_FREQ, wz * HOLLOW_ROUGH_FREQ, 403)
                    val t = rise / height
                    val cavityR = coreR * Math.sqrt((1.0 - t * t).coerceAtLeast(0.0)) * rough
                    val along = dx * openX + dz * openZ
                    val across = Math.abs(-dx * openZ + dz * openX)
                    val archT = rise / openHeight
                    val archHalf = HOLLOW_OPENING_HALF_WIDTH * Math.sqrt((1.0 - archT * archT).coerceAtLeast(0.0)) * rough
                    val inOpening = along > 0 && across <= archHalf
                    if (d <= cavityR || inOpening) col[ly] = AIR
                }
            }
        }
    }

    /**
     * Tapered cypress knees, only where the ground is wet. Each tip stands 1–2.6 blocks over the local
     * water (or ground), and the knees gather on the side of the tree that faces water.
     */
    private fun paintChildKnees(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        site: ChildTreeSite,
    ) {
        if (site.age == AGE_YOUNG) return
        val count = ((CHILD_KNEES_MIN + Math.floorMod(hash32(site.seed, 5, 1060), CHILD_KNEES_EXTRA + 1)) * site.scale).toInt()
        val reach = site.coreRadius + CHILD_KNEE_GAP + CHILD_KNEE_REACH + CHILD_KNEE_WIDE_RADIUS
        if (!columnsNear(chunkX0, chunkZ0, site.x, site.z, reach)) return
        for (k in 0 until count) {
            val angle = if (site.wetAngle.isNaN()) hash01(site.seed, k, 1061) * 2.0 * Math.PI
                else site.wetAngle + (hash01(site.seed, k, 1061) + hash01(site.seed, k, 1065) + hash01(site.seed, k, 1066) - 1.5) * CHILD_KNEE_WET_SPREAD
            val dist = site.coreRadius + CHILD_KNEE_GAP + hash01(site.seed, k, 1062) * CHILD_KNEE_REACH
            val kx = site.x + Math.round(Math.cos(angle) * dist).toInt()
            val kz = site.z + Math.round(Math.sin(angle) * dist).toInt()
            val ground = effectiveSurfaceYAt(kx, kz)
            if (ground > SEA_LEVEL_Y) continue
            val top = maxOf(ground, SEA_LEVEL_Y - 1) + 1.0 + hash01(site.seed, k, 1063) * CHILD_KNEE_RISE
            val r0 = if (hash01(site.seed, k, 1064) < CHILD_KNEE_WIDE_SHARE) CHILD_KNEE_WIDE_RADIUS else CHILD_KNEE_RADIUS
            val cx = kx + 0.5
            val cz = kz + 0.5
            val leanX = (hash01(site.seed, k, 1067) - 0.5) * CHILD_KNEE_LEAN
            val leanZ = (hash01(site.seed, k, 1068) - 0.5) * CHILD_KNEE_LEAN
            paintTreeSegment(target, chunkX0, chunkZ0, minY, span,
                TreeSegment(cx, ground + 0.5, cz, cx + leanX, top + 0.5, cz + leanZ, r0, CHILD_KNEE_TIP_RADIUS), WOGOR_WOOD_Y)
        }
    }

    /** True when any column of the chunk is within [reach] of ([x], [z]). */
    private fun columnsNear(chunkX0: Int, chunkZ0: Int, x: Int, z: Int, reach: Double): Boolean {
        val dx = (x.coerceIn(chunkX0, chunkX0 + 15) - x).toDouble()
        val dz = (z.coerceIn(chunkZ0, chunkZ0 + 15) - z).toDouble()
        return dx * dx + dz * dz <= reach * reach
    }

    /**
     * A young tree's buttress fins, one along each root arm: tall at the trunk, falling away in a curve,
     * so the roots grow out of the fins and not out of a cone.
     */
    private fun paintChildFins(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        site: ChildTreeSite,
    ) {
        val d0 = site.maxThickness + 1.0
        val d1 = site.coreRadius + FIN_BEYOND_CORE
        if (!columnsNear(chunkX0, chunkZ0, site.x, site.z, d1 + FIN_BASE_RADIUS + 1.0)) return
        val finH = childCanopy(site).buttressRange * FIN_HEIGHT_SHARE
        val cx = site.x + 0.5
        val cz = site.z + 0.5
        val baseY = site.baseY - 1.0
        for (angle in site.rootAngles) {
            val c = Math.cos(angle)
            val s = Math.sin(angle)
            var d = d0
            while (d <= d1) {
                val f = (d - d0) / (d1 - d0)
                val h = finH * (1.0 - f) * (1.0 - f) + 0.6
                paintTreeSegment(target, chunkX0, chunkZ0, minY, span,
                    TreeSegment(cx + c * d, baseY, cz + s * d, cx + c * d, baseY + h, cz + s * d, FIN_BASE_RADIUS, FIN_TOP_RADIUS),
                    WOGOR_WOOD_Y)
                d += FIN_STEP
            }
        }
    }

    /**
     * Wey'ye fruit grows straight from the trunk of a living, mature child tree, cacao-style:
     * low on the trunk in the shade of its own crown, in loose clusters.
     */
    private fun paintWeyyePods(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        site: ChildTreeSite,
    ) {
        val reach = site.maxThickness + 3
        val pod = EKBlocks.WEYYE_FRUIT_POD.get().defaultBlockState()
        for (wx in Math.max(site.x - reach, chunkX0)..Math.min(site.x + reach, chunkX0 + 15)) {
            for (wz in Math.max(site.z - reach, chunkZ0)..Math.min(site.z + reach, chunkZ0 + 15)) {
                val lx = wx - chunkX0
                val lz = wz - chunkZ0
                val col = target[lx][lz]
                val surface = effectiveSurfaceYAt(wx, wz)
                for (wy in site.baseY + WEYYE_MIN_HEIGHT..site.baseY + WEYYE_MAX_HEIGHT) {
                    val ly = wy - minY
                    if (ly !in 0 until span) continue
                    val cell = col[ly]
                    if (!(cell === AIR || (cell == null && wy > surface && wy >= SEA_LEVEL_Y))) continue
                    val cluster = 0.5 + 0.5 * valueNoise2(wy * WEYYE_CLUSTER_FREQ, Math.atan2((wz - site.z).toDouble(), (wx - site.x).toDouble()) * 2.0, WEYYE_SALT)
                    val roll = (hash32(wx, wy * 31 + wz, WEYYE_SALT) and 0xFFFF) / 65536.0
                    if (roll >= WEYYE_CHANCE * cluster * cluster) continue
                    for (dir in HORIZONTAL_DIRECTIONS) {
                        val nx = lx + dir.stepX
                        val nz = lz + dir.stepZ
                        if (nx !in 0..15 || nz !in 0..15) continue
                        if (target[nx][nz][ly] !== WOGOR_WOOD_Y) continue
                        val ageRoll = (hash32(wz, wx, wy) and 0xFF) / 256.0
                        val age = if (ageRoll < WEYYE_RIPE_SHARE) 2 else if (ageRoll < 0.6) 1 else 0
                        col[ly] = pod.setValue(net.minecraft.world.level.block.CocoaBlock.FACING, dir)
                            .setValue(net.minecraft.world.level.block.CocoaBlock.AGE, age)
                        break
                    }
                }
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
            if (Math.max(seg.startRadius, seg.endRadius) < TWIG_RADIUS) paintTwig(target, chunkX0, chunkZ0, minY, span, seg, wood)
            else paintTreeSegment(target, chunkX0, chunkZ0, minY, span, seg, wood)
        }
    }

    /** A twig as a face-connected line of blocks. A sub-block capsule leaves diagonal chains with gaps. */
    private fun paintTwig(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        seg: TreeSegment, wood: BlockState,
    ) {
        val len = Math.max(Math.abs(seg.endX - seg.startX), Math.max(Math.abs(seg.endY - seg.startY), Math.abs(seg.endZ - seg.startZ)))
        val steps = Math.ceil(len * 2.0).toInt() + 1
        var px = Math.floor(seg.startX).toInt()
        var py = Math.floor(seg.startY).toInt()
        var pz = Math.floor(seg.startZ).toInt()
        placeWoody(target, chunkX0, chunkZ0, minY, span, px, py, pz, wood)
        for (s in 1..steps) {
            val t = s.toDouble() / steps
            val x = Math.floor(seg.startX + (seg.endX - seg.startX) * t).toInt()
            val y = Math.floor(seg.startY + (seg.endY - seg.startY) * t).toInt()
            val z = Math.floor(seg.startZ + (seg.endZ - seg.startZ) * t).toInt()
            if (x != px) { px = x; placeWoody(target, chunkX0, chunkZ0, minY, span, px, py, pz, wood) }
            if (y != py) { py = y; placeWoody(target, chunkX0, chunkZ0, minY, span, px, py, pz, wood) }
            if (z != pz) { pz = z; placeWoody(target, chunkX0, chunkZ0, minY, span, px, py, pz, wood) }
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
                    if (col[ly] === WOGOR_LEAVES) { lowestLeafLY = ly; break }
                }
                if (lowestLeafLY < 0) continue
                // Effective surface for this column. Vines below the
                // water surface would overwrite water in the merge pass,
                // since features override base blocks. Stop the vine
                // column when it would hang into water/terrain.
                val effSurface = effectiveSurfaceYAt(wx, wz)
                val waterTop = if (effSurface >= SEA_LEVEL_Y) effSurface else SEA_LEVEL_Y
                // Bits 0–2 gate the column and 8–9 pick the face; the length takes its own bits.
                val len = 1 + ((h ushr 12) and 0xFFFF) % maxLen
                val vineState = VINE_BLOCKS[supportedVineFace(target, lx, lz, lowestLeafLY - 1, (h ushr 8) and 0x3)]
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
     * Long vines on the outer side of the crown's rim leaves, so the edge weeps; now and then a short
     * leaf strand drops instead. Only leaves in this chunk with an outward neighbour in this chunk count,
     * so the result does not depend on which chunk generates first.
     */
    @Suppress("LongParameterList") // chunk slice + crown centre and rim + length range + seed
    private fun paintVineEdge(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        cx: Double, cz: Double, minR: Double, maxR: Int, minLen: Int, maxLen: Int, seed: Int,
    ) {
        val nearX = cx.coerceIn(chunkX0.toDouble(), chunkX0 + 15.0) - cx
        val nearZ = cz.coerceIn(chunkZ0.toDouble(), chunkZ0 + 15.0) - cz
        if (nearX * nearX + nearZ * nearZ > maxR.toDouble() * maxR) return
        for (lx in 0..15) for (lz in 0..15) {
            val wx = chunkX0 + lx
            val wz = chunkZ0 + lz
            val dx = wx + 0.5 - cx
            val dz = wz + 0.5 - cz
            val d = Math.hypot(dx, dz)
            if (d < minR || d > maxR) continue
            // Outward along the larger axis; [VINE_BLOCKS] faces are NORTH, SOUTH, EAST, WEST.
            val alongX = Math.abs(dx) >= Math.abs(dz)
            val ox = if (alongX) Math.signum(dx).toInt() else 0
            val oz = if (alongX) 0 else Math.signum(dz).toInt()
            val nx = lx + ox
            val nz = lz + oz
            if (nx !in 0..15 || nz !in 0..15) continue
            val face = when { ox > 0 -> 3; ox < 0 -> 2; oz > 0 -> 0; else -> 1 }
            val col = target[lx][lz]
            val out = target[nx][nz]
            val waterTop = Math.max(effectiveSurfaceYAt(wx + ox, wz + oz), SEA_LEVEL_Y)
            for (ly in span - 1 downTo 1) {
                if (col[ly] !== WOGOR_LEAVES) continue
                val h = hash32(wx, ly * 7919 + wz, seed)
                val roll = (h and 0xFFFF) / 65536.0
                val len = minLen + ((h ushr 16) and 0x7FFF) % (maxLen - minLen + 1)
                if (out[ly] == null && roll < VINE_EDGE_CHANCE) {
                    hangColumn(out, ly, len, VINE_BLOCKS[face], minY, waterTop)
                } else if (col[ly - 1] == null && roll > 1.0 - VINE_EDGE_LEAF_CHANCE) {
                    hangColumn(col, ly - 1, (len + 1) / 2, WOGOR_LEAVES, minY, waterTop)
                }
            }
        }
    }

    /** Fills empty cells of [col] down from [fromLy], stopping at anything solid or at the water. */
    private fun hangColumn(col: Array<BlockState?>, fromLy: Int, len: Int, state: BlockState, minY: Int, waterTop: Int) {
        for (v in 0 until len) {
            val ly = fromLy - v
            if (ly < 0 || col[ly] != null || ly + minY <= waterTop) return
            col[ly] = state
        }
    }

    /** Face for a hanging vine column. A vine with a side face lives only with a block on that
     *  side, or with a vine above that has the same face. So the face of the top vine goes to
     *  a side that holds a block, when one exists; [preferred] is the fallback. */
    private fun supportedVineFace(
        target: Array<Array<Array<BlockState?>>>, lx: Int, lz: Int, ly: Int, preferred: Int,
    ): Int {
        if (ly < 0) return preferred
        for (i in 0..3) {
            val face = (preferred + i) and 0x3
            // [VINE_BLOCKS] order: NORTH, SOUTH, EAST, WEST.
            val nx = lx + (if (face == 2) 1 else if (face == 3) -1 else 0)
            val nz = lz + (if (face == 0) -1 else if (face == 1) 1 else 0)
            if (nx !in 0..15 || nz !in 0..15) continue
            val cell = target[nx][nz][ly] ?: continue
            if (cell === WOGOR_LEAVES || cell === WOGOR_WOOD_Y || cell === WOGOR_WOOD_X || cell === WOGOR_WOOD_Z) {
                return face
            }
        }
        return preferred
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
        if (existing == null || existing === WOGOR_LEAVES || isRiverCell(existing, wx, wy, wz)) {
            col[ly] = block
        }
    }

    /** A river cell loses to wood. The heart tunnel paints the same AIR and WATER states, and
     *  it must stay open, so the tunnel volume is excluded. */
    private fun isRiverCell(existing: BlockState, wx: Int, wy: Int, wz: Int): Boolean {
        if (existing !== AIR && existing !== WATER) return false
        if (wx.toLong() * wx + wz.toLong() * wz > HEART_TUNNEL_REACH_PLUS_PAD_SQ) return true
        return !isVoxelInsideHeartTunnel(wx + 0.5, wy + 0.5, wz + 0.5)
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
        val existing = col[ly]
        if (existing != null && !(existing === AIR && isRiverCell(existing, wx, wy, wz))) return
        // 0.1 % chance of a sea lantern lighting up the canopy.
        col[ly] = if ((hash32(wx, wy, wz) and 0x3FF) == 0) SEA_LANTERN
        else WOGOR_LEAVES
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
     * the cached path, and rasterises a tapered
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
        java.util.Arrays.fill(rootRingScratch.get(), -1)

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

    /** Cached tunnel paths, keyed by region (x, z). Built via
     *  [WohlonnogondoniaSurfaceRoots.buildPaths]. Empty array
     *  represents a sparse-gap region. [ConcurrentHashMap] so C2ME
     *  workers can race harmlessly. */
    private val tunnelPathCache:
        java.util.concurrent.ConcurrentHashMap<Long, Array<WohlonnogondoniaSurfaceRoots.TunnelPath>> =
            java.util.concurrent.ConcurrentHashMap()

    private fun getTunnelPaths(regionX: Int, regionZ: Int): Array<WohlonnogondoniaSurfaceRoots.TunnelPath> {
        val key = (regionX.toLong() and 0xFFFFFFFFL) shl 32 or
            (regionZ.toLong() and 0xFFFFFFFFL)
        val cached = tunnelPathCache[key]
        if (cached != null) return cached
        val built = WohlonnogondoniaSurfaceRoots.buildPaths(
            regionX, regionZ,
            heightAt = ::surfaceYAt,
            yMin = 2,
            yMax = MAX_BUILD_Y - 2,
            motherTreeExclusionSq = TUNNEL_MOTHER_TREE_EXCLUSION_SQ,
            waterTopY = SEA_LEVEL_Y - 1,
        )
        if (tunnelPathCache.size >= TUNNEL_PATH_CACHE_CAP) tunnelPathCache.clear()
        return tunnelPathCache.putIfAbsent(key, built) ?: built
    }

    /**
     * Rasterise a sphere at every path point, at the shared tapered radius
     * ([WohlonnogondoniaSurfaceRoots.radiusAt]). Points whose XZ AABB misses the chunk are
     * O(1) rejected before the inner loop. Path points inside the
     * Mother Tree exclusion are skipped per-point so a tunnel that
     * wanders through the trunk doesn't gouge it.
     */
    private fun paintTunnelPathInto(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
        path: TunnelPath,
    ) {
        val pts = path.points
        val n = path.count
        val ring = rootRingScratch.get()
        for (i in 0 until n) {
            val rD = WohlonnogondoniaSurfaceRoots.radiusAt(path, i)
            val rSqMax = rD * rD
            val r = Math.ceil(rD).toInt()
            val px = pts[i * 3 + 0]
            val py = pts[i * 3 + 1]
            val pz = pts[i * 3 + 2]
            // O(1) chunk-AABB reject, one block wider for the ring.
            if (px + r < chunkX0 - 1 || px - r > chunkX0 + 16) continue
            if (pz + r < chunkZ0 - 1 || pz - r > chunkZ0 + 16) continue
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
            if (isInsideChildTreeCore(px, py, pz)) continue

            for (dx in -r..r) {
                val nx = px + dx
                val lx = nx - chunkX0
                if (lx !in -1..16) continue
                val dxSq = dx * dx
                for (dz in -r..r) {
                    val nz = pz + dz
                    val lz = nz - chunkZ0
                    if (lz !in -1..16) continue
                    val dxzSq = dxSq + dz * dz
                    if (dxzSq > rSqMax) continue
                    if (lx !in 0..15 || lz !in 0..15) {
                        val topDy = Math.sqrt(rSqMax - dxzSq).toInt()
                        val topLy = Math.min(py + topDy, MAX_BUILD_Y - 1) - minY
                        val idx = ringIndex(lx, lz)
                        if (topLy in 0 until span && topLy > ring[idx]) ring[idx] = topLy
                        continue
                    }
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
                        if (existing != null && existing !== WATER &&
                            !(existing === AIR && isRiverCell(existing, nx, ny, nz))
                        ) continue
                        col[ly] = WOGOR_WOOD_Y
                    }
                }
            }
        }
    }


    // ------------------------------------------------------------------------
    //   rivers radiating from the moat
    // ------------------------------------------------------------------------

    /**
     * [RIVER_COUNT] channels radiating from the moat, plus tributaries and oxbow lakes.
     * Main rivers meander on a sine whose wavelength is tied to channel width (about 12×,
     * as in alluvial rivers), vary in width, and thin out into the swamp at their ends.
     *
     * Lazy-initialised once; C2ME workers race harmlessly on the synchronized init.
     */
    private val rivers: Array<RiverPath> by lazy { buildRiverNetwork() }

    /** Furthest any river column (bank included) reaches from origin, squared. */
    private val riverReachSq: Long by lazy {
        var max = 0.0
        for (river in rivers) {
            for (i in 0 until river.count) {
                val d = Math.hypot(river.x[i].toDouble(), river.z[i].toDouble()) + river.r[i]
                if (d > max) max = d
            }
        }
        val reach = (max + RIVER_BANK_WINDOW + 2).toLong()
        reach * reach
    }

    private fun buildRiverNetwork(): Array<RiverPath> {
        val out = ArrayList<RiverPath>()
        for (idx in 0 until RIVER_COUNT) {
            val seed = hash32(idx, 0, RIVER_SALT)
            val jitter = (unit(seed) - 0.5) * 2.0 * RIVER_ANGLE_JITTER
            val angle = idx * (2.0 * Math.PI / RIVER_COUNT) + jitter
            val start = MOAT_OUTER_R + 2.0
            val steps = RIVER_MIN_STEPS + Math.floorMod(seed ushr 8, RIVER_EXTRA_STEPS)
            val baseR = RIVER_MIN_RADIUS + unit(seed ushr 16) * RIVER_RADIUS_SPREAD
            val main = buildMeanderPath(
                idx * 97, start * Math.cos(angle), start * Math.sin(angle), angle, steps, baseR,
                radialBias = true, levee = true,
            )
            out += main
            addTributaries(out, main, idx, seed)
            addOxbows(out, main, idx, seed, baseR)
        }
        return out.toTypedArray()
    }

    private fun addTributaries(out: MutableList<RiverPath>, main: RiverPath, idx: Int, seed: Int) {
        val count = 1 + Math.floorMod(seed ushr 4, RIVER_MAX_TRIBUTARIES)
        for (t in 0 until count) {
            val ts = hash32(idx, t, RIVER_SALT + 1)
            val at = (main.count * (TRIBUTARY_START_MIN + unit(ts) * TRIBUTARY_START_SPAN)).toInt()
            val tangent = main.tangent(at)
            val side = if ((ts and 1) == 0) 1.0 else -1.0
            val heading = tangent + side * (TRIBUTARY_ANGLE_MIN + unit(ts ushr 8) * TRIBUTARY_ANGLE_SPAN)
            val steps = TRIBUTARY_MIN_STEPS + Math.floorMod(ts ushr 12, TRIBUTARY_EXTRA_STEPS)
            val baseR = TRIBUTARY_MIN_RADIUS + unit(ts ushr 20) * TRIBUTARY_RADIUS_SPREAD
            out += buildMeanderPath(
                idx * 97 + 11 + t, main.x[at].toDouble(), main.z[at].toDouble(), heading, steps, baseR,
                radialBias = false, levee = true,
            )
        }
    }

    /** Cut-off meander bends: crescents beside an apex of the channel, not joined to it. */
    private fun addOxbows(out: MutableList<RiverPath>, main: RiverPath, idx: Int, seed: Int, mainR: Double) {
        val count = Math.floorMod(seed ushr 6, RIVER_MAX_OXBOWS + 1)
        val wavelength = MEANDER_WAVELENGTH_PER_RADIUS * mainR
        val amplitude = MEANDER_AMPLITUDE_RATIO * wavelength
        for (o in 0 until count) {
            val os = hash32(idx, o, RIVER_SALT + 2)
            val at = (main.count * (OXBOW_START_MIN + unit(os) * OXBOW_START_SPAN)).toInt()
            val tangent = main.tangent(at)
            val side = if ((os and 1) == 0) 1.0 else -1.0
            val nx = -Math.sin(tangent) * side
            val nz = Math.cos(tangent) * side
            val arcR = OXBOW_ARC_RADIUS_RATIO * wavelength
            val offset = arcR + mainR + OXBOW_RADIUS + OXBOW_CLEARANCE + amplitude * 0.5
            val cx = main.x[at] + nx * offset
            val cz = main.z[at] + nz * offset
            val facing = Math.atan2(-nz, -nx)
            val n = OXBOW_POINTS
            val xs = FloatArray(n)
            val zs = FloatArray(n)
            val rs = FloatArray(n)
            for (i in 0 until n) {
                val a = facing + (i.toDouble() / (n - 1) - 0.5) * OXBOW_ARC_SPAN
                xs[i] = (cx + Math.cos(a) * arcR).toFloat()
                zs[i] = (cz + Math.sin(a) * arcR).toFloat()
                val taper = Math.sin(Math.PI * i / (n - 1)).coerceAtLeast(0.35)
                rs[i] = (OXBOW_RADIUS * taper).toFloat()
            }
            out += RiverPath(xs, zs, rs, n, levee = false)
        }
    }

    /**
     * A base walk (gentle yaw drift, optional outward pull) displaced sideways by a sine whose
     * wavelength scales with [baseR]. Width varies along the path; the last stretch narrows so
     * the channel thins out into the swamp instead of stopping.
     */
    private fun buildMeanderPath(
        id: Int, startX: Double, startZ: Double, startYaw: Double, steps: Int, baseR: Double,
        radialBias: Boolean, levee: Boolean,
    ): RiverPath {
        val wavelength = MEANDER_WAVELENGTH_PER_RADIUS * baseR
        val amplitude = MEANDER_AMPLITUDE_RATIO * wavelength
        val phase = unit(hash32(id, 1, RIVER_SALT + 3)) * 2.0 * Math.PI
        val xs = FloatArray(steps)
        val zs = FloatArray(steps)
        val rs = FloatArray(steps)
        var bx = startX
        var bz = startZ
        var yaw = startYaw
        for (step in 0 until steps) {
            bx += Math.cos(yaw) * RIVER_STEP_LEN
            bz += Math.sin(yaw) * RIVER_STEP_LEN
            yaw += (unit(hash32(id, step, RIVER_SALT + 4)) - 0.5) * RIVER_YAW_TURN
            if (radialBias) {
                var diff = Math.atan2(bz, bx) - yaw
                while (diff > Math.PI) diff -= 2.0 * Math.PI
                while (diff < -Math.PI) diff += 2.0 * Math.PI
                yaw += diff * RIVER_RADIAL_BIAS
            }
            val s = step * RIVER_STEP_LEN
            // Ramp the sine in so every river leaves its source on the base line.
            val ramp = (s / wavelength).coerceAtMost(1.0)
            val lateral = Math.sin(2.0 * Math.PI * s / wavelength + phase) * amplitude * ramp
            xs[step] = (bx - Math.sin(yaw) * lateral).toFloat()
            zs[step] = (bz + Math.cos(yaw) * lateral).toFloat()
            val widthNoise = valueNoise2(s * RIVER_WIDTH_NOISE_FREQ, id * 13.1, RIVER_SALT + 5)
            val tail = ((steps - step).toDouble() / (steps * RIVER_TAIL_FRACTION)).coerceAtMost(1.0)
            val r = baseR * (1.0 + RIVER_WIDTH_VARIATION * widthNoise) * (0.4 + 0.6 * tail)
            rs[step] = r.coerceAtLeast(RIVER_MIN_PAINT_RADIUS).toFloat()
        }
        return RiverPath(xs, zs, rs, steps, levee)
    }

    /**
     * Paint every river near this chunk as a channel cross-section: a U-shaped bed, a levee of
     * +1–2 along the bank on near-water ground, and banks graded at about 1:1 where the river
     * cuts through higher ground, so it never leaves a slot under an overhang.
     */
    private fun paintRiversInto(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
    ) {
        val nearestCornerX = if (chunkX0 + 15 < 0) chunkX0 + 15 else if (chunkX0 > 0) chunkX0 else 0
        val nearestCornerZ = if (chunkZ0 + 15 < 0) chunkZ0 + 15 else if (chunkZ0 > 0) chunkZ0 else 0
        val cornerDistSq = nearestCornerX.toLong() * nearestCornerX + nearestCornerZ.toLong() * nearestCornerZ
        if (cornerDistSq > riverReachSq) return

        val scratch = riverScratch.get()
        java.util.Arrays.fill(scratch.edge, Float.MAX_VALUE)
        var touched = false
        for (river in rivers) {
            for (i in 0 until river.count) {
                val px = river.x[i]
                val pz = river.z[i]
                val r = river.r[i]
                val reach = r + RIVER_BANK_WINDOW
                if (px + reach < chunkX0 || px - reach > chunkX0 + 15) continue
                if (pz + reach < chunkZ0 || pz - reach > chunkZ0 + 15) continue
                touched = true
                for (lx in 0..15) {
                    val dx = chunkX0 + lx - px
                    for (lz in 0..15) {
                        val dz = chunkZ0 + lz - pz
                        val e = Math.sqrt((dx * dx + dz * dz).toDouble()).toFloat() - r
                        val k = lx * 16 + lz
                        if (e < scratch.edge[k]) {
                            scratch.edge[k] = e
                            scratch.radius[k] = r
                            scratch.levee[k] = river.levee
                        }
                    }
                }
            }
        }
        if (!touched) return

        for (lx in 0..15) {
            for (lz in 0..15) {
                val k = lx * 16 + lz
                val e = scratch.edge[k]
                if (e > RIVER_BANK_WINDOW) continue
                val wx = chunkX0 + lx
                val wz = chunkZ0 + lz
                val natural = effectiveSurfaceYAt(wx, wz)
                val shaped = riverSurface(wx, wz, natural, e, scratch.radius[k], scratch.levee[k])
                if (shaped == natural) continue
                val col = target[lx][lz]
                if (shaped < natural) {
                    for (y in shaped + 1..natural) {
                        val ly = y - minY
                        if (ly in 0 until span) col[ly] = if (y < SEA_LEVEL_Y) WATER else AIR
                    }
                } else {
                    for (y in natural + 1..shaped) {
                        val ly = y - minY
                        if (ly in 0 until span) col[ly] = MUD
                    }
                }
            }
        }
    }

    /** Surface height for a column [edge] blocks outside the nearest channel edge (negative = inside). */
    private fun riverSurface(wx: Int, wz: Int, natural: Int, edge: Float, radius: Float, levee: Boolean): Int {
        val waterTop = SEA_LEVEL_Y - 1
        if (edge <= 0f) {
            val t = ((edge + radius) / radius).coerceIn(0f, 1f)
            val maxDepth = (RIVER_DEPTH_BASE + RIVER_DEPTH_PER_RADIUS * radius).coerceAtMost(RIVER_MAX_DEPTH)
            val depth = Math.max(1, Math.round(maxDepth * (1f - t * t)))
            return Math.min(natural, waterTop - depth)
        }
        var shaped = natural
        // Levees only form on ground near the water line; a channel through a deep pool gets none.
        if (levee && natural >= waterTop - 1 && edge <= LEVEE_WIDTH) {
            val crest = if (valueNoise2(wx * LEVEE_NOISE_FREQ, wz * LEVEE_NOISE_FREQ, RIVER_SALT + 6) > 0.1) 2 else 1
            val profile = if (edge <= LEVEE_CREST_EDGE) 1.0 else 1.0 - (edge - LEVEE_CREST_EDGE) / (LEVEE_WIDTH - LEVEE_CREST_EDGE)
            shaped = Math.max(shaped, waterTop + Math.round(crest * profile).toInt())
        }
        // A constant slope from a smooth channel line makes even steps parallel to the river.
        val jitter = valueNoise2(wx * BANK_JITTER_FREQ, wz * BANK_JITTER_FREQ, RIVER_SALT + 7) * BANK_JITTER_AMP
        val slopeNoise = 0.5 + 0.5 * valueNoise2(wx * BANK_SLOPE_FREQ, wz * BANK_SLOPE_FREQ, RIVER_SALT + 8)
        // High ground steepens the bank so it meets the surface inside the window; else the window edge is a wall.
        val needed = (natural - waterTop) / (RIVER_BANK_WINDOW - BANK_JITTER_AMP - 1.0)
        val slope = Math.max(BANK_SLOPE_MIN + slopeNoise * BANK_SLOPE_SPAN, needed)
        val bankCap = waterTop + Math.ceil(Math.max(0.0, edge + jitter) * slope).toInt()
        return Math.min(shaped, bankCap)
    }

    private class RiverScratch {
        val edge = FloatArray(256)
        val radius = FloatArray(256)
        val levee = BooleanArray(256)
    }

    private val riverScratch: ThreadLocal<RiverScratch> = ThreadLocal.withInitial { RiverScratch() }

    /** Built once; C2ME workers race harmlessly on the synchronized lazy init. See [HeartTunnelSkeleton]. */
    private val heartTunnel: HeartTunnelSkeleton.Skeleton by lazy { buildHeartTunnelSkeleton() }

    private fun buildHeartTunnelSkeleton(): HeartTunnelSkeleton.Skeleton {
        val seaLow = SEA_LEVEL_Y - HEART_Y_RANGE.toDouble()
        val seaHigh = SEA_LEVEL_Y + HEART_Y_RANGE.toDouble()

        // Per-world seed integer from the heart-tunnel angle, which carries the world seed.
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
            shape = HeartTunnelSkeleton.Shape(
                surfaceAt = { x, z -> effectiveSurfaceYAt(x, z) },
                moatInnerR = MOAT_INNER_R,
                minY = SEA_LEVEL_Y - HEART_TUNNEL_BELOW_SEA,
                maxY = SEA_LEVEL_Y + HEART_TUNNEL_ABOVE_SEA,
                seed = attractorSeed,
            ),
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
     * shell). Tip ends and grafts are segments of the skeleton too.
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

        val interior = heartInteriorScratch(span)
        // Every segment, tip end and graft: tapered capsule + bark noise, carve interior, line the shell.
        for (seg in skel.segments) {
            paintHeartTunnelSegment(
                target, chunkX0, chunkZ0, minY, span,
                seg, tunnelShell, interior,
            )
        }

        paintHeartDecor(target, chunkX0, chunkZ0, minY, span)

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
        seg: HeartTunnelSegment, shellThick: Double, interior: BooleanArray,
    ) {
        val p0x = seg.startX; val p0y = seg.startY; val p0z = seg.startZ
        val p1x = seg.endX; val p1y = seg.endY; val p1z = seg.endZ
        val r0 = seg.startRadius
        val r1 = seg.endRadius
        val rMax = Math.max(r0, r1)
        val barkAmpMax = barkAmplitudeFor(rMax)

        // World-space AABB inflated for radius + worst-case bark
        // excursion + shell band.
        val pad = (rMax + barkAmpMax + shellThick) * HEART_ELLIPSE + 1.0
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
                val dead = smoothstep01(
                    (Math.sqrt(wx.toDouble() * wx + wz.toDouble() * wz) - HEART_LIVE_RADIUS) /
                        (HEART_DEAD_RADIUS - HEART_LIVE_RADIUS),
                )
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
                    val distSq = ddx * ddx + ddy * ddy * HEART_INV_ELLIPSE_SQ + ddz * ddz

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
                    if (heartShaftBlock(wx, wy, wz) != null) continue
                    val ly = wy - minY
                    if (ly !in 0 until span) continue

                    // Rotting stretches silt up from the floor.
                    val silted = dead > 0.0 && ddy < -r * HEART_ELLIPSE * (1.0 - HEART_SILT_DEPTH * dead)
                    if (distSq <= rInner * rInner && !silted) {
                        col[ly] = if (wy < SEA_LEVEL_Y) WATER else AIR
                        interior[(lx * 16 + lz) * span + ly] = true
                    } else if (distSq <= rOuter * rOuter && col[ly] == null && wy <= surfaceHere && !silted) {
                        // Living root wood near the cave; farther out the wall rots away in patches.
                        val live = valueNoise3D(wx * HEART_LIVE_FREQ, wy * HEART_LIVE_FREQ, wz * HEART_LIVE_FREQ)
                        if (live >= dead * HEART_ROT_LINING_LOSS) col[ly] = WOGOR_WOOD_Y
                    }
                }
            }
        }
    }

    /**
     * Hanging roots under living root-wood ceilings, heartfire on rotting wood floors and ceilings.
     * Runs over cells the tunnel carved in this chunk only, so river water never gets decorated.
     */
    private fun paintHeartDecor(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int, span: Int,
    ) {
        val interior = heartInteriorScratch.get() ?: return
        for (lx in 0..15) {
            val wx = chunkX0 + lx
            for (lz in 0..15) {
                val wz = chunkZ0 + lz
                val col = target[lx][lz]
                val dead = smoothstep01(
                    (Math.sqrt(wx.toDouble() * wx + wz.toDouble() * wz) - HEART_LIVE_RADIUS) /
                        (HEART_DEAD_RADIUS - HEART_LIVE_RADIUS),
                )
                val rot = 1.0 - Math.abs(dead - 0.5) * 2.0
                val base = (lx * 16 + lz) * span
                for (ly in 1 until span - 1) {
                    if (!interior[base + ly]) continue
                    val cell = col[ly]
                    if (cell !== AIR && cell !== WATER) continue
                    val wet = cell === WATER
                    val roll = (hash32(wx, (minY + ly) * 31 + wz, HEART_DECOR_SALT) and 0xFFFF) / 65536.0
                    val fire = HEART_HEARTFIRE_CHANCE * rot * heartfirePatch(wx, minY + ly, wz)
                    if (col[ly + 1] === WOGOR_WOOD_Y) {
                        val hanging = HEART_HANGING_CHANCE * (1.0 - dead)
                        col[ly] = when {
                            roll < hanging -> if (wet) HANGING_ROOTS_WET else HANGING_ROOTS_DRY
                            roll < hanging + fire -> if (wet) HEARTFIRE_CEILING_WET else HEARTFIRE_CEILING_DRY
                            else -> cell
                        }
                    } else if (col[ly - 1] === WOGOR_WOOD_Y && roll < fire) {
                        col[ly] = if (wet) HEARTFIRE_FLOOR_WET else HEARTFIRE_FLOOR_DRY
                    } else if (roll < fire * 2.0) {
                        for (dir in HORIZONTAL_DIRECTIONS) {
                            val nx = lx + dir.stepX
                            val nz = lz + dir.stepZ
                            if (nx !in 0..15 || nz !in 0..15) continue
                            if (target[nx][nz][ly] !== WOGOR_WOOD_Y) continue
                            col[ly] = heartfireOn(dir, wet)
                            break
                        }
                    }
                }
            }
        }
    }

    /** One interior mask per worker thread, cleared for each chunk the tunnel touches. */
    private fun heartInteriorScratch(span: Int): BooleanArray {
        var mask = heartInteriorScratch.get()
        if (mask == null || mask.size != 256 * span) {
            mask = BooleanArray(256 * span)
            heartInteriorScratch.set(mask)
        } else {
            java.util.Arrays.fill(mask, false)
        }
        return mask
    }

    private val heartInteriorScratch = ThreadLocal<BooleanArray?>()

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
                    // The shaft wins over the cave in [fillFromNoise]; the flush must not undo it.
                    if (heartShaftBlock(wx, wy, wz) != null) continue
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
            // inside a tunnel segment. The adjacent
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
     * Is voxel centre `(vx, vy, vz)` inside any heart-tunnel segment? Lets [isHeartWallAt] classify
     * cross-chunk neighbours. Same capsule SDF as the painter minus the bark noise; one cell of slop
     * at a chunk boundary is invisible.
     */
    private fun isVoxelInsideHeartTunnel(vx: Double, vy: Double, vz: Double): Boolean {
        val skel = heartTunnel
        for (seg in skel.segments) {
            if (isVoxelInsideCapsule(
                vx, vy, vz,
                seg.startX, seg.startY, seg.startZ,
                seg.endX, seg.endY, seg.endZ,
                seg.startRadius, seg.endRadius,
            )) return true
        }
        return false
    }

    /** Tapered-capsule interior test, with the painter's projection, radius lerp and vertical stretch. */
    private fun isVoxelInsideCapsule(
        vx: Double, vy: Double, vz: Double,
        x0: Double, y0: Double, z0: Double,
        x1: Double, y1: Double, z1: Double,
        r0: Double, r1: Double,
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
        val distSq = ddx * ddx + ddy * ddy * HEART_INV_ELLIPSE_SQ + ddz * ddz
        val r = r0 + (r1 - r0) * t
        return distSq <= r * r
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
                // The hash gate runs first: it rejects 75 % of the columns before the scan.
                // (An empty-cell test above the surface was here; it also rejected each
                // root arc with air below it, which is the case this function is for.)
                val h = hash32(wx, wz, 0x73A1F4C7.toInt())
                if ((h and 0x3) != 0) continue
                val surface = surfaceYAt(wx, wz)
                val surfaceLY = surface - minY
                val col = target[lx][lz]
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
                hangRootVines(target, chunkX0, chunkZ0, minY, lx, lz, topLY, surfaceLY, h)
            }
        }
        // Columns of the next chunks, from the ring that the root painter recorded.
        val ring = rootRingScratch.get()
        for (lx in -1..16) {
            for (lz in -1..16) {
                if (lx in 0..15 && lz in 0..15) continue
                val topLY = ring[ringIndex(lx, lz)]
                if (topLY < 0) continue
                val wx = chunkX0 + lx
                val wz = chunkZ0 + lz
                val h = hash32(wx, wz, 0x73A1F4C7.toInt())
                if ((h and 0x3) != 0) continue
                hangRootVines(target, chunkX0, chunkZ0, minY, lx, lz, topLY, surfaceYAt(wx, wz) - minY, h)
            }
        }
    }

    /** One vine curtain from the root column ([lx], [lz]), which can be in the ring. Only the
     *  part in this chunk is written; the chunk on the other side writes its own part. */
    private fun hangRootVines(
        target: Array<Array<Array<BlockState?>>>,
        chunkX0: Int, chunkZ0: Int, minY: Int,
        lx: Int, lz: Int, topLY: Int, surfaceLY: Int, h: Int,
    ) {
        // Only hang vines from clearly elevated airborne sections.
        if (topLY - surfaceLY < 5) return

        val len = 2 + ((h ushr 2) and 0x7)  // 2..9 blocks
        // Each face index maps to (adjDx, adjDz, vineBlockIdx whose face property points
        // *back* at this column).
        val faceIdx = (h ushr 5) and 0x3
        val adjDx = when (faceIdx) { 2 -> -1; 3 -> 1; else -> 0 }
        val adjDz = when (faceIdx) { 0 -> 1; 1 -> -1; else -> 0 }
        val adjLx = lx + adjDx
        val adjLz = lz + adjDz
        if (adjLx !in 0..15 || adjLz !in 0..15) return
        val adjCol = target[adjLx][adjLz]
        val vineState = VINE_BLOCKS[faceIdx]
        // An empty cache cell is base terrain: mud up to the surface, then water.
        val adjSurface = effectiveSurfaceYAt(chunkX0 + adjLx, chunkZ0 + adjLz)
        val adjFloor = if (adjSurface >= SEA_LEVEL_Y) adjSurface else SEA_LEVEL_Y
        for (v in 0 until len) {
            val vy = topLY - v
            if (vy < 0) break
            if (vy + minY <= adjFloor) break
            // Don't replace cache wood with vine.
            if (adjCol[vy] != null) break
            adjCol[vy] = vineState
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
    ): Int {
        // Same surface as the column fill: mound and moat included, water counted as surface
        // for each heightmap type that is not an ocean floor.
        val ground = effectiveSurfaceYAt(x, z) + 1
        val floorType = type == Heightmap.Types.OCEAN_FLOOR || type == Heightmap.Types.OCEAN_FLOOR_WG
        return if (floorType) ground else Math.max(ground, SEA_LEVEL_Y)
    }

    override fun getBaseColumn(
        x: Int, z: Int, level: LevelHeightAccessor, randomState: RandomState,
    ): NoiseColumn {
        val surface = effectiveSurfaceYAt(x, z)
        val states: Array<BlockState> = Array(WORLD_HEIGHT) { i ->
            baseColumnBlock(MIN_Y + i, surface)
        }
        return NoiseColumn(MIN_Y, states)
    }

    override fun addDebugScreenInfo(
        info: MutableList<String>, randomState: RandomState, pos: BlockPos,
    ) = Unit

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

        // The trunk grows bias-only until an attractor is within attractionDist; the side clouds pull
        // limbs off it below the crown at y≈222, so it does not rise as one bare pole.
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
        val parts = listOf(mainCanopy, branch1, branch2) + motherScaffoldAttractors(baseX, baseY, baseZ)
        val attractors = DoubleArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { System.arraycopy(p, 0, attractors, at, p.size); at += p.size }

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
            taper = true,
        )
    }

    /** One small attractor cloud per scaffold limb, so big limbs leave the trunk below the crown. */
    private fun motherScaffoldAttractors(baseX: Double, baseY: Double, baseZ: Double): List<DoubleArray> {
        var angle = hash01(MOTHER_TREE_SEED, 0, 3000) * 2.0 * Math.PI
        return (0 until MOTHER_LIMB_COUNT).map { i ->
            angle += 2.0 * Math.PI / MOTHER_LIMB_COUNT * (0.6 + hash01(MOTHER_TREE_SEED, i, 3001) * 0.8)
            val y = baseY + MOTHER_LIMB_LOW + i * MOTHER_LIMB_STEP + hash01(MOTHER_TREE_SEED, i, 3002) * MOTHER_LIMB_JITTER
            val dist = MOTHER_LIMB_DIST_MIN + hash01(MOTHER_TREE_SEED, i, 3003) * MOTHER_LIMB_DIST_SPREAD
            WogorTreeSkeleton.ellipsoidAttractors(
                cx = baseX + Math.cos(angle) * dist, cy = y, cz = baseZ + Math.sin(angle) * dist,
                rx = MOTHER_LIMB_RXZ, ry = MOTHER_LIMB_RY, rz = MOTHER_LIMB_RXZ,
                count = MOTHER_LIMB_ATTRACTORS, seed = MOTHER_TREE_SEED + 200 + i,
            )
        }
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
        paintVineEdge(target, chunkX0, chunkZ0, minY, span,
            MOTHER_TREE_CX.toDouble(), MOTHER_TREE_CZ.toDouble(), MOTHER_VINE_EDGE_RIM.toDouble(), canopy.maxXZReach + 2,
            MOTHER_VINE_EDGE_MIN, MOTHER_VINE_EDGE_MAX, MOTHER_TREE_SEED xor 0x5A17E5)
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

            val reach = 80 + (hash32(MOTHER_TREE_SEED, i, 52) ushr 1) % 50
            val plungeDepth = 40 + (hash32(MOTHER_TREE_SEED, i, 53) ushr 1) % 30
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
        private const val RING_SIDE = 18
        // ---- world extent ----
        /** World floor. Dropped 32 below the old y0 purely to give the
         *  root cave carver two more coarse cell-layers of depth —
         *  [WORLD_HEIGHT] grows by the same 32 so [MAX_BUILD_Y] stays
         *  319 and the sky, the Mother Tree and every other feature keyed
         *  off the build ceiling are untouched. */
        const val MIN_Y = -32
        const val WORLD_HEIGHT = 352
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
        private const val FINE_SALT = 9101
        private const val WARP_SALT = 9103
        private const val REGIONAL_WARP_FREQ = 0.008
        private const val REGIONAL_WARP_AMP = 24.0

        // ---- depth profile (see shapeWaterDepth) ----
        // Real swamps: >50 % of the floor above the water table, hollows 1–2 m deep.
        // Raw heights at or under DEPTH_KEEP_Y keep their depth (the deepest pools).
        private const val DEPTH_KEEP_Y = 52.0
        private const val DEPTH_SHELF_Y = 55.0
        private const val DEPTH_SHALLOW_END_Y = 60.5
        private const val DEPTH_TOP_ANCHOR_Y = 74.0
        private const val SHALLOW_FLOOR_Y = 61.0
        private const val DRY_FLOOR_Y = 63.0

        // ---- relief: rolling hills and terraced bluffs on dry ground ----
        private const val RELIEF_SALT = 9301
        /** Lift fades in over ground this high above raw and fully applies [RELIEF_DRY_SPAN] higher. */
        private const val RELIEF_DRY_FROM = 62.5
        private const val RELIEF_DRY_SPAN = 2.5
        /** Mound and moat stay as authored; relief fades in past this radius. */
        private const val RELIEF_CENTRE_CLEAR = 220.0
        private const val RELIEF_CENTRE_FADE = 120.0
        /** ~250-block areas of each terrain character. */
        private const val RELIEF_MASK_FREQ = 0.004
        private const val HILL_MASK_LOW = -0.25
        private const val HILL_MASK_SPAN = 0.45
        private const val CLIFF_MASK_LOW = 0.2
        private const val CLIFF_MASK_SPAN = 0.25
        private const val ROLLING_FREQ = 0.02
        private const val ROLLING_FREQ_FINE = 0.05
        private const val ROLLING_AMP = 20.0
        private const val BLUFF_FREQ = 0.012
        private const val BLUFF_WARP_FREQ = 0.03
        private const val BLUFF_WARP_AMP = 10.0
        private const val BLUFF_TERRACES = 3.0
        private const val BLUFF_STEP_HEIGHT = 8.0
        /** Share of each terrace span taken by its riser; smaller is steeper. */
        private const val BLUFF_RISER_SHARE = 0.12

        // ---- hummocks: 1–2 block mounds, +1, on a 3-block jittered grid near the water line ----
        private const val HUMMOCK_CELL = 3
        /** Out of 256: share of grid cells that hold a hummock. */
        private const val HUMMOCK_CELL_FILL = 170
        private const val HUMMOCK_SALT = 9105
        private const val HUMMOCK_MIN_Y = SEA_LEVEL_Y - 2
        private const val HUMMOCK_MAX_Y = SEA_LEVEL_Y
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
        private const val CAVE_DOME_DROP = 9.0
        private const val CAVE_BOWL_RISE = 2.0
        /** Root-plate wood over the cave ceiling. */
        private const val CAVE_ROOF_THICKNESS = 2
        private const val CAVE_DECOR_SALT = 0xCA7E
        private const val CAVE_HANGING_CHANCE = 0.18
        private const val CAVE_HEARTFIRE_CHANCE = 0.02

        /** Under the heart at the pool surface, where a player can reach it; it wells up from the shaft below. */
        private const val FOUNTAIN_Y = SEA_LEVEL_Y - 1
        private const val ANCHOR_SALT = 0xA4C4
        private const val ANCHOR_MIN_COUNT = 4
        private const val ANCHOR_EXTRA_COUNT = 2
        private const val ANCHOR_MIN_DIST = 7.0
        private const val ANCHOR_DIST_SPREAD = 3.0
        private const val ANCHOR_MIN_RADIUS = 1.6
        private const val ANCHOR_RADIUS_SPREAD = 0.8
        private const val ANCHOR_LEAN = 0.25
        private const val ANCHOR_TAPER = 0.4
        /** Anchors reach into the root network's upper runners. */
        private const val ANCHOR_BOTTOM_Y = 24
        /** Radians kept clear on each side of the tunnel mouth. */
        private const val ANCHOR_MOUTH_CLEARANCE = 0.6
        /** Furthest an anchor reaches: start + full lean + radius. */
        private const val ANCHOR_REACH_SQ = 28L * 28L

        // ---- Rivers radiating from the moat ----
        private const val RIVER_COUNT = 8
        private const val RIVER_SALT = 9201
        /** Max deviation (radians) of each river's start angle from an even split. */
        private const val RIVER_ANGLE_JITTER = 0.3
        private const val RIVER_MIN_STEPS = 320
        private const val RIVER_EXTRA_STEPS = 200
        /** Short steps keep the painted points overlapping through tight meander bends. */
        private const val RIVER_STEP_LEN = 0.75
        private const val RIVER_MIN_RADIUS = 2.2
        private const val RIVER_RADIUS_SPREAD = 1.3
        private const val RIVER_MIN_PAINT_RADIUS = 1.0
        private const val RIVER_WIDTH_VARIATION = 0.25
        private const val RIVER_WIDTH_NOISE_FREQ = 0.02
        /** Last share of a path over which the channel narrows to 40 % width. */
        private const val RIVER_TAIL_FRACTION = 0.25
        /** Alluvial meander wavelength ≈ 12 × width ≈ 24 × radius. */
        private const val MEANDER_WAVELENGTH_PER_RADIUS = 24.0
        private const val MEANDER_AMPLITUDE_RATIO = 0.22
        private const val RIVER_YAW_TURN = 0.12
        private const val RIVER_RADIAL_BIAS = 0.05
        private const val RIVER_DEPTH_BASE = 1.0f
        private const val RIVER_DEPTH_PER_RADIUS = 1.2f
        /** Bed never below y59: the flooded root caves are lined up to y55. */
        private const val RIVER_MAX_DEPTH = 4.0f
        /** Horizontal reach past the channel edge that banks and levees can reshape. */
        private const val RIVER_BANK_WINDOW = 14f
        private const val BANK_SLOPE_MIN = 0.5
        private const val BANK_SLOPE_SPAN = 1.3
        private const val BANK_SLOPE_FREQ = 0.03
        private const val BANK_JITTER_FREQ = 0.18
        private const val BANK_JITTER_AMP = 1.5
        private const val LEVEE_WIDTH = 6f
        private const val LEVEE_CREST_EDGE = 2f
        private const val LEVEE_NOISE_FREQ = 0.05

        private const val RIVER_MAX_TRIBUTARIES = 3
        private const val TRIBUTARY_START_MIN = 0.25
        private const val TRIBUTARY_START_SPAN = 0.45
        private const val TRIBUTARY_ANGLE_MIN = 0.7
        private const val TRIBUTARY_ANGLE_SPAN = 0.5
        private const val TRIBUTARY_MIN_STEPS = 120
        private const val TRIBUTARY_EXTRA_STEPS = 120
        private const val TRIBUTARY_MIN_RADIUS = 1.0
        private const val TRIBUTARY_RADIUS_SPREAD = 0.6

        private const val RIVER_MAX_OXBOWS = 2
        private const val OXBOW_START_MIN = 0.2
        private const val OXBOW_START_SPAN = 0.6
        private const val OXBOW_ARC_RADIUS_RATIO = 0.18
        private const val OXBOW_ARC_SPAN = 2.4
        private const val OXBOW_RADIUS = 2.0
        private const val OXBOW_CLEARANCE = 4.0
        private const val OXBOW_POINTS = 40

        private fun unit(h: Int): Double = (h and 0xFFFF) / 65536.0

        // ---- Heart tunnel skeleton (cave → moat) ----
        // Space-colonisation algorithm (Runions et al. 2007) — same
        // machinery as the Mother Tree's [WogorTreeSkeleton], tuned
        // for an underground tunnel network constrained to a band
        // around sea level. The trunk grows from the heart at world
        // origin toward an attractor cloud densely packed around
        // the moat ring; branches emerge naturally where intermediate
        // attractors pull tips sideways.
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

        /** Smallest walkable radius at the tips; the trunk is capped at [HEART_R_MAX]. */
        private const val HEART_R_MIN = 1.6
        private const val HEART_R_MAX = 6.5
        /** Scales the area-preserving `√(descendantLeaves)` radius. */
        private const val HEART_THICKNESS_SCALE = 1.4
        /** Tunnels near the moat sit low enough to break through its bank as outlets. */
        private const val HEART_TUNNEL_BELOW_SEA = 4.0
        private const val HEART_TUNNEL_ABOVE_SEA = 14.0
        private const val HEART_TUNNEL_SHELL_THICK = 1
        /** Sections run taller than wide, like peat-pipe outlets. */
        private const val HEART_ELLIPSE = 1.35
        private const val HEART_INV_ELLIPSE_SQ = 1.0 / (HEART_ELLIPSE * HEART_ELLIPSE)
        /** Root wood is alive near the cave and rots toward the moat. */
        private const val HEART_LIVE_RADIUS = 40.0
        private const val HEART_DEAD_RADIUS = 160.0
        private const val HEART_LIVE_FREQ = 0.08
        /** At full rot this share of the wall is gone, and the floor silts this far up the section. */
        private const val HEART_ROT_LINING_LOSS = 0.7
        private const val HEART_SILT_DEPTH = 0.45
        private const val HEART_DECOR_SALT = 0x4EA7
        private const val HEART_HANGING_CHANCE = 0.2
        private const val HEART_HEARTFIRE_CHANCE = 0.1

        // ---- Vines ----
        /** Per-cell density threshold (0..255). Lower → sparser
         *  curtain. 80 → ~31 % of qualifying ceiling cells get a
         *  vine column, which reads as a generous but not solid
         *  drape. */
        private const val HEART_VINE_DENSITY_THRESHOLD = 80

        /** Reject chunks past this squared distance from origin.
         *  SCA grows to wherever attractors live; the moat ring
         *  attractors cap the network at `MOAT_OUTER_R`. Worst
         *  case: trunk reaches a moat-ring attractor at
         *  `MOAT_OUTER_R + HEART_KILL_DIST` (a node within kill
         *  distance of an outer-ring attractor), plus the tunnel's
         *  own [HEART_R_MAX] radius (stretched by [HEART_ELLIPSE]),
         *  plus an outlet reaching past the tip. Padded an extra 16. */
        private const val HEART_TUNNEL_REACH_PLUS_PAD_SQ: Long =
            ((MOAT_OUTER_R + HEART_KILL_DIST + HeartTunnelSkeleton.OUTLET_LENGTH +
                HEART_R_MAX * HEART_ELLIPSE).toLong() + 16) *
            ((MOAT_OUTER_R + HEART_KILL_DIST + HeartTunnelSkeleton.OUTLET_LENGTH +
                HEART_R_MAX * HEART_ELLIPSE).toLong() + 16)

        // ---- Child Tree dispersal ----
        /** Region grid in chunks. Each `N × N` region of chunks may host
         *  at most one Child Tree. 6 → one tree per 96×96-block cell on
         *  average. */
        const val CHILD_TREE_REGION_SIZE_CHUNKS = 6
        const val CHILD_TREE_REGION_SIZE = CHILD_TREE_REGION_SIZE_CHUNKS * 16

        // The caches grew with the explored area. A full cache is emptied; entries build again.
        private const val CHILD_TREE_SKELETON_CACHE_CAP = 64
        private const val CHILD_TREE_SITE_CACHE_CAP = 4096
        private const val TUNNEL_PATH_CACHE_CAP = 1024

        /** Height band of the trunk base for [isInsideChildTreeCore]. */
        private const val CHILD_TREE_CORE_BELOW = 8
        private const val CHILD_TREE_CORE_ABOVE = 24

        // Before NO_CHILD_TREE: companion fields initialise in order, and its defaults read this.
        private val EMPTY_NODES = DoubleArray(0)
        private val NO_CHILD_TREE = ChildTreeSite(0, 0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0)
        private const val AGE_YOUNG = 0
        private const val AGE_MATURE = 1
        private const val AGE_OLD = 2
        private const val AGE_DEAD = 3
        /** Worst-case XZ extent of a Child Tree (canopy reach + buttress
         *  + root walk) for the per-chunk AABB reject. */
        const val CHILD_TREE_MAX_REACH = 80
        /** Minimum centre-to-centre distance between two Child Trees in
         *  adjacent regions. Closer-than-this candidates are rejected via
         *  the lower-seed-wins rule, so child trees stay visually
         *  separated even when adjacent regions both spawn one. */
        const val CHILD_TREE_MIN_SPACING = 60
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

        // ---- surface roots: geometry lives in WohlonnogondoniaSurfaceRoots, shared with the Overworld grower ----
        private const val TUNNEL_REGION_SIZE_CHUNKS = WohlonnogondoniaSurfaceRoots.TUNNEL_REGION_SIZE_CHUNKS
        private const val TUNNEL_REGION_SEARCH = WohlonnogondoniaSurfaceRoots.TUNNEL_REGION_SEARCH

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
        private val MUD: BlockState = EKBlocks.WOHLON_MUD.get().defaultBlockState()
        private val HANGING_ROOTS_WET: BlockState = EKBlocks.WOHLON_HANGING_ROOTS.get().defaultBlockState()
            .setValue(BlockStateProperties.WATERLOGGED, true)
        private val HANGING_ROOTS_DRY: BlockState = EKBlocks.WOHLON_HANGING_ROOTS.get().defaultBlockState()
        private val HEARTFIRE_CEILING_WET: BlockState = EKBlocks.HEARTFIRE.get().defaultBlockState()
            .setValue(BlockStateProperties.UP, true).setValue(BlockStateProperties.WATERLOGGED, true)
        private val HEARTFIRE_FLOOR_WET: BlockState = EKBlocks.HEARTFIRE.get().defaultBlockState()
            .setValue(BlockStateProperties.DOWN, true).setValue(BlockStateProperties.WATERLOGGED, true)
        private val HEARTFIRE_CEILING_DRY: BlockState = EKBlocks.HEARTFIRE.get().defaultBlockState()
            .setValue(BlockStateProperties.UP, true)
        private val HEARTFIRE_FLOOR_DRY: BlockState = EKBlocks.HEARTFIRE.get().defaultBlockState()
            .setValue(BlockStateProperties.DOWN, true)
        private const val ROOT_DECOR_SALT = 0x5EED_0F
        private const val ROOT_CELLS_PER_CHUNK = 64

        // ---- child heart chambers ----
        private val CHILD_HEART: BlockState = EKBlocks.HEART_OF_THE_WILD.get().defaultBlockState()
        /** Chamber centre this far under the trunk base, so the roof sits just below the ground. */
        private const val CHILD_CHAMBER_DEPTH = 6
        private const val CHILD_CHAMBER_R_MIN = 4.5
        private const val CHILD_CHAMBER_R_SPREAD = 1.5
        private const val CHILD_CHAMBER_WOBBLE = 0.8
        /** Past the radius: wobble plus a block of margin for the footprint tests. */
        private const val CHILD_CHAMBER_SLACK = 2.0
        private const val CHILD_CHAMBER_RISE = 3
        private const val CHILD_CHAMBER_DOME = 2.5
        private const val CHILD_CHAMBER_FLOOR_DROP = 2
        private const val CHILD_CHAMBER_SALT = 0xC41D
        private const val CHILD_SHAFT_OFFSET = 2.5
        private const val CHILD_SHAFT_RADIUS = 1.8
        private const val CHILD_SHAFT_TAPER = 0.25
        private const val CHILD_SHAFT_LEAN = 0.3
        private const val CHILD_SHAFT_WOBBLE = 1.8
        private const val CHILD_SHAFT_WOBBLE_FREQ = 0.12
        private const val CHILD_SHAFT_DROP = 12
        /** The shaft's foot is a root-network junction, so it must sit inside the network band. */
        private const val CHILD_SHAFT_BOTTOM_MIN = 10
        private const val CHILD_SHAFT_BOTTOM_MAX = 44
        private const val CHILD_NODE_RADIUS = 3.0
        private const val CHILD_ANCHOR_MIN = 2
        private const val CHILD_ANCHOR_EXTRA = 2
        private const val CHILD_ANCHOR_RADIUS_MIN = 1.1
        private const val CHILD_ANCHOR_RADIUS_SPREAD = 0.5
        private const val CHILD_ANCHOR_LEAN = 0.2
        /** Anchors reach this far below the shaft foot, into the root network. */
        private const val CHILD_ANCHOR_BELOW_SHAFT = 4
        /** Radians kept clear on each side of the shaft. */
        private const val CHILD_ANCHOR_SHAFT_CLEARANCE = 0.7
        private const val ANCHOR_START_SHARE = 0.6
        private const val CHILD_ENTRANCE_RADIUS = 1.4
        /** The crawlway surfaces this far past the buttress flare. */
        private const val CHILD_ENTRANCE_BEYOND_CORE = 4
        private const val CHILD_ENTRANCE_POINTS = 10
        private const val CHILD_ENTRANCE_SWING = 4.0
        private const val CHILD_ENTRANCE_SAG = 2.5

        // ---- child tree placement and growth ----
        private const val GROVE_SALT = 9401
        /** Grove patches span a few regions. */
        private const val GROVE_FREQ = 0.25
        /** Ground this far above the water reads as high and dry: fewer, smaller trees. */
        private const val CHILD_HIGH_GROUND_SPAN = 14.0
        private val WATER_PROBE_DISTANCES = intArrayOf(12, 24)
        private const val AGE_YOUNG_SHARE = 0.25
        private const val AGE_MATURE_SHARE = 0.54
        private const val AGE_DEAD_SHARE = 0.06
        private const val YOUNG_SCALE = 0.55
        private const val DEAD_CROWN_KEEP = 0.6
        private const val CROWD_RANGE = 160.0
        private const val LIGHT_PROBE_DISTANCE = 30
        private const val WATER_LIGHT_PULL = 0.5
        /** Crown shift at full lean, as a share of the canopy radius. */
        private const val CROWN_LEAN_SHARE = 0.25
        private val ROOT_WATER_PROBES = intArrayOf(10, 20, 30, 40)
        private const val ROOT_WATER_OVERSHOOT = 8
        private const val ROOT_MAX_REACH = 55
        private const val ROOT_CLIMB_PROBE = 25
        private const val ROOT_CLIMB_LIMIT = 5
        private const val ROOT_CLIMB_SHRINK = 0.7
        private const val HOLLOW_CORE_SHARE = 0.45
        private const val HOLLOW_MIN_HEIGHT = 6
        private const val HOLLOW_EXTRA_HEIGHT = 4
        private const val HOLLOW_OPENING_HALF_WIDTH = 1.6
        private const val HOLLOW_OPENING_HEIGHT = 3.0
        private const val HOLLOW_OPENING_EXTRA = 2.0
        private const val HOLLOW_DRIFT = 0.7
        private const val HOLLOW_DRIFT_FREQ = 0.25
        private const val HOLLOW_ROUGHNESS = 0.3
        private const val HOLLOW_ROUGH_FREQ = 0.45
        private const val CHILD_KNEES_MIN = 14
        private const val CHILD_KNEES_EXTRA = 16
        private const val CHILD_KNEE_GAP = 2.0
        private const val CHILD_KNEE_WIDE_SHARE = 0.3
        private const val CHILD_KNEE_REACH = 12.0
        private const val CHILD_KNEE_WET_SPREAD = 1.3
        private const val CHILD_KNEE_RISE = 1.6
        private const val CHILD_KNEE_RADIUS = 1.3
        private const val CHILD_KNEE_WIDE_RADIUS = 1.8
        private const val CHILD_KNEE_TIP_RADIUS = 0.35
        private const val CHILD_KNEE_LEAN = 0.6
        private const val YOUNG_THICKNESS_SHARE = 0.6
        private const val YOUNG_FLARE_SHARE = 0.8
        private const val YOUNG_CROWN_SHARE = 0.55
        private const val YOUNG_RY_RATIO = 0.9
        private const val YOUNG_MIN_ATTRACTORS = 150
        private const val FIN_BEYOND_CORE = 5.0
        private const val FIN_STEP = 0.5
        private const val FIN_HEIGHT_SHARE = 0.55
        private const val FIN_BASE_RADIUS = 1.0
        private const val FIN_TOP_RADIUS = 0.8
        private const val BALD_LIMB_DROP = 0.25
        private const val BALD_LIMB_MIN_RADIUS = 1.2
        private const val BALD_MIN_HEIGHT_SHARE = 0.5
        private const val BALD_PATCH_FREQ = 0.09
        private const val BALD_PATCH_CUT = 0.5
        private const val BALD_PATCH_SALT = 311.7
        private const val ROOT_TURN_SHARE = 0.45
        private const val ROOT_TURN_STEPS = 4
        private const val ROOT_TURN_PROBE_START = 6
        private const val ROOT_TURN_PROBE_STEP = 2
        private const val TWIG_RADIUS = 1.0
        private const val VINE_EDGE_CHANCE = 0.22
        private const val VINE_EDGE_LEAF_CHANCE = 0.03
        private const val CHILD_VINE_EDGE_RIM_SHARE = 0.55
        private const val CHILD_VINE_EDGE_MIN = 3
        private const val CHILD_VINE_EDGE_MAX = 12
        private const val MOTHER_VINE_EDGE_RIM = 90
        private const val MOTHER_VINE_EDGE_MIN = 6
        private const val MOTHER_VINE_EDGE_MAX = 22
        private const val MOTHER_LIMB_COUNT = 6
        private const val MOTHER_LIMB_LOW = 35.0
        private const val MOTHER_LIMB_STEP = 19.0
        private const val MOTHER_LIMB_JITTER = 10.0
        private const val MOTHER_LIMB_DIST_MIN = 35.0
        private const val MOTHER_LIMB_DIST_SPREAD = 30.0
        private const val MOTHER_LIMB_RXZ = 20.0
        private const val MOTHER_LIMB_RY = 13.0
        private const val MOTHER_LIMB_ATTRACTORS = 220
        private const val WEYYE_SALT = 0x3E77E
        private const val WEYYE_MIN_HEIGHT = 2
        private const val WEYYE_MAX_HEIGHT = 12
        private const val WEYYE_CHANCE = 0.03
        private const val WEYYE_CLUSTER_FREQ = 0.4
        private const val WEYYE_RIPE_SHARE = 0.2
        private const val ROOT_CELL_CACHE_CAP = 4096
        /** Hanging roots on any ceiling, plus extra under the living roots near the tree. */
        private const val ROOT_HANGING_BASE = 0.08
        private const val ROOT_HANGING_NEAR = 0.2
        /** Heartfire on any surface, plus extra where the root wood is rotting. */
        private const val ROOT_HEARTFIRE_BASE = 0.03
        private const val ROOT_HEARTFIRE_ROTTING = 0.07
        /** Patch field for heartfire: ~20 % of tunnel space lies in a patch. */
        private const val HEARTFIRE_PATCH_FREQ = 0.07
        private const val HEARTFIRE_PATCH_LOW = 0.3
        private const val HEARTFIRE_PATCH_SPAN = 0.3
        /** Walls are rolled per tunnel cell, so they take a larger share than floors and ceilings. */
        private const val ROOT_HEARTFIRE_WALL_SHARE = 1.0
        private val HORIZONTAL_DIRECTIONS = arrayOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)
        private val HEARTFIRE_BY_FACE: Array<Array<BlockState>> = Array(2) { wet ->
            Array(Direction.values().size) { d ->
                EKBlocks.HEARTFIRE.get().defaultBlockState()
                    .setValue(net.minecraft.world.level.block.MultifaceBlock.getFaceProperty(Direction.values()[d]), true)
                    .setValue(BlockStateProperties.WATERLOGGED, wet == 1)
            }
        }

        /** Heartfire attached to the wall on side [dir] of its cell. */
        private fun heartfireOn(dir: Direction, wet: Boolean): BlockState = HEARTFIRE_BY_FACE[if (wet) 1 else 0][dir.ordinal]
        // ---- guardian spike clusters ----
        /** ~4 % of the field lies past the threshold, in blobs about 8 blocks across. */
        private const val SPIKE_CLUSTER_FREQ = 0.05
        private const val SPIKE_CLUSTER_LOW = 0.65
        private const val SPIKE_CLUSTER_SPAN = 0.15
        private const val SPIKE_CLUSTER_SALT = 211
        /** Share of fitting cells at a cluster's core; it thins to 0 at the edge. */
        private const val SPIKE_DENSITY = 0.55
        private const val BAND_SOLID = 1
        private const val BAND_OPEN = 0
        private const val BAND_UNKNOWN = -1
        private val SPIKE_ATTACHMENTS = SpikeAttachment.values()
        /** Chance each other fitting spike around the chosen corner joins a face or crease spike's cell. */
        private const val SPIKE_EXTRA_CHANCE = 0.5
        /** Past this deadness a channel is a silted dead root rather than rotting wood. */
        private const val SILT_DEADNESS = 0.8
        // ---- heart-cave -> root-cave descent ----
        /** Bottom of the shaft. Reaches well past the root network's y54
         *  ceiling — the coarse lattice's vertical cell is 16 blocks, so
         *  an 18-block overlap is guaranteed to cross a runner rather
         *  than bottoming out in solid mud. */
        private const val SHAFT_BOTTOM_Y = 36
        /** Top of the shaft: a few blocks into the heart cave's floor
         *  (base y60) so it always breaks through the noise-perturbed
         *  floor surface. */
        private const val SHAFT_TOP_Y = CAVE_FLOOR_BASE_Y + 3
        private const val SHAFT_RADIUS = 2.8
        private const val SHAFT_WOBBLE_FREQ = 0.05
        private const val SHAFT_WOBBLE_AMP = 2.5
        /** Mouth flare, so the shaft opens into the cave floor. */
        private const val SHAFT_FLARE = 2.5
        private const val SHAFT_FLARE_SPAN = 6
        /** Square pre-reject bound: radius + flare + wobble + shell. */
        /** Covers lean + wobble + radius + flare + lining. */
        private const val SHAFT_REJECT = 15
        private const val SHAFT_LEAN = 0.15
        /** The shaft narrows by this share from top to bottom. */
        private const val SHAFT_TAPER = 0.25

        /** Lining of every root-cave tunnel — see [WohlonRootField]. */
        private val ROOT_BLOCK: BlockState = EKBlocks.WOHLON_ROOT.get().defaultBlockState()
        private val WATER: BlockState = Blocks.WATER.defaultBlockState()
        /** Wogor leaves block state for the Mother Tree canopy.
         *  Lazy because EKBlocks registration runs at mod-init —
         *  same constraint as [WOGOR_LOG_Y] below. */
        private val WOGOR_LEAVES: BlockState by lazy {
            EKBlocks.WOGOR_LEAVES.get().defaultBlockState()
                .setValue(BlockStateProperties.PERSISTENT, true)
        }
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

        /** The Mother Tree's Heart of the Wild — placed at
         *  `(0, HEART_Y, 0)`, which is also the dim's heart-return
         *  portal position (see
         *  [org.shipwrights.enderkinesis.block.WohlonnogondoniaPortalManager]).
         *  The BER renders it at 2× scale; the
         *  [org.shipwrights.enderkinesis.block.HeartOfTheWildBlock]
         *  destroy guards + catastrophe trigger arm. Lazy for the
         *  same reason as the rest of these EK block constants. */
        private val FOUNTAIN: BlockState by lazy { EKBlocks.FOUNTAIN_OF_BLESSING.get().defaultBlockState() }

        private val MOTHER_HEART: BlockState by lazy {
            EKBlocks.HEART_OF_THE_WILD.get().defaultBlockState()
                .setValue(org.shipwrights.enderkinesis.block.HeartOfTheWildBlock.MOTHER, true)
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
            var h = a * 0x9E3779B1.toInt() xor (b * 0x85EBCA77.toInt()) xor (salt * 0xC2B2AE3D.toInt()) xor
                WohlonWorldSeed.salt
            h = (h xor (h ushr 15)) * 0x2C1B3C6D.toInt()
            h = (h xor (h ushr 12)) * 0x297A2D39.toInt()
            h = h xor (h ushr 15)
            return h
        }

        /** Base + regional terms of [surfaceYAt], without the fine and hill terms. */
        private fun coarseSurfaceY(wx: Int, wz: Int): Double {
            // Domain warp bends the regional lattice so pools and bars stop lining up with the axes.
            val warpX = valueNoise2(wx * REGIONAL_WARP_FREQ, wz * REGIONAL_WARP_FREQ, WARP_SALT) * REGIONAL_WARP_AMP
            val warpZ = valueNoise2(wx * REGIONAL_WARP_FREQ + 31.7, wz * REGIONAL_WARP_FREQ + 17.3, WARP_SALT) *
                REGIONAL_WARP_AMP
            return SURFACE_BASE_Y + valueNoise2((wx + warpX) * 0.015, (wz + warpZ) * 0.015) * REGIONAL_AMPLITUDE
        }

        /** [-1, 1] valley noise via bilinear interpolation of per-lattice hashes. */
        private fun valueNoise2(x: Double, z: Double, salt: Int = 1): Double {
            val ix = Math.floor(x).toInt()
            val iz = Math.floor(z).toInt()
            val fx = x - ix
            val fz = z - iz
            val sx = fx * fx * (3.0 - 2.0 * fx)
            val sz = fz * fz * (3.0 - 2.0 * fz)
            val a = (hash32(ix, iz, salt) and 0xFFFF) / 32768.0 - 1.0
            val b = (hash32(ix + 1, iz, salt) and 0xFFFF) / 32768.0 - 1.0
            val c = (hash32(ix, iz + 1, salt) and 0xFFFF) / 32768.0 - 1.0
            val d = (hash32(ix + 1, iz + 1, salt) and 0xFFFF) / 32768.0 - 1.0
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

/** River centreline points with a per-point channel radius. */
private class RiverPath(
    @JvmField val x: FloatArray,
    @JvmField val z: FloatArray,
    @JvmField val r: FloatArray,
    @JvmField val count: Int,
    @JvmField val levee: Boolean,
) {
    /** Heading (radians) of the path at [i], from its neighbours. */
    fun tangent(i: Int): Double {
        val a = (i - 1).coerceIn(0, count - 1)
        val b = (i + 1).coerceIn(0, count - 1)
        return Math.atan2((z[b] - z[a]).toDouble(), (x[b] - x[a]).toDouble())
    }
}
