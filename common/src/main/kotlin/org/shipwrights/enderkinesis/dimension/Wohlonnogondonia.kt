package org.shipwrights.enderkinesis.dimension

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.dimension.DimensionType
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Identity for **Wohlonnogondonia** — an infinite dismal swamp dimension. The terrain is a
 * mostly-flat plain of mud (bedrock at y=0, mud all the way up to a low surface around the
 * sea level at y=32), with murky pools of dark water filling every dip. The world is in
 * perpetual sunset, the air heavy and dim. Mangrove-styled Wogor trees scatter across the
 * dry mud; at world origin a wide low hill cradles the **Mother Tree**, a colossal Wogor
 * specimen whose crown reaches y=100.
 *
 *  - Chunk generation: [WohlonnogondoniaChunkGenerator]
 */
object Wohlonnogondonia {
    val ID: ResourceLocation = EnderkinesisMod.id("wohlonnogondonia")
    val LEVEL_KEY: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, ID)
    val DIMENSION_TYPE_KEY: ResourceKey<DimensionType> =
        ResourceKey.create(Registries.DIMENSION_TYPE, ID)

    /** Biome registry key for the Wohlonnogondonia biome. Same id
     *  as the dimension itself; the biome JSON lives at
     *  `data/enderkinesis/worldgen/biome/wohlonnogondonia.json`. */
    @JvmField
    val BIOME_KEY: ResourceKey<Biome> = ResourceKey.create(Registries.BIOME, ID)
}
