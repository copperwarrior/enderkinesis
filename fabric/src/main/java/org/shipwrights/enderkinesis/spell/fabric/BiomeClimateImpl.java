package org.shipwrights.enderkinesis.spell.fabric;

import net.minecraft.world.level.biome.Biome;
import org.shipwrights.enderkinesis.fabric.mixin.BiomeFabricAccessor;

public final class BiomeClimateImpl {
    private BiomeClimateImpl() {}

    public static float downfall(Biome biome) {
        return ((BiomeFabricAccessor) (Object) biome).enderkinesis$getClimateSettings().downfall();
    }
}
