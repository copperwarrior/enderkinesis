package org.shipwrights.enderkinesis.construction

import com.mojang.logging.LogUtils
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream

/** Maps one raw palette entry to a clean block-state tag ([planKeepTag] for structure void), or null for air. */
fun interface PlanPaletteMapper {
    fun map(raw: CompoundTag, dataVersion: Int): CompoundTag?
}

sealed interface PlanConversion {
    /** [peakSections] is the most sections held in memory at one time. */
    class Done(
        val size: Vec3i,
        val nonAir: Long,
        val dropped: Long,
        val strippedData: Long,
        val entities: Long,
        val peakSections: Int,
    ) : PlanConversion

    class Refused(val reason: String) : PlanConversion
}

/**
 * Converts a gzip structure .nbt into the section file format, with bounded memory for any plan size.
 * Pass 1 streams the NBT and spills raw block records to disk. Then records split by section-index range
 * into at most [MAX_OPEN] files at a time, until a range of at most [GROUP] sections fits in memory.
 */
object PlanSectionWriter {
    private val LOG = LogUtils.getLogger()
    const val GROUP = 256
    const val MAX_OPEN = 64

    /** Distinct raw palette entries, far above the block states of a large modpack; stops a hostile palette. */
    const val MAX_PALETTE = 1 shl 18

    private const val MAX_ENTRY_BYTES = 64L * 1024
    private const val CANCEL_CHECK = 4096
    private const val BUFFER = 64 * 1024

    // Structure files without a DataVersion come from before 1.9; vanilla StructureTemplateManager uses the same default.
    const val OLD_DATA_VERSION = 500

    private val AIR = CompoundTag().also { it.putString("Name", "minecraft:air") }

    /** Writes [target] through a temp file in [scratch]; [target] only appears when the conversion is done. */
    fun convert(source: Path, target: Path, scratch: Path, mapper: PlanPaletteMapper, cancelled: () -> Boolean): PlanConversion {
        Files.createDirectories(scratch)
        val temps = ArrayList<Path>()
        fun temp(name: String): Path = Files.createTempFile(scratch, name, ".tmp").also { temps.add(it) }
        try {
            val spill = temp("spill")
            val raw = DataInputStream(BufferedInputStream(GZIPInputStream(BufferedInputStream(Files.newInputStream(source), BUFFER)), BUFFER)).use { input ->
                DataOutputStream(BufferedOutputStream(Files.newOutputStream(spill), BUFFER)).use { out -> readStructure(PlanNbtPull(input), out, cancelled) }
            }
            val size = raw.size ?: return PlanConversion.Refused("bad_file")
            if (!PlanGrid.fits(size)) return PlanConversion.Refused("bad_file")
            val rawPalette = raw.palettes ?: raw.palette
            if (rawPalette.isNullOrEmpty()) return PlanConversion.Refused("empty")

            val keep = planKeepTag()
            val palette = ArrayList<CompoundTag>().also {
                it += AIR
                it += keep
            }
            val interned = HashMap<CompoundTag, Int>().also {
                it[AIR] = PLAN_AIR_ID
                it[keep] = PLAN_KEEP_ID
            }
            val rawToGlobal = IntArray(rawPalette.size) { i ->
                val mapped = mapper.map(rawPalette[i], raw.dataVersion)
                if (mapped == null) PLAN_AIR_ID else interned.getOrPut(mapped) { palette.size.also { palette += mapped } }
            }

            val grid = PlanGrid(size)
            val sections = SectionSink(temp("data"))
            val split = Splitter(grid, rawToGlobal, cancelled, ::temp)
            try {
                split.run(spill, raw.records, sections)
            } finally {
                sections.close()
            }

            val header = PlanHeader(size, sections.nonAir, palette, sections.indices())
            val out = temp("plan")
            DataOutputStream(BufferedOutputStream(Files.newOutputStream(out), BUFFER)).use { file ->
                header.write(file)
                var offset = file.size().toLong() + sections.count.toLong() * PlanHeader.TABLE_ENTRY
                for (i in 0 until sections.count) {
                    file.writeLong(offset)
                    file.writeInt(sections.length(i))
                    offset += sections.length(i)
                }
                Files.copy(sections.file, file)
            }
            Files.createDirectories(target.parent)
            moveInto(out, target)
            return PlanConversion.Done(size, sections.nonAir, raw.dropped + split.dropped, raw.strippedData, raw.entities, split.peakSections)
        } finally {
            temps.forEach(::deleteTemp)
        }
    }

    // A failed delete must not hide the exception that ended the conversion.
    private fun deleteTemp(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            LOG.warn("Cannot delete plan conversion file {}", path, e)
        }
    }

    private fun moveInto(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private class Raw {
        var size: Vec3i? = null
        var dataVersion = OLD_DATA_VERSION
        var palette: List<CompoundTag>? = null
        var palettes: List<CompoundTag>? = null
        var records = 0L
        var dropped = 0L
        var strippedData = 0L
        var entities = 0L
    }

    private fun readStructure(nbt: PlanNbtPull, spill: DataOutputStream, cancelled: () -> Boolean): Raw {
        formatCheck(nbt.type() == PlanNbtPull.COMPOUND) { "a structure file is a compound" }
        nbt.name()
        val raw = Raw()
        val triple = IntArray(3)
        while (true) {
            val type = nbt.type()
            if (type == PlanNbtPull.END) return raw
            val name = nbt.name()
            when {
                name == "size" && type == PlanNbtPull.LIST ->
                    raw.size = if (nbt.intTriple(triple)) Vec3i(triple[0], triple[1], triple[2]) else null
                name == "DataVersion" && nbt.number(type)?.also { raw.dataVersion = it.toInt() } != null -> Unit
                name == "palette" && type == PlanNbtPull.LIST -> raw.palette = readPalette(nbt)
                name == "palettes" && type == PlanNbtPull.LIST -> raw.palettes = readFirstPalette(nbt)
                name == "blocks" && type == PlanNbtPull.LIST -> readBlocks(nbt, spill, raw, cancelled)
                name == "entities" && type == PlanNbtPull.LIST -> {
                    val header = nbt.listHeader()
                    raw.entities += header.count
                    nbt.skipElements(header.element, header.count)
                }
                else -> nbt.skip(type)
            }
        }
    }

    private fun readPalette(nbt: PlanNbtPull): List<CompoundTag> {
        val header = nbt.listHeader()
        if (header.element != PlanNbtPull.COMPOUND) {
            nbt.skipElements(header.element, header.count)
            return emptyList()
        }
        formatCheck(header.count <= MAX_PALETTE) { "palette has ${header.count} entries" }
        return List(header.count) { nbt.compound(MAX_ENTRY_BYTES) }
    }

    /** Vanilla places one random palette of "palettes"; a plan uses the first. */
    private fun readFirstPalette(nbt: PlanNbtPull): List<CompoundTag> {
        val header = nbt.listHeader()
        if (header.element != PlanNbtPull.LIST || header.count == 0) {
            nbt.skipElements(header.element, header.count)
            return emptyList()
        }
        val first = readPalette(nbt)
        nbt.skipElements(header.element, header.count - 1)
        return first
    }

    private fun readBlocks(nbt: PlanNbtPull, spill: DataOutputStream, raw: Raw, cancelled: () -> Boolean) {
        val header = nbt.listHeader()
        if (header.element != PlanNbtPull.COMPOUND) {
            nbt.skipElements(header.element, header.count)
            return
        }
        val pos = IntArray(3)
        for (i in 0 until header.count) {
            if (i % CANCEL_CHECK == 0 && cancelled()) throw CancellationException("plan conversion cancelled")
            var hasPos = false
            var state = -1L
            while (true) {
                val type = nbt.type()
                if (type == PlanNbtPull.END) break
                val name = nbt.name()
                when {
                    name == "pos" && type == PlanNbtPull.LIST -> hasPos = nbt.intTriple(pos)
                    name == "state" && nbt.number(type)?.also { state = it } != null -> Unit
                    name == "nbt" -> {
                        raw.strippedData++
                        nbt.skip(type)
                    }
                    else -> nbt.skip(type)
                }
            }
            if (!hasPos || state !in 0..Int.MAX_VALUE) {
                raw.dropped++
                continue
            }
            spill.writeInt(pos[0])
            spill.writeInt(pos[1])
            spill.writeInt(pos[2])
            spill.writeInt(state.toInt())
            raw.records++
        }
    }

    /** Collects the encoded sections in ascending index order into one data file. */
    private class SectionSink(val file: Path) {
        private val out = DataOutputStream(BufferedOutputStream(Files.newOutputStream(file), BUFFER))
        private var indices = IntArray(INITIAL)
        private var lengths = IntArray(INITIAL)
        var count = 0
            private set
        var nonAir = 0L
            private set

        fun add(index: Int, data: PlanSectionData) {
            check(count == 0 || index > indices[count - 1]) { "sections must come in ascending order" }
            val payload = data.encode()
            if (count == indices.size) {
                indices = indices.copyOf(count * 2)
                lengths = lengths.copyOf(count * 2)
            }
            indices[count] = index
            lengths[count] = payload.size
            count++
            nonAir += data.nonAir
            out.write(payload)
        }

        fun length(i: Int): Int = lengths[i]

        fun indices(): IntArray = indices.copyOf(count)

        fun close() = out.close()

        companion object {
            private const val INITIAL = 64
        }
    }

    /**
     * Splits records by section-index range. A task holds records of sections lo until lo + span. The spill holds
     * raw records (x, y, z, raw state); split files hold checked ones (section index, cell, global id).
     */
    private class Splitter(
        private val grid: PlanGrid,
        private val rawToGlobal: IntArray,
        private val cancelled: () -> Boolean,
        private val temp: (String) -> Path,
    ) {
        var dropped = 0L
            private set
        var peakSections = 0
            private set

        private class Task(val file: Path, val raw: Boolean, val records: Long, val lo: Int, val span: Int)

        private val record = IntArray(3)

        fun run(spill: Path, records: Long, sink: SectionSink) {
            // Children go on in reverse, so the stack pops ranges in ascending order. Depth is at most log64(count / GROUP).
            val stack = ArrayDeque<Task>()
            stack.addLast(Task(spill, true, records, 0, grid.count))
            while (stack.isNotEmpty()) {
                val task = stack.removeLast()
                if (task.span <= GROUP) group(task, sink) else split(task).asReversed().forEach(stack::addLast)
                if (!task.raw) Files.deleteIfExists(task.file)
            }
        }

        /** Reads one record into [record]; false when a raw record is outside the plan and is dropped. */
        private fun read(input: DataInputStream, raw: Boolean): Boolean {
            if (!raw) {
                record[0] = input.readInt()
                record[1] = input.readInt()
                record[2] = input.readInt()
                return true
            }
            val x = input.readInt()
            val y = input.readInt()
            val z = input.readInt()
            val state = input.readInt()
            val size = grid.size
            if (x !in 0 until size.x || y !in 0 until size.y || z !in 0 until size.z || state !in rawToGlobal.indices) {
                dropped++
                return false
            }
            record[0] = grid.index(x shr 4, y shr 4, z shr 4)
            record[1] = cellIndex(x and 15, y and 15, z and 15)
            record[2] = rawToGlobal[state]
            return true
        }

        private fun split(task: Task): List<Task> {
            val childSpan = maxOf(GROUP, ((task.span.toLong() + MAX_OPEN - 1) / MAX_OPEN).toInt())
            val childCount = ((task.span.toLong() + childSpan - 1) / childSpan).toInt()
            val files = arrayOfNulls<Path>(childCount)
            val outs = arrayOfNulls<DataOutputStream>(childCount)
            val counts = LongArray(childCount)
            try {
                DataInputStream(BufferedInputStream(Files.newInputStream(task.file), BUFFER)).use { input ->
                    for (i in 0 until task.records) {
                        if (i % CANCEL_CHECK == 0L && cancelled()) throw CancellationException("plan conversion cancelled")
                        if (!read(input, task.raw)) continue
                        val child = (record[0] - task.lo) / childSpan
                        val out = outs[child] ?: run {
                            val file = temp("split")
                            files[child] = file
                            DataOutputStream(BufferedOutputStream(Files.newOutputStream(file), BUFFER / MAX_OPEN * 4)).also { outs[child] = it }
                        }
                        out.writeInt(record[0])
                        out.writeInt(record[1])
                        out.writeInt(record[2])
                        counts[child]++
                    }
                }
            } finally {
                for (out in outs) out?.close()
            }
            return (0 until childCount).mapNotNull { child ->
                files[child]?.let {
                    val lo = task.lo + child * childSpan
                    Task(it, false, counts[child], lo, minOf(childSpan, task.lo + task.span - lo))
                }
            }
        }

        private fun group(task: Task, sink: SectionSink) {
            val cells = arrayOfNulls<IntArray>(task.span)
            var held = 0
            DataInputStream(BufferedInputStream(Files.newInputStream(task.file), BUFFER)).use { input ->
                for (i in 0 until task.records) {
                    if (i % CANCEL_CHECK == 0L && cancelled()) throw CancellationException("plan conversion cancelled")
                    if (!read(input, task.raw)) continue
                    val slot = record[0] - task.lo
                    // A cell with no record is structure void: vanilla saves void by leaving the cell out.
                    val ids = cells[slot] ?: IntArray(PLAN_SECTION_CELLS) { PLAN_KEEP_ID }.also {
                        cells[slot] = it
                        held++
                    }
                    // A later record for the same cell wins, as in a vanilla placement.
                    ids[record[1]] = record[2]
                }
            }
            peakSections = maxOf(peakSections, held)
            for (slot in cells.indices) {
                val data = cells[slot]?.let(PlanSectionData::of) ?: continue
                sink.add(task.lo + slot, data)
            }
        }
    }
}
