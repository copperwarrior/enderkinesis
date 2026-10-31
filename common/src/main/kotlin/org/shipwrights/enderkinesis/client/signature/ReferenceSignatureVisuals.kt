package org.shipwrights.enderkinesis.client.signature

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.AegisClient
import org.shipwrights.enderkinesis.client.Sprite
import org.shipwrights.enderkinesis.client.SpellArt
import org.shipwrights.enderkinesis.client.SpellGeom
import org.shipwrights.enderkinesis.client.SpellShapes
import org.shipwrights.enderkinesis.client.SpellVfxRenderTypes
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.item.BeamSegment
import org.shipwrights.enderkinesis.item.SunderingBeamTrace
import org.shipwrights.enderkinesis.spell.Signature
import org.shipwrights.enderkinesis.spell.signature.ReferenceSignatures.BlackHoleSun
import org.shipwrights.enderkinesis.spell.signature.ReferenceSignatures.PrismLance
import org.shipwrights.enderkinesis.spell.signature.ReferenceSignatures.Rootquake
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** How Black Hole Sun, Rootquake and Prism Lance look. */
object ReferenceSignatureVisuals {
    val ALL: Map<Signature, SignatureVisual> = mapOf(
        Signature.BLACK_HOLE_SUN to BlackHoleSunVisual,
        Signature.ROOTQUAKE to RootquakeVisual,
        Signature.PRISM_LANCE to PrismLanceVisual,
    )

    /** Fades in over [inTicks] and out over the last [outTicks] of its life. */
    fun fade(d: SignatureDraw, inTicks: Float = 6f, outTicks: Float = 10f): Float =
        minOf(1f, d.age / inTicks, (d.e.life - d.age) / outTicks).coerceIn(0f, 1f)

    /** Vanilla's face shading: tops full, bottoms half, north and south 0.8, east and west 0.6. */
    private val FACE_SHADE = floatArrayOf(1f, 0.5f, 0.8f, 0.8f, 0.6f, 0.6f)

    /**
     * An axis-aligned box from [lo] to [hi] (local points) with [sprite] at Minecraft's scale on
     * every face, shaded per face as vanilla shades blocks and lit by the world's [light]. Its
     * sides' texture lines up with its top, as a block's does.
     */
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

    // ---- Black Hole Sun ---------------------------------------------------------------------------

    private const val SUN_GOLD = 0xFFC24A
    private const val SUN_FIRE = 0xF07A22
    private const val SUN_EMBER = 0xB8301A
    private const val SUN_HAZE = 0xC8862A
    /** Its black heart's radius and its disk's outer radius, as parts of its size. */
    private const val HEART = 0.55
    private const val DISK = 1.94

    /**
     * A black heart in a disk of churning fire, tipped a little from level its own way each
     * cast and tapering from the heart to a fine edge, with a glow round the heart as the Sunfire
     * cloak's halo, in a haze of golden light; the world behind is pulled a little toward it. A
     * volume, marched through per pixel as the cloaks' rings are (`rendertype_spell_black_sun`).
     * The disk turns round the heart in clumps and wisps, deep red where it meets the black, fire
     * through its middle and gold at its edge. It leaves the hand small and swells
     * exponentially as it flies ([BlackHoleSun.scale]).
     * Burning chunks and lava fly off it and embers are drawn back in. Beams of sunlight lance
     * from it at what it strikes. As it ends the ring caves in on the core and it flares out.
     */
    object BlackHoleSunVisual : SignatureVisual {
        /** A sunbeam as the client saw it strike: at whom, and when. */
        private class Strike(val target: Int, val tick: Int)

        /** A cast's beams so far: the last beam count seen, and the beams still showing. */
        private class Sun(var count: Int, val strikes: ArrayList<Strike> = ArrayList())

        override fun tick(e: SignatureEntity) {
            val sun = e.clientState as? Sun ?: Sun(e.data).also { e.clientState = it }
            if (e.data != sun.count) {
                sun.count = e.data
                sun.strikes += Strike(e.targetId, e.tickCount)
            }
            sun.strikes.removeIf { e.tickCount - it.tick > BlackHoleSun.BEAM_TICKS }
            val level = e.level()
            val r = level.random
            val core = e.position()
            val size = size(e.tickCount.toFloat(), e.life)
            if (size <= 0.0) return
            // Embers and smoke drawn in from all round it.
            repeat(4) {
                val dir = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize()
                val from = core.add(dir.scale(BlackHoleSun.REACH * (0.4 + 0.6 * r.nextDouble())))
                val v = core.subtract(from).scale(0.07)
                level.addParticle(if (r.nextInt(3) == 0) ParticleTypes.LARGE_SMOKE else ParticleTypes.SMALL_FLAME, from.x, from.y, from.z, v.x, v.y, v.z)
            }
            // Burning chunks thrown off the ring.
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

        /** Each beam of sunlight still showing: from the rim of the core to the creature it
         *  struck, white at its heart in a gold sheath, flashing out over [BlackHoleSun.BEAM_TICKS]. */
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

        /** How large it is, 0..1 of its full size: swelling as it flies, caving in at its end. */
        private fun size(age: Float, life: Int): Double {
            val cave = ((age - (life - 8)) / 8.0).coerceIn(0.0, 1.0)
            return BlackHoleSun.scale(age) * (1 - cave * cave)
        }

        /**
         * The sun itself, through `rendertype_spell_black_sun`: a cube round it, [outer] out each
         * way, marched through per pixel to a black core [inner] round in its disk of fire.
         * Each sun is its own draw, as the cloaks are: its middle is the shader's uniform.
         * Without the shader (or a buffer it can end), a plain black sphere stands in.
         */
        private fun sphere(d: SignatureDraw, inner: Double, outer: Double, shown: Float) {
            val buffers = d.buffers
            if (!SpellVfxRenderTypes.isBlackSunReady() || buffers !is MultiBufferSource.BufferSource) {
                (d.softShade() ?: d.shade()).let { SpellShapes.sphereFlat(it, d.m, Vec3.ZERO, inner, 0x000000, shown) }
                return
            }
            SpellVfxRenderTypes.blackSunCentre = d.view.cam.scale(-1.0)
            val c = buffers.getBuffer(SpellVfxRenderTypes.BLACK_SUN)
            val a = (shown * 255).toInt().coerceIn(0, 255)
            // Which way its disk tips, from the cast's seed.
            val tip = d.e.seed and 0xFF
            fun v(x: Double, y: Double, z: Double) {
                c.vertex(d.m, (x * outer).toFloat(), (y * outer).toFloat(), (z * outer).toFloat())
                    .uv(inner.toFloat(), outer.toFloat()).color(tip, 255, 255, a).endVertex()
            }
            v(-1.0, -1.0, -1.0); v(1.0, -1.0, -1.0); v(1.0, -1.0, 1.0); v(-1.0, -1.0, 1.0)
            v(-1.0, 1.0, -1.0); v(-1.0, 1.0, 1.0); v(1.0, 1.0, 1.0); v(1.0, 1.0, -1.0)
            v(-1.0, -1.0, -1.0); v(-1.0, 1.0, -1.0); v(1.0, 1.0, -1.0); v(1.0, -1.0, -1.0)
            v(-1.0, -1.0, 1.0); v(1.0, -1.0, 1.0); v(1.0, 1.0, 1.0); v(-1.0, 1.0, 1.0)
            v(-1.0, -1.0, -1.0); v(-1.0, -1.0, 1.0); v(-1.0, 1.0, 1.0); v(-1.0, 1.0, -1.0)
            v(1.0, -1.0, -1.0); v(1.0, 1.0, -1.0); v(1.0, 1.0, 1.0); v(1.0, -1.0, 1.0)
            buffers.endBatch(SpellVfxRenderTypes.BLACK_SUN)
        }

        override fun render(d: SignatureDraw) {
            val e = d.e
            val size = size(d.age, e.life)
            val core = BlackHoleSun.CORE * size
            val left = d.view.left; val up = d.view.up
            val time = d.time.toDouble()
            fun inView(angle: Double, r: Double): Vec3 = left.scale(cos(angle) * r).add(up.scale(sin(angle) * r))

            // The golden haze it hangs in, sinking softly into whatever it's near.
            (d.softGlow() ?: d.glow()).let { c ->
                SpellShapes.disc(c, d.m, d.view, Vec3.ZERO, core * 2.4, SUN_HAZE, 0.18f)
            }
            // The black sphere in its shell of churning fire, the gold event horizon at its edge.
            if (core > 0.01) sphere(d, core * HEART, core * DISK, fade(d, 4f, 4f).coerceAtLeast(0.05f))
            sunbeams(d, core)
            // The collapse's flare.
            val flare = ((d.age - (e.life - 4)) / 4f).coerceIn(0f, 1f)
            if (flare > 0f) (d.softGlow() ?: d.glow()).let { g ->
                SpellShapes.disc(g, d.m, d.view, Vec3.ZERO, BlackHoleSun.FLARE * flare, 0xFFE2A0, 1f - flare * 0.5f)
            }
        }
    }

    // ---- Rootquake ----------------------------------------------------------------------------------

    /** A quake as drawn: the same layout the server strikes with. */
    private class Quake(val layout: Rootquake.Layout)

    /** Points along the roots per lighting piece. */
    private const val ROOT_PIECE = 6

    /** Mangrove roots creeping out over the ground in a cone, then rooted dirt columns heaving up
     *  behind them and sinking back, every piece lit and shaded as the world's blocks are. */
    object RootquakeVisual : SignatureVisual {
        private fun quake(e: SignatureEntity): Quake =
            e.clientState as? Quake ?: Quake(Rootquake.layout(e.level(), e.position(), e.point, e.seed)).also { e.clientState = it }

        override fun render(d: SignatureDraw) {
            val q = quake(d.e)
            val front = d.age * Rootquake.FRONT_SPEED
            val sinkFade = 1f - ((d.age - Rootquake.SINK) / 20f).coerceIn(0f, 1f)
            // Each root, out as far as the front has run, tapering to its tip, each piece lit
            // where it lies.
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
            // The columns, each from deep in the ground to its risen top.
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
                // Lit by the air the column stands in, as the block it heaved up from was.
                val light = d.lightAt(Vec3(c.x, c.ground + 0.5, c.z))
                val head = maxOf(c.ground - 1.0, top - 1.0)
                shadedBox(solid, d.m, d.local(Vec3(c.x - 0.5, head, c.z - 0.5)), d.local(Vec3(c.x + 0.5, top, c.z + 0.5)), rooted, light)
                if (head > c.ground - 1.0) shadedBox(solid, d.m, d.local(Vec3(c.x - 0.5, c.ground - 1.0, c.z - 0.5)), d.local(Vec3(c.x + 0.5, head, c.z + 0.5)), dirt, light)
            }
        }
    }

    // ---- Prism Lance --------------------------------------------------------------------------------

    private val SPLIT_HUES = intArrayOf(0xFF6A6A, 0xFFD45A, 0x6AFF9A, 0x6AB8FF, 0xC86AFF)
    /** How long each band of the main beam's faint rainbow is, in blocks. */
    private const val BAND = 0.35
    /** How much of the rainbow shows through the beam's white: very little. */
    private const val RAINBOW = 0.28f

    /**
     * A clear crystal beam from the hand with the faintest rainbow running down it, and
     * saturated rainbow beams fanning from where it strikes a block and bouncing on; two that
     * merge run on brighter in both their colours, then part again. Its light is the show; it
     * gives off only a few snowflakes where it strikes.
     */
    object PrismLanceVisual : SignatureVisual {
        override fun tick(e: SignatureEntity) {
            val level = e.level()
            val p = e.point
            if (e.tickCount % 4 == 0) level.addParticle(ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, level.random.nextGaussian() * 0.04, 0.02, level.random.nextGaussian() * 0.04)
        }

        /** [rgb] washed toward white, leaving only [amount] of it. */
        private fun pale(rgb: Int, amount: Float): Int = SpellGeom.whiten(rgb, 1f - amount)

        /** A hue round the colour wheel, 0..1. */
        private fun hue(h: Double): Int {
            val x = ((h % 1.0) + 1.0) % 1.0 * 6
            val f = x - Math.floor(x)
            val (r, g, b) = when (x.toInt()) {
                0 -> Triple(1.0, f, 0.0); 1 -> Triple(1 - f, 1.0, 0.0); 2 -> Triple(0.0, 1.0, f)
                3 -> Triple(0.0, 1 - f, 1.0); 4 -> Triple(f, 0.0, 1.0); else -> Triple(1.0, 0.0, 1 - f)
            }
            return ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
        }

        /** [segments] cut into pieces about [step] long, as local points along it, with how far
         *  along each is. */
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
            // The lance: an icy halo, the faintest rainbow running down it, a white core.
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
            // The splits, each a bright hue, bouncing on.
            val owner = e.owner() ?: return
            val shields = SunderingBeamTrace.collectWieldedShields(d.level, owner) + AegisClient.snapshotDebugBoxes()
            val last = path.last()
            val rays = PrismLance.splits(d.level, owner, end.subtract(last.direction), end, e.seed, e.tickCount, shields)
            for (ray in rays) {
                if (ray.trace.segments.isEmpty()) continue
                // A merged ray carries both its splits' colours, blended, and runs brighter.
                val hues = ray.splits.map { SPLIT_HUES[it % SPLIT_HUES.size] }
                val rgb = if (hues.size == 1) hues[0] else blend(hues[0], hues[1])
                val wide = 1.0 + 0.45 * (hues.size - 1)
                beam(d, ray.trace.segments, 0.32 * wide, 0.07 * wide, rgb, f)
                for (seg in ray.trace.segments.drop(1)) (d.softGlow() ?: d.glow()).let { SpellShapes.disc(it, d.m, d.view, d.local(seg.start), 0.35, rgb, 0.5f * f) }
                if (hues.size > 1) (d.softGlow() ?: d.glow()).let { SpellShapes.disc(it, d.m, d.view, d.local(ray.trace.segments[0].start), 0.6, rgb, 0.7f * f) }
            }
        }

        /** Halfway between two colours. */
        private fun blend(a: Int, b: Int): Int {
            fun ch(c: Int, s: Int) = (c shr s) and 0xFF
            return (((ch(a, 16) + ch(b, 16)) / 2) shl 16) or (((ch(a, 8) + ch(b, 8)) / 2) shl 8) or ((ch(a, 0) + ch(b, 0)) / 2)
        }
    }
}
