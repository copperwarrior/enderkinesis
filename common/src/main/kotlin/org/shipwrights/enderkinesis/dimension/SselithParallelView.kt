package org.shipwrights.enderkinesis.dimension

import java.math.BigInteger
import java.util.UUID
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/**
 * The client's copy of the parallels in Sselith, written by the [SselithParallel.SYNC] receiver.
 * It holds no client-only types, so common mixins can read it on both sides.
 */
object SselithParallelView {

    // Client thread only: the receiver queues its writes onto the client thread.
    private val parallels = HashMap<UUID, BigInteger>()

    var localPlayer: UUID? = null

    val local: BigInteger get() = localPlayer?.let { parallelOf(it) } ?: BigInteger.ZERO

    fun parallelOf(player: UUID): BigInteger = parallels[player] ?: BigInteger.ZERO

    fun apply(replaceAll: Boolean, entries: Map<UUID, BigInteger>) {
        if (replaceAll) parallels.clear()
        for ((player, parallel) in entries) {
            if (parallel.signum() == 0) parallels.remove(player) else parallels[player] = parallel
        }
    }

    fun clear() {
        parallels.clear()
        localPlayer = null
    }

    /** True on the client for another player in Sselith whose parallel differs from the local player's. */
    fun isOtherParallel(entity: Entity): Boolean =
        entity is Player && !entity.isLocalPlayer && entity.level().isClientSide &&
            entity.level().dimension() == SselithRepertory.LEVEL_KEY &&
            parallelOf(entity.uuid) != local
}
