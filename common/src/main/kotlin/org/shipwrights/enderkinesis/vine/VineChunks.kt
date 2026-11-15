package org.shipwrights.enderkinesis.vine

import dev.architectury.event.events.common.ChunkEvent
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ImposterProtoChunk
import net.minecraft.world.level.chunk.ProtoChunk
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.duck.WorldgenVineHolder
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.chains.ChainLifecycle
import org.valkyrienskies.core.api.chains.ServerChain
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.shipObjectWorld

internal const val CHUNK_TAG = "enderkinesis:woggak_vines"
/** A page closes past this size; a live entry is at most 99 bytes and a parked or new one carries its nodes, far under the 1 MiB payload cap. */
private const val PAGE_BYTES = 256 * 1024
/** What follows a synced vine's head: nothing, the frozen shape of a sleeping vine, or the path of a new one. */
const val VINE_SHAPE_NONE = 0
const val VINE_SHAPE_PARKED = 1
const val VINE_SHAPE_SEED = 2

/** The saved state of one vine whose chunk unloaded: enough to make its chain again in the same shape. */
class VineRecord(
    val id: Long,
    val start: WoggakVineAnchor,
    val end: WoggakVineAnchor,
    val fadeStart: Long,
    val fromBreak: Boolean,
    val dropsOnFade: Boolean,
    val restLength: Double,
    /** World node positions, x, y, z triples. */
    val positions: DoubleArray,
    val brokenLinks: IntArray,
    /** The VS chain at the save; VS restores it from its own save, so the rebuild deletes it. */
    val chain: Long,
    val reelCredit: Double = 0.0,
    val spacing: Double = VINE_TARGET_SPACING,
    /** A worldgen vine that no one used yet; only such a vine sleeps. */
    val natural: Boolean = false,
    val width: Double = VINE_WIDTH,
    /** Fire fronts as (link, direction, ticks to the next step) triples; see [saveBurns]. */
    val burns: IntArray = NO_SAVED_BURNS,
) {
    init {
        require(positions.size % 3 == 0 && positions.size >= 6) { "Woggak Vine record $id has ${positions.size} coordinates" }
        require(validSavedBurns(burns)) { "Woggak Vine record $id has ${burns.size} burn values" }
        require(restLength.isFinite() && restLength > 0) { "Woggak Vine record $id has rest length $restLength" }
        require(spacing.isFinite() && spacing > 0) { "Woggak Vine record $id has spacing $spacing" }
        require(validVineWidth(width)) { "Woggak Vine record $id has width $width" }
    }

    val nodeCount: Int get() = positions.size / 3
    internal val bounds = VineBounds.of(positions)

    fun save(): CompoundTag = CompoundTag().apply {
        // A bare id here is CompoundTag.getId().
        putLong("id", this@VineRecord.id)
        putAnchor("a", start)
        putAnchor("b", end)
        putLong("fade", fadeStart)
        putBoolean("fromBreak", fromBreak)
        putBoolean("noDrops", !dropsOnFade)
        putDouble("rest", restLength)
        putLongArray("nodes", LongArray(positions.size) { positions[it].toRawBits() })
        putIntArray("broken", brokenLinks)
        putLong("chain", chain)
        putDouble("reelCredit", reelCredit)
        putDouble("spacing", spacing)
        putBoolean("natural", natural)
        putDouble("width", width)
        putIntArray("burns", burns)
    }

    companion object {
        /** Null when the tag does not hold a vine that can be made again. */
        fun load(tag: CompoundTag): VineRecord? {
            val bits = tag.getLongArray("nodes")
            val positions = DoubleArray(bits.size) { Double.fromBits(bits[it]) }
            val rest = tag.getDouble("rest")
            val spacing = tag.getDouble("spacing")
            val width = tag.getDouble("width")
            if (positions.size % 3 != 0 || positions.size < 6 || positions.any { !it.isFinite() }) return null
            if (!rest.isFinite() || rest <= 0 || !spacing.isFinite() || spacing <= 0 || !validVineWidth(width)) return null
            val burns = tag.getIntArray("burns")
            if (!validSavedBurns(burns)) return null
            return VineRecord(
                tag.getLong("id"), tag.getAnchor("a"), tag.getAnchor("b"), tag.getLong("fade"),
                tag.getBoolean("fromBreak"), !tag.getBoolean("noDrops"), rest, positions, tag.getIntArray("broken"),
                tag.getLong("chain"), validReelCredit(tag.getDouble("reelCredit")), spacing,
                tag.getBoolean("natural"), width, burns,
            )
        }
    }
}

/** The chunk of the first anchor on a world block, start before end; null when no end is on a world block. */
fun fixedBlockHome(start: WoggakVineAnchor, end: WoggakVineAnchor): Long? = start.blockHome() ?: end.blockHome()

private fun WoggakVineAnchor.blockHome(): Long? {
    val point = point ?: return null
    if (released || point.joint != null || point.shipId != null) return null
    return ChunkPos.asLong(point.block.x shr 4, point.block.z shr 4)
}

/** The chunk of the centre of the first [nodes] nodes of [positions] (x, y, z triples, world coordinates). */
fun centroidHome(positions: DoubleArray, nodes: Int): Long {
    require(nodes >= 1 && nodes * 3 <= positions.size) { "Node count $nodes" }
    var x = 0.0
    var z = 0.0
    for (i in 0 until nodes) {
        x += positions[i * 3]
        z += positions[i * 3 + 2]
    }
    return ChunkPos.asLong(Mth.floor(x / nodes) shr 4, Mth.floor(z / nodes) shr 4)
}

internal fun pathHome(path: List<Vector3dc>): Long {
    require(path.isNotEmpty()) { "Empty vine path" }
    var x = 0.0
    var z = 0.0
    for (p in path) { x += p.x(); z += p.z() }
    return ChunkPos.asLong(Mth.floor(x / path.size) shr 4, Mth.floor(z / path.size) shr 4)
}

internal fun ServerLevel.hasChunk(chunk: Long): Boolean = chunkSource.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))

// Chunk loads can run off the server thread (C2ME), so records wait here until the server tick makes the chains.
private val pendingVines = ConcurrentHashMap<ResourceKey<Level>, ConcurrentHashMap<Long, List<VineRecord>>>()

/**
 * Chunk saves can also run off the server thread, so they read these copies, which only the server thread makes.
 * [snapshots] are of loaded chunks; [orphans] are of chunks that unloaded, kept until their save took them.
 */
private class VineSnapshots {
    val snapshots = ConcurrentHashMap<Long, ListTag>()
    val orphans = ConcurrentHashMap<Long, ListTag>()
    /** What the last save of each loaded chunk wrote, so an unload knows if a save is still to come. */
    val written = ConcurrentHashMap<Long, Written>()
}

private class Written(val copy: ListTag?, val pending: List<VineRecord>?)

private val vineSnapshots = ConcurrentHashMap<ResourceKey<Level>, VineSnapshots>()

private fun ServerLevel.snapshotsOf(): VineSnapshots = vineSnapshots.computeIfAbsent(dimension()) { VineSnapshots() }

/** Vines per tick whose chunk copies the round-robin refresh makes again, so moving vines save near their place. */
private const val SNAPSHOT_VINES_PER_TICK = 64

internal fun registerVineChunks() {
    ChunkEvent.LOAD_DATA.register(ChunkEvent.LoadData { chunk, level, tag ->
        // A chunk still in worldgen keeps its vines on itself until it becomes full; see adoptWorldgenVines.
        if (chunk.isWorldgenChunk) {
            (chunk as WorldgenVineHolder).`enderkinesis$setWorldgenVines`(readVineRecords(chunk.pos, tag))
            return@LoadData
        }
        if (level == null) return@LoadData
        val key = chunk.pos.toLong()
        level.snapshotsOf().orphans.remove(key)
        val pending = pendingVines.computeIfAbsent(level.dimension()) { ConcurrentHashMap() }
        val records = readVineRecords(chunk.pos, tag)
        if (records.isEmpty()) pending.remove(key) else pending[key] = records
    })
    ChunkEvent.SAVE_DATA.register(ChunkEvent.SaveData { chunk, level, tag ->
        if (chunk.isWorldgenChunk) {
            val records = (chunk as WorldgenVineHolder).`enderkinesis$getWorldgenVines`()
            if (records.isNotEmpty()) tag.put(CHUNK_TAG, ListTag().apply { records.forEach { add(it.save()) } })
            return@SaveData
        }
        if (level == null) return@SaveData
        val list = level.chunkVineTags(chunk.pos.toLong())
        if (list.isEmpty()) return@SaveData
        tag.put(CHUNK_TAG, list)
        // Never mark the chunk unsaved here: the flush save (stop, save-all flush) repeats while a save writes, so it never ends.
    })
    LifecycleEvent.SERVER_LEVEL_UNLOAD.register { level ->
        pendingVines.remove(level.dimension())
        vineSnapshots.remove(level.dimension())
    }
    LifecycleEvent.SERVER_STOPPED.register {
        pendingVines.clear()
        vineSnapshots.clear()
    }
}

private val ChunkAccess.isWorldgenChunk: Boolean get() = this is ProtoChunk && this !is ImposterProtoChunk

internal fun readVineRecords(pos: ChunkPos, tag: CompoundTag): List<VineRecord> {
    if (!tag.contains(CHUNK_TAG, Tag.TAG_LIST.toInt())) return emptyList()
    val list = tag.getList(CHUNK_TAG, Tag.TAG_COMPOUND.toInt())
    val records = ArrayList<VineRecord>(list.size)
    for (i in 0 until list.size) {
        val record = VineRecord.load(list.getCompound(i))
        if (record == null) LOG.warn("Woggak Vine: a bad record in chunk {} is dropped", pos) else records.add(record)
    }
    return records
}

/** Called when [proto] becomes a full chunk: its worldgen vines wait for the chunk like saved vines do. */
fun ServerLevel.adoptWorldgenVines(proto: ProtoChunk) {
    val records = (proto as WorldgenVineHolder).`enderkinesis$getWorldgenVines`()
    if (records.isEmpty()) return
    pendingVines.computeIfAbsent(dimension()) { ConcurrentHashMap() }.merge(proto.pos.toLong(), records) { old, new -> old + new }
}

/** The records of the chunk, on any thread: those still to be made, then the last copy of its live vines. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.chunkVineTags(chunk: Long): ListTag {
    val list = ListTag()
    val pending = pendingVines[dimension()]?.get(chunk)
    pending?.forEach { list.add(it.save()) }
    val snapshots = snapshotsOf()
    if (server.isSameThread) loadedWoggakVines?.let { refreshSnapshot(it, chunk) }
    val live = snapshots.snapshots[chunk]
    val copy = live ?: snapshots.orphans.remove(chunk)
    // A published copy is never changed again, so a deep copy here is safe on any thread.
    copy?.forEach { list.add(it.copy()) }
    if (live != null || pending != null) snapshots.written[chunk] = Written(live, pending)
    return list
}

/** Makes the save copy of the live vines of [chunk] again; server thread only. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.refreshSnapshot(data: WoggakVineData, chunk: Long) {
    val snapshots = snapshotsOf().snapshots
    val list = ListTag()
    for (vine in data.inChunk(chunk)) {
        if (vine.shipEnds != 0) continue
        val record = vineRecord(vine)
        if (record == null) LOG.warn("Woggak Vine {} has no node positions; it is not saved", vine.id) else list.add(record.save())
    }
    if (list.isEmpty()) snapshots.remove(chunk) else snapshots[chunk] = list
}

/** Makes the copies of the chunks of the next [SNAPSHOT_VINES_PER_TICK] live vines again, in turn. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.refreshSnapshotsInTurn(data: WoggakVineData) {
    val vines = data.vines
    if (vines.isEmpty()) return
    data.snapshotDone.clear()
    var spent = 0
    var seen = 0
    while (spent < SNAPSHOT_VINES_PER_TICK && seen < vines.size) {
        if (data.snapshotCursor >= vines.size) data.snapshotCursor = 0
        val vine = vines[data.snapshotCursor++]
        seen++
        if (vine.shipEnds != 0 || !data.snapshotDone.add(vine.home)) continue
        refreshSnapshot(data, vine.home)
        // A save clears the flag; set it again so the next autosave or unload save takes the moved vines.
        chunkSource.getChunkNow(ChunkPos.getX(vine.home), ChunkPos.getZ(vine.home))?.isUnsaved = true
        spent += data.inChunk(vine.home).size
    }
}

/** The live record of [vine]; before VS has node positions, the path the vine was made along. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.vineRecord(vine: WoggakVine): VineRecord? {
    val chain = shipObjectWorld.allChains.getById(vine.chain)
    val status = chain?.status
    val nodes = status?.nodeCount ?: 0
    val rest = status?.paidLength() ?: 0.0
    if (chain != null && status != null && nodes >= 2 && rest.isFinite() && rest > 0) {
        val positions = DoubleArray(nodes * 3)
        if (chain.copyNodePositions(positions) == nodes && positions.all { it.isFinite() }) {
            return VineRecord(
                vine.id, vine.start, vine.end, vine.fadeStart, vine.fromBreak, vine.dropsOnFade, rest, positions,
                status.brokenLinks.toIntArray(), vine.chain.value, vine.reelCredit, vine.spacing, vine.natural, vine.width,
                vine.burns.saveBurns(gameTime),
            )
        }
    }
    val seed = vine.seedNodes ?: return null
    if (seed.size < 6 || !vine.seedRest.isFinite() || vine.seedRest <= 0) return null
    return VineRecord(
        vine.id, vine.start, vine.end, vine.fadeStart, vine.fromBreak, vine.dropsOnFade, vine.seedRest, seed,
        IntArray(0), vine.chain.value, vine.reelCredit, vine.spacing, vine.natural, vine.width, vine.burns.saveBurns(gameTime),
    )
}

/** Takes the vines of [chunk] out of the physics world; ChunkMap saved them to the chunk just before. */
@OptIn(GameTickOnly::class)
fun ServerLevel.unloadVineChunk(chunk: Long) {
    val data = loadedWoggakVines
    val pending = pendingVines[dimension()]?.remove(chunk)
    // An async save of this chunk can still come after the unload; it takes these copies then.
    val snapshots = snapshotsOf()
    val live = snapshots.snapshots.remove(chunk)
    val written = snapshots.written.remove(chunk)
    if (written == null || written.copy !== live || written.pending !== pending) {
        val orphan = ListTag()
        live?.let { orphan.addAll(it) }
        pending?.forEach { orphan.add(it.save()) }
        if (orphan.isNotEmpty()) snapshots.orphans[chunk] = orphan
    }
    if (data == null) return
    pending?.forEach { data.unloadedIds.add(it.id) }
    val here = data.inChunk(chunk)
    if (here.isEmpty()) return
    for (vine in here.toList()) {
        // VS suspends a ship-held chain with its ship, so that vine stays in the level data.
        if (vine.shipEnds != 0) continue
        releaseJointsTo(data, vine)
        vine.start.pair = null
        vine.end.pair = null
        if (!shipObjectWorld.deleteChain(vine.chain)) LOG.warn("Woggak Vine {} was already removed", vine.chain.value)
        data.remove(vine)
        data.unloadedIds.add(vine.id)
        data.anchorsDirty = true
        data.jointsDirty = true
    }
}

/** The ends joined to [vine] let go of their pairs; they wait, joined, until [vine] is back. */
private fun ServerLevel.releaseJointsTo(data: WoggakVineData, vine: WoggakVine) {
    for (other in data.vines) {
        for (anchor in arrayOf(other.start, other.end)) {
            if (!anchor.isJoined || anchor.point?.joint?.vine != vine.id) continue
            anchor.pair?.restoreContacts(this)
            anchor.pair = null
        }
    }
}

/** True when the target of [joint] is not loaded but may come back, so the joined end must wait. */
internal fun ServerLevel.isJointWaiting(data: WoggakVineData, joint: VineJoint): Boolean {
    if (data.vine(joint.vine) != null) return false
    if (data.unloadedIds.contains(joint.vine)) return true
    val pending = pendingVines[dimension()]
    if (pending != null && pending.values.any { records -> records.any { it.id == joint.vine } }) return true
    return !hasChunk(joint.home)
}

/**
 * Makes the chains of the waiting records of loaded chunks, nearest first, up to [VINE_WAKE_NODES_PER_TICK] nodes a
 * tick. A chunk wakes when a record in it is within [VINE_WAKE_DISTANCE] of an observer, is not natural, or is joined;
 * other records sleep: the chunk saves them and its trackers draw them frozen.
 */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.rebuildLoadedVines(data: WoggakVineData) {
    val pending = pendingVines[dimension()] ?: return
    if (pending.isEmpty()) return
    val sleep = data.sleep
    for (chunk in pending.keys) {
        if (!hasChunk(chunk)) continue
        val records = pending[chunk] ?: continue
        val distance = if (sleep.jointHomes.contains(chunk)) 0.0 else sleep.wakeDistance(data, records)
        if (distance <= VINE_WAKE_DISTANCE * VINE_WAKE_DISTANCE) sleep.offerWake(chunk, distance)
    }
    for (i in 0 until sleep.wakeCount) {
        val chunk = sleep.wakeChunks[i]
        val records = pending[chunk] ?: continue
        var nodes = 0
        for (record in records) nodes += wakeNodes(record)
        if (!sleep.budget.take(nodes)) break
        // The joints move after the whole chunk is back, so a vine of this chunk joined to a split one moves too.
        val splits = records.mapNotNull { record -> rebuildVine(data, record, chunk)?.let { record to it } }
        for ((record, pieces) in splits) {
            for (pair in data.repointSplit(record.id, null, pieces)) pair.restoreContacts(this)
        }
        // The snapshot takes the records before the pending entry goes, so an async save always sees them.
        refreshSnapshot(data, chunk)
        pending.remove(chunk, records)
        data.anchorsDirty = true
        data.jointsDirty = true
    }
}

/**
 * Squared blocks from [records] to the nearest observer. A player vine, or a record joined to a live vine, wakes at
 * any distance, so it is 0.
 */
private fun VineSleep.wakeDistance(data: WoggakVineData, records: List<VineRecord>): Double {
    var best = Double.POSITIVE_INFINITY
    for (record in records) {
        if (!record.natural || record.start.joinsLive(data) || record.end.joinsLive(data)) return 0.0
        best = minOf(best, nearestSquared(record.bounds))
    }
    return best
}

private fun WoggakVineAnchor.joinsLive(data: WoggakVineData): Boolean {
    val joint = point?.joint ?: return false
    return isJoined && data.vine(joint.vine) != null
}

/**
 * Takes live vine [index] out of physics and keeps it as a record of its home chunk, which its trackers draw frozen.
 * False when it cannot: its chain is not active and whole, or its home chunk is not loaded.
 */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.parkVine(data: WoggakVineData, index: Int): Boolean {
    val vine = data.vines[index]
    val world = shipObjectWorld
    val status = world.allChains.getById(vine.chain)?.status ?: return false
    if (status.lifecycle != ChainLifecycle.ACTIVE || status.brokenLinks.isNotEmpty()) return false
    val home = vine.home
    // Only a loaded chunk saves its records.
    if (!hasChunk(home)) return false
    val record = vineRecord(vine) ?: return false
    // The record is in place before the vine goes, so an async save of the chunk never misses it.
    addParked(home, record)
    if (!world.deleteChain(vine.chain)) LOG.warn("Woggak Vine {} was already removed", vine.chain.value)
    data.removeAt(index)
    data.unloadedIds.add(vine.id)
    data.anchorsDirty = true
    data.jointsDirty = true
    refreshSnapshot(data, home)
    return true
}

/**
 * Keeps [record] asleep in its home chunk from its birth, with no chain; a wake makes the chain. False when the home
 * chunk is not loaded or the vine already exists.
 */
internal fun ServerLevel.parkNewVine(data: WoggakVineData, record: VineRecord): Boolean {
    val home = fixedBlockHome(record.start, record.end) ?: centroidHome(record.positions, record.nodeCount)
    if (!hasChunk(home) || data.vine(record.id) != null || parkedIn(home).any { it.id == record.id }) return false
    addParked(home, record)
    data.dirtyChunks.add(home)
    return true
}

private fun ServerLevel.addParked(home: Long, record: VineRecord) {
    pendingVines.computeIfAbsent(dimension()) { ConcurrentHashMap() }.merge(home, listOf(record)) { old, new -> old + new }
    chunkSource.getChunkNow(ChunkPos.getX(home), ChunkPos.getZ(home))?.isUnsaved = true
}

/** The sleeping and not yet made vines of [chunk]. */
private fun ServerLevel.parkedIn(chunk: Long): List<VineRecord> = pendingVines[dimension()]?.get(chunk).orEmpty()

/**
 * Makes [record]'s chain again from its saved nodes; each unbroken run is one vine, and the first keeps the id.
 * Returns the pieces when the record split, else null.
 */
@OptIn(GameTickOnly::class)
private fun ServerLevel.rebuildVine(data: WoggakVineData, record: VineRecord, chunk: Long): List<SplitPiece>? {
    if (data.vine(record.id) != null) return null
    deleteUnusedChain(data, record.chain)
    val nodes = record.nodeCount
    val runs = unbrokenRuns(nodes, record.brokenLinks.toSet())
    val restPerLink = record.restLength / (nodes - 1)
    val pieces = ArrayList<SplitPiece>(runs.size)
    var id = record.id
    for (run in runs) {
        val start = if (run.first == 0) record.start else null
        val end = if (run.last == nodes - 1) record.end else null
        val vine = addPiece(
            record.positions, null, run, restPerLink * (run.last - run.first), start, end,
            record.fromBreak, record.dropsOnFade, record.spacing, id, chunk, record.width,
        )
        if (vine != null) {
            vine.natural = record.natural && runs.size == 1
            vine.fadeStart = record.fadeStart
            // The credit stays with the first piece, which keeps the id; a later piece starts with none.
            if (id == record.id) vine.reelCredit = record.reelCredit
            data.restoreBurns(vine, record.burns, gameTime, run)
            pieces += SplitPiece(run, vine)
        }
        id = newVineId()
    }
    return if (runs.size == 1 && runs[0].first == 0 && runs[0].last == nodes - 1) null else pieces
}

/** Deletes the VS chains that the last session's chunk vines left in the VS save. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.deleteStrayVineChains(data: WoggakVineData) {
    if (data.strayChains.isEmpty()) return
    val strays = data.strayChains
    data.strayChains = LongArray(0)
    for (chain in strays) deleteUnusedChain(data, chain)
}

@OptIn(GameTickOnly::class)
private fun ServerLevel.deleteUnusedChain(data: WoggakVineData, chain: Long) {
    if (chain == 0L || data.vines.any { it.chain.value == chain }) return
    val world = shipObjectWorld
    val id = ChainId(chain)
    if (world.allChains.getById(id) != null && !world.deleteChain(id)) LOG.warn("Woggak Vine chain {} was already removed", chain)
}

/** Moves a vine that has no world block anchor to the loaded chunk under the centre of its nodes. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.followHome(data: WoggakVineData, vine: WoggakVine, chain: ServerChain) {
    val home = fixedBlockHome(vine.start, vine.end) ?: run {
        val nodes = chain.status.nodeCount
        if (nodes < 1) return
        if (data.scratch.size < nodes * 3) data.scratch = DoubleArray(nodes * 3)
        if (chain.copyNodePositions(data.scratch) != nodes) return
        centroidHome(data.scratch, nodes)
    }
    // A vine homed in a chunk that is not loaded would never be saved.
    if (home != vine.home && hasChunk(home)) data.rehome(vine, home)
}

/** The first loaded chunk of: the block anchor, the path centre, [fallback]; else the first of them. */
internal fun ServerLevel.chooseHome(vine: WoggakVine, path: List<Vector3dc>, fallback: Long?): Long {
    val first = fixedBlockHome(vine.start, vine.end) ?: pathHome(path)
    if (hasChunk(first)) return first
    if (fallback != null && hasChunk(fallback)) return fallback
    for (p in path) {
        val chunk = ChunkPos.asLong(Mth.floor(p.x()) shr 4, Mth.floor(p.z()) shr 4)
        if (hasChunk(chunk)) return chunk
    }
    return first
}

/** Sends each changed chunk's vines to the players that track it, and marks the chunk for the next save. */
@OptIn(GameTickOnly::class)
internal fun ServerLevel.flushVineSync(data: WoggakVineData) {
    refreshSnapshotsInTurn(data)
    if (data.dirtyChunks.isEmpty()) {
        data.splits.clear()
        return
    }
    val iterator = data.dirtyChunks.iterator()
    while (iterator.hasNext()) {
        val chunk = iterator.nextLong()
        val pos = ChunkPos(chunk)
        if (hasChunk(chunk)) refreshSnapshot(data, chunk)
        chunkSource.getChunkNow(pos.x, pos.z)?.isUnsaved = true
        val players = chunkSource.chunkMap.getPlayers(pos, false)
        if (players.isNotEmpty()) sendVinePages(players, chunk, data.inChunk(chunk), parkedIn(chunk), data.splits.get(chunk))
    }
    data.dirtyChunks.clear()
    data.splits.clear()
}

/** Called when [chunk] is sent to this player. */
fun ServerPlayer.sendVineChunk(chunk: Long) {
    val level = serverLevel()
    val vines = level.loadedWoggakVines?.inChunk(chunk) ?: return
    val parked = level.parkedIn(chunk)
    if (vines.isNotEmpty() || parked.isNotEmpty()) sendVinePages(listOf(this), chunk, vines, parked)
}

/** Called when this player stops tracking [chunk]. */
fun ServerPlayer.forgetVineChunk(chunk: Long) {
    val data = serverLevel().loadedWoggakVines ?: return
    // A change that the next flush would send is lost once the player no longer tracks the chunk.
    if (data.inChunk(chunk).isEmpty() && !data.dirtyChunks.contains(chunk) && serverLevel().parkedIn(chunk).isEmpty()) return
    NetworkManager.sendToPlayer(this, WOGGAK_VINE_FORGET, FriendlyByteBuf(Unpooled.buffer()).apply { writeLong(chunk) })
}

/**
 * Sends the live [vines] and then the [parked] ones of [chunk]. The first page also holds the [splits] of vines of
 * [chunk], so the client draws each old vine until its pieces show.
 */
private fun sendVinePages(
    players: List<ServerPlayer>, chunk: Long, vines: List<WoggakVine>, parked: List<VineRecord>, splits: List<VineSplit>? = null,
) {
    val total = vines.size + parked.size
    var next = 0
    var first = true
    do {
        val entries = FriendlyByteBuf(Unpooled.buffer())
        var count = 0
        while (next < total && entries.writerIndex() < PAGE_BYTES) {
            if (next < vines.size) entries.writeVine(vines[next]) else entries.writeParked(parked[next - vines.size])
            next++
            count++
        }
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeLong(chunk)
        buf.writeBoolean(first)
        buf.writeVarInt(count)
        buf.writeBytes(entries)
        entries.release()
        val pageSplits = if (first) splits.orEmpty() else emptyList()
        buf.writeVarInt(pageSplits.size)
        for (split in pageSplits) {
            buf.writeLong(split.old)
            buf.writeLongArray(split.pieces)
        }
        for (player in players) NetworkManager.sendToPlayer(player, WOGGAK_VINE_SYNC, FriendlyByteBuf(buf.copy()))
        buf.release()
        first = false
    } while (next < total)
}

/** A new vine before VS has its nodes also sends the path it was made along, so the client draws it at once. */
private fun FriendlyByteBuf.writeVine(vine: WoggakVine) {
    writeVineHead(vine.id, vine.chain.value, vine.fadeStart, vine.start, vine.end, vine.spacing, vine.width, vine.burns)
    val seed = vine.seedNodes
    if (seed == null || seed.size < 6) {
        writeByte(VINE_SHAPE_NONE)
        return
    }
    writeByte(VINE_SHAPE_SEED)
    writeNodes(seed, seed.size / 3)
}

/** A sleeping vine has no chain, so its nodes go along. */
private fun FriendlyByteBuf.writeParked(record: VineRecord) {
    writeVineHead(record.id, 0L, record.fadeStart, record.start, record.end, record.spacing, record.width, emptyList())
    writeByte(VINE_SHAPE_PARKED)
    writeDouble(record.restLength)
    writeNodes(record.positions, record.nodeCount)
}

/** The first node as doubles, the others as float offsets from it. */
private fun FriendlyByteBuf.writeNodes(p: DoubleArray, nodes: Int) {
    writeVarInt(nodes)
    writeDouble(p[0]); writeDouble(p[1]); writeDouble(p[2])
    for (i in 3 until nodes * 3) writeFloat((p[i] - p[i % 3]).toFloat())
}

@Suppress("LongParameterList") // The synced fields of one vine, live or parked.
private fun FriendlyByteBuf.writeVineHead(
    id: Long, chain: Long, fadeStart: Long, start: WoggakVineAnchor, end: WoggakVineAnchor, spacing: Double, width: Double,
    burns: List<VineBurnFront>,
) {
    writeLong(id)
    writeLong(chain)
    writeLong(fadeStart)
    writeByte(anchorFlags(start, end))
    writeJointTarget(start)
    writeHold(start)
    writeJointTarget(end)
    writeHold(end)
    writeDouble(spacing)
    writeDouble(width)
    // Each burn step is a split, and a split sends the chunk again, so the burning links need no packet of their own.
    writeVarInt(burns.size)
    for (front in burns) writeVarInt(front.link)
}

/** The target vine id and node of a joined end, so the client draws the end on the target node. */
private fun FriendlyByteBuf.writeJointTarget(anchor: WoggakVineAnchor) {
    val joint = anchor.point?.joint
    if (!anchor.isJoined || joint == null) return
    writeLong(joint.vine)
    writeVarInt(joint.node)
}

/**
 * The ship (only when ship-held), block and point of a held end. The client draws a ship-held end on the ship's
 * render position, and puts the moss pad and the rope inset on the block face.
 */
private fun FriendlyByteBuf.writeHold(anchor: WoggakVineAnchor) {
    val point = anchor.point
    if (point == null || !(anchor.isShipHeld || anchor.isBlockHeld)) return
    point.shipId?.let { writeLong(it) }
    writeLong(point.block.asLong())
    writeDouble(point.local.x()); writeDouble(point.local.y()); writeDouble(point.local.z())
}
