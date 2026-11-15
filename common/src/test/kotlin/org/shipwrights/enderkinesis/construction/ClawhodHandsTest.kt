package org.shipwrights.enderkinesis.construction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClawhodHandsTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun hand(vararg stacks: ItemStack): MutableList<ItemStack> =
        MutableList(HAND_SLOTS) { stacks.getOrElse(it) { ItemStack.EMPTY } }

    private fun floors(vararg pos: BlockPos) = LongArray(pos.size) { pos[it].asLong() }

    @Suppress("LongParameterList") // Mirrors chooseWorkSpot with test defaults.
    private fun choose(
        floors: LongArray, targets: LongArray, targetCount: Int, supply: Long, tried: LongArray = LongArray(0),
        triedCount: Int = 0, furthest: Boolean = true, classes: ByteArray = ByteArray(floors.size),
        cover: LongArray = LongArray(0),
    ): Long = chooseWorkSpot(
        floors, classes, floors.size, 0.6, points(targets), targetCount, if (supply == NO_POS) null else points(longArrayOf(supply)),
        points(cover), cover.size, tried, triedCount, furthest,
    )

    /** Block centres as x, y, z triples, as the worker fills them. */
    private fun points(cells: LongArray) = DoubleArray(cells.size * 3) {
        val c = cells[it / 3]
        when (it % 3) {
            0 -> BlockPos.getX(c) + 0.5
            1 -> BlockPos.getY(c) + 0.5
            else -> BlockPos.getZ(c) + 0.5
        }
    }

    private fun closer(floors: LongArray, n: Int, cell: Long, x: Double, y: Double, z: Double, tried: LongArray, triedCount: Int) =
        closerWorkSpot(
            floors, n, 0.6, BlockPos.getX(cell) + 0.5, BlockPos.getY(cell) + 0.5, BlockPos.getZ(cell) + 0.5, x, y, z, tried, triedCount,
        )

    @Test
    fun `reach counts from the gem to the block centre`() {
        withinReach(0.5, 0.5, 0.5, 8, 0, 0) shouldBe true
        withinReach(0.5, 0.5, 0.5, 9, 0, 0) shouldBe false
        withinReach(0.5, 0.5, 0.5, 5, 5, 5) shouldBe false
        withinReach(0.5, 0.5, 0.5, 5, 4, 4) shouldBe true
    }

    @Test
    fun `a flight takes at least one tick and rounds up`() {
        flightTicks(0.0) shouldBe 1
        flightTicks(5.0) shouldBe 10
        flightTicks(5.1) shouldBe 11
        shouldThrow<IllegalArgumentException> { flightTicks(-1.0) }
    }

    @Test
    fun `an empty hand takes two stacks of one block`() {
        handRoom(hand(), Blocks.STONE) shouldBe 128
        handBlock(hand()) shouldBe null
    }

    @Test
    fun `a hand that holds one block type takes no other`() {
        val h = hand(ItemStack(Items.STONE, 10))
        handBlock(h) shouldBe Blocks.STONE
        handRoom(h, Blocks.STONE) shouldBe 118
        handRoom(h, Blocks.DIRT) shouldBe 0
    }

    @Test
    fun `a regular item takes a slot but does not set the held block`() {
        val h = hand(ItemStack(Items.FLINT, 5))
        handBlock(h) shouldBe null
        handRoom(h, Blocks.STONE) shouldBe 64
    }

    @Test
    fun `a block with no item takes a stack's room while a slot is empty`() {
        handRoom(hand(), Blocks.POTTED_POPPY) shouldBe 64
        handRoom(hand(ItemStack(Items.FLINT), ItemStack(Items.STICK)), Blocks.POTTED_POPPY) shouldBe 0
    }

    @Test
    fun `take removes from both slots and counts what it took`() {
        val h = hand(ItemStack(Items.STONE, 64), ItemStack(Items.STONE, 10))
        handCount(h, Blocks.STONE) shouldBe 74
        handTake(h, Blocks.STONE, 20) shouldBe 20
        handCount(h, Blocks.STONE) shouldBe 54
        h[1].isEmpty shouldBe true
        handTake(h, Blocks.STONE, 100) shouldBe 54
        handHasItems(h) shouldBe false
    }

    @Test
    fun `work spot offsets give rings one to six, sides first`() {
        WORK_SPOT_OFFSETS.size shouldBe WORK_SPOT_MAX_RINGS * 8 * 2
        (WORK_SPOT_OFFSETS[0] to WORK_SPOT_OFFSETS[1]) shouldBe (0 to -1)
        (WORK_SPOT_OFFSETS[8] to WORK_SPOT_OFFSETS[9]) shouldBe (1 to -1)
    }

    @Test
    fun `a spot that reaches the cell and the supply wins over a further one that reaches the cell only`() {
        val cell = BlockPos(10, 64, 10).asLong()
        val supply = BlockPos(14, 64, 10).asLong()
        val far = BlockPos(7, 63, 10)
        val both = BlockPos(12, 63, 10)
        choose(floors(far, both), longArrayOf(cell), 1, supply, LongArray(0), 0, furthest = true) shouldBe
            both.asLong()
    }

    @Test
    fun `the furthest floor that reaches wins, and the closest after a failed move`() {
        val cell = BlockPos(10, 64, 10).asLong()
        val near = BlockPos(9, 63, 10)
        val far = BlockPos(7, 63, 10)
        choose(floors(near, far), longArrayOf(cell), 1, NO_POS, LongArray(0), 0, furthest = true) shouldBe far.asLong()
        choose(floors(near, far), longArrayOf(cell), 1, NO_POS, LongArray(0), 0, furthest = false) shouldBe near.asLong()
    }

    @Test
    fun `tried spots and spots out of the planned reach are skipped`() {
        val cell = BlockPos(10, 64, 10).asLong()
        val tried = BlockPos(9, 63, 10)
        val far = BlockPos(17, 63, 10)
        val tries = longArrayOf(tried.asLong())
        choose(floors(tried, far), longArrayOf(cell), 1, NO_POS, tries, 1, furthest = true) shouldBe NO_POS
    }

    @Test
    fun `a ground floor beats a finished-work top that reaches more cells`() {
        val cell = BlockPos(10, 64, 10).asLong()
        val ground = BlockPos(7, 63, 10)
        val top = BlockPos(12, 64, 10)
        val cover = longArrayOf(BlockPos(14, 64, 10).asLong(), BlockPos(16, 64, 10).asLong())
        val classes = byteArrayOf(SPOT_GROUND, SPOT_TOP)
        choose(floors(ground, top), longArrayOf(cell), 1, NO_POS, classes = classes, cover = cover) shouldBe ground.asLong()
        // A floor outside the plan ranks with the ground: the cover vote decides.
        val outside = byteArrayOf(SPOT_GROUND, SPOT_OUTSIDE)
        choose(floors(ground, top), longArrayOf(cell), 1, NO_POS, classes = outside, cover = cover) shouldBe top.asLong()
    }

    @Test
    fun `the floor that reaches more pending cells beats the furthest one`() {
        val cell = BlockPos(10, 64, 10).asLong()
        val near = BlockPos(11, 63, 10)
        val far = BlockPos(5, 63, 10)
        val cover = longArrayOf(BlockPos(16, 64, 10).asLong(), BlockPos(17, 64, 10).asLong())
        choose(floors(near, far), longArrayOf(cell), 1, NO_POS, cover = cover) shouldBe near.asLong()
        coverVotes(11.5, 64.6, 10.5, points(longArrayOf(cell)), 1, points(cover), 2) shouldBe 2
        coverVotes(5.5, 64.6, 10.5, points(longArrayOf(cell)), 1, points(cover), 2) shouldBe 0
    }

    @Test
    fun `the supply in reach outranks the cover vote`() {
        val cell = BlockPos(10, 64, 10).asLong()
        val supply = BlockPos(5, 64, 10).asLong()
        val bySupply = BlockPos(8, 63, 10)
        val byCells = BlockPos(13, 63, 10)
        val cover = longArrayOf(BlockPos(17, 64, 10).asLong(), BlockPos(18, 64, 10).asLong())
        choose(floors(byCells, bySupply), longArrayOf(cell), 1, supply, cover = cover) shouldBe bySupply.asLong()
    }

    @Test
    fun `a far cell stays noted until a find`() {
        val pick = WorkerPick(BuildPattern.SCAN)
        pick.noteFar(1L, Blocks.STONE.defaultBlockState(), build = true)
        pick.noteFar(2L, Blocks.DIRT.defaultBlockState(), build = false)
        pick.farCell shouldBe 1L
        pick.farBuild shouldBe true
        pick.found()
        pick.farCell shouldBe NO_POS
    }

    @Test
    fun `bare-hand break time follows the vanilla formula`() {
        bareHandBreakTicks(1.5f, needsTool = true) shouldBe 150
        bareHandBreakTicks(0.5f, needsTool = false) shouldBe 15
        bareHandBreakTicks(0f, needsTool = false) shouldBe 1
        bareHandBreakTicks(-1f, needsTool = false) shouldBe -1
        bareHandBreakTicks(Blocks.DIRT.defaultBlockState().getDestroySpeed(null, BlockPos.ZERO), false) shouldBe 15
    }

    @Test
    fun `crack stages run 0 to 9 over the break`() {
        punchStage(0, 15) shouldBe 0
        punchStage(8, 15) shouldBe 5
        punchStage(15, 15) shouldBe 9
        shouldThrow<IllegalArgumentException> { punchStage(1, 0) }
    }

    @Test
    fun `a climb step takes the floor that brings the gem nearest a high cell`() {
        val cell = BlockPos(10, 80, 10).asLong()
        val ground = BlockPos(11, 63, 10)
        val wallTop = BlockPos(11, 70, 10)
        val spot = closer(floors(ground, wallTop), 2, cell, 5.5, 64.6, 10.5, LongArray(0), 0)
        spot shouldBe wallTop.asLong()
    }

    @Test
    fun `no climb step when no floor is at least one block nearer`() {
        val cell = BlockPos(10, 80, 10).asLong()
        val ground = BlockPos(11, 63, 10)
        closer(floors(ground), 1, cell, 11.5, 64.6, 10.5, LongArray(0), 0) shouldBe NO_POS
        val wallTop = BlockPos(11, 70, 10)
        closer(floors(wallTop), 1, cell, 5.5, 64.6, 10.5, longArrayOf(wallTop.asLong()), 1) shouldBe NO_POS
    }

    @Test
    fun `each hand has its own stable worker id`() {
        val id = java.util.UUID.randomUUID()
        val a = handWorkerId(id, 0)
        val b = handWorkerId(id, 1)
        (a == b) shouldBe false
        (a == id) shouldBe false
        handWorkerId(id, 0) shouldBe a
        shouldThrow<IllegalArgumentException> { handWorkerId(id, 2) }
    }

    @Test
    fun `a target point off the block grid is reached by its exact position`() {
        // A ship cell whose centre lands at x 17.2 after the ship transform: inside the reach, though block 17's centre is not.
        plannedReachTo(10.5, 64.6, 10.5, 17.2, 64.5, 10.5) shouldBe true
        plannedReach(10.5, 64.6, 10.5, BlockPos(17, 64, 10).asLong()) shouldBe false
    }

    @Test
    fun `a scaled reach searches more rings, within the bound`() {
        workSpotRings(CLAWHOD_REACH, WORK_SPOT_MARGIN) shouldBe WORK_SPOT_RINGS
        workSpotRings(CLAWHOD_REACH * 2, WORK_SPOT_MARGIN) shouldBe 14.coerceAtMost(WORK_SPOT_MAX_RINGS)
        workSpotRings(CLAWHOD_REACH / 4, WORK_SPOT_MARGIN) shouldBe 1
    }

    @Test
    fun `a carrier at twice the scale reaches twice as far`() {
        plannedReachTo(10.5, 64.6, 10.5, 22.5, 64.5, 10.5) shouldBe false
        plannedReachTo(10.5, 64.6, 10.5, 22.5, 64.5, 10.5, reach = CLAWHOD_REACH * 2) shouldBe true
    }

    @Test
    fun `planned reach allows the stop margin sideways only`() {
        // Gem 1 block to the side: 2.25 sideways with the margin, so 7.8 down is out and 7.5 down is in.
        plannedReach(10.5, 71.3, 10.5, BlockPos(11, 63, 10).asLong()) shouldBe false
        plannedReach(10.5, 71.0, 10.5, BlockPos(11, 63, 10).asLong()) shouldBe true
        // Straight above: the margin still counts, so 8.0 down is out and 7.0 down is in.
        plannedReach(11.5, 71.5, 10.5, BlockPos(11, 63, 10).asLong()) shouldBe false
        plannedReach(11.5, 70.5, 10.5, BlockPos(11, 63, 10).asLong()) shouldBe true
    }

    @Test
    fun `a floor that reaches both hands' cells beats a further one that reaches only the older`() {
        val older = BlockPos(10, 64, 10).asLong()
        val newer = BlockPos(16, 64, 10).asLong()
        val both = BlockPos(11, 63, 10)
        val onlyOlder = BlockPos(7, 63, 10)
        val targets = longArrayOf(older, newer)
        choose(floors(onlyOlder, both), targets, 2, NO_POS, LongArray(0), 0, furthest = true) shouldBe both.asLong()
        // No floor reaches the newer cell: the older hand's cell decides.
        choose(floors(onlyOlder), targets, 2, NO_POS, LongArray(0), 0, furthest = true) shouldBe onlyOlder.asLong()
    }

    @Test
    fun `a flight eases in and out and lifts on a low arc`() {
        easeInOut(0.0) shouldBe 0.0
        easeInOut(1.0) shouldBe 1.0
        easeInOut(0.5) shouldBe (0.5 plusOrMinus 1e-9)
        (easeInOut(0.1) < 0.1) shouldBe true
        (easeInOut(0.9) > 0.9) shouldBe true
        legLift(0.0, 8.0) shouldBe 0.0
        legLift(0.5, 8.0) shouldBe (ARC_LIFT plusOrMinus 1e-9)
        legLift(0.5, ARC_FULL_LENGTH / 2) shouldBe (ARC_LIFT / 2 plusOrMinus 1e-9)
    }

    @Test
    fun `a punch winds up, jabs in and comes back to rest`() {
        punchBack(0.0) shouldBe (PUNCH_REST plusOrMinus 1e-9)
        punchBack(PUNCH_WIND_END / 2) shouldBe (PUNCH_REST + PUNCH_WIND plusOrMinus 1e-9)
        punchBack(PUNCH_JAB_END) shouldBe (PUNCH_REST - PUNCH_DEPTH plusOrMinus 1e-9)
        punchBack(1.0) shouldBe (PUNCH_REST plusOrMinus 1e-9)
    }

    @Test
    fun `a place presses down and comes back`() {
        placeDip(0.0) shouldBe 0.0
        placeDip(0.5) shouldBe (PLACE_DIP plusOrMinus 1e-9)
        placeDip(1.0) shouldBe 0.0
    }
}
