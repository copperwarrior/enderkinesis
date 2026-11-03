package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.SpellGeom.easeOut
import org.shipwrights.enderkinesis.client.SpellGeom.smooth
import org.shipwrights.enderkinesis.client.SpellGeom.whiten
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** The Wehelo Blade tear: a fractal slit filled with end-portal space, with lightning cracks growing out of its rim. */
object WeheloRiftDraw {

    private class Tear(val spine: List<Vec3>, val left: List<Vec3>, val right: List<Vec3>, val cracks: List<Crack>)

    /** [rootScale] is the line width at the root, as a share of the rim width: 1 off a lip, less off a parent crack. */
    private class Crack(val points: List<Vec3>, val rootScale: Double)

    /** The open and close of a one-shot tear, from its 0..1 age [t]. */
    fun burstOpen(t: Float): Double = easeOut(minOf(1f, t * OPEN_RATE).toDouble())

    fun burstAmount(t: Float): Double = burstOpen(t) * (1.0 - smooth(((t - SEAL_FROM) / (1f - SEAL_FROM)).toDouble()))

    /**
     * Draws one tear: the space, then its rims over it. The rims merge with MAX in their own target, then add
     * onto the scene, so joins do not pile up light and the rims still brighten what is behind them.
     */
    fun draw(
        buffers: MultiBufferSource, m: Matrix4f, view: SpellView, a: Vec3, size: Double, rgb: Int, seed: Int,
        open: Double, amount: Double, time: Float,
    ) {
        if (!WeheloTearRenderTypes.isReady()) return
        val tear = build(view, a, size, seed, open, amount, time) ?: return
        portal(buffers.getBuffer(WeheloTearRenderTypes.SPACE), m, view, tear, rgb)
        val source = buffers as? MultiBufferSource.BufferSource
        if (source == null) {
            glow(buffers.getBuffer(WeheloTearRenderTypes.GLOW), m, view, tear, rgb, size)
            return
        }
        source.endBatch(WeheloTearRenderTypes.SPACE)
        if (!WeheloTearRenderTypes.beginOffscreenGlow()) {
            glow(source.getBuffer(WeheloTearRenderTypes.GLOW), m, view, tear, rgb, size)
            return
        }
        glow(source.getBuffer(WeheloTearRenderTypes.GLOW_OFFSCREEN), m, view, tear, rgb, size)
        source.endBatch(WeheloTearRenderTypes.GLOW_OFFSCREEN)
        WeheloTearRenderTypes.compositeGlow()
    }

    private fun portal(c: VertexConsumer, m: Matrix4f, view: SpellView, tear: Tear, rgb: Int) {
        val tint = whiten(rgb, PORTAL_WHITEN)
        for (i in 0 until tear.spine.size - 1) {
            SpellShapes.quad(c, m, tear.spine[i], tear.left[i], tear.left[i + 1], tear.spine[i + 1], tint, 1f, 1f, 1f, 1f)
            SpellShapes.quad(c, m, tear.spine[i], tear.right[i], tear.right[i + 1], tear.spine[i + 1], tint, 1f, 1f, 1f, 1f)
        }
    }

    private fun glow(c: VertexConsumer, m: Matrix4f, view: SpellView, tear: Tear, rgb: Int, size: Double) {
        // MAX blending does not stack strips into brightness, so each strip carries its full brightness itself.
        val halo = whiten(rgb, HALO_WHITEN)
        val core = whiten(rgb, CORE_WHITEN)
        // One loop up the left lip and down the right, so the tips are bends, not two crossing ends.
        val rim = tear.left + tear.right.subList(1, tear.right.size - 1).asReversed()
        SpellShapes.strip(c, m, view, rim, { size * LIP_HALO }, halo, HALO_ALPHA, soft = true, closed = true)
        SpellShapes.strip(c, m, view, rim, { size * LIP_CORE }, core, CORE_ALPHA, soft = true, closed = true)
        // A crack starts at the rim's own widths, so it grows out of the rim line instead of sitting on it.
        for (crack in tear.cracks) {
            SpellShapes.strip(c, m, view, crack.points, { i -> size * LIP_HALO * scale(crack, i) }, halo, HALO_ALPHA, soft = true)
            SpellShapes.strip(c, m, view, crack.points, { i -> size * LIP_CORE * scale(crack, i) }, core, CORE_ALPHA, soft = true)
        }
    }

    private fun scale(crack: Crack, i: Int): Double = crack.rootScale * taper(i, crack.points.size)

    private fun taper(i: Int, count: Int): Double = (1.0 - i.toDouble() / (count - 1)).pow(TAPER)

    @Suppress("LongMethod") // One pass over the plane coords keeps the lips, spine and cracks in step.
    private fun build(view: SpellView, a: Vec3, size: Double, seed: Int, open: Double, amount: Double, time: Float): Tear? {
        if (amount < MIN_AMOUNT) return null

        val toCam = view.cam.subtract(a)
        val side = Vec3(toCam.z, 0.0, -toCam.x).let { if (it.lengthSqr() < 1.0e-8) view.left else it.normalize() }
        fun at(x: Double, y: Double): Vec3 = a.add(side.scale(x)).add(0.0, y, 0.0)

        val stable = Random(seed)
        // The flicker seed changes every few ticks, so the edges crawl.
        val flicker = Random(seed + FLICKER_SALT * floor(time / FLICKER_TICKS).toInt())
        val spineP = profile(stable, DEPTH, SPINE_ROUGH)
        val leftP = profile(stable, DEPTH, EDGE_ROUGH)
        val rightP = profile(stable, DEPTH, EDGE_ROUGH)
        val leftF = profile(flicker, DEPTH, EDGE_FLICKER)
        val rightF = profile(flicker, DEPTH, EDGE_FLICKER)

        val n = 1 shl DEPTH
        val height = size * (HEIGHT_START + (1.0 - HEIGHT_START) * open)
        val halfWidth = size * WIDTH * amount
        val sx = DoubleArray(n + 1); val sy = DoubleArray(n + 1)
        val lx = DoubleArray(n + 1); val rx = DoubleArray(n + 1)
        for (i in 0..n) {
            val u = i.toDouble() / n
            val env = sin(Math.PI * u).pow(ENVELOPE)
            sx[i] = spineP[i] * size
            sy[i] = (u - 0.5) * height
            val toothL = if (stable.nextDouble() < TOOTH_CHANCE) TOOTH else 1.0
            val toothR = if (stable.nextDouble() < TOOTH_CHANCE) TOOTH else 1.0
            lx[i] = sx[i] - halfWidth * env * toothL * (1.0 + leftP[i] + leftF[i]).coerceAtLeast(MIN_EDGE)
            rx[i] = sx[i] + halfWidth * env * toothR * (1.0 + rightP[i] + rightF[i]).coerceAtLeast(MIN_EDGE)
        }

        val cracks = ArrayList<Crack>(CRACKS * 2)
        repeat(CRACKS) {
            val i = CRACK_EDGE_MARGIN + stable.nextInt(n + 1 - 2 * CRACK_EDGE_MARGIN)
            val outward = if (stable.nextBoolean()) 1.0 else -1.0
            val angle = (stable.nextDouble() * 2 - 1) * CRACK_SPREAD
            val dx = outward * cos(angle); val dy = sin(angle)
            val length = size * (CRACK_MIN + stable.nextDouble() * CRACK_RANGE) * amount
            val rootX = if (outward < 0) lx[i] else rx[i]
            val main = crackPath(stable, flicker, rootX, sy[i], dx, dy, length, CRACK_DEPTH)
            cracks += Crack(main.map { at(it.x, it.y) }, 1.0)
            // One branch off the middle of each crack.
            val j = 2 + stable.nextInt(main.size - 4)
            val turn = (if (stable.nextBoolean()) 1 else -1) * (BRANCH_MIN + stable.nextDouble() * BRANCH_RANGE)
            val bx = dx * cos(turn) - dy * sin(turn); val by = dx * sin(turn) + dy * cos(turn)
            val branch = crackPath(stable, flicker, main[j].x, main[j].y, bx, by, length * BRANCH_LENGTH, CRACK_DEPTH - 1)
            cracks += Crack(branch.map { at(it.x, it.y) }, taper(j, main.size))
        }

        return Tear(
            List(n + 1) { at(sx[it], sy[it]) },
            List(n + 1) { at(lx[it], sy[it]) },
            List(n + 1) { at(rx[it], sy[it]) },
            cracks,
        )
    }

    private fun crackPath(stable: Random, flicker: Random, x0: Double, y0: Double, dx: Double, dy: Double, length: Double, depth: Int): List<Vec3> {
        val p = profile(stable, depth, CRACK_ROUGH)
        val f = profile(flicker, depth, CRACK_FLICKER)
        val m = 1 shl depth
        return List(m + 1) { j ->
            val along = length * j / m
            val off = length * (p[j] + f[j])
            Vec3(x0 + dx * along - dy * off, y0 + dy * along + dx * off, 0.0)
        }
    }

    /** Midpoint displacement: 2^[depth] + 1 offsets, zero at both ends, roughness halving each level. */
    private fun profile(random: Random, depth: Int, rough: Double): DoubleArray {
        val n = 1 shl depth
        val out = DoubleArray(n + 1)
        var step = n
        var amp = rough
        while (step > 1) {
            val half = step / 2
            var i = half
            while (i < n) {
                out[i] = (out[i - half] + out[i + half]) * 0.5 + (random.nextDouble() * 2 - 1) * amp
                i += step
            }
            step = half
            amp *= ROUGH_DECAY
        }
        return out
    }

    private const val DEPTH = 5
    private const val CRACK_DEPTH = 3
    private const val ROUGH_DECAY = 0.72
    private const val OPEN_RATE = 5f
    private const val SEAL_FROM = 0.55f
    private const val MIN_AMOUNT = 0.01
    private const val HEIGHT_START = 0.6
    /** Widest half-gap of the tear, as a fraction of its height. */
    private const val WIDTH = 0.13
    private const val ENVELOPE = 0.7
    private const val SPINE_ROUGH = 0.07
    private const val EDGE_ROUGH = 0.55
    private const val EDGE_FLICKER = 0.3
    private const val MIN_EDGE = 0.15
    private const val TOOTH_CHANCE = 0.3
    private const val TOOTH = 1.9
    private const val FLICKER_TICKS = 2f
    private const val FLICKER_SALT = 7919
    private const val CRACKS = 7
    private const val CRACK_EDGE_MARGIN = 4
    private const val CRACK_SPREAD = 1.0
    private const val CRACK_MIN = 0.12
    private const val CRACK_RANGE = 0.3
    private const val CRACK_ROUGH = 0.18
    private const val CRACK_FLICKER = 0.05
    /** Above 1 the line thins fast near the root; below 1 it stays full longer. */
    private const val TAPER = 0.6
    private const val BRANCH_MIN = 0.35
    private const val BRANCH_RANGE = 0.5
    private const val BRANCH_LENGTH = 0.5
    private const val PORTAL_WHITEN = 0.4f
    private const val HALO_WHITEN = 0.3f
    private const val CORE_WHITEN = 0.75f
    private const val LIP_HALO = 0.08
    private const val LIP_CORE = 0.014
    private const val HALO_ALPHA = 0.8f
    private const val CORE_ALPHA = 1.0f
}
