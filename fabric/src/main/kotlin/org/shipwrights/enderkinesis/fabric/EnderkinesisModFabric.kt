package org.shipwrights.enderkinesis.fabric

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.biome.v1.BiomeModifications
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.GenerationStep
import org.shipwrights.enderkinesis.EnderkinesisMod

/**
 * Fabric-side initializer. Common content is registered through Architectury in
 * [EnderkinesisMod.init]; here we inject the crepusculite geode into every biome in the
 * `enderkinesis:geode_can_spawn_in` tag (Forge does the same via `data/forge/biome_modifier/`,
 * which references the same tag).
 */
class EnderkinesisModFabric : ModInitializer {
    override fun onInitialize() {
        EnderkinesisMod.init()

val canSpawnIn: TagKey<Biome> = TagKey.create(
            Registries.BIOME, EnderkinesisMod.id("geode_can_spawn_in")
        )
        val geode = ResourceKey.create(
            Registries.PLACED_FEATURE, EnderkinesisMod.id("crepusculite_geode")
        )
        BiomeModifications.addFeature(
            BiomeSelectors.tag(canSpawnIn),
            GenerationStep.Decoration.UNDERGROUND_DECORATION,
            geode
        )
    }
}
