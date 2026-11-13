package org.shipwrights.enderkinesis.vine

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.InteractionEvent
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import org.joml.Vector3d
import org.joml.Vector3dc
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.valkyrienskies.core.api.chains.ChainAttachmentState
import org.valkyrienskies.core.api.chains.ChainCreateData
import org.valkyrienskies.core.api.chains.ChainEnd
import org.valkyrienskies.core.api.chains.ChainEndpoint
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.chains.ChainLifecycle
import org.valkyrienskies.core.api.chains.ServerChain
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.ceil

/** S2C: one page of the vines of one chunk; the first page replaces the chunk's vines on the client. */
val WOGGAK_VINE_SYNC: ResourceLocation = EnderkinesisMod.id("woggak_vine/sync")
/** S2C: the client forgets the vines of one chunk. */
val WOGGAK_VINE_FORGET: ResourceLocation = EnderkinesisMod.id("woggak_vine/forget")

/** Ticks from the loss of the last anchor to the end of the grey fade; the client uses it for the fade too. */
const val WOGGAK_VINE_FADE_TICKS = 60

/** At least four particle radii, so that no node count makes a link shorter than one particle diameter. */
internal const val VINE_TARGET_SPACING = 0.4
internal const val PARTICLE_RADIUS = 0.1
internal const val MASS_PER_LENGTH = 5.0
/** Newtons on one link or end joint. */
internal const val VINE_BREAK_FORCE = 1.0e7
/** Rigid for now: a finite stiffness snaps vines under their own movement; a VS bug report is pending. */
internal const val VINE_LINK_STIFFNESS = Double.POSITIVE_INFINITY
private const val MIN_LENGTH = 1.0
internal const val BLOCKS_PER_ITEM = 4.0
private const val SAG_FRACTION = 0.08
private const val PATH_POINTS = 9
private const val DATA_NAME = "enderkinesis_woggak_vines"

internal val LOG = LogUtils.getLogger()

/**
 * [local] is in shipyard coordinates when [shipId] is set, else in world coordinates.
 * A point with a [joint] sits on another vine; then [block] has no meaning.
 */
class VinePoint(val block: BlockPos, val local: Vector3dc, val shipId: Long?, val joint: VineJoint? = null) {
    fun save(): CompoundTag = CompoundTag().apply {
        putLong("block", block.asLong())
        putDouble("x", local.x()); putDouble("y", local.y()); putDouble("z", local.z())
        if (shipId != null) putLong("ship", shipId)
        joint?.let { put("joint", it.save()) }
    }

    companion object {
        fun load(tag: CompoundTag) = VinePoint(
            BlockPos.of(tag.getLong("block")),
            Vector3d(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")),
            if (tag.contains("ship", Tag.TAG_LONG.toInt())) tag.getLong("ship") else null,
            if (tag.contains("joint", Tag.TAG_COMPOUND.toInt())) VineJoint.load(tag.getCompound("joint")) else null,
        )
    }
}

/** A null [point] is a free end. */
class WoggakVineAnchor(val point: VinePoint?) {
    var released = point == null
    /** The physics-thread pair of a joined end, made when both chains are active; not saved. */
    var pair: JointPair? = null
}

/**
 * [id] is ours and stays the same over a chunk unload and load; [chain] is the VS chain, which a reload replaces.
 * [fromBreak]: made by a split after a link broke; such a vine does not split again on a break.
 */
class WoggakVine(
    val id: Long, val chain: ChainId, val start: WoggakVineAnchor, end: WoggakVineAnchor, val fromBreak: Boolean = false,
) {
    /** A thrown vine gets its end anchor when the tip sticks. */
    var end = end
        internal set
    /** Game time when the last anchor went, or -1. */
    var fadeStart = -1L
    /** False on a piece that a creative cut set free; else the vine drops vine items at the end of its fade. */
    var dropsOnFade = true
    /** Reeled-in blocks not yet paid as an item; always under [BLOCKS_PER_ITEM]. */
    var reelCredit = 0.0
    /** The chunk (ChunkPos long) that saves this vine and whose trackers get it; see [fixedBlockHome]. */
    var home = 0L
        internal set
    /** Nodes that grip a ship's hull; saved with the vine. */
    var grips: List<VineGrip> = emptyList()
    /** The physics grips of [grips] on chain [hullGripChain]; node ids change with the chain. Not saved. */
    internal var hullGrips: Array<HullGrip>? = null
    internal var hullGripChain = 0L
    /** The path this vine was made along, for a save before VS has its first node positions; not saved. */
    internal var seedNodes: DoubleArray? = null
    internal var seedRest = 0.0

    /** Bit 0: the start is joined to another vine; bit 1: the end is. */
    val joinedEnds: Int
        get() = (if (start.isJoined) JOINED_START else 0) or (if (end.isJoined) JOINED_END else 0)

    /** Bit 2: a ship block holds the start; bit 3: one holds the end. */
    val shipEnds: Int
        get() = (if (start.isShipHeld) SHIP_START else 0) or (if (end.isShipHeld) SHIP_END else 0)
}

const val JOINED_START = 1
const val JOINED_END = 2
const val SHIP_START = 4
const val SHIP_END = 8

internal val WoggakVineAnchor.isJoined get() = !released && point?.joint != null
internal val WoggakVineAnchor.isShipHeld get() = !released && point?.joint == null && point?.shipId != null

/** A new vine id; random, so that no counter has to be saved before the chunks that hold the ids. */
internal fun newVineId(): Long {
    val id = ThreadLocalRandom.current().nextLong()
    return if (id == 0L) 1L else id
}

/**
 * The vines of the loaded chunks, and the ship-held vines of all chunks. Only the ship-held ones are saved here;
 * VS suspends their chains with the ship. The chunk saves each other vine (see VineChunks).
 */
class WoggakVineData : SavedData() {
    private val live = ArrayList<WoggakVine>()
    val vines: List<WoggakVine> get() = live
    private val byId = HashMap<Long, WoggakVine>()
    private val homes = Long2ObjectOpenHashMap<ArrayList<WoggakVine>>()
    /** Chunks whose vine list changed, so the next sync sends them to their trackers. */
    internal val dirtyChunks = LongOpenHashSet()
    /** Vines that went with their chunk in this session; joints to them wait. Not saved. */
    internal val unloadedIds = LongOpenHashSet()
    /** VS chains of chunk-saved vines at the last save; VS restores them too, so the first tick deletes them. */
    internal var strayChains = LongArray(0)
    /** Next vine of the round-robin save-copy refresh, and the chunks it did this tick; not saved. */
    internal var snapshotCursor = 0
    internal val snapshotDone = LongOpenHashSet()
    /** Server-thread scratch for node positions; not saved. */
    internal var scratch = DoubleArray(0)
    /** Set when a vine, anchor or joint changes, so the next tick checks which vines still reach an anchor. */
    internal var anchorsDirty = true
    /** Set when a joint pair is made or let go, so the next tick sends the pairs to the physics thread. */
    internal var jointsDirty = true
    /** Entity collision snapshot, rebuilt each tick; not saved. */
    val collider = VineCollider()
    internal val contacts = VineContacts()
    /** Players' masses on the vines they stand on, and their push packet budgets; not saved. */
    internal val riding = VineRiding()
    /** Game time of each player's last shear cut, so shears in both hands cut once a click; not saved. */
    internal val lastPlayerCut = HashMap<UUID, Long>()
    /** Game time of each player's last vine shorten; not saved. */
    internal val lastPlayerShorten = HashMap<UUID, Long>()
    /** Reels by vine id, one for each vine, so a link that comes in is paid once; not saved. */
    internal val reels = Long2ObjectOpenHashMap<VineReel>()
    /** Each player's vine hold; not saved, because a hold ends with the session. */
    internal val holds = HashMap<UUID, VineHold>()
    /** Thrown vines in flight, by vine id; they count as anchored. Not saved, so a reload ends the flight in a fade. */
    internal val throws = Long2ObjectOpenHashMap<VineThrow>()
    /** Ritual of Capturing runs; not saved, so a reload leaves the vines as they are. */
    internal val captures = ArrayList<VineCapture>()
    /** Set once the first tick deleted the hold particles that the last session left; not saved. */
    internal var strayHoldsDeleted = false

    fun vine(id: Long): WoggakVine? = byId[id]

    fun inChunk(chunk: Long): List<WoggakVine> = homes.get(chunk) ?: emptyList()

    internal fun add(vine: WoggakVine) {
        live.add(vine)
        byId[vine.id] = vine
        homeList(vine.home).add(vine)
        unloadedIds.remove(vine.id)
        dirtyChunks.add(vine.home)
    }

    internal fun removeAt(index: Int): WoggakVine {
        val vine = live.removeAt(index)
        if (byId[vine.id] === vine) byId.remove(vine.id)
        unhome(vine)
        return vine
    }

    internal fun remove(vine: WoggakVine) {
        val index = live.indexOf(vine)
        if (index >= 0) removeAt(index)
    }

    /** Moves [vine] to [chunk], and the joints to it with it, so a joint knows where its target waits. */
    internal fun rehome(vine: WoggakVine, chunk: Long) {
        if (vine.home == chunk) return
        unhome(vine)
        vine.home = chunk
        homeList(chunk).add(vine)
        dirtyChunks.add(chunk)
        for (other in live) {
            other.start.point?.joint?.let { if (it.vine == vine.id) it.home = chunk }
            other.end.point?.joint?.let { if (it.vine == vine.id) it.home = chunk }
        }
    }

    /** Sends [vine]'s chunk again, after a change that the client draws. */
    internal fun touch(vine: WoggakVine) {
        dirtyChunks.add(vine.home)
    }

    private fun homeList(chunk: Long): ArrayList<WoggakVine> =
        homes.get(chunk) ?: ArrayList<WoggakVine>().also { homes.put(chunk, it) }

    private fun unhome(vine: WoggakVine) {
        val list = homes.get(vine.home) ?: return
        list.remove(vine)
        if (list.isEmpty()) homes.remove(vine.home)
        dirtyChunks.add(vine.home)
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        var strays = LongArray(live.size)
        var strayCount = 0
        for (vine in live) {
            if (vine.shipEnds == 0) {
                strays[strayCount++] = vine.chain.value
                continue
            }
            list.add(CompoundTag().apply {
                putLong("id", vine.id)
                putLong("chain", vine.chain.value)
                putAnchor("a", vine.start)
                putAnchor("b", vine.end)
                putLong("fade", vine.fadeStart)
                putBoolean("fromBreak", vine.fromBreak)
                putBoolean("noDrops", !vine.dropsOnFade)
                putDouble("reelCredit", vine.reelCredit)
                putLong("home", vine.home)
                putGrips(vine.grips)
            })
        }
        if (strayCount < strays.size) strays = strays.copyOf(strayCount)
        tag.put("vines", list)
        tag.put("captures", ListTag().apply { captures.forEach { add(it.save()) } })
        tag.putLongArray("chunkChains", strays)
        return tag
    }

    companion object {
        fun load(tag: CompoundTag): WoggakVineData {
            val data = WoggakVineData()
            val list = tag.getList("vines", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                val t = list.getCompound(i)
                val vine = WoggakVine(t.getLong("id"), ChainId(t.getLong("chain")), t.getAnchor("a"), t.getAnchor("b"), t.getBoolean("fromBreak"))
                vine.fadeStart = t.getLong("fade")
                vine.dropsOnFade = !t.getBoolean("noDrops")
                vine.reelCredit = validReelCredit(t.getDouble("reelCredit"))
                vine.home = t.getLong("home")
                vine.grips = t.getGrips()
                data.add(vine)
            }
            data.strayChains = tag.getLongArray("chunkChains")
            val captures = tag.getList("captures", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until captures.size) VineCapture.load(captures.getCompound(i))?.let(data.captures::add)
            return data
        }
    }
}

internal fun CompoundTag.putAnchor(key: String, anchor: WoggakVineAnchor) {
    anchor.point?.let { put(key, it.save()) }
    putBoolean("${key}r", anchor.released)
}

internal fun CompoundTag.getAnchor(key: String): WoggakVineAnchor {
    val point = if (contains(key, Tag.TAG_COMPOUND.toInt())) VinePoint.load(getCompound(key)) else null
    return WoggakVineAnchor(point).apply { released = getBoolean("${key}r") || point == null }
}

// Lets the entity-move hooks skip the data-storage lookup; filled by the getter, emptied on level unload.
private val loadedVines = ConcurrentHashMap<ServerLevel, WoggakVineData>()

val ServerLevel.woggakVines: WoggakVineData
    get() = dataStorage.computeIfAbsent({ WoggakVineData.load(it) }, ::WoggakVineData, DATA_NAME)
        .also { loadedVines.putIfAbsent(this, it) }

/** The vine data once the level ticked at least once; null before, when no vine can exist. */
internal val ServerLevel.loadedWoggakVines: WoggakVineData? get() = loadedVines[this]

fun registerWoggakVineEvents() {
    TickEvent.SERVER_LEVEL_POST.register { level -> level.tickWoggakVines() }
    registerVinePushes()
    registerVineJoints()
    registerVineHolds()
    registerVineChunks()
    registerVineThrows()
    registerWohlonWind()
    LifecycleEvent.SERVER_LEVEL_UNLOAD.register { level ->
        // The hold particles go with the level save; the next load deletes them by tag.
        loadedVines.remove(level)?.holds?.clear()
        clearVinePushes(level.dimensionId)
    }
    PlayerEvent.PLAYER_QUIT.register { player -> player.letGoOfVine(false) }
    LifecycleEvent.SERVER_STOPPED.register {
        loadedVines.clear()
        clearVinePushes()
        clearVineJoints()
    }
    InteractionEvent.RIGHT_CLICK_ITEM.register(::shearWoggakVine)
}

/** [cost] is the number of vine items the vine uses; [error] is set when no vine is made. */
class VineResult(val error: Component?, val cost: Int)

/** Vine items for a vine between two points; one for each started [BLOCKS_PER_ITEM] blocks. */
fun woggakVineCost(length: Double): Int = ceil(length / BLOCKS_PER_ITEM).toInt().coerceAtLeast(1)

@OptIn(GameTickOnly::class)
fun ServerLevel.createWoggakVine(a: VinePoint, b: VinePoint, available: Int): VineResult {
    fun fail(key: String, vararg args: Any) = VineResult(Component.translatable("item.enderkinesis.woggak_vine.$key", *args), 0)
    val (worldA, start) = attachment(a) ?: return fail("no_ship")
    val (worldB, end) = attachment(b) ?: return fail("no_ship")
    val length = worldA.distance(worldB)
    if (length < MIN_LENGTH) return fail("too_short")
    val cost = woggakVineCost(length)
    if (cost > available) return fail("not_enough", cost)

    val path = sagPath(worldA, worldB)
    val chain = makeVineChain(path, start, end) ?: return fail("unsupported")
    addVine(WoggakVine(newVineId(), chain.id, WoggakVineAnchor(a), WoggakVineAnchor(b)), path)
    return VineResult(null, cost)
}

/** The laid-out path of a new vine from [a] to [b]: a parabola that sags by [SAG_FRACTION] of the span. */
internal fun sagPath(a: Vector3dc, b: Vector3dc): List<Vector3dc> {
    val sag = a.distance(b) * SAG_FRACTION
    return List(PATH_POINTS) { i ->
        val t = i / (PATH_POINTS - 1.0)
        Vector3d(a).lerp(b, t).sub(0.0, 4.0 * sag * t * (1.0 - t), 0.0)
    }
}

@OptIn(GameTickOnly::class)
internal fun ServerLevel.makeVineChain(
    path: List<Vector3dc>, start: ChainEndpoint, end: ChainEndpoint, initialVelocity: Vector3dc = Vector3d(),
    massPerLength: Double = MASS_PER_LENGTH,
): ServerChain? = try {
    shipObjectWorld.createChain(ChainCreateData(
        dimensionId = dimensionId,
        path = path,
        start = start,
        end = end,
        targetSpacing = VINE_TARGET_SPACING,
        particleRadius = PARTICLE_RADIUS,
        massPerLength = massPerLength,
        breakForce = VINE_BREAK_FORCE,
        stiffness = VINE_LINK_STIFFNESS,
        initialVelocity = initialVelocity,
    ))
} catch (e: UnsupportedOperationException) {
    LOG.warn("Woggak Vine: chains are not available", e)
    null
}

/** Adds [vine], laid along [path] in world coordinates, to the live list of its home chunk. */
internal fun ServerLevel.addVine(vine: WoggakVine, path: List<Vector3dc>, fallbackHome: Long? = null) {
    vine.home = chooseHome(vine, path, fallbackHome)
    val nodes = DoubleArray(path.size * 3)
    var rest = 0.0
    for ((i, p) in path.withIndex()) {
        nodes[i * 3] = p.x(); nodes[i * 3 + 1] = p.y(); nodes[i * 3 + 2] = p.z()
        if (i > 0) rest += p.distance(path[i - 1])
    }
    vine.seedNodes = nodes
    vine.seedRest = rest
    val data = woggakVines
    data.add(vine)
    data.anchorsDirty = true
    data.setDirty()
}

/** World position and endpoint for [point]; null when its ship or the ship's body is gone, or a joint target is not here. */
internal fun ServerLevel.attachment(point: VinePoint): Pair<Vector3d, ChainEndpoint>? {
    // A joined end is free; the joint solver in VineJoints holds it to the target node.
    point.joint?.let { joint -> return jointPosition(joint)?.let { it to ChainEndpoint.Free } }
    val shipId = point.shipId ?: return Vector3d(point.local) to ChainEndpoint.World(Vector3d(point.local))
    val ship = shipObjectWorld.allShips.getById(shipId) ?: return null
    val bodyId = ship.bodyId ?: return null
    // Body anchors are in model space, which for a ship is shipyard space.
    return ship.shipToWorld.transformPosition(Vector3d(point.local)) to ChainEndpoint.Body(bodyId, Vector3d(point.local))
}

@OptIn(GameTickOnly::class)
private fun ServerLevel.tickWoggakVines() {
    val data = loadedWoggakVines ?: woggakVines
    deleteStrayVineChains(data)
    rebuildLoadedVines(data)
    data.collider.clear()
    tickVineThrows(data)
    tickVineCaptures(data)
    tickHullGrips(data)
    tickVineHolds(data)
    tickVineReels(data)
    tickVineRiders(data)
    publishWohlonWind(data)
    if (data.vines.isEmpty()) {
        publishJointPairs(data)
        flushVineSync(data)
        return
    }
    val world = shipObjectWorld
    data.collider.particles = world.allParticles
    var topology = false
    // Splits add vines at the end of the list; a snapshot of the size keeps the scan bounded.
    var i = 0
    var count = data.vines.size
    while (i < count) {
        val vine = data.vines[i]
        val chain = world.allChains.getById(vine.chain)
        val status = chain?.status
        if (chain == null || status == null || status.lifecycle == ChainLifecycle.REMOVED || status.lifecycle == ChainLifecycle.FAILED) {
            if (status?.lifecycle == ChainLifecycle.FAILED) LOG.warn("Woggak Vine {} failed: {}", vine.chain.value, status.failure)
            data.removeAt(i); count--; topology = true
            continue
        }
        if (status.lifecycle == ChainLifecycle.ACTIVE) {
            vine.seedNodes = null
            if (releaseIfLost(chain, ChainEnd.START, vine.start, status.startAttachment)) { topology = true; data.touch(vine) }
            if (releaseIfLost(chain, ChainEnd.END, vine.end, status.endAttachment)) { topology = true; data.touch(vine) }
            // One split per break lineage: a piece made inside a block can break again at once, and a split on
            // each break then multiplied the chains until physics froze.
            if (status.brokenLinks.isNotEmpty() && !vine.fromBreak && splitWoggakVine(vine, chain, true)) {
                data.removeAt(i); count--; topology = true
                continue
            }
            followHome(data, vine, chain)
        }
        if (!data.collider.add(chain, vine.joinedEnds)) {
            LOG.warn("Woggak Vine {} has a position that is not finite; it is removed", vine.chain.value)
            if (!world.deleteChain(vine.chain)) LOG.warn("Woggak Vine {} was already removed", vine.chain.value)
            data.removeAt(i); count--; topology = true
            continue
        }
        if (vine.fadeStart >= 0 && gameTime - vine.fadeStart >= WOGGAK_VINE_FADE_TICKS) {
            if (vine.dropsOnFade) dropFadedVine(chain, vine.reelCredit)
            puffVine(chain)
            if (!world.deleteChain(vine.chain)) LOG.warn("Woggak Vine {} was already removed", vine.chain.value)
            data.removeAt(i); count--; topology = true
            continue
        }
        i++
    }
    if (topology) {
        data.anchorsDirty = true
        data.jointsDirty = true
        data.setDirty()
    }
    publishJointPairs(data)
    if (data.anchorsDirty) {
        data.anchorsDirty = false
        if (data.startFadeOfUnanchored(gameTime) { isJointWaiting(data, it) }) data.setDirty()
    }
    flushVineSync(data)
}

/** Frees the end when its anchor block or joined vine is gone; returns true when the anchor changed. */
@OptIn(GameTickOnly::class)
private fun ServerLevel.releaseIfLost(chain: ServerChain, end: ChainEnd, anchor: WoggakVineAnchor, state: ChainAttachmentState): Boolean {
    if (anchor.released) return false
    val point = anchor.point ?: return false
    val detached = state == ChainAttachmentState.BROKEN || state == ChainAttachmentState.BODY_REMOVED
    val joint = point.joint
    val lost = !detached && (
        if (joint != null) !followJoint(chain, end, anchor, joint)
        else isLoaded(point.block) && getBlockState(point.block).getCollisionShape(this, point.block).isEmpty
    )
    if (!detached && !lost) return false
    if (state == ChainAttachmentState.ATTACHED) chain.setEndpoint(end, ChainEndpoint.Free)
    anchor.released = true
    anchor.pair?.restoreContacts(this)
    anchor.pair = null
    return true
}
