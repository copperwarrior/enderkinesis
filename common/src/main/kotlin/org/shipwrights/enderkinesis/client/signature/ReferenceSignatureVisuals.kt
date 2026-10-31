package org.shipwrights.enderkinesis.client.signature

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.BlackSunVolume
import org.shipwrights.enderkinesis.client.Sprite
import org.shipwrights.enderkinesis.client.SpellArt
import org.shipwrights.enderkinesis.client.SpellGeom
import org.shipwrights.enderkinesis.client.SpellShapes
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.item.BeamSegment
import org.shipwrights.enderkinesis.item.SunderingBeamTrace
import org.shipwrights.enderkinesis.spell.Signature
import org.shipwrights.enderkinesis.spell.signature.ReferenceSignatures.BlackHoleSun
import org.shipwrights.enderkinesis.spell.signature.ReferenceSignatures.PrismLance
import org.shipwrights.enderkinesis.spell.signature.ReferenceSignatures.Rootquake
import kotlin.random.Random

object ReferenceSignatureVisuals {
    val ALL: Map<Signature, SignatureVisual> = mapOf(
        Signature.BLACK_HOLE_SUN to BlackHoleSunVisual,
        Signature.ROOTQUAKE to RootquakeVisual,
        Signature.PRISM_LANCE to PrismLanceVisual,
    )

    fun fade(d: SignatureDraw, inTicks: Float = 6f, outTicks: Float = 10f): Float =
        minOf(1f, d.age / inTicks, (d.e.life - d.age) / outTicks).coerceIn(0f, 1f)

    /** Vanilla block face shade, in the order top, bottom, north, south, west, east. */
    private val FACE_SHADE = floatArrayOf(1f, 0.5f, 0.8f, 0.8f, 0.6f, 0.6f)

    /** The side faces offset their texture (ov) so that it aligns with the top, as on a block. */
    fun shadedBox(c: VertexConsumer, m: Matrix4f, lo: Vec3, hi: Vec3, sprite: Sprite, light: Int, alpha: Float = 1f) {
        val h = hi.y - lo.y
        val ov = (1 - h % 1.0) % 1.0
        fun p(x: Double, y: Double, z: Double) = Vec3(x, y, z)
        val x0 = lo.x; val y0 = lo.y; val z0 = lo.z; val x1 = hi.x; val y1 = hi.y; val z1 = hi.z
        fun shade(k: Int): Int { val g = (255 * FACE_SHADE[k]).toInt(); return (g shl 16) or (g shl 8) or g }
        SpellShapes.texFace(c, m, p(x0, y1, z0), p(x1, y1, z0), p(x1, y1, z1), p(x0, y1, z1), sprite, shade(0), alpha, light)
        SpellShapes.texFace(c, m, p(x0, y0, z1), p(x1, y0, z1), p(x1, y0, z0), p(x0, y0, z0), sprite, shade(1), alpha, light)
        SpellShapes.texFace(c, m, p(x0, y0, z0), p(x1, y0, z0), p(x1, y1, z0), p(x0, y1, z0), sprite, shade(2), alpha, light, ov = ov)
        SpellShapes.texFace(c, m, p(x1, y0, z1), p(x0, y0, z1), p(x0, y1, z1), p(x1, y1, z1), sprite, shade(3), alpha, light, ov = ov)
        SpellShapes.texFace(c, m, p(x0, y0, z1), p(x0, y0, z0), p(x0, y1, z0), p(x0, y1, z1), sprite, shade(4), alpha, light, ov = ov)
        SpellShapes.texFace(c, m, p(x1, y0, z0), p(x1, y0, z1), p(x1, y1, z1), p(x1, y1, z0), sprite, shade(5), alpha, light, ov = ov)
    }

    private const val SUN_GOLD = 0xFFC24A
    private const val SUN_HAZE = 0xC8862A
    /** Black heart radius, as a fraction of the core radius. */
    private const val HEART = 0.55
    /** Volume box half size, in volume core radii. The disk reaches 4.2. */
    private const val BOX = 4.3
    /** Volume core radius, as a fraction of the stand-in heart radius. */
    private const val VOLUME_CORE = 0.5

    object BlackHoleSunVisual : SignatureVisual {
        private class Strike(val target: Int, val tick: Int)

        private class Sun(var count: Int, val strikes: ArrayList<Strike> = ArrayList())

        override fun tick(e: SignatureEntity) {
            val beams = BlackHoleSun.beams(e)
            val sun = e.clientState as? Sun ?: Sun(beams).also { e.clientState = it }
            if (beams != sun.count) {
                sun.count = beams
                sun.strikes += Strike(e.targetId, e.tickCount)
            }
            sun.strikes.removeIf { e.tickCount - it.tick > BlackHoleSun.BEAM_TICKS }
            val level = e.level()
            val r = level.random
            val core = e.position()
            val size = size(e, e.tickCount.toFloat())
            if (size <= 0.0) return
            repeat(4) {
                val dir = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize()
                val from = core.add(dir.scale(BlackHoleSun.REACH * (0.4 + 0.6 * r.nextDouble())))
                val v = core.subtract(from).scale(0.07)
                level.addParticle(if (r.nextInt(3) == 0) ParticleTypes.LARGE_SMOKE else ParticleTypes.SMALL_FLAME, from.x, from.y, from.z, v.x, v.y, v.z)
            }
            repeat(3) {
                val dir = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize()
                val p = core.add(dir.scale(BlackHoleSun.CORE * size * (0.9 + 0.3 * r.nextDouble())))
                val v = dir.scale(0.2 + 0.25 * r.nextDouble())
                val block = if (r.nextInt(3) == 0) Blocks.NETHERRACK else Blocks.MAGMA_BLOCK
                level.addParticle(BlockParticleOption(ParticleTypes.BLOCK, block.defaultBlockState()), p.x, p.y, p.z, v.x, v.y, v.z)
            }
            if (r.nextInt(2) == 0) {
                val dir = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize()
                val p = core.add(dir.scale(BlackHoleSun.CORE * size))
                level.addParticle(ParticleTypes.LAVA, p.x, p.y, p.z, 0.0, 0.0, 0.0)
            }
        }

        private fun sunbeams(d: SignatureDraw, core: Double) {
            val sun = d.e.clientState as? Sun ?: return
            for (strike in sun.strikes) {
                val target = d.level.getEntity(strike.target) ?: continue
                val f = (1f - (d.age - strike.tick) / BlackHoleSun.BEAM_TICKS).coerceIn(0f, 1f)
                if (f <= 0f) continue
                val to = d.local(target.getPosition(d.partialTick).add(0.0, target.bbHeight * 0.5, 0.0))
                if (to.lengthSqr() < 1.0e-6) continue
                val from = to.normalize().scale(core * 0.9)
                val c = d.softGlow() ?: d.glow()
                SpellShapes.strip(c, d.m, d.view, listOf(from, to), { 0.9 * f }, SUN_GOLD, 0.45f * f, soft = true)
                SpellShapes.strip(c, d.m, d.view, listOf(from, to), { 0.18 * f }, 0xFFF4D0, 0.95f * f, soft = false)
                SpellShapes.disc(c, d.m, d.view, to, 1.2 * f, SUN_GOLD, 0.6f * f)
            }
        }

        private fun size(e: SignatureEntity, age: Float): Double {
            val cave = ((age - (e.life - 8)) / 8.0).coerceIn(0.0, 1.0)
            return BlackHoleSun.scale(e, age) * (1 - cave * cave)
        }

        /** [BlackSunVolume.queue] returns false when the volume is off or full. Then draw a flat stand-in. */
        private fun sphere(d: SignatureDraw, core: Double, shown: Float) {
            val inner = core * HEART
            val volumeCore = inner * VOLUME_CORE
            if (BlackSunVolume.queue(d.at, volumeCore, volumeCore * BOX, d.e.seed and 0xFF, shown)) return
            (d.softGlow() ?: d.glow()).let { c -> SpellShapes.disc(c, d.m, d.view, Vec3.ZERO, core * 2.4, SUN_HAZE, 0.18f) }
            (d.softShade() ?: d.shade()).let { SpellShapes.sphereFlat(it, d.m, Vec3.ZERO, inner, 0x000000, shown) }
        }

        override fun render(d: SignatureDraw) {
            val e = d.e
            val size = size(e, d.age)
            val core = BlackHoleSun.CORE * size
            if (core > 0.01) sphere(d, core, fade(d, 4f, 4f).coerceAtLeast(0.05f))
            sunbeams(d, core)
            val flare = ((d.age - (e.life - 4)) / 4f).coerceIn(0f, 1f)
            if (flare > 0f) (d.softGlow() ?: d.glow()).let { g ->
                SpellShapes.disc(g, d.m, d.view, Vec3.ZERO, BlackHoleSun.FLARE * flare, 0xFFE2A0, 1f - flare * 0.5f)
            }
        }
    }

    /** Uses the same layout as the server, so that the roots agree with the hits. */
    private class Quake(val layout: Rootquake.Layout)

    /** Root points for each light sample. */
    private const val ROOT_PIECE = 6

    object RootquakeVisual : SignatureVisual {
        private fun quake(e: SignatureEntity): Quake =
            e.clientState as? Quake ?: Quake(Rootquake.layout(e.level(), e.position(), e.point, e.seed)).also { e.clientState = it }

        override fun render(d: SignatureDraw) {
            val q = quake(d.e)
            val front = d.age * Rootquake.FRONT_SPEED
            val sinkFade = 1f - ((d.age - Rootquake.SINK) / 20f).coerceIn(0f, 1f)
            d.blocks()?.let { c ->
                val sprite = SpellArt.block("mangrove_roots_side")
                for (root in q.layout.roots) {
                    val n = minOf(root.size, (front / Rootquake.ROOT_STEP).toInt())
                    if (n < 2) continue
                    val edges = List(n) { i ->
                        val a = root[maxOf(0, i - 1)]; val b = root[minOf(root.size - 1, i + 1)]
                        val along = b.subtract(a)
                        Vec3(-along.z, 0.0, along.x).normalize().scale(0.22 - 0.16 * i / root.size)
                    }
                    var i = 0
                    while (i < n - 1) {
                        val j = minOf(n - 1, i + ROOT_PIECE)
                        val light = d.lightAt(root[(i + j) / 2].add(0.0, 0.3, 0.0))
                        SpellShapes.stripPx(c, d.m, root.subList(i, j + 1).map(d::local), edges.subList(i, j + 1), sprite, 0xFFFFFF, FloatArray(j - i + 1) { sinkFade }, 0.5, light)
                        i = j
                    }
                }
            }
            val solid = d.solidBlocks() ?: return
            val rooted = SpellArt.block("rooted_dirt")
            val dirt = SpellArt.block("dirt")
            for (c in q.layout.columns) {
                val a = d.age - c.rise
                if (a < 0) continue
                val rise = if (a < 4) SpellGeom.easeOut(a / 4.0) * 1.12 else if (a < 7) 1.12 - 0.12 * (a - 4) / 3 else 1.0
                val sink = ((d.age - c.sink) / 10.0).coerceIn(0.0, 1.0)
                val top = c.ground + c.height * rise * (1 - sink)
                if (top <= c.ground + 0.01) continue
                // Sample the air above the ground, because the light in a solid block is zero.
                val light = d.lightAt(Vec3(c.x, c.ground + 0.5, c.z))
                val head = maxOf(c.ground - 1.0, top - 1.0)
                shadedBox(solid, d.m, d.local(Vec3(c.x - 0.5, head, c.z - 0.5)), d.local(Vec3(c.x + 0.5, top, c.z + 0.5)), rooted, light)
                if (head > c.ground - 1.0) shadedBox(solid, d.m, d.local(Vec3(c.x - 0.5, c.ground - 1.0, c.z - 0.5)), d.local(Vec3(c.x + 0.5, head, c.z + 0.5)), dirt, light)
            }
        }
    }

    private val SPLIT_HUES = intArrayOf(0xFF6A6A, 0xFFD45A, 0x6AFF9A, 0x6AB8FF, 0xC86AFF)
    /** Length of each rainbow band on the main beam, in blocks. */
    private const val BAND = 0.35
    /** Fraction of the rainbow hue that shows through the white beam. */
    private const val RAINBOW = 0.28f

    object PrismLanceVisual : SignatureVisual {
        override fun tick(e: SignatureEntity) {
            val level = e.level()
            val p = e.point
            if (e.tickCount % 4 == 0) level.addParticle(ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, level.random.nextGaussian() * 0.04, 0.02, level.random.nextGaussian() * 0.04)
        }

        /** Keeps [amount] of [rgb] and mixes the remainder to white. */
        private fun pale(rgb: Int, amount: Float): Int = SpellGeom.whiten(rgb, 1f - amount)

        private fun hue(h: Double): Int {
            val x = ((h % 1.0) + 1.0) % 1.0 * 6
            val f = x - Math.floor(x)
            val (r, g, b) = when (x.toInt()) {
                0 -> Triple(1.0, f, 0.0); 1 -> Triple(1 - f, 1.0, 0.0); 2 -> Triple(0.0, 1.0, f)
                3 -> Triple(0.0, 1 - f, 1.0); 4 -> Triple(f, 0.0, 1.0); else -> Triple(1.0, 0.0, 1 - f)
            }
            return ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
        }

        /** Local points about [step] apart along [segments], each with its distance along the path. */
        private fun pieces(d: SignatureDraw, segments: List<BeamSegment>, step: Double): List<Pair<Vec3, Double>> {
            val out = ArrayList<Pair<Vec3, Double>>()
            var travelled = 0.0
            for (seg in segments) {
                val n = maxOf(1, Math.ceil(seg.length / step).toInt())
                for (i in (if (out.isEmpty()) 0 else 1)..n) out += d.local(seg.start.add(seg.direction.scale(seg.length * i / n))) to travelled + seg.length * i / n
                travelled += seg.length
            }
            return out
        }

        private fun beam(d: SignatureDraw, segments: List<BeamSegment>, halo: Double, core: Double, rgb: Int, f: Float) {
            val pts = segments.flatMapIndexed { i, s -> if (i == 0) listOf(d.local(s.start), d.local(s.end)) else listOf(d.local(s.end)) }
            val glow = d.glow()
            SpellShapes.strip(glow, d.m, d.view, pts, { halo }, rgb, 0.5f * f, soft = true)
            SpellShapes.strip(glow, d.m, d.view, pts, { core }, SpellGeom.whiten(rgb, 0.4f), 0.85f * f, soft = false)
        }

        override fun render(d: SignatureDraw) {
            val f = minOf(1f, d.age / 3f)
            val e = d.e
            val path = SignatureVisuals.beamPath(e, d.partialTick, PrismLance.REACH) ?: return
            if (path.isEmpty()) return
            val time = d.time.toDouble()
            val glow = d.glow()
            val pts = pieces(d, path, BAND)
            SpellShapes.strip(glow, d.m, d.view, pts.map { it.first }, { 0.55 }, 0x9FDFFF, 0.3f * f, soft = true)
            for (i in 0 until pts.size - 1) {
                val rgb = pale(hue(pts[i].second * 0.08 - time * 0.02), RAINBOW)
                SpellShapes.strip(glow, d.m, d.view, listOf(pts[i].first, pts[i + 1].first), { 0.26 }, rgb, 0.55f * f, soft = true)
            }
            SpellShapes.strip(glow, d.m, d.view, pts.map { it.first }, { 0.09 }, 0xFFFFFF, 0.95f * f, soft = false)
            val end = path.last().end
            (d.softGlow() ?: d.glow()).let { SpellShapes.disc(it, d.m, d.view, d.local(end), 1.0, 0xFFFFFF, 0.6f * f) }
            val owner = e.owner() ?: return
            val shields = SunderingBeamTrace.collectWieldedShields(d.level, owner)
            val last = path.last()
            val rays = PrismLance.splits(d.level, owner, end.subtract(last.direction), end, e.seed, e.tickCount, shields)
            for (ray in rays) {
                if (ray.trace.segments.isEmpty()) continue
                    val hues = ray.splits.map { SPLIT_HUES[it % SPLIT_HUES.size] }
                val rgb = if (hues.size == 1) hues[0] else blend(hues[0], hues[1])
                val wide = 1.0 + 0.45 * (hues.size - 1)
                beam(d, ray.trace.segments, 0.32 * wide, 0.07 * wide, rgb, f)
                for (seg in ray.trace.segments.drop(1)) (d.softGlow() ?: d.glow()).let { SpellShapes.disc(it, d.m, d.view, d.local(seg.start), 0.35, rgb, 0.5f * f) }
                if (hues.size > 1) (d.softGlow() ?: d.glow()).let { SpellShapes.disc(it, d.m, d.view, d.local(ray.trace.segments[0].start), 0.6, rgb, 0.7f * f) }
            }
        }

        private fun blend(a: Int, b: Int): Int {
            fun ch(c: Int, s: Int) = (c shr s) and 0xFF
            return (((ch(a, 16) + ch(b, 16)) / 2) shl 16) or (((ch(a, 8) + ch(b, 8)) / 2) shl 8) or ((ch(a, 0) + ch(b, 0)) / 2)
        }
    }
}
