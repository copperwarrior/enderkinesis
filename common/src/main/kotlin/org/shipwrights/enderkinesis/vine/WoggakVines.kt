package org.shipwrights.enderkinesis.vine

import com.mojang.logging.LogUtils
import dev.architectury.event.events.common.InteractionEvent
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
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

val WOGGAK_VINE_SYNC: ResourceLocation = EnderkinesisMod.id("woggak_vine/sync")

/** Upper bound on vines in one level; also bounds the sync packet and the per-tick scan. */
const val MAX_WOGGAK_VINES = 1024

/** Ticks from the loss of the last anchor to the end of the grey fade; the client uses it for the fade too. */
const val WOGGAK_VINE_FADE_TICKS = 60

/** At least four particle radii, so that no node count makes a link shorter than one particle diameter. */
internal const val VINE_TARGET_SPACING = 0.4
private const val PARTICLE_RADIUS = 0.1
private const val MASS_PER_LENGTH = 5.0
/** Newtons on one link or end joint; about 100 t hanging still at 1 g. */
internal const val VINE_BREAK_FORCE = 1.0e6
private const val MIN_LENGTH = 1.0
private const val MAX_LENGTH = 32.0
private const val BLOCKS_PER_ITEM = 4.0
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

/** [fromBreak]: made by a split after a link broke; such a vine does not split again on a break. */
class WoggakVine(val chain: ChainId, val start: WoggakVineAnchor, val end: WoggakVineAnchor, val fromBreak: Boolean = false) {
    /** Game time when the last anchor went, or -1. */
    var fadeStart = -1L

    /** Bit 0: the start is joined to another vine; bit 1: the end is. */
    val joinedEnds: Int
        get() = (if (start.isJoined) JOINED_START else 0) or (if (end.isJoined) JOINED_END else 0)
}

const val JOINED_START = 1
const val JOINED_END = 2

internal val WoggakVineAnchor.isJoined get() = !released && point?.joint != null

class WoggakVineData : SavedData() {
    val vines = ArrayList<WoggakVine>()
    /** Server-thread scratch for node positions; not saved. */
    internal var scratch = DoubleArray(0)
    /** Set when a vine, anchor or joint changes, so the next tick checks which vines still reach an anchor. */
    internal var anchorsDirty = true
    /** Set when a joint pair is made or let go, so the next tick sends the pairs to the physics thread. */
    internal var jointsDirty = true
    /** Entity collision snapshot, rebuilt each tick; not saved. */
    val collider = VineCollider()
    internal val contacts = VineContacts()

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        for (vine in vines) list.add(CompoundTag().apply {
            putLong("chain", vine.chain.value)
            putAnchor("a", vine.start)
            putAnchor("b", vine.end)
            putLong("fade", vine.fadeStart)
            putBoolean("fromBreak", vine.fromBreak)
        })
        tag.put("vines", list)
        return tag
    }

    private fun CompoundTag.putAnchor(key: String, anchor: WoggakVineAnchor) {
        anchor.point?.let { put(key, it.save()) }
        putBoolean("${key}r", anchor.released)
    }

    companion object {
        fun load(tag: CompoundTag): WoggakVineData {
            val data = WoggakVineData()
            val list = tag.getList("vines", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until minOf(list.size, MAX_WOGGAK_VINES)) {
                val t = list.getCompound(i)
                data.vines.add(WoggakVine(ChainId(t.getLong("chain")), t.getAnchor("a"), t.getAnchor("b"), t.getBoolean("fromBreak")).apply {
                    fadeStart = t.getLong("fade")
                })
            }
            return data
        }

        private fun CompoundTag.getAnchor(key: String): WoggakVineAnchor {
            val point = if (contains(key, Tag.TAG_COMPOUND.toInt())) VinePoint.load(getCompound(key)) else null
            return WoggakVineAnchor(point).apply { released = getBoolean("${key}r") || point == null }
        }
    }
}

val ServerLevel.woggakVines: WoggakVineData
    get() = dataStorage.computeIfAbsent({ WoggakVineData.load(it) }, ::WoggakVineData, DATA_NAME)

fun registerWoggakVineEvents() {
    TickEvent.SERVER_LEVEL_POST.register { level -> level.tickWoggakVines() }
    registerVinePushes()
    registerVineJoints()
    LifecycleEvent.SERVER_STOPPED.register {
        clearVinePushes()
        clearVineJoints()
    }
    InteractionEvent.RIGHT_CLICK_ITEM.register(::shearWoggakVine)
    PlayerEvent.PLAYER_JOIN.register { player -> player.sendWoggakVines() }
    PlayerEvent.CHANGE_DIMENSION.register { player, _, _ -> player.sendWoggakVines() }
    // A respawn into a different dimension is not a CHANGE_DIMENSION event.
    PlayerEvent.PLAYER_RESPAWN.register { player, _ -> player.sendWoggakVines() }
}

/** [cost] is the number of vine items the vine uses; [error] is set when no vine is made. */
class VineResult(val error: Component?, val cost: Int)

/** Vine items for a vine between two points; one for each started [BLOCKS_PER_ITEM] blocks. */
fun woggakVineCost(length: Double): Int = kotlin.math.ceil(length / BLOCKS_PER_ITEM).toInt().coerceAtLeast(1)

@OptIn(GameTickOnly::class)
fun ServerLevel.createWoggakVine(a: VinePoint, b: VinePoint, available: Int): VineResult {
    fun fail(key: String, vararg args: Any) = VineResult(Component.translatable("item.enderkinesis.woggak_vine.$key", *args), 0)
    val data = woggakVines
    if (data.vines.size >= MAX_WOGGAK_VINES) return fail("too_many")
    val (worldA, start) = attachment(a) ?: return fail("no_ship")
    val (worldB, end) = attachment(b) ?: return fail("no_ship")
    val length = worldA.distance(worldB)
    if (length < MIN_LENGTH) return fail("too_short")
    if (length > MAX_LENGTH) return fail("too_long", MAX_LENGTH.toInt())
    val cost = woggakVineCost(length)
    if (cost > available) return fail("not_enough", cost)

    val sag = length * SAG_FRACTION
    val path = List(PATH_POINTS) { i ->
        val t = i / (PATH_POINTS - 1.0)
        Vector3d(worldA).lerp(worldB, t).sub(0.0, 4.0 * sag * t * (1.0 - t), 0.0)
    }
    val chain = makeVineChain(path, start, end) ?: return fail("unsupported")
    addVine(WoggakVine(chain.id, WoggakVineAnchor(a), WoggakVineAnchor(b)))
    return VineResult(null, cost)
}

@OptIn(GameTickOnly::class)
internal fun ServerLevel.makeVineChain(path: List<Vector3dc>, start: ChainEndpoint, end: ChainEndpoint): ServerChain? = try {
    shipObjectWorld.createChain(ChainCreateData(
        dimensionId = dimensionId,
        path = path,
        start = start,
        end = end,
        targetSpacing = VINE_TARGET_SPACING,
        particleRadius = PARTICLE_RADIUS,
        massPerLength = MASS_PER_LENGTH,
        breakForce = VINE_BREAK_FORCE,
    ))
} catch (e: UnsupportedOperationException) {
    LOG.warn("Woggak Vine: chains are not available", e)
    null
}

internal fun ServerLevel.addVine(vine: WoggakVine) {
    val data = woggakVines
    data.vines.add(vine)
    data.anchorsDirty = true
    data.setDirty()
    syncWoggakVines()
}

/** World position and endpoint for [point]; null when its ship or the ship's body is gone. */
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
    val data = woggakVines
    data.collider.clear()
    if (data.vines.isEmpty()) {
        publishJointPairs(data)
        return
    }
    val world = shipObjectWorld
    var changed = false
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
            data.vines.removeAt(i); count--; topology = true
            continue
        }
        if (status.lifecycle == ChainLifecycle.ACTIVE) {
            if (releaseIfLost(chain, ChainEnd.START, vine.start, status.startAttachment)) topology = true
            if (releaseIfLost(chain, ChainEnd.END, vine.end, status.endAttachment)) topology = true
            // One split per break lineage: a piece made inside a block can break again at once, and a split on
            // each break then multiplied the chains until physics froze.
            if (status.brokenLinks.isNotEmpty() && !vine.fromBreak && splitWoggakVine(vine, chain, true)) {
                data.vines.removeAt(i); count--; topology = true
                continue
            }
        }
        if (!data.collider.add(chain)) {
            LOG.warn("Woggak Vine {} has a position that is not finite; it is removed", vine.chain.value)
            if (!world.deleteChain(vine.chain)) LOG.warn("Woggak Vine {} was already removed", vine.chain.value)
            data.vines.removeAt(i); count--; topology = true
            continue
        }
        if (vine.fadeStart >= 0 && gameTime - vine.fadeStart >= WOGGAK_VINE_FADE_TICKS) {
            puffVine(chain)
            if (!world.deleteChain(vine.chain)) LOG.warn("Woggak Vine {} was already removed", vine.chain.value)
            data.vines.removeAt(i); count--; topology = true
            continue
        }
        i++
    }
    if (topology) {
        data.anchorsDirty = true
        data.jointsDirty = true
    }
    publishJointPairs(data)
    if (data.anchorsDirty) {
        data.anchorsDirty = false
        if (data.startFadeOfUnanchored(gameTime)) changed = true
    }
    if (changed || topology) {
        data.setDirty()
        syncWoggakVines()
    }
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
    anchor.pair?.let { restoreJointContacts(it) }
    anchor.pair = null
    return true
}

internal fun ServerLevel.syncWoggakVines() {
    for (player in players()) player.sendWoggakVines()
}

/** The target chain and node of a joined end, so the client draws the end on the target node. */
private fun writeJointTarget(buf: FriendlyByteBuf, anchor: WoggakVineAnchor) {
    val joint = anchor.point?.joint
    if (!anchor.isJoined || joint == null) return
    buf.writeLong(joint.chain.value)
    buf.writeVarInt(joint.node)
}

private fun ServerPlayer.sendWoggakVines() {
    val vines = serverLevel().woggakVines.vines
    val buf = FriendlyByteBuf(Unpooled.buffer())
    buf.writeVarInt(vines.size)
    for (vine in vines) {
        buf.writeLong(vine.chain.value)
        buf.writeLong(vine.fadeStart)
        buf.writeByte(vine.joinedEnds)
        writeJointTarget(buf, vine.start)
        writeJointTarget(buf, vine.end)
    }
    NetworkManager.sendToPlayer(this, WOGGAK_VINE_SYNC, buf)
}
