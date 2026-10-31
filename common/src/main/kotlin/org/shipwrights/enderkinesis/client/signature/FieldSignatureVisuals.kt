package org.shipwrights.enderkinesis.client.signature

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.SpellArt
import org.shipwrights.enderkinesis.client.SpellGeom
import org.shipwrights.enderkinesis.client.SpellShapes
import org.shipwrights.enderkinesis.client.SpellVfxRenderTypes
import org.shipwrights.enderkinesis.client.StillpointGlowVolume
import org.shipwrights.enderkinesis.client.Sprite
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.spell.Signature
import org.shipwrights.enderkinesis.spell.signature.FieldSignatures.AcidRain
import org.shipwrights.enderkinesis.spell.signature.FieldSignatures.AuroraVeil
import org.shipwrights.enderkinesis.spell.signature.FieldSignatures.Cloud
import org.shipwrights.enderkinesis.spell.signature.FieldSignatures.GustOfGlass
import org.shipwrights.enderkinesis.spell.signature.FieldSignatures.NetherRift
import org.shipwrights.enderkinesis.spell.signature.FieldSignatures.Stillpoint
import org.shipwrights.enderkinesis.spell.signature.FieldSignatures.StormCloud
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.shipObjectWorld
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object FieldSignatureVisuals {
    val ALL: Map<Signature, SignatureVisual> = mapOf(
        Signature.STILLPOINT to StillpointVisual,
        Signature.AURORA_VEIL to AuroraVeilVisual,
        Signature.ACID_RAIN to AcidRainVisual,
        Signature.STORM_CLOUD to StormCloudVisual,
        Signature.GUST_OF_GLASS to GustOfGlassVisual,
        Signature.NETHER_RIFT to NetherRiftVisual,
    )

    private fun fade(d: SignatureDraw, inTicks: Float, outTicks: Float): Float =
        minOf(1f, d.age / inTicks, (d.e.life - d.age) / outTicks).coerceIn(0f, 1f)

    private fun inBall(random: Random, r: Double): Vec3 {
        val dir = SpellGeom.spread(random)
        return dir.scale(r * Math.cbrt(random.nextDouble()))
    }

    private fun inDisc(random: Random, r: Double): Vec3 {
        val a = random.nextDouble() * Math.PI * 2
        val d = r * sqrt(random.nextDouble())
        return Vec3(cos(a) * d, 0.0, sin(a) * d)
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t).toInt() shl s
        return ch(16) or ch(8) or ch(0)
    }

    /** Use depth-blended light when the shaders are loaded, else plain additive light. */
    private fun soft(d: SignatureDraw): VertexConsumer = d.softGlow() ?: d.glow()

    private fun flatDisc(c: VertexConsumer, m: Matrix4f, centre: Vec3, r: Double, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f) return
        val sectors = 16
        for (k in 0 until sectors) {
            val a0 = 2 * Math.PI * k / sectors; val a1 = 2 * Math.PI * (k + 1) / sectors
            SpellShapes.quad(
                c, m, centre, centre, centre.add(cos(a1) * r, 0.0, sin(a1) * r), centre.add(cos(a0) * r, 0.0, sin(a0) * r),
                rgb, alpha, alpha, 0f, 0f,
            )
        }
    }

    private fun eye(): Vec3 = Minecraft.getInstance().gameRenderer.mainCamera.position

    private const val FROST = 0xDFF4FF
    private const val STILL_CRYSTALS = 48
    /** No crystal or mote is nearer the eye than this. Crystals fade in over the same distance beyond it. */
    private const val STILL_CLEAR = 1.5

    object StillpointVisual : SignatureVisual {
        override fun tick(e: SignatureEntity) {
            // The server keeps it fixed at its point. Do the same here.
            e.deltaMovement = Vec3.ZERO
            if (e.position().distanceToSqr(e.point) > 1.0e-6) e.setPos(e.point)
            val level = e.level()
            val r = Stillpoint.radius(e.power)
            if (e.tickCount < 4 || e.tickCount > e.life - 8) return
            val eye = eye()
            repeat(8) {
                val p = e.position().add(inBall(Random(level.random.nextLong()), r * 0.95))
                if (p.distanceTo(eye) < STILL_CLEAR * 2) return@repeat
                level.addParticle(DustParticleOptions(Vector3f(0.88f, 0.95f, 1f), 0.8f), p.x, p.y, p.z, 0.0, 0.0, 0.0)
            }
            repeat(3) {
                val p = e.position().add(inBall(Random(level.random.nextLong()), r * 0.9))
                if (p.distanceTo(eye) < STILL_CLEAR * 2) return@repeat
                level.addParticle(if (level.random.nextBoolean()) ParticleTypes.END_ROD else ParticleTypes.SNOWFLAKE, p.x, p.y, p.z, 0.0, 0.0, 0.0)
            }
        }

        override fun render(d: SignatureDraw) {
            d.castFlash(d.at, FROST)
            val o = SpellGeom.smooth(fade(d, 8f, 12f))
            val r = Stillpoint.radius(d.e.power) * (0.2 + 0.8 * o)
            // 0 when the eye is outside, 1 when the eye is a quarter of the radius inside.
            val inside = SpellGeom.smooth(((r * 1.02 - d.view.cam.length()) / (r * 0.25)).toFloat())
            val glow = soft(d)
            val haloAlpha = 0.28f * o * (1f - inside)
            if (!StillpointGlowVolume.queue(d.at, r * 1.35, 0xCFE8FF, haloAlpha)) {
                SpellShapes.disc(glow, d.m, d.view, Vec3.ZERO, r * 1.35, 0xCFE8FF, haloAlpha)
            }
            SpellShapes.sphere(glow, d.m, d.view, Vec3.ZERO, r, FROST, 0.4f * o * (1f - 0.55f * inside))
            SpellShapes.sphere(glow, d.m, d.view, Vec3.ZERO, r * 0.97, 0xA8D8FF, 0.15f * o * (1f - inside))
            val c = d.blocks() ?: return
            val random = Random(d.e.seed)
            val ice = SpellArt.block("packed_ice")
            for (k in 0 until STILL_CRYSTALS) {
                val p = inBall(random, r * 0.85)
                val half = 0.05 + random.nextDouble() * 0.07
                val yaw = random.nextDouble() * 6; val pitch = random.nextDouble() * 6; val roll = random.nextDouble() * 6
                val near = ((p.distanceTo(d.view.cam) - STILL_CLEAR) / STILL_CLEAR).coerceIn(0.0, 1.0).toFloat()
                if (near <= 0f) continue
                SpellShapes.texBox(c, d.m, SpellGeom.cubeCorners(p, half, yaw, pitch, roll), ice, 0xFFFFFF, 0.9f * o * near, d.lightAt(d.at.add(p)), d.e.seed + k)
            }
        }
    }

    private const val AURORA_GREEN = 0x50FFAA
    private const val AURORA_BLUE = 0x78C8FF
    private const val AURORA_VIOLET = 0xBE78FF
    private const val VEIL_COLUMNS_PER_BLOCK = 6
    private const val VEIL_ROWS = 20
    private const val SHIMMER_TICKS = 26
    private const val SHIMMER_BAND = 0.7

    private class Shimmer(val id: Int, val start: Int)

    private class Veil(val seen: HashMap<Int, Int> = HashMap(), val shimmers: MutableList<Shimmer> = ArrayList())

    object AuroraVeilVisual : SignatureVisual {
        private fun veil(e: SignatureEntity): Veil = e.clientState as? Veil ?: Veil().also { e.clientState = it }

        override fun tick(e: SignatureEntity) {
            val v = veil(e)
            v.shimmers.removeAll { e.tickCount - it.start > SHIMMER_TICKS }
            if (e.tickCount < AuroraVeil.RISEN || e.tickCount > AuroraVeil.FADING) return
            val level = e.level()
            val n = AuroraVeil.facing(e)
            val across = Vec3(-n.z, 0.0, n.x)
            val r = level.random
            val w = AuroraVeil.width(e.power)
            repeat(3) {
                val p = e.position().add(across.scale((r.nextDouble() - 0.5) * w)).add(0.0, 0.2 + r.nextDouble() * (AuroraVeil.HEIGHT - 0.4), 0.0)
                val tint = if (r.nextBoolean()) Vector3f(0.5f, 1f, 0.7f) else Vector3f(0.78f, 0.56f, 1f)
                level.addParticle(DustParticleOptions(tint, 0.9f), p.x, p.y, p.z, 0.0, 0.02, 0.0)
                if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.END_ROD, p.x, p.y, p.z, 0.0, 0.01, 0.0)
            }
            // Use the server's own test and cooldown, so a shimmer shows only on a real cleanse.
            val foot = e.position()
            val box = AABB(foot, foot.add(0.0, AuroraVeil.HEIGHT, 0.0)).inflate(w / 2 + 2.0, 0.5, w / 2 + 2.0)
            for (target in level.getEntitiesOfClass(LivingEntity::class.java, box)) {
                if (target.isSpectator || !target.isAlive || !AuroraVeil.inVeil(e, target, foot)) continue
                if (AuroraVeil.cleansed(v.seen, target, e.tickCount)) v.shimmers += Shimmer(target.id, e.tickCount)
            }
            for (s in v.shimmers) {
                val target = level.getEntity(s.id) ?: continue
                val t = (e.tickCount - s.start).toDouble() / SHIMMER_TICKS
                val y = target.y + target.bbHeight * t
                repeat(2) {
                    val a = r.nextDouble() * Math.PI * 2
                    val rr = target.bbWidth * 0.6
                    level.addParticle(ParticleTypes.END_ROD, target.x + cos(a) * rr, y, target.z + sin(a) * rr, 0.0, 0.04, 0.0)
                }
                if (r.nextInt(2) == 0) level.addParticle(ParticleTypes.FIREWORK, target.x, y, target.z, (r.nextDouble() - 0.5) * 0.15, 0.08, (r.nextDouble() - 0.5) * 0.15)
            }
        }

        /** [f] is the height fraction: 0 at the foot, 1 at the top. */
        private fun colour(f: Float): Int = if (f < 0.5f) mix(AURORA_GREEN, AURORA_BLUE, (f - 0.15f) / 0.4f) else mix(AURORA_BLUE, AURORA_VIOLET, (f - 0.55f) / 0.45f)

        private fun alpha(f: Float): Float = when {
            f < 0.15f -> f / 0.15f * 0.9f
            f < 0.55f -> 0.9f - (f - 0.15f) / 0.4f * 0.3f
            else -> 0.6f * (1f - (f - 0.55f) / 0.45f)
        }.coerceAtLeast(0f)

        override fun render(d: SignatureDraw) {
            d.castFlash(d.at.add(0.0, AuroraVeil.HEIGHT / 2, 0.0), AURORA_GREEN)
            val o = SpellGeom.smooth(fade(d, 15f, 25f))
            val n = AuroraVeil.facing(d.e)
            val across = Vec3(-n.z, 0.0, n.x)
            val glow = soft(d)
            if (o > 0f) {
                // Paler back sheets out of phase make the curtain look folded.
                sheet(d, glow, n, across, 0.0, 0f, 0.95f * o)
                sheet(d, glow, n, across, -0.4, 2.1f, 0.5f * o)
                sheet(d, glow, n, across, 0.35, 4.7f, 0.3f * o)
            }
            for (s in veil(d.e).shimmers) shimmer(d, glow, s)
        }

        private fun sheet(d: SignatureDraw, glow: VertexConsumer, n: Vec3, across: Vec3, back: Double, phase: Float, alpha: Float) {
            val t = d.age + phase * 20
            val w = AuroraVeil.width(d.e.power); val h = AuroraVeil.HEIGHT
            val columns = (w * VEIL_COLUMNS_PER_BLOCK).toInt()
            val rays = FloatArray(columns + 1) { i ->
                val x = -w / 2 + w * i / columns
                (0.55 + 0.45 * sin(x * 3.7 + t * 0.21) * sin(x * 1.1 - t * 0.05)).toFloat() *
                    (1f - (Math.abs(x) / (w / 2)).toFloat().let { it * it * it * it })
            }
            val m = d.m
            var lo = DoubleArray(3 * (columns + 1)); var hi = DoubleArray(3 * (columns + 1))
            sheetRow(lo, 0, columns, w, h, t, n, across, back)
            for (j in 0 until VEIL_ROWS) {
                sheetRow(hi, j + 1, columns, w, h, t, n, across, back)
                val f0 = j.toFloat() / VEIL_ROWS; val f1 = (j + 1).toFloat() / VEIL_ROWS
                val rgb = colour((f0 + f1) / 2)
                val red = (rgb shr 16) and 0xFF; val green = (rgb shr 8) and 0xFF; val blue = rgb and 0xFF
                val a0 = alpha * alpha(f0); val a1 = alpha * alpha(f1)
                for (i in 0 until columns) {
                    val r0 = rays[i]; val r1 = rays[i + 1]
                    SpellShapes.vertex(glow, m, lo[3 * i], lo[3 * i + 1], lo[3 * i + 2], red, green, blue, a0 * r0)
                    SpellShapes.vertex(glow, m, lo[3 * i + 3], lo[3 * i + 4], lo[3 * i + 5], red, green, blue, a0 * r1)
                    SpellShapes.vertex(glow, m, hi[3 * i + 3], hi[3 * i + 4], hi[3 * i + 5], red, green, blue, a1 * r1)
                    SpellShapes.vertex(glow, m, hi[3 * i], hi[3 * i + 1], hi[3 * i + 2], red, green, blue, a1 * r0)
                }
                val swap = lo; lo = hi; hi = swap
            }
        }

        private fun sheetRow(out: DoubleArray, j: Int, columns: Int, w: Double, h: Double, t: Float, n: Vec3, across: Vec3, back: Double) {
            val f = j.toDouble() / VEIL_ROWS
            for (i in 0..columns) {
                val x = -w / 2 + w * i / columns
                val sway = sin(x * 1.1 + t * 0.11 + f * 0.8) * 0.55 +
                    sin(x * 2.4 - t * 0.08 + f * 2.2) * 0.32 +
                    sin(x * 4.3 + t * 0.17 - f * 3.0) * 0.18 +
                    sin(x * 0.5 - t * 0.045) * 0.35 * (0.4 + 0.6 * f)
                val slide = sin(f * Math.PI * 0.9 + t * 0.06 + x * 0.7) * 0.25
                val s = x + slide; val k = sway * (0.55 + 0.45 * f) + back
                out[3 * i] = across.x * s + n.x * k
                out[3 * i + 1] = across.y * s + n.y * k + (0.05 + h * f)
                out[3 * i + 2] = across.z * s + n.z * k
            }
        }

        private fun shimmer(d: SignatureDraw, glow: VertexConsumer, s: Shimmer) {
            val target = d.level.getEntity(s.id) ?: return
            val t = ((d.age - s.start) / SHIMMER_TICKS).coerceIn(0f, 1f)
            if (t >= 1f) return
            val pos = d.local(target.getPosition(d.partialTick))
            val w = target.bbWidth / 2.0 + 0.15
            val h = target.bbHeight + 0.2
            val base = pos.y - 0.05
            val rise = SpellGeom.easeOut(t.toDouble())
            val band = base + (h + SHIMMER_BAND) * rise - SHIMMER_BAND / 2
            val a = 0.95f * (1f - t * t)
            val rgb = colour((rise * 0.9).toFloat())
            fun y(v: Double) = v.coerceIn(base, base + h)
            val corners = listOf(Vec3(-w, 0.0, -w), Vec3(w, 0.0, -w), Vec3(w, 0.0, w), Vec3(-w, 0.0, w)).map { it.add(pos.x, 0.0, pos.z) }
            for (k in 0 until 4) {
                val p = corners[k]; val q = corners[(k + 1) % 4]
                fun at(c: Vec3, yy: Double) = Vec3(c.x, yy, c.z)
                val lo = y(band - SHIMMER_BAND / 2); val mid = y(band); val hi = y(band + SHIMMER_BAND / 2)
                SpellShapes.quad(glow, d.m, at(p, lo), at(q, lo), at(q, mid), at(p, mid), rgb, 0f, 0f, a, a)
                SpellShapes.quad(glow, d.m, at(p, mid), at(q, mid), at(q, hi), at(p, hi), rgb, a, a, 0f, 0f)
                SpellShapes.quad(glow, d.m, at(p, base), at(q, base), at(q, lo), at(p, lo), AURORA_GREEN, 0.25f * a, 0.25f * a, 0.1f * a, 0.1f * a)
            }
            SpellShapes.ring(glow, d.m, Vec3(pos.x, y(band), pos.z), w * 1.4, 0.18, rgb, 0.8f * a, 0.0)
            SpellShapes.ring(glow, d.m, Vec3(pos.x, y(band - 0.25), pos.z), w * 1.1, 0.1, AURORA_VIOLET, 0.5f * a, 0.0)
            SpellShapes.disc(glow, d.m, d.view, Vec3(pos.x, base + h / 2, pos.z), h * 0.8, AURORA_BLUE, 0.25f * (1f - t))
        }
    }

    /** Cell size in blocks: vanilla fancy clouds (12 x 12 x 4) at a smaller scale. */
    private const val CELL = 2.0
    private const val CELL_THICK = 1.0
    /** Vanilla cloud face shading: top, bottom, x faces, z faces. */
    private const val SHADE_TOP = 1f
    private const val SHADE_BOTTOM = 0.7f
    private const val SHADE_X = 0.9f
    private const val SHADE_Z = 0.8f
    /** Grid slide speed as a multiple of [Cloud.WIND_SPEED]: base, extra for each layer, and veer for each layer in radians. */
    private const val SLIDE = 1.5
    private const val SLIDE_LAYER = 0.8
    private const val SLIDE_VEER = 0.2
    /** Edge churn (radians a tick), footprint breathe and centre wander (fractions of radius), layer heave (blocks). */
    private const val CHURN = 0.03
    private const val BILLOW = 0.08
    private const val WANDER = 0.1
    private const val HEAVE = 0.12
    /** Cells nearer the footprint edge than this are smaller and fainter, so they grow and fade in. */
    private const val EDGE_SOFT = 0.15
    /** Grow-in time of each cell. The middle starts first and the edge last, all within [Cloud.GROW]. */
    private const val CELL_GROW = 12f
    private const val CELL_MIN = 0.25
    /** Rain falls on a column only where the bottom-layer cell above it has at least this amount. */
    private const val RAIN_CELL = 0.5
    /** Fraction of the radius. It must cover the furthest reach of the churning edge. */
    private const val COLUMN_REACH = 1.4
    /** Same as the vanilla rain fade distance. */
    private const val RAIN_FADE = 10.0
    private const val LANDING_EVERY = 8
    private val RAIN = Sprite(ResourceLocation("textures/environment/rain.png"), 0f, 0f, 1f, 1f)
    private val NO_LANDING = Double.NaN

    /** Vanilla cloud passes: depth only, then colour with LEQUAL. Thus a translucent cloud hides its own far faces. */
    private val CLOUD_DEPTH: RenderType by lazy { cloudType("depth", RenderStateShard.DEPTH_WRITE) }
    private val CLOUD_COLOUR: RenderType by lazy { cloudType("colour", RenderStateShard.COLOR_DEPTH_WRITE) }

    private fun cloudType(pass: String, mask: RenderStateShard.WriteMaskStateShard): RenderType = RenderType.create(
        "${EnderkinesisMod.MOD_ID}_signature_cloud_$pass",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        4096,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
            .setWriteMaskState(mask)
            .setCullState(RenderStateShard.NO_CULL)
            .setOutputState(RenderStateShard.MAIN_TARGET)
            .createCompositeState(false),
    )

    private class Hailstone(var pos: Vec3, var vel: Vec3, val size: Double, val spin: Double) {
        var prev: Vec3 = pos
        var bounces = 0
        var resting = 0
    }

    private class Cell(val i: Int, val l: Int, val k: Int, val amount: Double)

    /** [offsets] are relative to block [ox], [oz]. [tops] holds NaN where nothing is within the rain's fall. */
    private class Sky(val seed: Int, val radius: Double, val layers: Int) {
        val offsets: List<IntArray>
        val index = HashMap<Long, Int>()
        val tops: DoubleArray
        var ox = Int.MIN_VALUE
        var oz = Int.MIN_VALUE
        var scanned = Int.MIN_VALUE
        val hail = ArrayList<Hailstone>()

        init {
            val reach = radius * COLUMN_REACH
            val out = ArrayList<IntArray>()
            val ri = Math.ceil(reach).toInt()
            for (dx in -ri..ri) for (dz in -ri..ri) {
                if (dx * dx + dz * dz > reach * reach) continue
                index[column(dx, dz)] = out.size
                out += intArrayOf(dx, dz)
            }
            offsets = out
            tops = DoubleArray(out.size) { NO_LANDING }
        }

        fun x(n: Int): Int = ox + offsets[n][0]
        fun z(n: Int): Int = oz + offsets[n][1]

        fun at(x: Int, z: Int): Int? = index[column(x - ox, z - oz)]

        fun slideX(l: Int, age: Float): Double = cos(SLIDE_VEER * l) * Cloud.WIND_SPEED * (SLIDE + SLIDE_LAYER * l) * age
        fun slideZ(l: Int, age: Float): Double = sin(SLIDE_VEER * l) * Cloud.WIND_SPEED * (SLIDE + SLIDE_LAYER * l) * age

        fun lift(l: Int, age: Float): Double = l * CELL_THICK + HEAVE * sin(age * 0.05 + l * 2.1 + seed)

        /** Returns 0 to 1. A cell never has more amount than the layer-below cell under it. */
        fun amount(i: Int, l: Int, k: Int, age: Float, cx: Double, cz: Double): Double {
            val x = (i + 0.5) * CELL + slideX(l, age); val z = (k + 0.5) * CELL + slideZ(l, age)
            val mx = cx + cos(age * 0.008 + seed) * radius * WANDER
            val mz = cz + sin(age * 0.008 + seed) * radius * WANDER
            val reach = (if (l == 0) radius else radius * 0.6 / l) * (1 + BILLOW * sin(age * 0.025 + l * 1.7 + seed))
            val d = sqrt((x - mx) * (x - mx) + (z - mz) * (z - mz)) / reach
            val n = hash(seed, i, l, k)
            val churn = sin(age * CHURN * (0.5 + hash(seed + 1, i, l, k)) + hash(seed + 2, i, l, k) * Math.PI * 2)
            val edge = ((0.72 + 0.28 * n + 0.15 * churn - d) / EDGE_SOFT).coerceIn(0.0, 1.0)
            if (edge <= 0.0) return 0.0
            val delay = d.coerceAtMost(1.0) * (Cloud.GROW - CELL_GROW) + l * 3
            val grow = ((age - delay) / CELL_GROW).coerceIn(0.0, 1.0)
            var a = SpellGeom.smooth(minOf(edge, grow))
            if (l > 0) a = minOf(a, under(x, z, l - 1, age, cx, cz))
            return a
        }

        fun under(x: Double, z: Double, l: Int, age: Float, cx: Double, cz: Double): Double =
            amount(floor((x - slideX(l, age)) / CELL).toInt(), l, floor((z - slideZ(l, age)) / CELL).toInt(), age, cx, cz)

        fun cells(age: Float, cx: Double, cz: Double): List<Cell> {
            val list = ArrayList<Cell>()
            val reach = radius * COLUMN_REACH + CELL
            for (l in 0 until layers) {
                val sx = slideX(l, age); val sz = slideZ(l, age)
                for (i in floor((cx - reach - sx) / CELL).toInt()..floor((cx + reach - sx) / CELL).toInt()) {
                    for (k in floor((cz - reach - sz) / CELL).toInt()..floor((cz + reach - sz) / CELL).toInt()) {
                        val a = amount(i, l, k, age, cx, cz)
                        if (a > 0.0) list += Cell(i, l, k, a)
                    }
                }
            }
            return list
        }

        fun rainsOn(n: Int, age: Float, cx: Double, cz: Double): Boolean = under(x(n) + 0.5, z(n) + 0.5, 0, age, cx, cz) >= RAIN_CELL
    }

    private fun key(i: Int, l: Int, k: Int): Long = ((i.toLong() and 0x1FFFFF) shl 42) or ((l.toLong() and 0x1FFFFF) shl 21) or (k.toLong() and 0x1FFFFF)

    private fun column(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    /** Deterministic 0..1 hash, so every client gets the same cloud. */
    private fun hash(a: Int, b: Int, c: Int, d: Int): Double {
        var h = a.toLong() * 0x9E3779B1L + b.toLong() * 0x85EBCA6BL + c.toLong() * 0xC2B2AE35L + d.toLong() * 0x27D4EB2FL
        h = h xor (h ushr 29); h *= 0x2C1B3C6DL; h = h xor (h ushr 32); h *= 0x297A2D39L; h = h xor (h ushr 29)
        return ((h ushr 11) and 0xFFFFFFL).toDouble() / 0x1000000
    }

    private fun sky(e: SignatureEntity, radius: Double, layers: Int): Sky =
        e.clientState as? Sky ?: Sky(e.seed, radius, layers).also { e.clientState = it }

    /** Rain stops where vanilla rain stops (motion-blocking heightmap, or below a roof), and also on ship blocks. */
    private fun follow(e: SignatureEntity, cloud: Cloud, sky: Sky) {
        cloud.drift(e)
        val bx = floor(e.x).toInt(); val bz = floor(e.z).toInt()
        if (bx == sky.ox && bz == sky.oz && e.tickCount - sky.scanned < LANDING_EVERY) return
        sky.ox = bx; sky.oz = bz
        sky.scanned = e.tickCount
        val level = e.level()
        val top = e.y
        val bottom = top - cloud.fall
        val reach = sky.radius * COLUMN_REACH
        val ships = level.shipObjectWorld.loadedShips.mapNotNull { it as? ClientShip }.filter {
            val b = it.renderAABB
            b.maxX() >= e.x - reach && b.minX() <= e.x + reach && b.maxZ() >= e.z - reach && b.minZ() <= e.z + reach &&
                b.maxY() >= bottom && b.minY() <= top
        }
        val pos = BlockPos.MutableBlockPos()
        val local = Vector3d()
        for (n in sky.offsets.indices) {
            val x = sky.x(n); val z = sky.z(n)
            var best = NO_LANDING
            val surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
            pos.set(x, if (surface <= top) surface - 1 else floor(top - 0.01).toInt(), z)
            while (pos.y >= bottom) {
                val state = level.getBlockState(pos)
                if (!state.fluidState.isEmpty) { best = pos.y + state.fluidState.getHeight(level, pos).toDouble(); break }
                val shape = state.getCollisionShape(level, pos)
                if (!shape.isEmpty) { best = pos.y + shape.max(Direction.Axis.Y); break }
                pos.move(0, -1, 0)
            }
            for (ship in ships) {
                var y = top
                val floorY = if (best.isNaN()) bottom else best
                while (y > floorY) {
                    ship.renderTransform.worldToShip.transformPosition(x + 0.5, y, z + 0.5, local)
                    if (!level.getBlockState(BlockPos.containing(local.x, local.y, local.z)).isAir) { best = y; break }
                    y -= 0.5
                }
            }
            sky.tops[n] = minOf(best, top)
        }
    }

    private fun splash(e: SignatureEntity, sky: Sky, extra: ((Vec3) -> Unit)? = null) {
        if (sky.offsets.isEmpty()) return
        val level = e.level()
        val r = level.random
        val age = e.tickCount.toFloat()
        repeat(2 + sky.offsets.size / 10) {
            val n = r.nextInt(sky.offsets.size)
            val y = sky.tops[n]
            if (y.isNaN() || !sky.rainsOn(n, age, e.x, e.z)) return@repeat
            val p = Vec3(sky.x(n) + r.nextDouble(), y + 0.01, sky.z(n) + r.nextDouble())
            level.addParticle(ParticleTypes.RAIN, p.x, p.y, p.z, 0.0, 0.0, 0.0)
            extra?.invoke(p)
        }
        if (e.tickCount % 3 == 0) {
            val n = r.nextInt(sky.offsets.size)
            val y = sky.tops[n]
            if (!y.isNaN()) level.playLocalSound(sky.x(n) + 0.5, y, sky.z(n) + 0.5, SoundEvents.WEATHER_RAIN, SoundSource.WEATHER, 0.5f, 1f, false)
        }
    }

    private fun rainAmount(e: SignatureEntity, age: Float): Float =
        minOf((age - Cloud.FORM) / 6f, (e.life - Cloud.FADE - age) / 8f + 1f).coerceIn(0f, 1f)

    /** Only the fade-out. The growth of each cell gives the fade-in. */
    private fun cloudAmount(d: SignatureDraw): Float =
        SpellGeom.smooth(((d.e.life - d.age) / Cloud.FADE).coerceIn(0f, 1f))

    /** The night darkening comes from the cloud colour, so do not apply it here. */
    private fun cellLight(packed: Int): Float {
        val level = maxOf(LightTexture.block(packed), LightTexture.sky(packed))
        return CELL_DARKEST + (1f - CELL_DARKEST) * level / 15f
    }

    private const val CELL_DARKEST = 0.25f

    private const val CLOUD_FACES_INITIAL = 256

    private class CloudFaces {
        var size = 0
            private set
        var corners = DoubleArray(12 * CLOUD_FACES_INITIAL)
            private set
        var rgbs = IntArray(CLOUD_FACES_INITIAL)
            private set
        var alphas = FloatArray(CLOUD_FACES_INITIAL)
            private set

        fun clear() {
            size = 0
        }

        fun add(
            x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double,
            x2: Double, y2: Double, z2: Double, x3: Double, y3: Double, z3: Double, rgb: Int, alpha: Float,
        ) {
            if (size == rgbs.size) {
                corners = corners.copyOf(corners.size * 2); rgbs = rgbs.copyOf(rgbs.size * 2); alphas = alphas.copyOf(alphas.size * 2)
            }
            val o = 12 * size
            corners[o] = x0; corners[o + 1] = y0; corners[o + 2] = z0
            corners[o + 3] = x1; corners[o + 4] = y1; corners[o + 5] = z1
            corners[o + 6] = x2; corners[o + 7] = y2; corners[o + 8] = z2
            corners[o + 9] = x3; corners[o + 10] = y3; corners[o + 11] = z3
            rgbs[size] = rgb; alphas[size] = alpha
            size++
        }
    }

    // Render-thread only scratch.
    private val cloudWhole = LongOpenHashSet()
    private val cloudFaces = CloudFaces()

    private fun drawCloud(d: SignatureDraw, sky: Sky, rgb: Int, alpha: Float, flash: Float = 0f) {
        if (alpha <= 0.004f) return
        val tint = d.level.getCloudColor(d.partialTick)
        val base = if (flash > 0f) mix(rgb, 0xE8EEFF, flash) else rgb
        fun shaded(f: Float, lit: Float): Int {
            val k = f * lit
            val r = (((base shr 16) and 0xFF) * k * tint.x).toInt().coerceIn(0, 255)
            val g = (((base shr 8) and 0xFF) * k * tint.y).toInt().coerceIn(0, 255)
            val b = ((base and 0xFF) * k * tint.z).toInt().coerceIn(0, 255)
            return (r shl 16) or (g shl 8) or b
        }
        val cam = d.view.cam
        val cells = sky.cells(d.age, d.at.x, d.at.z)
        val whole = cloudWhole
        whole.clear()
        for (c in cells) if (c.amount >= 0.999) whole.add(key(c.i, c.l, c.k))
        val faces = cloudFaces
        faces.clear()
        for (cell in cells) {
            val l = cell.l
            val s = CELL_MIN + (1 - CELL_MIN) * cell.amount
            val a = alpha * cell.amount.toFloat()
            val mx = (cell.i + 0.5) * CELL + sky.slideX(l, d.age) - d.at.x
            val mz = (cell.k + 0.5) * CELL + sky.slideZ(l, d.age) - d.at.z
            val my = sky.lift(l, d.age) + CELL_THICK / 2
            val hx = CELL / 2 * s; val hy = CELL_THICK / 2 * s
            val x0 = mx - hx; val x1 = mx + hx; val y0 = my - hy; val y1 = my + hy; val z0 = mz - hx; val z1 = mz + hx
            val isWhole = cell.amount >= 0.999
            val lit = cellLight(d.lightAt(d.at.add(mx, my, mz)))
            fun face(
                di: Int, dl: Int, dk: Int, shade: Float,
                ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double,
                cx: Double, cy: Double, cz: Double, ex: Double, ey: Double, ez: Double,
            ) {
                if (isWhole && dl == 0 && whole.contains(key(cell.i + di, l, cell.k + dk))) return
                val toCamera = (cam.x - (ax + cx) * 0.5) * di + (cam.y - (ay + cy) * 0.5) * dl + (cam.z - (az + cz) * 0.5) * dk
                if (toCamera <= 0) return
                faces.add(ax, ay, az, bx, by, bz, cx, cy, cz, ex, ey, ez, shaded(shade, lit), a)
            }
            face(0, 1, 0, SHADE_TOP, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1)
            face(0, -1, 0, SHADE_BOTTOM, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1)
            face(1, 0, 0, SHADE_X, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1)
            face(-1, 0, 0, SHADE_X, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1)
            face(0, 0, 1, SHADE_Z, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1)
            face(0, 0, -1, SHADE_Z, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0)
        }
        drawCloudFaces(d, d.buffers.getBuffer(CLOUD_DEPTH), faces)
        drawCloudFaces(d, d.buffers.getBuffer(CLOUD_COLOUR), faces)
    }

    private fun drawCloudFaces(d: SignatureDraw, c: VertexConsumer, faces: CloudFaces) {
        val m = d.m
        val p = faces.corners
        for (f in 0 until faces.size) {
            val rgb = faces.rgbs[f]; val a = faces.alphas[f]
            val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
            for (v in 0 until 4) {
                val o = 12 * f + 3 * v
                SpellShapes.vertex(c, m, p[o], p[o + 1], p[o + 2], r, g, b, a)
            }
        }
    }

    /** Call after [drawCloud], because the rain tests against the cloud depth. */
    private fun drawRain(d: SignatureDraw, sky: Sky, fall: Double, rgb: Int, alpha: Float) {
        if (alpha <= 0.004f || !SpellVfxRenderTypes.isReady() || sky.ox == Int.MIN_VALUE) return
        val c = d.buffers.getBuffer(SpellVfxRenderTypes.sprite(RAIN.texture))
        val cam = d.view.cam.add(d.at)
        val top = d.at.y
        for (n in sky.offsets.indices) {
            if (!sky.rainsOn(n, d.age, d.at.x, d.at.z)) continue
            val bottom = sky.tops[n].takeUnless { it.isNaN() } ?: (top - fall)
            if (top - bottom < 0.05) continue
            val bx = sky.x(n); val bz = sky.z(n)
            val x = bx + 0.5; val z = bz + 0.5
            val dx = x - cam.x; val dz = z - cam.z
            val len = sqrt(dx * dx + dz * dz)
            val sx = if (len < 1.0e-3) 0.5 else -dz / len * 0.5
            val sz = if (len < 1.0e-3) 0.0 else dx / len * 0.5
            // Vanilla per-column rain hash: it sets the texture offset and speed of each column.
            val hash = bx * bx * 3121 + bx * 45238971 xor bz * bz * 418711 + bz * 13761
            val speed = (3f + hash(hash, 0, 0, 0).toFloat()) / 32f
            val scroll = -((hash and 31) + d.age) * speed
            val f = (len / RAIN_FADE).coerceAtMost(1.0).toFloat()
            val a = ((1f - f * f) * 0.5f + 0.5f) * alpha
            val light = d.lightAt(Vec3(x, maxOf(bottom, cam.y), z))
            val vTop = (bottom * 0.25).toFloat() + scroll
            val vBottom = (top * 0.25).toFloat() + scroll
            SpellShapes.tquad(
                c, d.m,
                d.local(Vec3(x - sx, top, z - sz)), d.local(Vec3(x + sx, top, z + sz)),
                d.local(Vec3(x + sx, bottom, z + sz)), d.local(Vec3(x - sx, bottom, z - sz)),
                RAIN, rainUv(vTop, vBottom), rgb, a, light,
            )
        }
    }

    // Render-thread only scratch. tquad reads it at once.
    private val rainUvScratch = floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f, 0f, 0f)

    private fun rainUv(vTop: Float, vBottom: Float): FloatArray {
        val uv = rainUvScratch
        uv[1] = vTop; uv[3] = vTop; uv[5] = vBottom; uv[7] = vBottom
        return uv
    }

    private const val ACID_CLOUD = 0x86B83E
    /** The rain texture is pale blue-white, so it needs a strong tint to look acid green. */
    private const val ACID_RAIN = 0x38FF10
    private const val ACID_FLASH = 0x70FF30
    private const val ACID_LAYERS = 2

    object AcidRainVisual : SignatureVisual {
        // The cloud writes depth. In the entity pass, it cut out the water that drew after it.
        override val afterTranslucent = true

        override fun tick(e: SignatureEntity) {
            val sky = sky(e, AcidRain.radius(e.power), ACID_LAYERS)
            follow(e, AcidRain, sky)
            if (!AcidRain.raining(e, e.tickCount)) return
            val level = e.level()
            splash(e, sky) { p ->
                level.addParticle(DustParticleOptions(AcidRain.ACID, 1.0f), p.x, p.y + 0.05, p.z, 0.0, 0.03, 0.0)
                if (level.random.nextInt(10) == 0) level.addParticle(ParticleTypes.SMOKE, p.x, p.y + 0.1, p.z, 0.0, 0.03, 0.0)
            }
            if (e.tickCount % 10 == 0) level.playLocalSound(e.x, e.y - AcidRain.height, e.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.4f, 1.8f, false)
        }

        override fun render(d: SignatureDraw) {
            d.castFlash(d.at.add(0.0, CELL_THICK / 2, 0.0), ACID_FLASH)
            val sky = sky(d.e, AcidRain.radius(d.e.power), ACID_LAYERS)
            drawCloud(d, sky, ACID_CLOUD, 0.85f * cloudAmount(d))
            drawRain(d, sky, AcidRain.fall, ACID_RAIN, rainAmount(d.e, d.age))
        }
    }

    private const val STORM_CLOUD = 0x5E6370
    /** At or above this power, the storm cloud has a third layer. */
    private const val STORM_TOWER = 0.6f
    private const val HAIL_RATE = 5
    private const val HAIL_RATE_POWER = 8
    private const val HAIL_SPEED = 0.5
    private const val HAIL_GRAVITY = 0.05
    private const val HAIL_BOUNCE = 0.35
    private const val HAIL_BOUNCES = 2
    private const val HAIL_LIES = 14
    private const val HAIL_MAX = 600

    object StormCloudVisual : SignatureVisual {
        // The cloud writes depth. In the entity pass, it cut out the water that drew after it.
        override val afterTranslucent = true

        private fun layers(e: SignatureEntity) = if (e.power >= STORM_TOWER) 3 else 2

        override fun tick(e: SignatureEntity) {
            val sky = sky(e, StormCloud.radius(e.power), layers(e))
            follow(e, StormCloud, sky)
            tickHail(e, sky)
            if (!StormCloud.raining(e, e.tickCount)) return
            splash(e, sky)
            val level = e.level()
            if (level.random.nextInt(2) == 0) {
                val p = e.position().add(inDisc(Random(level.random.nextLong()), StormCloud.radius(e.power))).add(0.0, -0.2, 0.0)
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, 0.0, -0.05, 0.0)
            }
        }

        private fun tickHail(e: SignatureEntity, sky: Sky) {
            val level = e.level()
            val r = level.random
            val age = e.tickCount.toFloat()
            if (StormCloud.raining(e, e.tickCount) && sky.offsets.isNotEmpty() && sky.hail.size < HAIL_MAX) {
                repeat(HAIL_RATE + (HAIL_RATE_POWER * e.power).toInt()) {
                    val n = r.nextInt(sky.offsets.size)
                    if (!sky.rainsOn(n, age, e.x, e.z)) return@repeat
                    val p = Vec3(sky.x(n) + r.nextDouble(), e.y - 0.1, sky.z(n) + r.nextDouble())
                    val v = Vec3(Cloud.WIND_SPEED + (r.nextDouble() - 0.5) * 0.04, -HAIL_SPEED - r.nextDouble() * 0.2, (r.nextDouble() - 0.5) * 0.04)
                    sky.hail += Hailstone(p, v, 0.05 + r.nextDouble() * 0.04, r.nextDouble() * 6)
                }
            }
            var clatter = 0
            val stones = sky.hail.iterator()
            while (stones.hasNext()) {
                val h = stones.next()
                h.prev = h.pos
                if (h.resting > 0) { if (++h.resting > HAIL_LIES) stones.remove(); continue }
                h.vel = h.vel.add(0.0, -HAIL_GRAVITY, 0.0)
                h.pos = h.pos.add(h.vel)
                if (h.pos.y < e.y - StormCloud.fall) { stones.remove(); continue }
                val n = sky.at(floor(h.pos.x).toInt(), floor(h.pos.z).toInt()) ?: continue
                val top = sky.tops[n]
                if (top.isNaN() || h.pos.y - h.size > top) continue
                h.pos = Vec3(h.pos.x, top + h.size, h.pos.z)
                if (h.bounces < HAIL_BOUNCES && h.vel.y < -0.1) {
                    h.bounces++
                    h.vel = Vec3(h.vel.x * 0.5 + (r.nextDouble() - 0.5) * 0.12, -h.vel.y * HAIL_BOUNCE, h.vel.z * 0.5 + (r.nextDouble() - 0.5) * 0.12)
                    if (clatter < 3 && r.nextInt(4) == 0) {
                        clatter++
                        val sound = if (r.nextBoolean()) SoundEvents.GLASS_HIT else SoundEvents.STONE_HIT
                        level.playLocalSound(h.pos.x, h.pos.y, h.pos.z, sound, SoundSource.WEATHER, 0.35f, 1.7f + r.nextFloat() * 0.3f, false)
                    }
                    if (r.nextInt(6) == 0) level.addParticle(BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()), h.pos.x, h.pos.y, h.pos.z, 0.0, 0.0, 0.0)
                } else {
                    h.vel = Vec3.ZERO
                    h.resting = 1
                }
            }
        }

        override fun render(d: SignatureDraw) {
            d.castFlash(d.at.add(0.0, CELL_THICK / 2, 0.0), StormCloud.BOLT)
            val o = cloudAmount(d)
            val r = StormCloud.radius(d.e.power)
            val sky = sky(d.e, r, layers(d.e))
            // Draw the lightning just under the cloud, so the cloud depth does not hide it.
            val beat = d.e.tickCount / 3
            val flick = Random(d.e.seed * 31 + beat)
            val flickers = StormCloud.raining(d.e, d.e.tickCount) && flick.nextInt(3) == 0
            drawCloud(d, sky, STORM_CLOUD, 0.9f * o, if (flickers) 0.35f else 0f)
            if (flickers) {
                val a = inDisc(flick, r * 0.8).add(0.0, -0.15, 0.0)
                val b = inDisc(flick, r * 0.8).add(0.0, -0.3, 0.0)
                val glow = d.glow()
                val path = SpellGeom.jagged(flick, a, b, 0.2)
                SpellShapes.strip(glow, d.m, d.view, path, { 0.35 }, StormCloud.BOLT, 0.45f * o, soft = true)
                SpellShapes.strip(glow, d.m, d.view, path, { 0.07 }, 0xFFFFFF, 0.9f * o, soft = false)
                SpellShapes.disc(glow, d.m, d.view, a.lerp(b, 0.5), 3.5, StormCloud.BOLT, 0.3f * o)
            }
            drawRain(d, sky, StormCloud.fall, 0xFFFFFF, rainAmount(d.e, d.age))
            val c = d.blocks() ?: return
            val ice = SpellArt.block("packed_ice")
            for (k in sky.hail.indices) {
                val h = sky.hail[k]
                val p = h.prev.lerp(h.pos, d.partialTick.toDouble())
                val melt = if (h.resting > 0) (1f - (h.resting + d.partialTick) / HAIL_LIES).coerceIn(0f, 1f) else 1f
                if (melt <= 0f) continue
                val spin = if (h.resting > 0) h.spin else h.spin + d.age * 0.3
                SpellShapes.texBox(c, d.m, SpellGeom.cubeCorners(d.local(p), h.size * (0.4 + 0.6 * melt), spin, spin * 0.7), ice, 0xFFFFFF, 0.95f, d.lightAt(p), k)
            }
        }
    }

    private const val PANES = 110
    /** These values copy the Staff of Aegis shield look. */
    private const val AEGIS_EDGE = 0.3f
    private const val AEGIS_INSET = 0.32
    private const val SPARKLE_HALF = 0.08
    private const val SPARKLE_ALPHA = 0.45f
    private const val SPARKLE_DRIFT = 0.04
    private const val SPARKLES_PER_TICK = 5
    /** Same thickness-to-width ratio as the Aegis shield. */
    private const val SHARD_DEPTH = 0.15
    private const val SHARD_DRAG = 0.8
    private const val SHARD_STEER = 0.2
    private const val SHARD_MAX_SPEED = 0.5
    private const val SHARD_BOUNCE = 0.7
    private const val SHARD_FLING = 0.3
    private const val SHARD_FALL = 0.03

    /** [pos] is relative to the centre. The shard steers to its whirl place at radius [r] on [axis], angle [th]. */
    private class Pane(
        var r: Double, var th: Double, var w: Double, var axis: Vec3, var want: Vec3, var turn: Int,
        val size: Double, val roll: Double, val spin: Double, val out: Vec3,
    ) {
        var pos: Vec3 = Vec3.ZERO
        var prev: Vec3 = Vec3.ZERO
        var vel: Vec3 = Vec3.ZERO
        var facing: Vec3 = out
    }

    private class Sparkle(val at: Vec3, val v: Vec3, val life: Int) { var age = 0 }

    private class Tempest(val panes: List<Pane>, val random: Random) {
        val sparkles = ArrayList<Sparkle>()
    }

    object GustOfGlassVisual : SignatureVisual {
        private fun unit(random: Random): Vec3 = SpellGeom.spread(random)

        private fun tempest(e: SignatureEntity): Tempest = e.clientState as? Tempest ?: run {
            val random = Random(e.seed)
            val r = GustOfGlass.radius(e.power)
            val panes = List(PANES) {
                val axis = unit(random)
                Pane(
                    0.5 + random.nextDouble() * (r - 0.5), random.nextDouble() * Math.PI * 2,
                    (0.08 + random.nextDouble() * 0.14) * (if (random.nextBoolean()) 1 else -1),
                    axis, axis, 8 + random.nextInt(16), 0.35 + random.nextDouble() * 0.3,
                    random.nextDouble() * 6, (random.nextDouble() - 0.5) * 0.3, unit(random),
                )
            }
            Tempest(panes, random).also { e.clientState = it }
        }

        private fun place(p: Pane, n: Int): Vec3 {
            val grow = SpellGeom.easeOut(minOf(1.0, n / 12.0))
            var u = SpellGeom.UP.cross(p.axis)
            u = if (u.lengthSqr() < 0.01) Vec3(1.0, 0.0, 0.0) else u.normalize()
            val v = p.axis.cross(u)
            val r = p.r * grow
            return u.scale(cos(p.th) * r).add(v.scale(sin(p.th) * r))
        }

        private fun solid(e: SignatureEntity, p: Vec3): Boolean {
            val pos = BlockPos.containing(p)
            return !e.level().getBlockState(pos).getCollisionShape(e.level(), pos).isEmpty
        }

        override fun tick(e: SignatureEntity) {
            val t = tempest(e)
            val centre = e.position()
            val breaking = e.tickCount >= GustOfGlass.LIFE - GustOfGlass.BREAK
            var bounces = 0
            for (p in t.panes) {
                if (--p.turn <= 0) {
                    p.want = unit(t.random); p.turn = 8 + t.random.nextInt(16)
                    if (t.random.nextDouble() < 0.35) p.w = -p.w
                }
                p.axis = p.axis.lerp(p.want, 0.15).normalize()
                p.th += p.w
                p.prev = p.pos
                p.vel = if (breaking) p.vel.add(p.out.scale(SHARD_FLING)).add(0.0, -SHARD_FALL, 0.0).scale(0.9)
                else {
                    val v = p.vel.scale(SHARD_DRAG).add(place(p, e.tickCount).subtract(p.pos).scale(SHARD_STEER))
                    if (v.length() > SHARD_MAX_SPEED) v.normalize().scale(SHARD_MAX_SPEED) else v
                }
                var next = p.pos
                var vx = p.vel.x; var vy = p.vel.y; var vz = p.vel.z
                if (solid(e, centre.add(next.add(vx, 0.0, 0.0)))) { vx = -vx * SHARD_BOUNCE; bounces++ } else next = next.add(vx, 0.0, 0.0)
                if (solid(e, centre.add(next.add(0.0, vy, 0.0)))) { vy = -vy * SHARD_BOUNCE; bounces++ } else next = next.add(0.0, vy, 0.0)
                if (solid(e, centre.add(next.add(0.0, 0.0, vz)))) { vz = -vz * SHARD_BOUNCE; bounces++ } else next = next.add(0.0, 0.0, vz)
                p.vel = Vec3(vx, vy, vz)
                p.pos = next
                if (p.vel.lengthSqr() > 1.0e-4) p.facing = p.facing.lerp(p.vel.normalize(), 0.5).let { if (it.lengthSqr() < 1.0e-4) p.facing else it.normalize() }
            }
            if (bounces > 0 && e.tickCount % 3 == 0) {
                e.level().playLocalSound(centre.x, centre.y, centre.z, EKSounds.AEGIS_SHARD_BOUNCE.get(), SoundSource.PLAYERS, minOf(1f, 0.15f * bounces), 1.2f + t.random.nextFloat() * 0.4f, false)
            }
            t.sparkles.removeAll { ++it.age >= it.life }
            if (!breaking) repeat(SPARKLES_PER_TICK) {
                val r = GustOfGlass.radius(e.power)
                val dir = when (t.random.nextInt(4)) {
                    0 -> Vec3(SPARKLE_DRIFT, 0.0, 0.0); 1 -> Vec3(-SPARKLE_DRIFT, 0.0, 0.0)
                    2 -> Vec3(0.0, SPARKLE_DRIFT, 0.0); else -> Vec3(0.0, -SPARKLE_DRIFT, 0.0)
                }
                t.sparkles += Sparkle(inBall(t.random, r), dir, 25 + t.random.nextInt(15))
            }
        }

        /** Corner order must match [SpellGeom.cubeCorners]. */
        private fun corners(p: Pane, c: Vec3, angle: Double): Array<Vec3> {
            val (s1, s2) = SpellGeom.basis(p.facing)
            val ca = cos(angle); val sa = sin(angle)
            val a = s1.scale(ca).add(s2.scale(sa)); val b = s1.scale(-sa).add(s2.scale(ca))
            val h = p.size / 2; val t = h * SHARD_DEPTH
            return Array(8) { i ->
                c.add(a.scale(if (i and 1 != 0) h else -h)).add(b.scale(if (i and 2 != 0) h else -h)).add(p.facing.scale(if (i and 4 != 0) t else -t))
            }
        }

        override fun render(d: SignatureDraw) {
            d.castFlash(d.at, 0xFFFFFF)
            val t = tempest(d.e)
            val life = GustOfGlass.LIFE.toFloat()
            val fade = if (d.age > life - GustOfGlass.BREAK) ((life - d.age) / GustOfGlass.BREAK).coerceIn(0f, 1f) else minOf(1f, d.age / 6f)
            if (fade <= 0f) return
            val f = d.partialTick.toDouble()
            val r = GustOfGlass.radius(d.e.power)
            val boxes = t.panes.map { p -> corners(p, p.prev.lerp(p.pos, f), p.roll + p.spin * d.age) }
            val glow = soft(d)
            SpellShapes.disc(glow, d.m, d.view, Vec3.ZERO, r * 1.5, 0xE8EEFF, 0.12f * fade)
            for (q in boxes) SpellShapes.glowCubeFaces(glow, d.m, q, 0xFFFFFF, AEGIS_EDGE * fade, AEGIS_INSET)
            for ((k, q) in boxes.withIndex()) {
                val c = q[0].add(q[7]).scale(0.5)
                val facing = Math.abs(t.panes[k].facing.dot(d.view.cam.subtract(c).normalize()))
                val glint = facing * facing * facing * facing * facing * facing * facing * facing
                if (glint > 0.3) SpellShapes.disc(glow, d.m, d.view, c, t.panes[k].size * 0.8, 0xFFFFFF, (0.5 * glint * fade).toFloat())
            }
            for (s in t.sparkles) {
                val age = s.age + d.partialTick
                val a = SPARKLE_ALPHA * fade * (age / 4f).coerceIn(0f, 1f) * ((s.life - age) / 8f).coerceIn(0f, 1f)
                if (a <= 0.004f) continue
                val p = s.at.add(s.v.scale(age.toDouble()))
                val l = d.view.left.scale(SPARKLE_HALF); val u = d.view.up.scale(SPARKLE_HALF)
                SpellShapes.quad(glow, d.m, p.add(l).subtract(u), p.subtract(l).subtract(u), p.subtract(l).add(u), p.add(l).add(u), 0xFFFFFF, a, a, a, a)
            }
            val lines = d.buffers.getBuffer(RenderType.lines())
            val pose = d.pose.last()
            for (q in boxes) SpellShapes.wire(lines, pose, q, 0xFFFFFF, fade)
        }
    }

    private const val RIFT_ROW = 0.25
    private const val RIFT_CHIPS = 32
    private const val EMBER = 0xFF5010

    private class Rift(val left: DoubleArray, val right: DoubleArray, val chips: List<DoubleArray>)

    object NetherRiftVisual : SignatureVisual {
        private fun rift(e: SignatureEntity): Rift = e.clientState as? Rift ?: run {
            val random = Random(e.seed)
            val rows = Math.round(NetherRift.HEIGHT / RIFT_ROW).toInt()
            val left = DoubleArray(rows); val right = DoubleArray(rows)
            for (i in 0 until rows) {
                val w = sin(Math.PI * (i + 0.5) / rows) * NetherRift.HALF_WIDTH
                val jx = (random.nextInt(5) - 2) / 16.0
                left[i] = -w * (0.55 + random.nextDouble() * 0.45) + jx
                right[i] = maxOf(left[i] + 1 / 16.0, w * (0.55 + random.nextDouble() * 0.45) + jx)
            }
            val chips = List(RIFT_CHIPS) {
                val i = 1 + random.nextInt(rows - 2)
                val side = if (random.nextBoolean()) right[i] else left[i]
                val x = side * (1.3 + random.nextDouble() * 0.6) + (if (side >= 0) 0.2 else -0.2)
                doubleArrayOf(x, i * RIFT_ROW, (random.nextDouble() - 0.5) * 1.2, random.nextDouble() * 6, 0.06 + random.nextDouble() * 0.1)
            }
            Rift(left, right, chips).also { e.clientState = it }
        }

        private fun openness(age: Float): Float =
            SpellGeom.easeOut((age / NetherRift.OPEN).toDouble().coerceIn(0.0, 1.0)).toFloat() *
                (1f - ((age - NetherRift.CLOSE) / NetherRift.OPEN).coerceIn(0f, 1f))

        override fun tick(e: SignatureEntity) {
            // The server keeps it fixed at its point. Do the same here.
            e.deltaMovement = Vec3.ZERO
            if (e.position().distanceToSqr(e.point) > 1.0e-6) e.setPos(e.point)
            if (e.tickCount < NetherRift.SPILL_FROM || e.tickCount > NetherRift.SPILL_TO) return
            val level = e.level()
            val r = level.random
            val half = NetherRift.HALF_WIDTH
            val n = NetherRift.facing(e)
            val across = Vec3(-n.z, 0.0, n.x)
            repeat(8) {
                val p = e.position().add(across.scale((r.nextDouble() - 0.5) * half * 1.4)).add(n.scale((r.nextDouble() - 0.5) * 0.6))
                    .add(0.0, 0.3 + r.nextDouble() * (NetherRift.HEIGHT - 0.6), 0.0)
                level.addParticle(ParticleTypes.FLAME, p.x, p.y, p.z, (r.nextDouble() - 0.5) * 0.4, r.nextDouble() * 0.08, (r.nextDouble() - 0.5) * 0.4)
                if (r.nextInt(2) == 0) level.addParticle(ParticleTypes.PORTAL, p.x, p.y, p.z, (r.nextDouble() - 0.5) * 0.8, r.nextDouble() * 0.4, (r.nextDouble() - 0.5) * 0.8)
                if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.LAVA, p.x, p.y, p.z, 0.0, 0.0, 0.0)
                if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, 0.0, 0.06, 0.0)
                val g = e.position().add(inDisc(Random(r.nextLong()), NetherRift.REACH)).add(0.0, 0.1, 0.0)
                level.addParticle(ParticleTypes.FLAME, g.x, g.y, g.z, 0.0, 0.05 + r.nextDouble() * 0.05, 0.0)
                if (r.nextInt(4) == 0) level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, g.x, g.y, g.z, 0.0, 0.04, 0.0)
            }
            if (e.tickCount % 4 == 0) level.addParticle(ParticleTypes.EXPLOSION, e.x, e.y + NetherRift.HEIGHT / 2, e.z, 0.0, 0.0, 0.0)
            // Other dimensions have no portal link, so the rift does not pull particles in.
            if (level.dimension() != Level.OVERWORLD && level.dimension() != Level.NETHER) return
            repeat(3) {
                val p = e.position().add((r.nextDouble() - 0.5) * half * 4, r.nextDouble() * NetherRift.HEIGHT, (r.nextDouble() - 0.5) * half * 4)
                val to = e.position().add(0.0, NetherRift.HEIGHT / 2, 0.0).subtract(p)
                level.addParticle(ParticleTypes.REVERSE_PORTAL, p.x, p.y, p.z, to.x * 0.05, to.y * 0.05, to.z * 0.05)
            }
        }

        override fun render(d: SignatureDraw) {
            d.castFlash(d.at.add(0.0, NetherRift.HEIGHT / 2, 0.0), EMBER)
            val rift = rift(d.e)
            val o = openness(d.age)
            if (o <= 0.001f) return
            // Fixed in the world, not turned to the eye: the face looks along n.
            val n = NetherRift.facing(d.e)
            val across = Vec3(-n.z, 0.0, n.x)
            fun at(x: Double, y: Double, z: Double = 0.0) = across.scale(x).add(n.scale(z)).add(0.0, 0.1 + y, 0.0)
            val rows = rift.left.size
            val open = o.toDouble()
            val pulse = (0.85 + 0.15 * sin(d.age * 0.4)).toFloat()
            val glow = soft(d)
            planeDisc(glow, d.m, at(0.0, NetherRift.HEIGHT / 2), across, SpellGeom.UP, 6.0 * open, EMBER, 0.55f * o * pulse)
            planeDisc(glow, d.m, at(0.0, NetherRift.HEIGHT / 2), across, SpellGeom.UP, 3.5 * open, 0xFFA040, 0.45f * o * pulse)
            flatDisc(glow, d.m, Vec3(0.0, 0.05, 0.0), NetherRift.REACH * 1.1, EMBER, 0.55f * o * pulse)
            flatDisc(glow, d.m, Vec3(0.0, 0.05, 0.0), NetherRift.REACH * 0.4, 0xFFB050, 0.4f * o * pulse)
            // UVs stay at 16 texels a block, so the texture cuts as the rift opens and does not stretch.
            d.blocks()?.let { c ->
                val portal = SpellArt.block("nether_portal")
                for (i in 0 until rows) {
                    val y0 = i * RIFT_ROW; val y1 = y0 + RIFT_ROW
                    val xl = rift.left[i] * open; val xr = rift.right[i] * open
                    if (xr - xl < 1.0e-3) continue
                    SpellShapes.texFace(
                        c, d.m, at(xl, y0), at(xr, y0), at(xr, y1), at(xl, y1), portal, 0xFFFFFF, 0.95f, SpellShapes.FULL_BRIGHT,
                        xl - floor(xl), y0 - floor(y0),
                    )
                }
            }
            val left = ArrayList<Vec3>(); val right = ArrayList<Vec3>()
            for (i in 0 until rows) {
                left += at(rift.left[i] * open, i * RIFT_ROW); left += at(rift.left[i] * open, (i + 1) * RIFT_ROW)
                right += at(rift.right[i] * open, i * RIFT_ROW); right += at(rift.right[i] * open, (i + 1) * RIFT_ROW)
            }
            val lipGlow = d.glow()
            for (lip in listOf(left, right)) {
                planeStrip(lipGlow, d.m, lip, n, 0.6, 0xFF4A00, 0.55f * o * pulse, soft = true)
                planeStrip(lipGlow, d.m, lip, n, 0.12, 0xFFB040, 1f * o, soft = false)
            }
            d.glowBlocks()?.let { c ->
                val lava = SpellArt.block("lava_still")
                for (lip in listOf(left, right)) {
                    val edges = List(lip.size) { i -> inPlane(lip, i, n).scale(0.1) }
                    SpellShapes.stripPx(c, d.m, lip, edges, lava, 0xFFFFFF, FloatArray(lip.size) { 0.9f * o }, 0.5, SpellShapes.FULL_BRIGHT)
                }
            }
            if (o < 0.2f) return
            val solid = d.solidBlocks() ?: return
            val obsidian = SpellArt.block("obsidian")
            for ((k, chip) in rift.chips.withIndex()) {
                val p = at(chip[0] * open, chip[1] + sin(d.age * 0.15 + chip[3]) * 0.08, chip[2])
                val corners = SpellGeom.cubeCorners(p, chip[4], d.age * 0.02, d.age * 0.03 + chip[3])
                SpellShapes.texBox(solid, d.m, corners, obsidian, 0xFFFFFF, 1f, d.lightAt(d.at.add(p)), d.e.seed + k)
            }
        }

        private fun inPlane(points: List<Vec3>, i: Int, n: Vec3): Vec3 {
            val along = points[minOf(points.size - 1, i + 1)].subtract(points[maxOf(0, i - 1)])
            val side = along.cross(n)
            return if (side.lengthSqr() < 1.0e-12) Vec3.ZERO else side.normalize()
        }

        /** Unlike [SpellShapes.strip], this lies flat in the plane with normal [n], not turned to the eye. */
        private fun planeStrip(c: VertexConsumer, m: Matrix4f, points: List<Vec3>, n: Vec3, width: Double, rgb: Int, alpha: Float, soft: Boolean) {
            if (points.size < 2 || alpha <= 0.004f) return
            val sides = List(points.size) { inPlane(points, it, n).scale(width / 2) }
            val edge = if (soft) 0f else alpha
            for (i in 0 until points.size - 1) {
                val p0 = points[i]; val p1 = points[i + 1]
                SpellShapes.quad(c, m, p0, p1, p1.add(sides[i + 1]), p0.add(sides[i]), rgb, alpha, alpha, edge, edge)
                SpellShapes.quad(c, m, p0.subtract(sides[i]), p1.subtract(sides[i + 1]), p1, p0, rgb, edge, edge, alpha, alpha)
            }
        }

        private fun planeDisc(c: VertexConsumer, m: Matrix4f, centre: Vec3, u: Vec3, v: Vec3, r: Double, rgb: Int, alpha: Float) {
            if (alpha <= 0.004f || r <= 0.01) return
            val sectors = 16
            for (k in 0 until sectors) {
                val a0 = 2 * Math.PI * k / sectors; val a1 = 2 * Math.PI * (k + 1) / sectors
                val e0 = centre.add(u.scale(cos(a0) * r)).add(v.scale(sin(a0) * r))
                val e1 = centre.add(u.scale(cos(a1) * r)).add(v.scale(sin(a1) * r))
                SpellShapes.quad(c, m, centre, centre, e1, e0, rgb, alpha, alpha, 0f, 0f)
            }
        }
    }
}
