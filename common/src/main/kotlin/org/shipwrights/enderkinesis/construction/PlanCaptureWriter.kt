package org.shipwrights.enderkinesis.construction

import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * Builds one plan file from sections given in any order. [add] spills each payload to [data], so memory holds only
 * the palette and 16 bytes for each stored section. [finish] sorts the table and writes the file.
 */
class PlanCaptureWriter(val size: Vec3i, private val data: Path) : AutoCloseable {
    val grid = PlanGrid(size)

    private val out = DataOutputStream(BufferedOutputStream(Files.newOutputStream(data), BUFFER))
    private val palette = ArrayList<CompoundTag>()
    private val ids = HashMap<CompoundTag, Int>()

    // Per stored section, in add order: (index shl 32) or add rank, then the payload offset in [data] and its length.
    private var keys = LongArray(INITIAL)
    private var offsets = LongArray(INITIAL)
    private var lengths = IntArray(INITIAL)
    private var written = 0L
    private var closed = false

    var count = 0
        private set
    var nonAir = 0L
        private set

    init {
        palette += AIR
        palette += planKeepTag()
        ids[AIR] = PLAN_AIR_ID
        ids[palette[PLAN_KEEP_ID]] = PLAN_KEEP_ID
    }

    /** The global palette id of a clean block-state tag; [planKeepTag] gives [PLAN_KEEP_ID]. */
    fun idOf(tag: CompoundTag): Int = ids.getOrPut(tag) {
        check(palette.size < PlanSectionWriter.MAX_PALETTE) { "plan palette passed ${PlanSectionWriter.MAX_PALETTE} entries" }
        palette.size.also { palette += tag }
    }

    /** Global ids by [cellIndex]. A section of only structure void is not stored. Each index once. */
    fun add(index: Int, cells: IntArray) {
        check(!closed) { "the capture writer is closed" }
        require(index in 0 until grid.count) { "section index $index is outside the grid" }
        val section = PlanSectionData.of(cells) ?: return
        val payload = section.encode()
        if (count == keys.size) {
            keys = keys.copyOf(count * 2)
            offsets = offsets.copyOf(count * 2)
            lengths = lengths.copyOf(count * 2)
        }
        keys[count] = (index.toLong() shl Int.SIZE_BITS) or count.toLong()
        offsets[count] = written
        lengths[count] = payload.size
        count++
        nonAir += section.nonAir
        out.write(payload)
        written += payload.size
    }

    /** Writes the plan file to [target] and returns its SHA-256 as lowercase hex. Any thread, after the last [add]. */
    fun finish(target: Path): String {
        close()
        val order = keys.copyOf(count).also { it.sort() }
        val nonEmpty = IntArray(count) { (order[it] ushr Int.SIZE_BITS).toInt() }
        val header = PlanHeader(size, nonAir, palette, nonEmpty)
        val digest = MessageDigest.getInstance("SHA-256")
        DataOutputStream(BufferedOutputStream(DigestOutputStream(Files.newOutputStream(target), digest), BUFFER)).use { file ->
            header.write(file)
            check(file.size() < Int.MAX_VALUE) { "plan header is too large" }
            val base = file.size().toLong() + count.toLong() * PlanHeader.TABLE_ENTRY
            for (key in order) {
                val rank = (key and RANK_MASK).toInt()
                file.writeLong(base + offsets[rank])
                file.writeInt(lengths[rank])
            }
            Files.copy(data, file)
        }
        return PlanUploadStream.hex(digest.digest())
    }

    override fun close() {
        if (closed) return
        closed = true
        out.close()
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val INITIAL = 64
        const val RANK_MASK = 0xFFFF_FFFFL
        val AIR: CompoundTag = CompoundTag().also { it.putString("Name", "minecraft:air") }
    }
}
