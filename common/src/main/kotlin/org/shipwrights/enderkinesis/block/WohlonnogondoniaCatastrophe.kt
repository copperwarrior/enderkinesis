package org.shipwrights.enderkinesis.block

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.level.storage.LevelResource
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.mixin.ChunkStorageWorkerAccessor
import org.shipwrights.enderkinesis.mixin.IOWorkerStorageAccessor
import org.shipwrights.enderkinesis.mixin.RegionFileStorageAccessor
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * Wipes Wohlon when the Mother Heart is forcibly removed. Hybrid live+marker design:
 * marker is written first so a JVM death between trigger and wipe still gets cleaned up
 * on next start. Single-player integrated servers stop after phase-1 kicks, so phases 2-3
 * run via [registerLifecycleHooks] on next launch.
 */
object WohlonnogondoniaCatastrophe {

    private val LOG = LogUtils.getLogger()

    private const val MARKER_FILE_NAME = "enderkinesis_wipe_wohlon.flag"

    private val GODLY_INTERVENTION: ResourceKey<DamageType> =
        ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("godly_intervention"))

    private val KICK_MESSAGE: Component =
        Component.literal("Ready to wake up already?")

    /** 30 s — far beyond vanilla's ~30-tick unload, so this only trips on exotic chunk pins. */
    private const val DRAIN_TIMEOUT_TICKS: Int = 600

    @Volatile private var wipeInProgress: Boolean = false

    @JvmStatic
    fun isWipeInProgress(): Boolean = wipeInProgress

    fun trigger(server: MinecraftServer) {
        if (wipeInProgress) return
        wipeInProgress = true

        LOG.warn("WohlonnogondoniaCatastrophe: Mother Heart destroyed — initiating godly intervention.")

        // Marker FIRST so JVM death between kick and wipe still triggers a next-start wipe.
        writeWipeMarker(server)

        val wohlonLevel = server.getLevel(Wohlonnogondonia.LEVEL_KEY)
        if (wohlonLevel != null) {
            val damageSource: DamageSource = wohlonLevel.damageSources().source(GODLY_INTERVENTION)
            // Snapshot — hurt/disconnect mutate the live collection during iteration.
            val players = wohlonLevel.players().toList()
            for (player in players) {
                player.hurt(damageSource, Float.MAX_VALUE)
                player.connection.disconnect(KICK_MESSAGE)
            }
            LOG.warn(
                "WohlonnogondoniaCatastrophe: killed and disconnected {} player(s); waiting for chunks to unload.",
                players.size,
            )
            scheduleDrainWatcher(server, wohlonLevel)
        } else {
            LOG.warn(
                "WohlonnogondoniaCatastrophe: Wohlonnogondonia level not loaded — skipping " +
                    "drain phase and wiping files directly.",
            )
            performWipe(server, wohlonLevel = null)
        }
    }

    /** Architectury doesn't expose deregister; the listener stays after `performWipe` but
     *  short-circuits on `wipeInProgress`. */
    private fun scheduleDrainWatcher(server: MinecraftServer, wohlonLevel: ServerLevel) {
        val startTick = server.tickCount
        TickEvent.SERVER_LEVEL_POST.register { level: ServerLevel ->
            if (level === wohlonLevel && wipeInProgress) {
                val loaded = level.chunkSource.chunkMap.size()
                val elapsed = server.tickCount - startTick
                if (loaded == 0 || elapsed > DRAIN_TIMEOUT_TICKS) {
                    LOG.warn(
                        "WohlonnogondoniaCatastrophe: drain complete (loadedChunks={}, ticksElapsed={}); wiping files.",
                        loaded, elapsed,
                    )
                    performWipe(server, wohlonLevel)
                }
            }
        }
    }

    private fun performWipe(server: MinecraftServer, wohlonLevel: ServerLevel?) {
        try {
            if (wohlonLevel != null) {
                // Flush before closing handles — otherwise the delete races the chunk-save thread.
                wohlonLevel.chunkSource.save(true)
                releaseRegionFileHandles(wohlonLevel)
            }

            val dimPath = wohlonDimensionPath(server)
            if (dimPath.exists()) {
                deleteRecursively(dimPath)
                LOG.warn("WohlonnogondoniaCatastrophe: deleted dimension directory {}.", dimPath)
            } else {
                LOG.warn("WohlonnogondoniaCatastrophe: dim path {} did not exist; nothing to delete.", dimPath)
            }

            markerPath(server).deleteIfExists()
            WohlonnogondoniaPortalManager.resetSessionStateForWipe()
        } catch (e: Exception) {
            LOG.error("WohlonnogondoniaCatastrophe: live wipe FAILED — marker remains, next start will retry.", e)
        } finally {
            wipeInProgress = false
            LOG.warn("WohlonnogondoniaCatastrophe: wipe complete — Wohlonnogondonia will regenerate on next portal entry.")
        }
    }

    /** Vanilla's `RegionFileStorage.close()` leaves cache entries pointing at closed handles —
     *  the next `getRegionFile()` would return a dead handle. Walk + close + clear the cache so
     *  callers lazily re-open fresh. */
    private fun releaseRegionFileHandles(level: ServerLevel) {
        val chunkMap = level.chunkSource.chunkMap
        val worker = (chunkMap as ChunkStorageWorkerAccessor).`enderkinesis$getWorker`()
        val storage = (worker as IOWorkerStorageAccessor).`enderkinesis$getStorage`()
        val cache = (storage as RegionFileStorageAccessor).`enderkinesis$getRegionCache`()

        synchronized(storage) {
            val iter = cache.values.iterator()
            while (iter.hasNext()) {
                val rf = iter.next()
                try {
                    rf.close()
                } catch (e: Exception) {
                    LOG.warn("WohlonnogondoniaCatastrophe: failed to close a RegionFile during wipe.", e)
                }
            }
            cache.clear()
        }
        LOG.warn("WohlonnogondoniaCatastrophe: released {} cached RegionFile handle(s).", cache.size)
    }

    /** SERVER_BEFORE_START detects the marker before MC opens the dim files — covers
     *  single-player where phase 1's kick stops the server before drain can run. */
    fun registerLifecycleHooks() {
        LifecycleEvent.SERVER_BEFORE_START.register { server ->
            val marker = markerPath(server)
            if (marker.exists()) {
                val dimPath = wohlonDimensionPath(server)
                LOG.warn(
                    "WohlonnogondoniaCatastrophe: wipe marker found at {} — deleting Wohlonnogondonia dim files at {}.",
                    marker, dimPath,
                )
                try {
                    if (dimPath.exists()) {
                        deleteRecursively(dimPath)
                    }
                    marker.deleteIfExists()
                    LOG.warn("WohlonnogondoniaCatastrophe: pre-start wipe complete.")
                } catch (e: Exception) {
                    LOG.error("WohlonnogondoniaCatastrophe: pre-start wipe FAILED — files may be partially deleted.", e)
                }
            }
        }
        LifecycleEvent.SERVER_STOPPED.register { _ ->
            wipeInProgress = false
        }
    }

    private fun writeWipeMarker(server: MinecraftServer) {
        val marker = markerPath(server)
        try {
            Files.createDirectories(marker.parent)
            Files.writeString(marker, "wipe-on-next-start\n")
            LOG.warn("WohlonnogondoniaCatastrophe: wipe marker written to {}.", marker)
        } catch (e: Exception) {
            LOG.error("WohlonnogondoniaCatastrophe: failed to write wipe marker at {}.", marker, e)
        }
    }

    private fun markerPath(server: MinecraftServer): Path =
        server.getWorldPath(LevelResource.ROOT).resolve(MARKER_FILE_NAME)

    private fun wohlonDimensionPath(server: MinecraftServer): Path {
        val id = Wohlonnogondonia.ID
        return server.getWorldPath(LevelResource.ROOT)
            .resolve("dimensions")
            .resolve(id.namespace)
            .resolve(id.path)
    }

    /** `Files.walk` opens a file-system stream — `use` so it closes before unlinking the root. */
    private fun deleteRecursively(root: Path) {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
