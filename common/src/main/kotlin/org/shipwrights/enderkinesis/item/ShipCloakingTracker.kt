package org.shipwrights.enderkinesis.item

import dev.architectury.event.events.common.PlayerEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

/** Server-side tracker for which ships are currently being cloaked by the Staff of
 *  Concealment, and by whom. One player can cloak at most one ship at a time; starting
 *  a new cloak releases any previous one by the same player. State transitions
 *  (start/stop) broadcast a [ConcealmentNetwork.S2C_CLOAK_UPDATE] packet to every
 *  player in the same dimension so their clients can drive the visual fade.
 *
 *  Cleanup is handled by:
 *  - The item's `releaseUsing` / `finishUsingItem` (normal hold-release).
 *  - Player disconnect (this object's [init] subscribes to [PlayerEvent.PLAYER_QUIT]).
 *  - Player death is handled implicitly — vanilla calls `releaseUsing` when the
 *    player stops using the item, which death triggers. */
object ShipCloakingTracker {

    /** shipId → (player UUID, dimension the ship is in). Concurrent for safety against
     *  packet-thread broadcasts overlapping with game-tick cleanup. */
    private val activeCloaks: MutableMap<Long, CloakSession> = ConcurrentHashMap()

    private data class CloakSession(val cloakerUuid: UUID, val dimension: ResourceKey<Level>)

    fun init() {
        PlayerEvent.PLAYER_QUIT.register { player -> releaseAllBy(player) }
    }

    /** Begin cloaking [shipId]. Releases any prior cloak by [player] first so a player
     *  can't sustain two cloaks via dropped items / dupe scenarios. */
    fun startCloak(level: ServerLevel, shipId: Long, player: ServerPlayer) {
        // Drop any previous cloak by this player.
        val prior = activeCloaks.entries.firstOrNull { it.value.cloakerUuid == player.uuid }
        if (prior != null) {
            activeCloaks.remove(prior.key)
            ConcealmentNetwork.broadcastCloakUpdate(level, prior.key, cloaking = false)
        }
        activeCloaks[shipId] = CloakSession(player.uuid, level.dimension())
        ConcealmentNetwork.broadcastCloakUpdate(level, shipId, cloaking = true)
    }

    /** Stop whatever cloak this player currently holds. No-op if they have none. */
    fun stopCloak(level: ServerLevel, player: ServerPlayer) {
        val mine = activeCloaks.entries.firstOrNull { it.value.cloakerUuid == player.uuid } ?: return
        activeCloaks.remove(mine.key)
        ConcealmentNetwork.broadcastCloakUpdate(level, mine.key, cloaking = false)
    }

    /** True if [shipId] is currently being cloaked by someone. Server queries only —
     *  client visuals read [org.shipwrights.enderkinesis.client.ClientShipCloakingState]. */
    fun isCloaking(shipId: Long): Boolean = activeCloaks.containsKey(shipId)

    /** Iterates active cloak ship IDs in [dimension] for late-join sync (when a new
     *  player enters a dimension that already has active cloaks). */
    fun activeCloaksIn(dimension: ResourceKey<Level>): List<Long> =
        activeCloaks.entries.filter { it.value.dimension == dimension }.map { it.key }

    /** Called from PLAYER_QUIT — releases every cloak the leaving player owns. We
     *  can't get the player's ServerLevel here reliably (they're disconnecting), so
     *  we broadcast across the dimension recorded in the session. */
    private fun releaseAllBy(player: net.minecraft.world.entity.player.Player) {
        val uuid = player.uuid
        val server = player.server ?: return
        val mine = activeCloaks.entries.filter { it.value.cloakerUuid == uuid }
        for (entry in mine) {
            activeCloaks.remove(entry.key)
            val level = server.getLevel(entry.value.dimension) ?: continue
            ConcealmentNetwork.broadcastCloakUpdate(level, entry.key, cloaking = false)
        }
    }
}
