package org.shipwrights.enderkinesis.construction

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag

private fun entry(name: String) = CompoundTag().also { it.putString("Name", name) }

class PlanSanitizerTest : FunSpec({
    val known = setOf("minecraft:air", "minecraft:stone", "minecraft:command_block")

    fun sanitizer(trusted: Boolean = false) =
        PlanPaletteSanitizer({ it in known }, keepAdminBlocks = trusted, fix = { tag, _ -> tag }, canonical = { it })

    test("air names map to air, structure void to the keep tag, and known blocks as they are") {
        val s = sanitizer()
        s.map(entry("minecraft:air"), 0) shouldBe null
        s.map(entry("minecraft:structure_void"), 0) shouldBe planKeepTag()
        s.map(entry("stone"), 0)?.getString("Name") shouldBe "stone"
        s.issues shouldBe emptyList()
    }

    test("unknown blocks become no block with a report line") {
        val s = sanitizer()
        s.map(entry("othermod:gizmo"), 0) shouldBe null
        s.issues.single().key.endsWith("unknown_block") shouldBe true
        s.issues.single().args shouldBe listOf("othermod:gizmo")
    }

    test("admin blocks go for untrusted players and stay for trusted ones") {
        sanitizer().map(entry("minecraft:command_block"), 0) shouldBe null
        sanitizer(trusted = true).map(entry("minecraft:command_block"), 0)?.getString("Name") shouldBe "minecraft:command_block"
    }

    test("the data fixer sees the file data version") {
        var seen = -1
        val s = PlanPaletteSanitizer({ true }, false, fix = { tag, version -> seen = version; entry("minecraft:stone") }, canonical = { it })
        s.map(entry("minecraft:old_stone"), 1234)?.getString("Name") shouldBe "minecraft:stone"
        seen shouldBe 1234
    }

    test("unknown names past the line cap fold into one more line") {
        val s = sanitizer()
        for (i in 0 until 12) s.map(entry("othermod:b$i"), 0)
        s.issues.count { it.key.endsWith("unknown_block") } shouldBe 8
        s.issues.count { it.key.endsWith("unknown_block_more") } shouldBe 1
    }

    test("the report lists dropped cells and stripped data, and never entities") {
        val done = PlanConversion.Done(Vec3i(1, 1, 1), 1, dropped = 3, strippedData = 2, entities = 1, peakSections = 1)
        val check = planCheckOf(done, emptyList())
        check.accepted shouldBe true
        check.issues.map { it.key.substringAfterLast('.') } shouldBe listOf("dropped_cells", "stripped_block_data")
        planCheckOf(PlanConversion.Refused("empty"), emptyList()).accepted shouldBe false
    }
})
