package org.shipwrights.enderkinesis.construction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CarrierRoomTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private val queue = LongArrayFIFOQueue()
    private val seen = LongOpenHashSet()

    /** A 5 x 5 plan footprint (0..4) with walls on its edge, three high (y 0..2), and a roof at y 3 when [roof]. */
    private class Hut(val roof: Boolean, val door: Boolean, val extra: Long = Long.MIN_VALUE) {
        fun solid(x: Int, y: Int, z: Int): Boolean {
            if (y < 0) return true
            if (BlockPos.asLong(x, y, z) == extra) return true
            val inPlan = x in 0..4 && z in 0..4
            if (!inPlan) return false
            if (roof && y == 3) return true
            val edge = x == 0 || x == 4 || z == 0 || z == 4
            if (!edge || y > 2) return false
            return !(door && x == 2 && z == 0 && y <= 1)
        }

        /** A body two cells tall. */
        val room = BodyRoom { x, y, z -> !solid(x, y, z) && !solid(x, y + 1, z) }
        val out = OutOfPlan { x, y, z -> x !in 0..4 || z !in 0..4 || y > 3 }
    }

    private fun run(hut: Hut, cap: Int = 1024): WayOut = wayOut(2, 0, 2, -1, 4, hut.room, hut.out, queue, seen, cap)

    @Test
    fun `a carrier in a roofed hut with a door has a way out`() {
        run(Hut(roof = true, door = true)) shouldBe WayOut.OUT
    }

    @Test
    fun `the block that fills the door walls the carrier in`() {
        run(Hut(roof = true, door = true, extra = BlockPos.asLong(2, 1, 0))) shouldBe WayOut.SHUT
    }

    @Test
    fun `an open top lets the carrier climb out`() {
        run(Hut(roof = false, door = false)) shouldBe WayOut.OUT
    }

    @Test
    fun `a closed hut is shut and the search keeps every reached spot`() {
        run(Hut(roof = true, door = false)) shouldBe WayOut.SHUT
        seen.contains(BlockPos.asLong(1, 0, 1)) shouldBe true
        seen.contains(BlockPos.asLong(2, 1, 2)) shouldBe true
    }

    @Test
    fun `a search past its cap is unknown`() {
        run(Hut(roof = true, door = false), cap = 2) shouldBe WayOut.UNKNOWN
    }

    @Test
    fun `an empty layer range is refused`() {
        shouldThrow<IllegalArgumentException> { wayOut(0, 0, 0, 3, 2, Hut(true, true).room, Hut(true, true).out, queue, seen, 8) }
    }

    private fun claimsClash(a: Long, faceA: Int, reachA: Double, b: Long, faceB: Int, reachB: Double, height: Double = 2.0): Boolean {
        val boxA = DoubleArray(BOX_VALUES)
        val boxB = DoubleArray(BOX_VALUES)
        claimBodyBox(a, faceA, reachA, height, boxA)
        claimBodyBox(b, faceB, reachB, height, boxB)
        return standBoxesClash(boxA, boxB)
    }

    @Test
    fun `floor carriers clash when their bodies and leg cells would meet`() {
        val a = BlockPos.asLong(0, 64, 0)
        claimsClash(a, FLOOR_FACE, 0.0, BlockPos.asLong(2, 64, 0), FLOOR_FACE, 0.0) shouldBe true
        claimsClash(a, FLOOR_FACE, 0.0, BlockPos.asLong(3, 64, 0), FLOOR_FACE, 0.0) shouldBe false
        claimsClash(a, FLOOR_FACE, 1.0, BlockPos.asLong(3, 64, 3), FLOOR_FACE, 1.0) shouldBe true
        // A body 2 high leaves the gap clear to a body 6 layers up.
        claimsClash(a, FLOOR_FACE, 0.0, BlockPos.asLong(1, 70, 0), FLOOR_FACE, 0.0) shouldBe false
    }

    @Test
    fun `a wall claim covers its body out from the wall, not the tallest body`() {
        val east = Direction.EAST.get3DDataValue()
        // Body cell 5, 65, 0 on the east wall: its 2-high body lies from x 4 to x 6.
        val box = DoubleArray(BOX_VALUES)
        claimBodyBox(BlockPos.asLong(5, 65, 0), east, 0.0, 2.0, box)
        box.toList() shouldBe listOf(4.0, 65.0, 0.0, 6.0, 66.0, 1.0)
        claimsClash(BlockPos.asLong(5, 65, 0), east, 0.0, BlockPos.asLong(0, 64, 0), FLOOR_FACE, 0.0) shouldBe false
        claimsClash(BlockPos.asLong(5, 65, 0), east, 0.0, BlockPos.asLong(3, 64, 0), FLOOR_FACE, 0.0) shouldBe true
    }

    @Test
    fun `a failed spot stays blocked for its time, then frees`() {
        val list = SpotBlacklist(2, 600L)
        val a = BlockPos(1, 2, 3).asLong()
        list.add(a, 100L)
        list.blocks(a, 100L) shouldBe true
        list.blocks(a, 699L) shouldBe true
        list.blocks(a, 700L) shouldBe false
        list.blocks(BlockPos(1, 2, 4).asLong(), 100L) shouldBe false
    }

    @Test
    fun `a spot that fails again starts its time again, and a full list drops the oldest`() {
        val list = SpotBlacklist(2, 600L)
        val a = BlockPos(1, 2, 3).asLong()
        val b = BlockPos(4, 5, 6).asLong()
        val c = BlockPos(7, 8, 9).asLong()
        list.add(a, 0L)
        list.add(a, 500L)
        list.blocks(a, 1000L) shouldBe true
        list.add(b, 500L)
        list.add(c, 500L)
        list.blocks(a, 600L) shouldBe false
        list.blocks(b, 600L) shouldBe true
        list.blocks(c, 600L) shouldBe true
        list.clear()
        list.blocks(c, 600L) shouldBe false
        shouldThrow<IllegalArgumentException> { SpotBlacklist(0, 600L) }
    }

    @Test
    fun `body boxes from two frames clash by their world gap`() {
        // A deck body moved to the world starts 2.1 past a world body: clear; 1.9 past it: a clash.
        val world = doubleArrayOf(0.0, 65.0, 0.0, 1.0, 67.0, 1.0)
        standBoxesClash(world, doubleArrayOf(3.1, 65.2, 0.0, 4.1, 67.2, 1.0)) shouldBe false
        standBoxesClash(world, doubleArrayOf(2.9, 65.2, 0.0, 3.9, 67.2, 1.0)) shouldBe true
        standBoxesClash(world, doubleArrayOf(0.0, 69.0, 0.0, 1.0, 71.0, 1.0)) shouldBe false
    }
}
