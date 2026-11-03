package org.shipwrights.enderkinesis.client.signature

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.shipwrights.enderkinesis.client.SpellArt
import org.shipwrights.enderkinesis.client.SpellGeom
import org.shipwrights.enderkinesis.client.SpellShapes
import org.shipwrights.enderkinesis.client.SpellTube
import org.shipwrights.enderkinesis.client.SpellTube.Piece
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.item.BeamSegment
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Signature
import org.shipwrights.enderkinesis.spell.signature.LinkSignatures.VampiresBallroom
import org.shipwrights.enderkinesis.spell.signature.LinkSignatures.SupplicationOfSselith
import org.shipwrights.enderkinesis.spell.signature.LinkSignatures.JudgementPillar
import org.shipwrights.enderkinesis.spell.signature.LinkSignatures.Transfusion
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object LinkSignatureVisuals {
    val ALL: Map<Signature, SignatureVisual> = mapOf(
        Signature.VAMPIRES_BALLROOM to VampiresBallroomVisual,
        Signature.TRANSFUSION to TransfusionVisual,
        Signature.JUDGEMENT_PILLAR to JudgementPillarVisual,
        Signature.SUPPLICATION_OF_SSELITH to SupplicationOfSselithVisual,
    )

    private const val BLOOD_BRIGHT = 0xFF2A3A
    private const val SUNFIRE = 0xE6B53C
    private val BLOOD_DUST = DustParticleOptions(Vector3f(0.6f, 0.04f, 0.08f), 1.1f)

    private fun middle(entity: Entity, partialTick: Float): Vec3 = entity.getPosition(partialTick).add(0.0, entity.bbHeight * 0.5, 0.0)

    /** Plain glow until the soft-glow shader loads. */
    private fun softGlow(d: SignatureDraw): VertexConsumer = d.softGlow() ?: d.glow()

    /** Darkens [rgb] by the world light at [local], for untextured shapes that do not glow. */
    private fun litColour(d: SignatureDraw, rgb: Int, local: Vec3): Int {
        val packed = d.lightAt(local.add(d.at))
        val level = maxOf((packed shr 4) and 0xF, (packed shr 20) and 0xF)
        val f = 0.15f + 0.85f * level / 15f
        return SpellGeom.darken(rgb, f)
    }

    private fun beamPoints(segments: List<BeamSegment>): List<Vec3> =
        if (segments.isEmpty()) emptyList() else listOf(segments.first().start) + segments.map { it.end }

    private fun along(pts: List<Vec3>, u: Double): Vec3 {
        val lengths = DoubleArray(pts.size - 1) { pts[it].distanceTo(pts[it + 1]) }
        var left = lengths.sum() * u.coerceIn(0.0, 1.0)
        for (i in lengths.indices) {
            if (left <= lengths[i] || i == lengths.lastIndex) return pts[i].lerp(pts[i + 1], if (lengths[i] < 1.0e-6) 0.0 else (left / lengths[i]).coerceIn(0.0, 1.0))
            left -= lengths[i]
        }
        return pts.last()
    }

    /** Keeps the path from the last render, because tick particles have no beam trace. */
    private class BeamCache {
        var pts: List<Vec3> = emptyList()
    }

    private fun cache(e: SignatureEntity): BeamCache = e.clientState as? BeamCache ?: BeamCache().also { e.clientState = it }

    private fun alongCached(e: SignatureEntity, u: Double): Vec3? {
        val pts = (e.clientState as? BeamCache)?.pts ?: return null
        return if (pts.size < 2) null else along(pts, u)
    }

    private const val HELIX_STEP = 0.25

    private fun pieces(d: SignatureDraw, segments: List<BeamSegment>): List<Piece> {
        var along = 0.0
        return segments.filter { it.length > 1.0e-4 }.map { s -> Piece(d.local(s.start), s.direction, s.length, along).also { along += s.length } }
    }

    private fun pointAt(pieces: List<Piece>, dist: Double): Vec3 {
        for (p in pieces) if (dist <= p.from + p.length) return p.at((dist - p.from).coerceAtLeast(0.0))
        return pieces.last().at(pieces.last().length)
    }

    private fun growIn(dist: Double): Double = SpellTube.growIn(dist)

    private fun throb(dist: Double, age: Float): Double = SpellTube.throb(dist, age)

    private fun pulse(dist: Double, age: Float, speed: Double, spacing: Double): Double = SpellTube.pulse(dist, age, speed, spacing)

    private fun pulsesAt(total: Double, age: Float, speed: Double, spacing: Double): List<Double> {
        val out = ArrayList<Double>()
        var q = (age * speed) % spacing
        while (q < total) { out += q; q += spacing }
        return out
    }

    private fun tube(
        c: VertexConsumer, d: SignatureDraw, pieces: List<Piece>, radius: (Double) -> Double, rgb: Int, alpha: Float, dark: Boolean = false,
        grow: Boolean = true, alphaAt: (Double) -> Float = { 1f },
    ) = SpellTube.tube(c, d.m, d.view.cam, pieces, radius, rgb, alpha, dark, grow, alphaAt)

    /** [turns] is turns per block, and [flow] is turns per tick. */
    private fun helices(
        c: VertexConsumer, d: SignatureDraw, pieces: List<Piece>, count: Int, orbit: Double, turns: Double, flow: Double,
        width: Double, rgb: Int, alpha: Float, soft: Boolean, phase: Double = 0.0, grow: Boolean = true, alphaAt: (Double) -> Float = { 1f },
    ) {
        for (k in 0 until count) for (p in pieces) {
            val (s1, s2) = p.basis
            val n = maxOf(2, Math.ceil(p.length / HELIX_STEP).toInt())
            val pts = List(n + 1) { i ->
                val along = p.length * i / n
                val dist = p.from + along
                val angle = 2 * Math.PI * (turns * dist - flow * d.age + k.toDouble() / count) + phase
                val r = orbit * (1 + 0.25 * sin(dist * 1.7 - d.age * 0.3 + k * 2.0)) * (if (grow) 0.3 + 0.7 * growIn(dist) else 1.0)
                val cr = cos(angle) * r; val sr = sin(angle) * r
                val st = p.start; val dir = p.dir
                Vec3(st.x + dir.x * along + s1.x * cr + s2.x * sr, st.y + dir.y * along + s1.y * cr + s2.y * sr, st.z + dir.z * along + s1.z * cr + s2.z * sr)
            }
            val alphas = FloatArray(n + 1) { i -> val dist = p.from + p.length * i / n; (if (grow) growIn(dist).toFloat() else 1f) * alphaAt(dist) }
            SpellShapes.strip(c, d.m, d.view, pts, { width }, rgb, alpha, soft, alphas)
        }
    }

    object VampiresBallroomVisual : SignatureVisual {
        private const val STRANDS = 3
        private const val STEPS = 20
        private const val DROPS = 4
        private const val DROP_SPEED = 0.08f
        /** Strand points for each light sample. */
        private const val STRAND_PIECE = 4

        override fun tick(e: SignatureEntity) {
            val level = e.level()
            val r = level.random
            when (VampiresBallroom.kind(e.data)) {
                VampiresBallroom.Kind.SPARK -> if (e.tickCount == VampiresBallroom.SPARK_TICKS - 1) {
                    val to = e.target() ?: return
                    val p = middle(to, 1f)
                    repeat(14) { level.addParticle(BLOOD_DUST, p.x + r.nextGaussian() * 0.3, p.y + r.nextGaussian() * 0.3, p.z + r.nextGaussian() * 0.3, 0.0, 0.0, 0.0) }
                    level.addParticle(ParticleTypes.DAMAGE_INDICATOR, p.x, p.y + 0.3, p.z, 0.0, 0.1, 0.0)
                }
                VampiresBallroom.Kind.THREAD -> if (r.nextInt(3) == 0) {
                    val a = level.getEntity(VampiresBallroom.fromId(e.data)) ?: return
                    val b = e.target() ?: return
                    val p = middle(a, 1f).lerp(middle(b, 1f), r.nextDouble()).add(0.0, 0.1, 0.0)
                    level.addParticle(BLOOD_DUST, p.x, p.y, p.z, 0.0, 0.0, 0.0)
                }
                VampiresBallroom.Kind.WEB -> {}
            }
        }

        override fun render(d: SignatureDraw) {
            when (VampiresBallroom.kind(d.e.data)) {
                VampiresBallroom.Kind.WEB -> renderFirstLeap(d)
                VampiresBallroom.Kind.THREAD -> renderThread(d)
                VampiresBallroom.Kind.SPARK -> renderSpark(d)
            }
        }

        private fun renderFirstLeap(d: SignatureDraw) {
            val a = d.age - VampiresBallroom.LEAP_FIRST
            if (a < 0 || a > 6) return
            val target = d.e.target() ?: return
            bolt(d, d.hand() ?: d.at, middle(target, d.partialTick), 1f - a / 6f)
        }

        private fun bolt(d: SignatureDraw, from: Vec3, to: Vec3, f: Float) {
            val pts = SpellGeom.jagged(Random(d.e.seed + (d.age / 2).toInt()), d.local(from), d.local(to), 0.08)
            val glow = d.glow()
            SpellShapes.strip(glow, d.m, d.view, pts, { 0.4 }, 0xD02030, 0.7f * f, soft = true)
            SpellShapes.strip(glow, d.m, d.view, pts, { 0.08 }, 0xFFA0B0, 1f * f, soft = false)
        }

        /** [seed] fixes the bow of each strand, so that the bows stay the same from frame to frame. */
        private fun strands(a: Vec3, b: Vec3, seed: Int, time: Float): List<List<Vec3>> {
            val random = Random(seed)
            val span = b.subtract(a)
            val length = span.length()
            val (side1, side2) = SpellGeom.basis(if (length < 1.0e-6) SpellGeom.UP else span.scale(1.0 / length))
            return List(STRANDS) {
                val bowAngle = random.nextDouble() * 2 * Math.PI
                val bow = side1.scale(cos(bowAngle)).add(side2.scale(sin(bowAngle))).scale(length * (0.04 + 0.08 * random.nextDouble()))
                val control = a.lerp(b, 0.5).add(bow).add(0.0, length * 0.1, 0.0)
                val freq = 1.0 + random.nextDouble()
                val speed = 0.12 + 0.12 * random.nextDouble()
                val phase = random.nextDouble() * 10
                val amp = length * (0.012 + 0.018 * random.nextDouble())
                List(STEPS + 1) { i ->
                    val u = i.toDouble() / STEPS
                    val envelope = sin(Math.PI * u)
                    val w1 = sin(u * freq * Math.PI + time * speed + phase) * amp * envelope
                    val w2 = cos(u * freq * 1.7 * Math.PI - time * speed * 1.3 + phase) * amp * envelope
                    SpellGeom.bezier(a, control, b, u).add(side1.scale(w1)).add(side2.scale(w2))
                }
            }
        }

        private fun renderThread(d: SignatureDraw) {
            val a = d.level.getEntity(VampiresBallroom.fromId(d.e.data)) ?: return
            val b = d.e.target() ?: return
            val pa = middle(a, d.partialTick).add(0.0, 0.2, 0.0)
            val pb = middle(b, d.partialTick).add(0.0, 0.2, 0.0)
            if (pa.distanceTo(pb) > VampiresBallroom.SNAP) return
            val grow = (d.age / VampiresBallroom.THREAD_GROW).coerceIn(0f, 1f)
            val fade = ((d.e.life - d.age) / 10f).coerceIn(0f, 1f)
            val shown = (grow * STEPS).toInt().coerceAtLeast(1) + 1
            val paths = strands(d.local(pa), d.local(pb), d.e.seed, d.time).map { it.take(shown) }
            val pulse = (maxOf(hurt(a), hurt(b)) / 10f).coerceIn(0f, 1f)
            val rgb = Element.BLOOD.color
            val shade = d.shade()
            for ((k, path) in paths.withIndex()) {
                val w = (if (k == 0) 0.1 else 0.06) + 0.05 * pulse
                var i = 0
                while (i < path.size - 1) {
                    val j = minOf(path.size - 1, i + STRAND_PIECE)
                    val lit = litColour(d, SpellGeom.darken(rgb, 0.35f), path[(i + j) / 2])
                    SpellShapes.strip(shade, d.m, d.view, path.subList(i, j + 1), { w }, lit, 0.8f * fade, soft = false)
                    i = j
                }
            }
            d.particles()?.let { c ->
                val sprite = SpellArt.particle("drip_hang")
                for ((k, path) in paths.withIndex()) for (i in 0 until DROPS) {
                    val u = (d.age * DROP_SPEED + (i + k * 0.37f) / DROPS) % 1f
                    if (u > grow) continue
                    val p = path[(u * STEPS).toInt().coerceIn(0, path.size - 1)]
                    SpellShapes.sprite(c, d.m, d.view, p, 0.18 + 0.06 * pulse, sprite, rgb, 0.95f * fade, d.lightAt(p.add(d.at)))
                }
            }
            val glow = d.glow()
            for (path in paths) {
                SpellShapes.strip(glow, d.m, d.view, path, { 0.18 + 0.16 * pulse }, SpellGeom.darken(rgb, 0.6f), (0.16f + 0.2f * pulse) * fade, soft = true)
                if (pulse > 0f) SpellShapes.strip(glow, d.m, d.view, path, { 0.05 }, BLOOD_BRIGHT, 0.8f * pulse * fade, soft = false)
            }
            if (d.age < 6) bolt(d, pa, pb, 1f - d.age / 6f)
        }

        private fun hurt(entity: Entity): Int = (entity as? LivingEntity)?.hurtTime ?: 0

        private fun renderSpark(d: SignatureDraw) {
            val from = d.level.getEntity(VampiresBallroom.fromId(d.e.data)) ?: return
            val to = d.e.target() ?: return
            val u = (d.age / VampiresBallroom.SPARK_TICKS).coerceIn(0f, 1f).toDouble()
            val pa = middle(from, d.partialTick).add(0.0, 0.2, 0.0)
            val pb = middle(to, d.partialTick).add(0.0, 0.2, 0.0)
            val control = pa.lerp(pb, 0.5).add(0.0, 0.1 * pa.distanceTo(pb), 0.0)
            val p = d.local(SpellGeom.bezier(pa, control, pb, u))
            val trail = List(6) { i -> d.local(SpellGeom.bezier(pa, control, pb, (u - 0.04 * i).coerceAtLeast(0.0))) }
            val glow = d.glow()
            if (d.age < 3) SpellShapes.disc(glow, d.m, d.view, d.local(pa), 1.4, 0xFFFFFF, 0.8f * (1 - d.age / 3f))
            SpellShapes.strip(glow, d.m, d.view, trail, { 0.3 }, 0xFF3040, 0.7f, soft = true, alphas = FloatArray(6) { 1f - it / 6f })
            SpellShapes.disc(glow, d.m, d.view, p, 0.8, 0xFF3040, 1f)
            SpellShapes.disc(glow, d.m, d.view, p, 0.28, 0xFFFFFF, 1f)
        }
    }

    object TransfusionVisual : SignatureVisual {
        private const val PULSE_SPEED = 0.5
        private const val PULSE_SPACING = 9.0

        override fun tick(e: SignatureEntity) {
            val level = e.level()
            val r = level.random
            repeat(2) {
                val p = alongCached(e, r.nextDouble())?.add(r.nextGaussian() * 0.12, r.nextGaussian() * 0.12, r.nextGaussian() * 0.12) ?: return@repeat
                level.addParticle(BLOOD_DUST, p.x, p.y, p.z, 0.0, 0.0, 0.0)
            }
            if (r.nextInt(5) == 0) alongCached(e, r.nextDouble())?.let { level.addParticle(ParticleTypes.END_ROD, it.x, it.y, it.z, 0.0, 0.0, 0.0) }
            val target = e.target() ?: return
            val p = middle(target, 1f)
            if (e.data == 1) repeat(2) { level.addParticle(ParticleTypes.FLAME, p.x + r.nextGaussian() * 0.25, p.y + r.nextGaussian() * 0.4, p.z + r.nextGaussian() * 0.25, 0.0, 0.04, 0.0) }
            else if (r.nextInt(2) == 0) level.addParticle(if (r.nextInt(4) == 0) ParticleTypes.HEART else BLOOD_DUST, p.x + r.nextGaussian() * 0.3, p.y + 0.6, p.z + r.nextGaussian() * 0.3, 0.0, 0.02, 0.0)
        }

        override fun render(d: SignatureDraw) {
            val segments = SignatureVisuals.beamPath(d.e, d.partialTick, Transfusion.REACH) ?: return
            cache(d.e).pts = beamPoints(segments)
            val pieces = pieces(d, segments)
            if (pieces.isEmpty()) return
            val on = minOf(1f, d.age / 4f)
            val age = d.age
            val total = pieces.sumOf { it.length }
            val start = pieces.first().start
            val end = pointAt(pieces, total)
            val burning = d.e.data == 1
            val swell = { dist: Double -> pulse(dist, age, PULSE_SPEED, PULSE_SPACING) }
            val glow = softGlow(d)
            tube(glow, d, pieces, { 0.2 * (1 + 0.5 * (throb(it, age) - 1)) * (1 + 0.25 * swell(it)) }, 0xFFD0DC, 0.16f * on)
            helices(glow, d, pieces, 2, 0.16, 0.3, 0.02, 0.035, 0xE04050, 0.35f * on, soft = false)
            for (q in pulsesAt(total, age, PULSE_SPEED, PULSE_SPACING)) {
                SpellShapes.disc(glow, d.m, d.view, pointAt(pieces, q), 0.3, 0xFFB0C0, 0.22f * on * growIn(q).toFloat())
            }
            SpellShapes.disc(glow, d.m, d.view, start, 0.4, 0xFFD0DC, 0.25f * on)
            SpellShapes.disc(glow, d.m, d.view, end, 0.7, if (burning) 0xFFD080 else 0xFFC8D4, 0.3f * on)
            d.e.target()?.let { SpellShapes.disc(glow, d.m, d.view, d.local(middle(it, d.partialTick)), 0.8, if (burning) 0xFFB040 else 0xFF8090, 0.2f * on) }
            tube(d.shade(), d, pieces, { 0.028 }, 0xA00818, 0.6f * on, dark = true)
        }
    }

    object JudgementPillarVisual : SignatureVisual {
        private const val FADE = 25f
        /** Fade start and end, as fractions of the shaft height. */
        private const val FADE_FROM = 0.08
        private const val FADE_TO = 0.7
        private const val BREATH = 0.08f
        private const val BREATH_RATE = 0.08f

        override fun tick(e: SignatureEntity) {
            val level = e.level()
            val r = level.random
            val foot = e.position()
            val radius = JudgementPillar.radius(e.power)
            if (e.tickCount == JudgementPillar.IMPACT) {
                level.addParticle(ParticleTypes.FLASH, foot.x, foot.y + 1.0, foot.z, 0.0, 0.0, 0.0)
                repeat(40) {
                    val a = r.nextDouble() * Math.PI * 2
                    level.addParticle(ParticleTypes.END_ROD, foot.x, foot.y + 0.3, foot.z, cos(a) * 0.6, 0.05 + r.nextDouble() * 0.2, sin(a) * 0.6)
                }
            }
            if (e.tickCount < JudgementPillar.IMPACT || e.tickCount >= JudgementPillar.ACTIVE) return
            repeat(6) {
                val a = r.nextDouble() * Math.PI * 2
                val d = Math.sqrt(r.nextDouble()) * radius
                level.addParticle(ParticleTypes.END_ROD, foot.x + cos(a) * d, foot.y + r.nextDouble() * 4.0, foot.z + sin(a) * d, 0.0, 0.1 + r.nextDouble() * 0.1, 0.0)
            }
            repeat(2) {
                val a = r.nextDouble() * Math.PI * 2
                val d = Math.sqrt(r.nextDouble()) * radius
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, foot.x + cos(a) * d, foot.y + 6.0 + r.nextDouble() * 10.0, foot.z + sin(a) * d, 0.0, -0.8, 0.0)
            }
            if (r.nextInt(2) == 0) level.addParticle(ParticleTypes.WAX_ON, foot.x + r.nextGaussian() * radius * 0.5, foot.y + 0.2, foot.z + r.nextGaussian() * radius * 0.5, 0.0, 0.0, 0.0)
        }

        override fun render(d: SignatureDraw) {
            d.castFlash(d.e.point, SUNFIRE, width = 0.3)
            val impact = JudgementPillar.IMPACT.toFloat()
            val o = if (d.age < impact) 1f else (1f - (d.age - JudgementPillar.ACTIVE) / FADE).coerceIn(0f, 1f)
            if (o <= 0f) return
            val bright = if (d.e.data == 1) 1f else 0.8f
            val radius = JudgementPillar.radius(d.e.power)
            val foot = d.local(d.e.point)
            val drop = SpellGeom.easeOut((d.age / impact).toDouble().coerceIn(0.0, 1.0))
            val top = foot.add(0.0, JudgementPillar.HEIGHT, 0.0)
            val bottom = top.lerp(foot, drop)
            val shaft = listOf(Piece(bottom, SpellGeom.UP, (top.y - bottom.y).coerceAtLeast(0.01), bottom.y - foot.y))
            // Fade to zero well below the tip, so that the shaft has no visible top.
            val fade = { h: Double -> 1f - SpellGeom.smooth((h / JudgementPillar.HEIGHT - FADE_FROM) / (FADE_TO - FADE_FROM)).toFloat() }
            val glowing = o * bright * (1f - BREATH + BREATH * sin(d.age * BREATH_RATE))
            val glow = softGlow(d)
            tube(glow, d, shaft, { radius * 1.15 }, 0xFFC850, 0.45f * glowing, grow = false, alphaAt = fade)
            tube(glow, d, shaft, { radius * 0.7 }, 0xFFE08A, 0.5f * glowing, grow = false, alphaAt = fade)
            tube(glow, d, shaft, { radius * 0.28 }, 0xFFFBEA, 0.65f * glowing, grow = false, alphaAt = fade)
            if (drop < 1.0) return
            SpellShapes.ring(glow, d.m, foot.add(0.0, 0.05, 0.0), radius * 1.05, 0.4, SUNFIRE, 0.9f * o, 0.0)
            SpellShapes.ring(glow, d.m, foot.add(0.0, 0.05, 0.0), radius * 0.55, radius * 0.55, 0xFFE8B0, 0.45f * o * bright, 0.0)
            SpellShapes.disc(glow, d.m, d.view, foot.add(0.0, 0.5, 0.0), radius * 1.6, 0xFFE8B0, 0.45f * o * bright)
            val since = d.age - impact
            if (since < 12f) {
                val t = since / 12f
                SpellShapes.disc(glow, d.m, d.view, foot.add(0.0, 1.5, 0.0), 6.0 + 4.0 * t, 0xFFF4D0, 0.9f * (1f - t))
                SpellShapes.sphere(glow, d.m, d.view, foot, radius * (1.0 + 2.5 * SpellGeom.easeOut(t.toDouble())), 0xFFE08A, 0.6f * (1f - t))
                SpellShapes.ring(glow, d.m, foot.add(0.0, 0.1, 0.0), radius * (1.0 + 4.0 * SpellGeom.easeOut(t.toDouble())), 0.6, 0xFFF0C0, 0.9f * (1f - t), 0.0)
            }
        }
    }

    object SupplicationOfSselithVisual : SignatureVisual {
        private const val PULSE_SPEED = 0.7
        private const val PULSE_SPACING = 6.0

        override fun tick(e: SignatureEntity) {
            if (SupplicationOfSselith.Pool.isPool(e.data)) { tickPool(e); return }
            val level = e.level()
            val r = level.random
            val p = e.point
            repeat(2) {
                val dir = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize().scale(2.0 * r.nextDouble())
                level.addParticle(ParticleTypes.LARGE_SMOKE, p.x + dir.x, p.y + dir.y, p.z + dir.z, 0.0, 0.04, 0.0)
            }
            if (r.nextInt(2) == 0) level.addParticle(ParticleTypes.SMALL_FLAME, p.x + r.nextGaussian() * 0.4, p.y + r.nextGaussian() * 0.4, p.z + r.nextGaussian() * 0.4, 0.0, 0.03, 0.0)
            repeat(2) { alongCached(e, r.nextDouble())?.let { level.addParticle(ParticleTypes.SMOKE, it.x, it.y, it.z, 0.0, 0.01, 0.0) } }
        }

        private fun tickPool(e: SignatureEntity) {
            val level = e.level()
            val r = level.random
            val c = e.point
            val radius = SupplicationOfSselith.Pool.radiusOf(e.data)
            // Hold the same zone as the server, so that the client light agrees.
            SupplicationOfSselith.Pool.holdDark(e)
            if (r.nextInt(3) == 0) {
                val dir = Vec3(r.nextGaussian(), Math.abs(r.nextGaussian()) * 0.5, r.nextGaussian()).normalize().scale(radius * Math.sqrt(r.nextDouble()) * 0.9)
                level.addParticle(ParticleTypes.SMOKE, c.x + dir.x, c.y + dir.y, c.z + dir.z, 0.0, 0.02, 0.0)
            }
            if (r.nextInt(4) == 0) level.addParticle(ParticleTypes.ASH, c.x + r.nextGaussian() * radius * 0.5, c.y + r.nextDouble() * radius * 0.5, c.z + r.nextGaussian() * radius * 0.5, 0.0, 0.0, 0.0)
            if (r.nextInt(8) == 0) level.addParticle(ParticleTypes.SMALL_FLAME, c.x + r.nextGaussian() * radius * 0.4, c.y + 0.2, c.z + r.nextGaussian() * radius * 0.4, 0.0, 0.02, 0.0)
        }

        override fun render(d: SignatureDraw) {
            if (SupplicationOfSselith.Pool.isPool(d.e.data)) return
            val segments = SignatureVisuals.beamPath(d.e, d.partialTick, SupplicationOfSselith.REACH) ?: return
            cache(d.e).pts = beamPoints(segments)
            val pieces = pieces(d, segments)
            if (pieces.isEmpty()) return
            val on = minOf(1f, d.age / 5f)
            val age = d.age
            val total = pieces.sumOf { it.length }
            val start = pieces.first().start
            val end = pointAt(pieces, total)
            val swell = { dist: Double -> pulse(dist, age, PULSE_SPEED, PULSE_SPACING) }
            val flicker = (0.85 + 0.15 * sin(age * 0.8) * sin(age * 0.31)).toFloat()
            val glow = softGlow(d)
            tube(glow, d, pieces, { 0.62 * throb(it, age) * (1 + 0.3 * swell(it)) }, SUNFIRE, 0.5f * on * flicker)
            tube(glow, d, pieces, { 0.42 * throb(it + 2.0, age) * (1 + 0.4 * swell(it)) }, 0xFFE08A, 0.45f * on)
            helices(glow, d, pieces, 2, 0.5, 0.3, 0.02, 0.08, 0xFFD070, 0.85f * on, soft = false)
            helices(glow, d, pieces, 2, 0.5, 0.3, 0.02, 0.26, SUNFIRE, 0.3f * on, soft = true)
            SpellShapes.disc(glow, d.m, d.view, start, 1.0, SUNFIRE, 0.55f * on * flicker)
            SpellShapes.disc(glow, d.m, d.view, start, 0.4, 0xFFF0C0, 0.8f * on)
            SpellShapes.disc(glow, d.m, d.view, end, 2.6 + 0.4 * sin(age * 0.2), 0xFFC060, 0.8f * on * flicker)
            SpellShapes.sphere(glow, d.m, d.view, end, 1.3 * throb(total, age), 0xFFD070, 0.65f * on)
            val shade = d.shade()
            tube(shade, d, pieces, { 0.3 * throb(it + 5.0, age) * (1 + 0.6 * swell(it)) }, 0x000000, 0.97f * on, dark = true)
            helices(shade, d, pieces, 2, 0.42, 0.3, -0.03, 0.1, 0x000000, 0.8f * on, soft = false, phase = Math.PI / 2)
            SpellShapes.sphereFlat(shade, d.m, end, 0.9, 0x000000, 0.97f * on)
        }
    }
}
