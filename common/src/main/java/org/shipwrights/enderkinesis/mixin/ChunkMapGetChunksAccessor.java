package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker for {@link ChunkMap#getChunks()}, vanilla's iterator over every loaded
 * {@link ChunkHolder} in a {@code ServerLevel}'s chunk source. The method is
 * package-private in 1.20.1 (annotated {@code @VisibleForDebug}), which Kotlin reports
 * as "protected" and refuses to call.
 *
 * Used by {@link org.shipwrights.enderkinesis.block.WohlonnogondoniaSpreader} to find
 * tainted chunks that loaded, when the tainted set is larger than the loaded set.
 */
@Mixin(ChunkMap.class)
public interface ChunkMapGetChunksAccessor {

    @Invoker("getChunks")
    Iterable<ChunkHolder> enderkinesis$getChunks();
}
