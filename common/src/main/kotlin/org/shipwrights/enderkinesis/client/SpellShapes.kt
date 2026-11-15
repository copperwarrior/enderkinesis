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

class SpellView(val cam: Vec3, val left: Vec3, val up: Vec3)

/**
 * Untextured shapes need a position-colour consumer ([SpellVfxRenderTypes.SHADE] or [AegisRenderType.FACE_GLOW]).
 * Textured shapes need the [SpellVfxRenderTypes] sprite type for the texture of the [Sprite].
 */
object SpellShapes {
    private const val LAT = 10
    private const val LON = 16
    private const val RING_SEGMENTS = 32
    private const val DISC_SECTORS = 10
    private const val PUFF_LAT = 6
    private const val PUFF_LON = 10
    // Vec3.normalize gives ZERO below this length.
    private const val NORMALIZE_EPS = 1.0e-4
    private val SPHERE = UnitSphere(LAT, LON)
    private val PUFF = UnitSphere(PUFF_LAT, PUFF_LON)
    private val SPRITE_UV = floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f)

    fun quad(c: VertexConsumer, m: Matrix4f, p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, rgb: Int, a0: Float, a1: Float, a2: Float, a3: Float) {
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        vertex(c, m, p0, r, g, b, a0); vertex(c, m, p1, r, g, b, a1); vertex(c, m, p2, r, g, b, a2); vertex(c, m, p3, r, g, b, a3)
    }

    private fun vertex(c: VertexConsumer, m: Matrix4f, p: Vec3, r: Int, g: Int, b: Int, a: Float) = vertex(c, m, p.x, p.y, p.z, r, g, b, a)

    fun vertex(c: VertexConsumer, m: Matrix4f, x: Double, y: Double, z: Double, r: Int, g: Int, b: Int, a: Float) {
        c.vertex(m, x.toFloat(), y.toFloat(), z.toFloat()).color(r, g, b, (a * 255).toInt().coerceIn(0, 255)).endVertex()
    }

    /** A sphere whose opacity peaks at its silhouette, so it reads as a volume of light. */
    fun sphere(c: VertexConsumer, m: Matrix4f, view: SpellView, centre: Vec3, r: Double, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f || r <= 0.01) return
        sphereQuads(c, m, SPHERE, centre, r, rgb, alpha, view.cam)
    }

    fun sphereFlat(c: VertexConsumer, m: Matrix4f, centre: Vec3, r: Double, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f || r <= 0.01) return
        sphereQuads(c, m, SPHERE, centre, r, rgb, alpha, null)
    }

    fun puff(c: VertexConsumer, m: Matrix4f, view: SpellView, centre: Vec3, r: Double, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f || r <= 0.01) return
        val dx = centre.x - view.cam.x; val dy = centre.y - view.cam.y; val dz = centre.z - view.cam.z
        val len = Math.sqrt(dx * dx + dy * dy + dz * dz)
        val tx = if (len < NORMALIZE_EPS) 0.0 else dx / len
        val ty = if (len < NORMALIZE_EPS) 0.0 else dy / len
        val tz = if (len < NORMALIZE_EPS) 0.0 else dz / len
        val red = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        val s = PUFF
        for (i in 0 until PUFF_LAT) for (j in 0 until PUFF_LON) {
            puffVertex(c, m, s, s.index(i, j), centre, r, tx, ty, tz, red, g, b, alpha)
            puffVertex(c, m, s, s.index(i + 1, j), centre, r, tx, ty, tz, red, g, b, alpha)
            puffVertex(c, m, s, s.index(i + 1, j + 1), centre, r, tx, ty, tz, red, g, b, alpha)
            puffVertex(c, m, s, s.index(i, j + 1), centre, r, tx, ty, tz, red, g, b, alpha)
        }
    }

    private class UnitSphere(lat: Int, lon: Int) {
        private val stride = lon + 1
        val x = DoubleArray((lat + 1) * stride)
        val y = DoubleArray((lat + 1) * stride)
        val z = DoubleArray((lat + 1) * stride)

        init {
            for (i in 0..lat) for (j in 0..lon) {
                val theta = Math.PI * i / lat
                val phi = 2 * Math.PI * j / lon
                val k = index(i, j)
                x[k] = sin(theta) * cos(phi); y[k] = cos(theta); z[k] = sin(theta) * sin(phi)
            }
        }

        fun index(i: Int, j: Int) = i * stride + j
    }

    private fun sphereQuads(c: VertexConsumer, m: Matrix4f, s: UnitSphere, centre: Vec3, r: Double, rgb: Int, alpha: Float, cam: Vec3?) {
        val red = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        for (i in 0 until LAT) for (j in 0 until LON) {
            sphereVertex(c, m, s, s.index(i, j), centre, r, cam, red, g, b, alpha)
            sphereVertex(c, m, s, s.index(i + 1, j), centre, r, cam, red, g, b, alpha)
            sphereVertex(c, m, s, s.index(i + 1, j + 1), centre, r, cam, red, g, b, alpha)
            sphereVertex(c, m, s, s.index(i, j + 1), centre, r, cam, red, g, b, alpha)
        }
    }

    private fun sphereVertex(
        c: VertexConsumer, m: Matrix4f, s: UnitSphere, k: Int, centre: Vec3, r: Double, cam: Vec3?, red: Int, g: Int, b: Int, alpha: Float,
    ) {
        val nx = s.x[k]; val ny = s.y[k]; val nz = s.z[k]
        val px = centre.x + r * nx; val py = centre.y + r * ny; val pz = centre.z + r * nz
        val a = if (cam == null) alpha else alpha * rim(nx, ny, nz, px - cam.x, py - cam.y, pz - cam.z)
        vertex(c, m, px, py, pz, red, g, b, a)
    }

    private fun puffVertex(
        c: VertexConsumer, m: Matrix4f, s: UnitSphere, k: Int, centre: Vec3, r: Double,
        tx: Double, ty: Double, tz: Double, red: Int, g: Int, b: Int, alpha: Float,
    ) {
        val nx = s.x[k]; val ny = s.y[k]; val nz = s.z[k]
        val f = Math.abs(nx * tx + ny * ty + nz * tz).toFloat()
        vertex(c, m, centre.x + nx * r, centre.y + ny * r, centre.z + nz * r, red, g, b, alpha * f * f)
    }

    private fun rim(nx: Double, ny: Double, nz: Double, vx: Double, vy: Double, vz: Double): Float {
        val len = Math.sqrt(vx * vx + vy * vy + vz * vz)
        val d = if (len < NORMALIZE_EPS) 0.0 else (nx * vx + ny * vy + nz * vz) / len
        val edge = 1f - Math.abs(d).toFloat()
        return 0.12f + 0.88f * edge * edge
    }

    /** [tilt] turns the band about the x axis; 0 is level. */
    fun ring(c: VertexConsumer, m: Matrix4f, centre: Vec3, radius: Double, width: Double, rgb: Int, alpha: Float, tilt: Double) {
        if (alpha <= 0.004f || radius <= 0.01) return
        val ct = cos(tilt); val st = sin(tilt)
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        val inner = radius - width; val outer = radius + width
        var c0 = cos(0.0); var s0 = sin(0.0)
        for (k in 0 until RING_SEGMENTS) {
            val a1 = 2 * Math.PI * (k + 1) / RING_SEGMENTS
            val c1 = cos(a1); val s1 = sin(a1)
            ringVertex(c, m, centre, inner, c0, s0, ct, st, r, g, b, 0f)
            ringVertex(c, m, centre, inner, c1, s1, ct, st, r, g, b, 0f)
            ringVertex(c, m, centre, radius, c1, s1, ct, st, r, g, b, alpha)
            ringVertex(c, m, centre, radius, c0, s0, ct, st, r, g, b, alpha)
            ringVertex(c, m, centre, radius, c0, s0, ct, st, r, g, b, alpha)
            ringVertex(c, m, centre, radius, c1, s1, ct, st, r, g, b, alpha)
            ringVertex(c, m, centre, outer, c1, s1, ct, st, r, g, b, 0f)
            ringVertex(c, m, centre, outer, c0, s0, ct, st, r, g, b, 0f)
            c0 = c1; s0 = s1
        }
    }

    @Suppress("LongParameterList") // Primitive inputs keep the ring free of per-vertex Vec3 allocation.
    private fun ringVertex(
        c: VertexConsumer, m: Matrix4f, centre: Vec3, rr: Double, cosA: Double, sinA: Double, ct: Double, st: Double,
        r: Int, g: Int, b: Int, a: Float,
    ) {
        val x = rr * cosA; val z = rr * sinA
        vertex(c, m, centre.x + x, centre.y + -z * st, centre.z + z * ct, r, g, b, a)
    }

    fun disc(c: VertexConsumer, m: Matrix4f, view: SpellView, centre: Vec3, r: Double, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f) return
        val red = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        val l = view.left; val up = view.up
        var kc0 = cos(0.0) * r; var ks0 = sin(0.0) * r
        for (k in 0 until DISC_SECTORS) {
            val a1 = 2 * Math.PI * (k + 1) / DISC_SECTORS
            val kc1 = cos(a1) * r; val ks1 = sin(a1) * r
            vertex(c, m, centre, red, g, b, alpha)
            vertex(c, m, centre, red, g, b, alpha)
            vertex(c, m, centre.x + l.x * kc1 + up.x * ks1, centre.y + l.y * kc1 + up.y * ks1, centre.z + l.z * kc1 + up.z * ks1, red, g, b, 0f)
            vertex(c, m, centre.x + l.x * kc0 + up.x * ks0, centre.y + l.y * kc0 + up.y * ks0, centre.z + l.z * kc0 + up.z * ks0, red, g, b, 0f)
            kc0 = kc1; ks0 = ks1
        }
    }

    fun interface StripWidth {
        fun at(i: Int): Double
    }

    /**
     * Each joint shares its edge with both segments, so the bends have no gaps or overlaps.
     * [closed] also joins the last point back to the first, so a loop has no ends.
     */
    fun strip(
        c: VertexConsumer, m: Matrix4f, view: SpellView, points: List<Vec3>, width: StripWidth, rgb: Int, alpha: Float,
        soft: Boolean, alphas: FloatArray? = null, closed: Boolean = false,
    ) {
        val n = points.size
        if (n < 2 || alpha <= 0.004f) return
        val cam = view.cam
        val sides = DoubleArray(3 * n)
        for (i in 0 until n) {
            val next = if (closed) points[(i + 1) % n] else points[minOf(n - 1, i + 1)]
            val prev = if (closed) points[(i - 1 + n) % n] else points[maxOf(0, i - 1)]
            val p = points[i]
            val dx = next.x - prev.x; val dy = next.y - prev.y; val dz = next.z - prev.z
            val vx = p.x - cam.x; val vy = p.y - cam.y; val vz = p.z - cam.z
            val sx = dy * vz - dz * vy; val sy = dz * vx - dx * vz; val sz = dx * vy - dy * vx
            val lenSqr = sx * sx + sy * sy + sz * sz
            if (lenSqr < 1.0e-12) continue
            val len = Math.sqrt(lenSqr)
            val h = width.at(i) * 0.5
            sides[3 * i] = unitComponent(sx, len) * h
            sides[3 * i + 1] = unitComponent(sy, len) * h
            sides[3 * i + 2] = unitComponent(sz, len) * h
        }
        val red = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        for (i in 0 until if (closed) n else n - 1) {
            val j = (i + 1) % n
            val p0 = points[i]; val p1 = points[j]
            val a0 = alpha * (if (alphas != null) alphas[i] else 1f); val a1 = alpha * (if (alphas != null) alphas[j] else 1f)
            val e0 = if (soft) 0f else a0; val e1 = if (soft) 0f else a1
            val ix = sides[3 * i]; val iy = sides[3 * i + 1]; val iz = sides[3 * i + 2]
            val jx = sides[3 * j]; val jy = sides[3 * j + 1]; val jz = sides[3 * j + 2]
            vertex(c, m, p0, red, g, b, a0)
            vertex(c, m, p1, red, g, b, a1)
            vertex(c, m, p1.x + jx, p1.y + jy, p1.z + jz, red, g, b, e1)
            vertex(c, m, p0.x + ix, p0.y + iy, p0.z + iz, red, g, b, e0)
            vertex(c, m, p0.x - ix, p0.y - iy, p0.z - iz, red, g, b, e0)
            vertex(c, m, p1.x - jx, p1.y - jy, p1.z - jz, red, g, b, e1)
            vertex(c, m, p1, red, g, b, a1)
            vertex(c, m, p0, red, g, b, a0)
        }
    }

    // Same cut-off as Vec3.normalize, so the output matches it bit for bit.
    private fun unitComponent(v: Double, len: Double): Double = if (len < NORMALIZE_EPS) 0.0 else v / len

    fun glowCubeFaces(c: VertexConsumer, m: Matrix4f, p: Array<Vec3>, rgb: Int, edgeAlpha: Float, inset: Double = 0.35) {
        if (edgeAlpha <= 0.004f) return
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        for (face in SpellGeom.FACES) {
            val f0 = p[face[0]]; val f1 = p[face[1]]; val f2 = p[face[2]]; val f3 = p[face[3]]
            val cx = (0.0 + f0.x + f1.x + f2.x + f3.x) * 0.25
            val cy = (0.0 + f0.y + f1.y + f2.y + f3.y) * 0.25
            val cz = (0.0 + f0.z + f1.z + f2.z + f3.z) * 0.25
            for (k in 0 until 4) {
                val e0 = p[face[k]]; val e1 = p[face[(k + 1) % 4]]
                vertex(c, m, e0, r, g, b, edgeAlpha)
                vertex(c, m, e1, r, g, b, edgeAlpha)
                vertex(c, m, e1.x + inset * (cx - e1.x), e1.y + inset * (cy - e1.y), e1.z + inset * (cz - e1.z), r, g, b, 0f)
                vertex(c, m, e0.x + inset * (cx - e0.x), e0.y + inset * (cy - e0.y), e0.z + inset * (cz - e0.z), r, g, b, 0f)
            }
        }
    }

    /** Needs a [net.minecraft.client.renderer.RenderType.lines] consumer. */
    fun wire(c: VertexConsumer, pose: PoseStack.Pose, p: Array<Vec3>, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f) return
        for ((i, j) in SpellGeom.EDGES) line(c, pose, p[i], p[j], rgb, alpha)
    }

    fun line(c: VertexConsumer, pose: PoseStack.Pose, a: Vec3, b: Vec3, rgb: Int, alpha: Float) {
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val bl = rgb and 0xFF
        val al = (alpha * 255).toInt().coerceIn(0, 255)
        val dx = b.x - a.x; val dy = b.y - a.y; val dz = b.z - a.z
        val len = Math.sqrt(dx * dx + dy * dy + dz * dz)
        val nx = unitComponent(dx, len).toFloat(); val ny = unitComponent(dy, len).toFloat(); val nz = unitComponent(dz, len).toFloat()
        c.vertex(pose.pose(), a.x.toFloat(), a.y.toFloat(), a.z.toFloat()).color(r, g, bl, al).normal(pose.normal(), nx, ny, nz).endVertex()
        c.vertex(pose.pose(), b.x.toFloat(), b.y.toFloat(), b.z.toFloat()).color(r, g, bl, al).normal(pose.normal(), nx, ny, nz).endVertex()
    }

    fun lightAt(level: ClientLevel, p: Vec3): Int = LevelRenderer.getLightColor(level, BlockPos.containing(p))

    /** [uv] holds u, v per corner as fractions of [sprite]; v = 0 is the top of the image. */
    fun tquad(
        c: VertexConsumer, m: Matrix4f, p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, sprite: Sprite,
        uv: FloatArray, rgb: Int, alpha: Float, light: Int,
    ) {
        if (alpha <= 0.004f) return
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        tvertex(c, m, p0.x, p0.y, p0.z, sprite, uv[0], uv[1], r, g, b, a, light)
        tvertex(c, m, p1.x, p1.y, p1.z, sprite, uv[2], uv[3], r, g, b, a, light)
        tvertex(c, m, p2.x, p2.y, p2.z, sprite, uv[4], uv[5], r, g, b, a, light)
        tvertex(c, m, p3.x, p3.y, p3.z, sprite, uv[6], uv[7], r, g, b, a, light)
    }

    private fun tvertex(
        c: VertexConsumer, m: Matrix4f, x: Double, y: Double, z: Double, sprite: Sprite, u: Float, v: Float,
        r: Int, g: Int, b: Int, a: Int, light: Int,
    ) {
        c.vertex(m, x.toFloat(), y.toFloat(), z.toFloat()).uv(sprite.u(u), sprite.v(v)).color(r, g, b, a).uv2(light).endVertex()
    }

    fun sprite(c: VertexConsumer, m: Matrix4f, view: SpellView, centre: Vec3, size: Double, sprite: Sprite, rgb: Int, alpha: Float, light: Int, roll: Double = 0.0) {
        if (alpha <= 0.004f || size <= 0.0) return
        val h = size / 2; val cr = cos(roll); val sr = sin(roll)
        val l = view.left; val up = view.up
        val lx = (l.x * cr + up.x * sr) * h; val ly = (l.y * cr + up.y * sr) * h; val lz = (l.z * cr + up.z * sr) * h
        val ux = (up.x * cr - l.x * sr) * h; val uy = (up.y * cr - l.y * sr) * h; val uz = (up.z * cr - l.z * sr) * h
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val uv = SPRITE_UV
        tvertex(c, m, centre.x + lx - ux, centre.y + ly - uy, centre.z + lz - uz, sprite, uv[0], uv[1], r, g, b, a, light)
        tvertex(c, m, centre.x - lx - ux, centre.y - ly - uy, centre.z - lz - uz, sprite, uv[2], uv[3], r, g, b, a, light)
        tvertex(c, m, centre.x - lx + ux, centre.y - ly + uy, centre.z - lz + uz, sprite, uv[4], uv[5], r, g, b, a, light)
        tvertex(c, m, centre.x + lx + ux, centre.y + ly + uy, centre.z + lz + uz, sprite, uv[6], uv[7], r, g, b, a, light)
    }

    /** Cuts [0, length] at each block-sized texture repeat; [piece] gets from, to, texture from, texture to. */
    private inline fun forTexCuts(offset: Double, length: Double, piece: (Double, Double, Double, Double) -> Unit) {
        var x = 0.0
        while (x < length - 1.0e-6) {
            val t = offset + x
            val base = floor(t + 1.0e-9)
            val next = minOf(length, base + 1 - offset)
            piece(x, next, t - base, offset + next - base)
            x = next
        }
    }

    /** The texture repeats at 16 texels per block instead of stretching; [ou], [ov] offset its start. */
    fun texFace(
        c: VertexConsumer, m: Matrix4f, p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, sprite: Sprite,
        rgb: Int, alpha: Float, light: Int, ou: Double = 0.0, ov: Double = 0.0,
    ) {
        val lu = p1.distanceTo(p0); val lv = p3.distanceTo(p0)
        if (lu < 1.0e-6 || lv < 1.0e-6 || alpha <= 0.004f) return
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        forTexCuts(ou, lu) { u0, u1, tu0, tu1 ->
            val s0 = u0 / lu; val s1 = u1 / lu
            val fu0 = tu0.toFloat(); val fu1 = tu1.toFloat()
            forTexCuts(ov, lv) { v0, v1, tv0, tv1 ->
                val t0 = v0 / lv; val t1 = v1 / lv
                val fv0 = (1 - tv0).toFloat(); val fv1 = (1 - tv1).toFloat()
                faceVertex(c, m, p0, p1, p2, p3, s0, t0, sprite, fu0, fv0, r, g, b, a, light)
                faceVertex(c, m, p0, p1, p2, p3, s1, t0, sprite, fu1, fv0, r, g, b, a, light)
                faceVertex(c, m, p0, p1, p2, p3, s1, t1, sprite, fu1, fv1, r, g, b, a, light)
                faceVertex(c, m, p0, p1, p2, p3, s0, t1, sprite, fu0, fv1, r, g, b, a, light)
            }
        }
    }

    private fun faceVertex(
        c: VertexConsumer, m: Matrix4f, p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, s: Double, t: Double, sprite: Sprite,
        u: Float, v: Float, r: Int, g: Int, b: Int, a: Int, light: Int,
    ) {
        val ax = p0.x + s * (p1.x - p0.x); val ay = p0.y + s * (p1.y - p0.y); val az = p0.z + s * (p1.z - p0.z)
        val bx = p3.x + s * (p2.x - p3.x); val by = p3.y + s * (p2.y - p3.y); val bz = p3.z + s * (p2.z - p3.z)
        tvertex(c, m, ax + t * (bx - ax), ay + t * (by - ay), az + t * (bz - az), sprite, u, v, r, g, b, a, light)
    }

    /** Corners as [SpellGeom.cubeCorners]; random face offsets keep the texture repeats from lining up. */
    fun texBox(c: VertexConsumer, m: Matrix4f, p: Array<Vec3>, sprite: Sprite, rgb: Int, alpha: Float, light: Int, seed: Int) {
        val random = Random(seed)
        for (face in SpellGeom.FACES) {
            texFace(
                c, m, p[face[0]], p[face[1]], p[face[2]], p[face[3]], sprite, rgb, alpha, light,
                random.nextInt(16) / 16.0, random.nextInt(16) / 16.0,
            )
        }
    }

    /** Across, as many texels as the ribbon is wide, centred on column [uc]; [edges] are half-width vectors. */
    fun stripPx(
        c: VertexConsumer, m: Matrix4f, points: List<Vec3>, edges: List<Vec3>, sprite: Sprite, rgb: Int,
        alphas: FloatArray, uc: Double, light: Int,
    ) {
        var s = 0.0
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val bl = rgb and 0xFF
        for (i in 0 until points.size - 1) {
            val p0 = points[i]; val p1 = points[i + 1]
            val q0 = edges[i]; val q1 = edges[i + 1]
            val seg = p1.distanceTo(p0)
            if (seg < 1.0e-6) continue
            forTexCuts(s, seg) { from, to, tex0, tex1 ->
                val fa = from / seg; val fb = to / seg
                val ax = p0.x + fa * (p1.x - p0.x); val ay = p0.y + fa * (p1.y - p0.y); val az = p0.z + fa * (p1.z - p0.z)
                val bx = p0.x + fb * (p1.x - p0.x); val by = p0.y + fb * (p1.y - p0.y); val bz = p0.z + fb * (p1.z - p0.z)
                val eax = q0.x + fa * (q1.x - q0.x); val eay = q0.y + fa * (q1.y - q0.y); val eaz = q0.z + fa * (q1.z - q0.z)
                val ebx = q0.x + fb * (q1.x - q0.x); val eby = q0.y + fb * (q1.y - q0.y); val ebz = q0.z + fb * (q1.z - q0.z)
                val wa = minOf(0.5, Math.sqrt(eax * eax + eay * eay + eaz * eaz))
                val wb = minOf(0.5, Math.sqrt(ebx * ebx + eby * eby + ebz * ebz))
                val aa = (alphas[i] + (alphas[i + 1] - alphas[i]) * fa.toFloat()).let { (it * 255).toInt().coerceIn(0, 255) }
                val ab = (alphas[i] + (alphas[i + 1] - alphas[i]) * fb.toFloat()).let { (it * 255).toInt().coerceIn(0, 255) }
                val ca = uc.coerceIn(wa, 1 - wa); val cb = uc.coerceIn(wb, 1 - wb)
                val va = (1 - tex0).toFloat(); val vb = (1 - tex1).toFloat()
                tvertex(c, m, ax - eax, ay - eay, az - eaz, sprite, (ca - wa).toFloat(), va, r, g, bl, aa, light)
                tvertex(c, m, ax + eax, ay + eay, az + eaz, sprite, (ca + wa).toFloat(), va, r, g, bl, aa, light)
                tvertex(c, m, bx + ebx, by + eby, bz + ebz, sprite, (cb + wb).toFloat(), vb, r, g, bl, ab, light)
                tvertex(c, m, bx - ebx, by - eby, bz - ebz, sprite, (cb - wb).toFloat(), vb, r, g, bl, ab, light)
            }
            s += seg
        }
    }

    /** Needs the block atlas. [index] picks the 5x5 leaf outline and its cut position in the oak leaves texture. */
    fun leafChip(c: VertexConsumer, m: Matrix4f, view: SpellView, centre: Vec3, size: Double, roll: Double, index: Int, rgb: Int, alpha: Float, light: Int) {
        if (alpha <= 0.004f) return
        val leaves = SpellArt.block("oak_leaves")
        val mask = SpellArt.LEAF_MASKS[Math.floorMod(index, SpellArt.LEAF_MASKS.size)]
        val ox = Math.floorMod(index * 7, 11); val oy = Math.floorMod(index * 5 + 3, 11)
        val cr = cos(roll); val sr = sin(roll)
        val right = view.left.scale(-cr).add(view.up.scale(sr)); val up = view.up.scale(cr).add(view.left.scale(sr))
        val px = size / 5
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        for (row in 0 until 5) for (col in 0 until 5) {
            val ch = mask[row][col]
            if (ch == '.') continue
            val tint = if (ch == 's') darken(rgb, 0.65f) else rgb
            val r = (tint shr 16) and 0xFF; val g = (tint shr 8) and 0xFF; val b = tint and 0xFF
            val u0 = (ox + col) / 16f; val v0 = (oy + row) / 16f
            val u1 = u0 + 1 / 16f; val v1 = v0 + 1 / 16f
            val l = (col - 2.5) * px; val t = (2.5 - row) * px
            chipVertex(c, m, centre, right, up, l, t - px, leaves, u0, v1, r, g, b, a, light)
            chipVertex(c, m, centre, right, up, l + px, t - px, leaves, u1, v1, r, g, b, a, light)
            chipVertex(c, m, centre, right, up, l + px, t, leaves, u1, v0, r, g, b, a, light)
            chipVertex(c, m, centre, right, up, l, t, leaves, u0, v0, r, g, b, a, light)
        }
    }

    private fun chipVertex(
        c: VertexConsumer, m: Matrix4f, centre: Vec3, right: Vec3, up: Vec3, x: Double, y: Double, sprite: Sprite,
        u: Float, v: Float, r: Int, g: Int, b: Int, a: Int, light: Int,
    ) = tvertex(
        c, m, centre.x + right.x * x + up.x * y, centre.y + right.y * x + up.y * y, centre.z + right.z * x + up.z * y,
        sprite, u, v, r, g, b, a, light,
    )

    private fun darken(rgb: Int, f: Float): Int = SpellGeom.darken(rgb, f)
}
