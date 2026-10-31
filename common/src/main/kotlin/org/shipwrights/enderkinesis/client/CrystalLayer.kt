package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.client.SpellGeom.smooth
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// The plan stores positions on each face, so the crystals move with the animated part.
// Times are in seconds since the effect began.
object CrystalLayer {
    /** [s0], [t0], [ds], [dt] are fractions of the face sides; [at] is when the crust arrives. */
    private class Cell(val quad: Int, val s0: Float, val t0: Float, val ds: Float, val dt: Float, val at: Float)
    private class Bud(val cell: Cell, val start: Float, val top: Int, val phase: Float)
    private class Seed(val cell: Cell, val born: Float)
    /** Face `q` owns cells `firstCell[q]` until `firstCell[q + 1]`; the crust covers all of them by `covered[q]`. */
    private class Plan(val quads: Int, val cells: List<Cell>, val buds: List<Bud>, val seeds: List<Seed>, val firstCell: IntArray, val covered: FloatArray)

    // Render thread only.
    private val plans = HashMap<Int, Plan>()
    // Game tick when each mob was first seen crystallized.
    private val since = HashMap<Int, Long>()

    // 2 pixels, in blocks.
    private const val STEP = 0.125
    // Above this count on one mob, the cells become larger.
    private const val MAX_CELLS = 3000
    private const val SPREAD = 0.45f
    // Seconds.
    private const val CELL_GROW = 0.25f
    private const val LIFT = 0.008
    private const val BUD_SIZE = 0.3
    private val BUDS = arrayOf("small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud", "amethyst_cluster")

    fun forget(entityId: Int) {
        plans.remove(entityId)
        since.remove(entityId)
    }

    fun reset() {
        plans.clear()
        since.clear()
    }

    private fun sideS(q: EntityPasses.Quad): Vec3 = q.corners[1].subtract(q.corners[0])
    private fun sideT(q: EntityPasses.Quad): Vec3 = q.corners[3].subtract(q.corners[0])

    private fun point(q: EntityPasses.Quad, s: Double, t: Double, lift: Double): Vec3 {
        val c = q.corners
        val a = c[0].lerp(c[1], s)
        val b = c[3].lerp(c[2], s)
        return a.lerp(b, t).add(q.normal.scale(lift))
    }

    private fun plan(entityId: Int, quads: List<EntityPasses.Quad>): Plan {
        plans[entityId]?.takeIf { it.quads == quads.size }?.let { return it }
        val r = Random(entityId * 7919 + 1)
        val area = quads.sumOf { sideS(it).length() * sideT(it).length() }
        val step = max(STEP, sqrt(area / MAX_CELLS))
        val raw = ArrayList<Cell>()
        val centres = ArrayList<Vec3>()
        quads.forEachIndexed { qi, q ->
            val ls = sideS(q).length(); val lt = sideT(q).length()
            if (ls < 1e-4 || lt < 1e-4) return@forEachIndexed
            val ns = max(1, (ls / step).roundToInt()); val nt = max(1, (lt / step).roundToInt())
            for (i in 0 until ns) for (j in 0 until nt) {
                val cell = Cell(qi, i / ns.toFloat(), j / nt.toFloat(), 1f / ns, 1f / nt, 0f)
                raw += cell
                centres += point(q, (cell.s0 + cell.ds / 2).toDouble(), (cell.t0 + cell.dt / 2).toDouble(), 0.0)
            }
        }
        if (raw.isEmpty()) return Plan(quads.size, emptyList(), emptyList(), emptyList(), IntArray(quads.size + 1), FloatArray(quads.size)).also { plans[entityId] = it }
        fun spaced(count: Int, spacing: Double): List<Int> {
            val out = ArrayList<Int>()
            var tries = 0
            while (out.size < count && tries++ < count * 60) {
                val k = r.nextInt(raw.size)
                if (out.none { centres[it].distanceTo(centres[k]) < spacing }) out += k
            }
            return out
        }
        val seedIdx = spaced(6, 0.4)
        val born = seedIdx.map { r.nextFloat() * 0.5f }
        fun arrival(p: Vec3): Float = seedIdx.indices.minOf { k -> born[k] + 0.2f + (p.distanceTo(centres[seedIdx[k]]) / SPREAD).toFloat() }
        val cells = raw.mapIndexed { i, c -> Cell(c.quad, c.s0, c.t0, c.ds, c.dt, arrival(centres[i]) + r.nextFloat() * 0.15f) }
        val buds = spaced(24, 0.16).map { k ->
            val top = if (r.nextFloat() < 0.35f) 3 else if (r.nextFloat() < 0.6f) 2 else 1
            Bud(cells[k], cells[k].at + 0.35f + r.nextFloat() * 0.5f, top, r.nextFloat() * 6.28f)
        }
        val seeds = seedIdx.mapIndexed { k, i -> Seed(cells[i], born[k]) }
        val firstCell = IntArray(quads.size + 1) { cells.size }
        val covered = FloatArray(quads.size)
        for (i in cells.indices.reversed()) {
            val q = cells[i].quad
            firstCell[q] = i
            covered[q] = maxOf(covered[q], cells[i].at + CELL_GROW)
        }
        for (q in quads.size - 1 downTo 0) if (firstCell[q] > firstCell[q + 1]) firstCell[q] = firstCell[q + 1]
        return Plan(quads.size, cells, buds, seeds, firstCell, covered).also { plans[entityId] = it }
    }

    /** [quads] are in the mob's own space, which [pose] is at. */
    fun render(entity: LivingEntity, quads: List<EntityPasses.Quad>, buffers: MultiBufferSource, pose: PoseStack, light: Int, partialTick: Float) {
        if (!SpellVfxRenderTypes.isReady() || quads.isEmpty()) return
        val now = entity.level().gameTime
        val age = (now - since.getOrPut(entity.id) { now } + partialTick) / 20f
        val time = entity.tickCount + partialTick
        val plan = plan(entity.id, quads)
        val m = pose.last().pose()

        // Draw a covered face as one quad, not hundreds of cells. The texture repeat is the same.
        val crust = buffers.getBuffer(SpellVfxRenderTypes.sprite(BLOCK_ATLAS))
        val calcite = SpellArt.block("calcite")
        for (qi in quads.indices) {
            val q = quads[qi]
            if (age >= plan.covered[qi]) {
                if (plan.firstCell[qi] < plan.firstCell[qi + 1]) {
                    SpellShapes.texFace(crust, m, point(q, 0.0, 0.0, LIFT), point(q, 1.0, 0.0, LIFT), point(q, 1.0, 1.0, LIFT), point(q, 0.0, 1.0, LIFT), calcite, 0xFFFFFF, 1f, light)
                }
                continue
            }
            val ls = sideS(q).length(); val lt = sideT(q).length()
            for (ci in plan.firstCell[qi] until plan.firstCell[qi + 1]) {
                val cell = plan.cells[ci]
                val g = smooth((age - cell.at) / CELL_GROW)
                if (g <= 0f) continue
                val ss = cell.ds * (1 - g) / 2; val st = cell.dt * (1 - g) / 2
                val s0 = (cell.s0 + ss).toDouble(); val s1 = (cell.s0 + cell.ds - ss).toDouble()
                val t0 = (cell.t0 + st).toDouble(); val t1 = (cell.t0 + cell.dt - st).toDouble()
                // Offset the texture to the cell position, so the cells join into one surface.
                val u = (s0 * ls) % 1.0; val v = (t0 * lt) % 1.0
                SpellShapes.texFace(crust, m, point(q, s0, t0, LIFT), point(q, s1, t0, LIFT), point(q, s1, t1, LIFT), point(q, s0, t1, LIFT), calcite, 0xFFFFFF, 1f, light, u, v)
            }
        }

        if (SpellVfxRenderTypes.isGrayReady()) {
            val gray = buffers.getBuffer(SpellVfxRenderTypes.gray(BLOCK_ATLAS))
            for (b in plan.buds) bud(gray, m, quads, b, age, 0xF6F2FF, 1f, light)
            val glow = buffers.getBuffer(SpellVfxRenderTypes.glow(BLOCK_ATLAS))
            for (b in plan.buds) bud(glow, m, quads, b, age, 0xFFFFFF, 0.2f, SpellShapes.FULL_BRIGHT)
        }

        val seedGlow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        val cubes = plan.seeds.mapIndexedNotNull { k, s -> seedCube(quads, s, k, age, time) }
        for ((cc, glow) in cubes) SpellShapes.glowCubeFaces(seedGlow, m, cc, 0xFFFFFF, 0.5f * glow, 0.5)
        val lines = buffers.getBuffer(RenderType.lines())
        for ((cc, glow) in cubes) SpellShapes.wire(lines, pose.last(), cc, 0xFFFFFF, glow)
    }

    private fun bud(c: VertexConsumer, m: org.joml.Matrix4f, quads: List<EntityPasses.Quad>, b: Bud, age: Float, rgb: Int, alpha: Float, light: Int) {
        if (age < b.start) return
        val q = quads[b.cell.quad]
        val stage = minOf(b.top, floor((age - b.start) / 0.45f).toInt())
        val sprite = SpellArt.block(BUDS[stage])
        val base = point(q, (b.cell.s0 + b.cell.ds / 2).toDouble(), (b.cell.t0 + b.cell.dt / 2).toDouble(), LIFT)
        val u = sideS(q).normalize(); val v = sideT(q).normalize()
        val tip = base.add(q.normal.scale(BUD_SIZE))
        val half = (14.4 / 16) * sqrt(2.0) * BUD_SIZE / 2
        for (d in arrayOf(u.add(v).normalize(), u.subtract(v).normalize())) {
            val hw = d.scale(half)
            SpellShapes.tquad(c, m, base.subtract(hw), base.add(hw), tip.add(hw), tip.subtract(hw), sprite, floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f), rgb, alpha, light)
        }
    }

    private fun seedCube(quads: List<EntityPasses.Quad>, s: Seed, k: Int, age: Float, time: Float): Pair<Array<Vec3>, Float>? {
        val t = age - s.born
        if (t <= 0f) return null
        val q = quads[s.cell.quad]
        val size = 0.05 * smooth(t / 0.25f)
        val glow = 0.3f + 0.7f * (1f - smooth((t - 0.3f) / 1.0f))
        val centre = point(q, (s.cell.s0 + s.cell.ds / 2).toDouble(), (s.cell.t0 + s.cell.dt / 2).toDouble(), size * 0.8 + LIFT)
        return SpellGeom.cubeCorners(centre, size * (0.9 + 0.1 * sin(time * 0.2 + k)), k * 1.3 + time * 0.01, k * 0.7) to glow
    }
}
