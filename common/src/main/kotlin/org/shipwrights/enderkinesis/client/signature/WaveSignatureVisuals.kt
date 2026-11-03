package org.shipwrights.enderkinesis.client.signature

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.animation.KeyframeAnimations
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.Sprite
import org.shipwrights.enderkinesis.client.SpellArt
import org.shipwrights.enderkinesis.client.SpellGeom
import org.shipwrights.enderkinesis.client.SpellShapes
import org.shipwrights.enderkinesis.client.model.TentacleAnimation
import org.shipwrights.enderkinesis.client.model.TentacleModel
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.spell.Signature
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.shipwrights.enderkinesis.spell.signature.WaveSignatures
import org.shipwrights.enderkinesis.spell.signature.WaveSignatures.Avalanche
import org.shipwrights.enderkinesis.spell.signature.WaveSignatures.GardenSanguine
import org.shipwrights.enderkinesis.spell.signature.WaveSignatures.Bloodwave
import org.shipwrights.enderkinesis.spell.signature.WaveSignatures.Shroomspread
import org.shipwrights.enderkinesis.spell.signature.WaveSignatures.Fireweed
import org.shipwrights.enderkinesis.spell.signature.WaveSignatures.YgannsGrasp
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

object WaveSignatureVisuals {
    val ALL: Map<Signature, SignatureVisual> = mapOf(
        Signature.AVALANCHE to AvalancheVisual,
        Signature.BLOODWAVE to BloodwaveVisual,
        Signature.YGANNS_GRASP to YgannsGraspVisual,
        Signature.FIREWEED to FireweedVisual,
        Signature.SHROOMSPREAD to ShroomspreadVisual,
        Signature.GARDEN_SANGUINE to GardenSanguineVisual,
    )

    private const val VINE_GREEN = 0x48B518
    private const val ASH = 0x2A2420
    private const val EMBER = 0xFF7A22
    private const val BLOOD = 0xE3555F
    private const val VOID = 0xB77FD4
    private const val SPORE_GREEN = 0x7E9A48

    private fun fade(d: SignatureDraw, inTicks: Float, outTicks: Float): Float =
        minOf(1f, d.age / inTicks, (d.e.life - d.age) / outTicks).coerceIn(0f, 1f)

    private fun rnd(r: Random, lo: Double, hi: Double): Double = lo + r.nextDouble() * (hi - lo)

    private fun soft(d: SignatureDraw): VertexConsumer = d.softGlow() ?: d.glow()

    private fun softDark(d: SignatureDraw): VertexConsumer = d.softShade() ?: d.shade()

    /** Caches the ground height for each column of the lane. */
    private class LaneLook(val lane: WaveSignatures.Lane, val level: Level) {
        private val grounds = HashMap<Long, Double>()

        fun ground(s: Double, across: Double): Double {
            val p = lane.at(s, across)
            return grounds.getOrPut(BlockPos.asLong(floor(p.x).toInt(), 0, floor(p.z).toInt())) { lane.ground(level, s, across) }
        }

        fun on(s: Double, across: Double, up: Double): Vec3 = lane.at(s, across).let { Vec3(it.x, ground(s, across) + up, it.z) }

        /** Turns a box so that its x is along the lane side and its z is along the lane. */
        val yaw: Double get() = atan2(lane.side.z, lane.side.x)
    }

    private fun laneOf(e: SignatureEntity, length: Double): LaneLook =
        e.clientState as? LaneLook ?: LaneLook(WaveSignatures.lane(e.level(), e, length), e.level()).also { e.clientState = it }

    /** Shows only the top [h] (0..1) of the texture, so a growing plant is never squashed. */
    private fun plant(c: VertexConsumer, m: Matrix4f, base: Vec3, sprite: Sprite, h: Double, rgb: Int, alpha: Float, light: Int, turn: Double = 0.0, width: Double = 1.0) {
        if (h <= 0.01 || alpha <= 0.004f) return
        val half = width / 2
        val u0 = (0.5 - half).toFloat(); val u1 = (0.5 + half).toFloat(); val hv = h.toFloat()
        for (k in 0 until 2) {
            val a = turn + Math.PI / 4 + k * Math.PI / 2
            val dx = cos(a) * half; val dz = sin(a) * half
            val p0 = base.add(-dx, 0.0, -dz); val p1 = base.add(dx, 0.0, dz)
            SpellShapes.tquad(c, m, p0, p1, p1.add(0.0, h, 0.0), p0.add(0.0, h, 0.0), sprite, floatArrayOf(u0, hv, u1, hv, u1, 0f, u0, 0f), rgb, alpha, light)
        }
    }

    private fun topFace(d: SignatureDraw, c: VertexConsumer, x: Int, z: Int, y: Double, sprite: Sprite, rgb: Int, alpha: Float) {
        val light = d.lightAt(Vec3(x + 0.5, y + 0.5, z + 0.5))
        SpellShapes.texFace(
            c, d.m, d.local(Vec3(x.toDouble(), y, z.toDouble())), d.local(Vec3(x + 1.0, y, z.toDouble())),
            d.local(Vec3(x + 1.0, y, z + 1.0)), d.local(Vec3(x.toDouble(), y, z + 1.0)), sprite, rgb, alpha, light,
        )
    }

    private const val PIECE = 6

    /** Lights each piece of [PIECE] points at its own middle, so a long ribbon from light into
     *  shade gets the correct light along its length. */
    private fun litStrip(d: SignatureDraw, c: VertexConsumer, world: List<Vec3>, edges: List<Vec3>, sprite: Sprite, rgb: Int, alpha: Float, uc: Double, lift: Double = 0.3) {
        var i = 0
        while (i < world.size - 1) {
            val j = minOf(world.size - 1, i + PIECE)
            val light = d.lightAt(world[(i + j) / 2].add(0.0, lift, 0.0))
            SpellShapes.stripPx(c, d.m, world.subList(i, j + 1).map(d::local), edges.subList(i, j + 1), sprite, rgb, FloatArray(j - i + 1) { alpha }, uc, light)
            i = j
        }
    }

    private fun dust(r: Float, g: Float, b: Float, size: Float = 1.2f): ParticleOptions = DustParticleOptions(Vector3f(r, g, b), size)

    /** Steps each section as the server does, so that the client heaps follow the same path. */
    object AvalancheVisual : SignatureVisual {
        private const val CUBES_MIN = 4
        private const val CUBES_MAX = 12
        private const val END_FADE = 15f

        private fun slide(e: SignatureEntity): WaveSignatures.Slide =
            e.clientState as? WaveSignatures.Slide ?: Avalanche.slide(e.level(), e.position(), e.point, e.seed).also { e.clientState = it }

        private fun cubes(descent: Double): Int = (CUBES_MIN + descent / 2).toInt().coerceAtMost(CUBES_MAX)

        fun lead(e: SignatureEntity): Vec3 = slide(e).slabs[Avalanche.SLABS / 2].centre()

        override fun tick(e: SignatureEntity) {
            val slide = slide(e)
            val level = e.level()
            while (slide.t < e.tickCount) Avalanche.step(level, slide, e.power)
            val r = level.random
            for (s in slide.slabs) {
                val c = s.centre()
                if (s.landed) {
                    repeat(16) { level.addParticle(BlockParticleOption(ParticleTypes.BLOCK, Blocks.SNOW_BLOCK.defaultBlockState()), c.x, s.y + 0.2, c.z, r.nextGaussian() * 0.3, 0.2 + r.nextDouble() * 0.2, r.nextGaussian() * 0.3) }
                    repeat(4) { level.addParticle(ParticleTypes.CLOUD, c.x + r.nextGaussian() * s.size, s.y + 0.4, c.z + r.nextGaussian() * s.size, 0.0, 0.05, 0.0) }
                }
                if (!s.running) continue
                repeat(2) {
                    val kind = if (r.nextInt(3) == 0) BlockParticleOption(ParticleTypes.BLOCK, Blocks.SNOW_BLOCK.defaultBlockState()) else ParticleTypes.SNOWFLAKE
                    level.addParticle(kind, c.x + r.nextGaussian() * s.size, c.y + r.nextDouble() * s.size, c.z + r.nextGaussian() * s.size, slide.dir.x * 0.3, 0.1 + r.nextDouble() * 0.1, slide.dir.z * 0.3)
                }
                if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.ITEM_SNOWBALL, c.x, s.y + 0.2, c.z, slide.dir.x * 0.2, 0.15, slide.dir.z * 0.2)
            }
        }

        override fun render(d: SignatureDraw) {
            val e = d.e
            val slide = slide(e)
            val f = d.partialTick.toDouble()
            val out = SpellGeom.smooth((e.life - d.age) / END_FADE).toDouble()
            if (out <= 0.01) return
            val yaw = atan2(slide.side.z, slide.side.x)
            val snow = SpellArt.block("snow"); val ice = SpellArt.block("packed_ice")
            d.solidBlocks()?.let { c ->
                for (s in slide.slabs) {
                    val centre = s.centre(f)
                    val size = s.size * out
                    val tumble = s.rolled / s.size
                    val r = Random(s.seed)
                    repeat(cubes(s.descent)) { k ->
                        val off = SpellGeom.spread(r).let { Vec3(it.x, abs(it.y) * 0.6 - 0.2, it.z) }.scale(size * 0.75)
                        val half = size * rnd(r, 0.38, 0.62)
                        val p = centre.add(off)
                        val corners = SpellGeom.cubeCorners(d.local(p), half, yaw + rnd(r, -0.3, 0.3), tumble + k * 1.3)
                        SpellShapes.texBox(c, d.m, corners, if (k % 4 == 3) ice else snow, 0xFFFFFF, 1f, d.lightAt(p.add(0.0, half, 0.0)), s.seed + k)
                    }
                }
            }
            val shade = softDark(d)
            for (s in slide.slabs) {
                val centre = s.centre(f)
                val a = (if (s.running) 0.5f else 0.22f) * out.toFloat()
                SpellShapes.puff(shade, d.m, d.view, d.local(centre.add(0.0, s.size * 0.6, 0.0)), s.size * 1.8, 0xF2F8FF, a)
            }
        }
    }

    object BloodwaveVisual : SignatureVisual {
        /** The cross-section as (distance behind the front, height) pairs. */
        private val PROFILE = arrayOf(-1.8 to 0.0, -1.1 to 0.7, -0.5 to 1.25, -0.1 to 1.45, 0.25 to 1.3, 0.45 to 0.85)
        /** [Bloodwave.crest] scales the profile to this height. */
        private const val PROFILE_TOP = 1.45
        /** Sort slots for each ray: one body face for each profile step, then the crest mist. */
        private const val SLOTS = 6
        private const val MIST = 5
        // Render thread only: the dark layer's draw order, reused each frame.
        private var order = LongArray(Bloodwave.RAYS * SLOTS)

        override val afterTranslucent = true

        /** The squared distance is not negative, so its float bits sort as the value does. */
        private fun sortKey(cam: Vec3, x: Double, y: Double, z: Double, slot: Int): Long {
            val dx = x - cam.x; val dy = y - cam.y; val dz = z - cam.z
            return ((dx * dx + dy * dy + dz * dz).toFloat().toRawBits().toLong() shl 32) or slot.toLong()
        }

        @Suppress("UNCHECKED_CAST") // Only this visual puts a lane list in clientState.
        private fun rays(e: SignatureEntity): List<WaveSignatures.Lane> =
            e.clientState as? List<WaveSignatures.Lane> ?: Bloodwave.rays(e.level(), e.position(), Bloodwave.reach(e.power)).also { e.clientState = it }

        override fun tick(e: SignatureEntity) {
            val t = e.tickCount
            if (t > Bloodwave.endTick(e.power)) return
            val rays = rays(e)
            val level = e.level()
            val r = level.random
            val radius = Bloodwave.radius(t.toDouble())
            val crest = Bloodwave.crest(e.power)
            repeat(8) {
                val lane = rays[r.nextInt(rays.size)]
                if (!lane.reaches(radius)) return@repeat
                val p = lane.at(radius, 0.0).add(0.0, crest * 0.9, 0.0)
                level.addParticle(dust(0.55f, 0.03f, 0.08f, 1.6f), p.x, p.y, p.z, lane.dir.x * 0.2, 0.1, lane.dir.z * 0.2)
            }
            repeat(3) {
                val lane = rays[r.nextInt(rays.size)]
                if (!lane.reaches(radius)) return@repeat
                val p = lane.at(radius - 0.6, 0.0).add(0.0, crest, 0.0)
                level.addParticle(ParticleTypes.PORTAL, p.x, p.y, p.z, lane.dir.x * 0.3, 0.2, lane.dir.z * 0.3)
                if (r.nextInt(2) == 0) level.addParticle(ParticleTypes.DRIPPING_LAVA, p.x, p.y, p.z, 0.0, 0.0, 0.0)
            }
        }

        override fun render(d: SignatureDraw) {
            val e = d.e
            val rays = rays(e)
            val end = Bloodwave.endTick(e.power)
            val age = d.age
            val grow = SpellGeom.smooth(age / 6f)
            val sink = 1f - ((age - end) / 12f).coerceIn(0f, 1f)
            val scale = (grow * sink).toDouble() * Bloodwave.crest(e.power) / PROFILE_TOP
            if (scale <= 0.01) return
            val radius = Bloodwave.radius(min(age.toDouble(), end.toDouble())) + 0.2 * sin(d.time * 0.3)
            val n = rays.size
            // The wave stops where its ray leaves the band round the caster height (a cliff or a drop).
            val keep = Array(n + 1) { i ->
                val lane = rays[i % n]
                DoubleArray(PROFILE.size) { j -> if (lane.end.isInfinite()) 1.0 else SpellGeom.smooth(lane.end - max(0.0, radius + PROFILE[j].first) + 0.5) }
            }
            val grid = Array(n + 1) { i ->
                val lane = rays[i % n]
                Array(PROFILE.size) { j ->
                    val (back, h) = PROFILE[j]
                    val roll = 0.08 * sin(d.time * 0.4 + i * 0.45)
                    val s = min(max(0.0, radius + back), lane.end)
                    d.local(lane.at(s, 0.0).add(0.0, h * scale * (1 + roll) * keep[i][j], 0.0))
                }
            }
            val cam = d.view.cam
            val wave = Wave(
                grid, keep, BooleanArray(n) { i -> rays[i].dir.dot(cam.subtract(grid[i][3])) > 0 },
                List(n + 1) { grid[it][3] }, FloatArray(n + 1) { keep[it][3].toFloat() }, sink,
            )
            if (order.size < n * SLOTS) order = LongArray(n * SLOTS)
            // Draw the far half first, so that the near body covers the far glow.
            drawHalf(d, wave, near = false)
            drawHalf(d, wave, near = true)
        }

        private class Wave(
            val grid: Array<Array<Vec3>>, val keep: Array<DoubleArray>, val front: BooleanArray,
            val crestLine: List<Vec3>, val crestKeep: FloatArray, val sink: Float,
        )

        private fun drawHalf(d: SignatureDraw, wave: Wave, near: Boolean) {
            val grid = wave.grid; val keep = wave.keep; val front = wave.front; val sink = wave.sink
            val n = front.size
            val cam = d.view.cam
            val a = 0.82f * sink
            // Draw only faces turned to the eye, so the far side never shows through. The wave is a
            // height field, so each face normal points up.
            var count = 0
            for (i in 0 until n) {
                if (front[i] != near) continue
                for (j in 0 until PROFILE.size - 1) {
                    if (maxOf(keep[i][j], keep[i + 1][j], keep[i + 1][j + 1], keep[i][j + 1]) < 0.01) continue
                    val p0 = grid[i][j]; val p1 = grid[i + 1][j]; val p2 = grid[i + 1][j + 1]; val p3 = grid[i][j + 1]
                    var normal = p2.subtract(p0).cross(p3.subtract(p1))
                    if (normal.y < 0) normal = normal.scale(-1.0)
                    if (normal.dot(cam.subtract(p0.add(p2).scale(0.5))) <= 0) continue
                    order[count++] = sortKey(cam, (p0.x + p2.x) / 2, (p0.y + p2.y) / 2, (p0.z + p2.z) / 2, i * SLOTS + j)
                }
                if (i % 2 == 0) order[count++] = sortKey(cam, grid[i][3].x, grid[i][3].y + 0.35, grid[i][3].z, i * SLOTS + MIST)
            }
            Arrays.sort(order, 0, count)
            val shade = softDark(d)
            for (k in count - 1 downTo 0) {
                val slot = order[k].toInt()
                val i = slot / SLOTS; val j = slot % SLOTS
                if (j == MIST) {
                    SpellShapes.puff(shade, d.m, d.view, grid[i][3].add(0.0, 0.35, 0.0), 0.6, 0x4A2468, 0.3f * sink * keep[i][3].toFloat())
                    continue
                }
                val rgb = if (j >= 3) 0x6A0A18 else 0x3A0510
                SpellShapes.quad(shade, d.m, grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1], rgb, a * keep[i][j].toFloat(), a * keep[i + 1][j].toFloat(), a * keep[i + 1][j + 1].toFloat(), a * keep[i][j + 1].toFloat())
            }
            val glow = soft(d)
            var from = 0
            while (from < n) {
                if (front[from] != near) { from++; continue }
                var to = from
                while (to < n && front[to] == near) to++
                SpellShapes.strip(glow, d.m, d.view, wave.crestLine.subList(from, to + 1), { 0.35 }, BLOOD, 0.22f * sink, soft = true, alphas = wave.crestKeep.copyOfRange(from, to + 1))
                from = to
            }
            for (i in 0 until n step 3) if (front[i] == near) SpellShapes.puff(glow, d.m, d.view, grid[i][4].add(0.0, 0.5, 0.0), 0.7, VOID, 0.1f * sink * keep[i][4].toFloat())
        }
    }

    private val TENTACLE_TEX: ResourceLocation = EnderkinesisMod.id("textures/environment/ygann_abyss/writhing_tentacle.png")
    private val TENTACLE_GLOW_TEX: ResourceLocation = EnderkinesisMod.id("textures/environment/ygann_abyss/writhing_tentacle_emissive.png")
    /** The model length, in blocks. */
    private const val TENTACLE_HEIGHT = 90.0 / 16.0
    private const val TENTACLE_THICKNESS = 0.8f
    private var tentacleModel: TentacleModel? = null
    private val animVec = Vector3f()

    private class Tentacle(val base: Vec3, val span: Vec3, val roll: Float, val ms: Long, val alpha: Float)

    private fun drawTentacles(d: SignatureDraw, tentacles: List<Tentacle>) {
        if (tentacles.isEmpty()) return
        val model = tentacleModel ?: TentacleModel(TentacleModel.createMesh().bakeRoot()).also { tentacleModel = it }
        for (tips in listOf(false, true)) {
            val c = d.buffers.getBuffer(if (tips) RenderType.entityTranslucentEmissive(TENTACLE_GLOW_TEX) else RenderType.entityTranslucent(TENTACLE_TEX))
            for (t in tentacles) {
                val length = t.span.length()
                if (length < 0.02 || t.alpha < 0.01f) continue
                val dir = t.span.normalize()
                model.resetPose()
                KeyframeAnimations.animate(model, TentacleAnimation.IDLE_WAVE, t.ms, 1f, animVec)
                val at = d.local(t.base)
                d.pose.pushPose()
                d.pose.translate(at.x, at.y, at.z)
                d.pose.mulPose(Quaternionf().rotationTo(0f, 1f, 0f, dir.x.toFloat(), dir.y.toFloat(), dir.z.toFloat()))
                d.pose.mulPose(Quaternionf().rotationY(t.roll))
                val s = (length / TENTACLE_HEIGHT).toFloat()
                d.pose.scale(minOf(s, TENTACLE_THICKNESS), s, minOf(s, TENTACLE_THICKNESS))
                // Entity model framing: flip, then move down by the model's 24-pixel ground offset.
                d.pose.scale(-1f, -1f, 1f)
                d.pose.translate(0f, -1.501f, 0f)
                if (tips) model.renderTip(d.pose, c, SpellShapes.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, t.alpha)
                else model.renderToBuffer(d.pose, c, d.lightAt(t.base.add(t.span.scale(0.5)).add(0.0, 0.5, 0.0)), OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, t.alpha)
                d.pose.popPose()
            }
        }
    }

    private class Grasp(val look: LaneLook) {
        val seized = HashMap<Int, Int>()
        /** Keyed by rift phase. */
        private val midair = HashMap<Float, Boolean>()

        fun midair(rift: WaveSignatures.Rift): Boolean = midair.getOrPut(rift.phase) {
            val p = look.on(rift.along, rift.across, 0.0)
            WaveSignatures.surfaceY(look.level, p.x, p.z, p.y + 0.25, 0.75) == null
        }
    }

    object YgannsGraspVisual : SignatureVisual {
        private const val TENTACLE_LENGTH = 3.4
        private const val RIFT_SECTORS = 9
        private const val RIFT_SIZE = 1.5

        private fun grasp(e: SignatureEntity): Grasp =
            e.clientState as? Grasp ?: Grasp(LaneLook(WaveSignatures.lane(e.level(), e, YgannsGrasp.front(YgannsGrasp.END) + 2), e.level())).also { e.clientState = it }

        private fun opening(a: Float): Double = SpellGeom.easeOut(minOf(1.0, a / 4.0)) * (1 - SpellGeom.smooth((a - 22) / 8.0))

        /** Where the front is now, on the lane's middle line. */
        fun front(e: SignatureEntity): Vec3 = grasp(e).look.lane.at(YgannsGrasp.front(e.tickCount), 0.0)

        override fun tick(e: SignatureEntity) {
            val g = grasp(e)
            val level = e.level()
            val r = level.random
            val t = e.tickCount
            val lane = g.look.lane
            // Use the server reach test, so that tentacles wind round the same targets.
            if (t <= YgannsGrasp.END && lane.reaches(YgannsGrasp.front(t))) {
                val d = YgannsGrasp.front(t)
                val box = AABB(lane.at(d, -YgannsGrasp.HALF_WIDTH), lane.at(d, YgannsGrasp.HALF_WIDTH)).inflate(YgannsGrasp.BAND + 0.5, 4.0, YgannsGrasp.BAND + 0.5)
                for (target in level.getEntitiesOfClass(LivingEntity::class.java, box)) {
                    if (target === e.owner() || target.id in g.seized) continue
                    if (YgannsGrasp.reaches(lane, target.position(), t)) g.seized[target.id] = t
                }
                repeat(5) {
                    val p = g.look.on(d, (r.nextDouble() * 2 - 1) * YgannsGrasp.HALF_WIDTH, 0.1)
                    level.addParticle(if (r.nextBoolean()) ParticleTypes.PORTAL else ParticleTypes.REVERSE_PORTAL, p.x, p.y, p.z, 0.0, 0.08, 0.0)
                }
                for (rift in YgannsGrasp.rifts(e.seed, t)) {
                    val p = g.look.on(rift.along, rift.across, 0.2)
                    repeat(6) { level.addParticle(ParticleTypes.SQUID_INK, p.x, p.y, p.z, r.nextGaussian() * 0.12, 0.15 + r.nextDouble() * 0.15, r.nextGaussian() * 0.12) }
                }
            }
            g.seized.entries.removeIf { t - it.value > YgannsGrasp.SEIZE_TICKS }
            for (k in (t - YgannsGrasp.RIFT_LIFE)..t) for (rift in YgannsGrasp.rifts(e.seed, k)) {
                if (!lane.reaches(rift.along)) continue
                val a = t - k
                if (a < 5 || a > 22 || r.nextFloat() > 0.5f) continue
                val p = g.look.on(rift.along, rift.across, 0.6 + r.nextDouble() * 2.2)
                level.addParticle(if (r.nextBoolean()) ParticleTypes.SQUID_INK else dust(0.3f, 0.08f, 0.42f), p.x + r.nextGaussian() * 0.2, p.y, p.z + r.nextGaussian() * 0.2, 0.0, -0.05, 0.0)
            }
            for (id in g.seized.keys) {
                val target = level.getEntity(id) ?: continue
                if (r.nextInt(2) == 0) level.addParticle(dust(0.3f, 0.08f, 0.42f), target.x + r.nextGaussian() * 0.3, target.y + r.nextDouble() * target.bbHeight, target.z + r.nextGaussian() * 0.3, 0.0, -0.03, 0.0)
                if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.REVERSE_PORTAL, target.x + r.nextGaussian() * 0.3, target.y + 0.1, target.z + r.nextGaussian() * 0.3, 0.0, 0.05, 0.0)
            }
        }

        override fun render(d: SignatureDraw) {
            val e = d.e
            val g = grasp(e)
            val age = d.age
            val now = floor(age).toInt()
            val tentacles = ArrayList<Tentacle>()
            val rifts = ArrayList<Pair<WaveSignatures.Rift, Float>>()
            // Only rifts short of where the lane left the band round the caster's height.
            for (k in max(0, now - YgannsGrasp.RIFT_LIFE)..now) for (rift in YgannsGrasp.rifts(e.seed, k)) if (g.look.lane.reaches(rift.along)) rifts += rift to (age - k)
            drawRiftHoles(d, g, rifts)
            drawRiftEdges(d, g, rifts)
            drawRiftHaze(d, g, rifts)
            addRiftTentacles(g, rifts, age, tentacles)
            addSeizedTentacles(d, g, age, tentacles)
            drawTentacles(d, tentacles)
        }

        private fun rim(d: SignatureDraw, centre: Vec3, radii: DoubleArray, open: Double, k: Int, f: Double): Vec3 {
            val an = k * 2 * Math.PI / RIFT_SECTORS
            val rr = radii[k % RIFT_SECTORS] * open * f * RIFT_SIZE
            return d.local(centre.add(cos(an) * rr * 1.3, 0.0, sin(an) * rr))
        }

        private fun radii(rift: WaveSignatures.Rift): DoubleArray { val r = Random(rift.phase.toInt()); return DoubleArray(RIFT_SECTORS) { 0.45 + 0.35 * r.nextDouble() } }

        private fun drawRiftHoles(d: SignatureDraw, g: Grasp, rifts: List<Pair<WaveSignatures.Rift, Float>>) {
            val shade = d.shade()
            for ((rift, a) in rifts) {
                if (a < 0 || a > YgannsGrasp.RIFT_LIFE) continue
                val open = opening(a)
                if (open <= 0.01) continue
                val centre = g.look.on(rift.along, rift.across, 0.03)
                val radii = radii(rift)
                val c0 = d.local(centre)
                for (k in 0 until RIFT_SECTORS) SpellShapes.quad(shade, d.m, c0, c0, rim(d, centre, radii, open, k + 1, 1.0), rim(d, centre, radii, open, k, 1.0), 0x07020C, 0.95f, 0.95f, 0.95f, 0.95f)
            }
        }

        private fun drawRiftEdges(d: SignatureDraw, g: Grasp, rifts: List<Pair<WaveSignatures.Rift, Float>>) {
            val glow = d.glow()
            for ((rift, a) in rifts) {
                if (a < 0 || a > YgannsGrasp.RIFT_LIFE) continue
                val open = opening(a)
                if (open <= 0.01) continue
                val centre = g.look.on(rift.along, rift.across, 0.035)
                val radii = radii(rift)
                for (k in 0 until RIFT_SECTORS) {
                    SpellShapes.quad(glow, d.m, rim(d, centre, radii, open, k, 0.85), rim(d, centre, radii, open, k + 1, 0.85), rim(d, centre, radii, open, k + 1, 1.35), rim(d, centre, radii, open, k, 1.35), VOID, 0.8f, 0.8f, 0f, 0f)
                }
            }
        }

        /** A mid-air rift gets haze out of both faces. */
        private fun drawRiftHaze(d: SignatureDraw, g: Grasp, rifts: List<Pair<WaveSignatures.Rift, Float>>) {
            val haze = soft(d)
            for ((rift, a) in rifts) {
                if (a < 0 || a > YgannsGrasp.RIFT_LIFE) continue
                val open = opening(a)
                if (open <= 0.01) continue
                for (side in if (g.midair(rift)) doubleArrayOf(1.0, -1.0) else doubleArrayOf(1.0)) {
                    val centre = g.look.on(rift.along, rift.across, 0.3 * side)
                    SpellShapes.puff(haze, d.m, d.view, d.local(centre), 1.1 * open, VOID, (0.35 * open).toFloat())
                    SpellShapes.puff(haze, d.m, d.view, d.local(centre.add(0.0, 0.9 * side, 0.0)), 0.7 * open, 0x6A2A9A, (0.2 * open).toFloat())
                }
            }
        }

        private fun addRiftTentacles(g: Grasp, rifts: List<Pair<WaveSignatures.Rift, Float>>, age: Float, tentacles: MutableList<Tentacle>) {
            for ((rift, a) in rifts) {
                if (a < 0 || a > YgannsGrasp.RIFT_LIFE) continue
                val rise = if (a < 5) SpellGeom.easeOut(a / 5.0) else if (a > 20) max(0.0, 1 - (a - 20) / 10.0) else 1.0
                if (rise <= 0.01) continue
                val tilt = 0.3
                val dir = Vec3(sin(tilt) * cos(rift.phase.toDouble()), cos(tilt), sin(tilt) * sin(rift.phase.toDouble()))
                val base = g.look.on(rift.along, rift.across, -0.3 - (1 - rise) * 0.8)
                tentacles += Tentacle(base, dir.scale(TENTACLE_LENGTH * rise + 0.6), rift.phase, (age * 60 + rift.phase).toLong(), 1f)
                // A mid-air rift also sends a second tentacle down out of its underside.
                if (g.midair(rift)) {
                    val below = g.look.on(rift.along, rift.across, 0.3 + (1 - rise) * 0.8)
                    val down = Vec3(dir.x, -dir.y, dir.z)
                    tentacles += Tentacle(below, down.scale(TENTACLE_LENGTH * rise + 0.6), rift.phase + 1.7f, (age * 60 + rift.phase + 500).toLong(), 1f)
                }
            }
        }

        private fun addSeizedTentacles(d: SignatureDraw, g: Grasp, age: Float, tentacles: MutableList<Tentacle>) {
            for ((id, at) in g.seized) {
                val target = d.level.getEntity(id) ?: continue
                val a = age - at
                val rise = SpellGeom.easeOut(minOf(1.0, a / 4.0)) * (1 - SpellGeom.smooth((a - YgannsGrasp.SEIZE_TICKS + 6) / 6.0))
                if (rise <= 0.01) continue
                val feet = target.getPosition(d.partialTick)
                val reach = target.bbHeight + 0.9
                for (k in 0 until 3) {
                    val an = id * 1.7 + k * 2 * Math.PI / 3
                    val out = Vec3(cos(an), 0.0, sin(an)).scale(target.bbWidth * 0.5 + 0.3)
                    val base = feet.add(out).add(0.0, -0.4, 0.0)
                    val span = out.scale(-0.6).add(0.0, reach, 0.0).scale(rise)
                    tentacles += Tentacle(base, span, (an * 3).toFloat(), (age * 70 + id * 97 + k * 500).toLong(), 1f)
                }
            }
        }
    }

    /** [held] maps entity id to (distance where caught, tick when caught). */
    private class FireLook(val vines: List<WaveSignatures.Vine>) {
        val held = HashMap<Int, Pair<Double, Int>>()
    }

    object FireweedVisual : SignatureVisual {
        private const val END_FADE = 14f
        private const val FLAME_HEIGHT = 1.2
        private const val ERUPT_TICKS = 10f
        private const val STRANDS = 3
        private const val TURNS = 2.2
        private const val WIND_TICKS = 6f

        private fun look(e: SignatureEntity): FireLook =
            e.clientState as? FireLook ?: FireLook(Fireweed.vines(e.level(), e.position(), e.seed)).also { e.clientState = it }

        override fun tick(e: SignatureEntity) {
            val look = look(e)
            val vines = look.vines
            val level = e.level()
            val r = level.random
            val t = e.tickCount
            // Use the server catch test, so that vines wind round the same targets.
            if (t < Fireweed.IGNITE) {
                val box = AABB(e.position(), e.position()).inflate(Fireweed.REACH + 1, 4.0, Fireweed.REACH + 1)
                for (target in level.getEntitiesOfClass(LivingEntity::class.java, box)) {
                    if (target === e.owner() || target.id in look.held || !target.onGround()) continue
                    val dist = Fireweed.tangles(vines, target.position(), t) ?: continue
                    look.held[target.id] = dist to t
                    repeat(8) { level.addParticle(BlockParticleOption(ParticleTypes.BLOCK, Blocks.MANGROVE_ROOTS.defaultBlockState()), target.x, target.y + 0.3, target.z, 0.0, 0.0, 0.0) }
                }
            }
            look.held.entries.removeIf { !Fireweed.tangled(it.value.first, t.toDouble()) || level.getEntity(it.key) == null }
            if (e.tickCount == Fireweed.IGNITE) {
                repeat(50) {
                    val an = r.nextDouble() * 2 * Math.PI; val sp = 0.15 + r.nextDouble() * 0.25
                    level.addParticle(ParticleTypes.FLAME, e.x, e.y + 0.2, e.z, cos(an) * sp, 0.05 + r.nextDouble() * 0.2, sin(an) * sp)
                }
                repeat(12) { level.addParticle(ParticleTypes.LAVA, e.x, e.y + 0.2, e.z, 0.0, 0.0, 0.0) }
                repeat(10) { level.addParticle(ParticleTypes.LARGE_SMOKE, e.x + r.nextGaussian(), e.y + 0.5, e.z + r.nextGaussian(), 0.0, 0.08, 0.0) }
            }
            val front = Fireweed.fire(e.tickCount.toDouble())
            if (front < 0 || front - Fireweed.FIRE_TAIL > Fireweed.REACH || vines.isEmpty()) return
            repeat(7) {
                val vine = vines[r.nextInt(vines.size)]
                val i = vine.dists.indexOfFirst { it > front }.let { if (it < 0) vine.points.size - 1 else it - 1 }
                if (i < 0) return@repeat
                val p = vine.points[i]
                level.addParticle(ParticleTypes.FLAME, p.x, p.y + 0.15, p.z, 0.0, 0.06, 0.0)
                if (r.nextInt(2) == 0) level.addParticle(ParticleTypes.SMOKE, p.x, p.y + 0.6, p.z, 0.0, 0.05, 0.0)
                if (r.nextInt(4) == 0) level.addParticle(ParticleTypes.LAVA, p.x, p.y + 0.1, p.z, 0.0, 0.0, 0.0)
            }
            repeat(2) {
                val vine = vines[r.nextInt(vines.size)]
                val burnt = vine.points.indices.filter { vine.dists[it] < front - 1 }
                if (burnt.isNotEmpty()) {
                    val p = vine.points[burnt[r.nextInt(burnt.size)]]
                    level.addParticle(if (r.nextBoolean()) ParticleTypes.WHITE_ASH else ParticleTypes.LARGE_SMOKE, p.x, p.y + 0.2, p.z, 0.0, 0.03, 0.0)
                }
            }
        }

        override fun render(d: SignatureDraw) {
            val look = look(d.e)
            val age = d.age
            val out = fade(d, 1f, END_FADE)
            val grow = min(age * Fireweed.GROW_SPEED.toFloat(), Fireweed.REACH.toFloat()).toDouble()
            val front = Fireweed.fire(age.toDouble())
            val embers = ArrayList<Pair<Vec3, Double>>()
            d.blocks()?.let { c ->
                val mangrove = SpellArt.block("mangrove_roots_side"); val vineTex = SpellArt.block("vine"); val fire = SpellArt.block("fire_0")
                for (vine in look.vines) drawVine(d, c, vine, grow, front, out, mangrove, vineTex, fire, embers)
                drawHeld(d, c, look, age, front, out, mangrove, vineTex)
            }
            val glow = soft(d)
            for ((p, w) in embers) SpellShapes.puff(glow, d.m, d.view, d.local(p.add(0.0, 0.35, 0.0)), 0.8 * w, EMBER, 0.28f * out)
            val erupt = age - Fireweed.IGNITE
            if (erupt in 0f..ERUPT_TICKS) {
                val f = erupt / ERUPT_TICKS
                val centre = d.local(d.e.position().add(0.0, 0.4, 0.0))
                SpellShapes.puff(glow, d.m, d.view, centre, 1.0 + Fireweed.ERUPT_R * f, EMBER, 0.6f * (1 - f))
                SpellShapes.puff(glow, d.m, d.view, centre, 0.6 + 1.5 * f, 0xFFE0A0, 0.7f * (1 - f) * (1 - f))
            }
        }

        private fun drawVine(
            d: SignatureDraw, c: VertexConsumer, vine: WaveSignatures.Vine, grow: Double, front: Double, out: Float,
            mangrove: Sprite, vineTex: Sprite, fire: Sprite, embers: MutableList<Pair<Vec3, Double>>,
        ) {
            var n = 0
            while (n < vine.points.size && vine.dists[n] <= grow) n++
            if (n < 2) return
            val side = Vec3(-sin(vine.a0), 0.0, cos(vine.a0))
            val w0 = if (vine.root) 0.3 else 0.24
            val last = max(1.0, vine.dists.last())
            val edges = List(n) { i -> side.scale((w0 + (0.08 - w0) * vine.dists[i] / last) / 2) }
            val pts = vine.points.subList(0, n).map(d::local)
            val world = vine.points.subList(0, n)
            var b = 0
            while (b < n && vine.dists[b] <= front - 0.6) b++
            val sprite = if (vine.root) mangrove else vineTex
            if (b < n) {
                val from = max(0, b - 1)
                litStrip(d, c, world.subList(from, n), edges.subList(from, n), sprite, if (vine.root) 0xFFFFFF else VINE_GREEN, out, vine.uc)
            }
            if (b >= 2) {
                val ash = world.subList(0, b).map { it.add(0.0, 0.006, 0.0) }
                litStrip(d, c, ash, edges.subList(0, b), sprite, ASH, out, vine.uc)
            }
            // Fire gives its own light, so the flames use FULL_BRIGHT.
            for (i in 3 until n step 2) {
                val behind = front - vine.dists[i]
                if (behind < 0 || behind > Fireweed.FIRE_TAIL) continue
                val h = FLAME_HEIGHT * (1 - behind / Fireweed.FIRE_TAIL) * minOf(1.0, (behind + 0.2) / 0.6)
                plant(c, d.m, pts[i], fire, h, 0xFFFFFF, out, SpellShapes.FULL_BRIGHT, i * 0.7, 0.6)
            }
            if (b in 1 until n && front - vine.dists[b] < Fireweed.FIRE_TAIL) embers += vine.points[b] to 1.0
        }

        private fun drawHeld(d: SignatureDraw, c: VertexConsumer, look: FireLook, age: Float, front: Double, out: Float, mangrove: Sprite, vineTex: Sprite) {
            for ((id, caught) in look.held) {
                val target = d.level.getEntity(id) ?: continue
                val (dist, at) = caught
                val wind = SpellGeom.smooth((age - at) / WIND_TICKS).toDouble()
                if (wind <= 0.01) continue
                val feet = target.getPosition(d.partialTick)
                val radius = target.bbWidth * 0.5 + 0.08
                val height = min(target.bbHeight.toDouble(), 1.8) * 0.8 * wind
                val burnt = front >= dist
                for (k in 0 until STRANDS) {
                    val steps = 24
                    val pts = List(steps + 1) { i ->
                        val u = i.toDouble() / steps
                        val an = id * 0.7 + k * 2 * Math.PI / STRANDS + u * TURNS * 2 * Math.PI
                        feet.add(cos(an) * radius, u * height, sin(an) * radius)
                    }
                    val edges = List(steps + 1) { Vec3(0.0, 0.09, 0.0) }
                    val root = k == 0
                    val rgb = if (burnt) ASH else if (root) 0xFFFFFF else VINE_GREEN
                    litStrip(d, c, pts, edges, if (root) mangrove else vineTex, rgb, out, 0.3 + k * 0.2, 0.0)
                }
            }
        }
    }

    private class Mycelium {
        var bloom: WaveSignatures.Bloom? = null
        var landed = false
        val burst = IntArray(Shroomspread.CAPS) { -1 }
    }

    object ShroomspreadVisual : SignatureVisual {
        private const val END_FADE = 15f
        private const val BALL_BITS = 7
        private val CLOUD_LIFE = Shroomspread.LINGER.toFloat()

        private fun mycelium(e: SignatureEntity): Mycelium = e.clientState as? Mycelium ?: Mycelium().also { e.clientState = it }

        private fun bloomOf(e: SignatureEntity, my: Mycelium): WaveSignatures.Bloom =
            my.bloom ?: Shroomspread.bloom(e.level(), e.point, e.seed, e.power).also { my.bloom = it }

        override fun tick(e: SignatureEntity) {
            val my = mycelium(e)
            val level = e.level()
            val r = level.random
            val t = Shroomspread.since(e)
            if (t < 0) {
                repeat(4) {
                    val kind = when (r.nextInt(4)) { 0 -> ParticleTypes.MYCELIUM; 1 -> dust(0.45f, 0.35f, 0.25f, 1.3f); else -> dust(0.58f, 0.76f, 0.35f, 1.5f) }
                    level.addParticle(kind, e.x + r.nextGaussian() * 0.15, e.y + r.nextGaussian() * 0.15, e.z + r.nextGaussian() * 0.15, 0.0, 0.0, 0.0)
                }
                if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.SPORE_BLOSSOM_AIR, e.x, e.y, e.z, 0.0, 0.0, 0.0)
                return
            }
            if (!my.landed) {
                my.landed = true
                e.deltaMovement = Vec3.ZERO
                e.setPos(e.point)
                val p = e.point
                repeat(70) {
                    val kind = when (r.nextInt(3)) { 0 -> dust(0.58f, 0.76f, 0.35f, 2f); 1 -> dust(0.45f, 0.35f, 0.25f, 1.6f); else -> ParticleTypes.SNEEZE }
                    level.addParticle(kind, p.x, p.y + 0.4, p.z, r.nextGaussian() * 0.2, 0.05 + r.nextDouble() * 0.2, r.nextGaussian() * 0.2)
                }
                repeat(16) { level.addParticle(BlockParticleOption(ParticleTypes.BLOCK, Blocks.BROWN_MUSHROOM_BLOCK.defaultBlockState()), p.x, p.y + 0.3, p.z, 0.0, 0.0, 0.0) }
            }
            val bloom = bloomOf(e, my)
            for (tile in bloom.tiles) if (tile.at == t) repeat(3) {
                level.addParticle(ParticleTypes.MYCELIUM, tile.x + r.nextDouble(), tile.y + 0.1, tile.z + r.nextDouble(), 0.0, 0.02, 0.0)
            }
            for (s in bloom.shrooms) if (s.at == t) repeat(10) {
                level.addParticle(ParticleTypes.MYCELIUM, s.x + 0.5 + r.nextGaussian() * 0.6, s.y + 0.2 + r.nextDouble() * 5, s.z + 0.5 + r.nextGaussian() * 0.6, 0.0, 0.03, 0.0)
            }
            for ((i, cap) in bloom.caps.withIndex()) {
                if (my.burst[i] >= 0 || e.data and (1 shl i) == 0) continue
                my.burst[i] = t
                val size = Shroomspread.capSize(t - cap.born)
                val x = cap.x + 0.5; val y = cap.y + size / 2; val z = cap.z + 0.5
                repeat(70) {
                    val kind = when (r.nextInt(3)) { 0 -> dust(0.58f, 0.76f, 0.35f, 2.2f); 1 -> dust(0.45f, 0.35f, 0.25f, 1.6f); else -> ParticleTypes.SNEEZE }
                    level.addParticle(kind, x, y, z, r.nextGaussian() * 0.22, 0.05 + r.nextDouble() * 0.2, r.nextGaussian() * 0.22)
                }
                repeat(16) { level.addParticle(BlockParticleOption(ParticleTypes.BLOCK, Blocks.BROWN_MUSHROOM_BLOCK.defaultBlockState()), x, y, z, 0.0, 0.0, 0.0) }
            }
            for ((i, cap) in bloom.caps.withIndex()) {
                val at = my.burst[i]
                if (at < 0 || t - at > CLOUD_LIFE) continue
                repeat(2) { level.addParticle(dust(0.58f, 0.76f, 0.35f, 1.6f), cap.x + 0.5 + r.nextGaussian() * 1.4, cap.y + 0.3 + r.nextDouble() * 1.6, cap.z + 0.5 + r.nextGaussian() * 1.4, 0.0, 0.01, 0.0) }
            }
            if (e.tickCount < e.life - 10) {
                val shown = bloom.tiles.filter { it.at <= t }
                if (shown.isNotEmpty()) repeat(4) {
                    val tile = shown[r.nextInt(shown.size)]
                    level.addParticle(ParticleTypes.MYCELIUM, tile.x + r.nextDouble(), tile.y + 0.1, tile.z + r.nextDouble(), 0.0, 0.0, 0.0)
                }
            }
        }

        override fun render(d: SignatureDraw) {
            val e = d.e
            val my = mycelium(e)
            val age = d.age - Shroomspread.flight(e)
            if (age < 0 || !my.landed) { ball(d); return }
            val bloom = my.bloom ?: return
            val out = fade(d, 1f, END_FADE)
            val sink = SpellGeom.smooth((e.life - d.age) / END_FADE).toDouble()
            d.blocks()?.let { c ->
                val top = SpellArt.block("mycelium_top")
                for (tile in bloom.tiles) {
                    if (age < tile.at) continue
                    if (d.level.getBlockState(Shroomspread.groundPos(tile.x, tile.z, tile.y)).`is`(Blocks.MYCELIUM)) continue
                    topFace(d, c, tile.x, tile.z, tile.y + 0.012, top, 0xFFFFFF, out * minOf(1f, (age - tile.at) / 3f))
                }
            }
            d.solidBlocks()?.let { c ->
                val brown = SpellArt.block("brown_mushroom_block")
                for ((i, cap) in bloom.caps.withIndex()) {
                    if (age < cap.born) continue
                    val burst = my.burst[i]
                    if (burst >= 0 && age >= burst) continue
                    if (burst < 0 && age >= cap.pop + 4) continue
                    val size = Shroomspread.capSize(floor(age).toInt() - cap.born) * sink
                    if (size <= 0.02) continue
                    val p = Vec3(cap.x + 0.5, cap.y + size / 2, cap.z + 0.5)
                    SpellShapes.texBox(c, d.m, SpellGeom.cubeCorners(d.local(p), size / 2, 0.0, 0.0), brown, 0xFFFFFF, 1f, d.lightAt(Vec3(p.x, cap.y + size + 0.3, p.z)), i)
                }
            }
            val cloud = softDark(d)
            for ((i, cap) in bloom.caps.withIndex()) {
                val burst = my.burst[i]
                if (burst < 0) continue
                val a = age - burst
                if (a < 0 || a > CLOUD_LIFE) continue
                val f = a / CLOUD_LIFE
                val alpha = 0.4f * (1 - f) * minOf(1f, a / 2f)
                for (k in 0 until 3) {
                    val an = k * 2.1 + i
                    val p = Vec3(cap.x + 0.5 + cos(an) * 0.8 * f, cap.y + 0.6 + 0.5 * k * f, cap.z + 0.5 + sin(an) * 0.8 * f)
                    SpellShapes.puff(cloud, d.m, d.view, d.local(p), 1.0 + 1.6 * SpellGeom.easeOut(minOf(1.0, a / 12.0)), SPORE_GREEN, alpha)
                }
            }
            if (age < CLOUD_LIFE / 2) {
                val f = age / (CLOUD_LIFE / 2)
                SpellShapes.puff(cloud, d.m, d.view, d.local(e.point.add(0.0, 0.6, 0.0)), 1.0 + 2.0 * SpellGeom.easeOut(f.toDouble()), SPORE_GREEN, 0.45f * (1 - f))
            }
        }

        private fun ball(d: SignatureDraw) {
            val e = d.e
            val light = d.lightAt(d.at)
            d.solidBlocks()?.let { c ->
                val brown = SpellArt.block("brown_mushroom_block"); val stem = SpellArt.block("mushroom_stem")
                val r = Random(e.seed)
                repeat(BALL_BITS) { i ->
                    val off = if (i == 0) Vec3.ZERO else SpellGeom.spread(r).scale(0.14)
                    val size = if (i == 0) 0.2 else rnd(r, 0.08, 0.14)
                    val spin = rnd(r, 0.2, 0.45)
                    val corners = SpellGeom.cubeCorners(off, size, d.age * spin + i, d.age * spin * 0.7, d.age * spin * 0.4)
                    SpellShapes.texBox(c, d.m, corners, if (i % 3 == 2) stem else brown, 0xFFFFFF, 1f, light, i)
                }
            }
            SpellShapes.puff(softDark(d), d.m, d.view, Vec3.ZERO, 0.45, SPORE_GREEN, 0.4f)
        }
    }

    private class ClientBloom(val at: Vec3, val born: Float, val plants: List<Pair<Vec3, Boolean>>, val tiles: List<BlockPos>)

    private class Garden {
        val blooms = ArrayList<ClientBloom>()
        var planted = 0
    }

    object GardenSanguineVisual : SignatureVisual {
        private const val PETALS = 18
        private const val PLANTS = 7
        private const val PETAL_RED = 0xB0101E

        private fun garden(e: SignatureEntity): Garden = e.clientState as? Garden ?: Garden().also { e.clientState = it }

        private fun sprays(e: SignatureEntity, partialTick: Float): Pair<Vec3, List<Vec3>>? {
            val owner = e.owner() ?: return null
            val hand = SignatureVisuals.hand(e, partialTick) ?: return null
            val aim = SpellCasting.handAim(owner, hand, partialTick)
            return hand to GardenSanguine.sprays(aim, (e.tickCount + partialTick).toDouble()).map { GardenSanguine.lands(e.level(), hand, it) }
        }

        override fun tick(e: SignatureEntity) {
            val h = garden(e)
            val level = e.level()
            val r = level.random
            val released = e.data and GardenSanguine.RELEASED != 0
            val ends = if (released) null else sprays(e, 0f)?.second
            // Plant each server bloom where the client spray lands.
            val count = e.data and (GardenSanguine.RELEASED - 1)
            while (h.planted < count) {
                h.planted++
                val landed = ends?.get((h.planted - 1) % GardenSanguine.SPRAYS) ?: e.point
                val ground = GardenSanguine.groundUnder(level, landed) ?: continue
                val pr = Random(e.seed * 7 + h.planted)
                val plants = List(PLANTS) {
                    val an = pr.nextDouble() * 2 * Math.PI; val rr = pr.nextDouble() * GardenSanguine.BLOOM_R * 0.8
                    val x = ground.x + cos(an) * rr; val z = ground.z + sin(an) * rr
                    val y = WaveSignatures.surfaceY(level, x, z, ground.y + 1.0, 3.0) ?: ground.y
                    Vec3(x, y, z) to pr.nextBoolean()
                }
                h.blooms += ClientBloom(ground, e.tickCount.toFloat(), plants, GardenSanguine.patch(level, ground))
                repeat(14) { level.addParticle(dust(0.7f, 0.05f, 0.1f, 1.6f), ground.x + r.nextGaussian() * 0.7, ground.y + 0.2, ground.z + r.nextGaussian() * 0.7, 0.0, 0.06, 0.0) }
            }
            h.blooms.removeIf { e.tickCount - it.born > GardenSanguine.BLOOM_LIFE }
            if (ends != null) for (p in ends) {
                level.addParticle(dust(0.6f, 0.04f, 0.08f, 1.5f), p.x + r.nextGaussian() * 0.4, p.y + 0.1, p.z + r.nextGaussian() * 0.4, 0.0, 0.05, 0.0)
            }
            for (b in h.blooms) if (r.nextInt(4) == 0) {
                val (p, _) = b.plants[r.nextInt(b.plants.size)]
                level.addParticle(dust(1f, 0.45f, 0.45f, 0.9f), p.x, p.y + 0.3, p.z, 0.0, 0.05, 0.0)
            }
            if (h.blooms.isNotEmpty() && e.tickCount % 2 == 0) {
                val owner = e.owner()
                for (b in h.blooms) {
                    val box = AABB(b.at, b.at).inflate(GardenSanguine.BLOOM_R + 0.5, 2.0, GardenSanguine.BLOOM_R + 0.5)
                    for (other in level.getEntitiesOfClass(LivingEntity::class.java, box)) {
                        if (other === owner || !other.isAlive || !GardenSanguine.touches(b.at, other.position())) continue
                        level.addParticle(dust(0.55f, 0.02f, 0.06f, 1.4f), other.x + r.nextGaussian() * 0.25, other.y + r.nextDouble() * other.bbHeight, other.z + r.nextGaussian() * 0.25, 0.0, -0.08, 0.0)
                        if (r.nextInt(4) == 0) level.addParticle(ParticleTypes.DAMAGE_INDICATOR, other.x, other.y + other.bbHeight * 0.5, other.z, 0.0, 0.0, 0.0)
                    }
                }
            }
        }

        override fun render(d: SignatureDraw) {
            val e = d.e
            val h = garden(e)
            val age = d.age
            val spray = if (e.data and GardenSanguine.RELEASED == 0) sprays(e, d.partialTick) else null
            d.blocks()?.let { c ->
                val roots = SpellArt.block("crimson_roots"); val fungus = SpellArt.block("crimson_fungus"); val nylium = SpellArt.block("crimson_nylium")
                for (b in h.blooms) {
                    val a = age - b.born
                    val life = (GardenSanguine.BLOOM_LIFE - a) / 20f
                    val grow = SpellGeom.easeOut(minOf(1.0, a / 6.0)) * SpellGeom.smooth(life).toDouble()
                    if (grow <= 0.01) continue
                    for (tile in b.tiles) {
                        if (d.level.getBlockState(tile).`is`(Blocks.CRIMSON_NYLIUM)) continue
                        topFace(d, c, tile.x, tile.z, tile.y + 1.01, nylium, 0xFFFFFF, SpellGeom.smooth(life) * minOf(1f, a / 4f))
                    }
                    for ((k, spot) in b.plants.withIndex()) {
                        val (p, isFungus) = spot
                        plant(c, d.m, d.local(p), if (isFungus) fungus else roots, grow, 0xFFFFFF, 1f, d.lightAt(p.add(0.0, 0.5, 0.0)), k * 1.3)
                    }
                }
                val (hand, ends) = spray ?: return@let
                val a = d.local(hand)
                val f = minOf(1f, age / 3f)
                for ((k, end) in ends.withIndex()) {
                    val b = d.local(end)
                    val mid = a.lerp(b, 0.5).add(0.0, 0.06 * a.distanceTo(b), 0.0)
                    val r = Random(e.seed + k * 977)
                    for (i in 0 until PETALS) {
                        val u = ((age * 0.08 + i.toDouble() / PETALS + r.nextDouble() * 0.05) % 1.0)
                        val jitter = Vec3(r.nextDouble() - 0.5, r.nextDouble() - 0.5, r.nextDouble() - 0.5).scale(0.6 * u)
                        val p = SpellGeom.bezier(a, mid, b, u).add(jitter)
                        SpellShapes.leafChip(c, d.m, d.view, p, 0.24, age * 0.2 + i, i + k * PETALS, PETAL_RED, f * minOf(1f, (1 - u.toFloat()) * 5f), d.lightAt(hand.lerp(end, u)))
                    }
                }
            }
            val (hand, ends) = spray ?: return
            val a = d.local(hand)
            val f = minOf(1f, age / 3f)
            val glow = soft(d)
            for (end in ends) {
                val b = d.local(end)
                val mid = a.lerp(b, 0.5).add(0.0, 0.06 * a.distanceTo(b), 0.0)
                SpellShapes.strip(glow, d.m, d.view, List(9) { SpellGeom.bezier(a, mid, b, it / 8.0) }, { 0.35 }, BLOOD, 0.2f * f, soft = true)
                SpellShapes.puff(glow, d.m, d.view, b, 0.8, BLOOD, 0.2f * f)
            }
        }
    }
}
