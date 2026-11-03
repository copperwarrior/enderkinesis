package org.shipwrights.enderkinesis.dimension

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WogorTreeSkeletonTest {

    private fun tree(taper: Boolean = false, curvedFlare: Boolean = false, flare: Int = 0) = WogorTreeSkeleton.build(
        originX = 0.0, originY = 0.0, originZ = 0.0,
        attractors = WogorTreeSkeleton.ellipsoidAttractors(0.0, 40.0, 0.0, 25.0, 8.0, 25.0, 800, 1234),
        defaultDir = doubleArrayOf(0.0, 1.0, 0.0),
        attractionDist = 20.0, killDist = 4.0, stepSize = 3.5, maxIterations = 110, branchBias = 0.15,
        maxThickness = MAX_T, thicknessScale = 0.4, buttressFlare = flare, buttressRange = 20.0,
        taper = taper, curvedFlare = curvedFlare,
    )

    /** Radii of the trunk segments, foot first: the chain of segments that starts at the origin. */
    private fun trunkRadii(skeleton: TreeSkeleton): List<Double> {
        val out = ArrayList<Double>()
        var x = 0.0; var y = 0.0; var z = 0.0
        while (true) {
            val next = skeleton.segments.filter { it.startX == x && it.startY == y && it.startZ == z }
                .maxByOrNull { it.endRadius } ?: break
            out += next.startRadius
            x = next.endX; y = next.endY; z = next.endZ
        }
        return out
    }

    @Test
    fun `taper narrows the trunk from the cap at the foot`() {
        val radii = trunkRadii(tree(taper = true))
        radii.first() shouldBe (MAX_T.toDouble() plusOrMinus 1e-9)
        radii.zipWithNext().all { (a, b) -> b <= a + 1e-9 } shouldBe true
        radii[radii.size / 2] shouldBeLessThan MAX_T * 0.95
    }

    @Test
    fun `curved flare is wide at the foot and gone well up the trunk`() {
        val linear = trunkRadii(tree(flare = FLARE))
        val curved = trunkRadii(tree(flare = FLARE, curvedFlare = true))
        curved.first() shouldBeGreaterThan MAX_T.toDouble()
        // Two steps up the trunk the curve has fallen away faster than the straight ramp.
        curved[2] shouldBeLessThan linear[2]
    }

    private companion object {
        const val MAX_T = 6
        const val FLARE = 10
    }
}
