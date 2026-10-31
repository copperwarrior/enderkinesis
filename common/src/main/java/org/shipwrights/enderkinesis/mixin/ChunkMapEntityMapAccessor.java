package org.shipwrights.enderkinesis.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The level's tracked entities by id, so spell packets about an entity go only to the
 *  players tracking it (see {@code SpellNetwork}). The values are {@code ChunkMap.TrackedEntity},
 *  which is package-private: read them through {@link TrackedEntitySeenByAccessor}. */
@Mixin(ChunkMap.class)
public interface ChunkMapEntityMapAccessor {
    @Accessor("entityMap")
    Int2ObjectMap<Object> enderkinesis$getEntityMap();
}
