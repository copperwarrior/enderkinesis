package org.shipwrights.enderkinesis.block

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.chunk.storage.EntityStorage
import net.minecraft.world.level.chunk.storage.IOWorker
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.storage.LevelResource
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.mixin.ChunkStorageWorkerAccessor
import org.shipwrights.enderkinesis.mixin.DimensionDataStorageCacheAccessor
import org.shipwrights.enderkinesis.mixin.EntityStorageWorkerAccessor
import org.shipwrights.enderkinesis.mixin.IOWorkerStorageAccessor
import org.shipwrights.enderkinesis.mixin.PersistentEntitySectionManagerStorageAccessor
import org.shipwrights.enderkinesis.mixin.RegionFileStorageAccessor
import org.shipwrights.enderkinesis.mixin.SectionStorageAccessor
import org.shipwrights.enderkinesis.mixin.ServerLevelEntityManagerAccessor
import org.shipwrights.enderkinesis.util.ServerTicks
import org.valkyrienskies.mod.common.assembly.ShipAssembler
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
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

    private const val RAIDS_DATA_PREFIX = "raids"

    private val GODLY_INTERVENTION: ResourceKey<DamageType> =
        ResourceKey.create(Registries.DAMAGE_TYPE, EnderkinesisMod.id("godly_intervention"))

    private val KICK_MESSAGE: Component =
        Component.literal("Ready to wake up already?")

    /** 30 s — far beyond vanilla's ~30-tick unload, so this only trips on exotic chunk pins. */
    private const val DRAIN_TIMEOUT_TICKS: Int = 600

    @Volatile private var wipeInProgress: Boolean = false

    /** Server tick at which the drain started; -1 when no drain runs. Server thread only. */
    private var drainStartTick: Int = -1

    /** Set by the pre-start wipe, which runs before the levels and VS2 exist. */
    @Volatile private var wipedBeforeStart: Boolean = false

    private const val WIPE_DATA_NAME = "enderkinesis_wohlon_wipes"

    @JvmStatic
    fun isWipeInProgress(): Boolean = wipeInProgress

    /** A block read in [level] loads its chunk, and a loaded chunk stops the drain before the wipe. */
    fun isDraining(level: ServerLevel): Boolean =
        wipeInProgress && level.dimension() == Wohlonnogondonia.LEVEL_KEY

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
            deleteWohlonShips(wohlonLevel)
            drainStartTick = server.tickCount
        } else {
            performWipe(server, wohlonLevel = null)
        }
    }

    private fun tickDrain(level: ServerLevel) {
        if (drainStartTick < 0 || !wipeInProgress) return
        if (level.dimension() != Wohlonnogondonia.LEVEL_KEY) return
        val server = level.server
        val loaded = level.chunkSource.chunkMap.size()
        val elapsed = server.tickCount - drainStartTick
        if (loaded != 0 && elapsed <= DRAIN_TIMEOUT_TICKS) return
        drainStartTick = -1
        performWipe(server, level)
    }

    /** The ship data is in the overworld's VS2 store, not in the dimension directory. */
    private fun deleteWohlonShips(wohlonLevel: ServerLevel) {
        val dimId = wohlonLevel.dimensionId
        val ships = wohlonLevel.shipObjectWorld.allShips.filter { it.chunkClaimDimension == dimId }
        for (ship in ships) {
            try {
                ShipAssembler.deleteShip(wohlonLevel, ship, false, false)
            } catch (e: Exception) {
                LOG.error("WohlonnogondoniaCatastrophe: failed to delete ship {}.", ship.id, e)
            }
        }
    }

    /** State outside the dimension directory that points into the wiped terrain. */
    private fun afterWipe(server: MinecraftServer) {
        WohlonnogondoniaPortalManager.resetSessionStateForWipe(server)
        val data = wipeData(server)
        data.epoch++
        data.setDirty()
    }

    private fun wipeData(server: MinecraftServer): WipeData =
        server.overworld().dataStorage.computeIfAbsent({ tag -> WipeData.load(tag) }, { WipeData() }, WIPE_DATA_NAME)

    private fun onPlayerQuit(player: ServerPlayer) {
        if (player.level().dimension() != Wohlonnogondonia.LEVEL_KEY) return
        val data = wipeData(player.server)
        data.lastSeen[player.uuid] = data.epoch
        data.setDirty()
    }

    /** A player who logged out in Wohlon before a wipe comes back into new terrain at the old
     *  position, possibly inside a block. A dead player respawns at the spawn point without help. */
    private fun onPlayerJoin(player: ServerPlayer) {
        val server = player.server
        val data = wipeData(server)
        val seen = data.lastSeen.remove(player.uuid) ?: return
        data.setDirty()
        if (seen >= data.epoch) return
        if (player.level().dimension() != Wohlonnogondonia.LEVEL_KEY) return
        ServerTicks.runAfter(server, 1) {
            if (!player.hasDisconnected() && player.isAlive) moveToSpawn(player)
        }
    }

    private fun moveToSpawn(player: ServerPlayer) {
        val server = player.server
        val respawnLevel = server.getLevel(player.respawnDimension)
            ?.takeIf { it.dimension() != Wohlonnogondonia.LEVEL_KEY }
        val respawnPos = player.respawnPosition
        if (respawnLevel != null && respawnPos != null) {
            // `true`: a respawn anchor keeps its charge.
            val spot = Player.findRespawnPositionAndUseSpawnBlock(
                respawnLevel, respawnPos, player.respawnAngle, player.isRespawnForced, true,
            ).orElse(null)
            if (spot != null) {
                player.teleportTo(respawnLevel, spot.x, spot.y, spot.z, player.yRot, player.xRot)
                return
            }
        }
        val overworld = server.overworld()
        val top = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, overworld.sharedSpawnPos)
        player.teleportTo(overworld, top.x + 0.5, top.y.toDouble(), top.z + 0.5, overworld.sharedSpawnAngle, 0f)
    }

    /** `epoch` counts the wipes; `lastSeen` is the epoch at which a player logged out in Wohlon. */
    private class WipeData : SavedData() {
        var epoch: Int = 0
        val lastSeen: MutableMap<UUID, Int> = HashMap()

        override fun save(tag: CompoundTag): CompoundTag {
            tag.putInt("epoch", epoch)
            val list = ListTag()
            for ((uuid, seen) in lastSeen) {
                val entry = CompoundTag()
                entry.putUUID("uuid", uuid)
                entry.putInt("epoch", seen)
                list.add(entry)
            }
            tag.put("lastSeen", list)
            return tag
        }

        companion object {
            fun load(tag: CompoundTag): WipeData {
                val data = WipeData()
                data.epoch = tag.getInt("epoch")
                val list = tag.getList("lastSeen", Tag.TAG_COMPOUND.toInt())
                for (i in 0 until list.size) {
                    val entry = list.getCompound(i)
                    data.lastSeen[entry.getUUID("uuid")] = entry.getInt("epoch")
                }
                return data
            }
        }
    }

    private fun performWipe(server: MinecraftServer, wohlonLevel: ServerLevel?) {
        try {
            if (wohlonLevel != null) {
                // Flush before closing handles — otherwise the delete races the save threads.
                wohlonLevel.chunkSource.save(true)
                (wohlonLevel as ServerLevelEntityManagerAccessor).`enderkinesis$getEntityManager`().saveAll()
                for ((name, worker) in storageWorkers(wohlonLevel)) releaseRegionFileHandles(name, worker)
            }

            val dimPath = wohlonDimensionPath(server)
            if (dimPath.exists()) {
                deleteRecursively(dimPath)
            }

            if (wohlonLevel != null) forgetCachedState(wohlonLevel, dimPath)

            markerPath(server).deleteIfExists()
            afterWipe(server)
            LOG.warn("WohlonnogondoniaCatastrophe: wipe complete — Wohlonnogondonia will regenerate on next portal entry.")
        } catch (e: Exception) {
            LOG.error("WohlonnogondoniaCatastrophe: live wipe FAILED — marker remains, next start will retry.", e)
        } finally {
            wipeInProgress = false
        }
    }

    /** Chunks, entities and POI each have their own IOWorker with open region files. */
    private fun storageWorkers(level: ServerLevel): List<Pair<String, IOWorker>> {
        val chunkMap = level.chunkSource.chunkMap
        val entityManager = (level as ServerLevelEntityManagerAccessor).`enderkinesis$getEntityManager`()
        val entityStorage =
            (entityManager as PersistentEntitySectionManagerStorageAccessor).`enderkinesis$getPermanentStorage`()
        check(entityStorage is EntityStorage) { "entity storage is ${entityStorage.javaClass.name}, not EntityStorage" }
        return listOf(
            "region" to (chunkMap as ChunkStorageWorkerAccessor).`enderkinesis$getWorker`(),
            "entities" to (entityStorage as EntityStorageWorkerAccessor).`enderkinesis$getWorker`(),
            "poi" to (level.chunkSource.poiManager as SectionStorageAccessor).`enderkinesis$getWorker`(),
        )
    }

    /** Vanilla's `RegionFileStorage.close()` leaves cache entries pointing at closed handles —
     *  the next `getRegionFile()` would return a dead handle. Walk + close + clear the cache so
     *  callers lazily re-open fresh. */
    private fun releaseRegionFileHandles(name: String, worker: IOWorker) {
        worker.synchronize(true).join()
        val storage = (worker as IOWorkerStorageAccessor).`enderkinesis$getStorage`()
        val cache = (storage as RegionFileStorageAccessor).`enderkinesis$getRegionCache`()

        val released: Int
        synchronized(storage) {
            released = cache.size
            for (rf in cache.values) {
                try {
                    rf.close()
                } catch (e: Exception) {
                    LOG.warn("WohlonnogondoniaCatastrophe: failed to close a {} RegionFile during wipe.", name, e)
                }
            }
            cache.clear()
        }
    }

    /** The level stays open after the delete, so state that mirrors the deleted files must go too. */
    private fun forgetCachedState(level: ServerLevel, dimPath: Path) {
        // `SavedData.save` does not make the directory; without it each later save fails.
        Files.createDirectories(dimPath.resolve("data"))

        val poi = level.chunkSource.poiManager as SectionStorageAccessor
        poi.`enderkinesis$getStorage`().clear()
        poi.`enderkinesis$getDirty`().clear()

        // `ServerLevel.raids` holds its instance by reference; a removed entry would not save again.
        val savedData = (level.dataStorage as DimensionDataStorageCacheAccessor).`enderkinesis$getCache`()
        savedData.keys.removeIf { !it.startsWith(RAIDS_DATA_PREFIX) }
    }

    /** SERVER_BEFORE_START detects the marker before MC opens the dim files — covers
     *  single-player where phase 1's kick stops the server before drain can run. */
    fun registerLifecycleHooks() {
        LifecycleEvent.SERVER_BEFORE_START.register { server ->
            val marker = markerPath(server)
            if (marker.exists()) {
                val dimPath = wohlonDimensionPath(server)
                try {
                    if (dimPath.exists()) {
                        deleteRecursively(dimPath)
                    }
                    marker.deleteIfExists()
                    wipedBeforeStart = true
                    LOG.warn("WohlonnogondoniaCatastrophe: pre-start wipe complete.")
                } catch (e: Exception) {
                    LOG.error("WohlonnogondoniaCatastrophe: pre-start wipe FAILED — files may be partially deleted.", e)
                }
            }
        }
        LifecycleEvent.SERVER_STARTED.register { server ->
            if (wipedBeforeStart) {
                wipedBeforeStart = false
                server.getLevel(Wohlonnogondonia.LEVEL_KEY)?.let(::deleteWohlonShips)
                afterWipe(server)
            }
        }
        // Registered one time: Architectury has no deregister, and a listener for each trigger leaks.
        TickEvent.SERVER_LEVEL_POST.register(::tickDrain)
        PlayerEvent.PLAYER_QUIT.register(::onPlayerQuit)
        PlayerEvent.PLAYER_JOIN.register(::onPlayerJoin)
        LifecycleEvent.SERVER_STOPPED.register { _ ->
            wipeInProgress = false
            drainStartTick = -1
        }
    }

    private fun writeWipeMarker(server: MinecraftServer) {
        val marker = markerPath(server)
        try {
            Files.createDirectories(marker.parent)
            Files.writeString(marker, "wipe-on-next-start\n")
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
