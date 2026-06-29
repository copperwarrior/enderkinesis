package org.shipwrights.enderkinesis.scrying

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.shipwrights.enderkinesis.item.ScryingClientNetwork
import org.slf4j.LoggerFactory
import org.valkyrienskies.mod.common.getShipManagingPos
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val LOG = LoggerFactory.getLogger("EnderkinesisScryingSession")

/**
 * Server-side bookkeeping for active scrying sessions. One session per player.
 *
 * **Mount** force-loads a square of chunks around the target's world position via a
 * session-scoped [ScryingTicketType.SCRYING] ticket per chunk and sends the BEGIN
 * packet so the client can arm its parallel camera-chunk storage. The per-tick loop
 * streams each chunk via `ClientboundLevelChunkWithLightPacket` as it reaches FULL
 * status; on the client side the chunk-cache mixin routes those packets into the
 * camera storage instead of overwriting source-area slots.
 *
 * **No anchor entity, no [ServerPlayer.camera] write, no client-side viewCenter
 * displacement.** Vanilla's main storage stays anchored at the player throughout the
 * session. The camera storage on the client carries the target-area chunks
 * independently; the renderer falls back to it for positions the main storage
 * doesn't cover. Source-area rendering is untouched.
 *
 * **Unmount** releases tickets. There's no source re-stream and no forget-chunk
 * traffic — the client clears its camera storage locally when [ScryingClient.endScrying]
 * runs, and the main storage was never disturbed.
 */
object ScryingSessionManager {

    /** Vanilla's maximum view-distance setting. Clamp here just to refuse ridiculous
     *  values; the actual radius used is the server's `playerList.viewDistance`. */
    private const val MAX_SESSION_VIEW_DISTANCE: Int = 32

    data class Session(
        val player: ServerPlayer,
        val sourceShipyardPos: BlockPos,
        val targetShipyardPos: BlockPos,
        val targetWorldPos: Vec3,
        val tickets: Set<ChunkPos>,
        val pendingChunks: MutableSet<ChunkPos>,
        val sentChunks: MutableSet<ChunkPos>,
        val level: ServerLevel,
    )

    private val sessions: MutableMap<UUID, Session> = ConcurrentHashMap()

    fun init() {
        PlayerEvent.PLAYER_QUIT.register { player -> unmount(player as ServerPlayer) }
        LifecycleEvent.SERVER_STOPPING.register { _ -> unmountAll() }
        TickEvent.SERVER_POST.register { _ -> tickAllSessions() }
    }

    /** Tear down any prior session for this player, then mount a new one at the given
     *  target. Returns true if the session was created.
     *
     *  **Single-session invariant.** [sessions] is keyed by player UUID so the map can
     *  only ever hold one entry per player. A second right-click while a session is
     *  live preempts the existing one rather than stacking — that's what the unmount
     *  below guarantees. The same invariant is mirrored client-side by
     *  [org.shipwrights.enderkinesis.client.ScryingClient.beginScrying]. */
    fun mount(
        player: ServerPlayer,
        sourceShipyardPos: BlockPos,
        targetShipyardPos: BlockPos,
    ): Boolean {
        if (sessions.containsKey(player.uuid)) {
            LOG.info("Preempting existing scry session for player {}", player.name.string)
        }
        unmount(player)

        val level = player.serverLevel()
        val targetWorld = shipyardToWorld(level, targetShipyardPos)

        val viewDistance = Mth.clamp(
            player.server.playerList.viewDistance,
            2,
            MAX_SESSION_VIEW_DISTANCE,
        )

        val centerChunk = ChunkPos(BlockPos.containing(targetWorld.x, targetWorld.y, targetWorld.z))
        val tickets = HashSet<ChunkPos>()
        val pending = HashSet<ChunkPos>()
        val chunkSource = level.chunkSource
        for (dx in -viewDistance..viewDistance) {
            for (dz in -viewDistance..viewDistance) {
                val cp = ChunkPos(centerChunk.x + dx, centerChunk.z + dz)
                chunkSource.addRegionTicket(ScryingTicketType.SCRYING, cp, ScryingTicketType.DISTANCE, cp)
                tickets.add(cp)
                pending.add(cp)
            }
        }

        sessions[player.uuid] = Session(
            player = player,
            sourceShipyardPos = sourceShipyardPos.immutable(),
            targetShipyardPos = targetShipyardPos.immutable(),
            targetWorldPos = targetWorld,
            tickets = tickets,
            pendingChunks = pending,
            sentChunks = HashSet(),
            level = level,
        )

        ScryingClientNetwork.sendBeginScrying(player, sourceShipyardPos, targetShipyardPos, viewDistance)
        return true
    }

    /** Release this player's session if any. Safe to call when no session exists. The
     *  client clears its own camera storage when its [ScryingClient.endScrying]
     *  fires — we only need to release the server-side chunk tickets here. */
    fun unmount(player: ServerPlayer) {
        val session = sessions.remove(player.uuid) ?: return
        val chunkSource = session.level.chunkSource
        for (cp in session.tickets) {
            chunkSource.removeRegionTicket(ScryingTicketType.SCRYING, cp, ScryingTicketType.DISTANCE, cp)
        }
    }

    private fun unmountAll() {
        for (uuid in sessions.keys.toList()) {
            val session = sessions[uuid] ?: continue
            unmount(session.player)
        }
    }

    fun activeSession(player: ServerPlayer): Session? = sessions[player.uuid]

    /** Tear down every session in [level] whose source or target orb position matches
     *  [orbPos]. Called from [org.shipwrights.enderkinesis.block.OrbOfScryingBlock.onRemove]
     *  so breaking either side of a scrying link cleanly ends every in-flight session
     *  using it — orphaned sessions otherwise hold a live ticket square and keep
     *  showing a frozen camera view at the destroyed orb's position. */
    fun endSessionsForOrb(level: ServerLevel, orbPos: BlockPos) {
        if (sessions.isEmpty()) return
        val matches = sessions.values.filter { s ->
            s.level === level && (s.sourceShipyardPos == orbPos || s.targetShipyardPos == orbPos)
        }
        for (s in matches) unmount(s.player)
    }

    /** Players whose scrying-session ticket square covers this chunk pos. The chunk-
     *  broadcast mixin appends these to vanilla's normal recipient list so block-update
     *  / light / block-entity packets in camera chunks flow to the viewer mid-session. */
    fun viewersOfChunk(pos: ChunkPos): Set<ServerPlayer> {
        if (sessions.isEmpty()) return emptySet()
        var result: HashSet<ServerPlayer>? = null
        for (s in sessions.values) {
            if (s.tickets.contains(pos)) {
                if (result == null) result = HashSet(2)
                result.add(s.player)
            }
        }
        return result ?: emptySet()
    }

    /** Per-tick: stream camera chunks that have reached FULL status to active sessions. */
    private fun tickAllSessions() {
        if (sessions.isEmpty()) return
        for (session in sessions.values) {
            if (session.pendingChunks.isEmpty()) continue
            if (session.player.connection == null) continue
            val sentBefore = session.sentChunks.size
            val it = session.pendingChunks.iterator()
            while (it.hasNext()) {
                val cp = it.next()
                val chunk: LevelChunk = session.level.chunkSource.getChunk(cp.x, cp.z, false) ?: continue
                sendChunk(session.player, session.level, chunk)
                session.sentChunks.add(cp)
                it.remove()
            }
            val sentNow = session.sentChunks.size - sentBefore
            if (sentNow > 0) {
                LOG.info(
                    "Streamed {} camera chunks to {} (sent total: {}, pending: {})",
                    sentNow,
                    session.player.name.string,
                    session.sentChunks.size,
                    session.pendingChunks.size,
                )
            }
        }
    }

    private fun sendChunk(player: ServerPlayer, level: ServerLevel, chunk: LevelChunk) {
        val packet = ClientboundLevelChunkWithLightPacket(chunk, level.lightEngine, null, null)
        player.connection.send(packet)
    }

    private fun shipyardToWorld(level: ServerLevel, pos: BlockPos): Vec3 {
        val ship = level.getShipManagingPos(pos)
        val v = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        ship?.shipToWorld?.transformPosition(v)
        return Vec3(v.x, v.y, v.z)
    }
}
