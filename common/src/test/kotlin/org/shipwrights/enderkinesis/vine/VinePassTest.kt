package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.VoxelShape
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.valkyrienskies.core.api.chains.Chain
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.chains.ChainLifecycle
import org.valkyrienskies.core.api.chains.ChainStatus
import org.valkyrienskies.core.api.particles.ParticleId

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VinePassTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private class FakeChain(chain: Long, private val positions: DoubleArray) : Chain {
        override val id = ChainId(chain)
        override val dimension = "minecraft:overworld"
        override val status = ChainStatus(ChainLifecycle.ACTIVE, nodeCount = positions.size / 3)
        override val nodeIds = emptyList<ParticleId>()
        override fun copyNodePositions(output: DoubleArray, offset: Int): Int {
            if (output.size - offset >= positions.size) positions.copyInto(output, offset)
            return positions.size / 3
        }
    }

    /** Two vertical vines through the body box: chain 1 at x = 0, chain 2 at x = 0.2. */
    private val collider = VineCollider().apply {
        add(FakeChain(1, doubleArrayOf(0.0, 3.0, 0.0, 0.0, 0.0, 0.0)), 0).shouldBeTrue()
        add(FakeChain(2, doubleArrayOf(0.2, 3.0, 0.0, 0.2, 0.0, 0.0)), 0).shouldBeTrue()
    }
    private val body = AABB(-0.3, 0.5, -0.3, 0.3, 2.3, 0.3)
    private val away = AABB(10.0, 0.5, 10.0, 10.6, 2.3, 10.6)

    private fun shapesOf(pass: VinePass?): List<AABB> = collider.withShapes(body, emptyList<VoxelShape>(), pass).map { it.bounds() }

    @Test
    fun `the held vine has no shapes and does not move the body, and the other vine stays solid`() {
        val hold = ClientVineHold().apply { isHeld = true; chainId = 1L }
        hold.passes(1L).shouldBeTrue()
        hold.passes(2L).shouldBeFalse()
        shapesOf(hold).all { it.minX > 0.0 }.shouldBeTrue()
        shapesOf(hold).isEmpty().shouldBeFalse()
        val only1 = VinePass { it == 1L || it == 2L }
        collider.withShapes(body, emptyList<VoxelShape>(), only1).isEmpty().shouldBeTrue()
        collider.platformMove(body, standing = true, pass = only1).shouldBeNull()
        collider.touchesNear(body, only1).shouldBeFalse()
    }

    @Test
    fun `a held vine stays passable in a tick where the hold is lost`() {
        val hold = ClientVineHold().apply { isHeld = true; chainId = 1L; lost = true }
        hold.passes(1L).shouldBeTrue()
    }

    @Test
    fun `a vine let go of stays passable for the grace ticks when the body is clear of it`() {
        val hold = ClientVineHold()
        hold.startGrace(1L)
        repeat(VINE_PASS_GRACE_TICKS - 1) { hold.tickGrace(collider, away) }
        hold.passes(1L).shouldBeTrue()
        hold.tickGrace(collider, away)
        hold.passes(1L).shouldBeFalse()
        hold.passesAny.shouldBeFalse()
    }

    @Test
    fun `after the grace a vine stays passable until the body is clear of it`() {
        val hold = ClientVineHold()
        hold.startGrace(1L)
        repeat(VINE_PASS_GRACE_TICKS * 3) { hold.tickGrace(collider, body) }
        hold.passes(1L).shouldBeTrue()
        hold.tickGrace(collider, away)
        hold.passes(1L).shouldBeFalse()
    }

    @Test
    fun `a knot hop puts the vine left behind in grace, and a mantle makes its vine solid at once`() {
        val hold = ClientVineHold().apply { isHeld = true; chainId = 1L }
        hold.switchChain(2L, 0.0)
        hold.passes(1L).shouldBeTrue()
        hold.passes(2L).shouldBeTrue()
        hold.startGrace(2L)
        hold.endGrace(2L)
        hold.clear()
        hold.passes(2L).shouldBeFalse()
        hold.passes(1L).shouldBeTrue()
    }

    @Test
    fun `climbing back and forth keeps one grace entry per vine`() {
        val hold = ClientVineHold()
        repeat(20) { hold.startGrace(if (it % 2 == 0) 1L else 2L) }
        hold.passes(1L).shouldBeTrue()
        hold.passes(2L).shouldBeTrue()
        repeat(VINE_PASS_GRACE_TICKS) { hold.tickGrace(collider, away) }
        hold.passesAny shouldBe false
    }
}
