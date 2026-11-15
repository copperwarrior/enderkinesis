package org.shipwrights.enderkinesis.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LiftAxisTest {

    @Test
    fun levelShipLiftsAlongY() {
        liftAxis(0.0, 1.0, 0.0) shouldBe 1
    }

    @Test
    fun tiesPreferY() {
        liftAxis(0.5, 0.5, 0.5) shouldBe 1
    }

    @Test
    fun shipOnItsSideLiftsAlongX() {
        liftAxis(-0.9, 0.1, 0.2) shouldBe 0
    }

    @Test
    fun upsideDownShipStillUsesY() {
        liftAxis(0.0, -1.0, 0.0) shouldBe 1
    }

    @Test
    fun shipPitchedOntoItsNoseLiftsAlongZ() {
        liftAxis(0.1, 0.2, 0.95) shouldBe 2
    }
}
