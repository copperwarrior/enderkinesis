package org.shipwrights.enderkinesis.client.model

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.joml.Matrix4f
import org.joml.Vector3f
import org.junit.jupiter.api.Test

class ClawhodHandModelTest {

    // Cube centres in Blockbench space, from the "elements" of clawhod_left/right.bbmodel.
    private val bbCentres = mapOf(
        "palm" to Vector3f(0f, 1f, 0.5f),
        "finger" to Vector3f(3f, 1f, -4f),
        "finger2" to Vector3f(0f, 1f, -4f),
        "finger3" to Vector3f(-3f, 1f, -4f),
    )
    private val leftThumb = Vector3f(-3.5f, 1f, 1f)
    private val rightThumb = Vector3f(3.5f, 1f, 1f)

    /** The export mirrors X, and Y runs down from 24: Blockbench (x, y, z) is model (-x, 24 - y, z). */
    private fun bbCentre(left: Boolean, name: String): Vector3f {
        val specs = partSpecs(left)
        val palm = specs.first()
        val spec = specs.first { it.name == name }
        val base = if (spec === palm) Vector3f() else Vector3f(palm.offset.first, palm.offset.second, palm.offset.third)
        val x = base.x + spec.offset.first + spec.boxMin.first + spec.size.first / 2
        val y = base.y + spec.offset.second + spec.boxMin.second + spec.size.second / 2
        val z = base.z + spec.offset.third + spec.boxMin.third + spec.size.third / 2
        return Vector3f(-x, 24f - y, z)
    }

    private fun near(a: Vector3f, b: Vector3f) {
        a.x shouldBe (b.x plusOrMinus 1e-4f)
        a.y shouldBe (b.y plusOrMinus 1e-4f)
        a.z shouldBe (b.z plusOrMinus 1e-4f)
    }

    @Test
    fun `cubes land where the bbmodel puts them for both hands`() {
        for (left in listOf(true, false)) {
            for ((name, expected) in bbCentres) near(bbCentre(left, name), expected)
            near(bbCentre(left, "finger4"), if (left) leftThumb else rightThumb)
        }
    }

    @Test
    fun `left thumb is on the other side from the right thumb`() {
        (bbCentre(true, "finger4").x < 0f) shouldBe true
        (bbCentre(false, "finger4").x > 0f) shouldBe true
    }

    @Test
    fun `model rotation is the Blockbench rotation seen in the mirrored space`() {
        val mirror = Matrix4f().scaling(-1f, -1f, 1f)
        for (angles in listOf(Triple(-17.5f, 0f, 0f), Triple(30f, -15f, 75f), Triple(-90f, 14f, -74f))) {
            val (mx, my, mz) = modelRotation(angles.first, angles.second, angles.third)
            for (dir in listOf(Vector3f(0f, 0f, -1f), Vector3f(1f, 0f, 0f), Vector3f(0f, 1f, 0f))) {
                val bb = Matrix4f().rotateZYX(rad(angles.third), rad(angles.second), rad(angles.first))
                val expected = bb.transformDirection(Vector3f(dir)).let { mirror.transformDirection(it) }
                val model = Matrix4f().rotateZYX(rad(mz), rad(my), rad(mx))
                val actual = model.transformDirection(mirror.transformDirection(Vector3f(dir), Vector3f()))
                near(actual, expected)
            }
        }
    }

    @Test
    fun `idle palm tilt of minus 17 point 5 droops the fingers`() {
        val (mx, my, mz) = modelRotation(-17.5f, 0f, 0f)
        val tip = Matrix4f().rotateZYX(rad(mz), rad(my), rad(mx)).transformDirection(Vector3f(0f, 0f, -1f))
        // Model Y points down.
        (tip.y > 0f) shouldBe true
    }

    private fun rad(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()
}
