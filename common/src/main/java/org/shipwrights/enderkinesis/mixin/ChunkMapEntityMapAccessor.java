package org.shipwrights.enderkinesis.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The values are package-private {@code ChunkMap.TrackedEntity}: read them through {@link TrackedEntitySeenByAccessor}. */
@Mixin(ChunkMap.class)
public interface ChunkMapEntityMapAccessor {
    @Accessor("entityMap")
    Int2ObjectMap<Object> enderkinesis$getEntityMap();
}
