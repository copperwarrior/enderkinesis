package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.Optional
import net.minecraft.core.Vec3i
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState
import net.minecraft.world.level.levelgen.LegacyRandomSource
import net.minecraft.world.level.levelgen.WorldgenRandom
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType
import org.shipwrights.enderkinesis.registry.EKStructurePlacements

/**
 * Pairs each Ancient City with exactly one Sselith Ruin candidate chunk
 * at a per-AC-randomized direction ~[RUIN_RADIUS_CHUNKS] chunks out.
 *
 * **Why this extends `RandomSpreadStructurePlacement` and not the base
 * `StructurePlacement`:** vanilla's `ChunkGenerator.findNearestMapStructure`
 * (the routine `/locate` calls) only dispatches into two placement types —
 * `RandomSpreadStructurePlacement` and `ConcentricRingsStructurePlacement`.
 * Anything else is silently skipped and `/locate` returns "could not
 * find" even when structures are generating correctly during normal
 * worldgen (which uses `isPlacementChunk` per-chunk and works for any
 * placement). So we extend `RandomSpread` to ride its cell-spiral search,
 * but override two methods:
 *
 *  - [getPotentialStructureChunk] returns the ruin's chunk for a given
 *    cell — computed as the AC's predicted chunk in that cell plus a
 *    radial offset whose angle is drawn from the same per-AC RNG.
 *  - [isPlacementChunk] accepts a candidate iff [pairedAncientCityChunk]
 *    finds an AC in one of the 9 surrounding cells whose forward
 *    computation produces this exact ruin chunk.
 *
 * Both routines agree on exactly one ruin chunk per AC cell — the cell
 * grid is AC's own 24-chunk grid, and the AC chunk + angle within each
 * cell are keyed by AC's salt, so the pairing is 1:1 with where vanilla
 * *would* try to spawn an AC. The ruin's biome filter and
 * [SselithRuinStructure]'s biome verification then drop ACs whose
 * predicted chunk isn't actually deep-dark (the only break in strict 1:1).
 *
 * The base `RandomSpread` constructor is fed [AC_SPACING] / [AC_SEPARATION]
 * / linear spread type so all the inherited grid math operates on AC's
 * grid; our override of `getPotentialStructureChunk` ignores the
 * inherited `salt` field and always uses [AC_SALT] directly. The codec
 * exposes only the five base `StructurePlacement` fields — datapacks
 * don't need to (and shouldn't) configure spacing/separation/spread for
 * this placement, since they're locked to vanilla AC.
 */
class SselithRuinPlacement(
    locateOffset: Vec3i,
    frequencyReductionMethod: FrequencyReductionMethod,
    frequency: Float,
    salt: Int,
    exclusionZone: Optional<ExclusionZone>,
) : RandomSpreadStructurePlacement(
    locateOffset,
    frequencyReductionMethod,
    frequency,
    salt,
    exclusionZone,
    AC_SPACING,
    AC_SEPARATION,
    RandomSpreadType.LINEAR,
) {

    override fun getPotentialStructureChunk(seed: Long, x: Int, z: Int): ChunkPos {
        // CRITICAL: the (x, z) parameters from vanilla's `/locate` search
        // loop aren't cell indices — they're chunk-scaled values of the
        // form `playerChunkX + spacing * dx`, and vanilla's
        // `RandomSpread.getPotentialStructureChunk` recovers the cell
        // index with `Math.floorDiv(x, spacing)`. Skipping this step
        // multiplies the offset by spacing again and yields chunks tens
        // of thousands of blocks from the search origin.
        val cellX = Math.floorDiv(x, AC_SPACING)
        val cellZ = Math.floorDiv(z, AC_SPACING)
        return ruinChunkForCell(seed, cellX, cellZ).second
    }

    override fun isPlacementChunk(state: ChunkGeneratorStructureState, chunkX: Int, chunkZ: Int): Boolean {
        return pairedAncientCityChunk(state.levelSeed, chunkX, chunkZ) != null
    }

    override fun type(): StructurePlacementType<*> = EKStructurePlacements.SSELITH_RUIN.get()

    companion object {
        /** Vanilla ancient-city placement constants. Mirrored from
         *  `data/minecraft/worldgen/structure_set/ancient_cities.json`. */
        const val AC_SPACING = 24
        const val AC_SEPARATION = 8
        const val AC_SALT = 20083232

        /** Radial offset (chunks) from the AC anchor to the ruin anchor.
         *  8 chunks = 128 blocks — just past AC's 116-block
         *  `max_distance_from_center` outer-jigsaw reach, so the ruin
         *  reads as grafted onto the city's outskirts but never
         *  overlaps it. Direction is per-AC-random; see [ruinChunkForCell]. */
        const val RUIN_RADIUS_CHUNKS = 8.0

        /** Reverse lookup: given a candidate ruin chunk, return the AC it
         *  pairs with, or null if no AC in the 3×3 cell neighborhood pairs
         *  with this chunk. Used both by [isPlacementChunk] and by
         *  [SselithRuinStructure]'s biome gate (to know where to sample).
         *  Lives on the companion so the structure can call it without
         *  needing an instance. */
        fun pairedAncientCityChunk(seed: Long, ruinChunkX: Int, ruinChunkZ: Int): ChunkPos? {
            val baseCellX = Math.floorDiv(ruinChunkX, AC_SPACING)
            val baseCellZ = Math.floorDiv(ruinChunkZ, AC_SPACING)
            // The ruin sits at most RUIN_RADIUS_CHUNKS chunks from its AC,
            // and AC cells are AC_SPACING (24) chunks wide — so the paired
            // AC is in this chunk's cell or one of the 8 neighbors. 9 RNG
            // re-derives per call, all on stack; cheap enough at worldgen
            // frequency.
            for (dx in -1..1) {
                for (dz in -1..1) {
                    val (ac, ruin) = ruinChunkForCell(seed, baseCellX + dx, baseCellZ + dz)
                    if (ruin.x == ruinChunkX && ruin.z == ruinChunkZ) return ac
                }
            }
            return null
        }

        /** Forward computation for one AC cell: derive both the AC chunk
         *  (vanilla RandomSpread math bit-for-bit using [AC_SALT], so the
         *  result equals where vanilla anchors AC) and the ruin chunk
         *  (radial offset at a per-AC-random angle drawn from the same
         *  RNG sequence). Returns `(ac, ruin)`. */
        internal fun ruinChunkForCell(seed: Long, cellX: Int, cellZ: Int): Pair<ChunkPos, ChunkPos> {
            val rng = WorldgenRandom(LegacyRandomSource(0L))
            rng.setLargeFeatureWithSalt(seed, cellX, cellZ, AC_SALT)
            val acChunkX = cellX * AC_SPACING + rng.nextInt(AC_SPACING - AC_SEPARATION)
            val acChunkZ = cellZ * AC_SPACING + rng.nextInt(AC_SPACING - AC_SEPARATION)
            // Continue drawing from the same per-AC RNG so the angle is
            // keyed 1:1 with the AC. Both forward and reverse calls
            // reproduce the same angle for the same cell + seed.
            val angle = rng.nextDouble() * 2.0 * Math.PI
            val ruinX = acChunkX + Math.round(RUIN_RADIUS_CHUNKS * Math.cos(angle)).toInt()
            val ruinZ = acChunkZ + Math.round(RUIN_RADIUS_CHUNKS * Math.sin(angle)).toInt()
            return ChunkPos(acChunkX, acChunkZ) to ChunkPos(ruinX, ruinZ)
        }

        val CODEC: Codec<SselithRuinPlacement> = RecordCodecBuilder.mapCodec<SselithRuinPlacement> { builder ->
            placementCodec(builder).apply(builder, ::SselithRuinPlacement)
        }.codec()
    }
}
