package org.shipwrights.enderkinesis.construction

import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.networking.NetworkManager
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import java.util.BitSet
import java.util.UUID

enum class BuildCellStatus { TODO, DONE, BLOCKED, MISSING }

/**
 * Build progress per plan-local 16^3 section ([PlanGrid] of [ref]). Only sections the server has worked on
 * keep a status array; the rest are all TODO, or all DONE once complete.
 */
class ClientBuildJob(val id: UUID, val ref: PlanRef, val anchor: PlanAnchor) {
    val grid = PlanGrid(ref.size)
    private val complete = BitSet()
    private val cells = Int2ObjectOpenHashMap<ByteArray>()
    private val versions = Int2IntOpenHashMap()

    var completeSections: Int = 0
        private set

    /** Grows on every status change in the section, so a section mesh rebuilds only when this moves. */
    fun sectionVersion(sx: Int, sy: Int, sz: Int): Int =
        if (grid.contains(sx, sy, sz)) versions.get(grid.index(sx, sy, sz)) else 0

    /** Every cell of the section is DONE. */
    fun isSectionDone(sx: Int, sy: Int, sz: Int): Boolean = grid.contains(sx, sy, sz) && complete[grid.index(sx, sy, sz)]

    /** [local] is a [planCellLocal]. */
    fun status(sx: Int, sy: Int, sz: Int, local: Int): BuildCellStatus {
        if (!grid.contains(sx, sy, sz) || local !in 0 until PLAN_SECTION_CELLS) return BuildCellStatus.TODO
        val index = grid.index(sx, sy, sz)
        if (complete[index]) return BuildCellStatus.DONE
        val array = cells.get(index) ?: return BuildCellStatus.TODO
        return STATUSES[array[local].toInt()]
    }

    /** A word index outside the section grid comes from a bad packet and is skipped. */
    internal fun setWord(w: Int, value: Long) {
        if (w !in 0..(grid.count - 1) / Long.SIZE_BITS) return
        val base = w * Long.SIZE_BITS
        for (b in 0 until Long.SIZE_BITS) {
            val index = base + b
            if (index >= grid.count) return
            val now = (value ushr b) and 1L != 0L
            if (complete[index] == now) continue
            complete[index] = now
            if (now) {
                completeSections++
                cells.remove(index)
            } else {
                completeSections--
            }
            versions.addTo(index, 1)
        }
    }

    internal fun setSection(index: Int, packed: LongArray) {
        if (index !in 0 until grid.count) return
        val array = cells.computeIfAbsent(index) { ByteArray(PLAN_SECTION_CELLS) }
        for (local in 0 until PLAN_SECTION_CELLS) {
            val bit = local * BuildProgressNetwork.STATUS_BITS
            array[local] = ((packed[bit ushr 6] ushr (bit and 63)) and STATUS_MASK).toByte()
        }
        versions.addTo(index, 1)
    }

    internal fun setCells(index: Int, locals: ShortArray, values: ByteArray) {
        if (index !in 0 until grid.count || complete[index]) return
        val array = cells.computeIfAbsent(index) { ByteArray(PLAN_SECTION_CELLS) }
        var changed = false
        for (i in locals.indices) {
            val local = locals[i].toInt()
            val value = values[i]
            if (local !in 0 until PLAN_SECTION_CELLS || value.toInt() !in STATUSES.indices || array[local] == value) continue
            array[local] = value
            changed = true
        }
        if (changed) versions.addTo(index, 1)
    }

    private companion object {
        val STATUSES = BuildCellStatus.entries.toTypedArray()
        const val STATUS_MASK = (1L shl BuildProgressNetwork.STATUS_BITS) - 1
    }
}

/** Client thread only: every receiver reads on the network thread and hands the result to the client thread. */
object ClientBuildJobs {
    private val jobs = HashMap<UUID, ClientBuildJob>()

    fun jobs(): Collection<ClientBuildJob> = jobs.values

    fun init() {
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, BuildProgressNetwork.JOB) { buf, _ ->
            val id = buf.readUUID()
            val ref = buf.readPlanRef()
            val anchor = buf.readPlanAnchor()
            Minecraft.getInstance().execute {
                if (ref == null || !PlanGrid.fits(ref.size)) jobs.remove(id) else jobs[id] = ClientBuildJob(id, ref, anchor)
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, BuildProgressNetwork.WORDS) { buf, _ ->
            val id = buf.readUUID()
            val count = buf.readVarInt()
            require(count in 0..BuildProgressNetwork.MAX_WORDS_PER_PACKET) { "bad build word count $count" }
            val index = IntArray(count)
            val words = LongArray(count)
            for (i in 0 until count) {
                index[i] = buf.readVarInt()
                words[i] = buf.readLong()
            }
            Minecraft.getInstance().execute {
                val job = jobs[id] ?: return@execute
                for (i in 0 until count) job.setWord(index[i], words[i])
            }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, BuildProgressNetwork.SECTION) { buf, _ ->
            val id = buf.readUUID()
            val index = buf.readVarInt()
            val packed = LongArray(BuildProgressNetwork.SECTION_STATUS_LONGS) { buf.readLong() }
            Minecraft.getInstance().execute { jobs[id]?.setSection(index, packed) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, BuildProgressNetwork.DELTA) { buf, _ ->
            val id = buf.readUUID()
            val index = buf.readVarInt()
            val count = buf.readVarInt()
            require(count in 0..PLAN_SECTION_CELLS) { "bad build delta count $count" }
            val locals = ShortArray(count)
            val values = ByteArray(count)
            for (i in 0 until count) {
                locals[i] = buf.readShort()
                values[i] = buf.readByte()
            }
            Minecraft.getInstance().execute { jobs[id]?.setCells(index, locals, values) }
        }
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, BuildProgressNetwork.END) { buf, _ ->
            val id = buf.readUUID()
            Minecraft.getInstance().execute { jobs.remove(id) }
        }
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register { jobs.clear() }
    }
}
