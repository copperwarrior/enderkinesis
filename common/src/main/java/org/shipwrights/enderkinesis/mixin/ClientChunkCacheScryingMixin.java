package org.shipwrights.enderkinesis.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import org.shipwrights.enderkinesis.scrying.ScryingChunkCacheBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Parallel camera-chunk storage on top of {@link ClientChunkCache}. Vanilla's main
 * storage stays anchored at the player; this mixin maintains a second map of chunks
 * around the scrying target. The two coexist:
 *
 * <ul>
 *   <li>Incoming chunk packets at positions outside main storage but inside the
 *       camera-storage range are diverted into the camera map (and never compete with
 *       main storage's slot indexing).</li>
 *   <li>{@code getChunk} consults the camera map first; if it has a match for the
 *       queried position, that's returned, otherwise vanilla's main-storage lookup
 *       proceeds normally.</li>
 *   <li>{@code drop} removes from the camera map too, so a chunk explicitly dropped
 *       by vanilla doesn't linger in our shadow copy.</li>
 * </ul>
 *
 * Side effect: source-area rendering is completely untouched during a scry session —
 * the main storage and its {@code viewCenter} are never modified. End of session
 * just clears the camera map.
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheScryingMixin implements ScryingChunkCacheBridge {

    @Shadow @Final
    ClientLevel level;

    /** Camera-chunk storage, keyed by {@link ChunkPos#asLong(int, int)}. Holds chunks
     *  for the scrying-target area; vanilla's main storage doesn't see them. */
    @Unique
    private final Map<Long, LevelChunk> enderkinesis$cameraChunks = new ConcurrentHashMap<>();

    @Unique
    private volatile int enderkinesis$cameraCenterX = 0;

    @Unique
    private volatile int enderkinesis$cameraCenterZ = 0;

    @Unique
    private volatile int enderkinesis$cameraRadius = 0;

    @Unique
    private volatile boolean enderkinesis$cameraActive = false;

    @Unique
    private static final Logger ENDERKINESIS$LOGGER = LoggerFactory.getLogger("EnderkinesisScryingChunkCache");

    @Unique
    private int enderkinesis$routedThisSession = 0;

    @Unique
    private int enderkinesis$fallbackHitsThisSession = 0;

    @Override
    public boolean enderkinesis_isInCameraRange(int chunkX, int chunkZ) {
        if (!this.enderkinesis$cameraActive) return false;
        return Math.abs(chunkX - this.enderkinesis$cameraCenterX) <= this.enderkinesis$cameraRadius
            && Math.abs(chunkZ - this.enderkinesis$cameraCenterZ) <= this.enderkinesis$cameraRadius;
    }

    @Override
    @Nullable
    public LevelChunk enderkinesis_cameraChunkAt(int chunkX, int chunkZ) {
        return this.enderkinesis$cameraChunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    @Override
    public void enderkinesis_setCameraView(int chunkX, int chunkZ, int radius) {
        this.enderkinesis$cameraCenterX = chunkX;
        this.enderkinesis$cameraCenterZ = chunkZ;
        this.enderkinesis$cameraRadius = radius;
        this.enderkinesis$cameraActive = true;
        this.enderkinesis$routedThisSession = 0;
        this.enderkinesis$fallbackHitsThisSession = 0;

        // Prune chunks that just fell out of range — typically a no-op on first
        // arm; matters when the target moves between chunks during a session.
        Iterator<Map.Entry<Long, LevelChunk>> iter = this.enderkinesis$cameraChunks.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Long, LevelChunk> entry = iter.next();
            long key = entry.getKey();
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (Math.abs(cx - chunkX) > radius || Math.abs(cz - chunkZ) > radius) {
                iter.remove();
            }
        }
    }

    @Override
    public int enderkinesis_cameraChunkCount() {
        return this.enderkinesis$cameraChunks.size();
    }

    @Override
    public void enderkinesis_clearCameraStorage() {
        if (this.enderkinesis$cameraActive) {
            ENDERKINESIS$LOGGER.info(
                "Camera storage clearing — routed {} chunks, getChunk fallback hit {} times this session",
                this.enderkinesis$routedThisSession,
                this.enderkinesis$fallbackHitsThisSession
            );
        }
        this.enderkinesis$cameraActive = false;
        this.enderkinesis$cameraChunks.clear();
    }

    /**
     * Route chunk packets for positions inside the camera range into the camera map.
     * Cancels vanilla's main-storage write so the chunk lands ONLY in the parallel
     * storage. Returns the new chunk so {@code ClientPacketListener.updateLevelChunk}
     * still calls {@code level.onChunkLoaded} downstream — keeps tint caches and
     * entity ticking in sync.
     */
    @Inject(method = "replaceWithPacketData", at = @At("HEAD"), cancellable = true)
    private void enderkinesis$routeChunkToCameraStorage(
        int x, int z,
        FriendlyByteBuf buf,
        CompoundTag tag,
        Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
        CallbackInfoReturnable<LevelChunk> cir
    ) {
        if (!this.enderkinesis_isInCameraRange(x, z)) return;
        // If main storage's range covers this chunk too (camera area overlaps the
        // player's normal view — close-range scry), let vanilla put the chunk in main
        // storage as it normally would. The getChunk fallback handles the reverse
        // lookup: camera-only chunks come from cameraChunks, overlap chunks come from
        // main. Without this guard we'd cancel vanilla's write, the chunk would live
        // only in cameraChunks, and clearing cameraChunks on session end would leave
        // the renderer querying main storage for chunks that aren't there — black
        // terrain after the fade-back completes.
        //
        // We replicate ClientChunkCache.Storage.inRange via player chunk pos +
        // effectiveRenderDistance + 3 (the same `max(2, vd) + 3` storage uses to size
        // its radius). The storage's viewCenter is kept in sync with the player chunk
        // pos by vanilla's SetChunkCacheCenter packets, so this matches the actual
        // in-range gate that would have caught the chunk down the vanilla path.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            net.minecraft.world.level.ChunkPos pc = mc.player.chunkPosition();
            int mainRadius = Math.max(2, mc.options.getEffectiveRenderDistance()) + 3;
            if (Math.abs(x - pc.x) <= mainRadius && Math.abs(z - pc.z) <= mainRadius) {
                return;
            }
        }
        ChunkPos pos = new ChunkPos(x, z);
        LevelChunk chunk = new LevelChunk(this.level, pos);
        chunk.replaceWithPacketData(buf, tag, consumer);
        this.enderkinesis$cameraChunks.put(pos.toLong(), chunk);
        if (this.enderkinesis$routedThisSession == 0) {
            ENDERKINESIS$LOGGER.info("First camera-storage route: chunk ({}, {})", x, z);
        }
        this.enderkinesis$routedThisSession++;
        // Vanilla's replaceWithPacketData calls level.onChunkLoaded(pos) before returning.
        // Our HEAD-cancel skips that, so the tint-cache invalidation and entity-storage
        // ticking start never happen for camera-storage chunks — terrain renders with
        // stale grass / leaves colours and entities in those chunks never tick. Run it
        // manually here so the camera area finishes its load handshake.
        this.level.onChunkLoaded(pos);
        // Mark every render section in this chunk's column dirty so the renderer
        // rebuilds against the just-arrived camera-storage chunk rather than holding
        // its cached empty/old mesh. Section-Y range covers the entire build height.
        if (mc.levelRenderer != null) {
            int minSection = SectionPos.blockToSectionCoord(this.level.getMinBuildHeight());
            int maxSection = SectionPos.blockToSectionCoord(this.level.getMaxBuildHeight() - 1);
            for (int y = minSection; y <= maxSection; y++) {
                mc.levelRenderer.setSectionDirtyWithNeighbors(x, y, z);
            }
        }
        cir.setReturnValue(chunk);
    }

    /**
     * Falls back to the camera map for positions vanilla's main storage doesn't
     * cover (or wouldn't have a valid chunk at). HEAD-first lookup is fine because
     * source-area positions are never in camera range, so the fallback short-circuits
     * for them and vanilla's main-storage path runs untouched.
     */
    @Inject(
        method = "getChunk(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void enderkinesis$lookupCameraStorage(
        int x, int z, ChunkStatus status, boolean load,
        CallbackInfoReturnable<LevelChunk> cir
    ) {
        if (!this.enderkinesis_isInCameraRange(x, z)) return;
        LevelChunk chunk = this.enderkinesis$cameraChunks.get(ChunkPos.asLong(x, z));
        if (chunk != null) {
            if (this.enderkinesis$fallbackHitsThisSession == 0) {
                ENDERKINESIS$LOGGER.info("First camera-storage fallback hit: chunk ({}, {})", x, z);
            }
            this.enderkinesis$fallbackHitsThisSession++;
            cir.setReturnValue(chunk);
        }
    }

    /** Mirror vanilla's drop semantics for the camera map. */
    @Inject(method = "drop(II)V", at = @At("HEAD"))
    private void enderkinesis$dropCameraChunk(int x, int z, CallbackInfo ci) {
        this.enderkinesis$cameraChunks.remove(ChunkPos.asLong(x, z));
    }
}
