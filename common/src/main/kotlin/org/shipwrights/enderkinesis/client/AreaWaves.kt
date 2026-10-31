package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceLocation
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Inscription
import org.shipwrights.enderkinesis.spell.SpellCurves
import org.shipwrights.enderkinesis.spell.SpellMote
import org.shipwrights.enderkinesis.spell.SpellVfx
import org.shipwrights.enderkinesis.client.SpellGeom.easeOut
import org.shipwrights.enderkinesis.client.SpellGeom.smooth
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Finds the surface once, when the wave starts. Geometry draws each frame; particles emit each tick. Times are in ticks.
object AreaWaves {

    private class Cell(val bx: Int, val bz: Int, val top: Double, val d: Double, val ang: Double, val k: Int, val r1: Double, val r2: Double, val r3: Double) {
        val x = bx + 0.5
        val z = bz + 0.5
        val centre get() = Vec3(x, top, z)
    }

    private class Wave(
        val element: Element, val origin: Vec3, val air: Vec3, val radius: Double, val start: Long, val seed: Int,
        val cells: List<Cell>, val tops: Long2DoubleOpenHashMap, val ground: Double?,
    ) {
        // Growth only; built on first render.
        var roots: List<RootPath>? = null
        val columns: Long2ObjectOpenHashMap<Cell> by lazy(LazyThreadSafetyMode.NONE) {
            Long2ObjectOpenHashMap<Cell>(cells.size).also { m -> for (c in cells) m.put(key(c.bx, c.bz), c) }
        }
        val faceSets = ArrayList<FaceSet>(2)
    }

    /** [faces] is indexed by [Cell.k]. */
    private class FaceSet(val lift: Double, val faces: Array<List<Array<Vec3>>>)

    private class RootPath(val points: List<Vec3>, val dists: List<Double>, val a0: Double, val uc: Double)

    private class Circle(val at: Vec3, val rgb: Int, val start: Long)

    // Render thread only.
    private val waves = ArrayList<Wave>()
    private val circles = ArrayList<Circle>()

    private val SEA: ResourceLocation = EnderkinesisMod.id("textures/environment/ygann_abyss/writhing_mass_sea.png")

    private const val BURST = SpellVfx.WAVE_START_TICKS.toDouble()
    private const val WAVE = SpellVfx.WAVE_TICKS.toDouble()
    private const val CIRCLE_LIFE = 16
    // Thrown particles come to rest near the end of the wave.
    private const val DRAG = 0.825
    private const val THROW_TAU = 0.26 * 20
    // The largest radius the server sends: a full scroll of one element.
    private val MAX_RADIUS = SpellCurves.areaRadius(Inscription.MAX_GLYPHS.toFloat())
    // A full scroll casts at most 7 waves; this cap only stops runaway growth.
    private const val MAX_WAVES = 64
    private val SIDES = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))

    fun add(level: ClientLevel, kind: SpellVfx.Kind, at: Vec3, air: Vec3, size: Float, rgb: Int, seed: Int) {
        when (kind) {
            SpellVfx.Kind.RUNE_CIRCLE -> {
                if (circles.size >= MAX_WAVES) circles.removeAt(0)
                circles += Circle(at, rgb, SpellClock.now(level))
            }
            SpellVfx.Kind.AREA -> {
                val element = Element.entries.getOrNull(rgb) ?: return
                if (!size.isFinite()) return
                val radius = size.toDouble().coerceIn(0.0, MAX_RADIUS)
                if (waves.size >= MAX_WAVES) waves.removeAt(0)
                val (cells, tops) = surface(level, at, radius, seed)
                val below = topAt(level, Math.floor(at.x).toInt(), Math.floor(at.z).toInt(), Math.floor(at.y + 0.5).toInt(), Math.floor(at.y - 1.5).toInt())
                waves += Wave(element, at, air, radius, SpellClock.now(level), seed, cells, tops, below)
            }
            else -> {}
        }
    }

    fun isEmpty(): Boolean = waves.isEmpty() && circles.isEmpty()

    fun reset() {
        waves.clear()
        circles.clear()
    }

    private fun key(bx: Int, bz: Int): Long = (bx.toLong() shl 32) or (bz.toLong() and 0xFFFFFFFFL)

    /** The top of the highest collidable block in [lo]..[hi] with an empty block above it, or null. */
    private fun topAt(level: ClientLevel, bx: Int, bz: Int, hi: Int, lo: Int): Double? {
        val pos = BlockPos.MutableBlockPos()
        var aboveEmpty = level.getBlockState(pos.set(bx, hi + 1, bz)).getCollisionShape(level, pos).isEmpty
        for (y in hi downTo lo) {
            pos.set(bx, y, bz)
            val shape = level.getBlockState(pos).getCollisionShape(level, pos)
            if (!shape.isEmpty && aboveEmpty) return y + shape.max(Direction.Axis.Y)
            aboveEmpty = shape.isEmpty
        }
        return null
    }

    /** Searches 6 blocks down; null in midair. */
    fun groundBelow(level: ClientLevel, p: Vec3): Double? =
        topAt(level, floor(p.x).toInt(), floor(p.z).toInt(), floor(p.y).toInt(), floor(p.y).toInt() - 6)

    /** One cell per column, so no two surface layers overlap. The tops map also holds the ring of columns around the sphere. */
    private fun surface(level: ClientLevel, origin: Vec3, radius: Double, seed: Int): Pair<List<Cell>, Long2DoubleOpenHashMap> {
        val cells = ArrayList<Cell>()
        val tops = Long2DoubleOpenHashMap().apply { defaultReturnValue(Double.NaN) }
        val r = Random(seed)
        val hi = floor(origin.y + radius).toInt()
        val lo = floor(origin.y - radius).toInt() - 1
        for (bx in floor(origin.x - radius).toInt() - 1..floor(origin.x + radius).toInt() + 1) {
            for (bz in floor(origin.z - radius).toInt() - 1..floor(origin.z + radius).toInt() + 1) {
                val top = topAt(level, bx, bz, hi, lo) ?: continue
                tops.put(key(bx, bz), top)
                val x = bx + 0.5; val z = bz + 0.5
                val d = Vec3(x, top, z).distanceTo(origin)
                if (d > radius || hypot(x - origin.x, z - origin.z) < 0.9) continue
                cells += Cell(bx, bz, top, d, atan2(z - origin.z, x - origin.x), cells.size, r.nextDouble(), r.nextDouble(), r.nextDouble())
            }
        }
        return cells to tops
    }

    /** The top face, then each side face above a lower neighbour, [lift] off the block. */
    private fun cellFaces(c: Cell, tops: Long2DoubleOpenHashMap, lift: Double): List<Array<Vec3>> {
        val y = c.top + lift
        val faces = arrayListOf(arrayOf(Vec3(c.bx.toDouble(), y, c.bz + 1.0), Vec3(c.bx + 1.0, y, c.bz + 1.0), Vec3(c.bx + 1.0, y, c.bz.toDouble()), Vec3(c.bx.toDouble(), y, c.bz.toDouble())))
        for ((dx, dz) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val nh = tops.get(key(c.bx + dx, c.bz + dz))
            if (nh.isNaN() || nh >= c.top) continue
            val (p0, p1) = when {
                dx == 1 -> (c.bx + 1 + lift to c.bz + 1.0) to (c.bx + 1 + lift to c.bz.toDouble())
                dx == -1 -> (c.bx - lift to c.bz.toDouble()) to (c.bx - lift to c.bz + 1.0)
                dz == 1 -> (c.bx.toDouble() to c.bz + 1 + lift) to (c.bx + 1.0 to c.bz + 1 + lift)
                else -> (c.bx + 1.0 to c.bz - lift) to (c.bx.toDouble() to c.bz - lift)
            }
            faces += arrayOf(Vec3(p0.first, nh, p0.second), Vec3(p1.first, nh, p1.second), Vec3(p1.first, c.top, p1.second), Vec3(p0.first, c.top, p0.second))
        }
        return faces
    }

    /** Cached per lift, because the surface does not change. */
    private fun facesOf(w: Wave, lift: Double): Array<List<Array<Vec3>>> {
        for (set in w.faceSets) if (set.lift == lift) return set.faces
        return Array(w.cells.size) { cellFaces(w.cells[it], w.tops, lift) }.also { w.faceSets += FaceSet(lift, it) }
    }

    /** NaN where there is no surface. */
    private fun surfaceAt(w: Wave, x: Double, z: Double): Double = w.tops.get(key(floor(x).toInt(), floor(z).toInt()))

    /** In ticks after the cast. Gravity waves close inward. */
    private fun passAt(w: Wave, d: Double): Double {
        if (d > w.radius) return Double.POSITIVE_INFINITY
        val f = d / w.radius
        return BURST + WAVE * if (w.element == Element.GRAVITY) 1 - f else f
    }

    private fun progress(w: Wave, s: Double): Double = ((s - BURST) / WAVE).coerceIn(0.0, 1.0)
    private fun front(w: Wave, s: Double): Double = if (w.element == Element.GRAVITY) w.radius * (1 - progress(w, s)) else w.radius * progress(w, s)

    private fun airFade(s: Double): Float = 1f - smooth(((s - BURST - WAVE) / 7.0)).toFloat()

    private fun centre(w: Wave): Vec3 = w.air

    private fun dirs(n: Int): List<Vec3> = List(n) { i ->
        val y = 1 - 2 * (i + 0.5) / n
        val r = sqrt(1 - y * y)
        val th = i * 2.39996
        Vec3(cos(th) * r, y, sin(th) * r)
    }

    /** Comes to rest near [reach]; [gravity] is in blocks per tick squared. Null after its jittered [life]. */
    private fun thrown(centre: Vec3, d: Vec3, t: Double, reach: Double, gravity: Double, life: Double, seed: Int): Pair<Vec3, Float>? {
        val r = Random(seed)
        val spread = 0.85 + 0.3 * r.nextDouble()
        val lifeJ = life * (0.75 + 0.5 * r.nextDouble())
        if (t < 0 || t > lifeJ) return null
        val p = centre.add(d.scale(reach * spread * (1 - exp(-t / THROW_TAU)))).add(0.0, -0.5 * gravity * t * t, 0.0)
        return p to (1f - smooth(((t - lifeJ * 0.5) / (lifeJ * 0.5))).toFloat())
    }

    private fun open(level: ClientLevel, p: Vec3): Boolean {
        val pos = BlockPos.containing(p)
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty
    }

    fun tick(level: ClientLevel) {
        val now = SpellClock.now(level)
        waves.removeIf { now - it.start > SpellVfx.AREA_LIFE }
        circles.removeIf { now - it.start > CIRCLE_LIFE }
        val random = Random(level.random.nextLong())
        for (w in waves) tickWave(level, w, (now - w.start).toDouble(), random)
    }

    private fun tickWave(level: ClientLevel, w: Wave, s: Double, random: Random) {
        when (w.element) {
            Element.HEAT -> tickHeat(level, w, s, random)
            Element.COLD -> tickCold(level, w, s, random)
            Element.GROWTH -> tickGrowth(level, w, s, random)
            Element.PURITY -> tickPurity(level, w, s, random)
            Element.GRAVITY -> tickGravity(level, w, s, random)
            Element.CHAOS -> tickChaos(level, w, s, random)
            Element.BLOOD -> tickBlood(level, w, s, random)
            Element.SUNFIRE -> tickSunfire(level, w, s, random)
            Element.POISON -> cells(w, s, { it.k % 4 == 0 }, every = 33.0, lasting = 44.0) { c -> mote(level, SpellMote.POISON_SWIRL, Vec3(c.x, c.top + 0.15, c.z), Vec3(0.0, 0.04, 0.0)) }
            Element.VOID -> tickVoid(level, w, s, random)
            Element.SUNDERING -> {}
        }
    }

    private fun crossed(s: Double, at: Double) = s - 1 < at && s >= at

    private fun growing(s: Double) = s >= BURST && s < BURST + WAVE

    private fun particle(level: ClientLevel, p: ParticleOptions, at: Vec3, v: Vec3 = Vec3.ZERO) {
        if (open(level, at)) level.addParticle(p, at.x, at.y, at.z, v.x, v.y, v.z)
    }

    private fun mote(level: ClientLevel, m: SpellMote, at: Vec3, v: Vec3 = Vec3.ZERO) = particle(level, EKParticles.mote(m), at, v)

    /** Points where the front meets the ground, about [perBlock] per block round. */
    private inline fun ring(w: Wave, s: Double, random: Random, perBlock: Double, fn: (Vec3) -> Unit) {
        val f = front(w, s)
        val n = max(3, (f * perBlock / 2).toInt())
        repeat(n) {
            val an = random.nextDouble() * Math.PI * 2
            val x = w.origin.x + cos(an) * f; val z = w.origin.z + sin(an) * f
            val y = surfaceAt(w, x, z)
            if (!y.isNaN()) fn(Vec3(x, y, z))
        }
    }

    /** Calls [fn] for each picked cell when the wave passes it (plus [after]), then every [every] ticks for [lasting] ticks. */
    private inline fun cells(w: Wave, s: Double, pick: (Cell) -> Boolean, after: Double = 0.0, every: Double = 0.0, lasting: Double = 0.0, fn: (Cell) -> Unit) {
        for (c in w.cells) {
            if (!pick(c)) continue
            val age = s - passAt(w, c.d) - after
            if (age < 0 || age >= lasting + 1) continue
            if (age < 1 || (every > 0 && (age % every) < 1)) fn(c)
        }
    }

    private fun tickHeat(level: ClientLevel, w: Wave, s: Double, random: Random) {
        val c0 = centre(w)
        val R = w.radius
        for (i in 0 until 6) if (crossed(s, BURST + i * 2)) {
            val p = c0.add(SpellGeom.spread(random).scale(0.6 + R * 0.25 * random.nextDouble()))
            particle(level, ParticleTypes.EXPLOSION, p, Vec3(2 * (1 - (1.8 + 1.2 * random.nextDouble()) / 4), 0.0, 0.0))
        }
        if (crossed(s, BURST)) {
            for (d in dirs(40)) mote(level, SpellMote.AREA_FLAME, c0, d.scale(R * (1 - DRAG) * (0.85 + 0.3 * random.nextDouble())))
            for (d in dirs(20)) mote(level, SpellMote.HEAT_SMOKE, c0.add(d.scale(0.5)), d.scale(R * 0.7 * 0.1).add(0.0, 0.025, 0.0))
        }
        if (crossed(s, BURST + 10)) for (d in dirs(20)) mote(level, SpellMote.HEAT_EMBER, c0.add(d.scale(R * 0.85)))
        if (growing(s)) ring(w, s, random, 12.0) { p -> mote(level, SpellMote.FLAME, p.add(0.0, 0.2 + 0.3 * random.nextDouble(), 0.0)) }
        cells(w, s, { it.k % 3 == 0 }, after = 6.0) { c -> mote(level, SpellMote.HEAT_SMOKE, Vec3(c.x, c.top + 0.6, c.z), Vec3(0.01, 0.07, 0.0)) }
    }

    private fun tickCold(level: ClientLevel, w: Wave, s: Double, random: Random) {
        val c0 = centre(w)
        val R = w.radius
        if (s > BURST + 8 && s < BURST + 60) repeat(2) {
            mote(level, SpellMote.SNOW_PUFF, c0.add(SpellGeom.spread(random).scale(R * (0.3 + 0.6 * random.nextDouble()))), Vec3(0.0, -0.045, 0.0))
        }
        if (growing(s)) ring(w, s, random, 10.0) { p -> mote(level, SpellMote.FROST, p.add(0.0, 0.3 + 0.8 * random.nextDouble(), 0.0)) }
    }

    private fun tickGrowth(level: ClientLevel, w: Wave, s: Double, random: Random) {
        val c0 = centre(w)
        val R = w.radius
        if (crossed(s, BURST)) for (d in dirs(36)) {
            particle(level, EKParticles.leafChip(), c0, d.scale(R * 0.1 * (0.85 + 0.3 * random.nextDouble())).add(0.0, 0.05, 0.0))
        }
        if (growing(s)) ring(w, s, random, 4.0) { p -> particle(level, EKParticles.leafChip(), p.add(0.0, 0.4 + 0.5 * random.nextDouble(), 0.0)) }
        cells(w, s, { it.k % 3 == 0 }) { c -> mote(level, SpellMote.GROWTH_GLINT, Vec3(c.x, c.top + 0.3, c.z), Vec3(0.0, 0.06, 0.0)) }
    }

    private fun tickPurity(level: ClientLevel, w: Wave, s: Double, random: Random) {
        val c0 = centre(w)
        val R = w.radius
        if (crossed(s, BURST)) for (d in dirs(40)) mote(level, SpellMote.PURITY_GLITTER, c0, d.scale(R * 0.95 / 12 * (0.85 + 0.3 * random.nextDouble())).add(0.0, 0.01, 0.0))
        cells(w, s, { it.k % 2 == 0 }, every = 33.0, lasting = 48.0) { c -> mote(level, SpellMote.PURITY_GLITTER, Vec3(c.x, c.top + 0.2, c.z), Vec3(0.0, 0.1, 0.0)) }
    }

    private fun tickGravity(level: ClientLevel, w: Wave, s: Double, random: Random) {
        val c0 = centre(w)
        val R = w.radius
        if (s >= BURST && s < BURST + 50) repeat(3) {
            val d = SpellGeom.spread(random, 0.6)
            mote(level, SpellMote.GRAVITY_MOTE, c0.add(d.scale(R)), d.scale(-R / SpellMote.GRAVITY_MOTE.life))
        }
        cells(w, s, { it.k % 2 == 0 }) { c -> mote(level, SpellMote.SPRAY_SMOKE, Vec3(c.x + (c.r1 - 0.5) * 0.6, c.top + 0.1, c.z + (c.r2 - 0.5) * 0.6), Vec3(0.0, 0.02, 0.0)) }
    }

    private fun tickChaos(level: ClientLevel, w: Wave, s: Double, random: Random) {
        val c0 = centre(w)
        val R = w.radius
        if (crossed(s, BURST)) for (d in dirs(60)) mote(level, SpellMote.AREA_GLYPH, c0, d.scale(R * (0.6 + 0.4 * random.nextDouble()) * (1 - DRAG)))
        for (k in 0 until 10) if (crossed(s, BURST + k * 2.4)) {
            val from = w.origin.add(0.0, 1.2, 0.0)
            var end = from.add(SpellGeom.spread(random, 0.6).scale(R * (0.4 + 0.6 * random.nextDouble())))
            val g = surfaceAt(w, end.x, end.z)
            if (!g.isNaN() && (k % 2 == 0 || end.y < g)) end = Vec3(end.x, g + 0.05, end.z)
            SpellVfxClient.addLocal(SpellVfx.Kind.ARC, from, end, 1f, Element.CHAOS.color, 6, random.nextInt())
        }
    }

    private fun tickBlood(level: ClientLevel, w: Wave, s: Double, random: Random) {
        val c0 = centre(w)
        val R = w.radius
        if (crossed(s, BURST)) {
            for ((i, d) in dirs(70).withIndex()) mote(level, SpellMote.BLOOD_DROP, c0, d.scale(R / WAVE * 0.9 * (0.6 + 0.4 * ((i * 7) % 10) / 10.0)))
            for (d in dirs(30)) mote(level, SpellMote.BLOOD_MIST, c0, d.scale(R * 0.1 * (0.85 + 0.3 * random.nextDouble())))
        }
        if (s < 28) ring(w, s, random, 8.0) { p -> mote(level, SpellMote.BLOOD_MIST, p.add(0.0, 0.35 + 0.4 * random.nextDouble(), 0.0)) }
    }

    private fun tickSunfire(level: ClientLevel, w: Wave, s: Double, random: Random) {
        val c0 = centre(w)
        val R = w.radius
        if (crossed(s, BURST)) for (d in dirs(44)) mote(level, SpellMote.SUNFIRE_EMBER, c0, d.scale(R * 0.95 * 0.1 * (0.85 + 0.3 * random.nextDouble())))
        cells(w, s, { it.k % 4 == 0 }, every = 40.0, lasting = 44.0) { c -> mote(level, SpellMote.SUNFIRE_SPARK, Vec3(c.x, c.top + 0.1, c.z), Vec3(0.0, 0.08, 0.0)) }
    }

    private fun tickVoid(level: ClientLevel, w: Wave, s: Double, random: Random) {
        if (s >= BURST && s < BURST + WAVE + 28) {
            val tears = tears(w, s, s)
            repeat(2) {
                val tear = tears[random.nextInt(tears.size)]
                mote(level, SpellMote.MASS_DUST, tear[random.nextInt(tear.size)])
            }
            if (crossed(s, BURST + 3)) for (tear in tears) mote(level, SpellMote.YGANN_EYE, tear[(tear.size * (0.4 + 0.4 * random.nextDouble())).toInt().coerceAtMost(tear.size - 1)])
        }
        cells(w, s, { it.r2 < 0.12 }, after = 5.0) { c -> mote(level, SpellMote.YGANN_EYE, Vec3(c.x, c.top + 0.22, c.z)) }
    }

    /** Draws one render type at a time. [pose] is already at the world origin. */
    fun render(pose: PoseStack, buffers: MultiBufferSource, level: ClientLevel, view: SpellView, time: Float) {
        if (waves.isEmpty() && circles.isEmpty()) return
        val m = pose.last().pose()
        renderShade(buffers, m, level, view, time)
        if (SpellVfxRenderTypes.isReady()) renderSprites(buffers, m, level, time)
        renderGlow(buffers, m, level, view, time)
        if (SpellVfxRenderTypes.isReady()) renderLight(buffers, m, view, time)
        renderWires(pose, buffers, time)
    }

    private fun renderShade(buffers: MultiBufferSource, m: Matrix4f, level: ClientLevel, view: SpellView, time: Float) {
        val shade = buffers.getBuffer(SpellVfxRenderTypes.SHADE)
        for (w in waves) {
            val s = time - w.start.toDouble()
            when (w.element) {
                Element.GRAVITY -> { gravityBand(shade, m, w, s); gravityFrames(shade, m, w, s) }
                Element.VOID -> tearsDark(shade, m, view, level, w, s, time)
                else -> {}
            }
        }
    }

    private fun renderSprites(buffers: MultiBufferSource, m: Matrix4f, level: ClientLevel, time: Float) {
        val blocks = buffers.getBuffer(SpellVfxRenderTypes.sprite(BLOCK_ATLAS))
        for (w in waves) {
            val s = time - w.start.toDouble()
            when (w.element) {
                Element.HEAT -> fires(blocks, m, level, w, s, time)
                Element.COLD -> shardsInAir(blocks, m, level, w, s)
                Element.GROWTH -> { roots(blocks, m, level, w, s); vinesInAir(blocks, m, level, w, s) }
                else -> {}
            }
        }
        if (waves.any { it.element == Element.BLOOD }) {
            val particles = buffers.getBuffer(SpellVfxRenderTypes.sprite(PARTICLE_ATLAS))
            for (w in waves) if (w.element == Element.BLOOD) stains(particles, m, level, w, time - w.start.toDouble())
        }
        if (SpellVfxRenderTypes.isDissolveReady()) {
            if (waves.any { it.element == Element.COLD }) {
                val ice = buffers.getBuffer(SpellVfxRenderTypes.dissolve(BLOCK_ATLAS))
                for (w in waves) if (w.element == Element.COLD) frost(ice, m, level, w, time - w.start.toDouble())
            }
            if (waves.any { it.element == Element.VOID }) {
                val sea = buffers.getBuffer(SpellVfxRenderTypes.dissolve(SEA))
                for (w in waves) if (w.element == Element.VOID) mass(sea, m, level, w, time - w.start.toDouble())
            }
        }
    }

    private fun renderGlow(buffers: MultiBufferSource, m: Matrix4f, level: ClientLevel, view: SpellView, time: Float) {
        val glow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        for (c in circles) circleRings(glow, m, c, time - c.start.toDouble())
        for (w in waves) {
            val s = time - w.start.toDouble()
            when (w.element) {
                Element.PURITY -> { purityCubes(glow, m, w, s); SpellShapes.glowCubeFaces(glow, m, aegisCube(w, s), 0xFFFFFF, 0.25f * airFade(s), 0.25) }
                Element.SUNFIRE -> { sunlitGround(glow, m, w, s); sunRays(glow, m, view, w, s) }
                Element.VOID -> { massRim(glow, m, w, s); tearsGlow(glow, m, view, level, w, s, time) }
                else -> {}
            }
        }
    }

    private fun renderLight(buffers: MultiBufferSource, m: Matrix4f, view: SpellView, time: Float) {
        val light = buffers.getBuffer(SpellVfxRenderTypes.glow(PARTICLE_ATLAS))
        for (c in circles) circleGlyphs(light, m, c, time - c.start.toDouble(), time)
        for (w in waves) if (w.element == Element.SUNFIRE) {
            val s = time - w.start.toDouble()
            SpellShapes.sprite(light, m, view, centre(w), 4.0, SpellArt.particle("flash"), 0xFFE2A0, max(0f, 1f - (s / 6.0).toFloat()), SpellShapes.FULL_BRIGHT)
        }
    }

    private fun renderWires(pose: PoseStack, buffers: MultiBufferSource, time: Float) {
        if (waves.none { it.element == Element.PURITY }) return
        val lines = buffers.getBuffer(RenderType.lines())
        for (w in waves) if (w.element == Element.PURITY) {
            val s = time - w.start.toDouble()
            SpellShapes.wire(lines, pose.last(), aegisCube(w, s), 0xFFFFFF, airFade(s))
            for ((cube, a) in risingCubes(w, s)) SpellShapes.wire(lines, pose.last(), cube, 0xFFFFFF, a)
        }
    }

    private fun circleAlpha(s: Double): Float = (smooth(s / 1.2) * (1 - smooth((s - BURST - 5) / 9.0))).toFloat()

    private fun circleRings(c: VertexConsumer, m: Matrix4f, circle: Circle, s: Double) {
        val a = circleAlpha(s)
        if (a <= 0.01f) return
        val at = circle.at.add(0.0, 0.02, 0.0)
        val rgb = SpellGeom.whiten(circle.rgb, 0.25f)
        SpellShapes.ring(c, m, at, 1.75, 0.04, rgb, 0.6f * a, 0.0)
        SpellShapes.ring(c, m, at, 1.15, 0.03, rgb, 0.5f * a, 0.0)
    }

    private fun circleGlyphs(c: VertexConsumer, m: Matrix4f, circle: Circle, s: Double, time: Float) {
        val a = circleAlpha(s)
        if (a <= 0.01f) return
        val at = circle.at.add(0.0, 0.02, 0.0)
        val rgb = SpellGeom.whiten(circle.rgb, 0.25f)
        for ((n, rad, spin, size) in arrayOf(Glyphs(14, 1.45, 0.03, 0.34), Glyphs(9, 0.85, -0.05, 0.28))) {
            for (k in 0 until n) {
                val an = k * 2 * Math.PI / n + time * spin
                flat(c, m, at.add(cos(an) * rad, 0.0, sin(an) * rad), size, an + Math.PI / 2, SpellArt.glyph(k * 3 + n), rgb, a, SpellShapes.FULL_BRIGHT)
            }
        }
    }

    private data class Glyphs(val n: Int, val rad: Double, val spin: Double, val size: Double)

    private fun flat(c: VertexConsumer, m: Matrix4f, at: Vec3, size: Double, angle: Double, sprite: Sprite, rgb: Int, alpha: Float, light: Int) {
        val h = size / 2
        val u = Vec3(cos(angle), 0.0, sin(angle)); val v = Vec3(-sin(angle), 0.0, cos(angle))
        SpellShapes.tquad(
            c, m, at.add(u.scale(-h)).add(v.scale(h)), at.add(u.scale(h)).add(v.scale(h)), at.add(u.scale(h)).add(v.scale(-h)), at.add(u.scale(-h)).add(v.scale(-h)),
            sprite, floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f), rgb, alpha, light,
        )
    }

    /** Matches the proportions of the vanilla cross model. */
    private fun cross(c: VertexConsumer, m: Matrix4f, base: Vec3, size: Double, sprite: Sprite, alpha: Float, light: Int) {
        val half = (14.4 / 16) * Math.sqrt(2.0) * size / 2
        val top = base.add(0.0, size, 0.0)
        for (d in arrayOf(Vec3(Math.sqrt(0.5), 0.0, Math.sqrt(0.5)), Vec3(Math.sqrt(0.5), 0.0, -Math.sqrt(0.5)))) {
            val hw = d.scale(half)
            SpellShapes.tquad(c, m, base.subtract(hw), base.add(hw), top.add(hw), top.subtract(hw), sprite, floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f), 0xFFFFFF, alpha, light)
        }
    }

    private fun fires(c: VertexConsumer, m: Matrix4f, level: ClientLevel, w: Wave, s: Double, time: Float) {
        val fire = SpellArt.block("fire_0")
        for (cell in w.cells) {
            if (cell.k % 2 != 0) continue
            val age = s - passAt(w, cell.d)
            if (age < 0 || age >= 18) continue
            val light = SpellShapes.lightAt(level, cell.centre.add(0.0, 0.5, 0.0))
            for (j in 0 until 1 + (cell.r3 * 3).toInt()) {
                val r = Random(cell.k * 13 + j)
                val size = 0.3 + 0.55 * r.nextDouble()
                val room = 1 - size
                val base = Vec3(cell.bx + size / 2 + room * r.nextDouble(), cell.top, cell.bz + size / 2 + room * r.nextDouble())
                cross(c, m, base, size, fire, 1f - smooth(((age - 8 - 4 * j) / 7.0)).toFloat(), light)
            }
        }
    }

    /** Draws the snow layer as one surface, with sides only where it ends. */
    private fun frost(c: VertexConsumer, m: Matrix4f, level: ClientLevel, w: Wave, s: Double) {
        val melt = 1f - smooth(((s - BURST - 60) / 16.0)).toFloat()
        if (melt <= 0.01f) return
        val snow = SpellArt.block("snow")
        val columns = w.columns
        val layer = 2 / 16.0
        for (cell in w.cells) {
            val age = s - passAt(w, cell.d)
            if (age < 0) continue
            val light = SpellShapes.lightAt(level, cell.centre.add(0.0, 0.5, 0.0))
            val seed = SpellVfxRenderTypes.dissolveColour(cell.k * 37)
            val amount = melt * smooth(age / 3.0).toFloat()
            val x0 = cell.bx.toDouble(); val z0 = cell.bz.toDouble(); val y = cell.top + layer
            SpellShapes.texFace(c, m, Vec3(x0, y, z0 + 1), Vec3(x0 + 1, y, z0 + 1), Vec3(x0 + 1, y, z0), Vec3(x0, y, z0), snow, seed, amount, light)
            for (side in SIDES) {
                val dx = side[0]; val dz = side[1]
                val n = columns.get(key(cell.bx + dx, cell.bz + dz))
                // No side between two snow cells at the same height.
                if (n != null && n.top == cell.top && s >= passAt(w, n.d)) continue
                val (p0, p1) = when {
                    dx == 1 -> Vec3(x0 + 1, 0.0, z0 + 1) to Vec3(x0 + 1, 0.0, z0)
                    dx == -1 -> Vec3(x0, 0.0, z0) to Vec3(x0, 0.0, z0 + 1)
                    dz == 1 -> Vec3(x0, 0.0, z0 + 1) to Vec3(x0 + 1, 0.0, z0 + 1)
                    else -> Vec3(x0 + 1, 0.0, z0) to Vec3(x0, 0.0, z0)
                }
                SpellShapes.texFace(
                    c, m, Vec3(p0.x, cell.top, p0.z), Vec3(p1.x, cell.top, p1.z), Vec3(p1.x, y, p1.z), Vec3(p0.x, y, p0.z),
                    snow, seed, amount, light, 0.0, 14 / 16.0,
                )
            }
            if (cell.k % 2 == 0) {
                val dir = Vec3(cos(cell.ang) * 0.45, 1.0, sin(cell.ang) * 0.45).normalize()
                val h = (0.35 + 0.7 * cell.r1) * easeOut(min(1.0, age / 2.4))
                val p = SpellGeom.prismCorners(Vec3(cell.x + (cell.r2 - 0.5) * 0.4, cell.top + 0.05, cell.z + (cell.r3 - 0.5) * 0.4), dir, h, 0.07 + 0.06 * cell.r2, cell.r3 * 6)
                SpellShapes.texBox(c, m, p, SpellArt.block(if (cell.k % 6 != 0) "packed_ice" else "blue_ice"), SpellVfxRenderTypes.dissolveColour(cell.k * 53 + 11), melt, light, cell.k)
            }
        }
    }

    private fun shardsInAir(c: VertexConsumer, m: Matrix4f, level: ClientLevel, w: Wave, s: Double) {
        val t = s - BURST
        if (t < 0 || t > 40) return
        val c0 = centre(w)
        for ((i, d) in dirs(26).withIndex()) {
            val (p, a) = thrown(c0, d, t, w.radius * 0.92, 6.0 / 400, 30.0, i * 11 + 2) ?: continue
            if (!open(level, p)) continue
            val tip = d.add(0.0, -min(1.5, t / 20 * 1.4), 0.0).normalize()
            SpellShapes.texBox(c, m, SpellGeom.prismCorners(p, tip, 0.45, 0.06, i + t / 10), SpellArt.block(if (i % 4 != 0) "packed_ice" else "blue_ice"), 0xFFFFFF, a, SpellShapes.lightAt(level, p), i)
        }
    }

    private fun rootPaths(w: Wave, count: Int, seed: Int): List<RootPath> {
        val r = Random(seed)
        return List(count) { k ->
            val a0 = k * 2 * Math.PI / count + (r.nextDouble() - 0.5) * 0.4
            val wob = r.nextDouble() * 6
            val points = ArrayList<Vec3>(); val dists = ArrayList<Double>()
            var prevY: Double? = null; var prev: Vec3? = null
            var t = 0.6
            while (t <= w.radius) {
                val a = a0 + 0.22 * sin(t * 0.9 + wob)
                val x = w.origin.x + cos(a) * t; val z = w.origin.z + sin(a) * t
                val y = surfaceAt(w, x, z)
                if (y.isNaN()) break
                if (prevY != null && prev != null && y != prevY) {
                    val edge = prev.lerp(Vec3(x, 0.0, z), 0.5)
                    points += Vec3(edge.x, prevY + 0.02, edge.z); dists += t - 0.1
                    points += Vec3(edge.x, y + 0.02, edge.z); dists += t - 0.1
                }
                points += Vec3(x, y + 0.02, z); dists += t
                prevY = y; prev = Vec3(x, 0.0, z)
                t += 0.2
            }
            RootPath(points, dists, a0, r.nextDouble())
        }
    }

    private fun drawRoot(c: VertexConsumer, m: Matrix4f, path: RootPath, reach: Double, w0: Double, w1: Double, sprite: Sprite, rgb: Int, light: Int) {
        var n = 0
        while (n < path.points.size && path.dists[n] <= reach) n++
        if (n < 2) return
        val side = Vec3(-sin(path.a0), 0.0, cos(path.a0))
        val last = max(1.0, path.dists.last())
        val edges = List(n) { i -> side.scale((w0 + (w1 - w0) * path.dists[i] / last) / 2) }
        SpellShapes.stripPx(c, m, path.points.subList(0, n), edges, sprite, rgb, FloatArray(n) { 1f }, path.uc, light)
    }

    private fun roots(c: VertexConsumer, m: Matrix4f, level: ClientLevel, w: Wave, s: Double) {
        val roots = w.roots ?: (rootPaths(w, 12, 61) + rootPaths(w, 9, 87)).also { w.roots = it }
        val front = front(w, s)
        val light = SpellShapes.lightAt(level, w.origin.add(0.0, 0.5, 0.0))
        val mangrove = SpellArt.block("mangrove_roots_side")
        val vine = SpellArt.block("vine")
        roots.forEachIndexed { i, path ->
            if (i < 12) drawRoot(c, m, path, front, 0.26, 0.08, mangrove, 0xFFFFFF, light)
            else drawRoot(c, m, path, front * 0.95, 0.2, 0.08, vine, VINE_GREEN, light)
        }
        val faces = facesOf(w, 0.006)
        for (cell in w.cells) {
            val age = s - passAt(w, cell.d)
            if (age < 0) continue
            val grow = (age / 8.0).coerceIn(0.0, 1.0)
            val own = faces[cell.k]
            for (i in 1 until own.size) {
                val f = own[i]
                SpellShapes.texFace(c, m, f[0], f[1], f[1].lerp(f[2], grow), f[0].lerp(f[3], grow), vine, VINE_GREEN, 1f, SpellShapes.lightAt(level, cell.centre.add(0.0, 0.5, 0.0)))
            }
        }
    }

    private fun vinesInAir(c: VertexConsumer, m: Matrix4f, level: ClientLevel, w: Wave, s: Double) {
        val fade = airFade(s)
        if (fade <= 0.01f || s < BURST) return
        val f = front(w, s)
        val c0 = centre(w)
        val vine = SpellArt.block("vine")
        for ((k, d) in dirs(8).withIndex()) {
            val (s1, s2) = SpellGeom.basis(d)
            val points = ArrayList<Vec3>()
            var x = 0.3
            while (x <= f) {
                val an = x * 2.2 + k
                points += c0.add(d.scale(x)).add(s1.scale(cos(an) * 0.25)).add(s2.scale(sin(an) * 0.25))
                x += 0.15
            }
            if (points.size < 2) continue
            val alphas = FloatArray(points.size) { if (open(level, points[it])) fade else 0f }
            SpellShapes.stripPx(c, m, points, List(points.size) { s1.scale(0.09) }, vine, VINE_GREEN, alphas, (k * 0.13) % 1.0, SpellShapes.lightAt(level, c0))
        }
    }

    private const val VINE_GREEN = 0x48B518

    private fun risingCubes(w: Wave, s: Double): List<Pair<Array<Vec3>, Float>> {
        val out = ArrayList<Pair<Array<Vec3>, Float>>()
        for (cell in w.cells) {
            val age = s - passAt(w, cell.d)
            if (age < 0 || age >= 12) continue
            val f = (age / 12).toFloat()
            out += SpellGeom.cubeCorners(Vec3(cell.x, cell.top + 0.1 + age / 20 * 0.9, cell.z), 0.09 * (1 - f), cell.r1 * 6 + age / 20 * 3, cell.r2 * 6 + age / 20 * 2) to (1 - f)
        }
        return out
    }

    private fun purityCubes(c: VertexConsumer, m: Matrix4f, w: Wave, s: Double) {
        for ((cube, a) in risingCubes(w, s)) SpellShapes.glowCubeFaces(c, m, cube, 0xFFFFFF, 0.7f * a, 0.5)
    }

    private fun aegisCube(w: Wave, s: Double): Array<Vec3> {
        val t = (s - BURST) / 20
        return SpellGeom.cubeCorners(centre(w), front(w, s) * 0.85, t * 0.6, 0.3 + t * 0.2)
    }

    private fun gravityBand(c: VertexConsumer, m: Matrix4f, w: Wave, s: Double) {
        val f = front(w, s)
        val fade = 1 - smooth((s - BURST - WAVE) / 8.0)
        if (fade <= 0.01) return
        val band = { v: Vec3 -> (0.5 * exp(-((v.distanceTo(w.origin) - f) / 0.9).let { it * it }) * fade).toFloat() }
        val faces = facesOf(w, 0.005)
        for (cell in w.cells) for (face in faces[cell.k]) shadeFace(c, m, face, 0x0C0812, band)
    }

    private fun gravityFrames(c: VertexConsumer, m: Matrix4f, w: Wave, s: Double) {
        val fade = airFade(s)
        val f = front(w, s)
        if (fade <= 0.01f || f < 0.2 || s < BURST) return
        val c0 = centre(w)
        val t = (s - BURST) / 20
        val corners = arrayOf(1 to 1, -1 to 1, -1 to -1, 1 to -1)
        for (ax in 0 until 3) {
            val turn = t * 0.8 + ax
            val ct = cos(turn); val st = sin(turn)
            fun at(x: Double, y: Double): Vec3 {
                val a = x * ct - y * st; val b = x * st + y * ct
                return c0.add(when (ax) { 0 -> Vec3(a, b, 0.0); 1 -> Vec3(0.0, a, b); else -> Vec3(b, 0.0, a) })
            }
            val half = f * 0.9
            for ((width, rgb, alpha) in arrayOf(Triple(0.35, 0x0C0812, 0.55f), Triple(0.1, 0x3A2A5A, 0.8f))) {
                for (k in 0 until 4) {
                    val (x0, y0) = corners[k]; val (x1, y1) = corners[(k + 1) % 4]
                    for ((inner, outer, ai, ao) in arrayOf(Band(half - width, half, 0f, 1f), Band(half, half + width, 1f, 0f))) {
                        SpellShapes.quad(
                            c, m, at(x0 * inner, y0 * inner), at(x1 * inner, y1 * inner), at(x1 * outer, y1 * outer), at(x0 * outer, y0 * outer),
                            rgb, alpha * ai * fade, alpha * ai * fade, alpha * ao * fade, alpha * ao * fade,
                        )
                    }
                }
            }
        }
    }

    private data class Band(val inner: Double, val outer: Double, val ai: Float, val ao: Float)

    private fun shadeFace(c: VertexConsumer, m: Matrix4f, f: Array<Vec3>, rgb: Int, alphaAt: (Vec3) -> Float) {
        val a0 = alphaAt(f[0]); val a1 = alphaAt(f[1]); val a2 = alphaAt(f[2]); val a3 = alphaAt(f[3])
        if (a0 <= 0.004f && a1 <= 0.004f && a2 <= 0.004f && a3 <= 0.004f) return
        SpellShapes.quad(c, m, f[0], f[1], f[2], f[3], rgb, a0, a1, a2, a3)
    }

    private fun stains(c: VertexConsumer, m: Matrix4f, level: ClientLevel, w: Wave, s: Double) {
        val dry = 1f - smooth(((s - BURST - 70) / 24.0)).toFloat()
        if (dry <= 0.01f) return
        val drop = SpellArt.particle("drip_land")
        for (cell in w.cells) {
            if (cell.r1 > 0.45 || s < passAt(w, cell.d)) continue
            val s0 = 0.1 + 0.12 * cell.r2
            val x = cell.x + (cell.r3 - 0.5) * (0.9 - 2 * s0)
            val z = cell.z + (cell.r1 - 0.2) * (0.9 - 2 * s0)
            flat(c, m, Vec3(x, cell.top + 0.014, z), s0 * 2, cell.r3 * 37.0, drop, 0x7A0A12, 0.9f * dry, SpellShapes.lightAt(level, Vec3(x, cell.top + 0.5, z)))
        }
    }

    private fun sunlitGround(c: VertexConsumer, m: Matrix4f, w: Wave, s: Double) {
        val f = front(w, s)
        val light = { v: Vec3 ->
            val d = v.distanceTo(w.origin)
            val atFront = 0.55 * exp(-((d - f) / 0.8).let { it * it }) * (1 - smooth((s - BURST - WAVE) / 6.0))
            val behind = if (d < f) 0.18 * (1 - smooth((s - BURST - 10) / 20.0)) else 0.0
            (atFront + behind).toFloat()
        }
        val faces = facesOf(w, 0.005)
        for (cell in w.cells) for (face in faces[cell.k]) shadeFace(c, m, face, 0xFFCF6A, light)
    }

    private fun sunRays(c: VertexConsumer, m: Matrix4f, view: SpellView, w: Wave, s: Double) {
        val t = s - BURST
        if (t < 0) return
        val c0 = centre(w)
        val beam = max(0.0, 1 - t / 9.0).toFloat()
        if (beam > 0 && w.ground != null) {
            val col = listOf(w.origin.add(0.0, 12.0, 0.0), w.origin.add(0.0, 5.0, 0.0), Vec3(w.origin.x, w.ground + 0.02, w.origin.z))
            SpellShapes.strip(c, m, view, col, { 2.0 * beam + 0.3 }, Element.SUNFIRE.color, 0.45f * beam, true)
            SpellShapes.strip(c, m, view, col, { 0.5 * beam + 0.1 }, 0xFFF4D0, 0.85f * beam, false)
        }
        val fade = airFade(s)
        if (fade <= 0.01f) return
        val f = front(w, s)
        for ((i, d) in dirs(22).withIndex()) {
            val head = f * (0.8 + 0.25 * ((i * 3) % 5) / 5.0)
            val tail = max(0.0, head - 1.6 - f * 0.3)
            if (head - tail <= 0.1) continue
            val pts = listOf(c0.add(d.scale(tail)), c0.add(d.scale((tail + head) / 2)), c0.add(d.scale(head)))
            SpellShapes.strip(c, m, view, pts, { doubleArrayOf(0.02, 0.18, 0.28)[it] }, Element.SUNFIRE.color, 0.6f * fade, true, floatArrayOf(0.1f, 1f, 0.5f))
            SpellShapes.strip(c, m, view, pts, { doubleArrayOf(0.005, 0.05, 0.08)[it] }, 0xFFF4D0, 0.9f * fade, false, floatArrayOf(0.1f, 1f, 0.7f))
        }
    }

    /** A soft edge about a block wide behind the front. The mass recedes in the same way, from the inside out. */
    private fun cover(w: Wave, s: Double, v: Vec3): Float {
        val d = v.distanceTo(w.origin)
        val spread = smooth((front(w, s) - d) / 1.3 + 0.35)
        val gone = smooth((s - passAt(w, min(d, w.radius)) - 34) / 14.0)
        return (spread * (1 - gone)).toFloat()
    }

    private fun mass(c: VertexConsumer, m: Matrix4f, level: ClientLevel, w: Wave, s: Double) {
        val sea = SpellArt.sea()
        val faces = facesOf(w, 0.004)
        for (cell in w.cells) {
            val light = SpellShapes.lightAt(level, cell.centre.add(0.0, 0.5, 0.0))
            val seed = (cell.k * 37) and 0xFF
            for (f in faces[cell.k]) texFaceA(c, m, f, sea, light, seed) { cover(w, s, it) }
        }
    }

    private fun massRim(c: VertexConsumer, m: Matrix4f, w: Wave, s: Double) {
        val f = front(w, s)
        val fade = 1 - smooth((s - BURST - WAVE) / 8.0)
        if (fade <= 0.01) return
        val rim = { v: Vec3 -> (0.55 * exp(-((v.distanceTo(w.origin) - f) / 0.7).let { it * it }) * fade).toFloat() }
        val faces = facesOf(w, 0.004)
        for (cell in w.cells) for (face in faces[cell.k]) shadeFace(c, m, face, 0xB06AE0, rim)
    }

    /** Splits the face so alpha varies across it and the dissolve edge breaks up. [seed] picks the pattern. */
    private fun texFaceA(c: VertexConsumer, m: Matrix4f, f: Array<Vec3>, sprite: Sprite, light: Int, seed: Int, alphaAt: (Vec3) -> Float) {
        val lu = f[1].distanceTo(f[0]); val lv = f[3].distanceTo(f[0])
        if (lu < 1.0e-6 || lv < 1.0e-6) return
        fun at(u: Double, v: Double): Vec3 = f[0].lerp(f[1], u / lu).lerp(f[3].lerp(f[2], u / lu), v / lv)
        val steps = 2
        for (i in 0 until steps) for (j in 0 until steps) {
            val u0 = lu * i / steps; val u1 = lu * (i + 1) / steps
            val v0 = lv * j / steps; val v1 = lv * (j + 1) / steps
            val q = arrayOf(at(u0, v0), at(u1, v0), at(u1, v1), at(u0, v1))
            val a = FloatArray(4) { alphaAt(q[it]) }
            if (a.all { it <= 0.004f }) continue
            // The texture repeats once per block, so each piece takes its share of the tile.
            val tu0 = (u0 % 1).toFloat(); val tu1 = tu0 + ((u1 - u0) % 1.0001).toFloat()
            val tv0 = (v0 % 1).toFloat(); val tv1 = tv0 + ((v1 - v0) % 1.0001).toFloat()
            val uv = floatArrayOf(tu0, 1 - tv0, tu1, 1 - tv0, tu1, 1 - tv1, tu0, 1 - tv1)
            for (k in 0 until 4) {
                val p = q[k]
                c.vertex(m, p.x.toFloat(), p.y.toFloat(), p.z.toFloat()).uv(sprite.u(uv[k * 2]), sprite.v(uv[k * 2 + 1]))
                    .color(seed, 0xFF, 0xFF, (a[k] * 255).toInt().coerceIn(0, 255)).uv2(light).endVertex()
            }
        }
    }

    private fun tears(w: Wave, s: Double, time: Double): List<List<Vec3>> {
        val c0 = centre(w)
        val f = max(0.5, front(w, s))
        return dirs(9).mapIndexed { i, d ->
            SpellGeom.jagged(Random(i * 41 + (time / 4).toInt()), c0.add(d.scale(0.4)), c0.add(d.scale(f)), 0.08)
        }
    }

    private fun tearsDark(c: VertexConsumer, m: Matrix4f, view: SpellView, level: ClientLevel, w: Wave, s: Double, time: Float) {
        val fade = airFade(s)
        if (fade <= 0.01f || s < BURST) return
        for (pts in tears(w, s, time.toDouble())) {
            val on = FloatArray(pts.size) { if (open(level, pts[it])) 1f else 0f }
            SpellShapes.strip(c, m, view, pts, { 0.14 * (1 - 0.8 * it / pts.size) }, 0x07030C, 0.95f * fade, false, on)
        }
    }

    private fun tearsGlow(c: VertexConsumer, m: Matrix4f, view: SpellView, level: ClientLevel, w: Wave, s: Double, time: Float) {
        val fade = airFade(s)
        if (fade <= 0.01f || s < BURST) return
        for (pts in tears(w, s, time.toDouble())) {
            val on = FloatArray(pts.size) { if (open(level, pts[it])) 1f else 0f }
            SpellShapes.strip(c, m, view, pts, { 0.32 * (1 - it.toDouble() / pts.size) }, 0x6A2FA0, 0.5f * fade, true, on)
        }
    }
}
