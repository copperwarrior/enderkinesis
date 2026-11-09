package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.Optional
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.WorldGenerationContext
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool
import org.shipwrights.enderkinesis.dimension.WohlonnogondoniaChunkGenerator
import org.shipwrights.enderkinesis.registry.EKStructures

/** A jigsaw structure that starts only on flat, dry Wohlon ground. Vanilla's `JigsawStructure` is final. */
class WohlonFlatGroundStructure(
    settings: StructureSettings,
    internal val pool: Holder<StructureTemplatePool>,
    internal val startJigsawName: Optional<ResourceLocation>,
    internal val maxDepth: Int,
    internal val startHeight: HeightProvider,
    internal val useExpansionHack: Boolean,
    internal val projectStartToHeightmap: Optional<Heightmap.Types>,
    internal val maxDistanceFromCenter: Int,
    /** Blocks around the start that must be flat: the start piece can turn to any side of it. */
    internal val flatGroundReach: Int,
) : Structure(settings) {
    /** Lets the codec read the protected [Structure.settings]. */
    internal val mySettings: StructureSettings = settings

    override fun findGenerationPoint(context: GenerationContext): Optional<GenerationStub> {
        val generator = context.chunkGenerator() as? WohlonnogondoniaChunkGenerator ?: return Optional.empty()
        val x0 = context.chunkPos().minBlockX
        val z0 = context.chunkPos().minBlockZ
        val reach = flatGroundReach
        if (!generator.isFlatDryArea(x0 - reach, z0 - reach, x0 + reach, z0 + reach)) return Optional.empty()
        val y = startHeight.sample(context.random(), WorldGenerationContext(generator, context.heightAccessor()))
        return JigsawPlacement.addPieces(
            context, pool, startJigsawName, maxDepth, BlockPos(x0, y, z0),
            useExpansionHack, projectStartToHeightmap, maxDistanceFromCenter,
        )
    }

    override fun type(): StructureType<*> = EKStructures.WOHLON_FLAT_GROUND.get()

    companion object {
        val CODEC: Codec<WohlonFlatGroundStructure> =
            RecordCodecBuilder.create<WohlonFlatGroundStructure> { builder ->
                builder.group(
                    Structure.StructureSettings.CODEC.forGetter { it.mySettings },
                    StructureTemplatePool.CODEC.fieldOf("start_pool").forGetter { it.pool },
                    ResourceLocation.CODEC.optionalFieldOf("start_jigsaw_name").forGetter { it.startJigsawName },
                    Codec.intRange(0, 7).fieldOf("size").forGetter { it.maxDepth },
                    HeightProvider.CODEC.fieldOf("start_height").forGetter { it.startHeight },
                    Codec.BOOL.fieldOf("use_expansion_hack").forGetter { it.useExpansionHack },
                    Heightmap.Types.CODEC.optionalFieldOf("project_start_to_heightmap")
                        .forGetter { it.projectStartToHeightmap },
                    Codec.intRange(1, 128).fieldOf("max_distance_from_center").forGetter { it.maxDistanceFromCenter },
                    Codec.intRange(0, 64).fieldOf("flat_ground_reach").forGetter { it.flatGroundReach },
                ).apply(builder, ::WohlonFlatGroundStructure)
            }
    }
}
