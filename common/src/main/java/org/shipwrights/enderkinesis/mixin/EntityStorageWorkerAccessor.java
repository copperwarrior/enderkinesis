package org.shipwrights.enderkinesis.mixin;

import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityStorage.class)
public interface EntityStorageWorkerAccessor {
    @Accessor("worker")
    IOWorker enderkinesis$getWorker();
}
