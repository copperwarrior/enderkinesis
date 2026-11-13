package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.ChunkPos
import org.joml.Vector3d
import org.junit.jupiter.api.Test
import org.valkyrienskies.core.api.chains.ChainId

class VineChunksTest {

    private fun blockAnchor(x: Int, y: Int, z: Int) =
        WoggakVineAnchor(VinePoint(BlockPos(x, y, z), Vector3d(x + 0.5, y + 0.5, z + 0.5), null))

    private fun shipAnchor(x: Int, z: Int) = WoggakVineAnchor(VinePoint(BlockPos(x, 64, z), Vector3d(x + 0.5, 64.5, z + 0.5), 42L))

    private fun jointAnchor(vine: Long, home: Long) =
        WoggakVineAnchor(VinePoint(BlockPos(0, 64, 0), Vector3d(), null, VineJoint(vine, 3, home)))

    private fun free() = WoggakVineAnchor(null)

    @Test
    fun `a record keeps its nodes, anchors, joint and flags over a save and load`() {
        val positions = doubleArrayOf(0.1, 70.0, -3.5, 0.5, 69.6, -3.5, 0.9, 69.4, -3.25)
        val record = VineRecord(
            id = 123456789L, start = blockAnchor(17, 70, -4), end = jointAnchor(99L, ChunkPos.asLong(2, -1)),
            fadeStart = 400L, fromBreak = true, dropsOnFade = false, restLength = 0.8,
            positions = positions, brokenLinks = intArrayOf(1), chain = 77L,
        )
        val loaded = VineRecord.load(record.save()).shouldNotBeNull()

        loaded.id shouldBe 123456789L
        loaded.fadeStart shouldBe 400L
        loaded.fromBreak shouldBe true
        loaded.dropsOnFade shouldBe false
        loaded.restLength shouldBe 0.8
        loaded.chain shouldBe 77L
        loaded.positions.toList() shouldBe positions.toList()
        loaded.brokenLinks.toList() shouldBe listOf(1)
        loaded.start.point.shouldNotBeNull().block shouldBe BlockPos(17, 70, -4)
        loaded.start.released shouldBe false
        val joint = loaded.end.point.shouldNotBeNull().joint.shouldNotBeNull()
        joint.vine shouldBe 99L
        joint.node shouldBe 3
        joint.home shouldBe ChunkPos.asLong(2, -1)
    }

    @Test
    fun `a released end stays released over a save and load`() {
        val start = blockAnchor(0, 64, 0).also { it.released = true }
        val record = VineRecord(1L, start, free(), -1L, false, true, 1.0, DoubleArray(6) { it.toDouble() }, IntArray(0), 0L)
        val loaded = VineRecord.load(record.save()).shouldNotBeNull()
        loaded.start.released shouldBe true
        loaded.end.point.shouldBeNull()
        loaded.end.released shouldBe true
    }

    @Test
    fun `a record with too few or bad nodes does not load`() {
        val good = VineRecord(1L, free(), free(), -1L, false, true, 1.0, DoubleArray(6), IntArray(0), 0L).save()
        VineRecord.load(good.copy().also { it.putLongArray("nodes", LongArray(3)) }).shouldBeNull()
        VineRecord.load(good.copy().also { it.putLongArray("nodes", LongArray(7)) }).shouldBeNull()
        VineRecord.load(good.copy().also { it.putLongArray("nodes", LongArray(6) { Double.NaN.toRawBits() }) }).shouldBeNull()
        VineRecord.load(good.copy().also { it.putDouble("rest", 0.0) }).shouldBeNull()
        VineRecord.load(CompoundTag()).shouldBeNull()
    }

    @Test
    fun `the home is the chunk of the start block when the start is on a world block`() {
        fixedBlockHome(blockAnchor(-1, 64, 33), blockAnchor(100, 64, 100)) shouldBe ChunkPos.asLong(-1, 2)
    }

    @Test
    fun `the home is the end block when the start is free, joined, released or on a ship`() {
        val end = blockAnchor(40, 64, -17)
        val expected = ChunkPos.asLong(2, -2)
        fixedBlockHome(free(), end) shouldBe expected
        fixedBlockHome(jointAnchor(5L, 0L), end) shouldBe expected
        fixedBlockHome(shipAnchor(0, 0), end) shouldBe expected
        fixedBlockHome(blockAnchor(0, 64, 0).also { it.released = true }, end) shouldBe expected
    }

    @Test
    fun `a vine with no world block anchor has no fixed home`() {
        fixedBlockHome(free(), jointAnchor(5L, 0L)).shouldBeNull()
        fixedBlockHome(shipAnchor(0, 0), shipAnchor(16, 16)).shouldBeNull()
    }

    @Test
    fun `the centroid home is the chunk under the centre of the nodes`() {
        val positions = doubleArrayOf(14.0, 70.0, -1.0, 18.0, 60.0, -1.0, 0.0, 0.0, 0.0)
        centroidHome(positions, 2) shouldBe ChunkPos.asLong(1, -1)
        centroidHome(positions, 3) shouldBe ChunkPos.asLong(0, -1)
    }

    @Test
    fun `the level data saves only ship-held vines, and lists the chains of the others`() {
        val data = WoggakVineData()
        data.add(WoggakVine(1L, ChainId(10L), shipAnchor(0, 0), blockAnchor(3, 64, 3)))
        data.add(WoggakVine(2L, ChainId(20L), blockAnchor(0, 64, 0), free()))
        val loaded = WoggakVineData.load(data.save(CompoundTag()))
        loaded.vines.map { it.id } shouldBe listOf(1L)
        loaded.vines[0].chain shouldBe ChainId(10L)
        loaded.strayChains.toList() shouldBe listOf(20L)
    }

    @Test
    fun `a joint to a vine that is not loaded keeps the joined vine from fading while it waits`() {
        val data = WoggakVineData()
        val joined = WoggakVine(1L, ChainId(10L), jointAnchor(99L, 0L), free())
        data.add(joined)
        data.startFadeOfUnanchored(5L) { true } shouldBe false
        joined.fadeStart shouldBe -1L
        data.startFadeOfUnanchored(5L) { false } shouldBe true
        joined.fadeStart shouldBe 5L
    }

    @Test
    fun `a waiting joint also holds the vines joined to that vine`() {
        val data = WoggakVineData()
        val waiting = WoggakVine(1L, ChainId(10L), jointAnchor(99L, 0L), free())
        val chained = WoggakVine(2L, ChainId(20L), jointAnchor(1L, 0L), free())
        data.add(waiting)
        data.add(chained)
        data.startFadeOfUnanchored(5L) { it.vine == 99L } shouldBe false
        chained.fadeStart shouldBe -1L
    }

    @Test
    fun `a rehome moves the vine between chunks and updates the joints to it`() {
        val data = WoggakVineData()
        val target = WoggakVine(1L, ChainId(10L), free(), free())
        val joiner = WoggakVine(2L, ChainId(20L), jointAnchor(1L, 0L), free())
        data.add(target)
        data.add(joiner)
        val chunk = ChunkPos.asLong(3, 4)
        data.rehome(target, chunk)
        data.inChunk(chunk) shouldBe listOf(target)
        data.inChunk(0L) shouldBe listOf(joiner)
        joiner.start.point.shouldNotBeNull().joint.shouldNotBeNull().home shouldBe chunk
        data.remove(target)
        data.inChunk(chunk) shouldBe emptyList()
        data.vine(1L).shouldBeNull()
    }
}
