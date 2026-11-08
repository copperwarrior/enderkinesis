package org.shipwrights.enderkinesis.spell;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.world.level.biome.Biome;

/**
 * Forge reads climate through {@code getModifiedClimateSettings()}, so biome modifiers count. Do not widen
 * {@code Biome.climateSettings}: Forge's field_to_method coremod then fails for the whole Biome class.
 */
public final class BiomeClimate {
    private BiomeClimate() {}

    @ExpectPlatform
    public static float downfall(Biome biome) {
        throw new AssertionError();
    }
}
