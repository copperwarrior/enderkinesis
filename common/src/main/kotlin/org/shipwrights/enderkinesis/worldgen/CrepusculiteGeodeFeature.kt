package org.shipwrights.enderkinesis.worldgen

import com.mojang.datafixers.util.Pair
import com.mojang.serialization.Codec
import net.minecraft.Util
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.TickTask
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BuddingAmethystBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.LegacyRandomSource
import net.minecraft.world.level.levelgen.WorldgenRandom
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.GeodeConfiguration
import net.minecraft.world.level.levelgen.synth.NormalNoise
import org.shipwrights.enderkinesis.blockentity.CrepusculiteLatticeBlockEntity
import org.shipwrights.enderkinesis.compat.distanthorizons.DistantHorizonsCompat
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A faithful port of vanilla [net.minecraft.world.level.levelgen.feature.GeodeFeature] (the
 * amethyst geode generator) with exactly two deliberate changes:
 *
 *  1. **Generates in open air.** Vanilla aborts when distribution points land in air
 *     (`isAir() ... && ++m > invalidBlocksThreshold`); that is why vanilla geodes only form inside
 *     terrain. We only count true invalid blocks (lava/water), so the geode forms floating in open
 *     space. RNG consumption is unchanged, so the shape is identical to vanilla.
 *  2. **Lattice in the exact middle.** A crepusculite lattice is placed at the centroid of the
 *     distribution points (the geode's real centre) — vanilla's `origin` is a corner of the
 *     `[-16,+16]` generation cube, not the centre.
 */
class CrepusculiteGeodeFeature(codec: Codec<GeodeConfiguration>) : Feature<GeodeConfiguration>(codec) {

    private val directions = Direction.values()

    /**
     * The blocks this geode actually placed, collected during [place] so we can hand the *exact*
     * voxel set to VS2 — no connectivity flood-fill needed. Per-thread because worldgen runs many
     * [place] calls on different threads concurrently; each call resets it, and a single [place]
     * runs to completion on one thread before that thread starts another, so a plain reset (no
     * try/finally) is sufficient and can't leak between geodes.
     */
    private val placedBlocks = ThreadLocal<MutableSet<BlockPos>>()

    override fun safeSetBlock(
        level: WorldGenLevel, pos: BlockPos, state: BlockState,
        predicate: java.util.function.Predicate<BlockState>,
    ) {
        super.safeSetBlock(level, pos, state, predicate)
        // Record only blocks that were genuinely placed (predicate passed) and aren't air.
        if (!state.isAir && level.getBlockState(pos) == state) {
            placedBlocks.get()?.add(pos.immutable())
        }
    }

    override fun place(ctx: FeaturePlaceContext<GeodeConfiguration>): Boolean {
        placedBlocks.set(HashSet())
        val cfg = ctx.config()
        val rng = ctx.random()
        val origin = ctx.origin()
        val level = ctx.level()
        val minOff = cfg.minGenOffset
        val maxOff = cfg.maxGenOffset
        val points = ArrayList<Pair<BlockPos, Int>>()
        val k = cfg.distributionPoints.sample(rng)
        val wgRandom = WorldgenRandom(LegacyRandomSource(level.seed))
        val noise = NormalNoise.create(wgRandom, -4, 1.0)
        val crackPoints = ArrayList<BlockPos>()
        val d = k.toDouble() / cfg.outerWallDistance.maxValue.toDouble()
        val blocks = cfg.geodeBlockSettings
        val layers = cfg.geodeLayerSettings
        val crack = cfg.geodeCrackSettings
        val e = 1.0 / Math.sqrt(layers.filling)
        val f = 1.0 / Math.sqrt(layers.innerLayer + d)
        val g = 1.0 / Math.sqrt(layers.middleLayer + d)
        val h = 1.0 / Math.sqrt(layers.outerLayer + d)
        val l = 1.0 / Math.sqrt(crack.baseCrackSize + rng.nextDouble() / 2.0 + (if (k > 3) d else 0.0))
        val genCrack = rng.nextFloat().toDouble() < crack.generateCrackChance
        var invalid = 0

        for (n in 0 until k) {
            val o = cfg.outerWallDistance.sample(rng)
            val p = cfg.outerWallDistance.sample(rng)
            val q = cfg.outerWallDistance.sample(rng)
            val pt = origin.offset(o, p, q)
            // CHANGED: only real invalid blocks (lava/water) abort — not air — so it floats.
            if (level.getBlockState(pt).`is`(BlockTags.GEODE_INVALID_BLOCKS) && ++invalid > cfg.invalidBlocksThreshold) {
                return false
            }
            points.add(Pair.of(pt, cfg.pointOffset.sample(rng)))
        }

        if (genCrack) {
            val n = rng.nextInt(4)
            val o = k * 2 + 1
            when (n) {
                0 -> { crackPoints.add(origin.offset(o, 7, 0)); crackPoints.add(origin.offset(o, 5, 0)); crackPoints.add(origin.offset(o, 1, 0)) }
                1 -> { crackPoints.add(origin.offset(0, 7, o)); crackPoints.add(origin.offset(0, 5, o)); crackPoints.add(origin.offset(0, 1, o)) }
                2 -> { crackPoints.add(origin.offset(o, 7, o)); crackPoints.add(origin.offset(o, 5, o)); crackPoints.add(origin.offset(o, 1, o)) }
                else -> { crackPoints.add(origin.offset(0, 7, 0)); crackPoints.add(origin.offset(0, 5, 0)); crackPoints.add(origin.offset(0, 1, 0)) }
            }
        }

        val placements = ArrayList<BlockPos>()
        val predicate = Feature.isReplaceable(blocks.cannotReplace)
        for (bp in BlockPos.betweenClosed(origin.offset(minOff, minOff, minOff), origin.offset(maxOff, maxOff, maxOff))) {
            val r = noise.getValue(bp.x.toDouble(), bp.y.toDouble(), bp.z.toDouble()) * cfg.noiseMultiplier
            var s = 0.0
            var t = 0.0
            for (pair in points) {
                s += Mth.invSqrt(bp.distSqr(pair.first) + pair.second.toDouble()) + r
            }
            for (cp in crackPoints) {
                t += Mth.invSqrt(bp.distSqr(cp) + crack.crackPointOffset.toDouble()) + r
            }
            if (s < h) continue
            if (genCrack && t >= l && s < e) {
                safeSetBlock(level, bp, Blocks.AIR.defaultBlockState(), predicate)
                for (dir in directions) {
                    val rel = bp.relative(dir)
                    val fs = level.getFluidState(rel)
                    if (!fs.isEmpty) level.scheduleTick(rel, fs.type, 0)
                }
                continue
            }
            if (s >= e) {
                safeSetBlock(level, bp, blocks.fillingProvider.getState(rng, bp), predicate)
                continue
            }
            if (s >= f) {
                val alternate = rng.nextFloat().toDouble() < cfg.useAlternateLayer0Chance
                if (alternate) {
                    safeSetBlock(level, bp, blocks.alternateInnerLayerProvider.getState(rng, bp), predicate)
                } else {
                    safeSetBlock(level, bp, blocks.innerLayerProvider.getState(rng, bp), predicate)
                }
                if ((!cfg.placementsRequireLayer0Alternate || alternate) &&
                    rng.nextFloat().toDouble() < cfg.usePotentialPlacementsChance
                ) {
                    placements.add(bp.immutable())
                }
                continue
            }
            if (s >= g) {
                safeSetBlock(level, bp, blocks.middleLayerProvider.getState(rng, bp), predicate)
                continue
            }
            // s >= h
            safeSetBlock(level, bp, blocks.outerLayerProvider.getState(rng, bp), predicate)
        }

        val innerPlacements = blocks.innerPlacements
        loop@ for (place in placements) {
            var state = Util.getRandom(innerPlacements, rng)
            for (dir in directions) {
                if (state.hasProperty(BlockStateProperties.FACING)) {
                    state = state.setValue(BlockStateProperties.FACING, dir)
                }
                val rel = place.relative(dir)
                val existing = level.getBlockState(rel)
                if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
                    state = state.setValue(BlockStateProperties.WATERLOGGED, existing.fluidState.isSource)
                }
                if (!BuddingAmethystBlock.canClusterGrowAtState(existing)) continue
                safeSetBlock(level, rel, state, predicate)
                continue@loop
            }
        }

        // Centroid of the distribution points = the geode's real middle.
        if (points.isEmpty()) return true
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (pair in points) { sx += pair.first.x; sy += pair.first.y; sz += pair.first.z }
        val center = BlockPos(
            (sx / points.size).roundToInt(),
            (sy / points.size).roundToInt(),
            (sz / points.size).roundToInt()
        )

        // The lattice goes last so it overwrites the crystal's centre block.
        growCrystal(level, center, rng)
        level.setBlock(center, EKBlocks.CREPUSCULITE_LATTICE.get().defaultBlockState(), 2)
        placedBlocks.get()?.add(center.immutable())
        // NB: we can't stamp the sea level here — during worldgen the chunk is a ProtoChunk and
        // setBlock only records *pending* BE NBT, so getBlockEntity(center) is null. It's done in
        // the deferred task instead, once the chunk is force-loaded and the real BE exists.

        // Assemble the exact blocks we just placed into a VS2 ship. VS2's assembler is
        // game-tick-only and worldgen runs off-thread, so the known voxel set is deferred to the
        // main server thread. The level here is a WorldGenRegion during chunk gen but the
        // ServerLevel itself for `/place feature` — handle both.
        val placed = placedBlocks.get()
        @Suppress("DEPRECATION") // WorldGenRegion.getLevel: the only path to the ServerLevel here in 1.20.1.
        val server: ServerLevel? = (level as? WorldGenRegion)?.level ?: (level as? ServerLevel)
        if (server == null || placed.isNullOrEmpty()) return true
        // A DH LOD chunk is never saved; assembling would force-load and shipify a far real chunk.
        if (DistantHorizonsCompat.isLodGeneration()) return true
        scheduleShipAssembly(server, HashSet(placed), center.immutable(), ASSEMBLY_ATTEMPTS)
        return true
    }

    /**
     * Defer [ShipAssembler.assembleToShip] for the geode's exact block set onto the main thread.
     * The geode's chunk may not be a loaded `LevelChunk` when this runs (generated only as a
     * neighbour dependency, no player nearby), so rather than *waiting* for it — which loses the
     * race and abandons the geode as static blocks — we **force-load** the lattice's chunk to FULL
     * on the server thread, making assembly deterministic regardless of player proximity. A small
     * retry budget remains only for the rare case the chunk is still mid-pipeline. The
     * [TickTask] is the hand-off from the worldgen worker to the server thread, not a delay.
     */
    private fun scheduleShipAssembly(
        server: ServerLevel, blocks: Set<BlockPos>, latticePos: BlockPos, attemptsLeft: Int,
    ) {
        val ms = server.server
        ms.tell(TickTask(ms.tickCount + ASSEMBLY_DELAY_TICKS) {
            val cp = ChunkPos(latticePos)
            // Force the chunk to FULL on the server thread (loads/finishes it if needed), so the
            // lattice is readable even if no player is around to keep it ticketed.
            server.getChunk(cp.x, cp.z)
            if (server.getBlockState(latticePos).`is`(EKBlocks.CREPUSCULITE_LATTICE.get())) {
                try {
                    // Stamp the sea level on the (now real) BE *before* assembly so VS2 carries it
                    // through via the BE NBT — the lattice floats at its spawn Y, not a garbage
                    // unsettled-transform value.
                    val lattice = server.getBlockEntity(latticePos) as? CrepusculiteLatticeBlockEntity
                    lattice?.captureSeaLevel(latticePos.y + 0.5)
                    lattice?.parkForWorldgen()
                    val ship = ShipAssembler.assembleToShip(server, blocks)
                    if (ship != null) {
                        // Far shipyard chunks do not tick, so a dynamic geode falls into the
                        // void. Only a parked lattice releases the ship on its first real tick.
                        if (lattice != null) ship.isStatic = true
                        org.shipwrights.enderkinesis.dimension.YgannDiscoveries.mark(
                            ms, ship.id, org.shipwrights.enderkinesis.dimension.YgannDiscoveries.KIND_GEODE,
                        )
                    }
                } catch (t: Throwable) {
                    LOG.error("[EK] geode ship assembly threw at {}", latticePos, t)
                }
                return@TickTask
            }
            if (attemptsLeft > 1) {
                // A TickTask is not a delay, so the retry waits real ticks first.
                org.shipwrights.enderkinesis.util.ServerTicks.runAfter(ms, ASSEMBLY_DELAY_TICKS) {
                    scheduleShipAssembly(server, blocks, latticePos, attemptsLeft - 1)
                }
            }
        })
    }

    /**
     * Voxel approximation of a double-terminated hexagonal crystal centred on [center], along a
     * random non-axis-aligned direction: a hex prism in the middle with pyramidal points on both
     * ends.
     */
    private fun growCrystal(level: WorldGenLevel, center: BlockPos, rng: RandomSource) {
        // Uniformly-random direction on the unit sphere (any direction, cardinals included):
        // rejection-sample inside the unit ball, then normalize.
        var dx = 0.0; var dy = 1.0; var dz = 0.0
        var tries = 0
        while (tries++ < 64) {
            val x = rng.nextDouble() * 2 - 1
            val y = rng.nextDouble() * 2 - 1
            val z = rng.nextDouble() * 2 - 1
            val l2 = x * x + y * y + z * z
            if (l2 < 1.0e-6 || l2 > 1.0) continue
            val l = sqrt(l2)
            dx = x / l; dy = y / l; dz = z / l
            break
        }

        // Orthonormal basis (u, v) spanning the plane perpendicular to the crystal axis.
        val hx: Double; val hy: Double; val hz: Double
        if (abs(dy) < 0.9) { hx = 0.0; hy = 1.0; hz = 0.0 } else { hx = 1.0; hy = 0.0; hz = 0.0 }
        var ux = dy * hz - dz * hy; var uy = dz * hx - dx * hz; var uz = dx * hy - dy * hx
        val ul = sqrt(ux * ux + uy * uy + uz * uz); ux /= ul; uy /= ul; uz /= ul
        val vx = dy * uz - dz * uy; val vy = dz * ux - dx * uz; val vz = dx * uy - dy * ux

        val circ = CRYSTAL_CIRC                 // hex circumradius / width
        val half = 4.0 + rng.nextDouble() * 7.0 // crystal "radius": half-length along the axis, 4..11
        val prismHalf = half * 0.5              // straight middle; the rest tapers to points
        val taper = half - prismHalf
        val box = ceil(half + circ + 1.0).toInt()
        val glass = org.shipwrights.enderkinesis.registry.EKBlocks.CREPUSCULITE_GLASS.get().defaultBlockState()
        val k = 0.8660254                       // sqrt(3)/2

        for (ox in -box..box) for (oy in -box..box) for (oz in -box..box) {
            val rxd = ox.toDouble(); val ryd = oy.toDouble(); val rzd = oz.toDouble()
            val t = rxd * dx + ryd * dy + rzd * dz
            val at = abs(t)
            if (at > half) continue
            val rad = if (at <= prismHalf) circ else circ * ((half - at) / taper)
            if (rad <= 0.0) continue
            val a = rxd * ux + ryd * uy + rzd * uz
            val b = rxd * vx + ryd * vy + rzd * vz
            val ap = rad * k
            if (abs(a) <= ap && abs(a * 0.5 + b * k) <= ap && abs(a * 0.5 - b * k) <= ap) {
                val gp = BlockPos(center.x + ox, center.y + oy, center.z + oz)
                level.setBlock(gp, glass, 2)
                placedBlocks.get()?.add(gp)
            }
        }
    }

    private companion object {
        private val LOG = com.mojang.logging.LogUtils.getLogger()

        /** How many times to wait-and-retry the deferred ship assembly before giving up. */
        const val ASSEMBLY_ATTEMPTS = 20
        /** Ticks between assembly attempts — lets the geode's chunks finish generating. */
        const val ASSEMBLY_DELAY_TICKS = 10
        /** Crystal hex circumradius (== half-width). */
        const val CRYSTAL_CIRC = 3.0
    }
}
