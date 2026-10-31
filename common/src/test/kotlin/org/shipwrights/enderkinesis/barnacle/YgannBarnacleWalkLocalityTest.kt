package org.shipwrights.enderkinesis.barnacle

import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import org.junit.jupiter.api.Test
import org.shipwrights.enderkinesis.barnacle.YgannBarnacleGeometry.BPath

/** A hull edit must re-route only the limbs that pass near it, and the branches that grow from those. */
class YgannBarnacleWalkLocalityTest {

    private val core = BlockPos(0, 1, 0)

    private fun floor(extra: Set<Long>): YgannBarnacleGeometry.HullShapes =
        YgannBarnacleGeometry.HullShapes { x, y, z ->
            val solid = (y == 0 && x in -FLOOR..FLOOR && z in -FLOOR..FLOOR) || BlockPos.asLong(x, y, z) in extra
            if (solid) Shapes.block() else Shapes.empty()
        }

    private fun walk(seed: Long, extra: Set<Long>) =
        YgannBarnacleGeometry.walk(floor(extra), core, seed, Direction.UP).paths

    private fun sameRoute(a: BPath, b: BPath): Boolean =
        a.points.size == b.points.size && a.points.indices.all { a.points[it].pos == b.points[it].pos }

    /** True when a point of [path] is within [reach] blocks of the cell [cell] (core-local points). */
    private fun passesNear(path: BPath, cell: BlockPos, reach: Double): Boolean = path.points.any { wp ->
        val x = core.x + wp.pos.x; val y = core.y + wp.pos.y; val z = core.z + wp.pos.z
        val dx = Math.max(0.0, Math.max(cell.x - x, x - (cell.x + 1)))
        val dy = Math.max(0.0, Math.max(cell.y - y, y - (cell.y + 1)))
        val dz = Math.max(0.0, Math.max(cell.z - z, z - (cell.z + 1)))
        dx * dx + dy * dy + dz * dz <= reach * reach
    }

    private fun meets(a: BPath, b: BPath): Boolean =
        a.points.any { pa -> b.points.any { pb -> pa.pos.distance(pb.pos) < CONTACT_BLOCKS } }

    @Test
    fun `an edit leaves far limbs on the same route`() {
        var edits = 0
        var stray = 0
        for (seed in 1L..SEEDS) {
            val base = walk(seed, emptySet())
            for (x in -EDIT_RANGE..EDIT_RANGE) for (z in -EDIT_RANGE..EDIT_RANGE) {
                if (x == 0 && z == 0) continue
                val cell = BlockPos(x, 1, z)
                val edited = walk(seed, setOf(cell.asLong()))
                edits++
                // A limb may change when it passes near the edit, when its parent changed, or when a limb laid
                // before it changed and now (or before) reaches it, since limbs stop where they meet.
                val allowed = HashSet<Int>()
                val changed = ArrayList<BPath>()
                for (p in base) {
                    val q = edited.firstOrNull { it.id == p.id }
                    val parentChanged = p.parent >= 0 && base[p.parent].id in allowed
                    val touched = changed.any { c -> meets(p, c) }
                    if (parentChanged || touched || passesNear(p, cell, NEAR_BLOCKS)) allowed.add(p.id)
                    if (q == null || !sameRoute(p, q)) {
                        changed.add(p)
                        if (q != null) changed.add(q)
                    }
                }
                for (p in base) {
                    if (p.id in allowed) continue
                    val q = edited.firstOrNull { it.id == p.id }
                    if (q == null || !sameRoute(p, q)) {
                        stray++
                        println("seed $seed edit ($x,$z): far limb ${p.id} changed")
                    }
                }
            }
        }
        println("edits: $edits, far limbs that changed: $stray")
        stray shouldBe 0
    }

    private companion object {
        const val FLOOR = 16
        const val EDIT_RANGE = 8
        const val SEEDS = 6L
        /** On flat ground the walk reads only the hull under each step (one 3 px step ahead), so an
         *  edit farther than this from a limb must leave it alone. */
        const val NEAR_BLOCKS = 0.3
        /** A limb stops this near another (blocks; the walk's contact distance, with a margin). */
        const val CONTACT_BLOCKS = 0.25
    }
}
