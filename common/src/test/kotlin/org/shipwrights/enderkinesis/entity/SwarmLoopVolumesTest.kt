package org.shipwrights.enderkinesis.entity

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SwarmLoopVolumesTest {
    private val tolerance = 0.001f

    @Test
    fun `few flies are sparse only`() {
        for (count in 1..3) {
            swarmSparseVolume(count, 1f) shouldBe 1f
            swarmDenseVolume(count, 1f) shouldBe 0f
        }
    }

    @Test
    fun `twelve flies mix both loops`() {
        swarmSparseVolume(12, 1f) shouldBe (0.6f plusOrMinus tolerance)
        swarmDenseVolume(12, 1f) shouldBe (0.6f plusOrMinus tolerance)
    }

    @Test
    fun `full swarm is mostly dense`() {
        swarmSparseVolume(24, 1f) shouldBe (0.15f plusOrMinus tolerance)
        swarmDenseVolume(24, 1f) shouldBe (1f plusOrMinus tolerance)
    }

    @Test
    fun `mode scales are rest 0_4 wander 0_7 pester 1`() {
        swarmModeScale(SwarmFlyEntity.Mode.REST) shouldBe 0.4f
        swarmModeScale(SwarmFlyEntity.Mode.WANDER) shouldBe 0.7f
        swarmModeScale(SwarmFlyEntity.Mode.PESTER) shouldBe 1f
    }

    @Test
    fun `scale multiplies both volumes`() {
        swarmSparseVolume(24, 0.4f) shouldBe (0.06f plusOrMinus tolerance)
        swarmDenseVolume(24, 0.7f) shouldBe (0.7f plusOrMinus tolerance)
    }
}
