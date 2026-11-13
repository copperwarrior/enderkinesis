package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.joml.Vector3d
import org.junit.jupiter.api.Test

class VinePushImpulseTest {
    private val player = 78.0
    private val links = 4.0
    private val g = 9.81
    private val hit = reducedMass(player, links)

    @Test
    fun `a hit moves the reduced mass of player and links`() {
        hit shouldBe (player * links / (player + links) plusOrMinus 1e-12)
        reducedMass(player, 0.0) shouldBe 0.0
        reducedMass(0.0, links) shouldBe 0.0
    }

    @Test
    fun `a resting step gives the weight for one tick only`() {
        val impulse = vinePushImpulse(player, hit, g, 0.0, -REST_FALL, 0.0, false, Vector3d())
        impulse.y shouldBe (-player * g / 20 plusOrMinus 1e-9)
        impulse.x shouldBe 0.0
        impulse.z shouldBe 0.0
    }

    @Test
    fun `a rider particle carries the weight and the landing`() {
        val impulse = vinePushImpulse(player, hit, g, 0.0, -0.3, 0.0, true, Vector3d())
        impulse.y shouldBe 0.0
    }

    @Test
    fun `a landing adds the reduced-mass hit, not the player's whole momentum`() {
        val by = -0.155
        val impulse = vinePushImpulse(player, hit, g, 0.0, by, 0.0, false, Vector3d())
        val landing = hit * (-by - REST_FALL) * 20
        impulse.y shouldBe (-player * g / 20 - landing plusOrMinus 1e-9)
        // The old formula gave player * (-by - REST_FALL) * 20, about 20 times this on two 2 kg nodes.
        (landing < player * (-by - REST_FALL) * 20 / 20) shouldBe true
    }

    @Test
    fun `a stop gives the reduced-mass hit along the stop`() {
        val impulse = vinePushImpulse(player, hit, g, 0.2, 0.0, -0.1, false, Vector3d())
        impulse.x shouldBe (hit * 0.2 * 20 plusOrMinus 1e-9)
        impulse.y shouldBe 0.0
        impulse.z shouldBe (hit * -0.1 * 20 plusOrMinus 1e-9)
    }
}
