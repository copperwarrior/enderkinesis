package org.shipwrights.enderkinesis.spell

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.ints.Int2FloatMap
import it.unimi.dsi.fastutil.ints.Int2IntMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.UlderScrollItem
import org.shipwrights.enderkinesis.mixin.ChunkMapEntityMapAccessor
import org.shipwrights.enderkinesis.mixin.TrackedEntitySeenByAccessor
import org.valkyrienskies.mod.common.toWorldCoordinates
import org.shipwrights.enderkinesis.util.addPointsAlong
import org.shipwrights.enderkinesis.util.playersHolding

object SpellNetwork {
    val ABSENT: ResourceLocation = EnderkinesisMod.id("spell/absent")
    val PHASE: ResourceLocation = EnderkinesisMod.id("spell/phase")
    val UNPHASE: ResourceLocation = EnderkinesisMod.id("spell/unphase")
    val STUTTER: ResourceLocation = EnderkinesisMod.id("spell/stutter")
    /** Each packet replaces the full client list. Clients never time merges out, so an empty list ends them. */
    val MERGED: ResourceLocation = EnderkinesisMod.id("spell/merged")
    val VISUALS: ResourceLocation = EnderkinesisMod.id("spell/visuals")
    val VFX: ResourceLocation = EnderkinesisMod.id("spell/vfx")
    val BLOCK_SHIFTS: ResourceLocation = EnderkinesisMod.id("spell/block_shifts")
    const val MAX_SHIFTS_PER_PACKET = 2048
    val FROZEN_POSE: ResourceLocation = EnderkinesisMod.id("spell/frozen_pose")
    val USED: ResourceLocation = EnderkinesisMod.id("spell/used")
    val RELEASED: ResourceLocation = EnderkinesisMod.id("spell/released")
    val GAS: ResourceLocation = EnderkinesisMod.id("spell/gas")
    val GAS_CELLS: ResourceLocation = EnderkinesisMod.id("spell/gas_cells")
    val GAS_JET: ResourceLocation = EnderkinesisMod.id("spell/gas_jet")

    // Game thread only.
    private var holdingMerges = HashSet<ServerPlayer>()
    private val sentMerges = HashSet<ServerPlayer>()
    // Content hash of the merge state each player last got, so an unchanged state is not sent again.
    private val lastMerged = HashMap<ServerPlayer, Int>()
    private const val MAX_POSITIONS_PER_PACKET = 4096

    /** [from] also picks the players that get the batch, so it is kept when the batch is not [directed]. */
    private class ShiftBatch(val level: ServerLevel, val from: Vec3, val element: Int, val directed: Boolean) {
        val positions = LongArrayList()
        val states = IntArrayList()
    }

    // Game thread only.
    private val pendingShifts = ArrayList<ShiftBatch>()

    fun init() {
        PlayerEvent.PLAYER_JOIN.register { resync(it) }
        PlayerEvent.CHANGE_DIMENSION.register { player, _, _ -> resync(player) }
        PlayerEvent.PLAYER_RESPAWN.register { player, _ -> resync(player) }
        // BeamManager sends merges at the end of the server tick, so all recipients have them by the next start.
        TickEvent.SERVER_PRE.register { clearEndedMerges() }
        // Flush before the level sends its block changes, so the shift reaches clients first.
        // Casts from player input land between ticks, thus SERVER_POST also flushes.
        TickEvent.SERVER_LEVEL_PRE.register { flushBlockShifts(it) }
        TickEvent.SERVER_POST.register { flushBlockShifts(null) }
        LifecycleEvent.SERVER_STOPPING.register {
            holdingMerges.clear()
            sentMerges.clear()
            lastMerged.clear()
            pendingShifts.clear()
        }
    }

    fun broadcastAbsent(level: ServerLevel, entity: Entity, absent: Boolean) =
        sendTracking(level, entity, ABSENT) { writeAbsent(it, entity, absent) }

    fun broadcastVisuals(level: ServerLevel, entity: Entity, bits: Int, magnitudes: ByteArray) =
        sendTracking(level, entity, VISUALS) { writeVisuals(it, entity, bits, magnitudes) }

    /** One packet with every frozen pose [player] gets this tick: [count] entity ids, and 4 floats for each. */
    fun sendFrozenPoses(player: ServerPlayer, ids: IntArray, poses: FloatArray, count: Int) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(count)
        for (i in 0 until count) {
            buf.writeVarInt(ids[i])
            for (k in 0 until 4) buf.writeFloat(poses[i * 4 + k])
        }
        NetworkManager.sendToPlayer(player, FROZEN_POSE, buf)
    }

    fun frozenPoseViewers(level: ServerLevel, entity: Entity): Collection<ServerPlayer> = tracking(level, entity)

    /** Skips [player], whose client played it from the click. Send it before the visual of the use. */
    fun broadcastUsed(level: ServerLevel, player: ServerPlayer, use: UlderScrollItem.Companion.OtherUse, hand: InteractionHand) =
        sendTo(tracking(level, player).filter { it !== player }, USED) { buf ->
            buf.writeVarInt(player.id); buf.writeByte(use.ordinal); buf.writeBoolean(hand == InteractionHand.MAIN_HAND)
        }

    /** Send it before the use ends on clients, so they can tell a release from a switch away.
     *  Use [toSelf] when the hold limit released the cast, which the client of [player] did not see. */
    fun broadcastReleased(level: ServerLevel, player: ServerPlayer, toSelf: Boolean) =
        sendTo(tracking(level, player).filter { toSelf || it !== player }, RELEASED) { buf -> buf.writeVarInt(player.id) }

    /** Null or empty [cells] clears the chunk. [flows] are packed int8 x3 ([GasField.packFlow]) and replace the chunk's flows. */
    fun sendGas(players: List<ServerPlayer>, kind: GasKind, chunk: Long, cells: Int2FloatMap?, flows: Int2IntMap?) =
        sendTo(players, GAS) { buf ->
            buf.writeByte(kind.ordinal)
            buf.writeLong(chunk)
            buf.writeVarInt(cells?.size ?: 0)
            cells?.int2FloatEntrySet()?.forEach { e ->
                buf.writeVarInt(e.intKey)
                buf.writeByte(GasField.packAmount(e.floatValue))
            }
            writeFlows(buf, flows)
        }

    /** The state of a gas jet: start or change, or [stop]. Clients run the parcels from it. */
    fun sendJet(level: ServerLevel, chunk: ChunkPos, e: GasJetEmitter, stop: Boolean) =
        sendTo(level.chunkSource.chunkMap.getPlayers(chunk, false), GAS_JET) { buf ->
            buf.writeLong(e.id); buf.writeByte(e.kind.ordinal); buf.writeBoolean(e.hand); buf.writeBoolean(stop)
            buf.writeLong(e.frame)
            buf.writeDouble(e.px); buf.writeDouble(e.py); buf.writeDouble(e.pz)
            buf.writeFloat(e.dx.toFloat()); buf.writeFloat(e.dy.toFloat()); buf.writeFloat(e.dz.toFloat())
            buf.writeFloat(e.strength); buf.writeFloat(e.parcelMass); buf.writeLong(e.startTick); buf.writeLong(e.seed)
        }

    private class GasCellBatch {
        val entries: FriendlyByteBuf = FriendlyByteBuf(Unpooled.buffer())
        var count = 0
    }

    // Game thread only. The changed gas chunks of one kind for each player, sent as one GAS_CELLS packet by flushGasCells.
    private val cellBatches = HashMap<ServerPlayer, GasCellBatch>()
    private val spareCellBatches = ArrayList<GasCellBatch>()
    private val cellEntry = FriendlyByteBuf(Unpooled.buffer())
    private var batchKind: GasKind? = null
    // Well under the 1 MiB custom payload limit.
    private const val MAX_CELL_BATCH_BYTES = 512 * 1024

    /** Queues the changed cells and flows of one chunk for [players]. A flow of 0 removes it. The maps can be reused at once. */
    fun queueGasCells(players: List<ServerPlayer>, kind: GasKind, chunk: Long, cells: Int2FloatMap?, flows: Int2IntMap?) {
        if (players.isEmpty()) return
        // A sync that stopped before its flush must not mix its kind into this batch.
        batchKind?.let { if (it != kind) flushGasCells(it) }
        batchKind = kind
        cellEntry.clear()
        cellEntry.writeLong(chunk)
        cellEntry.writeVarInt(cells?.size ?: 0)
        cells?.int2FloatEntrySet()?.forEach { e ->
            cellEntry.writeVarInt(e.intKey)
            cellEntry.writeByte(if (e.floatValue <= 0f) 0 else GasField.packAmount(e.floatValue))
        }
        writeFlows(cellEntry, flows)
        val size = cellEntry.readableBytes()
        for (player in players) {
            val batch = cellBatches.getOrPut(player) { spareCellBatches.removeLastOrNull() ?: GasCellBatch() }
            if (batch.count > 0 && batch.entries.readableBytes() + size > MAX_CELL_BATCH_BYTES) sendCellBatch(player, kind, batch)
            batch.entries.writeBytes(cellEntry, cellEntry.readerIndex(), size)
            batch.count++
        }
    }

    /** Sends each player one GAS_CELLS packet with every chunk that [queueGasCells] queued for it. */
    fun flushGasCells(kind: GasKind) {
        for ((player, batch) in cellBatches) {
            sendCellBatch(player, kind, batch)
            spareCellBatches += batch
        }
        cellBatches.clear()
        batchKind = null
    }

    private fun sendCellBatch(player: ServerPlayer, kind: GasKind, batch: GasCellBatch) {
        if (batch.count == 0) return
        val buf = FriendlyByteBuf(Unpooled.buffer(batch.entries.readableBytes() + BATCH_HEADER_BYTES))
        buf.writeByte(kind.ordinal)
        buf.writeVarInt(batch.count)
        buf.writeBytes(batch.entries)
        NetworkManager.sendToPlayer(player, GAS_CELLS, buf)
        batch.entries.clear()
        batch.count = 0
    }

    // Kind byte and a count varint.
    private const val BATCH_HEADER_BYTES = 6

    private fun writeFlows(buf: FriendlyByteBuf, flows: Int2IntMap?) {
        buf.writeVarInt(flows?.size ?: 0)
        flows?.int2IntEntrySet()?.forEach { e ->
            buf.writeVarInt(e.intKey)
            buf.writeByte(e.intValue); buf.writeByte(e.intValue shr 8); buf.writeByte(e.intValue shr 16)
        }
    }

    private fun writeAbsent(buf: FriendlyByteBuf, entity: Entity, absent: Boolean) {
        buf.writeVarInt(entity.id); buf.writeBoolean(absent)
    }

    private fun writeVisuals(buf: FriendlyByteBuf, entity: Entity, bits: Int, magnitudes: ByteArray) {
        buf.writeVarInt(entity.id); buf.writeVarInt(bits); buf.writeByteArray(magnitudes)
    }

    /** Send only state that is set: the client forgot the entity when it stopped tracking it. */
    @JvmStatic
    fun onStartTracking(player: ServerPlayer, entity: Entity) = sendState(player, entity)

    private fun sendState(player: ServerPlayer, entity: Entity) {
        if (entity !is LivingEntity) return
        if (Absence.isAbsent(entity)) sendTo(listOf(player), ABSENT) { writeAbsent(it, entity, true) }
        val bits = SpellVisuals.bits(entity)
        if (bits != 0) sendTo(listOf(player), VISUALS) { writeVisuals(it, entity, bits, SpellVisuals.castMagnitudes(entity, bits)) }
    }

    /** Join, dimension change and respawn make a new client level; no tracking covers the player's own state. */
    private fun resync(player: ServerPlayer) {
        lastMerged.remove(player)
        val phased = AbyssalPockets.phasedIn(player.serverLevel())
        if (phased.isNotEmpty()) sendPositions(listOf(player), PHASE, phased.asList())
        sendState(player, player)
    }

    /** To the players who track a chunk of the pocket. A client that loads such a chunk later gets its phased blocks
     *  with it ([AbyssalPockets.sendPhasedInChunk]), and a client that unloads one forgets them, so none keeps a stale
     *  hole. */
    fun broadcastPhase(level: ServerLevel, positions: Collection<Long>, phased: Boolean) =
        sendPositions(trackingAny(level, chunkCentres(positions)), if (phased) PHASE else UNPHASE, positions)

    fun sendPhase(player: ServerPlayer, positions: Collection<Long>) = sendPositions(listOf(player), PHASE, positions)

    private fun chunkCentres(positions: Collection<Long>): List<Vec3> {
        val chunks = LongOpenHashSet()
        val out = ArrayList<Vec3>()
        for (key in positions) {
            val x = BlockPos.getX(key)
            val z = BlockPos.getZ(key)
            if (chunks.add(ChunkPos.asLong(x shr 4, z shr 4))) out += Vec3(x + 0.5, BlockPos.getY(key) + 0.5, z + 0.5)
        }
        return out
    }

    /** In packets of at most [MAX_POSITIONS_PER_PACKET], so a large pocket stays well under the payload limit. */
    private fun sendPositions(players: Collection<ServerPlayer>, id: ResourceLocation, positions: Collection<Long>) {
        if (players.isEmpty() || positions.isEmpty()) return
        val list = positions.toList()
        var start = 0
        while (start < list.size) {
            val end = minOf(start + MAX_POSITIONS_PER_PACKET, list.size)
            val slice = list.subList(start, end)
            sendTo(players, id) { writePositions(it, slice) }
            start = end
        }
    }

    private fun writePositions(buf: FriendlyByteBuf, positions: Collection<Long>) {
        buf.writeVarInt(positions.size)
        for (pos in positions) buf.writeLong(pos)
    }

    fun broadcastStutter(level: ServerLevel, shipId: Long, ticks: Int) =
        sendTo(level.players(), STUTTER) { it.writeLong(shipId); it.writeVarInt(ticks) }

    fun broadcastVfx(level: ServerLevel, kind: Int, a: Vec3, b: Vec3, size: Float, rgb: Int, life: Int, seed: Int) =
        sendTo(trackingAny(level, listOf(a, b)), VFX) { buf ->
            buf.writeByte(kind)
            buf.writeDouble(a.x); buf.writeDouble(a.y); buf.writeDouble(a.z)
            buf.writeDouble(b.x); buf.writeDouble(b.y); buf.writeDouble(b.z)
            buf.writeFloat(size)
            buf.writeInt(rgb)
            buf.writeVarInt(life)
            buf.writeInt(seed)
        }

    fun queueBlockShift(level: ServerLevel, pos: BlockPos, old: BlockState, from: Vec3, element: Element?, directed: Boolean) {
        val e = element?.ordinal ?: -1
        val last = pendingShifts.lastOrNull()
        val batch = if (last != null && last.level === level && last.element == e && last.from == from && last.directed == directed) last
        else ShiftBatch(level, from, e, directed).also { pendingShifts += it }
        batch.positions.add(pos.asLong())
        batch.states.add(Block.getId(old))
    }

    /** Each player gets the same changes as one packet per change would give them. */
    private fun flushBlockShifts(only: ServerLevel?) {
        if (pendingShifts.isEmpty()) return
        val byChunk = Long2ObjectOpenHashMap<Collection<ServerPlayer>>()
        val perPlayer = LinkedHashMap<ServerPlayer, IntArrayList>()
        val pos = BlockPos.MutableBlockPos()
        for (batch in pendingShifts) {
            val level = batch.level
            if (only != null && level !== only) continue
            val fromPlayers = trackingAny(level, listOf(batch.from))
            byChunk.clear()
            perPlayer.clear()
            for (n in 0 until batch.positions.size) {
                pos.set(batch.positions.getLong(n))
                val worldPos = BlockPos.containing(level.toWorldCoordinates(Vec3.atCenterOf(pos)))
                for (player in fromPlayers) perPlayer.getOrPut(player) { IntArrayList() }.add(n)
                val here = playersInChunk(level, byChunk, ChunkPos.asLong(pos.x shr 4, pos.z shr 4))
                val world = playersInChunk(level, byChunk, ChunkPos.asLong(worldPos.x shr 4, worldPos.z shr 4))
                for (player in here) if (player !in fromPlayers) perPlayer.getOrPut(player) { IntArrayList() }.addOnce(n)
                if (world !== here) for (player in world) if (player !in fromPlayers) perPlayer.getOrPut(player) { IntArrayList() }.addOnce(n)
            }
            for ((player, shifts) in perPlayer) sendBlockShifts(player, batch, shifts)
        }
        if (only == null) pendingShifts.clear() else pendingShifts.removeIf { it.level === only }
    }

    private fun IntArrayList.addOnce(n: Int) {
        if (isEmpty() || getInt(size - 1) != n) add(n)
    }

    private fun playersInChunk(level: ServerLevel, cache: Long2ObjectOpenHashMap<Collection<ServerPlayer>>, chunk: Long): Collection<ServerPlayer> =
        cache.get(chunk) ?: level.chunkSource.chunkMap.getPlayers(ChunkPos(chunk), false).also { cache.put(chunk, it) }

    private fun sendBlockShifts(player: ServerPlayer, batch: ShiftBatch, shifts: IntArrayList) {
        var start = 0
        while (start < shifts.size) {
            val end = minOf(start + MAX_SHIFTS_PER_PACKET, shifts.size)
            sendTo(listOf(player), BLOCK_SHIFTS) { buf ->
                buf.writeBoolean(batch.directed)
                if (batch.directed) { buf.writeDouble(batch.from.x); buf.writeDouble(batch.from.y); buf.writeDouble(batch.from.z) }
                buf.writeVarInt(batch.element + 1)
                buf.writeVarInt(end - start)
                for (k in start until end) {
                    val n = shifts.getInt(k)
                    buf.writeLong(batch.positions.getLong(n))
                    buf.writeVarInt(batch.states.getInt(n))
                }
            }
            start = end
        }
    }

    fun broadcastMerged(level: ServerLevel, beams: List<BeamManager.MergedBeam>, cuts: Map<Int, Double>) {
        val recipients = HashSet<ServerPlayer>()
        // Anyone near any part of a merged beam sees it, not only players near where it starts.
        recipients += trackingAny(level, alongSegments(beams))
        for (id in cuts.keys) level.getEntity(id)?.let { recipients += tracking(level, it) }
        if (recipients.isEmpty()) return
        sentMerges += recipients
        val buf = FriendlyByteBuf(Unpooled.buffer())
        writeMerged(buf, beams, cuts)
        val hash = buf.hashCode()
        for (player in recipients) {
            if (lastMerged.put(player, hash) == hash) continue
            // copy(): NetworkManager keeps the buffer past send.
            NetworkManager.sendToPlayer(player, MERGED, FriendlyByteBuf(buf.copy()))
        }
    }

    private fun alongSegments(beams: List<BeamManager.MergedBeam>): List<Vec3> {
        val out = ArrayList<Vec3>()
        for (beam in beams) for (seg in beam.segments) addPointsAlong(seg.start, seg.end, out)
        return out
    }

    // Segment points go as floats from the first point: a beam spans at most a few hundred blocks.
    private fun writeMerged(buf: FriendlyByteBuf, beams: List<BeamManager.MergedBeam>, cuts: Map<Int, Double>) {
        val origin = beams.firstOrNull()?.segments?.firstOrNull()?.start ?: Vec3.ZERO
        buf.writeDouble(origin.x); buf.writeDouble(origin.y); buf.writeDouble(origin.z)
        buf.writeVarInt(beams.size)
        for (beam in beams) {
            buf.writeVarInt(beam.elements)
            buf.writeInt(beam.parentA)
            buf.writeInt(beam.parentB)
            buf.writeVarInt(beam.fused)
            buf.writeVarInt(beam.segments.size)
            for (seg in beam.segments) {
                writeOffset(buf, seg.start, origin)
                writeOffset(buf, seg.end, origin)
            }
        }
        buf.writeVarInt(cuts.size)
        for ((id, cut) in cuts) {
            buf.writeVarInt(id)
            buf.writeDouble(cut)
        }
    }

    private fun writeOffset(buf: FriendlyByteBuf, point: Vec3, origin: Vec3) {
        buf.writeFloat((point.x - origin.x).toFloat())
        buf.writeFloat((point.y - origin.y).toFloat())
        buf.writeFloat((point.z - origin.z).toFloat())
    }

    private fun clearEndedMerges() {
        if (holdingMerges.isEmpty() && sentMerges.isEmpty()) return
        val ended = holdingMerges.filter { it !in sentMerges && !it.hasDisconnected() }
        for (player in holdingMerges) if (player !in sentMerges) lastMerged.remove(player)
        if (ended.isNotEmpty()) sendTo(ended, MERGED) { buf -> writeMerged(buf, emptyList(), emptyMap()) }
        holdingMerges = HashSet(sentMerges)
        sentMerges.clear()
    }

    private fun tracking(level: ServerLevel, entity: Entity): Collection<ServerPlayer> {
        val out = ArrayList<ServerPlayer>()
        val tracked = (level.chunkSource.chunkMap as ChunkMapEntityMapAccessor).`enderkinesis$getEntityMap`().get(entity.id)
        (tracked as? TrackedEntitySeenByAccessor)?.`enderkinesis$getSeenBy`()?.forEach { out += it.player }
        if (entity is ServerPlayer && entity !in out) out += entity
        return out
    }

    private fun trackingAny(level: ServerLevel, points: List<Vec3>): Collection<ServerPlayer> = level.playersHolding(points)

    private inline fun sendTo(players: Collection<ServerPlayer>, id: ResourceLocation, write: (FriendlyByteBuf) -> Unit) {
        if (players.isEmpty()) return
        val buf = FriendlyByteBuf(Unpooled.buffer())
        write(buf)
        // copy(): NetworkManager keeps the buffer past send.
        for (player in players) NetworkManager.sendToPlayer(player, id, FriendlyByteBuf(buf.copy()))
    }

    private inline fun sendTracking(level: ServerLevel, entity: Entity, id: ResourceLocation, write: (FriendlyByteBuf) -> Unit) =
        sendTo(tracking(level, entity), id, write)
}
