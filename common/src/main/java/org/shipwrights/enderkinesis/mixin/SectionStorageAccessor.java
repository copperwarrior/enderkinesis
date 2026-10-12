package org.shipwrights.enderkinesis.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionStorage.class)
public interface SectionStorageAccessor {
    @Accessor("worker")
    IOWorker enderkinesis$getWorker();

    @Accessor("storage")
    Long2ObjectMap<?> enderkinesis$getStorage();

    @Accessor("dirty")
    LongLinkedOpenHashSet enderkinesis$getDirty();
}
