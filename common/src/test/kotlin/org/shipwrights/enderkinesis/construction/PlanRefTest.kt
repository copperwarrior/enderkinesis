package org.shipwrights.enderkinesis.construction

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import java.util.UUID

class PlanRefTest : FunSpec({
    val ref = PlanRef(UUID.randomUUID(), "0123456789abcdef".repeat(4), "house", Vec3i(3, 4, 5))

    test("a plan ref survives a tag round trip") {
        ref.toTag().toPlanRef() shouldBe ref
    }

    test("a tag with a bad hash or no owner is not a plan ref") {
        ref.toTag().also { it.putString("Hash", "../x") }.toPlanRef() shouldBe null
        CompoundTag().toPlanRef() shouldBe null
    }
})
