package org.shipwrights.enderkinesis.spell

import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.ints.Int2FloatMap
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
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.item.MysticScrollItem
import org.shipwrights.enderkinesis.mixin.ChunkMapEntityMapAccessor
import org.shipwrights.enderkinesis.mixin.TrackedEntitySeenByAccessor
import org.valkyrienskies.mod.common.toWorldCoordinates

/**
 * Server senders for the spell effects clients have to see. Receivers are in
 * `SpellNetworkClient`, registered from client init.
 *
 * - [ABSENT]: an entity went Absent or came back; clients stop or resume drawing it.
 * - [VISUALS] / [FROZEN_POSE]: an entity's visible spell effects, and a frozen mob's tumble.
 * - [PHASE] / [UNPHASE]: Abyssal Pocket blocks went or returned; clients hide them locally.
 * - [MERGED]: the merged beams that are live, every tick while any are.
 * - [VFX]: one passing spell visual at a place.
 * - [STUTTER]: Reveal makes a concealed ship's cloak flicker for a while.
 * - [USED]: a player wrote a rune, imbued or cast an Area burst with a scroll, so others can
 *   play the arm's motion (the player's own client plays it from the click).
 * - [RELEASED]: a player let go of a held cast, so others see a Bolt thrown, not switched away
 *   from.
 * - [GAS]: one chunk's Poison gas ([GasField]), to the players tracking the chunk; [GAS_CELLS]:
 *   just the blocks of it that drifting changed.
 *
 * Who gets what:
 *
 * - An entity's state goes to the players tracking it, and to the entity itself if it's a
 *   player. A player who starts tracking an entity is sent its state then ([onStartTracking],
 *   from `ServerEntitySpellTrackingMixin`), and clients forget an entity's state when they
 *   stop tracking it, so nothing goes stale while it's out of sight.
 * - A visual goes to the players tracking the chunk it's in (both ends' chunks, for one that
 *   runs between two places). Visuals are short; a player arriving mid-visual misses it.
 * - Merged beams go to the players tracking a merge's crossing or either caster. A player
 *   who stops getting them (the merges ended, or they moved away) is sent an empty list,
 *   which clears them; clients never time merges out themselves (see [MERGED]).
 * - Phased blocks and cloak stutters go to everyone in the level. They're rare and small, and
 *   phasing has to be right everywhere: a client that missed a pocket closing would keep a
 *   hole that isn't there.
 *
 * Joining, changing dimension and respawning all start a fresh client level, so the level's
 * phased blocks and the player's own state are sent again then ([resync]); entities around
 * them arrive through [onStartTracking].
 */
object SpellNetwork {
    val ABSENT: ResourceLocation = EnderkinesisMod.id("spell/absent")
    val PHASE: ResourceLocation = EnderkinesisMod.id("spell/phase")
    val UNPHASE: ResourceLocation = EnderkinesisMod.id("spell/unphase")
    val STUTTER: ResourceLocation = EnderkinesisMod.id("spell/stutter")
    /** The complete list of live merged beams the player can see, and their parents' cuts;
     *  it replaces whatever the client had. An empty list ends them all. */
    val MERGED: ResourceLocation = EnderkinesisMod.id("spell/merged")
    val VISUALS: ResourceLocation = EnderkinesisMod.id("spell/visuals")
    val VFX: ResourceLocation = EnderkinesisMod.id("spell/vfx")
    val FROZEN_POSE: ResourceLocation = EnderkinesisMod.id("spell/frozen_pose")
    val USED: ResourceLocation = EnderkinesisMod.id("spell/used")
    val RELEASED: ResourceLocation = EnderkinesisMod.id("spell/released")
    val GAS: ResourceLocation = EnderkinesisMod.id("spell/gas")
    val GAS_CELLS: ResourceLocation = EnderkinesisMod.id("spell/gas_cells")

    // Game thread only. Players sent merged beams in the last tick that sent any, and those
    // sent them since; see [clearEndedMerges].
    private var holdingMerges = HashSet<ServerPlayer>()
    private val sentMerges = HashSet<ServerPlayer>()

    fun init() {
        PlayerEvent.PLAYER_JOIN.register { resync(it) }
        PlayerEvent.CHANGE_DIMENSION.register { player, _, _ -> resync(player) }
        PlayerEvent.PLAYER_RESPAWN.register { player, _ -> resync(player) }
        // Merges are sent at the end of the server tick (BeamManager); by the start of the
        // next, everyone who was going to get them has.
        TickEvent.SERVER_PRE.register { clearEndedMerges() }
        LifecycleEvent.SERVER_STOPPING.register {
            holdingMerges.clear()
            sentMerges.clear()
        }
    }

    // -----------------------------------------------------------------------
    // Entity state
    // -----------------------------------------------------------------------

    fun broadcastAbsent(level: ServerLevel, entity: Entity, absent: Boolean) =
        sendTracking(level, entity, ABSENT) { writeAbsent(it, entity, absent) }

    /** [entity]'s visible spell effects changed; see [SpellVisuals]. */
    fun broadcastVisuals(level: ServerLevel, entity: Entity, bits: Int, magnitudes: ByteArray) =
        sendTracking(level, entity, VISUALS) { writeVisuals(it, entity, bits, magnitudes) }

    /** A frozen mob's tumble: its body's rotation since it froze, sent every tick. */
    fun broadcastFrozenPose(level: ServerLevel, entity: Entity, x: Float, y: Float, z: Float, w: Float) =
        sendTracking(level, entity, FROZEN_POSE) { buf ->
            buf.writeVarInt(entity.id)
            buf.writeFloat(x); buf.writeFloat(y); buf.writeFloat(z); buf.writeFloat(w)
        }

    /** [player] used the scroll in [hand] one of [MysticScrollItem.Companion.OtherUse]'s ways:
     *  to the players tracking them, not to [player], whose client played it from the click.
     *  Sent before the use's visual, so the arm's motion is under way when that arrives. */
    fun broadcastUsed(level: ServerLevel, player: ServerPlayer, use: MysticScrollItem.Companion.OtherUse, hand: InteractionHand) =
        sendTo(tracking(level, player).filter { it !== player }, USED) { buf ->
            buf.writeVarInt(player.id); buf.writeByte(use.ordinal); buf.writeBoolean(hand == InteractionHand.MAIN_HAND)
        }

    /** [player] let go of a held cast: to the players tracking them, and to [player] too when
     *  [toSelf] (the hold limit let go, which their client didn't see). Sent before the use's
     *  end reaches them, so a release is known from a switch away when it does. */
    fun broadcastReleased(level: ServerLevel, player: ServerPlayer, toSelf: Boolean) =
        sendTo(tracking(level, player).filter { toSelf || it !== player }, RELEASED) { buf -> buf.writeVarInt(player.id) }

    /** All the gas in the [chunk] of [level] ([GasField]), to the players tracking that chunk;
     *  [cells] null or empty clears it. Each block's gas goes as a byte. */
    fun sendGas(level: ServerLevel, chunk: Long, cells: Int2FloatMap?) =
        sendTo(level.chunkSource.chunkMap.getPlayers(ChunkPos(chunk), false), GAS) { buf ->
            buf.writeLong(chunk)
            buf.writeVarInt(cells?.size ?: 0)
            cells?.int2FloatEntrySet()?.forEach { e ->
                buf.writeVarInt(e.intKey)
                buf.writeByte(Mth.clamp(Math.round(e.floatValue * 255f), 1, 255))
            }
        }

    /** Some blocks of gas in the [chunk] of [level] changed ([GasField]'s drifting): their new
     *  amounts, 0 for none, to the players tracking that chunk. */
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

    /** The bits, then a byte for each cloak among them: its magnitude, in tenths. */
    private fun writeVisuals(buf: FriendlyByteBuf, entity: Entity, bits: Int, magnitudes: ByteArray) {
        buf.writeVarInt(entity.id); buf.writeVarInt(bits); buf.writeByteArray(magnitudes)
    }

    /**
     * [player] started tracking [entity]: sends whatever spell state of it the client draws.
     * Only state that's set is sent; the client forgot the entity when it last stopped
     * tracking it. A frozen mob's pose follows with the next tick's.
     */
    @JvmStatic
    fun onStartTracking(player: ServerPlayer, entity: Entity) = sendState(player, entity)

    private fun sendState(player: ServerPlayer, entity: Entity) {
        if (entity !is LivingEntity) return
        if (Absence.isAbsent(entity)) sendTo(listOf(player), ABSENT) { writeAbsent(it, entity, true) }
        val bits = SpellVisuals.bits(entity)
        if (bits != 0) sendTo(listOf(player), VISUALS) { writeVisuals(it, entity, bits, SpellVisuals.castMagnitudes(entity, bits)) }
    }

    /** A fresh client level for [player] (joined, changed dimension, respawned): the level's
     *  phased blocks, and the player's own state, which no tracking covers. */
    private fun resync(player: ServerPlayer) {
        val phased = AbyssalPockets.phasedIn(player.serverLevel())
        if (phased.isNotEmpty()) sendTo(listOf(player), PHASE) { writePositions(it, phased.asList()) }
        sendState(player, player)
    }

    // -----------------------------------------------------------------------
    // Level-wide state
    // -----------------------------------------------------------------------

    fun broadcastPhase(level: ServerLevel, positions: Collection<Long>, phased: Boolean) =
        sendTo(level.players(), if (phased) PHASE else UNPHASE) { writePositions(it, positions) }

    private fun writePositions(buf: FriendlyByteBuf, positions: Collection<Long>) {
        buf.writeVarInt(positions.size)
        for (pos in positions) buf.writeLong(pos)
    }

    fun broadcastStutter(level: ServerLevel, shipId: Long, ticks: Int) =
        sendTo(level.players(), STUTTER) { it.writeLong(shipId); it.writeVarInt(ticks) }

    // -----------------------------------------------------------------------
    // Visuals
    // -----------------------------------------------------------------------

    /** One [SpellVfx] visual, to the players tracking [a]'s chunk or [b]'s. */
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

    // -----------------------------------------------------------------------
    // Merged beams
    // -----------------------------------------------------------------------

    /** This tick's merged beams in [level] and their parents' cut lengths (by caster entity
     *  id), to the players near any of them. Sent every tick while any exist. */
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

    /** Players who had merged beams but got none last tick: their merges ended, or they're
     *  out of sight of them. An empty list clears them. */
    private fun clearEndedMerges() {
        if (holdingMerges.isEmpty() && sentMerges.isEmpty()) return
        val ended = holdingMerges.filter { it !in sentMerges && !it.hasDisconnected() }
        if (ended.isNotEmpty()) sendTo(ended, MERGED) { buf -> writeMerged(buf, emptyList(), emptyMap()) }
        holdingMerges = HashSet(sentMerges)
        sentMerges.clear()
    }

    // -----------------------------------------------------------------------
    // Recipients
    // -----------------------------------------------------------------------

    /** The players tracking [entity], and [entity] itself if it's a player. */
    private fun tracking(level: ServerLevel, entity: Entity): Collection<ServerPlayer> {
        val out = ArrayList<ServerPlayer>()
        val tracked = (level.chunkSource.chunkMap as ChunkMapEntityMapAccessor).`enderkinesis$getEntityMap`().get(entity.id)
        (tracked as? TrackedEntitySeenByAccessor)?.`enderkinesis$getSeenBy`()?.forEach { out += it.player }
        if (entity is ServerPlayer && entity !in out) out += entity
        return out
    }

    /** The players tracking the chunk of any of [points]. A point on a ship counts at its
     *  shipyard chunk and at its chunk in the world. */
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
