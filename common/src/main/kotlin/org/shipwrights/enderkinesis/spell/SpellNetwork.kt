package org.shipwrights.enderkinesis.spell

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.ints.Int2FloatMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.MysticScrollItem
import org.shipwrights.enderkinesis.mixin.ChunkMapEntityMapAccessor
import org.shipwrights.enderkinesis.mixin.TrackedEntitySeenByAccessor
import org.valkyrienskies.mod.common.toWorldCoordinates

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

    // Game thread only.
    private var holdingMerges = HashSet<ServerPlayer>()
    private val sentMerges = HashSet<ServerPlayer>()

    private class ShiftBatch(val level: ServerLevel, val from: Vec3, val element: Int) {
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
            pendingShifts.clear()
        }
    }

    fun broadcastAbsent(level: ServerLevel, entity: Entity, absent: Boolean) =
        sendTracking(level, entity, ABSENT) { writeAbsent(it, entity, absent) }

    fun broadcastVisuals(level: ServerLevel, entity: Entity, bits: Int, magnitudes: ByteArray) =
        sendTracking(level, entity, VISUALS) { writeVisuals(it, entity, bits, magnitudes) }

    fun broadcastFrozenPose(level: ServerLevel, entity: Entity, x: Float, y: Float, z: Float, w: Float) =
        sendTracking(level, entity, FROZEN_POSE) { buf ->
            buf.writeVarInt(entity.id)
            buf.writeFloat(x); buf.writeFloat(y); buf.writeFloat(z); buf.writeFloat(w)
        }

    /** Skips [player], whose client played it from the click. Send it before the visual of the use. */
    fun broadcastUsed(level: ServerLevel, player: ServerPlayer, use: MysticScrollItem.Companion.OtherUse, hand: InteractionHand) =
        sendTo(tracking(level, player).filter { it !== player }, USED) { buf ->
            buf.writeVarInt(player.id); buf.writeByte(use.ordinal); buf.writeBoolean(hand == InteractionHand.MAIN_HAND)
        }

    /** Send it before the use ends on clients, so they can tell a release from a switch away.
     *  Use [toSelf] when the hold limit released the cast, which the client of [player] did not see. */
    fun broadcastReleased(level: ServerLevel, player: ServerPlayer, toSelf: Boolean) =
        sendTo(tracking(level, player).filter { toSelf || it !== player }, RELEASED) { buf -> buf.writeVarInt(player.id) }

    /** Null or empty [cells] clears the chunk. */
    fun sendGas(level: ServerLevel, chunk: Long, cells: Int2FloatMap?) =
        sendTo(level.chunkSource.chunkMap.getPlayers(ChunkPos(chunk), false), GAS) { buf ->
            buf.writeLong(chunk)
            buf.writeVarInt(cells?.size ?: 0)
            cells?.int2FloatEntrySet()?.forEach { e ->
                buf.writeVarInt(e.intKey)
                buf.writeByte(Mth.clamp(Math.round(e.floatValue * 255f), 1, 255))
            }
        }

    fun sendGasCells(level: ServerLevel, chunk: Long, cells: Int2FloatMap) =
        sendTo(level.chunkSource.chunkMap.getPlayers(ChunkPos(chunk), false), GAS_CELLS) { buf ->
            buf.writeLong(chunk)
            buf.writeVarInt(cells.size)
            cells.int2FloatEntrySet().forEach { e ->
                buf.writeVarInt(e.intKey)
                buf.writeByte(if (e.floatValue <= 0f) 0 else Mth.clamp(Math.round(e.floatValue * 255f), 1, 255))
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
        val phased = AbyssalPockets.phasedIn(player.serverLevel())
        if (phased.isNotEmpty()) sendTo(listOf(player), PHASE) { writePositions(it, phased.asList()) }
        sendState(player, player)
    }

    /** Send to all players in the level: a client that missed a pocket close keeps a hole that is not there. */
    fun broadcastPhase(level: ServerLevel, positions: Collection<Long>, phased: Boolean) =
        sendTo(level.players(), if (phased) PHASE else UNPHASE) { writePositions(it, positions) }

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

    fun queueBlockShift(level: ServerLevel, pos: BlockPos, old: BlockState, from: Vec3, element: Element?) {
        val e = element?.ordinal ?: -1
        val last = pendingShifts.lastOrNull()
        val batch = if (last != null && last.level === level && last.element == e && last.from == from) last
        else ShiftBatch(level, from, e).also { pendingShifts += it }
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
                buf.writeDouble(batch.from.x); buf.writeDouble(batch.from.y); buf.writeDouble(batch.from.z)
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
        recipients += trackingAny(level, beams.mapNotNull { it.segments.firstOrNull()?.start })
        for (id in cuts.keys) level.getEntity(id)?.let { recipients += tracking(level, it) }
        if (recipients.isEmpty()) return
        sentMerges += recipients
        sendTo(recipients, MERGED) { buf -> writeMerged(buf, beams, cuts) }
    }

    private fun writeMerged(buf: FriendlyByteBuf, beams: List<BeamManager.MergedBeam>, cuts: Map<Int, Double>) {
        buf.writeVarInt(beams.size)
        for (beam in beams) {
            buf.writeVarInt(beam.elements)
            buf.writeInt(beam.parentA)
            buf.writeInt(beam.parentB)
            buf.writeVarInt(beam.fused)
            buf.writeVarInt(beam.segments.size)
            for (seg in beam.segments) {
                buf.writeDouble(seg.start.x); buf.writeDouble(seg.start.y); buf.writeDouble(seg.start.z)
                buf.writeDouble(seg.end.x); buf.writeDouble(seg.end.y); buf.writeDouble(seg.end.z)
            }
        }
        buf.writeVarInt(cuts.size)
        for ((id, cut) in cuts) {
            buf.writeVarInt(id)
            buf.writeDouble(cut)
        }
    }

    private fun clearEndedMerges() {
        if (holdingMerges.isEmpty() && sentMerges.isEmpty()) return
        val ended = holdingMerges.filter { it !in sentMerges && !it.hasDisconnected() }
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

    /** A point on a ship counts at its shipyard chunk and at its world chunk. */
    private fun trackingAny(level: ServerLevel, points: List<Vec3>): Collection<ServerPlayer> {
        val chunks = HashSet<ChunkPos>()
        for (point in points) {
            chunks += ChunkPos(BlockPos.containing(point))
            chunks += ChunkPos(BlockPos.containing(level.toWorldCoordinates(point)))
        }
        if (chunks.size == 1) return level.chunkSource.chunkMap.getPlayers(chunks.first(), false)
        val out = LinkedHashSet<ServerPlayer>()
        for (chunk in chunks) out += level.chunkSource.chunkMap.getPlayers(chunk, false)
        return out
    }

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
