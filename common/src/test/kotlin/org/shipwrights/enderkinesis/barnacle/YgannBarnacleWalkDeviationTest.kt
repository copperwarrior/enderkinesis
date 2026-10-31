package org.shipwrights.enderkinesis.barnacle

import io.kotest.matchers.doubles.shouldBeLessThan
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.shapes.Shapes
import org.junit.jupiter.api.Test
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry.BPath

/** How far a limb moves when one block is placed on its route: the mean distance (blocks) from each
 *  new point to the old route. A limb that climbs over the block and goes on along its old course
 *  scores about the climb; a limb that takes a new course scores much more. */
class YgannBarnacleWalkDeviationTest {

    private val core = BlockPos(0, 1, 0)

    private fun walk(seed: Long, extra: Long?) = YgannBarnacleGeometry.walk(
        { x, y, z ->
            if ((y == 0 && x in -16..16 && z in -16..16) || BlockPos.asLong(x, y, z) == extra) Shapes.block() else Shapes.empty()
        },
        core, seed, Direction.UP,
    ).paths

    private fun meanDeviation(old: BPath, new: BPath): Double = new.points.map { a ->
        old.points.minOf { b -> a.pos.distance(b.pos) }
    }.average()

    @Test
    fun `a block on a limb moves it a little`() {
        val deviations = ArrayList<Double>()
        for (seed in 1L..SEEDS) {
            val base = walk(seed, null)
            // One block on each root, a third and two thirds along it.
            for (root in base.filter { it.parent < 0 }) for (frac in listOf(0.33, 0.66)) {
                val p = root.points[(root.points.size * frac).toInt()].pos
                val cell = BlockPos(Math.floor(core.x + p.x).toInt(), core.y, Math.floor(core.z + p.z).toInt())
                if (cell == core) continue
                val edited = walk(seed, cell.asLong())
                val q = edited.firstOrNull { it.id == root.id } ?: continue
                deviations.add(meanDeviation(root, q))
            }
        }
        deviations.sort()
        println("root moves: n=${deviations.size} median %.2f p90 %.2f max %.2f blocks".format(
            deviations[deviations.size / 2], deviations[deviations.size * 9 / 10], deviations.last()))
        deviations[deviations.size * 9 / 10] shouldBeLessThan P90_LIMIT_BLOCKS
    }

    private companion object {
        const val SEEDS = 6L
        /** Most detours must keep a root's new points this near its old route on average (blocks); a
         *  climb over the block costs about half of it. */
        const val P90_LIMIT_BLOCKS = 0.6
    }
}
