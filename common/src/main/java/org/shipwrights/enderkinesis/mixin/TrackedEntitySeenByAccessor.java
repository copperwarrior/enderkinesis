package org.shipwrights.enderkinesis.mixin;

import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/** The connections of the players tracking one entity; see {@link ChunkMapEntityMapAccessor}. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntitySeenByAccessor {
    @Accessor("seenBy")
    Set<ServerPlayerConnection> enderkinesis$getSeenBy();
}
