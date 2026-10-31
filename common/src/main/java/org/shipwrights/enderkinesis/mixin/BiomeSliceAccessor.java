package org.shipwrights.enderkinesis.mixin;

import me.jellysquid.mods.sodium.client.world.biome.BiomeSlice;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Raw quart cells of the slice snapshot; {@code getBiome} applies the Voronoi zoom. Same names in Sodium 0.5 and Embeddium 0.3. */
@Mixin(value = BiomeSlice.class, remap = false)
public interface BiomeSliceAccessor {

    @Accessor("biomes")
    Holder<Biome>[] enderkinesis$getBiomes();

    @Accessor("worldX")
    int enderkinesis$getWorldX();

    @Accessor("worldY")
    int enderkinesis$getWorldY();

    @Accessor("worldZ")
    int enderkinesis$getWorldZ();
}
