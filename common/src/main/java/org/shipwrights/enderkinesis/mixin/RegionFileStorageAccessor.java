package org.shipwrights.enderkinesis.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Step 3 of the catastrophe's region-file release chain. */
@Mixin(RegionFileStorage.class)
public interface RegionFileStorageAccessor {

    @Accessor("regionCache")
    Long2ObjectLinkedOpenHashMap<RegionFile> enderkinesis$getRegionCache();
}
