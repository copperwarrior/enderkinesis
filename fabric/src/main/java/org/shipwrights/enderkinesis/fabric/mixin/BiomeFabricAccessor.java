package org.shipwrights.enderkinesis.fabric.mixin;

import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Biome.class)
public interface BiomeFabricAccessor {
    @Accessor("climateSettings")
    Biome.ClimateSettings enderkinesis$getClimateSettings();
}
