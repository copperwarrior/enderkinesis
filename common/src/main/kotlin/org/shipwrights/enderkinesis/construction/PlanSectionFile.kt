package org.shipwrights.enderkinesis.construction

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A plan file on disk. With [channel] it reads through that one open handle until [close]; without, each read
 * opens the file, so nothing needs a close and any thread can read. Reads are positional, so no cursor is shared.
 */
class PlanSectionFile private constructor(
    val path: Path,
    val header: PlanHeader,
    val headerLength: Long,
    private val channel: FileChannel?,
) : AutoCloseable {
    // Kotlin's lazy does not keep a thrown IOException, so a failed read tries again on the next call.
    private val cachedHeaderBytes by lazy {
        check(headerLength <= Int.MAX_VALUE) { "plan header does not fit an array" }
        read { ByteBuffer.allocate(headerLength.toInt()).also { buffer -> readFully(it, buffer, 0) }.array() }
    }

    /** The stored payload of section [index], or null when the section is empty. */
    fun payload(index: Int): ByteArray? {
        val rank = header.rank(index)
        if (rank < 0) return null
        return read { channel ->
            val entry = ByteBuffer.allocate(PlanHeader.TABLE_ENTRY)
            readFully(channel, entry, headerLength + rank.toLong() * PlanHeader.TABLE_ENTRY)
            entry.flip()
            val offset = entry.long
            val length = entry.int
            formatCheck(length in 1..MAX_PAYLOAD && offset >= headerLength) { "bad section table entry $offset+$length" }
            val payload = ByteBuffer.allocate(length)
            readFully(channel, payload, offset)
            payload.array()
        }
    }

    fun data(index: Int): PlanSectionData? = payload(index)?.let { decodePlanSection(it, header.palette.size) }

    /** The bytes a client needs before it asks for sections: the header, without the section table. Shared; do not change it. */
    fun headerBytes(): ByteArray = cachedHeaderBytes

    override fun close() {
        channel?.close()
    }

    // An interrupt during a read closes a FileChannel for every user, so read a kept channel only on the server thread.
    private inline fun <T> read(block: (FileChannel) -> T): T =
        if (channel != null) block(channel) else FileChannel.open(path, StandardOpenOption.READ).use(block)

    companion object {
        /** Deflated payloads stay far below this; a larger table entry means a damaged file. */
        const val MAX_PAYLOAD = 1 shl 20

        /** With [keepOpen], the file keeps one handle until [close]. */
        fun open(path: Path, keepOpen: Boolean = false): PlanSectionFile {
            val header = DataInputStream(BufferedInputStream(Files.newInputStream(path))).use { PlanHeader.read(it) }
            val channel = if (keepOpen) FileChannel.open(path, StandardOpenOption.READ) else null
            return PlanSectionFile(path, header, header.byteLength(), channel)
        }

        private fun readFully(channel: FileChannel, buffer: ByteBuffer, at: Long) {
            var position = at
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, position)
                if (n < 0) throw EOFException("plan file is cut short")
                position += n
            }
        }
    }
}

/** Global palette tags as block states; entry 0 is air. Stored plans are already data-fixed and checked. */
fun PlanHeader.states(): Array<BlockState> = Array(palette.size) { i ->
    if (i == 0) Blocks.AIR.defaultBlockState() else readPlanState(palette[i])
}

fun readPlanState(tag: CompoundTag): BlockState = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag)

/**
 * A least-recently-used cache of decoded sections, shared by every plan that one side holds, so memory stays
 * bounded by [capacity] sections whatever the count or size of plans. Synchronized: the sketchbook reads it from
 * a worker and the render thread.
 */
class PlanSectionCache(private val capacity: Int) {
    private val map = Long2ObjectLinkedOpenHashMap<PlanSection>(capacity + 1)

    init {
        require(capacity > 0) { "plan section cache capacity must be positive" }
    }

    @Synchronized
    fun get(owner: Int, index: Int): PlanSection? = map.getAndMoveToLast(key(owner, index))

    @Synchronized
    fun put(owner: Int, index: Int, section: PlanSection) {
        map.putAndMoveToLast(key(owner, index), section)
        if (map.size > capacity) map.removeFirst()
    }

    @Synchronized
    fun removeOwner(owner: Int) {
        val keys = map.keys.iterator()
        while (keys.hasNext()) if ((keys.nextLong() ushr Int.SIZE_BITS).toInt() == owner) keys.remove()
    }

    @Synchronized
    fun clear() = map.clear()

    private fun key(owner: Int, index: Int): Long = (owner.toLong() shl Int.SIZE_BITS) or (index.toLong() and 0xFFFFFFFFL)

    companion object {
        private val serials = java.util.concurrent.atomic.AtomicInteger()

        /** A cache owner id for one open plan. */
        fun nextOwner(): Int = serials.incrementAndGet()
    }
}

/** A plan read from a [PlanSectionFile]: every section is EMPTY or READY, never LOADING. */
class FilePlanSections(
    val file: PlanSectionFile,
    private val cache: PlanSectionCache,
    private val onError: (Exception) -> Unit,
) : PlanSections {
    private val owner = PlanSectionCache.nextOwner()
    private val states: Array<BlockState> = file.header.states()
    override val size: Vec3i get() = file.header.size
    override val grid: PlanGrid get() = file.header.grid
    override val nonAir: Long get() = file.header.nonAir
    override val nonEmptyCount: Int get() = file.header.nonEmpty.size

    override fun nonEmptyIndex(i: Int): Int = file.header.nonEmpty[i]

    override fun state(index: Int): PlanSectionState =
        if (file.header.rank(index) < 0) PlanSectionState.EMPTY else PlanSectionState.READY

    @Suppress("TooGenericExceptionCaught") // A damaged or deleted file must not break the caller's tick; it reads as empty.
    override fun sectionAt(index: Int): PlanSection? {
        cache.get(owner, index)?.let { return it }
        val data = try {
            file.data(index)
        } catch (e: Exception) {
            onError(e)
            null
        } ?: return null
        return PlanSection(data, states).also { cache.put(owner, index, it) }
    }

    /** Drops this plan's sections from the shared cache and closes the file. */
    fun evict() {
        cache.removeOwner(owner)
        file.close()
    }
}
