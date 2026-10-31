package org.shipwrights.enderkinesis.client

import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.ResourceLocation

@Suppress("DEPRECATION") // Mojang marks the id deprecated; it is still the block atlas.
internal val BLOCK_ATLAS: ResourceLocation = TextureAtlas.LOCATION_BLOCKS

@Suppress("DEPRECATION") // Mojang marks the id deprecated; it is still the particle atlas.
internal val PARTICLE_ATLAS: ResourceLocation = TextureAtlas.LOCATION_PARTICLES
