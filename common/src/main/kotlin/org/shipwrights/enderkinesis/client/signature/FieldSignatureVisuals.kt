package org.shipwrights.enderkinesis.client.signature

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
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

/** How Stillpoint, Aurora Veil, Acid Rain, Storm Cloud, Gust of Glass and Nether Rift look. */
object FieldSignatureVisuals {
    val ALL: Map<Signature, SignatureVisual> = mapOf(
        Signature.STILLPOINT to StillpointVisual,
        Signature.AURORA_VEIL to AuroraVeilVisual,
        Signature.ACID_RAIN to AcidRainVisual,
        Signature.STORM_CLOUD to StormCloudVisual,
        Signature.GUST_OF_GLASS to GustOfGlassVisual,
        Signature.NETHER_RIFT to NetherRiftVisual,
    )

    /** Fades in over [inTicks] and out over the last [outTicks] of its life. */
    private fun fade(d: SignatureDraw, inTicks: Float, outTicks: Float): Float =
        minOf(1f, d.age / inTicks, (d.e.life - d.age) / outTicks).coerceIn(0f, 1f)

    /** A random point inside a ball of radius [r], evenly spread. */
    private fun inBall(random: Random, r: Double): Vec3 {
        val dir = SpellGeom.spread(random)
        return dir.scale(r * Math.cbrt(random.nextDouble()))
    }

    /** A random point in a level disc of radius [r], evenly spread. */
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

    /** Depth-blended light where the shaders have loaded, plain additive light until then. */
    private fun soft(d: SignatureDraw): VertexConsumer = d.softGlow() ?: d.glow()

    /** A level disc of light lying at [centre], bright in the middle and fading to its edge. */
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

    /** Where this client's eye is in the world. */
    private fun eye(): Vec3 = Minecraft.getInstance().gameRenderer.mainCamera.position

    // ---- Stillpoint -----------------------------------------------------------------------------------

    private const val FROST = 0xDFF4FF
    private const val STILL_CRYSTALS = 48
    /** Nothing is drawn or spawned nearer the eye than this, so nothing is pressed to the view
     *  from inside; crystals fade in over the same distance again beyond it. */
    private const val STILL_CLEAR = 1.5

    /**
     * A frost-white sphere with a pale halo, both sinking softly into whatever they touch, and
     * packed-ice crystals and motes hanging motionless inside it; a flash from the hand marks
     * where it's cast. Seen from inside, the halo and the inner shell give way and the sphere
     * thins to a soft frosty wash round the eye, and no crystal or mote comes close to it. Each
     * crystal is lit by the world where it hangs.
     */
    object StillpointVisual : SignatureVisual {
        override fun tick(e: SignatureEntity) {
            // It doesn't move, whatever pushes at it; nor does it here.
            e.deltaMovement = Vec3.ZERO
            if (e.position().distanceToSqr(e.point) > 1.0e-6) e.setPos(e.point)
            val level = e.level()
            val r = Stillpoint.radius(e.power)
            if (e.tickCount < 4 || e.tickCount > e.life - 8) return
            val eye = eye()
            // Motes that hang where they appear, never close to the eye.
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
            // How far inside the eye is: 0 outside, 1 a quarter of the way in.
            val inside = SpellGeom.smooth(((r * 1.02 - d.view.cam.length()) / (r * 0.25)).toFloat())
            val glow = soft(d)
            SpellShapes.disc(glow, d.m, d.view, Vec3.ZERO, r * 1.35, 0xCFE8FF, 0.28f * o * (1f - inside))
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

    // ---- Aurora Veil --------------------------------------------------------------------------------

    private const val AURORA_GREEN = 0x50FFAA
    private const val AURORA_BLUE = 0x78C8FF
    private const val AURORA_VIOLET = 0xBE78FF
    private const val VEIL_COLUMNS_PER_BLOCK = 6
    private const val VEIL_ROWS = 20
    /** How long a cleansed creature's shimmer lasts, and how tall its band of light is. */
    private const val SHIMMER_TICKS = 26
    private const val SHIMMER_BAND = 0.7

    /** A creature seen cleansed at [start]. */
    private class Shimmer(val id: Int, val start: Int)

    /** What the veil remembers on this side: when each creature was last in it, and the
     *  shimmers running. */
    private class Veil(val seen: HashMap<Int, Int> = HashMap(), val shimmers: MutableList<Shimmer> = ArrayList())

    /**
     * Ribbons of green, blue and violet light hung from nothing, rolling with waves like the
     * northern lights: several waves of different lengths and speeds run along it and up it at
     * once, with a slow deep fold through the whole curtain, so it billows and folds over
     * itself, sinking softly into the ground at its foot, with glitter drifting up off it; a
     * flash from the hand marks where it's cast. A creature that passes through and is cleansed
     * is washed in an aurora shimmer: a band of light that sweeps up its body from feet to
     * crown, ringed with a halo, trailing glitter, while the server's chime rings and its
     * glitter bursts off it.
     */
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
            // Who passes through, as the server tells it: the same test, the same wait to be
            // cleansed again.
            val foot = e.position()
            val box = AABB(foot, foot.add(0.0, AuroraVeil.HEIGHT, 0.0)).inflate(w / 2 + 2.0, 0.5, w / 2 + 2.0)
            for (target in level.getEntitiesOfClass(LivingEntity::class.java, box)) {
                if (target.isSpectator || !target.isAlive || !AuroraVeil.inVeil(e, target, foot)) continue
                if (AuroraVeil.cleansed(v.seen, target, e.tickCount)) v.shimmers += Shimmer(target.id, e.tickCount)
            }
            // The shimmers trail glitter up the bodies they wash.
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

        /** The aurora's colour at height [f] (0 at its foot, 1 at its top) and its opacity. */
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
                // Three sheets, the back ones paler and out of step, so it reads as folds of light.
                sheet(d, glow, n, across, 0.0, 0f, 0.95f * o)
                sheet(d, glow, n, across, -0.4, 2.1f, 0.5f * o)
                sheet(d, glow, n, across, 0.35, 4.7f, 0.3f * o)
            }
            for (s in veil(d.e).shimmers) shimmer(d, glow, s)
        }

        /**
         * One sheet of the curtain. Its sway at each point is several waves added: a long slow
         * one, a shorter one running the other way, a quick ripple, each leaning as it climbs so
         * the waves also run up it, and a deep fold through the whole curtain; the curtain also
         * slides a little sideways as it climbs, as an aurora's folds do.
         */
        private fun sheet(d: SignatureDraw, glow: VertexConsumer, n: Vec3, across: Vec3, back: Double, phase: Float, alpha: Float) {
            val t = d.age + phase * 20
            val w = AuroraVeil.width(d.e.power); val h = AuroraVeil.HEIGHT
            val columns = (w * VEIL_COLUMNS_PER_BLOCK).toInt()
            fun at(i: Int, j: Int): Vec3 {
                val x = -w / 2 + w * i / columns
                val f = j.toDouble() / VEIL_ROWS
                val sway = sin(x * 1.1 + t * 0.11 + f * 0.8) * 0.55 +
                    sin(x * 2.4 - t * 0.08 + f * 2.2) * 0.32 +
                    sin(x * 4.3 + t * 0.17 - f * 3.0) * 0.18 +
                    sin(x * 0.5 - t * 0.045) * 0.35 * (0.4 + 0.6 * f)
                val slide = sin(f * Math.PI * 0.9 + t * 0.06 + x * 0.7) * 0.25
                return across.scale(x + slide).add(n.scale(sway * (0.55 + 0.45 * f) + back)).add(0.0, 0.05 + h * f, 0.0)
            }
            // Brighter rays drifting along it, as an aurora's curtains streak.
            fun ray(i: Int): Float {
                val x = -w / 2 + w * i / columns
                return (0.55 + 0.45 * sin(x * 3.7 + t * 0.21) * sin(x * 1.1 - t * 0.05)).toFloat() *
                    (1f - (Math.abs(x) / (w / 2)).toFloat().let { it * it * it * it })
            }
            for (j in 0 until VEIL_ROWS) {
                val f0 = j.toFloat() / VEIL_ROWS; val f1 = (j + 1).toFloat() / VEIL_ROWS
                val rgb = colour((f0 + f1) / 2)
                for (i in 0 until columns) {
                    val r0 = ray(i); val r1 = ray(i + 1)
                    SpellShapes.quad(
                        glow, d.m, at(i, j), at(i + 1, j), at(i + 1, j + 1), at(i, j + 1), rgb,
                        alpha * alpha(f0) * r0, alpha * alpha(f0) * r1, alpha * alpha(f1) * r1, alpha * alpha(f1) * r0,
                    )
                }
            }
        }

        /** A band of aurora light sweeping up a cleansed creature from its feet to its crown,
         *  with a halo round it at the band and a fading wash left on the body below it. */
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
                // The wash it leaves on the body below.
                SpellShapes.quad(glow, d.m, at(p, base), at(q, base), at(q, lo), at(p, lo), AURORA_GREEN, 0.25f * a, 0.25f * a, 0.1f * a, 0.1f * a)
            }
            SpellShapes.ring(glow, d.m, Vec3(pos.x, y(band), pos.z), w * 1.4, 0.18, rgb, 0.8f * a, 0.0)
            SpellShapes.ring(glow, d.m, Vec3(pos.x, y(band - 0.25), pos.z), w * 1.1, 0.1, AURORA_VIOLET, 0.5f * a, 0.0)
            SpellShapes.disc(glow, d.m, d.view, Vec3(pos.x, base + h / 2, pos.z), h * 0.8, AURORA_BLUE, 0.25f * (1f - t))
        }
    }

    // ---- Clouds and rain: Acid Rain and Storm Cloud ------------------------------------------------

    /** A cloud's cells: [CELL] blocks square and [CELL_THICK] tall, as Minecraft's fancy clouds
     *  are 12 by 12 by 4, only smaller; on a grid, as vanilla's are, that slides with the wind. */
    private const val CELL = 2.0
    private const val CELL_THICK = 1.0
    /** Vanilla's cloud face shading: top, bottom, the faces along x, those along z. */
    private const val SHADE_TOP = 1f
    private const val SHADE_BOTTOM = 0.7f
    private const val SHADE_X = 0.9f
    private const val SHADE_Z = 0.8f
    /** The cell grids slide downwind faster than the cloud itself drifts ([Cloud.WIND]): the
     *  bottom layer this many times as fast, each layer above this much more, each veering this
     *  far off the wind, in radians, so the layers slide past each other. */
    private const val SLIDE = 1.5
    private const val SLIDE_LAYER = 0.8
    private const val SLIDE_VEER = 0.2
    /** How fast cells at the edge churn in and out, radians a tick; how far the footprint
     *  breathes, as a fraction of its radius; how far its middle wanders; how far each layer
     *  heaves up and down, in blocks. */
    private const val CHURN = 0.03
    private const val BILLOW = 0.08
    private const val WANDER = 0.1
    private const val HEAVE = 0.12
    /** How far inside the footprint's edge a cell must be to be whole: one nearer the edge is
     *  smaller and fainter, so cells drifting or churning in grow and fade in. */
    private const val EDGE_SOFT = 0.15
    /** How long each cell takes to grow in as the cloud forms; the middle's start first, the
     *  edge's last, all within [Cloud.GROW] ticks. */
    private const val CELL_GROW = 12f
    /** The smallest a cell is drawn as it grows, as a fraction of its whole size. */
    private const val CELL_MIN = 0.25
    /** A column is rained on where the bottom layer's cell over it is at least this much there. */
    private const val RAIN_CELL = 0.5
    /** The rain's block columns reach this far out, as a fraction of the radius: as far as the
     *  churning edge can reach. */
    private const val COLUMN_REACH = 1.4
    /** Rain is fainter out to this far from the eye, as vanilla's is over its 10 blocks. */
    private const val RAIN_FADE = 10.0
    /** How often each side looks again for where the rain lands, in ticks. */
    private const val LANDING_EVERY = 8
    private val RAIN = Sprite(ResourceLocation("textures/environment/rain.png"), 0f, 0f, 1f, 1f)
    private val NO_LANDING = Double.NaN

    /**
     * Vanilla's clouds' two passes: first every face is written to depth only, then drawn in
     * colour where it is the nearest (LEQUAL, writing depth again), so a translucent cloud hides
     * its own far faces and what's behind or inside it sorts right against it.
     */
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

    /** A hailstone falling, bouncing, then lying a moment before it melts away. */
    private class Hailstone(var pos: Vec3, var vel: Vec3, val size: Double, val spin: Double) {
        var prev: Vec3 = pos
        var bounces = 0
        var resting = 0
    }

    /** One cell there this frame: layer [l]'s cell [i], [k], and how much of it there is. */
    private class Cell(val i: Int, val l: Int, val k: Int, val amount: Double)

    /**
     * A cloud as this side lays it out: its [radius] and [layers] tiers, the block columns its
     * rain may fall in (as offsets from the block it's over, [ox], [oz], which follows it as it
     * drifts) with the top of what each lands on (NaN for nothing within its fall), and any hail
     * in the air.
     */
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

        /** Column [n]'s world block x and z. */
        fun x(n: Int): Int = ox + offsets[n][0]
        fun z(n: Int): Int = oz + offsets[n][1]

        /** The index of the column at world block [x], [z], or null when it's outside the reach. */
        fun at(x: Int, z: Int): Int? = index[column(x - ox, z - oz)]

        /** How far layer [l]'s grid has slid downwind by [age]. */
        fun slideX(l: Int, age: Float): Double = cos(SLIDE_VEER * l) * Cloud.WIND_SPEED * (SLIDE + SLIDE_LAYER * l) * age
        fun slideZ(l: Int, age: Float): Double = sin(SLIDE_VEER * l) * Cloud.WIND_SPEED * (SLIDE + SLIDE_LAYER * l) * age

        /** How high layer [l]'s underside heaves at [age], over the cloud's underside. */
        fun lift(l: Int, age: Float): Double = l * CELL_THICK + HEAVE * sin(age * 0.05 + l * 2.1 + seed)

        /**
         * How much of layer [l]'s cell [i], [k] is there at [age], 0 to 1, with the cloud's
         * middle at [cx], [cz]: whole well inside its footprint (which wanders and breathes),
         * growing and fading toward the footprint's edge, where cells churn in and out; grown in
         * from the middle out as the cloud forms; and never more than the layer below under it.
         */
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

        /** How much of layer [l] is over the world point [x], [z] at [age]. */
        fun under(x: Double, z: Double, l: Int, age: Float, cx: Double, cz: Double): Double =
            amount(floor((x - slideX(l, age)) / CELL).toInt(), l, floor((z - slideZ(l, age)) / CELL).toInt(), age, cx, cz)

        /** Every cell there at [age] round [cx], [cz]. */
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

        /** Whether it rains on column [n] at [age]. */
        fun rainsOn(n: Int, age: Float, cx: Double, cz: Double): Boolean = under(x(n) + 0.5, z(n) + 0.5, 0, age, cx, cz) >= RAIN_CELL
    }

    private fun key(i: Int, l: Int, k: Int): Long = ((i.toLong() and 0x1FFFFF) shl 42) or ((l.toLong() and 0x1FFFFF) shl 21) or (k.toLong() and 0x1FFFFF)

    private fun column(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    /** A steady 0..1 from four whole numbers, the same on every client. */
    private fun hash(a: Int, b: Int, c: Int, d: Int): Double {
        var h = a.toLong() * 0x9E3779B1L + b.toLong() * 0x85EBCA6BL + c.toLong() * 0xC2B2AE35L + d.toLong() * 0x27D4EB2FL
        h = h xor (h ushr 29); h *= 0x2C1B3C6DL; h = h xor (h ushr 32); h *= 0x297A2D39L; h = h xor (h ushr 29)
        return ((h ushr 11) and 0xFFFFFFL).toDouble() / 0x1000000
    }

    /** [e]'s cloud, laid out from its seed on first use. */
    private fun sky(e: SignatureEntity, radius: Double, layers: Int): Sky =
        e.clientState as? Sky ?: Sky(e.seed, radius, layers).also { e.clientState = it }

    /**
     * Keeps the cloud drifting with the wind, as the server does, and finds, every
     * [LANDING_EVERY] ticks or whenever it has drifted over a new block, the top of the first
     * thing under it in each column, as vanilla rain stops: the world's motion-blocking
     * heightmap, or straight down from the cloud where it's under a roof, and ships' blocks.
     */
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

    /** Rain's splashes where it lands, vanilla's own, and now and then the sound of it. */
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

    /** How hard it's raining [age] ticks in: in once it's formed, out over a few ticks. */
    private fun rainAmount(e: SignatureEntity, age: Float): Float =
        minOf((age - Cloud.FORM) / 6f, (e.life - Cloud.FADE - age) / 8f + 1f).coerceIn(0f, 1f)

    /** How much of [e]'s cloud there is [d]'s frame as it goes: gone over its last ticks. Its
     *  forming is in each cell's own growth. */
    private fun cloudAmount(d: SignatureDraw): Float =
        SpellGeom.smooth(((d.e.life - d.age) / Cloud.FADE).coerceIn(0f, 1f))

    /** How bright a cloud cell is in the world's light [packed] there (block or sky, whichever
     *  is brighter; the sky's own darkening at night is in the cloud colour): full under open
     *  sky, never quite black. */
    private fun cellLight(packed: Int): Float {
        val level = maxOf(LightTexture.block(packed), LightTexture.sky(packed))
        return CELL_DARKEST + (1f - CELL_DARKEST) * level / 15f
    }

    /** The darkest a cloud cell gets in no light at all. */
    private const val CELL_DARKEST = 0.25f

    /** One face of a cloud cell, ready to draw in both passes. */
    private class CloudFace(val p0: Vec3, val p1: Vec3, val p2: Vec3, val p3: Vec3, val rgb: Int, val alpha: Float)

    /**
     * The cloud as Minecraft's fancy clouds are drawn: flat, translucent, axis-aligned slabs of
     * [rgb], each face shaded as vanilla shades them and tinted by the sky's cloud colour, so it
     * darkens at night, in vanilla's two passes ([CLOUD_DEPTH], then [CLOUD_COLOUR]). Its
     * layers slide past each other downwind, heave a little, and the cells at its edge churn in
     * and out as its footprint wanders and breathes; a cell only partly there is drawn smaller
     * and fainter about its middle, so cells grow and fade in as the cloud forms and as they
     * drift in. Faces between two whole cells of a layer, and those facing away from the eye,
     * are left out. Each cell is also darkened by the world's light where it is ([cellLight]),
     * so a cloud under a roof or in a cave is dim. [flash] whitens it, lightning lit from inside.
     */
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
        val whole = HashSet<Long>()
        for (c in cells) if (c.amount >= 0.999) whole += key(c.i, c.l, c.k)
        val faces = ArrayList<CloudFace>()
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
            fun face(di: Int, dl: Int, dk: Int, shade: Float, p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3) {
                if (isWhole && dl == 0 && key(cell.i + di, l, cell.k + dk) in whole) return
                val mid = p0.add(p2).scale(0.5)
                if (cam.subtract(mid).dot(Vec3(di.toDouble(), dl.toDouble(), dk.toDouble())) <= 0) return
                faces += CloudFace(p0, p1, p2, p3, shaded(shade, lit), a)
            }
            face(0, 1, 0, SHADE_TOP, Vec3(x0, y1, z0), Vec3(x1, y1, z0), Vec3(x1, y1, z1), Vec3(x0, y1, z1))
            face(0, -1, 0, SHADE_BOTTOM, Vec3(x0, y0, z0), Vec3(x1, y0, z0), Vec3(x1, y0, z1), Vec3(x0, y0, z1))
            face(1, 0, 0, SHADE_X, Vec3(x1, y0, z0), Vec3(x1, y1, z0), Vec3(x1, y1, z1), Vec3(x1, y0, z1))
            face(-1, 0, 0, SHADE_X, Vec3(x0, y0, z0), Vec3(x0, y1, z0), Vec3(x0, y1, z1), Vec3(x0, y0, z1))
            face(0, 0, 1, SHADE_Z, Vec3(x0, y0, z1), Vec3(x1, y0, z1), Vec3(x1, y1, z1), Vec3(x0, y1, z1))
            face(0, 0, -1, SHADE_Z, Vec3(x0, y0, z0), Vec3(x1, y0, z0), Vec3(x1, y1, z0), Vec3(x0, y1, z0))
        }
        for (type in listOf(CLOUD_DEPTH, CLOUD_COLOUR)) {
            val c = d.buffers.getBuffer(type)
            for (f in faces) SpellShapes.quad(c, d.m, f.p0, f.p1, f.p2, f.p3, f.rgb, f.alpha, f.alpha, f.alpha, f.alpha)
        }
    }

    /**
     * Rain as vanilla draws it: in each block column the cloud is over this frame, one vertical
     * quad a block wide turned square to the eye, from the cloud's underside to where the rain
     * lands, carrying vanilla's rain texture scrolling down at each column's own speed, fainter
     * the further from the eye, lit by the world there and tinted [rgb]. Drawn after the cloud,
     * it's tested against the depth the cloud wrote.
     */
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
            // Vanilla's per-column hash, for where in its fall each column's texture is and how fast.
            val hash = bx * bx * 3121 + bx * 45238971 xor bz * bz * 418711 + bz * 13761
            val speed = (3f + Random(hash).nextFloat()) / 32f
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
                RAIN, floatArrayOf(0f, vTop, 1f, vTop, 1f, vBottom, 0f, vBottom), rgb, a, light,
            )
        }
    }

    // ---- Acid Rain ----------------------------------------------------------------------------------

    private const val ACID_CLOUD = 0x86B83E
    /** The rain texture is pale blue-white; tinted this, it reads plainly acid green. */
    private const val ACID_RAIN = 0x38FF10
    private const val ACID_FLASH = 0x70FF30
    private const val ACID_LAYERS = 2

    /** A sickly yellow-green Minecraft cloud that grows in over the aimed point, a flash from
     *  the hand marking it, alive with the wind and drifting on it, and vanilla rain tinted acid
     *  green falling from it, splashing green and fizzing where it lands. */
    object AcidRainVisual : SignatureVisual {
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

    // ---- Storm Cloud --------------------------------------------------------------------------------

    private const val STORM_CLOUD = 0x5E6370
    /** At or over this power, the storm cloud piles a third tier. */
    private const val STORM_TOWER = 0.6f
    /** Hailstones loosed a tick, plus more at full power; how fast they leave the cloud and fall
     *  faster; how much of its fall a bounce gives back, and how many it makes; how long one
     *  lies before it melts; and the most in the air at once. */
    private const val HAIL_RATE = 5
    private const val HAIL_RATE_POWER = 8
    private const val HAIL_SPEED = 0.5
    private const val HAIL_GRAVITY = 0.05
    private const val HAIL_BOUNCE = 0.35
    private const val HAIL_BOUNCES = 2
    private const val HAIL_LIES = 14
    private const val HAIL_MAX = 600

    /** A dark grey Minecraft thundercloud that grows in over the aimed point, a flash from the
     *  hand marking it, alive with the wind and drifting on it, lightning flickering under it
     *  and lighting it up; vanilla rain pours from it, splashing where it lands, and hailstones
     *  of packed ice pelt down through it, bouncing and clattering, then melt; its strikes come
     *  down as the mod's arcs. */
    object StormCloudVisual : SignatureVisual {
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

        /** Looses hail from under the cloud while it rains, and moves what's in the air: it falls,
         *  bounces off what the rain lands on, clatters, then lies and melts. */
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
            // Lightning flickering in it, a beat at a time: drawn just under its belly, where the
            // cloud's depth doesn't hide it, as it lights the cloud up.
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
            // The hail: small cubes of packed ice, lit by the world where they are.
            val c = d.blocks() ?: return
            val ice = SpellArt.block("packed_ice")
            for ((k, h) in sky.hail.withIndex()) {
                val p = h.prev.lerp(h.pos, d.partialTick.toDouble())
                val melt = if (h.resting > 0) (1f - (h.resting + d.partialTick) / HAIL_LIES).coerceIn(0f, 1f) else 1f
                if (melt <= 0f) continue
                val spin = if (h.resting > 0) h.spin else h.spin + d.age * 0.3
                SpellShapes.texBox(c, d.m, SpellGeom.cubeCorners(d.local(p), h.size * (0.4 + 0.6 * melt), spin, spin * 0.7), ice, 0xFFFFFF, 0.95f, d.lightAt(p), k)
            }
        }
    }

    // ---- Gust of Glass ------------------------------------------------------------------------------

    private const val PANES = 110
    /** The Staff of Aegis's shield look: each face's edge glow, how far in it fades, and its
     *  sparkles' size, opacity and drift. */
    private const val AEGIS_EDGE = 0.3f
    private const val AEGIS_INSET = 0.32
    private const val SPARKLE_HALF = 0.08
    private const val SPARKLE_ALPHA = 0.45f
    private const val SPARKLE_DRIFT = 0.04
    private const val SPARKLES_PER_TICK = 5
    /** A shard's thickness against its width, as the shield's depth is against its face. */
    private const val SHARD_DEPTH = 0.15
    /** How a shard flies: how much of its speed it keeps each tick, how hard it's steered back
     *  to its place in the whirl, its top speed, and how much speed it keeps off a bounce. */
    private const val SHARD_DRAG = 0.8
    private const val SHARD_STEER = 0.2
    private const val SHARD_MAX_SPEED = 0.5
    private const val SHARD_BOUNCE = 0.7
    /** As it breaks, each shard is flung out this hard a tick, and falls this fast. */
    private const val SHARD_FLING = 0.3
    private const val SHARD_FALL = 0.03

    /**
     * One shard: its place in the whirl is [r] out round its [axis] at angle [th], turning [w] a
     * tick, the axis swinging toward [want] and a new one picked every so often; the shard
     * itself flies after that place at [pos] (from the middle), [vel] a tick, its face turned
     * to [facing], the way it's going.
     */
    private class Pane(
        var r: Double, var th: Double, var w: Double, var axis: Vec3, var want: Vec3, var turn: Int,
        val size: Double, val roll: Double, val spin: Double, val out: Vec3,
    ) {
        var pos: Vec3 = Vec3.ZERO
        var prev: Vec3 = Vec3.ZERO
        var vel: Vec3 = Vec3.ZERO
        var facing: Vec3 = out
    }

    /** A sparkle drifting in the gust as the shield's do: from [at], [v] a tick, [age] of [life]. */
    private class Sparkle(val at: Vec3, val v: Vec3, val life: Int) { var age = 0 }

    private class Tempest(val panes: List<Pane>, val random: Random) {
        val sparkles = ArrayList<Sparkle>()
    }

    /**
     * Shards like small Staff of Aegis shields, white light glowing in from every face's edge
     * and a white wire round each, whirling round a point, each forever changing the plane it
     * turns in, face on to the way it's flying, glinting as it faces the eye and glancing off
     * any block it meets, with the shield's sparkles drifting among them; a flash from the hand
     * marks where it's cast, and at the end they fly apart, bounce and fade.
     */
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

        /** Where [p]'s place in the whirl is, from the middle, [n] ticks in. */
        private fun place(p: Pane, n: Int): Vec3 {
            val grow = SpellGeom.easeOut(minOf(1.0, n / 12.0))
            var u = SpellGeom.UP.cross(p.axis)
            u = if (u.lengthSqr() < 0.01) Vec3(1.0, 0.0, 0.0) else u.normalize()
            val v = p.axis.cross(u)
            val r = p.r * grow
            return u.scale(cos(p.th) * r).add(v.scale(sin(p.th) * r))
        }

        /** Whether the world point [p] is inside a block a shard would glance off. */
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
                // It flies after its place in the whirl; as the gust breaks, it's flung out and falls.
                p.vel = if (breaking) p.vel.add(p.out.scale(SHARD_FLING)).add(0.0, -SHARD_FALL, 0.0).scale(0.9)
                else {
                    val v = p.vel.scale(SHARD_DRAG).add(place(p, e.tickCount).subtract(p.pos).scale(SHARD_STEER))
                    if (v.length() > SHARD_MAX_SPEED) v.normalize().scale(SHARD_MAX_SPEED) else v
                }
                // Each way it moves, it glances off a block in its path.
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
            // The shield's sparkles: each drifts one way, up, down or to a side, and fades.
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

        /** [p]'s eight corners about [c], numbered as [SpellGeom.cubeCorners] numbers them: its
         *  face square to its [Pane.facing], the thin side along it, turned [angle] about it. */
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
            // Each shard as a thin box, like the shield's slab, face on to its flight.
            val boxes = t.panes.map { p -> corners(p, p.prev.lerp(p.pos, f), p.roll + p.spin * d.age) }
            val glow = soft(d)
            SpellShapes.disc(glow, d.m, d.view, Vec3.ZERO, r * 1.5, 0xE8EEFF, 0.12f * fade)
            for (q in boxes) SpellShapes.glowCubeFaces(glow, d.m, q, 0xFFFFFF, AEGIS_EDGE * fade, AEGIS_INSET)
            // Glints as a shard's face turns to the eye.
            for ((k, q) in boxes.withIndex()) {
                val c = q[0].add(q[7]).scale(0.5)
                val facing = Math.abs(t.panes[k].facing.dot(d.view.cam.subtract(c).normalize()))
                val glint = facing * facing * facing * facing * facing * facing * facing * facing
                if (glint > 0.3) SpellShapes.disc(glow, d.m, d.view, c, t.panes[k].size * 0.8, 0xFFFFFF, (0.5 * glint * fade).toFloat())
            }
            // The shield's sparkles.
            for (s in t.sparkles) {
                val age = s.age + d.partialTick
                val a = SPARKLE_ALPHA * fade * (age / 4f).coerceIn(0f, 1f) * ((s.life - age) / 8f).coerceIn(0f, 1f)
                if (a <= 0.004f) continue
                val p = s.at.add(s.v.scale(age.toDouble()))
                val l = d.view.left.scale(SPARKLE_HALF); val u = d.view.up.scale(SPARKLE_HALF)
                SpellShapes.quad(glow, d.m, p.add(l).subtract(u), p.subtract(l).subtract(u), p.subtract(l).add(u), p.add(l).add(u), 0xFFFFFF, a, a, a, a)
            }
            // And the white wire round each, the shield's outline.
            val lines = d.buffers.getBuffer(RenderType.lines())
            val pose = d.pose.last()
            for (q in boxes) SpellShapes.wire(lines, pose, q, 0xFFFFFF, fade)
        }
    }

    // ---- Nether Rift ---------------------------------------------------------------------------------

    /** Rows of the rift, each [RIFT_ROW] tall, its edge stepping out or in a few texels from row
     *  to row like a pixel-art crack. */
    private const val RIFT_ROW = 0.25
    private const val RIFT_CHIPS = 32
    private const val EMBER = 0xFF5010

    /** The rift's shape: each row's left and right edges, and its bobbing obsidian chips. */
    private class Rift(val left: DoubleArray, val right: DoubleArray, val chips: List<DoubleArray>)

    /** A great craggy tear in the air, [NetherRift.HALF_WIDTH] either side: flat and upright,
     *  fixed in the world facing the way it was cast ([NetherRift.facing]), never turning to
     *  the eye. It's full of the Nether portal's swirl, its stepped edges glow like lava, and
     *  its light lies in its plane and on the ground round it; chips of obsidian bob round it
     *  and fire, lava and smoke pour out; a flash from the hand marks where it's cast. Its
     *  fireballs are the scrolls' own Heat bolts, drawn as those are. */
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
            // It stays where it tore open, here as on the server.
            e.deltaMovement = Vec3.ZERO
            if (e.position().distanceToSqr(e.point) > 1.0e-6) e.setPos(e.point)
            if (e.tickCount < NetherRift.SPILL_FROM || e.tickCount > NetherRift.SPILL_TO) return
            val level = e.level()
            val r = level.random
            val half = NetherRift.HALF_WIDTH
            val n = NetherRift.facing(e)
            val across = Vec3(-n.z, 0.0, n.x)
            repeat(8) {
                // Out of its face, across its width and just off its plane.
                val p = e.position().add(across.scale((r.nextDouble() - 0.5) * half * 1.4)).add(n.scale((r.nextDouble() - 0.5) * 0.6))
                    .add(0.0, 0.3 + r.nextDouble() * (NetherRift.HEIGHT - 0.6), 0.0)
                level.addParticle(ParticleTypes.FLAME, p.x, p.y, p.z, (r.nextDouble() - 0.5) * 0.4, r.nextDouble() * 0.08, (r.nextDouble() - 0.5) * 0.4)
                if (r.nextInt(2) == 0) level.addParticle(ParticleTypes.PORTAL, p.x, p.y, p.z, (r.nextDouble() - 0.5) * 0.8, r.nextDouble() * 0.4, (r.nextDouble() - 0.5) * 0.8)
                if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.LAVA, p.x, p.y, p.z, 0.0, 0.0, 0.0)
                if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, 0.0, 0.06, 0.0)
                // Flames licking out over the ground it scorches.
                val g = e.position().add(inDisc(Random(r.nextLong()), NetherRift.REACH)).add(0.0, 0.1, 0.0)
                level.addParticle(ParticleTypes.FLAME, g.x, g.y, g.z, 0.0, 0.05 + r.nextDouble() * 0.05, 0.0)
                if (r.nextInt(4) == 0) level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, g.x, g.y, g.z, 0.0, 0.04, 0.0)
            }
            if (e.tickCount % 4 == 0) level.addParticle(ParticleTypes.EXPLOSION, e.x, e.y + NetherRift.HEIGHT / 2, e.z, 0.0, 0.0, 0.0)
            // Where it leads nowhere, it doesn't draw things in.
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
            // A flat upright tear, fixed in the world: its face looks along n, its width runs across.
            val n = NetherRift.facing(d.e)
            val across = Vec3(-n.z, 0.0, n.x)
            fun at(x: Double, y: Double, z: Double = 0.0) = across.scale(x).add(n.scale(z)).add(0.0, 0.1 + y, 0.0)
            val rows = rift.left.size
            val open = o.toDouble()
            val pulse = (0.85 + 0.15 * sin(d.age * 0.4)).toFloat()
            // The Nether's light, lying in its plane and on the ground it stands on.
            val glow = soft(d)
            planeDisc(glow, d.m, at(0.0, NetherRift.HEIGHT / 2), across, SpellGeom.UP, 6.0 * open, EMBER, 0.55f * o * pulse)
            planeDisc(glow, d.m, at(0.0, NetherRift.HEIGHT / 2), across, SpellGeom.UP, 3.5 * open, 0xFFA040, 0.45f * o * pulse)
            flatDisc(glow, d.m, Vec3(0.0, 0.05, 0.0), NetherRift.REACH * 1.1, EMBER, 0.55f * o * pulse)
            flatDisc(glow, d.m, Vec3(0.0, 0.05, 0.0), NetherRift.REACH * 0.4, 0xFFB050, 0.4f * o * pulse)
            // The portal's swirl, laid at 16 texels a block and anchored to the rift: the texture
            // is cut, never stretched, as the rift opens.
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
            // Its lips glowing like lava, stepping row by row, in its plane.
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
            // Chips of obsidian bobbing round it, each lit where it is.
            if (o < 0.2f) return
            val solid = d.solidBlocks() ?: return
            val obsidian = SpellArt.block("obsidian")
            for ((k, chip) in rift.chips.withIndex()) {
                val p = at(chip[0] * open, chip[1] + sin(d.age * 0.15 + chip[3]) * 0.08, chip[2])
                val corners = SpellGeom.cubeCorners(p, chip[4], d.age * 0.02, d.age * 0.03 + chip[3])
                SpellShapes.texBox(solid, d.m, corners, obsidian, 0xFFFFFF, 1f, d.lightAt(d.at.add(p)), d.e.seed + k)
            }
        }

        /** The unit direction across the line [points] at [i] that lies in the plane whose
         *  normal is [n]. */
        private fun inPlane(points: List<Vec3>, i: Int, n: Vec3): Vec3 {
            val along = points[minOf(points.size - 1, i + 1)].subtract(points[maxOf(0, i - 1)])
            val side = along.cross(n)
            return if (side.lengthSqr() < 1.0e-12) Vec3.ZERO else side.normalize()
        }

        /** A strip of light along [points], [width] wide, lying flat in the plane whose normal
         *  is [n] rather than turned to the eye; a [soft] one fades out to its edges. */
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

        /** A disc of light at [centre] lying flat in the plane of [u] and [v], bright in the
         *  middle and fading to its edge. */
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
