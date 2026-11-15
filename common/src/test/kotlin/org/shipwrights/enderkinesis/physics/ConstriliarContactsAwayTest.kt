package org.shipwrights.enderkinesis.physics

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.joml.Vector3d
import org.junit.jupiter.api.Test

class ConstriliarContactsAwayTest {

    private val up = Vector3d(0.0, 1.0, 0.0)
    private val plate = Vector3d(0.0, 0.0, 0.0)
    private val lateral = Vector3d()
    private val out = Vector3d()

    private fun point(px: Double, py: Double, pz: Double, nx: Double, ny: Double, nz: Double): DoubleArray =
        doubleArrayOf(px, py, pz, nx, ny, nz, 0.0, 0.0)

    @Test
    fun `a wall contact pushes the body away whatever the normal's sign`() {
        for (sign in doubleArrayOf(1.0, -1.0)) {
            ConstriliarContacts.awayFromContact(point(0.5, 0.5, 0.0, sign, 0.0, 0.0), 0, plate, up, lateral, out) shouldBe true
            out.x shouldBe (-1.0 plusOrMinus 1.0e-9)
            out.y shouldBe (0.0 plusOrMinus 1.0e-9)
        }
    }

    @Test
    fun `a sloped normal keeps only its in-plane part`() {
        ConstriliarContacts.awayFromContact(point(0.0, 1.0, -0.6, 0.0, 0.6, 0.8), 0, plate, up, lateral, out) shouldBe true
        out.z shouldBe (1.0 plusOrMinus 1.0e-9)
        out.y shouldBe (0.0 plusOrMinus 1.0e-9)
    }

    @Test
    fun `support and ceiling contacts do not push`() {
        // Floor under the plate: the normal is along up.
        ConstriliarContacts.awayFromContact(point(0.6, -0.5, 0.0, 0.0, 1.0, 0.0), 0, plate, up, lateral, out) shouldBe false
        // On the body's axis: no side to push to.
        ConstriliarContacts.awayFromContact(point(0.05, 1.5, 0.0, 1.0, 0.0, 0.0), 0, plate, up, lateral, out) shouldBe false
    }

    @Test
    fun `a ceiling contact over the load is a top contact and the floor is not`() {
        ConstriliarContacts.isTopContact(point(0.3, 1.5, 0.0, 0.0, -1.0, 0.0), 0, plate, up) shouldBe true
        ConstriliarContacts.isTopContact(point(0.3, -0.5, 0.0, 0.0, 1.0, 0.0), 0, plate, up) shouldBe false
        // A wall beside the load is not a top contact.
        ConstriliarContacts.isTopContact(point(0.5, 1.5, 0.0, 1.0, 0.0, 0.0), 0, plate, up) shouldBe false
    }

    @Test
    fun `each point names the other body of its pair from either side`() {
        val snap = ConstriliarContacts.Snapshot(0L, 2, DoubleArray(16), FloatArray(2), longArrayOf(5L, 9L, 9L, 7L))
        snap.other(0, 5L) shouldBe 9L
        snap.other(0, 9L) shouldBe 5L
        snap.other(1, 9L) shouldBe 7L
    }

    @Test
    fun `the point stride holds the approach speed and the start flag after the normal`() {
        ConstriliarContacts.STRIDE shouldBe 8
        ConstriliarContacts.SPEED shouldBe 6
        ConstriliarContacts.START shouldBe 7
    }
}
