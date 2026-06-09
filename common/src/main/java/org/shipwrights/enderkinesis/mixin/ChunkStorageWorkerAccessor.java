package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Step 1 of the catastrophe's region-file release chain (chunkMap → worker → storage → regionCache). */
@Mixin(ChunkStorage.class)
public interface ChunkStorageWorkerAccessor {

    @Accessor("worker")
    IOWorker enderkinesis$getWorker();
}
