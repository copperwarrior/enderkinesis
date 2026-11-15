package org.shipwrights.enderkinesis.construction

import com.mojang.logging.LogUtils
import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.networking.NetworkManager
import net.minecraft.client.Minecraft
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.state.BlockState
import org.shipwrights.enderkinesis.util.joinChunksOrNull
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.UUID

/**
 * Client only: plans by owner and hash, fetched from the server as a header, then section by section on demand,
 * and the player's own plan list. Memory is bounded by [HEADERS] plans and [SECTIONS] decoded sections.
 */
object ClientPlans {
    private val LOG = LogUtils.getLogger()
    private const val HEADERS = 16
    internal const val SECTIONS = 4096

    /** Sections asked for and not yet answered, over all plans; below the server queue cap. */
    private const val IN_FLIGHT = 48

    private val cache = PlanSectionCache(SECTIONS)

    // Client thread only, keyed by [PlanRef.key], which the ref holds, so the per-frame lookup does not allocate.
    // A plan in [unavailable] is not asked for again until the player leaves the server.
    private val plans = object : LinkedHashMap<PlanKey, ClientPlanSections>(HEADERS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PlanKey, ClientPlanSections>): Boolean {
            if (size <= HEADERS) return false
            dropInFlight(eldest.value)
            cache.removeOwner(eldest.value.owner)
            return true
        }
    }
    private val incoming = HashMap<PlanKey, Array<ByteArray?>>()
    private val unavailable = HashSet<PlanKey>()

    // Plans the server was too busy to send. The server frees a header slot when it sends the last part of a header,
    // so that part is the signal to ask again.
    private val busy = HashSet<PlanKey>()
    private var mine: List<PlanRef> = emptyList()

    // Misses of this tick in call order, and the requests the server has not answered.
    private val wanted = ArrayList<ClientPlanSections>()
    private val wantedIndex = IntArrayList()
    private val wantedKeys = HashSet<Long>()
    private val inFlight = HashSet<Long>()
    private val scratch = IntArray(PlanNetwork.MAX_FETCH_SECTIONS)

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, PlanNetwork.UPLOAD_RESULT) { buf, ctx ->
            val result = buf.readPlanUploadResult()
            ctx.queue { ClientPlanUpload.onResult(result) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, PlanNetwork.UPLOAD_LIMITS) { buf, ctx ->
            val limits = PlanNetwork.readUploadLimits(buf) ?: return@registerReceiver
            ctx.queue { ClientPlanUpload.onLimits(limits) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, PlanNetwork.UPLOAD_ACK) { buf, ctx ->
            val acked = buf.readVarInt()
            ctx.queue { ClientPlanUpload.onAck(acked) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, PlanNetwork.HEADER) { buf, ctx ->
            val part = PlanNetwork.readHeaderPart(buf) ?: return@registerReceiver
            ctx.queue { receiveHeaderPart(part) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, PlanNetwork.SECTION) { buf, ctx ->
            val part = PlanNetwork.readSection(buf) ?: return@registerReceiver
            ctx.queue { receiveSection(part) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, PlanNetwork.MISSING) { buf, ctx ->
            val key = PlanKey(buf.readUUID(), buf.readUtf(PlanRef.HASH_LENGTH))
            ctx.queue {
                incoming.remove(key)
                plans.remove(key)?.let {
                    dropInFlight(it)
                    cache.removeOwner(it.owner)
                }
                unavailable.add(key)
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, PlanNetwork.BUSY) { buf, ctx ->
            val key = PlanKey(buf.readUUID(), buf.readUtf(PlanRef.HASH_LENGTH))
            ctx.queue {
                if (incoming.remove(key) != null) busy.add(key)
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, PlanNetwork.LIST_RESULT) { buf, ctx ->
            val count = buf.readVarInt()
            require(count in 0..PlanNetwork.MAX_LISTED_PLANS) { "bad plan list size: $count" }
            val list = List(count) { buf.readPlanRef() }.filterNotNull()
            ctx.queue { mine = list }
        }
        ClientTickEvent.CLIENT_POST.register {
            ClientPlanUpload.tick()
            sendWanted()
            pumpPausedServer()
        }
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register {
            ClientPlanUpload.onQuit()
            plans.clear()
            cache.clear()
            incoming.clear()
            unavailable.clear()
            busy.clear()
            clearWanted()
            inFlight.clear()
            mine = emptyList()
        }
    }

    /**
     * A paused integrated server skips SERVER_POST, but still runs queued tasks, so each client tick hands it the
     * plan work of one tick. Uploads, section sends and captures then keep their per-tick budgets while paused.
     */
    private fun pumpPausedServer() {
        val mc = Minecraft.getInstance()
        val server = mc.singleplayerServer ?: return
        if (mc.isPaused) server.execute { PlanNetwork.serverTick(server) }
    }

    /** The plan by section, or null while its header loads or when the server has no such plan. The first miss asks. */
    fun sections(ref: PlanRef): PlanSections? {
        val key = ref.key
        plans[key]?.let { return it }
        if (key in incoming || key in unavailable || key in busy) return null
        incoming[key] = arrayOfNulls(0)
        PlanNetwork.sendFetch(ref)
        return null
    }

    enum class Status { LOADING, READY, MISSING }

    /** Header status: [Status.MISSING] when the server has no such plan or sent a header that does not read. */
    fun status(ref: PlanRef): Status = when {
        ref.key in plans -> Status.READY
        ref.key in unavailable -> Status.MISSING
        else -> Status.LOADING
    }

    /** The player's own uploaded plans, from the last server list. */
    fun mine(): List<PlanRef> = mine

    fun requestMine() = PlanNetwork.sendListRequest()

    fun delete(ref: PlanRef) = PlanNetwork.sendDelete(ref)

    private fun key(plan: ClientPlanSections, index: Int): Long = (plan.owner.toLong() shl Int.SIZE_BITS) or (index.toLong() and 0xFFFFFFFFL)

    internal fun want(plan: ClientPlanSections, index: Int) {
        val key = key(plan, index)
        if (key in inFlight || !wantedKeys.add(key)) return
        wanted += plan
        wantedIndex.add(index)
    }

    /** Sends this tick's misses in call order, up to the free in-flight room, grouped in runs of one plan. */
    private fun sendWanted() {
        var i = 0
        while (i < wanted.size && inFlight.size < IN_FLIGHT) {
            val plan = wanted[i]
            var count = 0
            while (i < wanted.size && wanted[i] === plan && count < scratch.size && inFlight.size < IN_FLIGHT) {
                scratch[count++] = wantedIndex[i]
                inFlight += key(plan, wantedIndex[i])
                i++
            }
            PlanNetwork.sendFetchSections(plan.ref, scratch, count)
        }
        clearWanted()
    }

    private fun clearWanted() {
        wanted.clear()
        wantedIndex.clear()
        wantedKeys.clear()
    }

    private fun dropInFlight(plan: ClientPlanSections) {
        val owner = plan.owner
        inFlight.removeIf { (it ushr Int.SIZE_BITS).toInt() == owner }
    }

    @Suppress("TooGenericExceptionCaught") // NbtIo and PlanHeader throw RuntimeExceptions on bad data.
    private fun receiveHeaderPart(part: PlanNetwork.HeaderPart) {
        val key = part.ref.key
        if (part.index == part.total - 1) busy.clear()
        // Only plans this client asked for; an empty array marks a request with no part yet.
        val parts = incoming[key]?.let { if (it.size == part.total) it else arrayOfNulls<ByteArray>(part.total) } ?: return
        incoming[key] = parts
        parts[part.index] = part.bytes
        val joined = parts.joinChunksOrNull() ?: return
        incoming.remove(key)
        try {
            val header = DataInputStream(ByteArrayInputStream(joined)).use { PlanHeader.read(it) }
            plans[key] = ClientPlanSections(part.ref, header, header.states(), PlanSectionCache.nextOwner())
        } catch (e: Exception) {
            LOG.warn("Cannot read plan {} from the server", key, e)
            unavailable.add(key)
        }
    }

    @Suppress("TooGenericExceptionCaught") // A bad section must not break the client tick.
    private fun receiveSection(part: PlanNetwork.SectionPart) {
        val plan = plans[part.ref.key] ?: return
        if (!inFlight.remove(key(plan, part.index)) || part.payload.isEmpty()) return
        try {
            cache.put(plan.owner, part.index, PlanSection(decodePlanSection(part.payload, plan.header.palette.size), plan.states))
        } catch (e: Exception) {
            LOG.warn("Cannot read section {} of plan {} from the server", part.index, part.ref.hash, e)
        }
    }

    internal fun cached(plan: ClientPlanSections, index: Int): PlanSection? = cache.get(plan.owner, index)

    private class IntArrayList {
        private var data = IntArray(PlanNetwork.MAX_FETCH_SECTIONS)
        var size = 0
            private set

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        operator fun get(i: Int): Int = data[i]

        fun clear() {
            size = 0
        }
    }
}

/** A server plan on the client. A section that is not cached is [PlanSectionState.LOADING] until it arrives. */
class ClientPlanSections internal constructor(
    val ref: PlanRef,
    internal val header: PlanHeader,
    internal val states: Array<BlockState>,
    internal val owner: Int,
) : PlanSections {
    override val size: Vec3i get() = header.size
    override val grid: PlanGrid get() = header.grid
    override val nonAir: Long get() = header.nonAir
    override val nonEmptyCount: Int get() = header.nonEmpty.size

    override fun nonEmptyIndex(i: Int): Int = header.nonEmpty[i]

    override fun state(index: Int): PlanSectionState = when {
        header.rank(index) < 0 -> PlanSectionState.EMPTY
        ClientPlans.cached(this, index) != null -> PlanSectionState.READY
        else -> PlanSectionState.LOADING
    }

    override fun sectionAt(index: Int): PlanSection? {
        if (header.rank(index) < 0) return null
        ClientPlans.cached(this, index)?.let { return it }
        ClientPlans.want(this, index)
        return null
    }
}
