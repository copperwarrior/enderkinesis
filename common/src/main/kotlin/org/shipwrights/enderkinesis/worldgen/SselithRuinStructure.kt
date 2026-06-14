package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.Optional
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.BiomeTags
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.LegacyRandomSource
import net.minecraft.world.level.levelgen.WorldgenRandom
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool
import org.shipwrights.enderkinesis.registry.EKStructures

/**
 * Sselith Ruin — a jigsaw structure with one extra `findGenerationPoint` gate.
 * (Vanilla's `JigsawStructure` is `final`, so we extend `Structure` directly
 * and replicate its jigsaw-placement delegation with the same constructor
 * shape, codec layout, and `JigsawPlacement.addPieces` call. The gate:
 *
 *  1. **Ancient-city pairing.** The structure_set uses our custom
 *     [SselithRuinPlacement], which deterministically picks exactly one
 *     candidate chunk per AC cell at a per-AC-random angle ~8 chunks
 *     out, so vanilla only ever asks us about chunks paired 1:1 with
 *     an AC. Here we verify the AC actually *spawns* at the paired
 *     offset — the placement math gives a position vanilla *would*
 *     try, but vanilla also gates AC placement on
 *     `BiomeTags.HAS_ANCIENT_CITY` (= deep_dark). We query the biome
 *     at the AC's chunk-min block (the same point vanilla's
 *     `Structure.isValidBiome` consults) and reject the ruin if it's
 *     not in the AC tag — i.e. no AC actually generates there.
 *
 * The Y placement itself is unconditional: every paired ruin lands at
 * the same `start_height` vanilla AC uses (`-27`). The NBT encodes the
 * ruin's correct vertical silhouette, and `terrain_adaptation: beard_thin`
 * in the structure JSON handles whatever the surrounding terrain
 * happens to be — caves, solid stone, or anything between.)
 */
class SselithRuinStructure(
    settings: StructureSettings,
    internal val pool: Holder<StructureTemplatePool>,
    internal val startJigsawName: Optional<ResourceLocation>,
    internal val maxDepth: Int,
    internal val startHeight: HeightProvider,
    internal val useExpansionHack: Boolean,
    internal val projectStartToHeightmap: Optional<Heightmap.Types>,
    internal val maxDistanceFromCenter: Int,
) : Structure(settings) {
    /** Shadow of the protected [Structure.settings] field so the companion's
     *  codec can read it without crossing the protected-member boundary. */
    internal val mySettings: StructureSettings = settings

    override fun findGenerationPoint(context: GenerationContext): Optional<GenerationStub> {
        val chunkPos = context.chunkPos()
        if (!hasAncientCityAtOffset(context, chunkPos)) return Optional.empty()
        // Plant the ruin's *floor* at the same world Y as the ancient
        // city's main *floor*. Vanilla AC's `city_anchor` jigsaw block
        // lands at `start_height` (= [AC_ANCHOR_Y]), and the AC's
        // jigsaw-expanded main floor sits [AC_FLOOR_DEPTH_BELOW_ANCHOR]
        // blocks below that — derived empirically: in-world the AC
        // anchor is at -27, the visible main floor is at -52, so the
        // depth is 25. The ruin's own NBT has its lowest block at
        // template Y=[RUIN_FLOOR_TEMPLATE_Y] (template Y=0 is an empty
        // placeholder layer), so for the ruin's floor block to land at
        // the AC's floor world Y we offset by both numbers:
        //
        //     ruin_floor_world_y = AC_ANCHOR_Y - AC_FLOOR_DEPTH_BELOW_ANCHOR
        //     startPos.y         = ruin_floor_world_y - RUIN_FLOOR_TEMPLATE_Y
        //                        = -27 - 25 - 1 = -53
        //
        // Both constants are dynamic in the sense that they're plain
        // values driving a single formula — bump `AC_ANCHOR_Y` if
        // Mojang ever changes AC's `start_height`, or re-measure
        // `AC_FLOOR_DEPTH_BELOW_ANCHOR` if they reshape AC's jigsaw
        // hierarchy. The ruin's `RUIN_FLOOR_TEMPLATE_Y` changes only
        // when the NBT itself is re-edited.
        val ruinFloorWorldY = AC_ANCHOR_Y - AC_FLOOR_DEPTH_BELOW_ANCHOR
        val startPos = BlockPos(
            chunkPos.minBlockX,
            ruinFloorWorldY - RUIN_FLOOR_TEMPLATE_Y,
            chunkPos.minBlockZ,
        )
        return JigsawPlacement.addPieces(
            context, pool, startJigsawName, maxDepth, startPos,
            useExpansionHack, projectStartToHeightmap, maxDistanceFromCenter,
        )
    }

    /** Verifies that a *real* ancient city actually spawns at the AC
     *  chunk paired with this ruin chunk by [SselithRuinPlacement].
     *  The placement guarantees the pairing math lines up — there's
     *  exactly one AC cell whose forward computation produces this
     *  chunk — but vanilla also gates AC placement on
     *  `BiomeTags.HAS_ANCIENT_CITY` (= deep-dark). This check runs
     *  the same biome query vanilla's `Structure.isValidBiome` uses,
     *  at the predicted AC anchor's chunk-min block (vanilla's
     *  JigsawStructure stub position is `chunkpos.getMinBlockX()`,
     *  not the chunk centre — the 8-block difference matters at biome
     *  boundaries). Without this gate, we'd generate ruins for phantom
     *  ACs that exist on paper but not in the world. */
    private fun hasAncientCityAtOffset(context: GenerationContext, chunkPos: ChunkPos): Boolean {
        val acChunk = SselithRuinPlacement.pairedAncientCityChunk(
            context.seed(), chunkPos.x, chunkPos.z,
        ) ?: return false
        val biome = context.biomeSource().getNoiseBiome(
            acChunk.x * 4,
            AC_ANCHOR_Y shr 2,
            acChunk.z * 4,
            context.randomState().sampler(),
        )
        return biome.`is`(BiomeTags.HAS_ANCIENT_CITY)
    }

    override fun type(): StructureType<*> = EKStructures.SSELITH_RUIN.get()

    companion object {
        /** Y level vanilla anchors ancient cities at — mirrored from
         *  `worldgen/structure/ancient_city.json`'s `start_height: -27`.
         *  Used to query the AC's anchor-chunk biome at the right Y so
         *  `BiomeTags.HAS_ANCIENT_CITY` resolves the same biome vanilla
         *  would consult when deciding whether to place an AC there. */
        private const val AC_ANCHOR_Y = -27

        /** Calibrated empirically: with depth=25 (`startPos.y` = −53),
         *  the floor landed at world Y=−54 — i.e. one block below
         *  `startPos.y`. The reason is that `JigsawPlacement.addPieces`
         *  aligns the *start jigsaw block* with `startPos`, not the
         *  template origin: our start jigsaw sits at template Y=2, so
         *  the template origin lands at `startPos.y − 2` and the floor
         *  (template Y=1) lands at `startPos.y − 1`. We compensate by
         *  pulling depth in by 1 from the naïve `−27 − (−53) = 26`
         *  to 24 — `startPos.y = −52`, floor lands at −53, matching
         *  the AC floor.
         *
         *  Re-measure if vanilla moves the AC floor, the ruin NBT is
         *  re-edited with a different start-jigsaw Y, or any future
         *  vanilla update changes the placement chain's offsets. */
        private const val AC_FLOOR_DEPTH_BELOW_ANCHOR = 23

        /** Template Y of the ruin's lowest occupied block in the Sselith
         *  Ruin NBT. Template Y=0 is an empty placeholder layer (added
         *  by the structure-block save when the ruin was edited); the
         *  ruin's actual visible floor is one block up. Verified by
         *  walking the NBT block list. */
        private const val RUIN_FLOOR_TEMPLATE_Y = 1


        val CODEC: Codec<SselithRuinStructure> =
            RecordCodecBuilder.create<SselithRuinStructure> { builder ->
                builder.group(
                    Structure.StructureSettings.CODEC.forGetter { it.mySettings },
                    StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter { it.pool },
                    ResourceLocation.CODEC.optionalFieldOf("start_jigsaw_name").forGetter { it.startJigsawName },
                    Codec.intRange(0, 7).fieldOf("size").forGetter { it.maxDepth },
                    HeightProvider.CODEC.fieldOf("start_height").forGetter { it.startHeight },
                    Codec.BOOL.fieldOf("use_expansion_hack").forGetter { it.useExpansionHack },
                    Heightmap.Types.CODEC.optionalFieldOf("project_start_to_heightmap").forGetter { it.projectStartToHeightmap },
                    Codec.intRange(1, 128).fieldOf("max_distance_from_center").forGetter { it.maxDistanceFromCenter },
                ).apply(builder, ::SselithRuinStructure)
            }
    }
}
