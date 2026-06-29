package org.shipwrights.enderkinesis.scrying;

import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

/**
 * Interface bolted onto {@link net.minecraft.client.multiplayer.ClientChunkCache} via
 * mixin so callers outside the package can drive the parallel camera-chunk storage
 * without exposing its internals. Implemented by
 * {@link org.shipwrights.enderkinesis.mixin.ClientChunkCacheScryingMixin}.
 *
 * <p>Design summary: vanilla's main {@code Storage} stays anchored at the player's
 * chunk; the camera storage tracks the scrying target. Chunk packets arriving for
 * positions that the main storage doesn't cover but the camera storage does are
 * routed here; lookups fall back here when the main storage misses. Both render
 * simultaneously through the normal {@code level.getChunk(x, z)} path — the renderer
 * doesn't need to know there are two storages.
 *
 * <p>Client-side only — the interface is added by a client mixin.
 */
public interface ScryingChunkCacheBridge {

    /** Whether the camera storage is currently armed and the given chunk pos falls
     *  inside its range from the camera center. */
    boolean enderkinesis_isInCameraRange(int chunkX, int chunkZ);

    /** The camera-storage chunk at the given pos, or null. */
    @Nullable
    LevelChunk enderkinesis_cameraChunkAt(int chunkX, int chunkZ);

    /** Arm the camera storage and re-center it. Chunks now outside the new range are
     *  pruned; the camera storage is marked active so the chunk-route mixin will start
     *  accepting incoming chunks for this area. Safe to call repeatedly — every
     *  call replaces the center / radius. */
    void enderkinesis_setCameraView(int chunkX, int chunkZ, int radius);

    /** Disarm the camera storage and drop every chunk it holds. Called at session
     *  end so memory and renderer fallbacks return to normal. */
    void enderkinesis_clearCameraStorage();

    /** Current number of distinct camera-storage chunks held. Used by the begin-fade
     *  adaptive hold to wait until enough of the target area has streamed before
     *  starting the fade-out. */
    int enderkinesis_cameraChunkCount();
}
