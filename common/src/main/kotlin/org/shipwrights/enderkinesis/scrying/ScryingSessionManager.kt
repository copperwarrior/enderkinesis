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
import org.valkyrienskies.mod.common.getShipManagingPos
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


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

    private const val VALIDATION_INTERVAL_TICKS = 20

    /** The client limit is 1.5 blocks (`ScryingClient`). The wider limit here keeps lag from
     *  ending a valid session. */
    private const val MAX_PROXIMITY_SQR: Double = 6.0 * 6.0

    data class Session(
        val player: ServerPlayer,
        val sourceShipyardPos: BlockPos,
        val targetShipyardPos: BlockPos,
        var targetWorldPos: Vec3,
        var centerChunk: ChunkPos,
        val viewDistance: Int,
        val tickets: MutableSet<ChunkPos>,
        val pendingChunks: MutableSet<ChunkPos>,
        val sentChunks: MutableSet<ChunkPos>,
        val level: ServerLevel,
    )

    private val sessions: MutableMap<UUID, Session> = ConcurrentHashMap()
    private var validationCountdown = 0

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
            centerChunk = centerChunk,
            viewDistance = viewDistance,
            tickets = tickets,
            pendingChunks = pending,
            sentChunks = HashSet(),
            level = level,
        )

        ScryingClientNetwork.sendBeginScrying(player, sourceShipyardPos, targetShipyardPos, viewDistance)
        refreshEntityTracking(player)
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
        refreshEntityTracking(player)
    }

    /** Vanilla updates the pairing of an entity and a player only when one of them crosses a
     *  section border. The tracking mixin reads the session, so each start and end needs a pass. */
    private fun refreshEntityTracking(player: ServerPlayer) {
        if (!player.isRemoved && !player.hasDisconnected()) player.serverLevel().chunkSource.move(player)
    }

    /** The client ends its session by these same rules, then sends the end packet. A client
     *  that sends no end packet must not keep the camera area loaded. */
    private fun isStillValid(session: Session): Boolean {
        val player = session.player
        if (player.isRemoved || !player.isAlive || player.level() !== session.level) return false
        val source = shipyardToWorld(session.level, session.sourceShipyardPos)
        return player.eyePosition.distanceToSqr(source) <= MAX_PROXIMITY_SQR
    }

    /** A target orb on a ship moves. The tickets and the tracking position go with it. */
    private fun followTarget(session: Session) {
        val world = shipyardToWorld(session.level, session.targetShipyardPos)
        session.targetWorldPos = world
        val center = ChunkPos(BlockPos.containing(world.x, world.y, world.z))
        if (center == session.centerChunk) return
        session.centerChunk = center
        val chunkSource = session.level.chunkSource
        val r = session.viewDistance
        val stale = session.tickets.iterator()
        while (stale.hasNext()) {
            val cp = stale.next()
            if (Math.abs(cp.x - center.x) <= r && Math.abs(cp.z - center.z) <= r) continue
            chunkSource.removeRegionTicket(ScryingTicketType.SCRYING, cp, ScryingTicketType.DISTANCE, cp)
            session.pendingChunks.remove(cp)
            session.sentChunks.remove(cp)
            stale.remove()
        }
        for (dx in -r..r) {
            for (dz in -r..r) {
                val cp = ChunkPos(center.x + dx, center.z + dz)
                if (!session.tickets.add(cp)) continue
                chunkSource.addRegionTicket(ScryingTicketType.SCRYING, cp, ScryingTicketType.DISTANCE, cp)
                session.pendingChunks.add(cp)
            }
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
        if (++validationCountdown >= VALIDATION_INTERVAL_TICKS) {
            validationCountdown = 0
            for (session in sessions.values.toList()) {
                if (isStillValid(session)) followTarget(session) else unmount(session.player)
            }
        }
        for (session in sessions.values) {
            if (session.pendingChunks.isEmpty()) continue
            if (session.player.connection == null) continue
            val it = session.pendingChunks.iterator()
            while (it.hasNext()) {
                val cp = it.next()
                // `getChunkNow`: the ticket gives each chunk a holder, so `getChunk` waits on the
                // server thread until the chunk is full, for the whole camera square in one tick.
                val chunk: LevelChunk = session.level.chunkSource.getChunkNow(cp.x, cp.z) ?: continue
                sendChunk(session.player, session.level, chunk)
                session.sentChunks.add(cp)
                it.remove()
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
