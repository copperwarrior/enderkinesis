package org.shipwrights.enderkinesis.spell.forge;

import net.minecraft.world.level.biome.Biome;

public final class BiomeClimateImpl {
    private BiomeClimateImpl() {}

    public static float downfall(Biome biome) {
        return biome.getModifiedClimateSettings().downfall();
    }
}
