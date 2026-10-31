package org.shipwrights.enderkinesis.barnacle

import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.shapes.Shapes
import org.joml.Vector3d
import org.junit.jupiter.api.Test
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry.BPath

/** Limbs from different roots must not lie on each other, and a limb with no room must stop, not coil. */
class YgannBarnacleWalkContactTest {

    private val core = BlockPos(0, 1, 0)

    private fun walks(facing: Direction, shapes: YgannBarnacleGeometry.HullShapes) =
        (1L..SEEDS).map { YgannBarnacleGeometry.walk(shapes, core, it, facing).paths }

    /** Pairs of limbs from different roots that come within [TOUCH_BLOCKS], away from the core. */
    private fun crossings(paths: List<BPath>): Int {
        fun root(i: Int): Int { var k = i; while (paths[k].parent >= 0) k = paths[k].parent; return k }
        var count = 0
        for (a in paths.indices) for (b in a + 1 until paths.size) {
            if (root(a) == root(b)) continue
            val near = paths[a].points.drop(CORE_POINTS).any { pa ->
                paths[b].points.drop(CORE_POINTS).any { pb -> pa.pos.distance(pb.pos) < TOUCH_BLOCKS }
            }
            if (near) count++
        }
        return count
    }

    /** Places where a limb turns back on itself within a few steps. */
    private fun foldBacks(path: BPath): Int {
        val p = path.points
        var count = 0
        for (k in 0 until p.size - 7) {
            val d1 = Vector3d(p[k + 2].pos).sub(p[k].pos)
            val d2 = Vector3d(p[k + 7].pos).sub(p[k + 5].pos)
            if (d1.lengthSquared() > 1.0e-6 && d2.lengthSquared() > 1.0e-6 && d1.normalize().dot(d2.normalize()) < -0.7) count++
        }
        return count
    }

    @Test
    fun `limbs of different roots do not lie on each other`() {
        val platform = walks(Direction.UP) { x, y, z -> if (y == 0 && x in -4..4 && z in -4..4) Shapes.block() else Shapes.empty() }
        val rows = walks(Direction.UP) { x, y, z ->
            val floor = y == 0 && x in -16..16 && z in -16..16
            if (floor || (y == 1 && Math.floorMod(x, 3) == 2) || (y == 2 && Math.floorMod(x, 6) == 5)) Shapes.block() else Shapes.empty()
        }
        val total = (platform + rows).sumOf { crossings(it) }
        println("crossing limb pairs on a platform and rows: $total")
        total shouldBeLessThanOrEqual MAX_CROSSINGS
    }

    @Test
    fun `a barnacle under one block does not coil its limbs round it`() {
        val under = walks(Direction.DOWN) { x, y, z -> if (x == 0 && y == 2 && z == 0) Shapes.block() else Shapes.empty() }
        val folds = under.sumOf { paths -> paths.sumOf { foldBacks(it) } }
        println("fold-backs under one block: $folds")
        folds shouldBeLessThanOrEqual MAX_FOLDS_UNDER_BLOCK
    }

    private companion object {
        const val SEEDS = 8L
        const val TOUCH_BLOCKS = 0.15
        /** Roots all start at the core, so their first points are near each other by design. */
        const val CORE_POINTS = 4
        const val MAX_CROSSINGS = 6
        /** A limb that drapes over the one block still turns back once at each edge. */
        const val MAX_FOLDS_UNDER_BLOCK = 40
    }
}
