package org.shipwrights.enderkinesis.dimension

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.DimensionType
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Identity for **Sureibjin** — the dream-coast ulder dimension reached by
 * sleeping inside another ulder dim. Infinite N/S beach with a steep west
 * dune cap and a long east shelf into deep ocean; obsidian / crying-obsidian
 * tendril fractals lift through the world; sky is a desaturated blue-pink-
 * grey noise flow.
 *
 *  - Chunk generation: [SureibjinChunkGenerator]
 *  - Sky rendering: `SureibjinSky` (client-side, via Mixin into
 *    `LevelRenderer.renderSky`)
 */
object Sureibjin {
    val ID: ResourceLocation = EnderkinesisMod.id("sureibjin")
    val LEVEL_KEY: ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, ID)
    val DIMENSION_TYPE_KEY: ResourceKey<DimensionType> =
        ResourceKey.create(Registries.DIMENSION_TYPE, ID)
}
