package org.shipwrights.enderkinesis.barnacle

import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.shapes.Shapes
import org.joml.Vector3d
import org.junit.jupiter.api.Test
import java.util.Random

/** A resting limb, drawn the way the renderer draws it (straight boxes along the route), must not sink
 *  into the hull: on a floor with scattered blocks, no route point and few box points may be in a block. */
class YgannBarnacleDrawClipTest {

    private val core = BlockPos(0, 1, 0)

    @Test
    fun `resting limbs stay out of the hull`() {
        var probes = 0
        var inside = 0
        var routeInside = 0
        var segments = 0
        var limbs = 0
        for (seed in 1L..SEEDS) {
            val rng = Random(seed * 7919L)
            val extra = HashSet<Long>()
            repeat(BLOCKS) {
                val x = rng.nextInt(13) - 6; val z = rng.nextInt(13) - 6
                if (x != 0 || z != 0) {
                    extra.add(BlockPos.asLong(x, 1, z))
                    if (rng.nextInt(4) == 0) extra.add(BlockPos.asLong(x, 2, z))
                }
            }
            val shapes = YgannBarnacleGeometry.HullShapes { x, y, z ->
                if ((y == 0 && x in -16..16 && z in -16..16) || BlockPos.asLong(x, y, z) in extra) Shapes.block() else Shapes.empty()
            }
            fun solid(p: Vector3d): Boolean = !shapes.shape(
                Math.floor(core.x + p.x).toInt(), Math.floor(core.y + p.y).toInt(), Math.floor(core.z + p.z).toInt(),
            ).isEmpty
            for (path in YgannBarnacleGeometry.walk(shapes, core, seed, Direction.UP).paths) {
                val pts = path.points
                limbs++
                routeInside += pts.count { solid(it.pos) }
                val breaks = path.breaks
                segments += breaks.size - 1
                for (k in 0 until breaks.size - 1) {
                    val (a, na) = YgannBarnacleGeometry.sampleAt(pts, breaks[k])
                    val (b, nb) = YgannBarnacleGeometry.sampleAt(pts, breaks[k + 1])
                    val dir = Vector3d(b).sub(a)
                    if (dir.lengthSquared() > 1.0e-12) {
                        dir.normalize()
                        val n = Vector3d(na).add(nb).normalize()
                        val side = Vector3d(dir).cross(n).normalize()
                        n.set(side).cross(dir).normalize()
                        for (f in 0..4) for (s in listOf(-0.8, 0.0, 0.8)) for (t in listOf(0.2, 1.0, 1.8)) {
                            probes++
                            if (solid(Vector3d(a).lerp(b, f / 4.0).fma(s * W, side).fma(t * W, n))) inside++
                        }
                    }
                }
            }
        }
        val share = inside.toDouble() / probes
        println("segments per limb: %.1f".format(segments.toDouble() / limbs))
        println("route points in a block: $routeInside; drawn box points in a block: $inside of $probes (%.2f%%)".format(share * 100))
        routeInside shouldBe 0
        share shouldBeLessThan MAX_INSIDE_SHARE
    }

    private companion object {
        const val SEEDS = 8L
        const val BLOCKS = 40
        /** The thickest tube half-width (blocks); the real tube thins toward the tip. */
        const val W = 2.4 * 1.06 / 16.0
        const val MAX_INSIDE_SHARE = 0.006
    }
}
