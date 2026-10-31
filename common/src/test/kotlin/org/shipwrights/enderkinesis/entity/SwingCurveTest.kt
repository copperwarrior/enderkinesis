package org.shipwrights.enderkinesis.entity

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.junit.jupiter.api.Test

class SwingCurveTest {

    private val up = Vector3d(0.0, 1.0, 0.0)
    private fun at(from: Vec3, to: Vec3, p: Double) = ConstriliarGait.swingPoint(from, to, up, p, Vector3d())

    @Test
    fun `the curve starts at the lift-off and ends at the touchdown`() {
        val from = Vec3(0.0, 0.0, 0.0)
        val to = Vec3(1.0, 0.0, 0.0)
        at(from, to, 0.0).distance(0.0, 0.0, 0.0) shouldBe (0.0 plusOrMinus 1e-9)
        at(from, to, 1.0).distance(1.0, 0.0, 0.0) shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `the foot pulls back and up before it carries forward`() {
        val p = at(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), 0.12)
        p.x shouldBeLessThan 0.0
        p.y shouldBeGreaterThan 0.0
    }

    @Test
    fun `a step up rises before the foot is half way across`() {
        val p = at(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 0.0), 0.4)
        p.x shouldBeLessThan 0.5
        p.y shouldBeGreaterThan 0.8
    }
}
