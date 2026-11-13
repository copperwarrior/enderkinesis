package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.world.phys.AABB
import org.junit.jupiter.api.Test
import org.valkyrienskies.core.api.chains.Chain
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.chains.ChainLifecycle
import org.valkyrienskies.core.api.chains.ChainStatus
import org.valkyrienskies.core.api.particles.ParticleId

class VineCarryFollowTest {

    private object FakeChain : Chain {
        override val id = ChainId(1)
        override val dimension = "minecraft:overworld"
        override val status = ChainStatus(ChainLifecycle.ACTIVE, nodeCount = NODES)
        override val nodeIds = emptyList<ParticleId>()
        override fun copyNodePositions(output: DoubleArray, offset: Int): Int = 0
    }

    private val collider = VineCollider()
    private val support = VineSupport()
    // Feet on the top of a level vine along x; no VS2 velocities, so only the followed point can carry.
    private val box = AABB(0.7, VINE_HALF_WIDTH, -0.3, 1.3, VINE_HALF_WIDTH + 1.8, 0.3)

    /** The vine of this tick: a level line from x 0 to 2, moved by (dx, dy, dz). */
    private fun frame(dx: Double, dy: Double, dz: Double) {
        val positions = DoubleArray(NODES * 3)
        for (i in 0 until NODES) {
            positions[i * 3] = i * SPACING + dx; positions[i * 3 + 1] = dy; positions[i * 3 + 2] = dz
        }
        collider.clear()
        collider.add(FakeChain, positions, NODES, 0, frameArrival = 0L)
    }

    @Test
    fun `the feet follow the support point through a sideways sway`() {
        frame(0.0, 0.0, 0.0)
        collider.platformMove(box, standing = true, support, tick = 0).shouldBeNull()
        frame(0.0, -0.1, 0.35)
        val move = collider.platformMove(box, standing = true, support, tick = 1).shouldNotBeNull()
        move.x() shouldBe (0.0 plusOrMinus 1e-9)
        move.y() shouldBe (-0.1 plusOrMinus 1e-9)
        move.z() shouldBe (0.35 plusOrMinus 1e-9)
    }

    @Test
    fun `one tick off the ground still follows, a second does not`() {
        frame(0.0, 0.0, 0.0)
        collider.platformMove(box, standing = true, support, tick = 0)
        frame(0.0, -0.1, 0.2)
        val graced = collider.platformMove(box, standing = false, support, tick = 1).shouldNotBeNull()
        graced.z() shouldBe (0.2 plusOrMinus 1e-9)
        val moved = box.move(graced.x(), graced.y(), graced.z())
        frame(0.0, -0.2, 0.4)
        collider.platformMove(moved, standing = false, support, tick = 2).shouldBeNull()
    }

    @Test
    fun `a support point from an older tick is not followed`() {
        frame(0.0, 0.0, 0.0)
        collider.platformMove(box, standing = true, support, tick = 0)
        frame(0.0, -0.1, 0.2)
        collider.platformMove(box, standing = true, support, tick = 2).shouldBeNull()
    }

    private companion object {
        const val NODES = 6
        const val SPACING = 0.4
    }
}
