package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import org.shipwrights.enderkinesis.item.MysticScrollItem
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Shape
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.shipwrights.enderkinesis.spell.SpellVfx
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws [SpellVfx] visuals as geometry: rim-lit shells of light, dark imploding spheres,
 * Aegis-style cubes, branching lightning and flowing streams, all built from coloured quads
 * and lines with no textures. Also emits what held casts look like from the caster's hand:
 * a spray's mist, and a Bolt gathering as it charges.
 *
 * Drawn from the same world-render hook as the Staff of Sundering, one render type at a
 * time (an immediate buffer source reuses one builder per type).
 */
object SpellVfxClient {

    private class Vfx(
        val kind: SpellVfx.Kind, val a: Vec3, val b: Vec3, val size: Float,
        val rgb: Int, val life: Int, val seed: Int, val start: Long,
    )

    // Render thread only.
    private val active = ArrayList<Vfx>()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel(::tick))
    }

    fun add(kind: Int, a: Vec3, b: Vec3, size: Float, rgb: Int, life: Int, seed: Int) {
        val level = Minecraft.getInstance().level ?: return
        val k = SpellVfx.Kind.entries.getOrNull(kind) ?: return
        active += Vfx(k, a, b, size, rgb, life.coerceAtLeast(1), seed, level.gameTime)
    }

    fun reset() = active.clear()

    // -----------------------------------------------------------------------
    // Per tick: expiry, spray mists, Bolt charge
    // -----------------------------------------------------------------------

    private fun tick(level: ClientLevel) {
        val now = level.gameTime
        active.removeIf { now - it.start > it.life }
        for (player in level.players()) {
            if (!player.isUsingItem) continue
            val spell = MysticScrollItem.spellOf(player.useItem) ?: continue
            when (spell.shape) {
                Shape.SPRAY -> sprayMist(level, player, spell.magnitudes.keys, spell.magnitude(spell.shapeElement!!))
                Shape.BOLT -> boltCharge(level, player, spell.shapeElement!!)
                else -> {}
            }
        }
    }

    /** A spray mist from the caster's hand: each element's own stuff flung out in a cone. */
    private fun sprayMist(level: ClientLevel, player: Player, elements: Set<Element>, magnitude: Float) {
        val origin = SpellCasting.handOrigin(player, player.usedItemHand)
        val aim = SpellCasting.handAim(player, origin)
        val speed = SpellCasting.sprayLength(magnitude) / MIST_FLIGHT_TICKS
        for (element in elements) {
            for ((particle, count) in mistOf(element)) repeat(count) {
                val dir = jitter(level, aim, SPRAY_SPREAD)
                val v = dir.scale(speed * (0.6 + 0.6 * level.random.nextDouble()))
                level.addParticle(particle, origin.x, origin.y, origin.z, v.x, v.y, v.z)
            }
        }
    }

    private fun mistOf(element: Element): List<Pair<ParticleOptions, Int>> = when (element) {
        Element.BLOOD -> listOf(DustParticleOptions(Vector3f(0.55f, 0.02f, 0.04f), 1.8f) to 7, EKParticles.bloodDrip() to 2)
        Element.HEAT -> listOf(ParticleTypes.FLAME to 7, ParticleTypes.SMALL_FLAME to 3, ParticleTypes.SMOKE to 1)
        Element.COLD -> listOf(ParticleTypes.SNOWFLAKE to 5, DustParticleOptions(Vector3f(0.8f, 0.92f, 1f), 1.3f) to 3)
        Element.POISON -> listOf(EKParticles.rotSpore() to 4, dust(Element.POISON.color, 1.5f) to 4)
        else -> listOf(dust(element.color, 1.1f) to 2)
    }

    /** Motes drawn into the hand while a Bolt charges; the growing orb is drawn in [renderAll]. */
    private fun boltCharge(level: ClientLevel, player: Player, element: Element) {
        val hand = SpellCasting.handOrigin(player, player.usedItemHand)
        repeat(2) {
            val off = Vec3(level.random.nextGaussian(), level.random.nextGaussian(), level.random.nextGaussian()).normalize().scale(0.6)
            val from = hand.add(off)
            val v = off.scale(-1.0 / CHARGE_MOTE_TICKS)
            level.addParticle(dust(element.color, 0.7f), from.x, from.y, from.z, v.x, v.y, v.z)
        }
    }

    private fun dust(rgb: Int, size: Float) =
        DustParticleOptions(Vector3f(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f), size)

    private fun jitter(level: ClientLevel, dir: Vec3, spread: Double): Vec3 =
        dir.add(level.random.nextGaussian() * spread, level.random.nextGaussian() * spread, level.random.nextGaussian() * spread).normalize()

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    private class View(val cam: Vec3, val left: Vec3, val up: Vec3)

    @JvmStatic
    fun renderAll(pose: PoseStack, buffers: MultiBufferSource, camX: Double, camY: Double, camZ: Double, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val camera = mc.gameRenderer.mainCamera
        val view = View(Vec3(camX, camY, camZ), Vec3(camera.leftVector), Vec3(camera.upVector))
        val time = level.gameTime + partialTick
        val charging = level.players().filter { p ->
            p.isUsingItem && MysticScrollItem.spellOf(p.useItem)?.shape == Shape.BOLT
        }
        if (active.isEmpty() && charging.isEmpty()) return
        pose.pushPose()
        pose.translate(-camX, -camY, -camZ)
        val matrix = pose.last().pose()

        // Dark volumes first, so light drawn after glows over them.
        val shade = buffers.getBuffer(SpellVfxRenderTypes.SHADE)
        for (vfx in active) if (vfx.kind == SpellVfx.Kind.IMPLODE) implodeShade(shade, matrix, vfx, t(vfx, time))

        val glow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        for (vfx in active) {
            val t = t(vfx, time)
            when (vfx.kind) {
                SpellVfx.Kind.SHELL -> shell(glow, matrix, view, vfx, t)
                SpellVfx.Kind.IMPLODE -> implodeGlow(glow, matrix, view, vfx, t, time)
                SpellVfx.Kind.AEGIS -> aegisFaces(glow, matrix, vfx, t)
                SpellVfx.Kind.ARC -> arc(glow, matrix, view, vfx, t, time)
                SpellVfx.Kind.STREAM -> stream(glow, matrix, view, vfx, t)
                SpellVfx.Kind.FLASH -> flash(glow, matrix, view, vfx, t)
            }
        }
        for (player in charging) chargeOrb(glow, matrix, view, player, partialTick, time)

        val lines = buffers.getBuffer(RenderType.lines())
        for (vfx in active) if (vfx.kind == SpellVfx.Kind.AEGIS) aegisWire(lines, matrix, vfx, t(vfx, time))
        pose.popPose()
    }

    private fun t(vfx: Vfx, time: Float): Float = ((time - vfx.start) / vfx.life).coerceIn(0f, 1f)

    private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

    // ---- Shell: an expanding sphere of light, brightest at its rim, with a ground ring -------

    private fun shell(c: VertexConsumer, m: Matrix4f, view: View, vfx: Vfx, t: Float) {
        val r = vfx.size * (0.15f + 0.85f * easeOut(minOf(1f, t * 1.4f)))
        val fade = (1f - t).let { it * it }
        sphere(c, m, view, vfx.a, r.toDouble(), vfx.rgb, 0.75f * fade)
        sphere(c, m, view, vfx.a, r * 0.6, whiten(vfx.rgb, 0.4f), 0.35f * fade)
        ring(c, m, vfx.a, r * 1.1, r * 0.08, vfx.rgb, 0.6f * fade, 0.0)
    }

    // ---- Implode: a dark sphere collapsing, rings spinning into it ---------------------------

    private fun implodeShade(c: VertexConsumer, m: Matrix4f, vfx: Vfx, t: Float) {
        val r = vfx.size * (1f - t).let { Math.pow(it.toDouble(), 0.7).toFloat() }
        val dark = darken(vfx.rgb, 0.2f)
        // A plain shaded sphere: no rim weighting, just a body of darkness.
        sphereFlat(c, m, vfx.a, r.toDouble(), dark, 0.55f * (1f - t * 0.6f))
    }

    private fun implodeGlow(c: VertexConsumer, m: Matrix4f, view: View, vfx: Vfx, t: Float, time: Float) {
        val r = vfx.size * (1f - t)
        sphere(c, m, view, vfx.a, r.toDouble(), vfx.rgb, 0.5f * (1f - t))
        for (i in 0 until 3) {
            val tilt = (i * 60.0 + time * 6.0) * Mth.DEG_TO_RAD
            ring(c, m, vfx.a, r * (0.8 + 0.25 * i), r * 0.05, vfx.rgb, 0.7f * (1f - t), tilt)
        }
    }

    // ---- Aegis: a turning, growing cube of glowing faces and a wireframe ---------------------

    private fun aegisCorners(vfx: Vfx, t: Float): Array<Vec3> {
        val half = vfx.size * 0.5 * (0.4 + 0.6 * easeOut(t))
        val yaw = t * Math.PI / 2 + vfx.seed % 7
        val cy = cos(yaw); val sy = sin(yaw)
        return Array(8) { i ->
            val x = if (i and 1 != 0) half else -half
            val y = if (i and 2 != 0) half else -half
            val z = if (i and 4 != 0) half else -half
            vfx.a.add(x * cy - z * sy, y, x * sy + z * cy)
        }
    }

    private val FACES = arrayOf(
        intArrayOf(0, 1, 3, 2), intArrayOf(4, 5, 7, 6), intArrayOf(0, 1, 5, 4),
        intArrayOf(2, 3, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 3, 7, 5),
    )
    private val EDGES = arrayOf(
        intArrayOf(0, 1), intArrayOf(2, 3), intArrayOf(4, 5), intArrayOf(6, 7),
        intArrayOf(0, 2), intArrayOf(1, 3), intArrayOf(4, 6), intArrayOf(5, 7),
        intArrayOf(0, 4), intArrayOf(1, 5), intArrayOf(2, 6), intArrayOf(3, 7),
    )

    private fun aegisFaces(c: VertexConsumer, m: Matrix4f, vfx: Vfx, t: Float) {
        val p = aegisCorners(vfx, t)
        val a = 0.3f * (1f - t)
        for (face in FACES) {
            // Bright edges fading toward the face centre, as the Staff of Aegis draws.
            val centre = face.fold(Vec3.ZERO) { acc, i -> acc.add(p[i]) }.scale(0.25)
            for (k in 0 until 4) {
                val e0 = p[face[k]]; val e1 = p[face[(k + 1) % 4]]
                val i0 = e0.lerp(centre, AEGIS_INSET); val i1 = e1.lerp(centre, AEGIS_INSET)
                quad(c, m, e0, e1, i1, i0, vfx.rgb, a, a, 0f, 0f)
            }
        }
    }

    private fun aegisWire(c: VertexConsumer, m: Matrix4f, vfx: Vfx, t: Float) {
        val p = aegisCorners(vfx, t)
        val alpha = ((1f - t) * 255).toInt()
        val (r, g, b) = rgb(whiten(vfx.rgb, 0.3f))
        for ((i, j) in EDGES) {
            val d = p[j].subtract(p[i]).normalize()
            c.vertex(m, p[i].x.toFloat(), p[i].y.toFloat(), p[i].z.toFloat()).color(r, g, b, alpha).normal(d.x.toFloat(), d.y.toFloat(), d.z.toFloat()).endVertex()
            c.vertex(m, p[j].x.toFloat(), p[j].y.toFloat(), p[j].z.toFloat()).color(r, g, b, alpha).normal(d.x.toFloat(), d.y.toFloat(), d.z.toFloat()).endVertex()
        }
    }

    // ---- Lightning: a jagged, branching bolt that re-strikes every couple of ticks -----------

    private fun arc(c: VertexConsumer, m: Matrix4f, view: View, vfx: Vfx, t: Float, time: Float) {
        val strike = (time / ARC_RESTRIKE_TICKS).toInt()
        val random = Random(vfx.seed * 31 + strike)
        val flicker = 0.55f + 0.45f * random.nextFloat()
        val fade = (1f - t) * flicker
        val main = jagged(random, vfx.a, vfx.b, ARC_JAGGEDNESS)
        bolt(c, m, view, main, vfx.rgb, fade, ARC_CORE_WIDTH, ARC_HALO_WIDTH)
        repeat(2) {
            val from = main[1 + random.nextInt((main.size - 2).coerceAtLeast(1))]
            val span = vfx.b.subtract(vfx.a)
            val branchDir = span.normalize().add(Vec3(random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5)).normalize()
            val to = from.add(branchDir.scale(span.length() * (0.2 + 0.25 * random.nextDouble())))
            bolt(c, m, view, jagged(random, from, to, ARC_JAGGEDNESS), vfx.rgb, fade * 0.6f, ARC_CORE_WIDTH * 0.6, ARC_HALO_WIDTH * 0.6)
        }
    }

    /** Points from [a] to [b] displaced sideways at random, most in the middle. */
    private fun jagged(random: Random, a: Vec3, b: Vec3, jaggedness: Double): List<Vec3> {
        val span = b.subtract(a)
        val length = span.length()
        val dir = if (length < 1.0e-6) Vec3(0.0, 1.0, 0.0) else span.scale(1.0 / length)
        val side1 = dir.cross(if (Math.abs(dir.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)).normalize()
        val side2 = dir.cross(side1)
        val steps = (length * 3).toInt().coerceIn(6, 28)
        return List(steps + 1) { i ->
            val f = i.toDouble() / steps
            if (i == 0 || i == steps) a.lerp(b, f)
            else {
                val amp = length * jaggedness * sin(Math.PI * f)
                a.lerp(b, f).add(side1.scale((random.nextDouble() - 0.5) * 2 * amp)).add(side2.scale((random.nextDouble() - 0.5) * 2 * amp))
            }
        }
    }

    private fun bolt(c: VertexConsumer, m: Matrix4f, view: View, points: List<Vec3>, rgb: Int, alpha: Float, core: Double, halo: Double) {
        for (i in 0 until points.size - 1) {
            ribbon(c, m, view, points[i], points[i + 1], halo, rgb, alpha * 0.35f, alpha * 0.35f)
            ribbon(c, m, view, points[i], points[i + 1], core, whiten(rgb, 0.75f), alpha, alpha)
        }
    }

    // ---- Stream: beads of light flowing along a rising curve from a to b ----------------------

    private fun stream(c: VertexConsumer, m: Matrix4f, view: View, vfx: Vfx, t: Float) {
        val lift = vfx.a.distanceTo(vfx.b) * 0.25
        val control = vfx.a.lerp(vfx.b, 0.5).add(0.0, lift, 0.0)
        val fade = 1f - t * t
        // A faint thread along the path, then the beads.
        var prev = vfx.a
        for (i in 1..STREAM_STEPS) {
            val p = bezier(vfx.a, control, vfx.b, i.toDouble() / STREAM_STEPS)
            ribbon(c, m, view, prev, p, 0.05, vfx.rgb, 0.18f * fade, 0.18f * fade)
            prev = p
        }
        for (i in 0 until STREAM_BEADS) {
            val u = ((t * STREAM_SPEED + i.toFloat() / STREAM_BEADS) % 1f).toDouble()
            val p = bezier(vfx.a, control, vfx.b, u)
            disc(c, m, view, p, 0.09 + 0.05 * sin(u * Math.PI), vfx.rgb, 0.85f * fade)
        }
    }

    private fun bezier(a: Vec3, c: Vec3, b: Vec3, u: Double): Vec3 = a.lerp(c, u).lerp(c.lerp(b, u), u)

    // ---- Flash and the charging Bolt ----------------------------------------------------------

    private fun flash(c: VertexConsumer, m: Matrix4f, view: View, vfx: Vfx, t: Float) {
        val r = vfx.size * (0.6 + 0.8 * t)
        val a = (1f - t) * (1f - t)
        disc(c, m, view, vfx.a, r, vfx.rgb, 0.8f * a)
        disc(c, m, view, vfx.a, r * 0.4, 0xFFFFFF, a)
    }

    private fun chargeOrb(c: VertexConsumer, m: Matrix4f, view: View, player: Player, partialTick: Float, time: Float) {
        val spell = MysticScrollItem.spellOf(player.useItem) ?: return
        val rgb = spell.tint()
        val charge = ((player.ticksUsingItem + partialTick) / BOLT_FULL_CHARGE_TICKS).coerceIn(0f, 1f)
        val hand = SpellCasting.handOrigin(player, player.usedItemHand, partialTick)
        val pulse = 1f + 0.12f * Mth.sin(time * 0.6f)
        val r = (0.06 + 0.16 * charge) * pulse
        disc(c, m, view, hand, r * 2.4, rgb, 0.35f + 0.3f * charge)
        disc(c, m, view, hand, r, whiten(rgb, 0.6f), 0.9f)
        // Motes circling the gathering bolt.
        for (i in 0 until 5) {
            val angle = time * 0.25 + i * (2 * Math.PI / 5)
            val p = hand.add(view.left.scale(cos(angle) * r * 2.2)).add(view.up.scale(sin(angle) * r * 2.2))
            disc(c, m, view, p, 0.025 + 0.02 * charge, rgb, 0.8f)
        }
    }

    // -----------------------------------------------------------------------
    // Geometry
    // -----------------------------------------------------------------------

    /** A sphere whose opacity peaks at its silhouette, so it reads as a volume of light. */
    private fun sphere(c: VertexConsumer, m: Matrix4f, view: View, centre: Vec3, r: Double, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f || r <= 0.01) return
        for (i in 0 until LAT) for (j in 0 until LON) {
            val p = arrayOf(
                spherePoint(centre, r, i, j), spherePoint(centre, r, i + 1, j),
                spherePoint(centre, r, i + 1, j + 1), spherePoint(centre, r, i, j + 1),
            )
            val a = FloatArray(4) { k -> alpha * rim(p[k], centre, view.cam) }
            quad(c, m, p[0], p[1], p[2], p[3], rgb, a[0], a[1], a[2], a[3])
        }
    }

    private fun sphereFlat(c: VertexConsumer, m: Matrix4f, centre: Vec3, r: Double, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f || r <= 0.01) return
        for (i in 0 until LAT) for (j in 0 until LON) {
            quad(
                c, m, spherePoint(centre, r, i, j), spherePoint(centre, r, i + 1, j),
                spherePoint(centre, r, i + 1, j + 1), spherePoint(centre, r, i, j + 1), rgb, alpha, alpha, alpha, alpha,
            )
        }
    }

    private fun spherePoint(centre: Vec3, r: Double, i: Int, j: Int): Vec3 {
        val theta = Math.PI * i / LAT
        val phi = 2 * Math.PI * j / LON
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
    private fun ring(c: VertexConsumer, m: Matrix4f, centre: Vec3, radius: Double, width: Double, rgb: Int, alpha: Float, tilt: Double) {
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
    private fun disc(c: VertexConsumer, m: Matrix4f, view: View, centre: Vec3, r: Double, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f) return
        for (k in 0 until DISC_SECTORS) {
            val a0 = 2 * Math.PI * k / DISC_SECTORS
            val a1 = 2 * Math.PI * (k + 1) / DISC_SECTORS
            val e0 = centre.add(view.left.scale(cos(a0) * r)).add(view.up.scale(sin(a0) * r))
            val e1 = centre.add(view.left.scale(cos(a1) * r)).add(view.up.scale(sin(a1) * r))
            quad(c, m, centre, centre, e1, e0, rgb, alpha, alpha, 0f, 0f)
        }
    }

    /** A strip from [p0] to [p1] turned to face the camera, [width] wide. */
    private fun ribbon(c: VertexConsumer, m: Matrix4f, view: View, p0: Vec3, p1: Vec3, width: Double, rgb: Int, a0: Float, a1: Float) {
        val mid = p0.lerp(p1, 0.5)
        val side = p1.subtract(p0).cross(mid.subtract(view.cam))
        if (side.lengthSqr() < 1.0e-9) return
        val s = side.normalize().scale(width * 0.5)
        quad(c, m, p0.add(s), p1.add(s), p1.subtract(s), p0.subtract(s), rgb, a0, a1, a1, a0)
    }

    private fun quad(
        c: VertexConsumer, m: Matrix4f, p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3,
        rgb: Int, a0: Float, a1: Float, a2: Float, a3: Float,
    ) {
        val (r, g, b) = rgb(rgb)
        vertex(c, m, p0, r, g, b, a0)
        vertex(c, m, p1, r, g, b, a1)
        vertex(c, m, p2, r, g, b, a2)
        vertex(c, m, p3, r, g, b, a3)
    }

    private fun vertex(c: VertexConsumer, m: Matrix4f, p: Vec3, r: Int, g: Int, b: Int, a: Float) {
        c.vertex(m, p.x.toFloat(), p.y.toFloat(), p.z.toFloat()).color(r, g, b, (a * 255).toInt().coerceIn(0, 255)).endVertex()
    }

    private fun rgb(rgb: Int) = Triple((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

    private fun whiten(rgb: Int, f: Float): Int {
        val (r, g, b) = rgb(rgb)
        return ((r + (255 - r) * f).toInt() shl 16) or ((g + (255 - g) * f).toInt() shl 8) or (b + (255 - b) * f).toInt()
    }

    private fun darken(rgb: Int, f: Float): Int {
        val (r, g, b) = rgb(rgb)
        return ((r * f).toInt() shl 16) or ((g * f).toInt() shl 8) or (b * f).toInt()
    }

    private const val LAT = 10
    private const val LON = 16
    private const val RING_SEGMENTS = 32
    private const val DISC_SECTORS = 10
    private const val AEGIS_INSET = 0.35
    private const val ARC_RESTRIKE_TICKS = 2f
    private const val ARC_JAGGEDNESS = 0.12
    private const val ARC_CORE_WIDTH = 0.045
    private const val ARC_HALO_WIDTH = 0.22
    private const val STREAM_STEPS = 16
    private const val STREAM_BEADS = 9
    private const val STREAM_SPEED = 1.6f
    private const val MIST_FLIGHT_TICKS = 12.0
    private const val SPRAY_SPREAD = 0.22
    private const val CHARGE_MOTE_TICKS = 8.0
    private const val BOLT_FULL_CHARGE_TICKS = 30f
}
