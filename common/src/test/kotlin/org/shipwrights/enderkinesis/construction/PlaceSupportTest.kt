package org.shipwrights.enderkinesis.construction

import io.kotest.matchers.shouldBe
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlaceSupportTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private val queue = LongArrayFIFOQueue()
    private val seen = LongOpenHashSet()

    private fun p(x: Int, y: Int, z: Int) = BlockPos.asLong(x, y, z)

    /** Ground is y < 0; [built] are placed blocks; [plan] are plan cells still to place. */
    private fun hold(start: Long, plan: Set<Long>, built: Set<Long> = emptySet(), cap: Int = SUPPORT_SEARCH_CAP): PlaceHold {
        val solid = CellTest { BlockPos.getY(it) < 0 || it in built }
        val pending = CellTest { it in plan && it !in built }
        return placeSupport(start, solid, pending, queue, seen, cap)
    }

    @Test
    fun `a cell on the ground or beside a placed block holds`() {
        hold(p(0, 0, 0), setOf(p(0, 0, 0))) shouldBe PlaceHold.WORLD
        hold(p(1, 1, 0), setOf(p(1, 1, 0)), built = setOf(p(0, 1, 0))) shouldBe PlaceHold.WORLD
    }

    @Test
    fun `a wall cell whose base is not placed yet waits for it`() {
        val wall = setOf(p(0, 0, 0), p(0, 1, 0), p(0, 2, 0))
        hold(p(0, 2, 0), wall) shouldBe PlaceHold.LATER
    }

    @Test
    fun `a plan part that touches nothing goes in unsupported`() {
        val island = setOf(p(0, 5, 0), p(1, 5, 0), p(2, 5, 0))
        hold(p(1, 5, 0), island) shouldBe PlaceHold.FLOATING
    }

    @Test
    fun `a part bigger than the cap is taken to reach the world`() {
        val island = setOf(p(0, 5, 0), p(1, 5, 0), p(2, 5, 0))
        hold(p(0, 5, 0), island, cap = 1) shouldBe PlaceHold.LATER
    }
}
