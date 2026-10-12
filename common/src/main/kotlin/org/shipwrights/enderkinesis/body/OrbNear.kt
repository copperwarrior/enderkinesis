package org.shipwrights.enderkinesis.body

import it.unimi.dsi.fastutil.longs.LongArrayList
import net.minecraft.world.level.Level
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * The cull for Orbs of Potential. An orb is never destroyed, so the orb table grows with each
 * Sselith globe that a player has loaded. The per-tick and per-frame users read the orbs near
 * a player only, from a list that is built again one time each second.
 */
internal object OrbNear {

    /** Blocks from a player to an orb's body. Above the largest render and interaction reach. */
    private const val RADIUS_SQ: Double = 160.0 * 160.0

    const val REBUILD_TICKS: Long = 20L

    /** One list for one level. Not thread-safe; each owner has its own lock or thread. */
    class Cache {
        private var levelIdentity = 0
        private var bucket = Long.MIN_VALUE
        private var ids = LongArray(0)

        fun invalidate() {
            bucket = Long.MIN_VALUE
        }

        fun get(level: Level, all: Iterable<Long>): LongArray {
            val nowBucket = level.gameTime / REBUILD_TICKS
            val identity = System.identityHashCode(level)
            if (identity != levelIdentity || nowBucket != bucket) {
                levelIdentity = identity
                bucket = nowBucket
                ids = filter(level, all)
            }
            return ids
        }
    }

    /** By body position, not by anchor: a player can carry an orb away from its anchor. */
    private fun filter(level: Level, all: Iterable<Long>): LongArray {
        val players = level.players()
        if (players.isEmpty()) return LongArray(0)
        val bodies = level.shipObjectWorld.allBodies
        val near = LongArrayList()
        for (id in all) {
            val pos = bodies.getById(id)?.kinematics?.position ?: continue
            for (player in players) {
                if (player.distanceToSqr(pos.x(), pos.y(), pos.z()) <= RADIUS_SQ) {
                    near.add(id)
                    break
                }
            }
        }
        return near.toLongArray()
    }
}
