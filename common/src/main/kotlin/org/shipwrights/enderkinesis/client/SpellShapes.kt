package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Where the camera is and which way its screen runs, for camera-facing shapes. */
class SpellView(val cam: Vec3, val left: Vec3, val up: Vec3)

/**
 * The shapes spell visuals are built from. Untextured ones take a position-colour consumer
 * ([SpellVfxRenderTypes.SHADE] or [AegisRenderType.FACE_GLOW]); textured ones take one of
 * [SpellVfxRenderTypes]' sprite types for the [Sprite]'s texture, and are lit by the world at
 * each face unless told otherwise. Textures are laid at Minecraft's 16 texels per block and
 * repeat rather than stretch.
 */
object SpellShapes {
    private const val LAT = 10
    private const val LON = 16
    private const val RING_SEGMENTS = 32
    private const val DISC_SECTORS = 10
    const val FULL_BRIGHT = 0xF000F0

    // ---- Untextured ---------------------------------------------------------------------------

    fun quad(c: VertexConsumer, m: Matrix4f, p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, rgb: Int, a0: Float, a1: Float, a2: Float, a3: Float) {
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        vertex(c, m, p0, r, g, b, a0); vertex(c, m, p1, r, g, b, a1); vertex(c, m, p2, r, g, b, a2); vertex(c, m, p3, r, g, b, a3)
    }

    private fun vertex(c: VertexConsumer, m: Matrix4f, p: Vec3, r: Int, g: Int, b: Int, a: Float) {
        c.vertex(m, p.x.toFloat(), p.y.toFloat(), p.z.toFloat()).color(r, g, b, (a * 255).toInt().coerceIn(0, 255)).endVertex()
    }

    /** A sphere whose opacity peaks at its silhouette, so it reads as a volume of light. */
    fun sphere(c: VertexConsumer, m: Matrix4f, view: SpellView, centre: Vec3, r: Double, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f || r <= 0.01) return
        for (i in 0 until LAT) for (j in 0 until LON) {
            val p0 = spherePoint(centre, r, i, j); val p1 = spherePoint(centre, r, i + 1, j)
            val p2 = spherePoint(centre, r, i + 1, j + 1); val p3 = spherePoint(centre, r, i, j + 1)
            quad(c, m, p0, p1, p2, p3, rgb, alpha * rim(p0, centre, view.cam), alpha * rim(p1, centre, view.cam), alpha * rim(p2, centre, view.cam), alpha * rim(p3, centre, view.cam))
        }
    }

    /** A sphere of one even opacity: a body of darkness. */
    fun sphereFlat(c: VertexConsumer, m: Matrix4f, centre: Vec3, r: Double, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f || r <= 0.01) return
        for (i in 0 until LAT) for (j in 0 until LON) {
            quad(c, m, spherePoint(centre, r, i, j), spherePoint(centre, r, i + 1, j), spherePoint(centre, r, i + 1, j + 1), spherePoint(centre, r, i, j + 1), rgb, alpha, alpha, alpha, alpha)
        }
    }

    /** A soft ball of gas: thickest where it faces the eye, fading to nothing at its edge. */
    fun puff(c: VertexConsumer, m: Matrix4f, view: SpellView, centre: Vec3, r: Double, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f || r <= 0.01) return
        val toward = centre.subtract(view.cam).normalize()
        for (i in 0 until 6) for (j in 0 until 10) {
            val n = arrayOf(spherePoint(Vec3.ZERO, 1.0, i, j, 6, 10), spherePoint(Vec3.ZERO, 1.0, i + 1, j, 6, 10), spherePoint(Vec3.ZERO, 1.0, i + 1, j + 1, 6, 10), spherePoint(Vec3.ZERO, 1.0, i, j + 1, 6, 10))
            val a = FloatArray(4) { k -> val f = Math.abs(n[k].dot(toward)).toFloat(); alpha * f * f }
            quad(c, m, centre.add(n[0].scale(r)), centre.add(n[1].scale(r)), centre.add(n[2].scale(r)), centre.add(n[3].scale(r)), rgb, a[0], a[1], a[2], a[3])
        }
    }

    fun spherePoint(centre: Vec3, r: Double, i: Int, j: Int, lat: Int = LAT, lon: Int = LON): Vec3 {
        val theta = Math.PI * i / lat
        val phi = 2 * Math.PI * j / lon
        return centre.add(r * sin(theta) * cos(phi), r * cos(theta), r * sin(theta) * sin(phi))
    }

    private fun rim(point: Vec3, centre: Vec3, cam: Vec3): Float {
        val n = point.subtract(centre).normalize()
        val v = point.subtract(cam).normalize()
        val edge = 1f - Math.abs(n.dot(v)).toFloat()
        return 0.12f + 0.88f * edge * edge
    }

    /** A flat band of light around [centre], [radius] out and [width] wide, tilted [tilt]
     *  about the x axis (0 = level). */
    fun ring(c: VertexConsumer, m: Matrix4f, centre: Vec3, radius: Double, width: Double, rgb: Int, alpha: Float, tilt: Double) {
        if (alpha <= 0.004f || radius <= 0.01) return
        val ct = cos(tilt); val st = sin(tilt)
        fun at(angle: Double, rr: Double): Vec3 {
            val x = rr * cos(angle); val z = rr * sin(angle)
            return centre.add(x, -z * st, z * ct)
        }
        for (k in 0 until RING_SEGMENTS) {
            val a0 = 2 * Math.PI * k / RING_SEGMENTS
            val a1 = 2 * Math.PI * (k + 1) / RING_SEGMENTS
            quad(c, m, at(a0, radius - width), at(a1, radius - width), at(a1, radius), at(a0, radius), rgb, 0f, 0f, alpha, alpha)
            quad(c, m, at(a0, radius), at(a1, radius), at(a1, radius + width), at(a0, radius + width), rgb, alpha, alpha, 0f, 0f)
        }
    }

    /** A soft disc facing the camera: bright in the middle, fading to nothing at its edge. */
    fun disc(c: VertexConsumer, m: Matrix4f, view: SpellView, centre: Vec3, r: Double, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f) return
        for (k in 0 until DISC_SECTORS) {
            val a0 = 2 * Math.PI * k / DISC_SECTORS
            val a1 = 2 * Math.PI * (k + 1) / DISC_SECTORS
            val e0 = centre.add(view.left.scale(cos(a0) * r)).add(view.up.scale(sin(a0) * r))
            val e1 = centre.add(view.left.scale(cos(a1) * r)).add(view.up.scale(sin(a1) * r))
            quad(c, m, centre, centre, e1, e0, rgb, alpha, alpha, 0f, 0f)
        }
    }

    /**
     * One unbroken camera-facing strip along [points]: each joint shares its edge with both
     * segments, so there are no gaps or overlaps at the bends. [width] and [alphas] give each
     * point its width and opacity (times [alpha]); a [soft] strip fades out to its edges.
     */
    fun strip(
        c: VertexConsumer, m: Matrix4f, view: SpellView, points: List<Vec3>, width: (Int) -> Double, rgb: Int, alpha: Float,
        soft: Boolean, alphas: FloatArray? = null,
    ) {
        if (points.size < 2 || alpha <= 0.004f) return
        val sides = Array(points.size) { i ->
            val along = points[minOf(points.size - 1, i + 1)].subtract(points[maxOf(0, i - 1)])
            val side = along.cross(points[i].subtract(view.cam))
            if (side.lengthSqr() < 1.0e-12) Vec3.ZERO else side.normalize().scale(width(i) * 0.5)
        }
        for (i in 0 until points.size - 1) {
            val p0 = points[i]; val p1 = points[i + 1]
            val a0 = alpha * (alphas?.get(i) ?: 1f); val a1 = alpha * (alphas?.get(i + 1) ?: 1f)
            val e0 = if (soft) 0f else a0; val e1 = if (soft) 0f else a1
            quad(c, m, p0, p1, p1.add(sides[i + 1]), p0.add(sides[i]), rgb, a0, a1, e1, e0)
            quad(c, m, p0.subtract(sides[i]), p1.subtract(sides[i + 1]), p1, p0, rgb, e0, e1, a1, a0)
        }
    }

    /** A cube's faces glowing at their edges and fading toward their middles, as the Staff of
     *  Aegis draws its shield. */
    fun glowCubeFaces(c: VertexConsumer, m: Matrix4f, p: Array<Vec3>, rgb: Int, edgeAlpha: Float, inset: Double = 0.35) {
        if (edgeAlpha <= 0.004f) return
        for (face in SpellGeom.FACES) {
            val centre = face.fold(Vec3.ZERO) { acc, i -> acc.add(p[i]) }.scale(0.25)
            for (k in 0 until 4) {
                val e0 = p[face[k]]; val e1 = p[face[(k + 1) % 4]]
                quad(c, m, e0, e1, e1.lerp(centre, inset), e0.lerp(centre, inset), rgb, edgeAlpha, edgeAlpha, 0f, 0f)
            }
        }
    }

    /** A cube's twelve edges, on a [net.minecraft.client.renderer.RenderType.lines] consumer. */
    fun wire(c: VertexConsumer, pose: PoseStack.Pose, p: Array<Vec3>, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f) return
        for ((i, j) in SpellGeom.EDGES) line(c, pose, p[i], p[j], rgb, alpha)
    }

    fun line(c: VertexConsumer, pose: PoseStack.Pose, a: Vec3, b: Vec3, rgb: Int, alpha: Float) {
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val bl = rgb and 0xFF
        val al = (alpha * 255).toInt().coerceIn(0, 255)
        val d = b.subtract(a).normalize()
        c.vertex(pose.pose(), a.x.toFloat(), a.y.toFloat(), a.z.toFloat()).color(r, g, bl, al).normal(pose.normal(), d.x.toFloat(), d.y.toFloat(), d.z.toFloat()).endVertex()
        c.vertex(pose.pose(), b.x.toFloat(), b.y.toFloat(), b.z.toFloat()).color(r, g, bl, al).normal(pose.normal(), d.x.toFloat(), d.y.toFloat(), d.z.toFloat()).endVertex()
    }

    // ---- Textured -----------------------------------------------------------------------------

    /** The world's light at [p], as the particle shader takes it. */
    fun lightAt(level: ClientLevel, p: Vec3): Int = LevelRenderer.getLightColor(level, BlockPos.containing(p))

    /** A textured quad: [p0]..[p3] round it, each with its texture coordinates as fractions of
     *  [sprite] ([uv] holds u, v for each corner, v = 0 at the top of the image). */
    fun tquad(
        c: VertexConsumer, m: Matrix4f, p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, sprite: Sprite,
        uv: FloatArray, rgb: Int, alpha: Float, light: Int,
    ) {
        if (alpha <= 0.004f) return
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val ps = arrayOf(p0, p1, p2, p3)
        for (k in 0 until 4) {
            val p = ps[k]
            c.vertex(m, p.x.toFloat(), p.y.toFloat(), p.z.toFloat()).uv(sprite.u(uv[k * 2]), sprite.v(uv[k * 2 + 1])).color(r, g, b, a).uv2(light).endVertex()
        }
    }

    /** A camera-facing textured square, [size] blocks across, turned [roll]. */
    fun sprite(c: VertexConsumer, m: Matrix4f, view: SpellView, centre: Vec3, size: Double, sprite: Sprite, rgb: Int, alpha: Float, light: Int, roll: Double = 0.0) {
        if (alpha <= 0.004f || size <= 0.0) return
        val h = size / 2; val cr = cos(roll); val sr = sin(roll)
        val l = view.left.scale(cr).add(view.up.scale(sr)); val u = view.up.scale(cr).add(view.left.scale(-sr))
        tquad(
            c, m, centre.add(l.scale(h)).add(u.scale(-h)), centre.add(l.scale(-h)).add(u.scale(-h)),
            centre.add(l.scale(-h)).add(u.scale(h)), centre.add(l.scale(h)).add(u.scale(h)),
            sprite, floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f), rgb, alpha, light,
        )
    }

    /** [0, length] split wherever the texture repeats (one texture per block, starting
     *  [offset] blocks into it). Each piece is from, to, texture from, texture to. */
    private fun texCuts(offset: Double, length: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var x = 0.0
        while (x < length - 1.0e-6) {
            val t = offset + x
            val base = floor(t + 1.0e-9)
            val next = minOf(length, base + 1 - offset)
            out += doubleArrayOf(x, next, t - base, offset + next - base)
            x = next
        }
        return out
    }

    /** A face at Minecraft's scale, the texture repeating across it rather than stretching.
     *  [p0]..[p3] go round it; [ou], [ov] shift where in the texture it starts. */
    fun texFace(
        c: VertexConsumer, m: Matrix4f, p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, sprite: Sprite,
        rgb: Int, alpha: Float, light: Int, ou: Double = 0.0, ov: Double = 0.0,
    ) {
        val lu = p1.distanceTo(p0); val lv = p3.distanceTo(p0)
        if (lu < 1.0e-6 || lv < 1.0e-6) return
        fun at(u: Double, v: Double): Vec3 = p0.lerp(p1, u / lu).lerp(p3.lerp(p2, u / lu), v / lv)
        for (cu in texCuts(ou, lu)) for (cv in texCuts(ov, lv)) {
            tquad(
                c, m, at(cu[0], cv[0]), at(cu[1], cv[0]), at(cu[1], cv[1]), at(cu[0], cv[1]), sprite,
                floatArrayOf(
                    cu[2].toFloat(), (1 - cv[2]).toFloat(), cu[3].toFloat(), (1 - cv[2]).toFloat(),
                    cu[3].toFloat(), (1 - cv[3]).toFloat(), cu[2].toFloat(), (1 - cv[3]).toFloat(),
                ),
                rgb, alpha, light,
            )
        }
    }

    /** A box of eight corners (numbered as [SpellGeom.cubeCorners] numbers them) textured at
     *  Minecraft's scale, each face starting somewhere different in the texture so the repeats
     *  don't line up. */
    fun texBox(c: VertexConsumer, m: Matrix4f, p: Array<Vec3>, sprite: Sprite, rgb: Int, alpha: Float, light: Int, seed: Int) {
        val random = Random(seed)
        for (face in SpellGeom.FACES) {
            texFace(
                c, m, p[face[0]], p[face[1]], p[face[2]], p[face[3]], sprite, rgb, alpha, light,
                random.nextInt(16) / 16.0, random.nextInt(16) / 16.0,
            )
        }
    }

    /**
     * A ribbon along [points] at Minecraft's scale: across it, as many texels as it is wide,
     * centred on texture column [uc] (0..1); along it, the texture repeats once per block.
     * [edges] are each point's half-width vectors, [alphas] each point's opacity.
     */
    fun stripPx(
        c: VertexConsumer, m: Matrix4f, points: List<Vec3>, edges: List<Vec3>, sprite: Sprite, rgb: Int,
        alphas: FloatArray, uc: Double, light: Int,
    ) {
        var s = 0.0
        for (i in 0 until points.size - 1) {
            val p0 = points[i]; val p1 = points[i + 1]
            val seg = p1.distanceTo(p0)
            if (seg < 1.0e-6) continue
            for (cut in texCuts(s, seg)) {
                val fa = cut[0] / seg; val fb = cut[1] / seg
                val a = p0.lerp(p1, fa); val b = p0.lerp(p1, fb)
                val ea = edges[i].lerp(edges[i + 1], fa); val eb = edges[i].lerp(edges[i + 1], fb)
                val wa = minOf(0.5, ea.length()); val wb = minOf(0.5, eb.length())
                val aa = alphas[i] + (alphas[i + 1] - alphas[i]) * fa.toFloat()
                val ab = alphas[i] + (alphas[i + 1] - alphas[i]) * fb.toFloat()
                val ca = uc.coerceIn(wa, 1 - wa); val cb = uc.coerceIn(wb, 1 - wb)
                val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val bl = rgb and 0xFF
                fun put(p: Vec3, u: Double, v: Double, al: Float) {
                    c.vertex(m, p.x.toFloat(), p.y.toFloat(), p.z.toFloat()).uv(sprite.u(u.toFloat()), sprite.v(v.toFloat()))
                        .color(r, g, bl, (al * 255).toInt().coerceIn(0, 255)).uv2(light).endVertex()
                }
                put(a.subtract(ea), ca - wa, 1 - cut[2], aa)
                put(a.add(ea), ca + wa, 1 - cut[2], aa)
                put(b.add(eb), cb + wb, 1 - cut[3], ab)
                put(b.subtract(eb), cb - wb, 1 - cut[3], ab)
            }
            s += seg
        }
    }

    /**
     * A leaf chip facing the camera: the oak leaves texture's own pixels in a 5x5 leaf outline
     * ([index] picks the outline and where in the texture it's cut from), [size] blocks across,
     * turned [roll]. Drawn on the block atlas.
     */
    fun leafChip(c: VertexConsumer, m: Matrix4f, view: SpellView, centre: Vec3, size: Double, roll: Double, index: Int, rgb: Int, alpha: Float, light: Int) {
        if (alpha <= 0.004f) return
        val leaves = SpellArt.block("oak_leaves")
        val mask = SpellArt.LEAF_MASKS[Math.floorMod(index, SpellArt.LEAF_MASKS.size)]
        val ox = Math.floorMod(index * 7, 11); val oy = Math.floorMod(index * 5 + 3, 11)
        val cr = cos(roll); val sr = sin(roll)
        val right = view.left.scale(-cr).add(view.up.scale(sr)); val up = view.up.scale(cr).add(view.left.scale(sr))
        val px = size / 5
        for (row in 0 until 5) for (col in 0 until 5) {
            val ch = mask[row][col]
            if (ch == '.') continue
            val tint = if (ch == 's') darken(rgb, 0.65f) else rgb
            val u0 = (ox + col) / 16f; val v0 = (oy + row) / 16f
            val l = (col - 2.5) * px; val t = (2.5 - row) * px
            fun at(x: Double, y: Double) = centre.add(right.scale(x)).add(up.scale(y))
            tquad(
                c, m, at(l, t - px), at(l + px, t - px), at(l + px, t), at(l, t), leaves,
                floatArrayOf(u0, v0 + 1 / 16f, u0 + 1 / 16f, v0 + 1 / 16f, u0 + 1 / 16f, v0, u0, v0), tint, alpha, light,
            )
        }
    }

    private fun darken(rgb: Int, f: Float): Int = SpellGeom.darken(rgb, f)
}
