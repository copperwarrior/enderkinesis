package org.shipwrights.enderkinesis.construction

import dev.architectury.networking.NetworkManager
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.EnderkinesisMod
import java.util.BitSet
import java.util.UUID

/** What the owner's client still needs of one job. Server thread only. */
internal class BuildJobSync {
    var fullPending = true
    var wordCursor = -1
    val sections = IntArrayFIFOQueue()
    val words = IntArrayFIFOQueue()

    fun sectionStarted(index: Int) = sections.enqueue(index)

    fun sectionCompleted(index: Int) = words.enqueue(index ushr 6)

    fun drop(job: BuildJob) {
        fullPending = true
        wordCursor = -1
        sections.clear()
        words.clear()
        for (s in job.active) s.dirty.clear()
    }

    fun beginFull(job: BuildJob) {
        fullPending = false
        wordCursor = 0
        sections.clear()
        words.clear()
        for (index in job.progress.partialSections()) sections.enqueue(index)
        for (s in job.active) {
            s.dirty.clear()
            if (job.progress.partialOrNull(s.index) == null) sections.enqueue(s.index)
        }
    }
}

/**
 * S2C build progress, to the job owner only, per 16^3 plan section (see [PlanGrid]).
 *  - [JOB]: job id, plan ref, anchor. The client starts the job with every cell TODO.
 *  - [WORDS]: job id, count, then (word index varint, long) for each word of the complete-section bit set.
 *  - [SECTION]: job id, section index varint, 2-bit [BuildCellStatus] ordinals by [planCellLocal] as longs.
 *  - [DELTA]: job id, section index varint, count, then (local short, status byte) for each changed cell.
 *  - [END]: job id; the job finished or stopped.
 */
object BuildProgressNetwork {
    val JOB: ResourceLocation = EnderkinesisMod.id("build/progress_job")
    val WORDS: ResourceLocation = EnderkinesisMod.id("build/progress_words")
    val SECTION: ResourceLocation = EnderkinesisMod.id("build/progress_section")
    val DELTA: ResourceLocation = EnderkinesisMod.id("build/progress_delta")
    val END: ResourceLocation = EnderkinesisMod.id("build/progress_end")

    const val STATUS_BITS = 2
    const val SECTION_STATUS_LONGS = PLAN_SECTION_CELLS * STATUS_BITS / Long.SIZE_BITS
    const val MAX_WORDS_PER_PACKET = 4096

    /** Status bytes per tick per owner, so a huge plan streams over many ticks. */
    private const val BYTES_PER_TICK = 64 * 1024
    private const val SECTION_BYTES = SECTION_STATUS_LONGS * Long.SIZE_BYTES

    /** Sends what the client lacks, within [BYTES_PER_TICK]. */
    internal fun flush(player: ServerPlayer, job: BuildJob) {
        val sync = job.sync
        if (sync.fullPending) {
            sync.beginFull(job)
            sendJob(player, job)
        }
        var budget = BYTES_PER_TICK
        val totalWords = (job.grid.count + Long.SIZE_BITS - 1) / Long.SIZE_BITS
        while (sync.wordCursor in 0 until totalWords && budget > 0) {
            val from = sync.wordCursor
            val to = minOf(totalWords, from + MAX_WORDS_PER_PACKET)
            sendWords(player, job, IntArray(to - from) { from + it })
            sync.wordCursor = to
            budget -= (to - from) * Long.SIZE_BYTES
        }
        if (sync.wordCursor >= totalWords) sync.wordCursor = -1
        if (!sync.words.isEmpty) {
            val n = minOf(sync.words.size(), MAX_WORDS_PER_PACKET)
            sendWords(player, job, IntArray(n) { sync.words.dequeueInt() })
        }
        while (!sync.sections.isEmpty && budget > 0) {
            val index = sync.sections.dequeueInt()
            if (job.progress.isComplete(index)) continue
            sendSection(player, job, index)
            budget -= SECTION_BYTES
        }
        for (s in job.active) if (!s.dirty.isEmpty) sendDelta(player, job, s)
    }

    private fun sendJob(player: ServerPlayer, job: BuildJob) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUUID(job.id)
        buf.writePlanRef(job.ref)
        buf.writePlanAnchor(job.anchor)
        NetworkManager.sendToPlayer(player, JOB, buf)
    }

    private fun sendWords(player: ServerPlayer, job: BuildJob, words: IntArray) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUUID(job.id)
        buf.writeVarInt(words.size)
        for (w in words) {
            buf.writeVarInt(w)
            buf.writeLong(job.progress.complete.word(w))
        }
        NetworkManager.sendToPlayer(player, WORDS, buf)
    }

    private fun sendSection(player: ServerPlayer, job: BuildJob, index: Int) {
        val active = job.section(index)
        val done = job.progress.partialOrNull(index)
        val packed = LongArray(SECTION_STATUS_LONGS)
        for (local in 0 until PLAN_SECTION_CELLS) {
            val status = when {
                active != null -> CellStatus.toClient(active.status[local])
                done != null && done.bit(local) -> BuildCellStatus.DONE
                else -> BuildCellStatus.TODO
            }
            val bit = local * STATUS_BITS
            packed[bit ushr 6] = packed[bit ushr 6] or (status.ordinal.toLong() shl (bit and 63))
        }
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUUID(job.id)
        buf.writeVarInt(index)
        for (word in packed) buf.writeLong(word)
        NetworkManager.sendToPlayer(player, SECTION, buf)
    }

    private fun sendDelta(player: ServerPlayer, job: BuildJob, s: BuildSection) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUUID(job.id)
        buf.writeVarInt(s.index)
        buf.writeVarInt(s.dirty.cardinality())
        var local = s.dirty.nextSetBit(0)
        while (local >= 0) {
            buf.writeShort(local)
            buf.writeByte(CellStatus.toClient(s.status[local]).ordinal)
            local = s.dirty.nextSetBit(local + 1)
        }
        s.dirty.clear()
        NetworkManager.sendToPlayer(player, DELTA, buf)
    }

    internal fun sendEnd(player: ServerPlayer, jobId: UUID) {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeUUID(jobId)
        NetworkManager.sendToPlayer(player, END, buf)
    }
}

/** Bits [w * 64, w * 64 + 64) as one long, with no copy of the whole set. */
internal fun BitSet.word(w: Int): Long {
    val base = w * Long.SIZE_BITS
    var out = 0L
    var i = nextSetBit(base)
    while (i in base until base + Long.SIZE_BITS) {
        out = out or (1L shl (i - base))
        i = nextSetBit(i + 1)
    }
    return out
}
