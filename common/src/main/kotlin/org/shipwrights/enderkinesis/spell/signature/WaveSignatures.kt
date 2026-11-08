package org.shipwrights.enderkinesis.spell.signature

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.TamableAnimal
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.MushroomBlock
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.dimension.EKDamageSources
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.spell.BloodPacts
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Signature
import org.shipwrights.enderkinesis.spell.SpellContext
import org.shipwrights.enderkinesis.spell.SpellVfx
import org.valkyrienskies.mod.common.world.clipIncludeShips
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import org.shipwrights.enderkinesis.util.colliderClip
import org.valkyrienskies.core.api.util.GameTickOnly

@OptIn(GameTickOnly::class)
object WaveSignatures {
    val ALL: Map<Signature, SignatureBehaviour> = mapOf(
        Signature.AVALANCHE to Avalanche,
        Signature.BLOODWAVE to Bloodwave,
        Signature.YGANNS_GRASP to YgannsGrasp,
        Signature.FIREWEED to Fireweed,
        Signature.SHROOMSPREAD to Shroomspread,
        Signature.GARDEN_SANGUINE to GardenSanguine,
    )

    /** Client and server both call this. On a ship hit, blockPos is in the shipyard; null when [fromY] starts inside a block. */
    fun surface(level: Level, x: Double, z: Double, fromY: Double, depth: Double): BlockHitResult? {
        val from = Vec3(x, fromY, z)
        val hit = level.clipIncludeShips(colliderClip(from, Vec3(x, fromY - depth, z)))
        if (hit.type != HitResult.Type.BLOCK || hit.location.y >= fromY - 1.0e-3) return null
        return hit
    }

    fun surfaceY(level: Level, x: Double, z: Double, fromY: Double, depth: Double): Double? = surface(level, x, z, fromY, depth)?.location?.y

    private fun heading(cast: SignatureCast): Vec3 {
        val aim = cast.aim
        val flat = Vec3(aim.x, 0.0, aim.z)
        if (flat.lengthSqr() > 1.0e-4) return flat.normalize()
        val yaw = Math.toRadians(cast.caster.yRot.toDouble())
        return Vec3(-sin(yaw), 0.0, cos(yaw))
    }

    /** Client and server build the same lane from the entity position and [SignatureEntity.point]. */
    class Lane(val from: Vec3, val dir: Vec3, private val heights: DoubleArray, val end: Double = Double.POSITIVE_INFINITY) {
        val side: Vec3 = Vec3(-dir.z, 0.0, dir.x)

        fun reaches(s: Double): Boolean = s <= end

        fun centreY(s: Double): Double {
            val i = (s / STEP).coerceIn(0.0, heights.size - 1.0)
            val i0 = floor(i).toInt(); val i1 = min(i0 + 1, heights.size - 1)
            return heights[i0] + (heights[i1] - heights[i0]) * (i - i0)
        }

        fun at(s: Double, across: Double): Vec3 =
            Vec3(from.x + dir.x * s + side.x * across, centreY(s), from.z + dir.z * s + side.z * across)

        fun ground(level: Level, s: Double, across: Double): Double {
            val p = at(s, across)
            return surfaceY(level, p.x, p.z, p.y + 2.0, 6.0) ?: p.y
        }

        fun along(p: Vec3): Double = (p.x - from.x) * dir.x + (p.z - from.z) * dir.z
        fun across(p: Vec3): Double = (p.x - from.x) * side.x + (p.z - from.z) * side.z

        companion object {
            const val STEP = 0.5
        }
    }

    fun lane(level: Level, e: SignatureEntity, length: Double): Lane {
        val from = e.position()
        val flat = Vec3(e.point.x - from.x, 0.0, e.point.z - from.z)
        val dir = if (flat.lengthSqr() < 1.0e-6) Vec3(1.0, 0.0, 0.0) else flat.normalize()
        return ray(level, from, dir, length)
    }

    /** [dir] must be a level unit vector. */
    fun ray(level: Level, from: Vec3, dir: Vec3, length: Double): Lane {
        val n = (length / Lane.STEP).toInt() + 2
        val heights = DoubleArray(n)
        var y = from.y
        var end = Double.POSITIVE_INFINITY
        for (i in 0 until n) {
            val s = i * Lane.STEP
            // Follow the ground down slopes and up single steps; a wall keeps the last height.
            // Ground out of [BAND] ends the lane.
            if (end.isInfinite()) {
                val ground = surfaceY(level, from.x + dir.x * s, from.z + dir.z * s, y + 1.6, 5.0) ?: y
                if (abs(ground - from.y) > BAND) end = maxOf(0.0, s - Lane.STEP) else y = ground
            }
            heights[i] = y
        }
        return Lane(from, dir, heights, end)
    }

    /** Maximum height change from the cast point that a wave follows. */
    const val BAND = 4.0

    /** Heals [caster] by [share] of the health that [hurt] took from [target]. */
    private fun drain(caster: LivingEntity?, target: LivingEntity, share: Float, hurt: () -> Boolean): Boolean {
        val before = target.health
        if (!hurt()) return false
        val taken = (before - target.health).coerceAtLeast(0f)
        if (caster != null && caster.isAlive && taken > 0f && !SpellContext.isUndead(caster)) caster.heal(taken * share)
        return true
    }

    private fun ally(caster: LivingEntity?, other: LivingEntity): Boolean {
        if (caster == null) return false
        if (other === caster || other.isAlliedTo(caster)) return true
        if (other is TamableAnimal && other.isOwnedBy(caster)) return true
        return BloodPacts.isThrallOf(other, caster.uuid)
    }

    /** Client and server step it the same way with [Avalanche.step]. */
    class Slab(val across: Double, val lat: Double, val seed: Int, x: Double, y: Double, z: Double) {
        var x = x; var y = y; var z = z
        var px = x; var py = y; var pz = z
        var vy = 0.0
        val startY = y
        var descent = 0.0
        var running = true
        var landed = false
        var rolled = 0.0

        val size: Double get() = min(Avalanche.SIZE_MAX, Avalanche.SIZE0 + Avalanche.GROW * descent)

        fun centre(f: Double = 1.0): Vec3 = Vec3(px + (x - px) * f, py + (y - py) * f + size, pz + (z - pz) * f)
    }

    class Slide(val dir: Vec3, val slabs: List<Slab>) {
        val side: Vec3 = Vec3(-dir.z, 0.0, dir.x)
        var t = 0
    }

    object Avalanche : SignatureBehaviour() {
        const val SLABS = 11
        const val END_MIN = 56
        const val END_MAX = 116
        const val LIFE = END_MAX + 30
        const val FRONT0 = 1.2
        const val SPEED = 0.3
        const val ACCEL = 0.03
        const val SPEED_MAX = 0.8
        /** [DROP_MAX] is large on purpose: the slide is meant to fall. */
        const val CLIMB_MAX = 4.0
        const val DROP_MAX = 24.0
        const val GRAVITY = 0.08
        const val STEP_UP = 1.1
        const val FALL_DEPTH = 48.0
        const val SPREAD = 0.03
        const val SIZE0 = 0.55
        const val GROW = 0.06
        const val SIZE_MAX = 1.6
        private const val FALL_BOOST = 0.05
        private const val FALL_BOOST_MAX = 15.0
        private const val HIT_MIN = 14f
        private const val HIT_MAX = 22f
        private const val GRIND = 4f
        private const val GRIND_EVERY = 5
        private const val REACH = 0.9
        private const val SLAM = 0.9
        private const val SLAM_UP = 0.45
        private const val CARRY = 0.25
        private const val SHIP_PUSH = 1.4
        private const val SLOW_TICKS = 110
        private const val SLOW_LEVEL = 3
        private const val CHILL_TICKS = 100
        private const val BURY_LAYERS = 4
        private const val SETTLE = 0.35

        fun endTick(power: Float): Int = (END_MIN + (END_MAX - END_MIN) * power).toInt()

        fun boost(descent: Double): Double = 1 + FALL_BOOST * min(descent, FALL_BOOST_MAX)

        /** Client and server both call this. */
        fun slide(level: Level, from: Vec3, toward: Vec3, seed: Int): Slide {
            val flat = Vec3(toward.x - from.x, 0.0, toward.z - from.z)
            val dir = if (flat.lengthSqr() < 1.0e-6) Vec3(1.0, 0.0, 0.0) else flat.normalize()
            val side = Vec3(-dir.z, 0.0, dir.x)
            val r = Random(seed)
            val slabs = List(SLABS) { i ->
                val across = (i - (SLABS - 1) / 2.0) * 0.75 + (r.nextDouble() - 0.5) * 0.4
                val x = from.x + dir.x * FRONT0 + side.x * across
                val z = from.z + dir.z * FRONT0 + side.z * across
                val y = surfaceY(level, x, z, from.y + 1.6, 6.0) ?: from.y
                Slab(across, across * SPREAD, r.nextInt(), x, y, z)
            }
            return Slide(dir, slabs)
        }

        fun step(level: Level, slide: Slide, power: Float) {
            slide.t++
            val stop = slide.t > endTick(power)
            for (s in slide.slabs) {
                s.px = s.x; s.py = s.y; s.pz = s.z
                s.landed = false
                if (stop) s.running = false
                if (s.running) {
                    val hs = min(SPEED_MAX, SPEED + ACCEL * s.descent)
                    val nx = s.x + slide.dir.x * hs + slide.side.x * s.lat
                    val nz = s.z + slide.dir.z * hs + slide.side.z * s.lat
                    val top = BlockPos.containing(nx, s.y + STEP_UP, nz)
                    val next = surfaceY(level, nx, nz, s.y + STEP_UP, FALL_DEPTH)
                    if (!level.getBlockState(top).getCollisionShape(level, top).isEmpty) s.running = false
                    else if (next == null || next > s.startY + CLIMB_MAX || next < s.startY - DROP_MAX) s.running = false
                    else { s.x = nx; s.z = nz; s.rolled += hs }
                }
                val ground = surfaceY(level, s.x, s.z, s.y + STEP_UP, FALL_DEPTH)
                s.vy -= GRAVITY
                s.y += s.vy
                if (ground != null && s.y <= ground) {
                    s.landed = s.vy < -0.3
                    s.y = ground
                    s.vy = 0.0
                }
                s.descent = maxOf(s.descent, s.startY - s.y)
            }
        }

        private class Sliding(val slide: Slide) {
            // Tick of the last grind for each entity id.
            val ground = HashMap<Int, Int>()
            val pushed = HashSet<Pair<Long, Long>>()
            var slammed = Int.MIN_VALUE / 2
        }

        /** About 2 slab slams a second at most. */
        private const val SLAB_SLAM_EVERY = 10

        override fun start(cast: SignatureCast): SignatureEntity {
            val feet = cast.caster.position()
            val dir = heading(cast)
            SignatureKit.sound(cast.level, feet, EKSounds.SIGNATURE_AVALANCHE_CAST.get(), 2f)
            return spawn(cast, feet, LIFE) { point = feet.add(dir.scale(16.0)) }
        }

        override fun tick(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val state = e.state as? Sliding ?: Sliding(slide(level, e.position(), e.point, e.seed)).also { e.state = it }
            val slide = state.slide
            while (slide.t < e.tickCount) step(level, slide, e.power)
            val t = e.tickCount
            // Carry each creature once a tick, however many sections it is in.
            val moved = HashSet<Int>()
            for (s in slide.slabs) {
                val centre = s.centre()
                val boost = boost(s.descent)
                if (s.landed) {
                    if (t - state.slammed >= SLAB_SLAM_EVERY) {
                        state.slammed = t
                        SignatureKit.sound(level, centre, EKSounds.SIGNATURE_AVALANCHE_SLAM.get(), 2f)
                    }
                    SignatureKit.particles(level, ParticleTypes.SNOWFLAKE, centre, 12, s.size, 0.1)
                }
                if (!s.running) continue
                for (target in SignatureKit.creatures(e, centre, s.size + REACH)) {
                    val last = state.ground[target.id]
                    if (moved.add(target.id)) {
                        SignatureKit.push(target, slide.dir.scale(CARRY * boost))
                        target.ticksFrozen = minOf(target.ticksFrozen + 10, target.ticksRequiredToFreeze + CHILL_TICKS)
                    }
                    if (last == null) {
                        state.ground[target.id] = t
                        SignatureKit.hurt(e, target, EKDamageSources.WINTER_KEY, ((HIT_MIN + (HIT_MAX - HIT_MIN) * e.power) * boost).toFloat())
                        SignatureKit.push(target, slide.dir.scale(SLAM * boost).add(0.0, SLAM_UP, 0.0))
                        target.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, SLOW_TICKS, SLOW_LEVEL), e.caster)
                        SignatureKit.sound(level, target.position(), EKSounds.SIGNATURE_AVALANCHE_SLAM.get(), 1.4f)
                        bury(e, target.blockPosition())
                    } else if (last != t && t - last >= GRIND_EVERY) {
                        state.ground[target.id] = t
                        SignatureKit.hurtThrough(e, target, EKDamageSources.WINTER_KEY, (GRIND * boost).toFloat())
                    }
                }
                for (block in SignatureShips.blocksNear(level, centre, s.size + 0.3)) {
                    if (state.pushed.add(block.ship.id to block.pos.asLong())) SignatureShips.push(block.ship, block.world, slide.dir.scale(SHIP_PUSH * boost))
                }
                if (s.vy == 0.0 && level.random.nextFloat() < SETTLE) {
                    val pos = BlockPos.containing(s.x, s.y + 0.01, s.z)
                    if (level.getBlockState(pos).isAir && Blocks.SNOW.defaultBlockState().canSurvive(level, pos)) SignatureKit.setBlock(e, pos, Blocks.SNOW.defaultBlockState())
                }
            }
            if (t == endTick(e.power)) settle(level, state)
        }

        private fun settle(level: ServerLevel, state: Sliding) {
            SignatureKit.sound(level, state.slide.slabs[SLABS / 2].centre(), EKSounds.SIGNATURE_AVALANCHE_SETTLE.get(), 2f)
            for (id in state.ground.keys) {
                val buried = level.getEntity(id) as? LivingEntity ?: continue
                if (buried.isAlive) SignatureKit.sound(level, buried.position(), EKSounds.SIGNATURE_AVALANCHE_BURY.get())
            }
        }

        private fun bury(e: SignatureEntity, feet: BlockPos) {
            val level = e.level() as ServerLevel
            for (pos in listOf(feet, feet.north(), feet.south(), feet.east(), feet.west())) {
                val state = level.getBlockState(pos)
                if (state.`is`(Blocks.SNOW)) {
                    val layers = state.getValue(SnowLayerBlock.LAYERS)
                    if (layers < BURY_LAYERS) SignatureKit.setBlock(e, pos, state.setValue(SnowLayerBlock.LAYERS, layers + 1))
                } else if (state.isAir && Blocks.SNOW.defaultBlockState().canSurvive(level, pos)) {
                    SignatureKit.setBlock(e, pos, Blocks.SNOW.defaultBlockState())
                }
            }
        }
    }

    object Bloodwave : SignatureBehaviour() {
        const val END_MIN = 56
        const val END_MAX = 112
        const val LIFE = END_MAX + 30
        const val R0 = 1.2
        const val SPEED = 0.3
        const val RAYS = 96
        const val DEPTH = 1.5
        const val HEIGHT = 3.0
        private const val SHIP_PUSH = 1.3
        private const val HIT_MIN = 18f
        private const val HIT_MAX = 30f
        private const val SIP = 4.5f
        private const val SIP_EVERY = 5
        private const val CRASH = 0.8
        private const val CRASH_UP = 0.35
        private const val CARRY = 0.2
        private const val HEAL_SHARE = 0.4f

        fun endTick(power: Float): Int = (END_MIN + (END_MAX - END_MIN) * power).toInt()
        fun radius(t: Double): Double = R0 + t * SPEED
        fun reach(power: Float): Double = radius(endTick(power).toDouble()) + 2

        fun crest(power: Float): Double = 2.25 + 1.0 * power

        /** Client and server both call this. */
        fun rays(level: Level, from: Vec3, reach: Double): List<Lane> =
            List(RAYS) { k -> val a = k * 2 * Math.PI / RAYS; ray(level, from, Vec3(cos(a), 0.0, sin(a)), reach) }

        fun rayAt(rays: List<Lane>, p: Vec3): Lane {
            val from = rays[0].from
            val a = Math.atan2(p.z - from.z, p.x - from.x)
            return rays[Math.floorMod(Math.round(a / (2 * Math.PI) * RAYS).toInt(), RAYS)]
        }

        fun groundAt(rays: List<Lane>, p: Vec3): Double =
            rayAt(rays, p).centreY(Vec3(p.x - rays[0].from.x, 0.0, p.z - rays[0].from.z).length())

        fun reachesAt(rays: List<Lane>, p: Vec3): Boolean =
            rayAt(rays, p).reaches(Vec3(p.x - rays[0].from.x, 0.0, p.z - rays[0].from.z).length())

        private class Ring(val rays: List<Lane>) {
            val struck = HashSet<Int>()
            val drained = HashMap<Int, Int>()
            val pushed = HashSet<Pair<Long, Long>>()
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            val feet = cast.caster.position()
            SignatureKit.sound(cast.level, feet, EKSounds.SIGNATURE_BLOODWAVE_SURGE.get(), 2f)
            return spawn(cast, feet, LIFE)
        }

        override fun tick(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val state = e.state as? Ring ?: Ring(rays(level, e.position(), reach(e.power))).also { e.state = it }
            val t = e.tickCount
            val end = endTick(e.power)
            if (t > end) return
            val from = e.position()
            if (t == end) SignatureKit.sound(level, from, EKSounds.SIGNATURE_BLOODWAVE_EBB.get(), 2f)
            val r = radius(t.toDouble())
            val box = AABB(from, from).inflate(r + DEPTH + 1, HEIGHT + 4, r + DEPTH + 1)
            for (target in level.getEntitiesOfClass(LivingEntity::class.java, box)) {
                if (target === e.caster || !target.isAlive || target.isSpectator) continue
                val p = target.position()
                val flat = Vec3(p.x - from.x, 0.0, p.z - from.z)
                if (abs(flat.length() - r) > DEPTH + target.bbWidth * 0.5 || !reachesAt(state.rays, p)) continue
                val h = p.y - groundAt(state.rays, p)
                if (h > HEIGHT || h < -2.0) continue
                touch(e, state, target, if (flat.lengthSqr() < 1.0e-4) Vec3(1.0, 0.0, 0.0) else flat.normalize())
            }
            if (t % 2 == 0 && SignatureShips.ships(level, from, r + 4).isNotEmpty()) {
                val n = ceil(2 * Math.PI * r).toInt()
                for (k in 0 until n) {
                    val a = k * 2 * Math.PI / n
                    val out = Vec3(cos(a), 0.0, sin(a))
                    val p = from.add(out.scale(r))
                    if (!reachesAt(state.rays, p)) continue
                    val g = groundAt(state.rays, p)
                    for (h in doubleArrayOf(0.5, 1.5)) {
                        for (block in SignatureShips.blocksNear(level, Vec3(p.x, g + h, p.z), 0.8)) {
                            if (state.pushed.add(block.ship.id to block.pos.asLong())) SignatureShips.push(block.ship, block.world, out.scale(SHIP_PUSH))
                        }
                    }
                }
            }
        }

        private fun touch(e: SignatureEntity, state: Ring, target: LivingEntity, out: Vec3) {
            val first = state.struck.add(target.id)
            SignatureKit.push(target, out.scale(CARRY))
            if (first) {
                SignatureKit.push(target, out.scale(CRASH).add(0.0, CRASH_UP, 0.0))
                SignatureKit.sound(e.level() as ServerLevel, target.position(), EKSounds.SIGNATURE_BLOODWAVE_CRASH.get(), 2f)
            }
            if (SpellContext.isUndead(target)) return
            val last = state.drained[target.id]
            if (!first && last != null && e.tickCount - last < SIP_EVERY) return
            state.drained[target.id] = e.tickCount
            val drank = drain(e.caster, target, HEAL_SHARE) {
                if (first) SignatureKit.hurt(e, target, EKDamageSources.BLOOD_DRAIN_KEY, HIT_MIN + (HIT_MAX - HIT_MIN) * e.power)
                else SignatureKit.hurtThrough(e, target, EKDamageSources.BLOOD_DRAIN_KEY, SIP)
            }
            if (drank && !first && e.tickCount % 20 < SIP_EVERY) {
                SignatureKit.sound(e.level() as ServerLevel, target.position(), EKSounds.SIGNATURE_BLOODWAVE_SIP.get())
            }
            val caster = e.caster
            if (drank && caster != null && caster.isAlive && (first || e.tickCount % 10 < SIP_EVERY)) {
                SpellVfx.stream(e.level() as ServerLevel, SignatureKit.middle(target), caster.position().add(0.0, caster.bbHeight * 0.6, 0.0), Element.BLOOD.color)
            }
        }
    }

    class Rift(val along: Double, val across: Double, val born: Int, val phase: Float)

    object YgannsGrasp : SignatureBehaviour() {
        const val LIFE = 195
        const val END = 140
        const val RIFT_EVERY = 4
        const val RIFT_LIFE = 30
        const val SEIZE_TICKS = 60
        const val BAND = 1.0
        const val HALF_WIDTH = 3.4
        private const val SEIZE_MIN = 16f
        private const val SEIZE_MAX = 28f
        private const val SIP = 4.5f
        private const val SIP_EVERY = 5
        private const val HEAL_SHARE = 0.5f
        private const val YANK = 0.7
        private const val DRAG = 0.14
        private const val SHIP_PULL = 1.1
        private const val SHIP_REACH = 5.0

        fun front(t: Int): Double = 2.0 + min(t, END) * 0.22

        fun rifts(seed: Int, t: Int): List<Rift> {
            if (t > END || t % RIFT_EVERY != 0) return emptyList()
            val r = Random(seed * 31 + t)
            val f = front(t)
            return listOf(-1.0, 0.0, 1.0).map { s ->
                Rift(f + r.nextDouble() * 0.8 - 0.4, s * HALF_WIDTH * 0.62 + (r.nextDouble() - 0.5) * 1.2, t, r.nextFloat() * 2000f)
            }
        }

        fun reaches(lane: Lane, p: Vec3, t: Int): Boolean =
            t <= END && lane.reaches(front(t)) && abs(lane.along(p) - front(t)) < BAND && abs(lane.across(p)) < HALF_WIDTH && abs(p.y - lane.centreY(front(t))) < REACH_UP

        private const val REACH_UP = 3.0

        private class Grasp(val lane: Lane) {
            val seized = HashMap<Int, Int>()
            val gripped = HashSet<Pair<Long, Long>>()
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            val feet = cast.caster.position()
            val dir = heading(cast)
            SignatureKit.sound(cast.level, feet, EKSounds.SIGNATURE_YGANNS_GRASP_CAST.get(), 1.6f)
            return spawn(cast, feet, LIFE) { point = feet.add(dir.scale(16.0)) }
        }

        override fun tick(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val state = e.state as? Grasp ?: Grasp(lane(level, e, front(END) + 2)).also { e.state = it }
            val lane = state.lane
            val t = e.tickCount
            if (t <= END && lane.reaches(front(t))) {
                val d = front(t)
                val box = AABB(lane.at(d, -HALF_WIDTH), lane.at(d, HALF_WIDTH)).inflate(BAND + 0.5, 4.0, BAND + 0.5)
                for (target in level.getEntitiesOfClass(LivingEntity::class.java, box)) {
                    if (target === e.caster || !target.isAlive || target.isSpectator || target.id in state.seized) continue
                    if (!reaches(lane, target.position(), t)) continue
                    state.seized[target.id] = t
                    SignatureKit.hurt(e, target, EKDamageSources.VOID_KEY, SEIZE_MIN + (SEIZE_MAX - SEIZE_MIN) * e.power)
                    SignatureKit.push(target, Vec3(0.0, -YANK, 0.0))
                    // Hide the poison particles so only the Abyss visuals show.
                    target.addEffect(MobEffectInstance(MobEffects.POISON, 140, 2, false, false, true), e.caster)
                    SignatureKit.sound(level, target.position(), EKSounds.SIGNATURE_YGANNS_GRASP_SEIZE.get(), 1.6f)
                }
                if (t % 2 == 0) {
                    var x = -HALF_WIDTH
                    while (x <= HALF_WIDTH) {
                        val p = lane.at(d, x)
                        val g = lane.ground(level, d, x)
                        var y = g + 0.5
                        while (y <= g + SHIP_REACH) {
                            val hit = SignatureShips.blockAt(level, Vec3(p.x, y, p.z))
                            if (hit != null) {
                                if (state.gripped.add(hit.ship.id to hit.pos.asLong())) SignatureShips.push(hit.ship, hit.world, Vec3(0.0, -SHIP_PULL, 0.0))
                                break
                            }
                            y += 0.5
                        }
                        x += 1.0
                    }
                }
            }
            if (t == END) SignatureKit.sound(level, lane.at(front(END), 0.0), EKSounds.SIGNATURE_YGANNS_GRASP_EBB.get(), 1.6f)
            val it = state.seized.entries.iterator()
            while (it.hasNext()) {
                val (id, at) = it.next()
                val target = level.getEntity(id) as? LivingEntity
                if (target == null || !target.isAlive || t - at > SEIZE_TICKS) { it.remove(); continue }
                if (SignatureKit.movable(target)) {
                    target.deltaMovement = Vec3(0.0, minOf(target.deltaMovement.y, 0.0) - DRAG, 0.0)
                    target.hurtMarked = true
                }
                target.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 10, 6, false, false), e.caster)
                if ((t - at) % SIP_EVERY == SIP_EVERY - 1 && !SpellContext.isUndead(target)) {
                    drain(e.caster, target, HEAL_SHARE) { SignatureKit.hurtThrough(e, target, EKDamageSources.BLOOD_DRAIN_KEY, SIP) }
                }
            }
        }

        override fun end(e: SignatureEntity) {
            (e.state as? Grasp)?.seized?.clear()
        }
    }

    /** [dists] is the path length at each point; [uc] is the texture offset. */
    class Vine(val points: List<Vec3>, val dists: List<Double>, val a0: Double, val uc: Double, val root: Boolean)

    object Fireweed : SignatureBehaviour() {
        const val LIFE = 125
        const val REACH = 30.0
        const val GROW_SPEED = 1.0
        const val IGNITE = 30
        const val FIRE_SPEED = 0.75
        const val FIRE_TAIL = 6.0
        const val ROOTS = 16
        const val VINES = 12
        const val TANGLE = 0.9
        const val ERUPT_R = 4.0
        private const val ERUPT_MIN = 14f
        private const val ERUPT_MAX = 22f
        private const val ERUPT_PUSH = 1.1
        private const val BURN_MIN = 18f
        private const val BURN_MAX = 28f
        private const val BURN_PULSE = 4f
        private const val BURN_EVERY = 5
        private const val TANGLED_BURN = 2f
        private const val BURN_SECONDS = 7
        private const val FLING = 0.45
        private const val STAND = 0.9

        /** Client and server both call this. */
        fun vines(level: Level, origin: Vec3, seed: Int): List<Vine> {
            val r = Random(seed)
            val out = ArrayList<Vine>()
            for ((count, offset, root) in listOf(Triple(ROOTS, 0.0, true), Triple(VINES, 0.5, false))) {
                for (k in 0 until count) {
                    val a0 = (k + offset) * 2 * Math.PI / count + (r.nextDouble() - 0.5) * 0.4
                    val wob = r.nextDouble() * 6
                    val uc = r.nextDouble()
                    val points = ArrayList<Vec3>(); val dists = ArrayList<Double>()
                    var prevY = origin.y; var prev: Vec3? = null
                    var t = 0.6
                    while (t <= REACH) {
                        val a = a0 + 0.22 * sin(t * 0.9 + wob)
                        val x = origin.x + cos(a) * t; val z = origin.z + sin(a) * t
                        val y = surfaceY(level, x, z, prevY + 1.6, 4.0) ?: break
                        if (abs(y - origin.y) > BAND) break
                        // Add two points at a step, so the vine runs down or up the block face.
                        if (prev != null && abs(y - prevY) > 0.05) {
                            val edge = prev.lerp(Vec3(x, 0.0, z), 0.5)
                            points += Vec3(edge.x, prevY + 0.02, edge.z); dists += t - 0.1
                            points += Vec3(edge.x, y + 0.02, edge.z); dists += t - 0.1
                        }
                        points += Vec3(x, y + 0.02, z); dists += t
                        prevY = y; prev = Vec3(x, 0.0, z)
                        t += 0.2
                    }
                    out += Vine(points, dists, a0, uc, root)
                }
            }
            return out
        }

        fun fire(age: Double): Double = (age - IGNITE) * FIRE_SPEED

        fun grown(age: Double): Double = min(age * GROW_SPEED, REACH)

        /** Client and server both call this. Returns how far out the vine tip that caught [feet] is, or null. */
        fun tangles(vines: List<Vine>, feet: Vec3, t: Int): Double? {
            if (t >= IGNITE) return null
            val tip = grown(t.toDouble())
            for (v in vines) for (i in v.points.indices) {
                val d = v.dists[i]
                if (d > tip || d < tip - 2 * GROW_SPEED || d < 1.2) continue
                val p = v.points[i]
                if (abs(p.x - feet.x) < TANGLE && abs(p.z - feet.z) < TANGLE && abs(p.y - feet.y) < 0.7) return d
            }
            return null
        }

        fun tangled(dist: Double, age: Double): Boolean = fire(age) - FIRE_TAIL < dist

        override fun start(cast: SignatureCast): SignatureEntity {
            val feet = cast.caster.position()
            SignatureKit.sound(cast.level, feet, EKSounds.SIGNATURE_FIREWEED_GROW.get(), 1.8f)
            return spawn(cast, feet, LIFE)
        }

        private class Tangle(val at: Vec3, val dist: Double)

        private class Burning(val vines: List<Vine>) {
            val burnt = HashMap<Int, Int>()
            val held = HashMap<Int, Tangle>()
        }

        override fun tick(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val state = e.state as? Burning ?: Burning(vines(level, e.position(), e.seed)).also { e.state = it }
            val t = e.tickCount
            val box = AABB(e.position(), e.position()).inflate(REACH + 1, 4.0, REACH + 1)
            if (t < IGNITE) entangle(e, level, state, box, t)
            holdTangled(e, level, state, t)
            if (t == IGNITE) erupt(e, level, state)
            if (t < IGNITE) return
            val front = fire(t.toDouble())
            if (front - FIRE_TAIL > REACH) return
            burnStanding(e, level, state, box, t, front)
        }

        private fun entangle(e: SignatureEntity, level: ServerLevel, state: Burning, box: AABB, t: Int) {
            for (target in level.getEntitiesOfClass(LivingEntity::class.java, box) { it.isAlive && !it.isSpectator && it !== e.caster }) {
                if (target.id in state.held || !target.onGround() || ally(e.caster, target) || !SignatureKit.movable(target)) continue
                val dist = tangles(state.vines, target.position(), t) ?: continue
                state.held[target.id] = Tangle(target.position(), dist)
                SignatureKit.sound(level, target.position(), EKSounds.SIGNATURE_FIREWEED_TANGLE.get(), 1.6f)
            }
        }

        private fun holdTangled(e: SignatureEntity, level: ServerLevel, state: Burning, t: Int) {
            val it = state.held.entries.iterator()
            while (it.hasNext()) {
                val (id, tangle) = it.next()
                val target = level.getEntity(id) as? LivingEntity
                if (target == null || !target.isAlive || !tangled(tangle.dist, t.toDouble())) { it.remove(); continue }
                if (target.position().distanceToSqr(tangle.at) > 0.04) target.teleportTo(tangle.at.x, tangle.at.y, tangle.at.z)
                target.deltaMovement = Vec3(0.0, minOf(target.deltaMovement.y, 0.0), 0.0)
                target.hurtMarked = true
                target.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 5, 9, false, false, false), e.caster)
            }
        }

        private fun erupt(e: SignatureEntity, level: ServerLevel, state: Burning) {
            SignatureKit.sound(level, e.position(), EKSounds.SIGNATURE_FIREWEED_ERUPT.get(), 2f)
            SignatureKit.particles(level, ParticleTypes.FLAME, e.position().add(0.0, 0.3, 0.0), 60, 1.2, 0.12)
            SignatureKit.particles(level, ParticleTypes.LAVA, e.position().add(0.0, 0.3, 0.0), 14, 1.0, 0.0)
            for (target in SignatureKit.creatures(e, e.position().add(0.0, 0.8, 0.0), ERUPT_R)) {
                val away = Vec3(target.x - e.x, 0.0, target.z - e.z).let { if (it.lengthSqr() < 1.0e-4) Vec3(1.0, 0.0, 0.0) else it.normalize() }
                // Set it alight before the hurt, so a kill drops cooked meat.
                target.setSecondsOnFire(BURN_SECONDS)
                SignatureKit.hurt(e, target, EKDamageSources.PYRE_KEY, ERUPT_MIN + (ERUPT_MAX - ERUPT_MIN) * e.power)
                if (target.id !in state.held) SignatureKit.push(target, away.scale(ERUPT_PUSH).add(0.0, 0.5, 0.0))
            }
        }

        private fun burnStanding(e: SignatureEntity, level: ServerLevel, state: Burning, box: AABB, t: Int, front: Double) {
            val standing = level.getEntitiesOfClass(LivingEntity::class.java, box) { it.isAlive && !it.isSpectator && it !== e.caster }
            for (target in standing) {
                val feet = target.position()
                val on = state.vines.any { v ->
                    v.points.indices.any { i ->
                        val d = v.dists[i]
                        d <= front && d > front - FIRE_TAIL && d > 0.8 && v.points[i].let { p -> abs(p.x - feet.x) < STAND && abs(p.z - feet.z) < STAND && abs(p.y - feet.y) < 0.7 }
                    }
                }
                if (!on) continue
                val held = target.id in state.held
                val times = if (held) TANGLED_BURN else 1f
                val last = state.burnt[target.id]
                // Set it alight before the hurt, so a kill drops cooked meat.
                target.setSecondsOnFire(BURN_SECONDS)
                if (last == null) {
                    state.burnt[target.id] = t
                    SignatureKit.hurt(e, target, EKDamageSources.PYRE_KEY, (BURN_MIN + (BURN_MAX - BURN_MIN) * e.power) * times)
                    if (!held) SignatureKit.push(target, Vec3(0.0, FLING, 0.0))
                    SignatureKit.sound(level, feet, EKSounds.SIGNATURE_FIREWEED_IGNITE.get(), 1.2f)
                } else if (t - last >= BURN_EVERY) {
                    state.burnt[target.id] = t
                    SignatureKit.hurtThrough(e, target, EKDamageSources.PYRE_KEY, BURN_PULSE * times)
                }
            }
        }
    }

    class Tile(val x: Int, val z: Int, val y: Double, val at: Int)

    class Shroom(val x: Int, val z: Int, val y: Double, val at: Int)

    class Sprout(val x: Int, val z: Int, val y: Double, val at: Int)

    class Cap(val x: Int, val z: Int, val y: Double, val born: Int, val pop: Int)

    class Bloom(val tiles: List<Tile>, val shrooms: List<Shroom>, val sprouts: List<Sprout>, val caps: List<Cap>)

    /** Bloom times are ticks after landing. [SignatureEntity.data]: flight ticks from [FLIGHT_SHIFT] up, and one burst bit for each cap below. */
    object Shroomspread : SignatureBehaviour() {
        const val LIFE = 340
        const val FADE_AT = LIFE - 40
        const val SPREAD_MIN = 8
        const val SPREAD_MAX = 14
        const val CAPS = 8
        const val CAP_STEP = 8
        const val FLIGHT_SHIFT = 8
        const val FLIGHT_SPEED = 1.4
        private const val RANGE = 52.0
        private const val DROP = 48.0
        private const val IMPACT_R = 2.8
        private const val IMPACT_MIN = 12f
        private const val IMPACT_MAX = 22f
        private const val TRIGGER = 2.5
        private const val BURST = 3.5
        private const val BURST_MIN = 18f
        private const val BURST_MAX = 30f
        private const val BURST_PUSH = 0.8
        const val LINGER = 100
        private const val LINGER_EVERY = 5
        private const val LINGER_HIT = 4f
        /** The most that the vanilla huge red mushroom feature can fill: a doubled stem of 6 plus its cap. */
        private const val HUGE_HEIGHT = 12
        private const val HUGE_RADIUS = 2
        private const val HUGE_CAP_FROM = 1
        private const val SPROUT_AFTER = 8
        private const val SHROOMS = 5
        private const val GROW_TICKS = 3.2
        /** Pairs of (waves round the edge, strength). */
        private val EDGE_WAVES = listOf(2 to 0.5, 3 to 0.35, 5 to 0.25, 7 to 0.15)
        /** [TENDRIL_REACH] is in units of the spread radius. */
        private const val TENDRILS_MIN = 3
        private const val TENDRIL_REACH = 1.45
        private const val TENDRIL_STEP = 0.7

        private fun blockNoise(x: Int, z: Int, seed: Int): Double {
            var h = x * 73856093 xor z * 19349663 xor seed * 83492791
            h = h xor (h ushr 13); h *= 0x5bd1e995; h = h xor (h ushr 15)
            return (h and 0xFFFF) / 65535.0
        }

        fun capSize(age: Int): Double = 0.35 + 0.12 * (maxOf(0, age) / CAP_STEP)

        fun flight(e: SignatureEntity): Int = e.data ushr FLIGHT_SHIFT

        /** Negative while the spore ball flies. */
        fun since(e: SignatureEntity): Int = e.tickCount - flight(e)

        /** Client and server both call this. */
        fun bloom(level: Level, point: Vec3, seed: Int, power: Float): Bloom {
            val r = Random(seed)
            val spread = SPREAD_MIN + ((SPREAD_MAX - SPREAD_MIN) * power + 0.5f).toInt()
            val cx = floor(point.x).toInt(); val cz = floor(point.z).toInt()
            val tiles = groundTiles(level, point, reachTimes(point, seed, spread, r))
            val spots = tiles.filter { abs(it.x - cx) + abs(it.z - cz) >= 1 }.toMutableList()
            fun take(): Tile = if (spots.isEmpty()) Tile(cx, cz, point.y, 0) else spots.removeAt(r.nextInt(spots.size))
            // Keep the huge mushrooms apart, so their caps do not meet.
            val shrooms = ArrayList<Shroom>()
            repeat(SHROOMS) { i ->
                val far = spots.filter { s -> shrooms.all { abs(it.x - s.x) > 4 || abs(it.z - s.z) > 4 } }
                if (far.isEmpty()) return@repeat
                val tile = far[r.nextInt(far.size)]
                spots.remove(tile)
                shrooms += Shroom(tile.x, tile.z, tile.y, maxOf(tile.at + SPROUT_AFTER, 22 + i * 20))
            }
            val sprouts = List(minOf(spots.size, 4 + 2 * spread)) { take().let { Sprout(it.x, it.z, it.y, it.at + SPROUT_AFTER + r.nextInt(14)) } }
            val caps = List(CAPS) { i -> take().let { Cap(it.x, it.z, it.y, 20 + i * 16, 64 + i * 20) } }
            return Bloom(tiles, shrooms, sprouts, caps)
        }

        /** Uses [r] before the rest of [bloom], so do not change the order of its draws. */
        private fun reachTimes(point: Vec3, seed: Int, spread: Int, r: Random): HashMap<Long, Int> {
            val cx = floor(point.x).toInt(); val cz = floor(point.z).toInt()
            val reached = HashMap<Long, Int>()
            fun reach(x: Int, z: Int, t: Int) {
                val key = BlockPos.asLong(x, 0, z)
                val was = reached[key]
                if (was == null || t < was) reached[key] = t
            }
            val phases = DoubleArray(EDGE_WAVES.size) { r.nextDouble() * 2 * Math.PI }
            fun edge(a: Double): Double {
                var n = 0.0
                for ((k, w) in EDGE_WAVES.withIndex()) n += w.second * sin(w.first * a + phases[k])
                return spread * (0.7 + 0.3 * n / EDGE_WAVES.sumOf { it.second })
            }
            val far = ceil(spread * TENDRIL_REACH).toInt() + 2
            for (x in cx - far..cx + far) for (z in cz - far..cz + far) {
                val dx = x + 0.5 - point.x; val dz = z + 0.5 - point.z
                val dist = Math.hypot(dx, dz)
                if (dist > edge(Math.atan2(dz, dx))) continue
                reach(x, z, (dist * GROW_TICKS * (0.8 + 0.4 * blockNoise(x, z, seed))).toInt())
            }
            repeat(TENDRILS_MIN + spread / 4) {
                var heading = r.nextDouble() * 2 * Math.PI
                val length = spread * (0.9 + (TENDRIL_REACH - 0.9) * r.nextDouble())
                var x = point.x; var z = point.z
                var walked = 0.0
                while (walked < length) {
                    heading += (r.nextDouble() - 0.5) * 0.7
                    x += cos(heading) * TENDRIL_STEP; z += sin(heading) * TENDRIL_STEP
                    walked += TENDRIL_STEP
                    val width = 0.4 + 0.9 * (1 - walked / length)
                    val w = ceil(width).toInt()
                    for (bx in floor(x).toInt() - w..floor(x).toInt() + w) for (bz in floor(z).toInt() - w..floor(z).toInt() + w) {
                        if (Math.hypot(bx + 0.5 - x, bz + 0.5 - z) > width) continue
                        reach(bx, bz, (walked * GROW_TICKS).toInt())
                    }
                }
            }
            reach(cx, cz, 0)
            return reached
        }

        private fun groundTiles(level: Level, point: Vec3, reached: HashMap<Long, Int>): List<Tile> {
            val due = LinkedHashMap<Long, Int>()
            for ((key, t) in reached.entries.sortedWith(compareBy({ it.value }, { it.key }))) due[key] = t
            val tiles = ArrayList<Tile>()
            for ((key, t0) in due) {
                val x = BlockPos.getX(key); val z = BlockPos.getZ(key)
                val y = surfaceY(level, x + 0.5, z + 0.5, point.y + BAND + 1.0, 2 * BAND + 1.0) ?: continue
                if (abs(y - point.y) > BAND) continue
                tiles += Tile(x, z, y, t0)
            }
            return tiles
        }

        fun groundPos(x: Int, z: Int, y: Double): BlockPos = BlockPos(x, floor(y - 0.01).toInt(), z)

        private fun takes(state: BlockState): Boolean =
            state.`is`(Blocks.GRASS_BLOCK) || state.`is`(Blocks.DIRT) || state.`is`(Blocks.COARSE_DIRT) || state.`is`(Blocks.ROOTED_DIRT) || state.`is`(Blocks.PODZOL)

        private class Spores(val bloom: Bloom) {
            /** -1 until the cap bursts. */
            val burst = IntArray(CAPS) { -1 }
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            val from = cast.origin
            val aimed = cast.aimed(RANGE)
            var at = aimed.point
            if (aimed.block == null && aimed.entity == null) surfaceY(cast.level, at.x, at.z, at.y, DROP)?.let { at = Vec3(at.x, it, at.z) }
            val flight = maxOf(1, ceil(from.distanceTo(at) / FLIGHT_SPEED).toInt())
            SignatureKit.sound(cast.level, from, EKSounds.SIGNATURE_SHROOMSPREAD_CAST.get(), 1.4f)
            return spawn(cast, from, flight + LIFE) {
                point = at
                data = flight shl FLIGHT_SHIFT
                deltaMovement = at.subtract(from).scale(1.0 / flight)
            }
        }

        override fun tick(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val t = since(e)
            if (t < 0) return
            if (t == 0) land(e, level)
            if (t == FADE_AT) SignatureKit.sound(level, e.point, EKSounds.SIGNATURE_SHROOMSPREAD_FADE.get(), 1.4f)
            val state = e.state as? Spores ?: Spores(bloom(level, e.point, e.seed, e.power)).also { e.state = it }
            val bloom = state.bloom
            for (tile in bloom.tiles) if (t == tile.at) {
                val pos = groundPos(tile.x, tile.z, tile.y)
                if (takes(level.getBlockState(pos))) mycelium(e, level, pos)
            }
            for (s in bloom.sprouts) if (t == s.at) sprout(e, level, groundPos(s.x, s.z, s.y).above())
            for (s in bloom.shrooms) if (t == s.at) grow(e, level, s)
            for ((i, cap) in bloom.caps.withIndex()) {
                val centre = Vec3(cap.x + 0.5, cap.y + capSize(t - cap.born) / 2, cap.z + 0.5)
                val burst = state.burst[i]
                if (burst < 0) {
                    if (t < cap.born + CAP_STEP) continue
                    val near = t >= cap.pop || SignatureKit.creatures(e, centre, TRIGGER).isNotEmpty()
                    if (!near) continue
                    state.burst[i] = t
                    e.data = e.data or (1 shl i)
                    SignatureKit.sound(level, centre, EKSounds.SIGNATURE_SHROOMSPREAD_BURST.get(), 2f)
                    for (target in SignatureKit.creatures(e, centre, BURST)) {
                        SignatureKit.hurt(e, target, EKDamageSources.ROT_KEY, BURST_MIN + (BURST_MAX - BURST_MIN) * e.power)
                        target.addEffect(MobEffectInstance(MobEffects.POISON, 160, 2), e.caster)
                        target.addEffect(MobEffectInstance(MobEffects.CONFUSION, 120, 0), e.caster)
                        val away = SignatureKit.middle(target).subtract(centre).let { if (it.lengthSqr() < 1.0e-4) Vec3(0.0, 1.0, 0.0) else it.normalize() }
                        SignatureKit.push(target, away.scale(BURST_PUSH).add(0.0, 0.3, 0.0))
                    }
                } else if (t - burst <= LINGER && (t - burst) % LINGER_EVERY == 0 && t > burst) {
                    for (target in SignatureKit.creatures(e, centre, BURST)) {
                        SignatureKit.hurtThrough(e, target, EKDamageSources.ROT_KEY, LINGER_HIT)
                        target.addEffect(MobEffectInstance(MobEffects.POISON, 80, 1), e.caster)
                    }
                }
            }
        }

        private fun land(e: SignatureEntity, level: ServerLevel) {
            e.deltaMovement = Vec3.ZERO
            e.setPos(e.point)
            SignatureKit.sound(level, e.point, EKSounds.SIGNATURE_SHROOMSPREAD_LAND.get(), 2f)
            SignatureKit.sound(level, e.point, EKSounds.SIGNATURE_SHROOMSPREAD_SPREAD.get(), 1.4f)
            for (target in SignatureKit.creatures(e, e.point.add(0.0, 0.5, 0.0), IMPACT_R)) {
                SignatureKit.hurt(e, target, EKDamageSources.ROT_KEY, IMPACT_MIN + (IMPACT_MAX - IMPACT_MIN) * e.power)
                target.addEffect(MobEffectInstance(MobEffects.POISON, 120, 1), e.caster)
            }
        }

        /** Use block updates, so plants and snow next to it react. */
        private fun mycelium(e: SignatureEntity, level: ServerLevel, pos: BlockPos) {
            val state = Block.updateFromNeighbourShapes(Blocks.MYCELIUM.defaultBlockState(), level, pos)
            if (!SignatureKit.setBlock(e, pos, state)) return
            level.updateNeighborsAt(pos, state.block)
            // Removing the foot of a tall plant also removes its top half.
            val above = pos.above()
            if (grassy(level.getBlockState(above))) SignatureKit.setBlock(e, above, Blocks.AIR.defaultBlockState())
        }

        private fun grassy(state: BlockState): Boolean =
            state.`is`(Blocks.GRASS) || state.`is`(Blocks.TALL_GRASS) || state.`is`(Blocks.FERN) || state.`is`(Blocks.LARGE_FERN) ||
                state.`is`(Blocks.DEAD_BUSH)

        private fun sprout(e: SignatureEntity, level: ServerLevel, pos: BlockPos): Boolean {
            val plant = Blocks.RED_MUSHROOM.defaultBlockState()
            if (!level.getBlockState(pos).isAir || !level.getBlockState(pos.below()).`is`(Blocks.MYCELIUM) || !plant.canSurvive(level, pos)) return false
            if (!SignatureKit.setBlock(e, pos, plant)) return false
            SignatureKit.sound(level, Vec3.atCenterOf(pos), EKSounds.SIGNATURE_SHROOMSPREAD_SPROUT.get())
            return true
        }

        /** Check every block of [hugeFootprint] before the vanilla feature grows, so claims hold. */
        private fun grow(e: SignatureEntity, level: ServerLevel, s: Shroom) {
            val pos = groundPos(s.x, s.z, s.y).above()
            if (!sprout(e, level, pos)) return
            val ctx = e.ctx ?: return
            val cap = Blocks.RED_MUSHROOM_BLOCK.defaultBlockState()
            if (!hugeFootprint(pos).all { ctx.mayChange(it, cap) }) return
            val plant = level.getBlockState(pos)
            val mushroom = plant.block as? MushroomBlock ?: return
            if (!mushroom.growMushroom(level, pos, plant, level.random)) return
            SignatureKit.sound(level, Vec3.atCenterOf(pos), EKSounds.SIGNATURE_SHROOMSPREAD_SHROOM.get(), 1.6f)
            SignatureKit.particles(level, ParticleTypes.HAPPY_VILLAGER, Vec3.atCenterOf(pos), 14, 0.6, 0.0)
        }

        private fun hugeFootprint(base: BlockPos): List<BlockPos> {
            val out = ArrayList<BlockPos>()
            for (y in 0..HUGE_HEIGHT) for (x in -HUGE_RADIUS..HUGE_RADIUS) for (z in -HUGE_RADIUS..HUGE_RADIUS) {
                if ((x != 0 || z != 0) && y < HUGE_CAP_FROM) continue
                out += base.offset(x, y, z)
            }
            return out
        }
    }

    class Planting(val at: Vec3, val born: Int)

    /** [SignatureEntity.data] is the bloom count, with [RELEASED] set after release; the client plants a bloom as the count goes up. */
    object GardenSanguine : SignatureBehaviour() {
        const val REACH = 32.0
        const val SPRAYS = 3
        /** [TURN] is in radians per tick. */
        const val SPLAY = 0.14
        const val TURN = 0.16
        const val PLANT_EVERY = 1
        const val MAX_BLOOMS = 160
        const val BLOOM_LIFE = 280
        const val BLOOM_R = 2.2
        const val RELEASED = 1 shl 16
        private const val HIT_EVERY = 5
        private const val SIP = 4f
        private const val SPLASH = 2.0
        private const val LINE_WIDTH = 0.5
        private const val PRESS = 0.08
        private const val HEAL_SHARE = 0.5f
        private const val CONTACT_EVERY = 5
        private const val CONTACT_SIP = 4f
        private const val CONTACT_HEAL_SHARE = 0.35f
        private const val REGEN_EVERY = 20
        private const val HOLD_LIFE = 20 * 60

        /** Client and server both call this. */
        fun sprays(aim: Vec3, age: Double): List<Vec3> {
            val a = aim.normalize()
            val s1 = a.cross(if (abs(a.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)).normalize()
            val s2 = a.cross(s1)
            return List(SPRAYS) { k ->
                val an = age * TURN + k * 2 * Math.PI / SPRAYS
                a.add(s1.scale(cos(an) * SPLAY)).add(s2.scale(sin(an) * SPLAY)).normalize()
            }
        }

        fun lands(level: Level, from: Vec3, dir: Vec3): Vec3 {
            val end = from.add(dir.scale(REACH))
            val hit = level.clipIncludeShips(colliderClip(from, end))
            return if (hit.type == HitResult.Type.BLOCK) hit.location else end
        }

        fun groundUnder(level: Level, point: Vec3): Vec3? =
            surfaceY(level, point.x, point.z, point.y + 1.0, 5.0)?.let { Vec3(point.x, it, point.z) }

        /** Client and server both call this. */
        fun patch(level: Level, ground: Vec3): List<BlockPos> {
            val centre = BlockPos.containing(ground.x, ground.y - 0.01, ground.z)
            if (abs(ground.y - (centre.y + 1)) > 0.02) return emptyList()
            val out = ArrayList<BlockPos>()
            for (dx in -1..1) for (dz in -1..1) {
                if (dx * dx + dz * dz == 2) continue
                val pos = centre.offset(dx, 0, dz)
                val top = surfaceY(level, pos.x + 0.5, pos.z + 0.5, ground.y + 0.5, 1.0) ?: continue
                if (abs(top - ground.y) < 0.02) out += pos
            }
            return out
        }

        /** Client and server both call this. */
        fun touches(at: Vec3, feet: Vec3): Boolean =
            feet.y - at.y in -1.2..1.6 && Vec3(feet.x - at.x, 0.0, feet.z - at.z).length() <= BLOOM_R

        private class Garden {
            val blooms = ArrayList<Planting>()
            var planted = 0
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            SignatureKit.sound(cast.level, cast.origin, EKSounds.SIGNATURE_GARDEN_SANGUINE_SPRAY_START.get(), 1.4f)
            return spawn(cast, cast.origin, HOLD_LIFE) {
                point = cast.aimed(REACH).point
                state = Garden()
            }
        }

        override fun held(e: SignatureEntity, cast: SignatureCast, held: Int) {
            val from = cast.origin
            e.setPos(from)
            e.deltaMovement = Vec3.ZERO
            e.life = e.tickCount + HOLD_LIFE
            e.point = cast.aimed(REACH).point
            val state = e.state as? Garden ?: Garden().also { e.state = it }
            val level = cast.level
            val dirs = sprays(cast.aim, e.tickCount.toDouble())
            val ends = dirs.map { lands(level, from, it) }
            if (held % HIT_EVERY == 0) {
                val struck = LinkedHashSet<LivingEntity>()
                for (end in ends) {
                    struck += SignatureKit.creaturesAlong(e, from, end, LINE_WIDTH)
                    struck += SignatureKit.creatures(e, end, SPLASH)
                }
                for (target in struck) {
                    if (ally(e.caster, target)) continue
                    SignatureKit.push(target, cast.aim.scale(PRESS))
                    if (SpellContext.isUndead(target)) continue
                    val drank = drain(e.caster, target, HEAL_SHARE) { SignatureKit.hurtThrough(e, target, EKDamageSources.BLOOD_DRAIN_KEY, SIP) }
                    if (drank && held % (HIT_EVERY * 2) == 0) SpellVfx.stream(level, SignatureKit.middle(target), from, Element.BLOOD.color)
                }
            }
            if (held % PLANT_EVERY == 0 && state.planted < MAX_BLOOMS) {
                val ground = groundUnder(level, ends[state.planted % SPRAYS])
                if (ground != null) {
                    state.blooms += Planting(ground, e.tickCount)
                    state.planted++
                    e.data = state.planted
                    for (pos in patch(level, ground)) {
                        if (level.getBlockState(pos).`is`(Blocks.NETHERRACK)) SignatureKit.setBlock(e, pos, Blocks.CRIMSON_NYLIUM.defaultBlockState())
                    }
                    if (state.planted % 8 == 1) SignatureKit.sound(level, ground, EKSounds.SIGNATURE_GARDEN_SANGUINE_BLOOM.get(), 1.3f)
                }
            }
        }

        override fun released(e: SignatureEntity, cast: SignatureCast, held: Int) {
            SignatureKit.sound(cast.level, e.position(), EKSounds.SIGNATURE_GARDEN_SANGUINE_SPRAY_STOP.get())
            val state = e.state as? Garden
            val last = state?.blooms?.maxOfOrNull { it.born }
            if (last == null) {
                end(e)
                e.discard()
                return
            }
            e.data = e.data or RELEASED
            e.life = last + BLOOM_LIFE
        }

        override fun end(e: SignatureEntity) {
            SignatureKit.sound(e.level() as ServerLevel, e.point, EKSounds.SIGNATURE_GARDEN_SANGUINE_WILT.get(), 1.4f)
        }

        override fun tick(e: SignatureEntity) {
            val state = e.state as? Garden ?: return
            state.blooms.removeAll { e.tickCount - it.born > BLOOM_LIFE }
            val contact = e.tickCount % CONTACT_EVERY == 0
            val regen = e.tickCount % REGEN_EVERY == 0
            if (!contact && !regen) return
            val level = e.level() as ServerLevel
            val drained = HashSet<Int>()
            for (bloom in state.blooms) {
                for (other in SignatureKit.creatures(level, bloom.at.add(0.0, 0.5, 0.0), BLOOM_R + 1.0, null)) {
                    if (!touches(bloom.at, other.position())) continue
                    if (ally(e.caster, other)) {
                        if (regen) other.addEffect(MobEffectInstance(MobEffects.REGENERATION, REGEN_EVERY * 3, 1), e.caster)
                        continue
                    }
                    // Drain once each pulse, however many blooms it stands in.
                    if (!contact || !drained.add(other.id)) continue
                    other.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, CONTACT_EVERY + 10, 2), e.caster)
                    if (SpellContext.isUndead(other)) continue
                    val drank = drain(e.caster, other, CONTACT_HEAL_SHARE) { SignatureKit.hurtThrough(e, other, EKDamageSources.BLOOD_DRAIN_KEY, CONTACT_SIP) }
                    if (!drank || e.tickCount % (CONTACT_EVERY * 2) != 0) continue
                    SpellVfx.stream(level, SignatureKit.middle(other), bloom.at.add(0.0, 0.2, 0.0), Element.BLOOD.color, 12)
                    SignatureKit.particles(level, DustParticleOptions(Vector3f(0.6f, 0.03f, 0.08f), 1.4f), SignatureKit.middle(other), 10, 0.3, 0.0)
                    SignatureKit.sound(level, other.position(), EKSounds.SIGNATURE_GARDEN_SANGUINE_DRAIN.get())
                    val caster = e.caster
                    if (caster != null && caster.isAlive) SpellVfx.stream(level, bloom.at.add(0.0, 0.3, 0.0), caster.position().add(0.0, caster.bbHeight * 0.6, 0.0), Element.BLOOD.color)
                }
            }
        }
    }
}
