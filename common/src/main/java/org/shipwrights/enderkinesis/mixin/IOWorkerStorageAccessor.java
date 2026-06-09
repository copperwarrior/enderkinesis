package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Step 2 of the catastrophe's region-file release chain. */
@Mixin(IOWorker.class)
public interface IOWorkerStorageAccessor {

    @Accessor("storage")
    RegionFileStorage enderkinesis$getStorage();
}
