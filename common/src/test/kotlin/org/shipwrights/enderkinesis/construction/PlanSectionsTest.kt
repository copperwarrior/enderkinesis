package org.shipwrights.enderkinesis.construction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/** One block record of a generated structure file. */
private class Cell(val x: Int, val y: Int, val z: Int, val state: Int, val data: Boolean = false)

/**
 * Writes a gzip structure file tag by tag, so a test file of any size never exists as a tag tree.
 * "blocks" comes before "palette" and "size", which a streamed reader must accept.
 */
private fun writeStructure(path: Path, size: Vec3i, palette: List<String>, cells: Sequence<Cell>, count: Int, extra: (DataOutputStream) -> Unit = {}) {
    DataOutputStream(BufferedOutputStream(GZIPOutputStream(Files.newOutputStream(path)), 1 shl 16)).use { out ->
        out.writeByte(PlanNbtPull.COMPOUND)
        out.writeUTF("")
        extra(out)
        out.writeByte(PlanNbtPull.LIST)
        out.writeUTF("blocks")
        out.writeByte(PlanNbtPull.COMPOUND)
        out.writeInt(count)
        for (cell in cells) {
            out.writeByte(PlanNbtPull.LIST)
            out.writeUTF("pos")
            out.writeByte(PlanNbtPull.INT)
            out.writeInt(3)
            out.writeInt(cell.x)
            out.writeInt(cell.y)
            out.writeInt(cell.z)
            out.writeByte(PlanNbtPull.INT)
            out.writeUTF("state")
            out.writeInt(cell.state)
            if (cell.data) {
                out.writeByte(PlanNbtPull.COMPOUND)
                out.writeUTF("nbt")
                out.writeByte(PlanNbtPull.STRING)
                out.writeUTF("id")
                out.writeUTF("minecraft:chest")
                out.writeByte(PlanNbtPull.END)
            }
            out.writeByte(PlanNbtPull.END)
        }
        out.writeByte(PlanNbtPull.LIST)
        out.writeUTF("entities")
        out.writeByte(PlanNbtPull.COMPOUND)
        out.writeInt(1)
        out.writeByte(PlanNbtPull.END)
        out.writeByte(PlanNbtPull.LIST)
        out.writeUTF("palette")
        out.writeByte(PlanNbtPull.COMPOUND)
        out.writeInt(palette.size)
        for (name in palette) CompoundTag().also { it.putString("Name", name) }.write(out)
        out.writeByte(PlanNbtPull.LIST)
        out.writeUTF("size")
        out.writeByte(PlanNbtPull.INT)
        out.writeInt(3)
        out.writeInt(size.x)
        out.writeInt(size.y)
        out.writeInt(size.z)
        out.writeByte(PlanNbtPull.INT)
        out.writeUTF("DataVersion")
        out.writeInt(3465)
        out.writeByte(PlanNbtPull.END)
    }
}

private val identity = PlanPaletteMapper { raw, _ -> if (raw.getString("Name") == "minecraft:air") null else raw.copy() }

private const val VOID = "minecraft:structure_void"

/** The global palette name at a plan cell, or null for air; reads one section per call, as a consumer would. */
private fun PlanSectionFile.nameAt(x: Int, y: Int, z: Int): String? {
    val grid = header.grid
    val data = data(grid.index(x shr 4, y shr 4, z shr 4)) ?: return VOID
    val id = data.idAt(x and 15, y and 15, z and 15)
    return if (id == PLAN_AIR_ID) null else header.palette[id].getString("Name")
}

class PlanSectionsTest : FunSpec({
    val dir = Files.createTempDirectory("plan-sections-test")
    afterSpec { dir.toFile().deleteRecursively() }
    var serial = 0

    fun convert(size: Vec3i, palette: List<String>, cells: List<Cell>, extra: (DataOutputStream) -> Unit = {}): Pair<PlanConversion, Path> {
        val source = dir.resolve("s${serial}.nbt")
        val target = dir.resolve("t${serial++}.plan")
        writeStructure(source, size, palette, cells.asSequence(), cells.size, extra)
        return PlanSectionWriter.convert(source, target, dir.resolve("scratch"), identity) { false } to target
    }

    test("a section round-trips through its payload") {
        val random = Random(7)
        for (distinct in listOf(1, 2, 3, 16, 17, 300, PLAN_SECTION_CELLS - 1)) {
            val ids = IntArray(PLAN_SECTION_CELLS) { if (it % 5 == 0) 0 else 1 + random.nextInt(distinct) }
            val data = checkNotNull(PlanSectionData.of(ids))
            val back = decodePlanSection(data.encode(), distinct + 1)
            back.nonAir shouldBe ids.count { it > PLAN_KEEP_ID }
            for (cell in ids.indices) back.idAt(cell and 15, cell ushr 8, cell ushr 4 and 15) shouldBe ids[cell]
            var visited = 0
            back.forEachNonAir { x, y, z, id ->
                id shouldBe ids[cellIndex(x, y, z)]
                visited++
            }
            visited shouldBe back.nonAir
        }
    }

    test("an all-void section is not stored, an all-air one is, and a payload must hold a non-void cell") {
        PlanSectionData.of(IntArray(PLAN_SECTION_CELLS) { PLAN_KEEP_ID }) shouldBe null
        val air = checkNotNull(PlanSectionData.of(IntArray(PLAN_SECTION_CELLS)))
        air.nonAir shouldBe 0
        decodePlanSection(air.encode(), 2).isKeep(0) shouldBe false
        shouldThrow<PlanFormatException> { decodePlanSection(byteArrayOf(1, 2, 3), 4) }
        val data = checkNotNull(PlanSectionData.of(IntArray(PLAN_SECTION_CELLS) { 3 }))
        shouldThrow<PlanFormatException> { decodePlanSection(data.encode(), 3) }
    }

    test("a section with no cell in the file is all void and not stored; one with only air is stored") {
        val cells = listOf(Cell(0, 0, 0, 1), Cell(40, 0, 0, 1), Cell(20, 0, 0, 0))
        val (result, target) = convert(Vec3i(64, 1, 1), listOf("minecraft:air", "minecraft:stone"), cells)
        result.shouldBeInstanceOf<PlanConversion.Done>().nonAir shouldBe 2
        val file = PlanSectionFile.open(target)
        file.header.nonEmpty.toList() shouldBe listOf(0, 1, 2)
        file.header.rank(3) shouldBeLessThanOrEqual -1
        file.payload(3) shouldBe null
        file.nameAt(40, 0, 0) shouldBe "minecraft:stone"
        file.nameAt(20, 0, 0) shouldBe null
        file.nameAt(21, 0, 0) shouldBe VOID
        file.nameAt(50, 0, 0) shouldBe VOID
    }

    test("structure void and cells the file leaves out keep the world; air clears it") {
        val cells = listOf(Cell(0, 0, 0, 2), Cell(1, 0, 0, 1), Cell(2, 0, 0, 0))
        val (result, target) = convert(Vec3i(4, 1, 1), listOf("minecraft:air", VOID, "a:one"), cells)
        result.shouldBeInstanceOf<PlanConversion.Done>().nonAir shouldBe 1
        val file = PlanSectionFile.open(target)
        file.header.palette[PLAN_AIR_ID].getString("Name") shouldBe "minecraft:air"
        file.header.palette[PLAN_KEEP_ID] shouldBe planKeepTag()
        val data = checkNotNull(file.data(0))
        data.isKeep(cellIndex(0, 0, 0)) shouldBe false
        data.isKeep(cellIndex(1, 0, 0)) shouldBe true
        data.isKeep(cellIndex(2, 0, 0)) shouldBe false
        data.idAt(2, 0, 0) shouldBe PLAN_AIR_ID
        data.isKeep(cellIndex(3, 0, 0)) shouldBe true
        file.nameAt(0, 0, 0) shouldBe "a:one"
        var blocks = 0
        data.forEachNonAir { _, _, _, _ -> blocks++ }
        blocks shouldBe 1
    }

    test("a plan larger than one section on every axis keeps every cell") {
        val size = Vec3i(40, 35, 33)
        val palette = listOf("minecraft:air", "a:one", "a:two", "a:three")
        fun expected(x: Int, y: Int, z: Int) = (x * 7 + y * 3 + z) % palette.size
        val cells = ArrayList<Cell>()
        for (y in 0 until size.y) for (z in 0 until size.z) for (x in 0 until size.x) cells += Cell(x, y, z, expected(x, y, z))
        val (result, target) = convert(size, palette, cells)
        val done = result.shouldBeInstanceOf<PlanConversion.Done>()
        done.nonAir shouldBe cells.count { it.state != 0 }.toLong()
        val file = PlanSectionFile.open(target)
        val grid = file.header.grid
        grid.count shouldBe 3 * 3 * 3
        for (index in 0 until grid.count) {
            val data = file.data(index)
            val origin = grid.origin(index)
            for (ly in 0 until 16) for (lz in 0 until 16) for (lx in 0 until 16) {
                val x = origin.x + lx
                val y = origin.y + ly
                val z = origin.z + lz
                val id = data?.idAt(lx, ly, lz) ?: PLAN_KEEP_ID
                val name = if (id == PLAN_AIR_ID) null else file.header.palette[id].getString("Name")
                val inside = x < size.x && y < size.y && z < size.z
                name shouldBe when {
                    !inside -> VOID
                    expected(x, y, z) != 0 -> palette[expected(x, y, z)]
                    else -> null
                }
            }
        }
    }

    test("bad cells drop, block data and entities count, and a later cell wins") {
        val cells = listOf(
            Cell(0, 0, 0, 1, data = true), Cell(0, 0, 0, 2), Cell(5, 0, 0, 1), Cell(0, -1, 0, 1), Cell(1, 0, 0, 9),
        )
        val (result, target) = convert(Vec3i(2, 1, 1), listOf("minecraft:air", "a:one", "a:two"), cells)
        val done = result.shouldBeInstanceOf<PlanConversion.Done>()
        done.dropped shouldBe 3
        done.strippedData shouldBe 1
        done.entities shouldBe 1
        PlanSectionFile.open(target).nameAt(0, 0, 0) shouldBe "a:two"
    }

    test("no palette refuses the plan; deep nesting is skipped up to the cap") {
        convert(Vec3i(1, 1, 1), emptyList(), emptyList()).first.shouldBeInstanceOf<PlanConversion.Refused>().reason shouldBe "empty"
        fun nested(depth: Int): (DataOutputStream) -> Unit = { out ->
            out.writeByte(PlanNbtPull.LIST)
            out.writeUTF("junk")
            repeat(depth) {
                out.writeByte(PlanNbtPull.LIST)
                out.writeInt(1)
            }
            out.writeByte(PlanNbtPull.END)
            out.writeInt(0)
        }
        val palette = listOf("a:one")
        convert(Vec3i(1, 1, 1), palette, listOf(Cell(0, 0, 0, 0)), nested(400)).first.shouldBeInstanceOf<PlanConversion.Done>()
        shouldThrow<PlanFormatException> { convert(Vec3i(1, 1, 1), palette, listOf(Cell(0, 0, 0, 0)), nested(600)) }
    }

    test("a big file streams with a bounded count of sections in memory") {
        // 288 sections, over GROUP, so the records split by range before any section is built.
        val size = Vec3i(192, 32, 192)
        val count = size.x * size.y * size.z
        val palette = listOf("a:one", "a:two", "a:three", "a:four")
        val source = dir.resolve("big.nbt")
        val cells = sequence {
            for (y in 0 until size.y) for (z in 0 until size.z) for (x in 0 until size.x) yield(Cell(x, y, z, (x + y + z) % palette.size))
        }
        writeStructure(source, size, palette, cells, count)
        val target = dir.resolve("big.plan")
        val done = PlanSectionWriter.convert(source, target, dir.resolve("scratch"), identity) { false }
            .shouldBeInstanceOf<PlanConversion.Done>()
        done.nonAir shouldBe count.toLong()
        done.peakSections shouldBeLessThanOrEqual PlanSectionWriter.GROUP
        val file = PlanSectionFile.open(target)
        file.header.nonEmpty.size shouldBe 12 * 2 * 12
        for ((x, y, z) in listOf(Triple(0, 0, 0), Triple(191, 31, 191), Triple(100, 17, 3), Triple(16, 16, 16))) {
            file.nameAt(x, y, z) shouldBe palette[(x + y + z) % palette.size]
        }
        Files.list(dir.resolve("scratch")).use { it.count() } shouldBe 0
    }

    test("a cancelled conversion stops and leaves no files") {
        val source = dir.resolve("cancel.nbt")
        writeStructure(source, Vec3i(16, 16, 16), listOf("a:one"), sequenceOf(Cell(0, 0, 0, 0)), 1)
        val target = dir.resolve("cancel.plan")
        shouldThrow<java.util.concurrent.CancellationException> {
            PlanSectionWriter.convert(source, target, dir.resolve("scratch"), identity) { true }
        }
        Files.exists(target) shouldBe false
    }

    test("the grid orders sections bottom-up and refuses sizes past an int") {
        val grid = PlanGrid(Vec3i(33, 17, 16))
        grid.count shouldBe 3 * 2 * 1
        val index = grid.index(2, 1, 0)
        Triple(grid.sectionX(index), grid.sectionY(index), grid.sectionZ(index)) shouldBe Triple(2, 1, 0)
        grid.index(0, 1, 0) shouldBe 3
        PlanGrid.fits(Vec3i(Int.MAX_VALUE, Int.MAX_VALUE, 16)) shouldBe false
        shouldThrow<IllegalArgumentException> { PlanGrid(Vec3i(0, 1, 1)) }
    }

    test("a header that claims a huge section count runs out of data, not memory") {
        val size = Vec3i(16 * 1024, 16 * 1024, 16 * 1024)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(PlanHeader.MAGIC)
            out.writeInt(PlanHeader.VERSION)
            out.writeInt(size.x)
            out.writeInt(size.y)
            out.writeInt(size.z)
            out.writeLong(1)
            out.writeInt(2)
            NbtIo.write(CompoundTag().also { it.putString("Name", "minecraft:air") }, out)
            NbtIo.write(planKeepTag(), out)
            out.writeInt(PlanGrid(size).count)
            out.writeInt(0)
        }
        shouldThrow<EOFException> {
            PlanHeader.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        }
    }
})
