package org.shipwrights.enderkinesis.construction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path
import java.util.UUID

class PlanFilesTest : FunSpec({
    val root = Path.of("/world/data/enderkinesis/plans")
    val owner = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val hash = "a".repeat(64)

    test("plan files sit in the owner folder") {
        planFile(root, owner, hash) shouldBe root.resolve(owner.toString()).resolve("$hash.plan")
    }

    test("hashes that are not lowercase hex are refused") {
        for (bad in listOf("../../" + "a".repeat(58), "A".repeat(64), "a".repeat(63), "a".repeat(65), "", "g".repeat(64))) {
            shouldThrow<IllegalArgumentException> { planFile(root, owner, bad) }
            PlanRef.isHash(bad) shouldBe false
        }
    }

    test("display names lose path parts, the extension and odd characters") {
        cleanPlanName("../../etc/house.nbt") shouldBe "house"
        cleanPlanName("C:\\plans\\tower (2).nbt") shouldBe "tower (2)"
        cleanPlanName("a\u0000b\nc.nbt") shouldBe "abc"
        cleanPlanName(".nbt") shouldBe "plan"
        cleanPlanName("x".repeat(200) + ".nbt").length shouldBe PlanRef.MAX_NAME
        cleanPlanName("a/b") shouldNotContain "/"
    }
})
