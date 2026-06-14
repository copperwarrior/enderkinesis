package org.shipwrights.enderkinesis.item

import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level

/**
 * Per-dimension SEND-orb index for the playSeededSound mixin — avoids walking every loaded BE
 * per fired sound. Stale entries (chunk unload, break) drop lazily on first miss; reloads
 * re-add via [TomeOrbBehavior.onLoad].
 */
object VentriloquismNetwork {

    /** Capture-bubble radius (blocks, world space) around a SEND orb. Sounds inside this
     *  sphere are mirrored to every receiver, preserving the offset from the SEND centre. */
    const val CAPTURE_RADIUS: Double = 7.0

    /** Squared radius for the mixin's per-sound distance test — saves a `sqrt` per orb. */
    const val CAPTURE_RADIUS_SQ: Double = CAPTURE_RADIUS * CAPTURE_RADIUS

    private val byDim: MutableMap<ResourceKey<Level>, MutableSet<BlockPos>> = ConcurrentHashMap()

    @JvmStatic
    fun register(level: ServerLevel, pos: BlockPos) {
        byDim.computeIfAbsent(level.dimension()) { ConcurrentHashMap.newKeySet() }.add(pos.immutable())
    }

    @JvmStatic
    fun unregister(level: ServerLevel, pos: BlockPos) {
        val set = byDim[level.dimension()] ?: return
        set.remove(pos)
        if (set.isEmpty()) byDim.remove(level.dimension())
    }

    /** Snapshot of registered SEND orbs for this dimension. Empty when no orb in this dim has
     *  any outgoing ventriloquism link — the mixin uses isEmpty as its fast-out. */
    @JvmStatic
    fun sendOrbs(level: ServerLevel): Collection<BlockPos> = byDim[level.dimension()] ?: emptyList()
}
