package org.shipwrights.enderkinesis.body

import java.util.concurrent.ConcurrentHashMap
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level

/**
 * Server-side per-dimension registry of Wand-of-Ga'Mylan cages keyed by
 * their VS body ID. Live cache; [GamylanCagePersistence] is the on-disk
 * source of truth. Mutations that need to survive a restart go through
 * [add] / [remove] (which mark the SavedData dirty) — record fields
 * that mutate in place (hp, fadeComplete, lastDamageTick, droppedItems)
 * are dirtied by the caller after the write via
 * [GamylanCagePersistence.markDirty].
 *
 * Thread-safety: server tick is single-threaded but VS2 phys-tick fires
 * off-thread, and the renderer may snapshot the registry from a worker
 * if any future code paths read concurrently. The [ConcurrentHashMap]
 * keeps single-key writes safe; callers that need consistent multi-key
 * snapshots use [snapshot].
 */
object GamylanCageRegistry {

    private val byDimension: ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<Long, GamylanCageData>> =
        ConcurrentHashMap()

    fun add(level: ServerLevel, data: GamylanCageData) {
        byDimension.computeIfAbsent(level.dimension()) { ConcurrentHashMap() }[data.bodyId] = data
        val store = GamylanCagePersistence.forLevel(level)
        store.records[data.bodyId] = data
        store.setDirty()
    }

    fun remove(level: ServerLevel, bodyId: Long): GamylanCageData? {
        val removed = byDimension[level.dimension()]?.remove(bodyId)
        if (removed != null) {
            val store = GamylanCagePersistence.forLevel(level)
            store.records.remove(bodyId)
            store.setDirty()
        }
        return removed
    }

    /** Hydration path called from SERVER_LEVEL_LOAD. Populates the
     *  in-memory cache from the SavedData without re-dirtying the
     *  store — the records were just read from disk. */
    fun restore(level: ServerLevel, data: GamylanCageData) {
        byDimension.computeIfAbsent(level.dimension()) { ConcurrentHashMap() }[data.bodyId] = data
    }

    fun get(level: ServerLevel, bodyId: Long): GamylanCageData? {
        return byDimension[level.dimension()]?.get(bodyId)
    }

    /** Snapshot of every cage in [level] for a full-list sync. */
    fun snapshot(level: ServerLevel): List<GamylanCageData> {
        val perDim = byDimension[level.dimension()] ?: return emptyList()
        return ArrayList(perDim.values)
    }

    /** All dim/data pairs — for cross-level scans (release on player
     *  attack, etc.). */
    fun allEntries(): Sequence<Pair<ResourceKey<Level>, GamylanCageData>> = sequence {
        for ((dim, map) in byDimension) {
            for (data in map.values) yield(dim to data)
        }
    }

    /** Iterate every cage whose [GamylanCageData.dimId] equals
     *  [vsDimId]. Off-thread safe: callers are the VS2 phys-tick
     *  listener which holds no MC locks. */
    /** True when no dimension has a cage. For the per-move fast exit; any thread. */
    fun hasNone(): Boolean {
        for (cages in byDimension.values) if (cages.isNotEmpty()) return false
        return true
    }

    fun forEachInVsDim(vsDimId: String, action: (GamylanCageData) -> Unit) {
        for (map in byDimension.values) {
            for (data in map.values) {
                if (data.dimId == vsDimId) action(data)
            }
        }
    }

    /** In singleplayer the JVM lives across worlds, and VS2 ids start again in each save. */
    fun clearSession() {
        byDimension.clear()
    }
}
