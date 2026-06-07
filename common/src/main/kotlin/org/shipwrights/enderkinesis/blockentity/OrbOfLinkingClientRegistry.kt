package org.shipwrights.enderkinesis.blockentity

import net.minecraft.world.level.Level
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-client-level set of loaded [OrbOfLinkingBlockEntity]s so [org.shipwrights.enderkinesis.client.OrbOfLinkingClient]
 * can iterate SEND orbs each tick without scanning every loaded chunk.
 *
 * The BE registers in [setLevel] when its level is a client level, and unregisters in
 * [setRemoved] (chunk unload, block break). The registry stores *all* roles — UNASSIGNED,
 * SEND, RECEIVE — and the client tick filters by role; this saves the BE having to
 * register/unregister on every role flip.
 *
 * Concurrent because client-render thread reads while the client-tick thread mutates on
 * chunk load/unload. CHM + a backing CHM-set gives weakly-consistent iteration with no
 * lock contention.
 */
object OrbOfLinkingClientRegistry {

    private val byLevel: ConcurrentHashMap<Level, MutableSet<OrbOfLinkingBlockEntity>> =
        ConcurrentHashMap()

    fun register(level: Level, be: OrbOfLinkingBlockEntity) {
        byLevel.computeIfAbsent(level) {
            Collections.newSetFromMap(ConcurrentHashMap())
        }.add(be)
    }

    fun unregister(level: Level, be: OrbOfLinkingBlockEntity) {
        byLevel[level]?.remove(be)
    }

    /** All loaded orb BEs in [level]. The caller should filter by role as needed. */
    fun sendOrbs(level: Level): Collection<OrbOfLinkingBlockEntity> =
        byLevel[level] ?: emptyList()
}
