package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
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
import kotlin.math.sqrt

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VineColliderShapeTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private class FakeChain(private val positions: DoubleArray) : Chain {
        override val id = ChainId(1)
        override val dimension = "minecraft:overworld"
        override val status = ChainStatus(ChainLifecycle.ACTIVE, nodeCount = positions.size / 3)
        override val nodeIds = emptyList<ParticleId>()
        override fun copyNodePositions(output: DoubleArray, offset: Int): Int {
            if (output.size - offset >= positions.size) positions.copyInto(output, offset)
            return positions.size / 3
        }
    }

    private fun collider(joinedEnds: Int, vararg nodes: Double) =
        VineCollider().apply { add(FakeChain(nodes), joinedEnds).shouldBeTrue() }

    private fun shapes(collider: VineCollider): List<AABB> =
        collider.withShapes(AABB(-100.0, -100.0, -100.0, 100.0, 100.0, 100.0), emptyList<VoxelShape>()).map { it.bounds() }

    private fun AABB.holds(x: Double, y: Double, z: Double): Boolean =
        x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ

    @Test
    fun `a long diagonal link is solid along its whole length`() {
        for (length in doubleArrayOf(0.4, 3.0, 7.9)) {
            val u = length / sqrt(3.0)
            val boxes = shapes(collider(0, 0.0, 0.0, 0.0, u, u, u))
            for (s in 0..1000) {
                val t = u * s / 1000
                boxes.any { it.holds(t, t, t) }.shouldBeTrue()
            }
            for (i in 0 until boxes.size - 1) boxes[i].intersects(boxes[i + 1]).shouldBeTrue()
        }
    }

    @Test
    fun `a joined end has a solid knot as wide as the drawn knot`() {
        val nodes = doubleArrayOf(0.0, 0.0, 0.0, 0.4, 0.0, 0.0, 0.8, 0.0, 0.0)
        val bare = collider(0, *nodes)
        val joined = collider(JOINED_START or JOINED_END, *nodes)
        val knot = AABB(-VINE_KNOT_HALF, -VINE_KNOT_HALF, -VINE_KNOT_HALF, VINE_KNOT_HALF, VINE_KNOT_HALF, VINE_KNOT_HALF)
        shapes(joined).any { it == knot }.shouldBeTrue()
        shapes(joined).any { it == knot.move(0.8, 0.0, 0.0) }.shouldBeTrue()
        shapes(bare).any { it == knot }.shouldBeFalse()
        // Between the link top (2/16) and the knot top (3/16), over the start node.
        joined.touches(-0.01, 0.15, -0.01, 0.01, 0.16, 0.01).shouldBeTrue()
        bare.touches(-0.01, 0.15, -0.01, 0.01, 0.16, 0.01).shouldBeFalse()
    }

    @Test
    fun `a box on a knot only gives a contact on the end link`() {
        val nodes = doubleArrayOf(0.0, 0.0, 0.0, 0.4, 0.0, 0.0, 0.8, 0.0, 0.0)
        val box = AABB(-0.01, 0.15, -0.01, 0.01, 0.16, 0.01)
        val contacts = VineContacts()
        collider(0, *nodes).contacts(box, contacts)
        contacts.size shouldBe 0
        val joined = collider(JOINED_START, *nodes)
        joined.contacts(box, contacts)
        contacts.size shouldBe 1
        contacts.node[0] shouldBe 0
        contacts.t[0] shouldBe 0.0
        joined.touchingLink(1, 0, box).shouldNotBeNull()
        collider(0, *nodes).touchingLink(1, 0, box).shouldBeNull()
    }

    @Test
    fun `feet that start a tick inside a link top go up onto it`() {
        val vine = collider(0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0)
        val feet = VINE_HALF_WIDTH - 0.05
        val move = vine.platformMove(AABB(0.7, feet, -0.3, 1.3, feet + 1.8, 0.3), standing = false).shouldNotBeNull()
        move.y() shouldBe (0.05 plusOrMinus 1e-9)
        move.x() shouldBe 0.0
        move.z() shouldBe 0.0
    }

    @Test
    fun `feet that start inside a knot top go up onto it`() {
        val vine = collider(JOINED_START, 0.0, 0.0, 0.0, 0.4, 0.0, 0.0)
        val feet = VINE_KNOT_HALF - 0.05
        val move = vine.platformMove(AABB(-0.3, feet, -0.3, 0.3, feet + 1.8, 0.3), standing = false).shouldNotBeNull()
        move.y() shouldBe (0.05 plusOrMinus 1e-9)
    }

    @Test
    fun `a box resting on a link top or under a link does not move`() {
        val vine = collider(0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0)
        vine.platformMove(AABB(0.7, VINE_HALF_WIDTH, -0.3, 1.3, VINE_HALF_WIDTH + 1.8, 0.3), standing = false).shouldBeNull()
        val head = -VINE_HALF_WIDTH + 0.05
        vine.platformMove(AABB(0.7, head - 1.8, -0.3, 1.3, head, 0.3), standing = false).shouldBeNull()
    }

    @Test
    fun `a steep vine through the box does not lift it`() {
        val vine = collider(0, 1.0, -1.0, 0.0, 1.0, 3.0, 0.0)
        vine.platformMove(AABB(0.7, 0.0, -0.3, 1.3, 1.8, 0.3), standing = false).shouldBeNull()
    }
}
