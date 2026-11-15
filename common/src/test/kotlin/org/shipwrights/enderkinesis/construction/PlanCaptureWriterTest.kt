package org.shipwrights.enderkinesis.construction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import java.nio.file.Files
import java.security.MessageDigest

private fun block(name: String) = CompoundTag().also { it.putString("Name", name) }

class PlanCaptureWriterTest : FunSpec({
    val dir = Files.createTempDirectory("plan-capture-test")
    afterSpec { dir.toFile().deleteRecursively() }

    test("sections added out of order read back by index") {
        val size = Vec3i(20, 20, 3)
        val writer = PlanCaptureWriter(size, Files.createTempFile(dir, "data", ".tmp"))
        val stone = writer.idOf(block("minecraft:stone"))
        val oak = writer.idOf(block("minecraft:oak_planks"))
        writer.idOf(block("minecraft:stone")) shouldBe stone
        writer.idOf(planKeepTag()) shouldBe PLAN_KEEP_ID
        val grid = writer.grid
        // Column order: both sections of column (1, 0) before the upper section of column (0, 0).
        val order = listOf(grid.index(0, 0, 0), grid.index(1, 0, 0), grid.index(1, 1, 0), grid.index(0, 1, 0))
        for (index in order) {
            val cells = IntArray(PLAN_SECTION_CELLS) { PLAN_AIR_ID }
            cells[cellIndex(0, 0, 0)] = if (index % 2 == 0) stone else oak
            cells[cellIndex(1, 0, 0)] = PLAN_KEEP_ID
            writer.add(index, cells)
        }
        writer.nonAir shouldBe 4L
        val target = dir.resolve("out.plan")
        val hash = writer.finish(target)
        PlanRef.isHash(hash) shouldBe true
        hash shouldBe PlanUploadStream.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target)))

        val file = PlanSectionFile.open(target)
        file.header.size shouldBe size
        file.header.nonAir shouldBe 4L
        file.header.nonEmpty.toList() shouldBe order.sorted()
        for (index in order) {
            val data = file.data(index)!!
            val name = file.header.palette[data.idAt(0, 0, 0)].getString("Name")
            name shouldBe if (index % 2 == 0) "minecraft:stone" else "minecraft:oak_planks"
            data.idAt(1, 0, 0) shouldBe PLAN_KEEP_ID
            data.idAt(2, 0, 0) shouldBe PLAN_AIR_ID
        }
    }

    test("a section of only structure void is not stored") {
        val writer = PlanCaptureWriter(Vec3i(16, 32, 16), Files.createTempFile(dir, "data", ".tmp"))
        writer.add(0, IntArray(PLAN_SECTION_CELLS) { PLAN_KEEP_ID })
        writer.add(1, IntArray(PLAN_SECTION_CELLS) { PLAN_AIR_ID })
        writer.count shouldBe 1
        val target = dir.resolve("void.plan")
        writer.finish(target)
        val file = PlanSectionFile.open(target)
        file.data(0) shouldBe null
        file.data(1) shouldNotBe null
    }

    test("the same section twice is refused") {
        val writer = PlanCaptureWriter(Vec3i(16, 16, 16), Files.createTempFile(dir, "data", ".tmp"))
        writer.add(0, IntArray(PLAN_SECTION_CELLS) { PLAN_AIR_ID })
        writer.add(0, IntArray(PLAN_SECTION_CELLS) { PLAN_AIR_ID })
        shouldThrow<IllegalArgumentException> { writer.finish(dir.resolve("twice.plan")) }
    }
})
