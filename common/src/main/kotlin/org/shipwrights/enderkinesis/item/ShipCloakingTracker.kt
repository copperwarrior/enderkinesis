package org.shipwrights.enderkinesis.item

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.server.MinecraftServer
import org.shipwrights.enderkinesis.registry.EKItems
import dev.architectury.event.events.common.PlayerEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.util.ShipBlockMove
import org.shipwrights.enderkinesis.util.ShipMoveListener

/** Server-side tracker for which ships are currently being cloaked by the Staff of
 *  Concealment, and by whom. One player can cloak at most one ship at a time; starting
 *  a new cloak releases any previous one by the same player. State transitions
 *  (start/stop) broadcast a [ConcealmentNetwork.S2C_CLOAK_UPDATE] packet to every
 *  player in the same dimension so their clients can drive the visual fade.
 *
 *  Cleanup is handled by:
 *  - The item's `releaseUsing` / `finishUsingItem` (normal hold-release).
 *  - Player disconnect (this object's [init] subscribes to [PlayerEvent.PLAYER_QUIT]).
 *  - A server tick check, for each path where vanilla stops the use with no `releaseUsing`
 *    (hotbar change, death, dimension change). */
object ShipCloakingTracker : ShipMoveListener {

    /** shipId → (player UUID, dimension the ship is in). Concurrent for safety against
     *  packet-thread broadcasts overlapping with game-tick cleanup. */
    private val activeCloaks: MutableMap<Long, CloakSession> = ConcurrentHashMap()

    /** [targetPos] is the shipyard block the staff hit; the cloak follows it across ships. */
    private data class CloakSession(val cloakerUuid: UUID, val dimension: ResourceKey<Level>, val targetPos: BlockPos)

    fun init() {
        PlayerEvent.PLAYER_QUIT.register { player -> releaseAllBy(player) }
        // A cloak update goes only to the players in the dimension at that time.
        PlayerEvent.PLAYER_JOIN.register(::syncTo)
        PlayerEvent.CHANGE_DIMENSION.register { player, _, _ -> syncTo(player) }
        TickEvent.SERVER_POST.register(TickEvent.Server(::releaseAbandoned))
        LifecycleEvent.SERVER_STOPPED.register { _ -> activeCloaks.clear() }
    }

    /** Vanilla calls `stopUsingItem`, not `releaseUsing`, on a hotbar change, a death and a
     *  dimension change. Without this check the ship stays cloaked with no staff in use. */
    private fun releaseAbandoned(server: MinecraftServer) {
        if (activeCloaks.isEmpty()) return
        val iter = activeCloaks.entries.iterator()
        while (iter.hasNext()) {
            val (shipId, session) = iter.next()
            val player = server.playerList.getPlayer(session.cloakerUuid)
            val holds = player != null && player.isUsingItem &&
                player.useItem.`is`(EKItems.STAFF_OF_CONCEALMENT.get()) &&
                player.level().dimension() == session.dimension
            if (holds) continue
            iter.remove()
            val level = server.getLevel(session.dimension) ?: continue
            ConcealmentNetwork.broadcastCloakUpdate(level, shipId, cloaking = false)
        }
    }

    /** Begin cloaking [shipId]. Releases any prior cloak by [player] first so a player
     *  can't sustain two cloaks via dropped items / dupe scenarios. */
    fun startCloak(level: ServerLevel, shipId: Long, targetPos: BlockPos, player: ServerPlayer) {
        // Drop any previous cloak by this player.
        val prior = activeCloaks.entries.firstOrNull { it.value.cloakerUuid == player.uuid }
        if (prior != null) {
            activeCloaks.remove(prior.key)
            ConcealmentNetwork.broadcastCloakUpdate(level, prior.key, cloaking = false)
        }
        activeCloaks[shipId] = CloakSession(player.uuid, level.dimension(), targetPos.immutable())
        ConcealmentNetwork.broadcastCloakUpdate(level, shipId, cloaking = true)
    }

    /** Stop whatever cloak this player currently holds. No-op if they have none. */
    fun stopCloak(level: ServerLevel, player: ServerPlayer) {
        val mine = activeCloaks.entries.firstOrNull { it.value.cloakerUuid == player.uuid } ?: return
        activeCloaks.remove(mine.key)
        ConcealmentNetwork.broadcastCloakUpdate(level, mine.key, cloaking = false)
    }

    /** The cloak stays on the part that has the targeted block. */
    override fun onShipBlocksMoved(level: ServerLevel, move: ShipBlockMove) {
        val session = activeCloaks[move.fromShipId] ?: return
        if (session.dimension != level.dimension()) return
        if (!move.fromShipDeleted && !move.contains(session.targetPos)) return
        activeCloaks.remove(move.fromShipId)
        if (activeCloaks.containsKey(move.toShipId)) {
            ConcealmentNetwork.broadcastCloakUpdate(level, move.fromShipId, cloaking = false)
            return
        }
        activeCloaks[move.toShipId] = session.copy(targetPos = move.mapBlock(session.targetPos))
        ConcealmentNetwork.broadcastCloakTransfer(level, move.fromShipId, move.toShipId)
    }

    /** True if [shipId] is currently being cloaked by someone. Server queries only —
     *  client visuals read [org.shipwrights.enderkinesis.client.ClientShipCloakingState]. */
    fun isCloaking(shipId: Long): Boolean = activeCloaks.containsKey(shipId)

    private fun syncTo(player: ServerPlayer) {
        val dimension = player.level().dimension()
        for ((shipId, session) in activeCloaks) {
            if (session.dimension == dimension) ConcealmentNetwork.sendCloakUpdate(player, shipId, cloaking = true)
        }
    }

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
