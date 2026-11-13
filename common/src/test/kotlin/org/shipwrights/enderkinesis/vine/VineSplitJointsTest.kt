package org.shipwrights.enderkinesis.vine

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import org.joml.Vector3d
import org.junit.jupiter.api.Test
import org.valkyrienskies.core.api.chains.ChainId
import org.valkyrienskies.core.api.particles.ParticleId

class VineSplitJointsTest {

    private fun block(x: Int) = WoggakVineAnchor(VinePoint(BlockPos(x, 64, 0), Vector3d(x + 0.5, 64.5, 0.5), null))

    private fun joined(vine: Long, node: Int) = WoggakVineAnchor(VinePoint(BlockPos(0, 70, 0), Vector3d(), null, VineJoint(vine, node, 0L)))

    private fun free() = WoggakVineAnchor(null)

    private val WoggakVineAnchor.joint: VineJoint get() = point.shouldNotBeNull().joint.shouldNotBeNull()

    /** Vine 1 (nodes 0..20, the start on a block) is cut at link 10: piece 11 holds nodes 0..10, piece 12 nodes 11..20. */
    private class Cut(val anchoredPiece: WoggakVine, val freePiece: WoggakVine)

    private fun cut(data: WoggakVineData, grips: List<VineGrip> = emptyList(), keepFreePiece: Boolean = true): Cut {
        val old = data.vine(1L).shouldNotBeNull()
        val anchoredPiece = WoggakVine(11L, ChainId(110L), WoggakVineAnchor(old.start.point), free())
        val freePiece = WoggakVine(12L, ChainId(120L), free(), free())
        data.add(anchoredPiece)
        val pieces = mutableListOf(SplitPiece(0..10, anchoredPiece))
        if (keepFreePiece) {
            data.add(freePiece)
            pieces += SplitPiece(11..20, freePiece)
        }
        data.repointSplit(1L, old, pieces, grips)
        data.remove(old)
        return Cut(anchoredPiece, freePiece)
    }

    private fun weave(): WoggakVineData = WoggakVineData().apply {
        add(WoggakVine(1L, ChainId(10L), block(0), free()))
        add(WoggakVine(2L, ChainId(20L), block(8), free()))
    }

    @Test
    fun `a joint moves to the piece that holds its node, with the node counted from the piece start`() {
        val data = weave()
        val low = WoggakVine(3L, ChainId(30L), joined(1L, 4), free()).also(data::add)
        val high = WoggakVine(4L, ChainId(40L), free(), joined(1L, 15)).also(data::add)
        val edge = WoggakVine(5L, ChainId(50L), joined(1L, 11), joined(1L, 10)).also(data::add)
        cut(data)
        low.start.joint.vine shouldBe 11L
        low.start.joint.node shouldBe 4
        high.end.joint.vine shouldBe 12L
        high.end.joint.node shouldBe 4
        edge.start.joint.vine shouldBe 12L
        edge.start.joint.node shouldBe 0
        edge.end.joint.vine shouldBe 11L
        edge.end.joint.node shouldBe 10
    }

    @Test
    fun `after a cut, links on the anchored side live and links that reach only the free piece fade`() {
        val data = weave()
        // Each cross-link ties vine 1 to vine 2, which a block holds.
        val anchoredSide = WoggakVine(3L, ChainId(30L), joined(1L, 5), joined(2L, 3)).also(data::add)
        val onlyFree = WoggakVine(4L, ChainId(40L), joined(1L, 15), free()).also(data::add)
        val freeGroup = WoggakVine(5L, ChainId(50L), joined(1L, 17), free()).also(data::add)
        val onGroup = WoggakVine(6L, ChainId(60L), joined(5L, 2), joined(1L, 18)).also(data::add)
        val cut = cut(data)
        data.startFadeOfUnanchored(5L) { false } shouldBe true
        cut.anchoredPiece.fadeStart shouldBe -1L
        anchoredSide.fadeStart shouldBe -1L
        cut.freePiece.fadeStart shouldBe 5L
        onlyFree.fadeStart shouldBe 5L
        freeGroup.fadeStart shouldBe 5L
        onGroup.fadeStart shouldBe 5L
    }

    @Test
    fun `a link across the cut keeps the free piece and its links alive`() {
        val data = weave()
        val bridge = WoggakVine(3L, ChainId(30L), joined(1L, 4), joined(1L, 16)).also(data::add)
        val onFree = WoggakVine(4L, ChainId(40L), joined(1L, 19), free()).also(data::add)
        val cut = cut(data)
        data.startFadeOfUnanchored(5L) { false } shouldBe false
        bridge.fadeStart shouldBe -1L
        cut.freePiece.fadeStart shouldBe -1L
        onFree.fadeStart shouldBe -1L
    }

    @Test
    fun `a joint on a node that no piece kept is lost, and its vine fades with no other anchor`() {
        val data = weave()
        val gone = WoggakVine(3L, ChainId(30L), joined(1L, 15), free()).also(data::add)
        gone.start.pair = JointPair(ParticleId(7L), ParticleId(8L))
        val kept = WoggakVine(4L, ChainId(40L), joined(1L, 6), free()).also(data::add)
        kept.start.pair = JointPair(ParticleId(9L), ParticleId(10L))
        cut(data, keepFreePiece = false)
        gone.start.released shouldBe true
        gone.start.pair shouldBe null
        kept.start.released shouldBe false
        // The new chain has new node ids, so the pair is made again.
        kept.start.pair shouldBe null
        data.startFadeOfUnanchored(5L) { false } shouldBe true
        gone.fadeStart shouldBe 5L
        kept.fadeStart shouldBe -1L
    }

    @Test
    fun `hull grips move to the piece that holds their node`() {
        val data = weave()
        val grips = listOf(VineGrip(3, 42L, Vector3d(1.0, 2.0, 3.0)), VineGrip(14, 42L, Vector3d(4.0, 5.0, 6.0)))
        val cut = cut(data, grips)
        cut.anchoredPiece.grips.map { it.node } shouldBe listOf(3)
        cut.freePiece.grips.map { it.node } shouldBe listOf(3)
        cut.freePiece.grips.single().local.x() shouldBe 4.0
    }

    @Test
    fun `a joint that the split vine shares with its piece moves once`() {
        val data = WoggakVineData()
        // Vine 1's own end is tied to its node 18: the free piece keeps that end, and the shared joint moves once.
        val old = WoggakVine(1L, ChainId(10L), block(0), joined(1L, 18)).also(data::add)
        val anchoredPiece = WoggakVine(11L, ChainId(110L), WoggakVineAnchor(old.start.point), free()).also(data::add)
        val freePiece = WoggakVine(12L, ChainId(120L), free(), WoggakVineAnchor(old.end.point)).also(data::add)
        data.repointSplit(1L, old, listOf(SplitPiece(0..10, anchoredPiece), SplitPiece(11..20, freePiece)), emptyList())
        freePiece.end.joint.vine shouldBe 12L
        freePiece.end.joint.node shouldBe 7
    }
}
