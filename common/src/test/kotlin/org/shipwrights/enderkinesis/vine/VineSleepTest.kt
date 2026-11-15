package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import org.joml.Vector3d
import org.junit.jupiter.api.Test
import org.valkyrienskies.core.api.chains.ChainId

class VineSleepTest {
    private fun anchor() = WoggakVineAnchor(VinePoint(BlockPos(0, 80, 0), Vector3d(0.5, 80.0, 0.5), null))

    private fun record(natural: Boolean = true) = VineRecord(
        42L, anchor(), WoggakVineAnchor(null), -1L, false, true, 12.0,
        strandNodes(0.5, 80.0, 0.5, 12.0, WORLDGEN_VINE_SPACING), IntArray(0), 0L,
        spacing = WORLDGEN_VINE_SPACING, natural = natural,
    )

    @Test
    fun `the wake list keeps the nearest chunks, near to far`() {
        val sleep = VineSleep()
        val offers = VINE_WAKE_CHUNKS_PER_TICK * 2
        for (i in 0 until offers) sleep.offerWake(i.toLong(), (offers - i).toDouble())
        sleep.wakeCount shouldBe VINE_WAKE_CHUNKS_PER_TICK
        for (i in 0 until sleep.wakeCount) sleep.wakeDistances[i] shouldBe (i + 1).toDouble()
        sleep.wakeChunks[0] shouldBe offers - 1L
    }

    @Test
    fun `distance uses the whole vine box, with its long tail and height`() {
        val bounds = VineBounds.of(doubleArrayOf(0.0, 120.0, 0.0, 100.0, 40.0, 0.0))
        bounds.distanceSquared(VineBounds(103.0, 36.0, 0.0, 103.0, 36.0, 0.0)) shouldBe 25.0
        bounds.distanceSquared(VineBounds(50.0, 70.0, -1.0, 51.0, 80.0, 1.0)) shouldBe 0.0
        bounds.distanceSquared(VineBounds(0.0, 200.0, 0.0, 0.0, 200.0, 0.0)) shouldBe 6400.0
    }

    @Test
    fun `the nearest observer counts, and none means out of reach`() {
        val sleep = VineSleep()
        val box = VineBounds.of(doubleArrayOf(0.0, 64.0, 0.0, 0.0, 70.0, 0.0))
        sleep.nearestSquared(box) shouldBe Double.POSITIVE_INFINITY
        sleep.addObserver(100.0, 64.0, 0.0, 100.0, 64.0, 0.0)
        sleep.addObserver(0.0, 64.0, 30.0, 0.0, 64.0, 30.0)
        // A ship box that reaches over the vine.
        sleep.addObserver(-5.0, 60.0, -5.0, 5.0, 66.0, 5.0)
        sleep.nearestSquared(box) shouldBe 0.0
        sleep.clearObservers()
        sleep.addObserver(0.0, 64.0, 30.0, 0.0, 64.0, 30.0)
        sleep.nearestSquared(box) shouldBe 900.0
    }

    @Test
    fun `the wake budget caps a batch and lets one oversized chunk through`() {
        val budget = VineBuildBudget(VINE_WAKE_NODES_PER_TICK)
        budget.take(128) shouldBe true
        budget.take(128) shouldBe true
        budget.take(2) shouldBe false
        budget.reset()
        budget.take(700) shouldBe true
        budget.take(2) shouldBe false
    }

    @Test
    fun `a wake counts the nodes VS lays out at the record spacing`() {
        wakeNodes(record()) shouldBe 16
    }

    @Test
    fun `a worldgen record is natural and keeps its spacing and shape over a save`() {
        val nodes = strandNodes(0.5, 80.0, 0.5, 12.0, WORLDGEN_VINE_SPACING)
        val made = RealVine(anchor().point!!, null, nodes, 12.0).toRecord(13L)
        made.natural shouldBe true
        made.spacing shouldBe WORLDGEN_VINE_SPACING
        val saved = VineRecord.load(made.save()).shouldNotBeNull()
        saved.natural shouldBe true
        saved.spacing shouldBe WORLDGEN_VINE_SPACING
        saved.positions.toList() shouldBe nodes.toList()
        VineRecord.load(record(natural = false).save())?.natural shouldBe false
    }

    @Test
    fun `a saved live vine keeps its natural flag and spacing`() {
        val data = WoggakVineData()
        val vine = WoggakVine(1L, ChainId(2L), anchor(), WoggakVineAnchor(null), spacing = WORLDGEN_VINE_SPACING)
        vine.natural = true
        // Only ship-held vines are saved in the level data; the flag still goes with them.
        val shipAnchor = WoggakVineAnchor(VinePoint(BlockPos(0, 80, 0), Vector3d(0.5, 80.0, 0.5), 5L))
        val held = WoggakVine(3L, ChainId(4L), shipAnchor, WoggakVineAnchor(null), spacing = WORLDGEN_VINE_SPACING)
        held.natural = true
        data.add(vine)
        data.add(held)
        val loaded = WoggakVineData.load(data.save(CompoundTag()), now = 1000L)
        loaded.vine(1L) shouldBe null
        loaded.vine(3L).shouldNotBeNull().natural shouldBe true
        loaded.vine(3L)!!.spacing shouldBe WORLDGEN_VINE_SPACING
    }

    @Test
    fun `use clears the natural flag for good, and only a natural vine may sleep`() {
        val data = WoggakVineData()
        val vine = WoggakVine(1L, ChainId(2L), anchor(), WoggakVineAnchor(null), spacing = WORLDGEN_VINE_SPACING)
        vine.natural = true
        data.add(vine)
        data.sleep.scanVines(data)
        data.sleep.canSleep(data, vine) shouldBe true
        data.touch(vine)
        vine.natural shouldBe false
        vine.spacing shouldBe WORLDGEN_VINE_SPACING
        data.sleep.canSleep(data, vine) shouldBe false
    }

    @Test
    fun `respaced links and moved nodes follow the vine's spacing`() {
        respacedLinks(4.0, WORLDGEN_VINE_SPACING) shouldBe 5
        extendedNode(5, 5, 4.0, 0.0, false, WORLDGEN_VINE_SPACING) shouldBe 5
    }
}
