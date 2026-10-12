package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.Optional
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BuiltinStructures
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool
import org.shipwrights.enderkinesis.registry.EKStructures

/**
 * Sselith Ruin — a jigsaw structure with one extra `findGenerationPoint` gate.
 * (Vanilla's `JigsawStructure` is `final`, so we extend `Structure` directly
 * and replicate its jigsaw-placement delegation with the same constructor
 * shape, codec layout (minus `start_height`, which this class never reads),
 * and `JigsawPlacement.addPieces` call. The gate:
 *
 *  1. **Ancient-city pairing.** The structure_set uses our custom
 *     [SselithRuinPlacement], which deterministically picks exactly one
 *     candidate chunk per AC cell at a per-AC-random angle ~8 chunks
 *     out, so vanilla only ever asks us about chunks paired 1:1 with
 *     an AC. Here we verify the AC actually *spawns* at the paired
 *     offset — the placement math gives a position vanilla *would*
 *     try, but vanilla also gates AC placement on
 *     `BiomeTags.HAS_ANCIENT_CITY` (= deep_dark). We run vanilla's own
 *     AC generation-point check for the paired chunk and reject the
 *     ruin if it fails — i.e. no AC actually generates there.
 *
 * The Y placement itself is unconditional: every paired ruin floor lands
 * at a fixed offset below vanilla AC's `start_height` (`-27`). The NBT encodes the
 * ruin's correct vertical silhouette, and `terrain_adaptation: beard_thin`
 * in the structure JSON handles whatever the surrounding terrain
 * happens to be — caves, solid stone, or anything between.)
 */
class SselithRuinStructure(
    settings: StructureSettings,
    internal val pool: Holder<StructureTemplatePool>,
    internal val startJigsawName: Optional<ResourceLocation>,
    internal val maxDepth: Int,
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
        // startPos.y = AC_ANCHOR_Y - AC_FLOOR_DEPTH_BELOW_ANCHOR - RUIN_FLOOR_TEMPLATE_Y
        //            = -27 - 23 - 1 = -51. See [AC_FLOOR_DEPTH_BELOW_ANCHOR].
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

    /** Verifies that a *real* ancient city spawns at the AC chunk paired
     *  with this ruin chunk by [SselithRuinPlacement]. Without this gate,
     *  ruins generate for ACs that the biome check rejects. */
    private fun hasAncientCityAtOffset(context: GenerationContext, chunkPos: ChunkPos): Boolean {
        val acChunk = SselithRuinPlacement.pairedAncientCityChunk(
            context.seed(), chunkPos.x, chunkPos.z,
        ) ?: return false
        val ancientCity = context.registryAccess().registryOrThrow(Registries.STRUCTURE)
            .getHolder(BuiltinStructures.ANCIENT_CITY).orElse(null)?.value() ?: return false
        // Vanilla samples the biome at the centre of the rotated start piece, not at the
        // chunk corner. Only AC's own seeded findGenerationPoint gives that point.
        val acContext = GenerationContext(
            context.registryAccess(), context.chunkGenerator(), context.biomeSource(),
            context.randomState(), context.structureTemplateManager(), context.seed(),
            acChunk, context.heightAccessor(), ancientCity.biomes()::contains,
        )
        return ancientCity.findValidGenerationPoint(acContext).isPresent
    }

    override fun type(): StructureType<*> = EKStructures.SSELITH_RUIN.get()

    companion object {
        /** Mirrors `worldgen/structure/ancient_city.json`'s `start_height: -27`. */
        private const val AC_ANCHOR_Y = -27

        /** Tuned in game. `JigsawPlacement.addPieces` aligns the start jigsaw
         *  (template Y=2) with `startPos`, so the floor lands at `startPos.y - 1` = -52. */
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
                    Codec.BOOL.fieldOf("use_expansion_hack").forGetter { it.useExpansionHack },
                    Heightmap.Types.CODEC.optionalFieldOf("project_start_to_heightmap").forGetter { it.projectStartToHeightmap },
                    Codec.intRange(1, 128).fieldOf("max_distance_from_center").forGetter { it.maxDistanceFromCenter },
                ).apply(builder, ::SselithRuinStructure)
            }
    }
}
