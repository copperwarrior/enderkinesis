package org.shipwrights.enderkinesis.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.shipwrights.enderkinesis.scrying.ScryingSessionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Route block / light / block-entity updates inside a scrying camera's chunk square to
 * the viewing player. ChunkMap implements ChunkHolder.PlayerProvider — its
 * {@code getPlayers(ChunkPos, boolean)} is what {@code ChunkHolder.broadcastChanges}
 * calls to find recipients for a chunk's pending packets. Vanilla bases that list on
 * each player's distance to the chunk; we append anyone whose camera covers the chunk
 * so their client receives the same updates the area's "real" players would.
 *
 * One hook covers block-update packets, section-block-update packets, light-update
 * packets, and block-entity packets because all of them route through the same
 * provider call inside broadcastChanges.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapBroadcastScryingMixin {

    @ModifyReturnValue(method = "getPlayers", at = @At("RETURN"))
    private List<ServerPlayer> enderkinesis$includeScryingViewers(
        List<ServerPlayer> original, ChunkPos pos, boolean boundaryOnly
    ) {
        Set<ServerPlayer> viewers = ScryingSessionManager.INSTANCE.viewersOfChunk(pos);
        if (viewers.isEmpty()) return original;
        // Most ticks original already contains every recipient; allocate only when we
        // genuinely have extra viewers to merge in. Linear contains() is fine because
        // `original` is short (per-chunk recipient count is bounded by render distance).
        List<ServerPlayer> merged = new ArrayList<>(original.size() + viewers.size());
        merged.addAll(original);
        for (ServerPlayer v : viewers) {
            if (!original.contains(v)) merged.add(v);
        }
        return merged;
    }
}
