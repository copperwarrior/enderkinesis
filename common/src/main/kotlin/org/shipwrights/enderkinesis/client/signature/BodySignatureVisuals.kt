package org.shipwrights.enderkinesis.client.signature

import com.mojang.blaze3d.vertex.VertexConsumer
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.client.PocketPortalRenderType
import org.shipwrights.enderkinesis.client.SpellArt
import org.shipwrights.enderkinesis.client.SpellGeom
import org.shipwrights.enderkinesis.client.SpellShapes
import org.shipwrights.enderkinesis.client.Sprite
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.spell.Signature
import org.shipwrights.enderkinesis.util.CrossFrameBlocks
import org.shipwrights.enderkinesis.spell.signature.BodySignatures.Faespark
import org.shipwrights.enderkinesis.spell.signature.BodySignatures.Starfall
import org.shipwrights.enderkinesis.spell.signature.BodySignatures.HungeringMaw
import org.shipwrights.enderkinesis.spell.signature.BodySignatures.NorthStar
import org.shipwrights.enderkinesis.spell.signature.BodySignatures.Rotdance
import org.shipwrights.enderkinesis.spell.signature.BodySignatures.Supernova
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.shipwrights.enderkinesis.client.BLOCK_ATLAS

object BodySignatureVisuals {
    val ALL: Map<Signature, SignatureVisual> = mapOf(
        Signature.STARFALL to StarfallVisual,
        Signature.NORTH_STAR to NorthStarVisual,
        Signature.HUNGERING_MAW to HungeringMawVisual,
        Signature.FAESPARK to FaesparkVisual,
        Signature.ROTDANCE to RotdanceVisual,
        Signature.SUPERNOVA to SupernovaVisual,
    )

    private const val HEAT = 0xF08A3A
    private const val COLD = 0x5FB4DC
    private const val VOID = 0xB77FD4
    private const val CHAOS = 0xEA6A9A
    private const val POISON = 0x94C25A
    private const val SUNFIRE = 0xE6B53C

    /** Plain additive glow until the soft-glow shader loads. */
    private fun soft(d: SignatureDraw): VertexConsumer = d.softGlow() ?: d.glow()

    private fun bolt(d: SignatureDraw, random: Random, a: Vec3, b: Vec3, rgb: Int, alpha: Float, width: Double = 0.12) {
        val pts = SpellGeom.jagged(random, a, b, 0.12)
        val glow = d.glow()
        SpellShapes.strip(glow, d.m, d.view, pts, { width }, rgb, 0.55f * alpha, soft = true)
        SpellShapes.strip(glow, d.m, d.view, pts, { width * 0.3 }, 0xFFFFFF, 0.9f * alpha, soft = false)
    }

    /** Meteors the client saw hit the ground before the server reported it, so that they do not overshoot. */
    private class Sky { val gone = BooleanArray(Starfall.COUNT) }

    /** The impact fire, smoke and rock are server particles. */
    object StarfallVisual : SignatureVisual {
        private const val ROCK = 0.6
        private const val TAIL = 7.0

        private fun sky(e: SignatureEntity): Sky = e.clientState as? Sky ?: Sky().also { e.clientState = it }

        private fun falling(e: SignatureEntity, k: Int, t: Double): Boolean =
            t >= 0 && t <= Starfall.FALL + Starfall.OVERRUN && !Starfall.landed(e, k) && !sky(e).gone[k]

        override fun tick(e: SignatureEntity) {
            val level = e.level()
            val r = level.random
            val s = sky(e)
            for (k in 0 until Starfall.count(e.power)) {
                val t = (e.tickCount - Starfall.startOf(k)).toDouble()
                if (!falling(e, k, t)) continue
                val fall = Starfall.fall(e, k)
                val p = fall.at(t)
                val pos = BlockPos.containing(p)
                if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty) { s.gone[k] = true; continue }
                if (t == 0.0) level.playLocalSound(fall.mark.x, fall.mark.y, fall.mark.z, EKSounds.SIGNATURE_STARFALL_FALL.get(), SoundSource.PLAYERS, 4f, 1f, false)
                val back = fall.from.subtract(fall.mark).normalize()
                repeat(3) {
                    val q = p.add(back.scale(r.nextDouble() * 2.5))
                    level.addParticle(ParticleTypes.LARGE_SMOKE, q.x + r.nextGaussian() * 0.3, q.y + r.nextGaussian() * 0.3, q.z + r.nextGaussian() * 0.3, 0.0, 0.03, 0.0)
                }
                repeat(5) { level.addParticle(ParticleTypes.FLAME, p.x + r.nextGaussian() * 0.4, p.y + r.nextGaussian() * 0.4, p.z + r.nextGaussian() * 0.4, r.nextGaussian() * 0.04, 0.0, r.nextGaussian() * 0.04) }
                if (r.nextInt(2) == 0) level.addParticle(ParticleTypes.LAVA, p.x, p.y, p.z, 0.0, 0.0, 0.0)
            }
            val m = e.point
            if (e.tickCount % 2 == 0) {
                val a = r.nextDouble() * Math.PI * 2
                val rr = (Starfall.SCATTER + 1) * (0.6 + 0.4 * r.nextDouble())
                level.addParticle(ParticleTypes.FLAME, m.x + cos(a) * rr, m.y + 0.1, m.z + sin(a) * rr, 0.0, 0.04, 0.0)
            }
        }

        override fun render(d: SignatureDraw) {
            d.castFlash(d.e.point, HEAT)
            val falling = ArrayList<Pair<Vec3, Vec3>>()
            for (k in 0 until Starfall.count(d.e.power)) {
                val t = (d.age - Starfall.startOf(k)).toDouble()
                if (!falling(d.e, k, t)) continue
                val fall = Starfall.fall(d.e, k)
                falling += d.local(fall.at(t)) to fall.from.subtract(fall.mark).normalize()
            }
            val markAlpha = minOf(1f, d.age / 6f) * (if (falling.isEmpty() && d.age > Starfall.DELAY + 2) 0f else 1f)
            if (markAlpha > 0f) {
                val pulse = 0.75f + 0.25f * sin(d.time * 0.5f)
                val ground = d.local(d.e.point).add(0.0, 0.05, 0.0)
                SpellShapes.ring(soft(d), d.m, ground, Starfall.SCATTER + 1, 0.35, HEAT, 0.6f * pulse * markAlpha, 0.0)
                SpellShapes.ring(soft(d), d.m, ground, 0.9, 0.3, 0xFFB060, 0.7f * pulse * markAlpha, 0.0)
            }
            if (falling.isEmpty()) return
            d.solidBlocks()?.let { c ->
                for ((i, f) in falling.withIndex()) {
                    val spin = d.age * 0.3 + d.e.seed % 7 + i
                    SpellShapes.texBox(c, d.m, SpellGeom.cubeCorners(f.first, ROCK, spin, spin * 1.3, spin * 0.7), SpellArt.block("magma"), 0xFFFFFF, 1f, SpellShapes.FULL_BRIGHT, d.e.seed + i)
                }
            }
            val glow = soft(d)
            for ((p, back) in falling) {
                SpellShapes.strip(glow, d.m, d.view, listOf(p, p.add(back.scale(TAIL))), { i -> if (i == 0) 1.6 else 0.5 }, HEAT, 0.6f, soft = true, alphas = floatArrayOf(1f, 0f))
                SpellShapes.strip(glow, d.m, d.view, listOf(p, p.add(back.scale(TAIL * 0.5))), { i -> if (i == 0) 0.6 else 0.1 }, 0xFFE0A0, 0.7f, soft = true, alphas = floatArrayOf(1f, 0f))
                SpellShapes.sphere(glow, d.m, d.view, p, 1.0, 0xFFB060, 0.6f)
                SpellShapes.disc(glow, d.m, d.view, p, 2.8, HEAT, 0.6f)
            }
        }
    }

    private const val SHARDS = 48
    private const val RING_TILT = 0.45
    private const val RING_ROLL = 0.2
    /** Ticks that the client stop guess waits for the server before the client discards it. */
    private const val GUESS_TICKS = 6

    /** The stop point the client predicts, so that the bolt does not overshoot and snap back. */
    private class Frost { var stop: Vec3? = null; var stopAt = 0 }

    /** On a creature, the core uses the interpolated creature position, so that it moves smoothly with it. */
    object NorthStarVisual : SignatureVisual {
        private fun frost(e: SignatureEntity): Frost = e.clientState as? Frost ?: Frost().also { e.clientState = it }

        /** Null means: use the entity position. */
        private fun core(e: SignatureEntity, partialTick: Float): Vec3? {
            val stuckAt = NorthStar.stuckAt(e)
            if (stuckAt <= 0) return frost(e).stop
            if (e.targetId >= 0) {
                val host = e.target() as? LivingEntity ?: return null
                if (!host.isAlive) return null
                val middle = host.getPosition(partialTick).add(0.0, host.bbHeight * 0.5, 0.0)
                return NorthStar.circling(middle, host.bbWidth, e.point, (e.tickCount + partialTick - stuckAt).toDouble())
            }
            return if (NorthStar.onShip(e)) null else NorthStar.hovering(e.point, NorthStar.hover(e), (e.tickCount + partialTick - stuckAt).toDouble())
        }

        private fun guess(e: SignatureEntity) {
            val f = frost(e)
            if (f.stop != null) {
                if (e.tickCount - f.stopAt > GUESS_TICKS) f.stop = null
                return
            }
            val p = e.position()
            if (e.tickCount * NorthStar.SPEED >= NorthStar.range(e.power)) { f.stop = p; f.stopAt = e.tickCount; return }
            val next = p.add(e.deltaMovement)
            val level = e.level()
            val hit = level.clip(ClipContext(p, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e))
            val end = if (hit.type == HitResult.Type.BLOCK) hit.location else next
            val owner = e.owner()
            val creature = level.getEntitiesOfClass(LivingEntity::class.java, AABB(p, end).inflate(1.0)) { it.isAlive && !it.isSpectator && it !== owner }
                .mapNotNull { it.boundingBox.inflate(0.3).clip(p, end).orElse(null) }
                .minByOrNull { it.distanceToSqr(p) }
            val stop = creature ?: if (hit.type == HitResult.Type.BLOCK) hit.location else null
            if (stop != null) { f.stop = stop; f.stopAt = e.tickCount }
        }

        override fun tick(e: SignatureEntity) {
            val stuck = NorthStar.stuckAt(e) > 0
            if (!stuck) guess(e)
            val level = e.level()
            val r = level.random
            val p = core(e, 1f) ?: e.position()
            val spread = if (stuck) NorthStar.ORBIT * 0.8 else 0.4
            repeat(if (stuck) 10 else 3) {
                level.addParticle(ParticleTypes.SNOWFLAKE, p.x + r.nextGaussian() * spread, p.y + r.nextGaussian() * spread * 0.4, p.z + r.nextGaussian() * spread, 0.0, -0.01, 0.0)
            }
            if (stuck && e.tickCount % 3 == 0) level.addParticle(ParticleTypes.ITEM_SNOWBALL, p.x + r.nextGaussian() * spread, p.y, p.z + r.nextGaussian() * spread, 0.0, 0.0, 0.0)
        }

        override fun render(d: SignatureDraw) {
            val f = ReferenceSignatureVisuals.fade(d, 2f, NorthStar.SHRINK.toFloat())
            val world = core(d.e, d.partialTick)
            val at = world?.let(d::local) ?: Vec3.ZERO
            val coreWorld = world ?: d.at
            val light = d.lightAt(coreWorld)
            val stuckAt = NorthStar.stuckAt(d.e)
            val grow = if (stuckAt <= 0) 0.0 else SpellGeom.easeOut(((d.age - stuckAt) / NorthStar.GROW).toDouble().coerceIn(0.0, 1.0)) * f
            val random = Random(d.e.seed)
            d.blocks()?.let { c ->
                SpellShapes.texBox(c, d.m, SpellGeom.cubeCorners(at, 0.6, d.age * 0.1, 0.4), SpellArt.block("packed_ice"), 0xFFFFFF, f, light, d.e.seed)
                if (grow > 0.05) for (i in 0 until SHARDS) {
                    val half = 0.2 + random.nextDouble() * 0.25
                    val rr = NorthStar.ORBIT * grow * (0.75 + 0.35 * random.nextDouble())
                    val a = d.age * 0.06 + i * 2 * Math.PI / SHARDS
                    val p = tilted(Vec3(cos(a) * rr, sin(a * 3) * 0.5, sin(a) * rr))
                    // Sample light for each shard, because the ring is wide.
                    SpellShapes.texBox(c, d.m, SpellGeom.cubeCorners(at.add(p), half, d.age * 0.07, d.age * 0.1 + i), SpellArt.block(if (i % 3 == 0) "blue_ice" else "ice"), 0xFFFFFF, 0.9f * f, d.lightAt(coreWorld.add(p)), d.e.seed + i)
                }
            }
            val glow = soft(d)
            SpellShapes.sphere(glow, d.m, d.view, at, 1.2 + 1.5 * grow, COLD, 0.55f * f)
            SpellShapes.disc(glow, d.m, d.view, at, 2.5 + 3.0 * grow, COLD, 0.45f * f)
            if (grow > 0.05) {
                SpellShapes.ring(glow, d.m, at, NorthStar.ORBIT * grow, 1.2, 0xBFE6FF, 0.35f * grow.toFloat(), RING_TILT)
                SpellShapes.ring(glow, d.m, at, NorthStar.ORBIT * grow * 0.8, 0.6, COLD, 0.25f * grow.toFloat(), RING_TILT)
                SpellShapes.sphere(glow, d.m, d.view, at, NorthStar.ORBIT * grow * 1.15, COLD, 0.1f * grow.toFloat())
            }
        }

        /** Rotates about x by [RING_TILT], then about z by [RING_ROLL]. */
        private fun tilted(p: Vec3): Vec3 {
            val ct = cos(RING_TILT); val st = sin(RING_TILT)
            val y1 = p.y * ct - p.z * st; val z1 = p.y * st + p.z * ct
            val cr = cos(RING_ROLL); val sr = sin(RING_ROLL)
            return Vec3(p.x * cr - y1 * sr, p.x * sr + y1 * cr, z1)
        }
    }

    object HungeringMawVisual : SignatureVisual {
        private const val SMEARS = 11
        private const val EAT_TICKS = 16f
        private const val EAT_TURNS = 1.2

        /** [from] is the offset from the maw at the time the block was eaten. */
        private class Eaten(val state: BlockState, val pos: BlockPos, val from: Vec3, val at: Int)

        private class Near {
            lateinit var state: BlockState
            var x = 0.0
            var y = 0.0
            var z = 0.0
        }

        // Two maps swap each tick and their entries are reused, so a maw tick does not allocate for each near block.
        private class Maw {
            var near = Long2ObjectOpenHashMap<Near>()
            var spare = Long2ObjectOpenHashMap<Near>()
            val freeNear = ArrayList<Near>()
            val cursor = BlockPos.MutableBlockPos()
            val eaten = ArrayList<Eaten>()
        }

        private fun maw(e: SignatureEntity): Maw = e.clientState as? Maw ?: Maw().also { e.clientState = it }

        /** The client cannot see which blocks the server ate, so it compares the near blocks with the last tick. */
        private fun watch(e: SignatureEntity) {
            val level = e.level()
            val m = maw(e)
            val p = e.position()
            val last = m.near.long2ObjectEntrySet().fastIterator()
            while (last.hasNext()) {
                val entry = last.next()
                if (!level.getBlockState(m.cursor.set(entry.longKey)).isAir) continue
                val near = entry.value
                m.eaten += Eaten(near.state, BlockPos.of(entry.longKey), Vec3(near.x - p.x, near.y - p.y, near.z - p.z), e.tickCount)
            }
            m.eaten.removeIf { e.tickCount - it.at > EAT_TICKS }
            val next = m.spare
            val reach = HungeringMaw.TEAR_REACH + 1.5
            val blocks = CrossFrameBlocks(level, p, reach)
            blocks.forEachWithin(reach) { pos, _ ->
                val state = level.getBlockState(pos)
                if (HungeringMaw.eatable(level, pos, state)) {
                    val middle = blocks.visitedMiddle
                    val near = m.freeNear.removeLastOrNull() ?: Near()
                    near.state = state
                    near.x = middle.x; near.y = middle.y; near.z = middle.z
                    next.put(pos.asLong(), near)?.let { m.freeNear += it }
                }
            }
            m.freeNear.addAll(m.near.values)
            m.near.clear()
            m.spare = m.near
            m.near = next
        }

        override fun tick(e: SignatureEntity) {
            watch(e)
            val level = e.level()
            val r = level.random
            val p = e.position()
            repeat(10) {
                val dir = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize()
                val from = p.add(dir.scale(HungeringMaw.REACH * (0.3 + 0.7 * r.nextDouble())))
                val v = p.subtract(from).scale(0.06)
                level.addParticle(if (it % 3 == 0) ParticleTypes.PORTAL else ParticleTypes.REVERSE_PORTAL, from.x, from.y, from.z, v.x, v.y, v.z)
            }
            if (e.tickCount % 3 == 0) {
                val dir = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize()
                val from = p.add(dir.scale(HungeringMaw.SPHERE * 1.6))
                level.addParticle(ParticleTypes.DRAGON_BREATH, from.x, from.y, from.z, -dir.x * 0.05, -dir.y * 0.05, -dir.z * 0.05)
            }
        }

        override fun render(d: SignatureDraw) {
            val live = minOf(SpellGeom.easeOut((d.age / HungeringMaw.GROW).toDouble().coerceIn(0.0, 1.0)), ((d.e.life - d.age) / HungeringMaw.CLOSE).toDouble().coerceIn(0.0, 1.0))
            if (live <= 0.0) return
            val sphere = HungeringMaw.SPHERE * live
            val a = live.toFloat()
            SpellShapes.disc(d.softShade() ?: d.shade(), d.m, d.view, Vec3.ZERO, HungeringMaw.BITE * live * 1.1, 0x05000A, 0.6f * a)
            val glow = soft(d)
            SpellShapes.disc(glow, d.m, d.view, Vec3.ZERO, HungeringMaw.REACH * 0.45 * live, VOID, 0.3f * a)
            SpellShapes.disc(glow, d.m, d.view, Vec3.ZERO, HungeringMaw.BITE * live, 0xE8C8FF, 0.22f * a)
            val random = Random(d.e.seed)
            for (k in 0 until SMEARS) {
                val r = sphere * (1.06 + random.nextDouble() * 0.35)
                val start = random.nextDouble() * Math.PI * 2 + d.age * (0.04 + random.nextDouble() * 0.05)
                val span = 0.8 + random.nextDouble() * 1.4
                val pts = List(11) { i ->
                    val t = start + span * i / 10
                    d.view.left.scale(cos(t) * r).add(d.view.up.scale(sin(t) * r))
                }
                val alphas = FloatArray(11) { i -> sin(Math.PI * i / 10).toFloat() }
                SpellShapes.strip(glow, d.m, d.view, pts, { 0.28 }, if (k % 2 == 0) VOID else 0xF0DCFF, 0.8f * a, soft = true, alphas = alphas)
            }
            SpellShapes.sphere(glow, d.m, d.view, Vec3.ZERO, sphere * 1.05, VOID, 0.8f * a)
            SpellShapes.ring(glow, d.m, Vec3.ZERO, sphere * 1.5, 0.2, 0xE8C8FF, 0.4f * a, 0.25 + 0.1 * sin(d.time * 0.05))
            eaten(d, live)
            if (PocketPortalRenderType.isReady()) {
                SpellShapes.sphereFlat(d.buffers.getBuffer(PocketPortalRenderType.POCKET_PORTAL), d.m, Vec3.ZERO, sphere, 0xFFFFFF, 1f)
            } else SpellShapes.sphereFlat(d.shade(), d.m, Vec3.ZERO, sphere, 0x05000A, 0.95f)
        }

        private fun eaten(d: SignatureDraw, live: Double) {
            val m = maw(d.e)
            if (m.eaten.isEmpty()) return
            val c = d.blocks() ?: return
            val mc = Minecraft.getInstance()
            for ((i, b) in m.eaten.withIndex()) {
                val t = ((d.age - b.at) / EAT_TICKS).toDouble()
                if (t < 0 || t >= 1) continue
                val pull = t * t
                val spin = EAT_TURNS * 2 * Math.PI * pull
                val off = b.from.yRot(spin.toFloat()).scale(1 - pull)
                val half = 0.5 * (1 - 0.7 * t)
                val icon = mc.blockRenderer.getBlockModel(b.state).particleIcon
                val sprite = Sprite(BLOCK_ATLAS, icon.u0, icon.v0, icon.u1, icon.v1)
                // Leaves and plants keep their biome tint.
                val tinted = b.state.block is LeavesBlock || b.state.getCollisionShape(d.level, b.pos).isEmpty
                val rgb = if (tinted) mc.blockColors.getColor(b.state, d.level, b.pos, 0).let { if (it == -1) 0xFFFFFF else it and 0xFFFFFF } else 0xFFFFFF
                val tumble = d.age * 0.3 + i
                SpellShapes.texBox(c, d.m, SpellGeom.cubeCorners(off, half, tumble, tumble * 0.7), sprite, rgb, (live * (1 - 0.5 * t)).toFloat(), d.lightAt(d.at.add(off)), d.e.seed + i)
            }
        }
    }

    private class Orb {
        val trail = ArrayDeque<Vec3>()
        var chain: List<Vec3> = emptyList()
        var chainAt = -100
    }

    private const val TRAIL = 10
    private const val BOLT_TICKS = 4

    object FaesparkVisual : SignatureVisual {
        private fun orb(e: SignatureEntity): Orb = e.clientState as? Orb ?: Orb().also { e.clientState = it }

        override fun tick(e: SignatureEntity) {
            val o = orb(e)
            val level = e.level()
            val p = e.position()
            if (Faespark.isHand(e)) {
                val hand = SignatureVisuals.hand(e, 1f) ?: return
                if (e.tickCount % 2 == 0) level.addParticle(ParticleTypes.ELECTRIC_SPARK, hand.x, hand.y, hand.z, level.random.nextGaussian() * 0.04, level.random.nextGaussian() * 0.04, level.random.nextGaussian() * 0.04)
                return
            }
            o.trail.addFirst(p)
            while (o.trail.size > TRAIL) o.trail.removeLast()
            if (Faespark.chainsNow(e)) {
                val metal = Faespark.chainMetal(e)
                if (metal != null) level.playLocalSound(p.x, p.y, p.z, EKSounds.SIGNATURE_FAESPARK_ARC.get(), SoundSource.PLAYERS, 1f, 1f, false)
                val chain = Faespark.chain(level, p, metal, e.owner())
                if (chain.points.size > 1) {
                    o.chain = chain.points
                    o.chainAt = e.tickCount
                    level.playLocalSound(p.x, p.y, p.z, EKSounds.SIGNATURE_FAESPARK_ZAP.get(), SoundSource.PLAYERS, 0.7f, 1f, false)
                    for (q in chain.points.drop(1)) repeat(4) {
                        level.addParticle(ParticleTypes.ELECTRIC_SPARK, q.x, q.y, q.z, level.random.nextGaussian() * 0.15, 0.1, level.random.nextGaussian() * 0.15)
                    }
                }
            }
            level.addParticle(if (e.tickCount % 2 == 0) ParticleTypes.ELECTRIC_SPARK else ParticleTypes.SMALL_FLAME, p.x, p.y, p.z, level.random.nextGaussian() * 0.05, level.random.nextGaussian() * 0.05, level.random.nextGaussian() * 0.05)
        }

        override fun render(d: SignatureDraw) {
            val o = orb(d.e)
            if (Faespark.isHand(d.e)) {
                val hand = d.hand() ?: return
                val at = d.local(hand)
                val flare = 1f - ((d.age % Faespark.GAP) / Faespark.GAP).coerceIn(0f, 1f)
                SpellShapes.sphere(soft(d), d.m, d.view, at, 0.12 + 0.12 * flare, 0xFF8A50, 0.6f + 0.3f * flare)
                SpellShapes.disc(soft(d), d.m, d.view, at, 0.4 + 0.4 * flare, CHAOS, 0.3f + 0.3f * flare)
                return
            }
            val f = ReferenceSignatureVisuals.fade(d, 3f, 6f)
            val pulse = 1 + 0.18 * sin(d.time * 1.7 + Faespark.index(d.e))
            val glow = soft(d)
            if (o.trail.size >= 2) {
                val pts = listOf(Vec3.ZERO) + o.trail.drop(1).map(d::local)
                val alphas = FloatArray(pts.size) { i -> 1f - i.toFloat() / pts.size }
                SpellShapes.strip(glow, d.m, d.view, pts, { i -> 0.5 * (1 - i.toDouble() / pts.size) }, CHAOS, 0.55f * f, soft = true, alphas = alphas)
            }
            SpellShapes.disc(glow, d.m, d.view, Vec3.ZERO, 1.4 * pulse, 0xFF8A50, 0.6f * f)
            SpellShapes.sphere(glow, d.m, d.view, Vec3.ZERO, 0.45 * pulse, 0xFF8A50, 0.85f * f)
            SpellShapes.sphere(glow, d.m, d.view, Vec3.ZERO, 0.2, 0xFFD8F0, 0.95f * f)
            val flicker = Random(d.e.seed + (d.age / 2).toInt() * 7919)
            repeat(3) {
                val end = SpellGeom.spread(flicker).scale(0.6 + 0.4 * flicker.nextDouble())
                bolt(d, flicker, Vec3.ZERO, end, CHAOS, 0.8f * f, 0.08)
            }
            val since = d.age - o.chainAt
            if (since < BOLT_TICKS && o.chain.size > 1) {
                val a = 1f - since / BOLT_TICKS
                val random = Random(d.e.seed + o.chainAt * 31)
                for (k in 0 until o.chain.size - 1) bolt(d, random, d.local(o.chain[k]), d.local(o.chain[k + 1]), CHAOS, a, 0.2)
            }
        }
    }

    private const val MOTES = 56
    private const val SPARKS = 10
    private val SPORE = DustParticleOptions(Vector3f(0.85f, 0.94f, 0.69f), 0.9f)

    object RotdanceVisual : SignatureVisual {
        override fun tick(e: SignatureEntity) {
            val level = e.level()
            val r = level.random
            val p = e.position()
            if (e.data == Rotdance.FADING) {
                repeat(8) {
                    val dir = Vec3(r.nextGaussian(), r.nextGaussian() * 0.5, r.nextGaussian()).normalize()
                    val from = p.add(dir.scale(Rotdance.BURST * r.nextDouble()))
                    level.addParticle(SPORE, from.x, from.y, from.z, dir.x * 0.05, 0.01, dir.z * 0.05)
                }
                repeat(2) { level.addParticle(ParticleTypes.SNEEZE, p.x + r.nextGaussian() * 2, p.y + r.nextGaussian(), p.z + r.nextGaussian() * 2, 0.0, 0.02, 0.0) }
                return
            }
            repeat(3) { level.addParticle(SPORE, p.x + r.nextGaussian() * 0.6, p.y + r.nextGaussian() * 0.6, p.z + r.nextGaussian() * 0.6, 0.0, 0.0, 0.0) }
            if (e.data == Rotdance.MOVING) level.addParticle(ParticleTypes.ELECTRIC_SPARK, p.x, p.y, p.z, r.nextGaussian() * 0.05, r.nextGaussian() * 0.05, r.nextGaussian() * 0.05)
            if (e.data == Rotdance.SETTLED && e.tickCount % 3 == 0) level.addParticle(ParticleTypes.SNEEZE, p.x + r.nextGaussian() * 0.5, p.y + r.nextGaussian() * 0.5, p.z + r.nextGaussian() * 0.5, 0.0, 0.02, 0.0)
            if (e.tickCount % 12 == 0) level.playLocalSound(p.x, p.y, p.z, SoundEvents.BEEHIVE_WORK, SoundSource.PLAYERS, 1.2f, 1.1f, false)
        }

        override fun render(d: SignatureDraw) {
            val fadeIn = minOf(1f, d.age / 5f)
            val out = if (d.e.data == Rotdance.FADING) ((d.e.life - d.age) / Rotdance.FADE).coerceIn(0f, 1f) else 1f
            val f = fadeIn * out
            if (f <= 0f) return
            val host = d.e.target()
            val base = if (d.e.data == Rotdance.SETTLED && host != null) maxOf(0.9, host.bbWidth * 1.0) else 0.9
            val burst = if (d.e.data == Rotdance.FADING) SpellGeom.easeOut((1.0 - out).coerceIn(0.0, 1.0)) else 0.0
            val spread = base + (Rotdance.BURST - base) * burst
            if (burst > 0) {
                SpellShapes.disc(d.softShade() ?: d.shade(), d.m, d.view, Vec3.ZERO, spread * 1.1, 0x2A3A12, 0.4f * out)
                SpellShapes.sphere(soft(d), d.m, d.view, Vec3.ZERO, spread * 0.9, POISON, 0.35f * out)
            }
            val c = d.glowParticles()
            val random = Random(d.e.seed)
            if (c != null) for (i in 0 until MOTES + SPARKS) {
                val r = spread * (0.3 + 0.7 * random.nextDouble())
                val speed = 0.08 + random.nextDouble() * 0.12
                val a = d.age * speed + random.nextDouble() * Math.PI * 2
                val b = d.age * speed * 0.7 + random.nextDouble() * Math.PI * 2
                val at = Vec3(cos(a) * cos(b) * r, sin(b) * r * 0.7, sin(a) * cos(b) * r)
                if (i >= MOTES) {
                    if (((d.age.toInt() + i * 3) % 5) > 1) continue
                    SpellShapes.sprite(c, d.m, d.view, at, 0.18, SpellArt.spark(i + d.age.toInt()), CHAOS, f, SpellShapes.FULL_BRIGHT)
                } else {
                    SpellShapes.sprite(c, d.m, d.view, at, 0.13, SpellArt.glitter(i + d.age.toInt() / 2), if (i % 3 == 0) POISON else 0xD8F0B0, f, SpellShapes.FULL_BRIGHT)
                }
            }
            SpellShapes.disc(soft(d), d.m, d.view, Vec3.ZERO, spread * 1.8, POISON, 0.3f * f)
        }
    }

    private const val SHOCK_TICKS = 22
    private const val STAB_TICKS = 2.5f

    private class Rays { var to: List<Vec3> = emptyList(); var at = -100 }

    object SupernovaVisual : SignatureVisual {
        private fun rays(e: SignatureEntity): Rays = e.clientState as? Rays ?: Rays().also { e.clientState = it }

        override fun tick(e: SignatureEntity) {
            val level = e.level()
            val r = level.random
            val p = e.position()
            if (Supernova.stabsNow(e)) {
                val ray = rays(e)
                ray.to = Supernova.rayTargets(level, p, e.owner()).map { it.position().add(0.0, it.bbHeight * 0.5, 0.0) }
                ray.at = e.tickCount
                for (q in ray.to) repeat(3) { level.addParticle(ParticleTypes.END_ROD, q.x, q.y, q.z, r.nextGaussian() * 0.1, r.nextGaussian() * 0.1, r.nextGaussian() * 0.1) }
                if (ray.to.isNotEmpty()) level.playLocalSound(p.x, p.y, p.z, EKSounds.SIGNATURE_SUPERNOVA_RAY.get(), SoundSource.PLAYERS, 1.2f, 1f, false)
            }
            if (Supernova.phase(e) == Supernova.BURST) {
                val c = Supernova.charge(e)
                val reach = Supernova.blastRadius(c)
                if (e.tickCount >= e.life - 6) return
                repeat(6) {
                    val dir = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize()
                    val from = p.add(dir.scale(reach * 0.4 * r.nextDouble()))
                    level.addParticle(ParticleTypes.FLAME, from.x, from.y, from.z, dir.x * 0.15, dir.y * 0.15 + 0.05, dir.z * 0.15)
                }
                repeat(3) {
                    val dir = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize()
                    level.addParticle(ParticleTypes.END_ROD, p.x, p.y, p.z, dir.x * 0.4, dir.y * 0.4, dir.z * 0.4)
                }
                if (r.nextInt(2) == 0) level.addParticle(ParticleTypes.LARGE_SMOKE, p.x + r.nextGaussian() * reach * 0.3, p.y + reach * 0.4, p.z + r.nextGaussian() * reach * 0.3, 0.0, 0.08, 0.0)
                if (r.nextInt(3) == 0) level.addParticle(ParticleTypes.LAVA, p.x, p.y, p.z, 0.0, 0.0, 0.0)
                return
            }
            val reach = 0.6 + 1.2 * Supernova.charge(e)
            repeat(3) {
                val dir = Vec3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize()
                val from = p.add(dir.scale(reach))
                val v = p.subtract(from).scale(0.15)
                level.addParticle(if (r.nextBoolean()) ParticleTypes.END_ROD else ParticleTypes.SMALL_FLAME, from.x, from.y, from.z, v.x, v.y, v.z)
            }
        }

        override fun render(d: SignatureDraw) {
            val c = Supernova.charge(d.e)
            val glow = soft(d)
            stabs(d, glow)
            if (Supernova.phase(d.e) == Supernova.BURST) {
                burst(d, glow, c)
                return
            }
            val at = if (Supernova.phase(d.e) == Supernova.CHARGING) {
                val owner = d.e.owner()
                val hand = d.hand()
                if (owner != null && hand != null) d.local(hand.add(owner.getViewVector(d.partialTick).scale(Supernova.AHEAD))) else Vec3.ZERO
            } else Vec3.ZERO
            val throb = 0.03 * sin(d.time * 2.0)
            SpellShapes.disc(glow, d.m, d.view, at, 0.8 + 2.4 * c, SUNFIRE, 0.65f)
            SpellShapes.sphere(glow, d.m, d.view, at, 0.25 + 0.5 * c + throb, SUNFIRE, 0.8f)
            SpellShapes.sphere(glow, d.m, d.view, at, 0.15 + 0.35 * c + throb, 0xFFFFFF, 0.95f)
            SpellShapes.disc(glow, d.m, d.view, at, 0.3 + 0.7 * c, 0xFFFFFF, 0.85f)
            val flicker = Random(d.e.seed + (d.age / 2).toInt() * 7919)
            repeat(2) {
                if (flicker.nextFloat() < 0.6f) {
                    val dir = SpellGeom.spread(flicker)
                    bolt(d, flicker, at.add(dir.scale(0.15 + 0.35 * c)), at.add(dir.scale(0.6 + 0.9 * c)), CHAOS, 0.85f, 0.08)
                }
            }
        }

        private fun stabs(d: SignatureDraw, glow: VertexConsumer) {
            val ray = rays(d.e)
            val since = d.age - ray.at
            if (since < 0f || since >= STAB_TICKS || ray.to.isEmpty()) return
            val k = 1f - since / STAB_TICKS
            for (to in ray.to) {
                val b = d.local(to)
                val pts = listOf(Vec3.ZERO, b)
                SpellShapes.strip(glow, d.m, d.view, pts, { i -> if (i == 0) 0.3 * k else 0.12 * k }, SUNFIRE, 0.6f * k, soft = true)
                SpellShapes.strip(glow, d.m, d.view, pts, { i -> if (i == 0) 0.08 * k else 0.03 }, 0xFFFFFF, 0.95f * k, soft = false)
                SpellShapes.disc(glow, d.m, d.view, b, 0.6 * k + 0.2, 0xFFF4D8, 0.8f * k)
            }
        }

        private fun burst(d: SignatureDraw, glow: VertexConsumer, c: Float) {
            val t = Supernova.sinceBurst(d.e, d.age)
            val r = Supernova.blastRadius(c)
            val out = ((Supernova.BURST_TICKS - t) / 20f).coerceIn(0f, 1f)
            val flash = (1f - t / 10f).coerceIn(0f, 1f)
            val swell = SpellGeom.easeOut((t / 10.0).coerceIn(0.0, 1.0))
            val throb = 1 + 0.07 * sin(d.time * 1.3)
            SpellShapes.disc(glow, d.m, d.view, Vec3.ZERO, r * (0.6 + t / 8.0), 0xFFFFFF, 0.95f * flash)
            SpellShapes.disc(glow, d.m, d.view, Vec3.ZERO, r * 1.4 * (0.5 + 0.5 * swell), SUNFIRE, 0.6f * out)
            val core = r * 0.45 * swell * throb * (0.4 + 0.6 * out)
            SpellShapes.sphere(glow, d.m, d.view, Vec3.ZERO, core, SUNFIRE, 0.75f * out)
            SpellShapes.sphere(glow, d.m, d.view, Vec3.ZERO, core * 0.6, 0xFFF4D8, 0.9f * out)
            SpellShapes.disc(glow, d.m, d.view, Vec3.ZERO, core * 1.2, 0xFFFFFF, 0.5f * out)
            var s = 0
            while (s <= t) {
                val u = t - s
                if (u < SHOCK_TICKS) {
                    val k = 1f - u / SHOCK_TICKS
                    val rr = 0.8 + r * 1.3 * SpellGeom.easeOut(u / SHOCK_TICKS.toDouble())
                    SpellShapes.ring(glow, d.m, Vec3.ZERO, rr, 0.5, 0xFFE0A0, 0.9f * k, 0.0)
                    SpellShapes.ring(glow, d.m, Vec3.ZERO, rr * 0.85, 0.35, SUNFIRE, 0.6f * k, Math.PI / 2)
                }
                s += Supernova.SHOCK_EVERY
            }
            val flicker = Random(d.e.seed + (d.age / 2).toInt() * 7919)
            repeat(4) {
                val dir = SpellGeom.spread(flicker)
                bolt(d, flicker, dir.scale(core * 0.8), dir.scale(core * 0.8 + r * (0.3 + 0.3 * flicker.nextDouble())), CHAOS, 0.8f * out, 0.1)
            }
        }
    }
}
