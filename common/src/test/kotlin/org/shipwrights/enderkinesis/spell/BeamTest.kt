package org.shipwrights.enderkinesis.spell

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Test
import org.shipwrights.enderkinesis.item.BeamSegment
import org.shipwrights.enderkinesis.item.BeamTrace
import org.shipwrights.enderkinesis.item.SunderingBeamTrace
import org.shipwrights.enderkinesis.spell.Element.BLOOD
import org.shipwrights.enderkinesis.spell.Element.CHAOS
import org.shipwrights.enderkinesis.spell.Element.COLD
import org.shipwrights.enderkinesis.spell.Element.GRAVITY
import org.shipwrights.enderkinesis.spell.Element.GROWTH
import org.shipwrights.enderkinesis.spell.Element.HEAT
import org.shipwrights.enderkinesis.spell.Element.POISON
import org.shipwrights.enderkinesis.spell.Element.PURITY
import org.shipwrights.enderkinesis.spell.Element.SUNFIRE
import org.shipwrights.enderkinesis.spell.Element.VOID

class BeamTest {

    private fun seg(from: Vec3, to: Vec3): BeamSegment {
        val d = to.subtract(from)
        return BeamSegment(from, d.normalize(), to, d.length())
    }

    @Test
    fun `closest points of crossing segments`() {
        val c = BeamGeometry.closest(Vec3(-5.0, 0.0, 0.0), Vec3(5.0, 0.0, 0.0), Vec3(0.0, 1.0, -5.0), Vec3(0.0, 1.0, 5.0))
        c.distance shouldBe (1.0 plusOrMinus 1e-9)
        c.s shouldBe (0.5 plusOrMinus 1e-9)
        c.midpoint.y shouldBe (0.5 plusOrMinus 1e-9)
    }

    @Test
    fun `closest points of parallel and disjoint segments`() {
        BeamGeometry.closest(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(3.0, 0.0, 0.0), Vec3(4.0, 0.0, 0.0)).distance shouldBe
            (2.0 plusOrMinus 1e-9)
        BeamGeometry.closest(Vec3(0.0, 0.0, 0.0), Vec3(4.0, 0.0, 0.0), Vec3(0.0, 2.0, 0.0), Vec3(4.0, 2.0, 0.0)).distance shouldBe
            (2.0 plusOrMinus 1e-9)
    }

    @Test
    fun `head-on means within 10 degrees of opposite`() {
        BeamGeometry.headOn(Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0)) shouldBe true
        val nine = Math.toRadians(171.0)
        BeamGeometry.headOn(Vec3(1.0, 0.0, 0.0), Vec3(Math.cos(nine), Math.sin(nine), 0.0)) shouldBe true
        val eleven = Math.toRadians(169.0)
        BeamGeometry.headOn(Vec3(1.0, 0.0, 0.0), Vec3(Math.cos(eleven), Math.sin(eleven), 0.0)) shouldBe false
    }

    @Test
    fun `a beam sent straight back loops at the reflection`() {
        val out = seg(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 0.0, 0.0))
        val back = seg(Vec3(10.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0))
        val trace = SunderingBeamTrace.truncateAtLoop(BeamTrace(listOf(out, back), null), 0.5)
        trace.loopPoint.shouldNotBeNull().x shouldBe (10.0 plusOrMinus 1e-9)
        trace.segments.size shouldBe 1
    }

    @Test
    fun `a beam crossing an earlier segment loops there`() {
        val a = seg(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 0.0, 0.0))
        val b = seg(Vec3(10.0, 0.0, 0.0), Vec3(10.0, 0.0, 10.0))
        val c = seg(Vec3(10.0, 0.0, 10.0), Vec3(5.0, 0.0, -5.0))
        val trace = SunderingBeamTrace.truncateAtLoop(BeamTrace(listOf(a, b, c), null), 0.5)
        // c crosses a at z = 0, two thirds of the way along.
        val loop = trace.loopPoint.shouldNotBeNull()
        loop.x shouldBe (20.0 / 3.0 plusOrMinus 1e-6)
        loop.z shouldBe (0.0 plusOrMinus 1e-6)
        trace.segments.size shouldBe 3
        trace.segments.last().length shouldBe (c.length * 2.0 / 3.0 plusOrMinus 1e-6)
    }

    @Test
    fun `a beam that never meets itself is unchanged`() {
        val a = seg(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 0.0, 0.0))
        val b = seg(Vec3(10.0, 0.0, 0.0), Vec3(10.0, 10.0, 0.0))
        SunderingBeamTrace.truncateAtLoop(BeamTrace(listOf(a, b), null), 0.5).loopPoint.shouldBeNull()
    }

    @Test
    fun `opposites inside fusions count`() {
        BeamManager.opposed(listOf(SUNFIRE), listOf(GROWTH)) shouldBe true
        BeamManager.opposed(listOf(VOID), listOf(CHAOS)) shouldBe true
        BeamManager.opposed(listOf(POISON), listOf(BLOOD)) shouldBe false
    }

    @Test
    fun `the spec's crossing example leaves a fiery blast`() {
        val (left, annihilated) = BeamManager.combine(listOf(SUNFIRE), listOf(GROWTH))
        left shouldBe listOf(HEAT)
        annihilated shouldBe 2
    }

    @Test
    fun `merging beams fuses as at the altar`() {
        BeamManager.combine(listOf(HEAT, HEAT), listOf(PURITY)).first shouldBe listOf(HEAT, SUNFIRE)
        // Poison's Cold fuses with the Growth already there; its own Growth is added after.
        BeamManager.combine(listOf(GROWTH), listOf(POISON)).first shouldBe listOf(POISON, GROWTH)
        BeamManager.combine(listOf(COLD, GRAVITY), listOf(HEAT)).second shouldBe 2
    }
}
