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

/**
 * The waves and the spreading ground: Avalanche, Bloodwave, Ygann's Grasp, Fireweed,
 * Shroomspread and Garden Sanguine.
 */
object WaveSignatures {
    val ALL: Map<Signature, SignatureBehaviour> = mapOf(
        Signature.AVALANCHE to Avalanche,
        Signature.BLOODWAVE to Bloodwave,
        Signature.YGANNS_GRASP to YgannsGrasp,
        Signature.FIREWEED to Fireweed,
        Signature.SHROOMSPREAD to Shroomspread,
        Signature.GARDEN_SANGUINE to GardenSanguine,
    )

    // ---- Shared: the ground, a ship's deck included -----------------------------------------------

    /**
     * What's under [x], [z], searching down from [fromY] for [depth] blocks: the ground or a
     * ship's deck (its [BlockHitResult.getBlockPos] is then in the ship's yard). Null where there's
     * nothing, or where [fromY] is already inside a block (a wall, not ground). Either side may ask.
     */
    fun surface(level: Level, x: Double, z: Double, fromY: Double, depth: Double): BlockHitResult? {
        val from = Vec3(x, fromY, z)
        val hit = level.clipIncludeShips(ClipContext(from, Vec3(x, fromY - depth, z), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null))
        if (hit.type != HitResult.Type.BLOCK || hit.location.y >= fromY - 1.0e-3) return null
        return hit
    }

    /** The height of [surface], or null. */
    fun surfaceY(level: Level, x: Double, z: Double, fromY: Double, depth: Double): Double? = surface(level, x, z, fromY, depth)?.location?.y

    /** Level ground ahead of [caster]: the way they look, flattened, or the way they face when
     *  they look straight up or down. */
    private fun heading(cast: SignatureCast): Vec3 {
        val aim = cast.aim
        val flat = Vec3(aim.x, 0.0, aim.z)
        if (flat.lengthSqr() > 1.0e-4) return flat.normalize()
        val yaw = Math.toRadians(cast.caster.yRot.toDouble())
        return Vec3(-sin(yaw), 0.0, cos(yaw))
    }

    /**
     * A wave's lane: from [from] along the level [dir], with the ground's height along its middle
     * every [STEP] blocks, so a wave follows the ground down slopes and over steps. Built the same
     * on both sides from the entity's position and its [SignatureEntity.point] ([lane]), or a
     * direction ([ray]).
     */
    class Lane(val from: Vec3, val dir: Vec3, private val heights: DoubleArray, val end: Double = Double.POSITIVE_INFINITY) {
        val side: Vec3 = Vec3(-dir.z, 0.0, dir.x)

        /** Whether [s] blocks out is still on the lane: short of where the ground left [BAND]. */
        fun reaches(s: Double): Boolean = s <= end

        /** The ground's height along the middle, [s] blocks out. */
        fun centreY(s: Double): Double {
            val i = (s / STEP).coerceIn(0.0, heights.size - 1.0)
            val i0 = floor(i).toInt(); val i1 = min(i0 + 1, heights.size - 1)
            return heights[i0] + (heights[i1] - heights[i0]) * (i - i0)
        }

        /** [s] out and [across] to the side, at the middle's height. */
        fun at(s: Double, across: Double): Vec3 =
            Vec3(from.x + dir.x * s + side.x * across, centreY(s), from.z + dir.z * s + side.z * across)

        /** The ground (or deck) [s] out and [across] to the side. */
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

    /** The lane of a wave cast from [e]'s position toward its [SignatureEntity.point], [length] long. */
    fun lane(level: Level, e: SignatureEntity, length: Double): Lane {
        val from = e.position()
        val flat = Vec3(e.point.x - from.x, 0.0, e.point.z - from.z)
        val dir = if (flat.lengthSqr() < 1.0e-6) Vec3(1.0, 0.0, 0.0) else flat.normalize()
        return ray(level, from, dir, length)
    }

    /** A lane from [from] along the level unit [dir], [length] long. */
    fun ray(level: Level, from: Vec3, dir: Vec3, length: Double): Lane {
        val n = (length / Lane.STEP).toInt() + 2
        val heights = DoubleArray(n)
        var y = from.y
        var end = Double.POSITIVE_INFINITY
        for (i in 0 until n) {
            val s = i * Lane.STEP
            // Down slopes and up single steps, as snow or water would go; a wall is passed at the
            // height it was met. Where the ground climbs or drops out of [BAND], the lane ends.
            if (end.isInfinite()) {
                val ground = surfaceY(level, from.x + dir.x * s, from.z + dir.z * s, y + 1.6, 5.0) ?: y
                if (abs(ground - from.y) > BAND) end = maxOf(0.0, s - Lane.STEP) else y = ground
            }
            heights[i] = y
        }
        return Lane(from, dir, heights, end)
    }

    /** How far above or below where it was cast a wave may follow the ground: past that (a cliff
     *  up, a drop down) it stops. */
    const val BAND = 4.0

    /** Heals [caster] by [share] of what [hurt] took from [target]. */
    private fun drain(caster: LivingEntity?, target: LivingEntity, share: Float, hurt: () -> Boolean): Boolean {
        val before = target.health
        if (!hurt()) return false
        val taken = (before - target.health).coerceAtLeast(0f)
        if (caster != null && caster.isAlive && taken > 0f && !SpellContext.isUndead(caster)) caster.heal(taken * share)
        return true
    }

    /** The caster, and those on their side: their team, their pets, their thralls. */
    private fun ally(caster: LivingEntity?, other: LivingEntity): Boolean {
        if (caster == null) return false
        if (other === caster || other.isAlliedTo(caster)) return true
        if (other is TamableAnimal && other.isOwnedBy(caster)) return true
        return BloodPacts.isThrallOf(other, caster.uuid)
    }

    // ---- Avalanche: a slide of snow sections falling under their own weight ------------------

    /**
     * One section of an avalanche's slide: where its foot is (and was a tick ago, for drawing),
     * how fast it falls, how far it has come down since it set out, and whether it still runs.
     * Stepped the same on both sides by [Avalanche.step].
     */
    class Slab(val across: Double, val lat: Double, val seed: Int, x: Double, y: Double, z: Double) {
        var x = x; var y = y; var z = z
        var px = x; var py = y; var pz = z
        var vy = 0.0
        val startY = y
        /** How far it has come down, at most, since it set out. */
        var descent = 0.0
        /** Still sliding on (false once it has run its course or met a wall). */
        var running = true
        /** It came down hard onto the ground this tick. */
        var landed = false
        /** How far it has slid, for how far it has tumbled. */
        var rolled = 0.0

        /** Its half-size: bigger the further it has fallen. */
        val size: Double get() = min(Avalanche.SIZE_MAX, Avalanche.SIZE0 + Avalanche.GROW * descent)

        /** Its middle, [f] of the way from last tick to this one. */
        fun centre(f: Double = 1.0): Vec3 = Vec3(px + (x - px) * f, py + (y - py) * f + size, pz + (z - pz) * f)
    }

    /** An avalanche's slide: the way it runs, its sections, and how many ticks it has been stepped. */
    class Slide(val dir: Vec3, val slabs: List<Slab>) {
        val side: Vec3 = Vec3(-dir.z, 0.0, dir.x)
        var t = 0
    }

    /**
     * An avalanche of snow sliding out from the caster's feet the way they look, in sections
     * that each move under real gravity: each slides on along the ground, follows it down slopes,
     * falls off ledges accelerating as it drops, climbs single steps and stops at walls, and at
     * the edge of ground more than [CLIMB_MAX] above or [DROP_MAX] below where it set out. The
     * further a section has come down, the faster, bigger and harder it is. A section slams into
     * each creature it meets with cold for a hard hit, throwing it on and up, then grinds it with
     * more cold every [GRIND_EVERY] ticks it stays in it; it slows and chills it and heaps snow
     * round its feet. Snow settles where the sections pass. Each ship block a section meets gets
     * one push on, harder the further it has fallen.
     */
    object Avalanche : SignatureBehaviour() {
        const val SLABS = 11
        const val END_MIN = 56
        const val END_MAX = 116
        const val LIFE = END_MAX + 30
        const val FRONT0 = 1.2
        /** Its sliding speed, and how much faster per block it has come down, at most. */
        const val SPEED = 0.3
        const val ACCEL = 0.03
        const val SPEED_MAX = 0.8
        /** How far above where it set out a section may climb, and how far below it may fall:
         *  falling is the point of it, so that's generous. */
        const val CLIMB_MAX = 4.0
        const val DROP_MAX = 24.0
        /** Real gravity, blocks a tick per tick, and how high a step it climbs. */
        const val GRAVITY = 0.08
        const val STEP_UP = 1.1
        /** How far below a section it looks for ground to land on. */
        const val FALL_DEPTH = 48.0
        /** How fast the sections spread apart, per block they set out from the middle. */
        const val SPREAD = 0.03
        const val SIZE0 = 0.55
        const val GROW = 0.06
        const val SIZE_MAX = 1.6
        /** How much harder it hits and pushes per block it has come down, and up to how far. */
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
        /** Snow round a creature's feet, at most. */
        private const val BURY_LAYERS = 4
        /** The chance a running section leaves snow where it is, each tick. */
        private const val SETTLE = 0.35

        fun endTick(power: Float): Int = (END_MIN + (END_MAX - END_MIN) * power).toInt()

        /** How much harder a section that has come down [descent] blocks hits. */
        fun boost(descent: Double): Double = 1 + FALL_BOOST * min(descent, FALL_BOOST_MAX)

        /** The slide of a cast from [from] toward [toward] with [seed], before its first step;
         *  either side may ask. */
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

        /** One tick of [slide], cast at [power]: each section slides on and falls. */
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
                    // A wall, or ground climbing above [CLIMB_MAX] or dropping past [DROP_MAX]
                    // from where it set out: it stops at the edge.
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

        /** Its per-cast state: the slide, who it has struck, when it last ground each creature,
         *  and which ship blocks it has pushed (each once). */
        private class Sliding(val slide: Slide) {
            val ground = HashMap<Int, Int>()
            val pushed = HashSet<Pair<Long, Long>>()
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            val feet = cast.caster.position()
            val dir = heading(cast)
            SignatureKit.sound(cast.level, feet, SoundEvents.POWDER_SNOW_PLACE, 2f, 0.4f)
            SignatureKit.sound(cast.level, feet, SoundEvents.SNOW_BREAK, 2f, 0.5f)
            SignatureKit.sound(cast.level, feet, SoundEvents.GENERIC_EXPLODE, 0.8f, 0.6f)
            return spawn(cast, feet, LIFE) { point = feet.add(dir.scale(16.0)) }
        }

        override fun tick(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val state = e.state as? Sliding ?: Sliding(slide(level, e.position(), e.point, e.seed)).also { e.state = it }
            val slide = state.slide
            while (slide.t < e.tickCount) step(level, slide, e.power)
            val t = e.tickCount
            // Each creature is carried once a tick, however many sections it's in.
            val moved = HashSet<Int>()
            for (s in slide.slabs) {
                val centre = s.centre()
                val boost = boost(s.descent)
                if (s.landed) {
                    SignatureKit.sound(level, centre, SoundEvents.SNOW_BREAK, 2f, 0.4f)
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
                        SignatureKit.hurt(e, target, EKDamageSources.COLD_KEY, ((HIT_MIN + (HIT_MAX - HIT_MIN) * e.power) * boost).toFloat())
                        SignatureKit.push(target, slide.dir.scale(SLAM * boost).add(0.0, SLAM_UP, 0.0))
                        target.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, SLOW_TICKS, SLOW_LEVEL), e.caster)
                        SignatureKit.sound(level, target.position(), SoundEvents.SNOW_BREAK, 1.4f, 0.5f)
                        bury(e, target.blockPosition())
                    } else if (last != t && t - last >= GRIND_EVERY) {
                        state.ground[target.id] = t
                        SignatureKit.hurtThrough(e, target, EKDamageSources.COLD_KEY, (GRIND * boost).toFloat())
                    }
                }
                for (block in SignatureShips.blocksNear(level, centre, s.size + 0.3)) {
                    if (state.pushed.add(block.ship.id to block.pos.asLong())) SignatureShips.push(block.ship, block.world, slide.dir.scale(SHIP_PUSH * boost))
                }
                // Snow settles where it passes.
                if (s.vy == 0.0 && level.random.nextFloat() < SETTLE) {
                    val pos = BlockPos.containing(s.x, s.y + 0.01, s.z)
                    if (level.getBlockState(pos).isAir && Blocks.SNOW.defaultBlockState().canSurvive(level, pos)) SignatureKit.setBlock(e, pos, Blocks.SNOW.defaultBlockState())
                }
            }
            val lead = slide.slabs[SLABS / 2]
            if (lead.running && t % 4 == 0) SignatureKit.sound(level, lead.centre(), SoundEvents.POWDER_SNOW_BREAK, 2f, 0.4f + level.random.nextFloat() * 0.2f)
            if (lead.running && t % 9 == 0) SignatureKit.sound(level, lead.centre(), SoundEvents.SNOW_BREAK, 2f, 0.4f)
        }

        /** Heaps snow round [feet]: a layer more where there's snow, a first one where it can lie. */
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

    // ---- Bloodwave: a ring of dark blood rolling out from the caster -------------------------

    /**
     * A ring wave of dark blood rolling out from the caster's feet in every direction along the
     * ground, following it down slopes and over steps along each of its [RAYS] rays, further the
     * longer it was charged; a ray stops where the ground climbs or drops more than [BAND] from
     * where it was cast. It crashes into each creature it washes over, draining it hard and
     * throwing it outward, then drains it again every [SIP_EVERY] ticks as it carries it along;
     * the caster heals by part of all of it. Undead have no blood to give and are only swept
     * along. Each ship block it meets gets one hard push on, outward.
     */
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

        /** How tall the wave stands, in blocks over the ground, cast at [power]. */
        fun crest(power: Float): Double = 2.25 + 1.0 * power

        /** The ground along each of the ring's rays from [from], out to [reach]; either side may ask. */
        fun rays(level: Level, from: Vec3, reach: Double): List<Lane> =
            List(RAYS) { k -> val a = k * 2 * Math.PI / RAYS; ray(level, from, Vec3(cos(a), 0.0, sin(a)), reach) }

        /** The ray nearest [p]. */
        fun rayAt(rays: List<Lane>, p: Vec3): Lane {
            val from = rays[0].from
            val a = Math.atan2(p.z - from.z, p.x - from.x)
            return rays[Math.floorMod(Math.round(a / (2 * Math.PI) * RAYS).toInt(), RAYS)]
        }

        /** The ground under [p], from the ray nearest it. */
        fun groundAt(rays: List<Lane>, p: Vec3): Double =
            rayAt(rays, p).centreY(Vec3(p.x - rays[0].from.x, 0.0, p.z - rays[0].from.z).length())

        /** Whether the wave reaches [p] along its ray: short of where the ground left [BAND]. */
        fun reachesAt(rays: List<Lane>, p: Vec3): Boolean =
            rayAt(rays, p).reaches(Vec3(p.x - rays[0].from.x, 0.0, p.z - rays[0].from.z).length())

        private class Ring(val rays: List<Lane>) {
            val struck = HashSet<Int>()
            val drained = HashMap<Int, Int>()
            val pushed = HashSet<Pair<Long, Long>>()
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            val feet = cast.caster.position()
            SignatureKit.sound(cast.level, feet, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, 2f, 0.4f)
            SignatureKit.sound(cast.level, feet, SoundEvents.SOUL_ESCAPE, 2f, 0.5f)
            SignatureKit.sound(cast.level, feet, SoundEvents.WARDEN_HEARTBEAT, 2f, 0.6f)
            return spawn(cast, feet, LIFE)
        }

        override fun tick(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val state = e.state as? Ring ?: Ring(rays(level, e.position(), reach(e.power))).also { e.state = it }
            val t = e.tickCount
            if (t > endTick(e.power)) return
            val from = e.position()
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
            // Each ship block it meets, a push on at that block, once.
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
            if (t % 4 == 0) {
                val a = level.random.nextDouble() * 2 * Math.PI
                SignatureKit.sound(level, from.add(cos(a) * r, 0.5, sin(a) * r), SoundEvents.GENERIC_SPLASH, 2f, 0.4f + level.random.nextFloat() * 0.2f)
            }
            if (t % 10 == 0) SignatureKit.sound(level, from, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, 2f, 0.45f)
        }

        private fun touch(e: SignatureEntity, state: Ring, target: LivingEntity, out: Vec3) {
            val first = state.struck.add(target.id)
            SignatureKit.push(target, out.scale(CARRY))
            if (first) SignatureKit.push(target, out.scale(CRASH).add(0.0, CRASH_UP, 0.0))
            if (SpellContext.isUndead(target)) return
            val last = state.drained[target.id]
            if (!first && last != null && e.tickCount - last < SIP_EVERY) return
            state.drained[target.id] = e.tickCount
            val drank = drain(e.caster, target, HEAL_SHARE) {
                if (first) SignatureKit.hurt(e, target, EKDamageSources.BLOOD_DRAIN_KEY, HIT_MIN + (HIT_MAX - HIT_MIN) * e.power)
                else SignatureKit.hurtThrough(e, target, EKDamageSources.BLOOD_DRAIN_KEY, SIP)
            }
            val caster = e.caster
            if (drank && caster != null && caster.isAlive && (first || e.tickCount % 10 < SIP_EVERY)) {
                SpellVfx.stream(e.level() as ServerLevel, SignatureKit.middle(target), caster.position().add(0.0, caster.bbHeight * 0.6, 0.0), Element.BLOOD.color)
            }
        }
    }

    // ---- Ygann's Grasp: rifts racing along the ground, tentacles rising from them --------------

    /** One rift the front opens: where along and across the lane, when, and its tentacle's turn. */
    class Rift(val along: Double, val across: Double, val born: Int, val phase: Float)

    /**
     * A front racing out along the ground from the caster's feet, tearing open three violet rifts
     * every [RIFT_EVERY] ticks with an Ygann tentacle bursting up from each and sinking back.
     * Every creature the front reaches is seized: struck hard, yanked down and held fast,
     * poisoned (with none of poison's particles), and drained every [SIP_EVERY] ticks for the
     * caster. Where it passes under a
     * hull, tentacles grip each block of it they reach and wrench it down there, once.
     */
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
        /** The yank down as it seizes, and the drag while it holds. */
        private const val YANK = 0.7
        private const val DRAG = 0.14
        private const val SHIP_PULL = 1.1
        private const val SHIP_REACH = 5.0

        fun front(t: Int): Double = 2.0 + min(t, END) * 0.22

        /** The rifts the front opens at tick [t] of a cast with [seed]: three across it. */
        fun rifts(seed: Int, t: Int): List<Rift> {
            if (t > END || t % RIFT_EVERY != 0) return emptyList()
            val r = Random(seed * 31 + t)
            val f = front(t)
            return listOf(-1.0, 0.0, 1.0).map { s ->
                Rift(f + r.nextDouble() * 0.8 - 0.4, s * HALF_WIDTH * 0.62 + (r.nextDouble() - 0.5) * 1.2, t, r.nextFloat() * 2000f)
            }
        }

        /** Whether a creature at [p] is in the front's reach at tick [t]. */
        fun reaches(lane: Lane, p: Vec3, t: Int): Boolean =
            t <= END && lane.reaches(front(t)) && abs(lane.along(p) - front(t)) < BAND && abs(lane.across(p)) < HALF_WIDTH && abs(p.y - lane.centreY(front(t))) < REACH_UP

        /** How far above the lane's ground a creature can be and still be reached. */
        private const val REACH_UP = 3.0

        private class Grasp(val lane: Lane) {
            val seized = HashMap<Int, Int>()
            val gripped = HashSet<Pair<Long, Long>>()
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            val feet = cast.caster.position()
            val dir = heading(cast)
            SignatureKit.sound(cast.level, feet, SoundEvents.PORTAL_TRIGGER, 1f, 0.4f)
            SignatureKit.sound(cast.level, feet, SoundEvents.WARDEN_SONIC_CHARGE, 1.6f, 0.5f)
            SignatureKit.sound(cast.level, feet, SoundEvents.WARDEN_EMERGE, 1.2f, 0.7f)
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
                    // Poisoned, without poison's green swirls: the grasp shows only the Abyss.
                    target.addEffect(MobEffectInstance(MobEffects.POISON, 140, 2, false, false, true), e.caster)
                    SignatureKit.sound(level, target.position(), SoundEvents.SCULK_BLOCK_SPREAD, 1.6f, 0.5f)
                    SignatureKit.sound(level, target.position(), SoundEvents.EVOKER_FANGS_ATTACK, 1.2f, 0.6f)
                }
                // Under a hull, tentacles grip each block they reach and pull it down.
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
                if (t % RIFT_EVERY == 0) SignatureKit.sound(level, lane.at(d, 0.0), SoundEvents.SCULK_CATALYST_BLOOM, 1.6f, 0.45f + level.random.nextFloat() * 0.3f)
            }
            // The seized are held and dragged down, drained a little at a time.
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

    // ---- Fireweed: vines creep out and fire runs along them --------------------------------

    /** One vine or mangrove root creeping out over the ground: its points, how far along each is,
     *  the angle it set out at, where in its texture it runs, and whether it's a root. */
    class Vine(val points: List<Vec3>, val dists: List<Double>, val a0: Double, val uc: Double, val root: Boolean)

    /**
     * Vines and mangrove roots creep out over the ground and decks from the caster's feet for
     * [IGNITE] ticks, as a Growth area cast lays them, each stopping where the ground climbs or
     * drops more than [BAND] from the caster's feet. An opponent standing on the ground that
     * they reach as they creep is entangled: held fast where it stands until the fire has passed
     * it ([tangled]). Then fire erupts at their feet, blasting back whatever stands close, and
     * races out along them at [FIRE_SPEED]; whatever stands on a burning stretch is struck hard
     * by heat and flung up burning (an entangled creature is held down instead), and burnt again
     * every [BURN_EVERY] ticks it stays on one. Fire hurts the entangled twice as much. All of
     * it is fire damage (heat, which is tagged as fire) dealt to a creature already alight, so
     * what it kills drops its meat cooked. Nothing is left behind. Ships aren't pushed.
     */
    object Fireweed : SignatureBehaviour() {
        const val LIFE = 125
        const val REACH = 30.0
        const val GROW_SPEED = 1.0
        const val IGNITE = 30
        const val FIRE_SPEED = 0.75
        /** How far behind the fire's front a vine still burns. */
        const val FIRE_TAIL = 6.0
        const val ROOTS = 16
        const val VINES = 12
        /** How near a creeping vine's tip must come to a creature's feet to entangle it. */
        const val TANGLE = 0.9
        /** The eruption at the caster's feet as it catches: how far it reaches, how hard. */
        const val ERUPT_R = 4.0
        private const val ERUPT_MIN = 14f
        private const val ERUPT_MAX = 22f
        private const val ERUPT_PUSH = 1.1
        private const val BURN_MIN = 18f
        private const val BURN_MAX = 28f
        private const val BURN_PULSE = 4f
        private const val BURN_EVERY = 5
        /** How much more fire hurts an entangled creature. */
        private const val TANGLED_BURN = 2f
        private const val BURN_SECONDS = 7
        private const val FLING = 0.45
        private const val STAND = 0.9

        /** The vines and roots of a cast from [origin] with [seed]; either side may ask. */
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
                        // It creeps no further up a cliff or down a drop than [BAND].
                        if (abs(y - origin.y) > BAND) break
                        // Where the ground steps, it runs down or up the block's face.
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

        /** How far out the fire has run at [age] ticks. */
        fun fire(age: Double): Double = (age - IGNITE) * FIRE_SPEED

        /** How far out the vines have crept at [age] ticks. */
        fun grown(age: Double): Double = min(age * GROW_SPEED, REACH)

        /**
         * Where along the vines a creature on the ground at [feet] is caught by them at tick [t],
         * as they creep: how far out the vine's tip that reached it is, or null. Either side may ask.
         */
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

        /** Whether a creature caught [dist] out is still held at [age]: until the fire's burning
         *  stretch has passed it. */
        fun tangled(dist: Double, age: Double): Boolean = fire(age) - FIRE_TAIL < dist

        override fun start(cast: SignatureCast): SignatureEntity {
            val feet = cast.caster.position()
            SignatureKit.sound(cast.level, feet, SoundEvents.ROOTED_DIRT_BREAK, 1.8f, 0.6f)
            SignatureKit.sound(cast.level, feet, SoundEvents.BIG_DRIPLEAF_TILT_DOWN, 1.4f, 0.5f)
            return spawn(cast, feet, LIFE)
        }

        /** One creature the vines hold: where it was caught, and how far out along them. */
        private class Tangle(val at: Vec3, val dist: Double)

        /** A cast's vines, when the fire last burnt each creature, and whom the vines hold (by id). */
        private class Burning(val vines: List<Vine>) {
            val burnt = HashMap<Int, Int>()
            val held = HashMap<Int, Tangle>()
        }

        override fun tick(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val state = e.state as? Burning ?: Burning(vines(level, e.position(), e.seed)).also { e.state = it }
            val vines = state.vines
            val t = e.tickCount
            val box = AABB(e.position(), e.position()).inflate(REACH + 1, 4.0, REACH + 1)
            if (t < IGNITE) {
                // The creeping vines catch opponents standing on the ground.
                for (target in level.getEntitiesOfClass(LivingEntity::class.java, box) { it.isAlive && !it.isSpectator && it !== e.caster }) {
                    if (target.id in state.held || !target.onGround() || ally(e.caster, target) || !SignatureKit.movable(target)) continue
                    val dist = tangles(vines, target.position(), t) ?: continue
                    state.held[target.id] = Tangle(target.position(), dist)
                    SignatureKit.sound(level, target.position(), SoundEvents.ROOTED_DIRT_PLACE, 1.6f, 0.6f)
                    SignatureKit.sound(level, target.position(), SoundEvents.CAVE_VINES_PLACE, 1.6f, 0.6f)
                }
            }
            // The entangled are held fast where they were caught.
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
            if (t == IGNITE) {
                // It catches at the caster's feet with a blast that throws back whatever's close.
                SignatureKit.sound(level, e.position(), SoundEvents.FIRECHARGE_USE, 2f, 0.5f)
                SignatureKit.sound(level, e.position(), SoundEvents.BLAZE_SHOOT, 1.6f, 0.5f)
                SignatureKit.particles(level, ParticleTypes.FLAME, e.position().add(0.0, 0.3, 0.0), 60, 1.2, 0.12)
                SignatureKit.particles(level, ParticleTypes.LAVA, e.position().add(0.0, 0.3, 0.0), 14, 1.0, 0.0)
                for (target in SignatureKit.creatures(e, e.position().add(0.0, 0.8, 0.0), ERUPT_R)) {
                    val away = Vec3(target.x - e.x, 0.0, target.z - e.z).let { if (it.lengthSqr() < 1.0e-4) Vec3(1.0, 0.0, 0.0) else it.normalize() }
                    // Alight before it's hurt, so what the fire kills drops its meat cooked.
                    target.setSecondsOnFire(BURN_SECONDS)
                    SignatureKit.hurt(e, target, EKDamageSources.HEAT_KEY, ERUPT_MIN + (ERUPT_MAX - ERUPT_MIN) * e.power)
                    if (target.id !in state.held) SignatureKit.push(target, away.scale(ERUPT_PUSH).add(0.0, 0.5, 0.0))
                }
            }
            if (t < IGNITE) return
            val front = fire(t.toDouble())
            if (front - FIRE_TAIL > REACH) return
            // Whatever stands on a burning stretch of vine catches.
            val standing = level.getEntitiesOfClass(LivingEntity::class.java, box) { it.isAlive && !it.isSpectator && it !== e.caster }
            for (target in standing) {
                val feet = target.position()
                val on = vines.any { v ->
                    v.points.indices.any { i ->
                        val d = v.dists[i]
                        d <= front && d > front - FIRE_TAIL && d > 0.8 && v.points[i].let { p -> abs(p.x - feet.x) < STAND && abs(p.z - feet.z) < STAND && abs(p.y - feet.y) < 0.7 }
                    }
                }
                if (!on) continue
                val held = target.id in state.held
                val times = if (held) TANGLED_BURN else 1f
                val last = state.burnt[target.id]
                // Alight before it's hurt, so what the fire kills drops its meat cooked.
                target.setSecondsOnFire(BURN_SECONDS)
                if (last == null) {
                    state.burnt[target.id] = t
                    SignatureKit.hurt(e, target, EKDamageSources.HEAT_KEY, (BURN_MIN + (BURN_MAX - BURN_MIN) * e.power) * times)
                    if (!held) SignatureKit.push(target, Vec3(0.0, FLING, 0.0))
                    SignatureKit.sound(level, feet, SoundEvents.FIRECHARGE_USE, 1.2f, 0.8f)
                } else if (t - last >= BURN_EVERY) {
                    state.burnt[target.id] = t
                    SignatureKit.hurtThrough(e, target, EKDamageSources.HEAT_KEY, BURN_PULSE * times)
                }
            }
            if (t % 6 == 0) SignatureKit.sound(level, e.position(), SoundEvents.FIRE_AMBIENT, 2f, 0.6f + level.random.nextFloat() * 0.3f)
            if (t % 10 == 0) SignatureKit.sound(level, e.position(), SoundEvents.BLAZE_BURN, 1.4f, 0.5f)
        }
    }

    // ---- Shroomspread: a spore ball, real mycelium, red mushrooms and bursting spore caps -----

    /** One block of mycelium: its column, the top it lies on, and when it spreads there. */
    class Tile(val x: Int, val z: Int, val y: Double, val at: Int)

    /** A red mushroom forced to grow huge: where it stands, and when it springs up and grows. */
    class Shroom(val x: Int, val z: Int, val y: Double, val at: Int)

    /** A red mushroom springing up on the mycelium: where, and when. */
    class Sprout(val x: Int, val z: Int, val y: Double, val at: Int)

    /** A spore cap: where, when it starts to grow, and when it bursts if nothing comes near first. */
    class Cap(val x: Int, val z: Int, val y: Double, val born: Int, val pop: Int)

    class Bloom(val tiles: List<Tile>, val shrooms: List<Shroom>, val sprouts: List<Sprout>, val caps: List<Cap>)

    /**
     * A ball of spores flies from the hand to where it's aimed (falling to the ground below when
     * it's aimed at the open air) and bursts there in a choking cloud. From there mycelium spreads
     * block by block in a blotchy, uneven patch with tendrils fingering out past its edge, out to
     * about [SPREAD_MIN]..[SPREAD_MAX] blocks with the charge: grass and dirt it
     * reaches really become mycelium (with block updates round it, clearing the grass and ferns
     * on top), red mushrooms spring up on it, and a few are forced to grow, as bone meal grows
     * them, into huge red mushrooms where there's room for one. Blocky brown spore caps swell
     * among them a step at a time and burst into poison when a creature comes near, or when
     * they're full grown, striking hard and throwing it back; the cloud hangs for [LINGER] ticks,
     * choking whatever stays in it. Every block change is the caster's to make (claims apply),
     * and they stay.
     *
     * Times in the bloom are ticks after the ball lands. How long it flies is in the bits of
     * [SignatureEntity.data] from [FLIGHT_SHIFT] up; which caps have burst is in the bits below,
     * one each, for the client to burst too.
     */
    object Shroomspread : SignatureBehaviour() {
        const val LIFE = 340
        const val SPREAD_MIN = 8
        const val SPREAD_MAX = 14
        const val CAPS = 8
        const val CAP_STEP = 8
        const val FLIGHT_SHIFT = 8
        /** How fast the spore ball flies, in blocks a tick. */
        const val FLIGHT_SPEED = 1.4
        private const val RANGE = 52.0
        /** How far below an aim at the open air the ball looks for ground to fall to. */
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
        /** The most a huge red mushroom grown by vanilla's feature can fill: its tallest
         *  (a doubled stem of 6, with its cap on top), its cap's reach, and the lowest the cap
         *  hangs (its shortest stem of 4, less 3). */
        private const val HUGE_HEIGHT = 12
        private const val HUGE_RADIUS = 2
        private const val HUGE_CAP_FROM = 1
        /** How long after a block turns to mycelium a red mushroom may spring up on it. */
        private const val SPROUT_AFTER = 8
        /** How many red mushrooms are forced to grow huge, at most. */
        private const val SHROOMS = 5
        /** How many ticks the mycelium takes to spread a block. */
        private const val GROW_TICKS = 3.2
        /** The waves round its edge that make it blotchy: how many times each goes round, and how strong. */
        private val EDGE_WAVES = listOf(2 to 0.5, 3 to 0.35, 5 to 0.25, 7 to 0.15)
        /** Its tendrils: how many at least, how far past the edge they can reach (in spreads), and their stride. */
        private const val TENDRILS_MIN = 3
        private const val TENDRIL_REACH = 1.45
        private const val TENDRIL_STEP = 0.7

        /** A steady 0..1 for the block column [x], [z] of a cast with [seed]. */
        private fun blockNoise(x: Int, z: Int, seed: Int): Double {
            var h = x * 73856093 xor z * 19349663 xor seed * 83492791
            h = h xor (h ushr 13); h *= 0x5bd1e995; h = h xor (h ushr 15)
            return (h and 0xFFFF) / 65535.0
        }

        /** How big a cap is at [age] ticks: a step bigger every [CAP_STEP] ticks. */
        fun capSize(age: Int): Double = 0.35 + 0.12 * (maxOf(0, age) / CAP_STEP)

        /** How many ticks [e]'s spore ball flies before it lands. */
        fun flight(e: SignatureEntity): Int = e.data ushr FLIGHT_SHIFT

        /** Ticks since [e]'s spore ball landed (negative while it flies). */
        fun since(e: SignatureEntity): Int = e.tickCount - flight(e)

        /** The bloom of a ball landed at [point] with [seed], cast at [power]; either side may ask. */
        fun bloom(level: Level, point: Vec3, seed: Int, power: Float): Bloom {
            val r = Random(seed)
            val spread = SPREAD_MIN + ((SPREAD_MAX - SPREAD_MIN) * power + 0.5f).toInt()
            val cx = floor(point.x).toInt(); val cz = floor(point.z).toInt()
            val reached = HashMap<Long, Int>()
            fun reach(x: Int, z: Int, t: Int) {
                val key = BlockPos.asLong(x, 0, z)
                val was = reached[key]
                if (was == null || t < was) reached[key] = t
            }
            // A blotchy edge: how far it reaches each way, the sum of a few waves round it.
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
                // Each block takes a little sooner or later than its distance alone would say.
                reach(x, z, (dist * GROW_TICKS * (0.8 + 0.4 * blockNoise(x, z, seed))).toInt())
            }
            // Tendrils fingering out past the edge, wandering, thinning to their tips.
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
            val due = LinkedHashMap<Long, Int>()
            for ((key, t) in reached.entries.sortedWith(compareBy({ it.value }, { it.key }))) due[key] = t
            val tiles = ArrayList<Tile>()
            for ((key, t0) in due) {
                val x = BlockPos.getX(key); val z = BlockPos.getZ(key)
                // Only ground within [BAND] of where it landed: none up a cliff or down a drop.
                val y = surfaceY(level, x + 0.5, z + 0.5, point.y + BAND + 1.0, 2 * BAND + 1.0) ?: continue
                if (abs(y - point.y) > BAND) continue
                tiles += Tile(x, z, y, t0)
            }
            val spots = tiles.filter { abs(it.x - cx) + abs(it.z - cz) >= 1 }.toMutableList()
            fun take(): Tile = if (spots.isEmpty()) Tile(cx, cz, point.y, 0) else spots.removeAt(r.nextInt(spots.size))
            // The huge mushrooms stand apart: far enough out that their caps don't meet.
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

        /** The ground block under a tile, which the mycelium turns. */
        fun groundPos(x: Int, z: Int, y: Double): BlockPos = BlockPos(x, floor(y - 0.01).toInt(), z)

        /** Ground the mycelium can take. */
        private fun takes(state: BlockState): Boolean =
            state.`is`(Blocks.GRASS_BLOCK) || state.`is`(Blocks.DIRT) || state.`is`(Blocks.COARSE_DIRT) || state.`is`(Blocks.ROOTED_DIRT) || state.`is`(Blocks.PODZOL)

        private class Spores(val bloom: Bloom) {
            /** When each cap burst, or -1. */
            val burst = IntArray(CAPS) { -1 }
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            val from = cast.origin
            val aimed = cast.aimed(RANGE)
            var at = aimed.point
            // Into the open air, it falls to the ground below.
            if (aimed.block == null && aimed.entity == null) surfaceY(cast.level, at.x, at.z, at.y, DROP)?.let { at = Vec3(at.x, it, at.z) }
            val flight = maxOf(1, ceil(from.distanceTo(at) / FLIGHT_SPEED).toInt())
            SignatureKit.sound(cast.level, from, SoundEvents.LLAMA_SPIT, 1.4f, 0.5f)
            SignatureKit.sound(cast.level, from, SoundEvents.ROOTED_DIRT_BREAK, 1.2f, 0.6f)
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
            val state = e.state as? Spores ?: Spores(bloom(level, e.point, e.seed, e.power)).also { e.state = it }
            val bloom = state.bloom
            // The mycelium spreads over real ground, and red mushrooms spring up on it.
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
                    SignatureKit.sound(level, centre, SoundEvents.SLIME_BLOCK_BREAK, 2f, 0.5f)
                    SignatureKit.sound(level, centre, SoundEvents.PUFFER_FISH_BLOW_UP, 1.6f, 0.5f)
                    for (target in SignatureKit.creatures(e, centre, BURST)) {
                        SignatureKit.hurt(e, target, EKDamageSources.ROT_KEY, BURST_MIN + (BURST_MAX - BURST_MIN) * e.power)
                        target.addEffect(MobEffectInstance(MobEffects.POISON, 160, 2), e.caster)
                        target.addEffect(MobEffectInstance(MobEffects.CONFUSION, 120, 0), e.caster)
                        val away = SignatureKit.middle(target).subtract(centre).let { if (it.lengthSqr() < 1.0e-4) Vec3(0.0, 1.0, 0.0) else it.normalize() }
                        SignatureKit.push(target, away.scale(BURST_PUSH).add(0.0, 0.3, 0.0))
                    }
                } else if (t - burst <= LINGER && (t - burst) % LINGER_EVERY == 0 && t > burst) {
                    // The spores hang a while where it burst, choking whatever stays in them.
                    for (target in SignatureKit.creatures(e, centre, BURST)) {
                        SignatureKit.hurtThrough(e, target, EKDamageSources.ROT_KEY, LINGER_HIT)
                        target.addEffect(MobEffectInstance(MobEffects.POISON, 80, 1), e.caster)
                    }
                }
            }
            if (t % 8 == 0 && t < 50) SignatureKit.sound(level, e.point, SoundEvents.NYLIUM_PLACE, 1.4f, 0.6f)
        }

        /** The ball lands: it bursts in a cloud that strikes and poisons whatever is close. */
        private fun land(e: SignatureEntity, level: ServerLevel) {
            e.deltaMovement = Vec3.ZERO
            e.setPos(e.point)
            SignatureKit.sound(level, e.point, SoundEvents.SLIME_BLOCK_BREAK, 2f, 0.4f)
            SignatureKit.sound(level, e.point, SoundEvents.ROOTED_DIRT_BREAK, 2f, 0.5f)
            for (target in SignatureKit.creatures(e, e.point.add(0.0, 0.5, 0.0), IMPACT_R)) {
                SignatureKit.hurt(e, target, EKDamageSources.ROT_KEY, IMPACT_MIN + (IMPACT_MAX - IMPACT_MIN) * e.power)
                target.addEffect(MobEffectInstance(MobEffects.POISON, 120, 1), e.caster)
            }
        }

        /**
         * Turns [pos] to mycelium, snowy where snow lies on it as placed mycelium would be, and
         * tells the blocks round it (the change is made with block updates, so plants, snow and
         * the like next to it react). Grass, ferns and dead bushes on it are cleared away.
         */
        private fun mycelium(e: SignatureEntity, level: ServerLevel, pos: BlockPos) {
            val state = Block.updateFromNeighbourShapes(Blocks.MYCELIUM.defaultBlockState(), level, pos)
            if (!SignatureKit.setBlock(e, pos, state)) return
            level.updateNeighborsAt(pos, state.block)
            // Grass and grassy plants on it die back; a tall one's top half goes with its foot.
            val above = pos.above()
            if (grassy(level.getBlockState(above))) SignatureKit.setBlock(e, above, Blocks.AIR.defaultBlockState())
        }

        /** Grass and grassy plants, which the mycelium clears from the top of what it takes. */
        private fun grassy(state: BlockState): Boolean =
            state.`is`(Blocks.GRASS) || state.`is`(Blocks.TALL_GRASS) || state.`is`(Blocks.FERN) || state.`is`(Blocks.LARGE_FERN) ||
                state.`is`(Blocks.DEAD_BUSH)

        /** A red mushroom springs up at [pos], on mycelium. */
        private fun sprout(e: SignatureEntity, level: ServerLevel, pos: BlockPos): Boolean {
            val plant = Blocks.RED_MUSHROOM.defaultBlockState()
            if (!level.getBlockState(pos).isAir || !level.getBlockState(pos.below()).`is`(Blocks.MYCELIUM) || !plant.canSurvive(level, pos)) return false
            if (!SignatureKit.setBlock(e, pos, plant)) return false
            SignatureKit.sound(level, Vec3.atCenterOf(pos), SoundEvents.FUNGUS_PLACE, 1f, 0.8f + level.random.nextFloat() * 0.3f)
            return true
        }

        /**
         * [s] grows into a huge red mushroom, as bone meal grows one: a red mushroom springs up
         * there, then is forced to grow by vanilla's own huge red mushroom feature. Only where the
         * caster may change every block a huge red mushroom could fill there ([hugeFootprint]), so
         * claims hold; otherwise, or where there's no room, it stays a small red mushroom.
         */
        private fun grow(e: SignatureEntity, level: ServerLevel, s: Shroom) {
            val pos = groundPos(s.x, s.z, s.y).above()
            if (!sprout(e, level, pos)) return
            val ctx = e.ctx ?: return
            val cap = Blocks.RED_MUSHROOM_BLOCK.defaultBlockState()
            if (!hugeFootprint(pos).all { ctx.mayChange(it, cap) }) return
            val plant = level.getBlockState(pos)
            val mushroom = plant.block as? MushroomBlock ?: return
            if (!mushroom.growMushroom(level, pos, plant, level.random)) return
            SignatureKit.sound(level, Vec3.atCenterOf(pos), SoundEvents.BONE_MEAL_USE, 1.6f, 0.5f)
            SignatureKit.sound(level, Vec3.atCenterOf(pos), SoundEvents.ROOTED_DIRT_PLACE, 1.6f, 0.5f)
            SignatureKit.particles(level, ParticleTypes.HAPPY_VILLAGER, Vec3.atCenterOf(pos), 14, 0.6, 0.0)
        }

        /** Every block a huge red mushroom grown at [base] could fill: its stem column, up to
         *  [HUGE_HEIGHT] (vanilla's tallest), and its cap round it, [HUGE_RADIUS] out. */
        private fun hugeFootprint(base: BlockPos): List<BlockPos> {
            val out = ArrayList<BlockPos>()
            for (y in 0..HUGE_HEIGHT) for (x in -HUGE_RADIUS..HUGE_RADIUS) for (z in -HUGE_RADIUS..HUGE_RADIUS) {
                if ((x != 0 || z != 0) && y < HUGE_CAP_FROM) continue
                out += base.offset(x, y, z)
            }
            return out
        }
    }

    // ---- Garden Sanguine: three turning sprays that drain and plant blooms ---------------------

    /** One bloom the spray planted: where on the ground, and the tick it was planted. */
    class Planting(val at: Vec3, val born: Int)

    /**
     * Held, three sprays of blood-red petals from the hand, a third of a turn apart round the
     * aim and splayed a little out from it, the three turning steadily round it. Each drains the
     * creatures along it and where it lands every [HIT_EVERY] ticks (once a pulse, however many
     * sprays strike a creature), healing the caster by part of it, and every tick one of them,
     * in turn, plants a bloom of crimson fungus on the ground or deck where it lands.
     * Netherrack under a bloom really becomes crimson nylium. The blooms outlast the hold for
     * [BLOOM_LIFE] ticks. A creature that touches one has its blood drawn out by it every
     * [CONTACT_EVERY] ticks, held back by its roots, and the caster heals by part of it; the
     * caster and their allies standing in them regenerate instead.
     *
     * [SignatureEntity.data] is the count of blooms planted, with [RELEASED] set once let go; the
     * client plants each bloom where its spray lands as the count goes up.
     */
    object GardenSanguine : SignatureBehaviour() {
        const val REACH = 32.0
        const val SPRAYS = 3
        /** How far each spray splays out from the aim, and how fast the three turn (radians a tick). */
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
        /** The spray's own shove on what it strikes, the way it's aimed. */
        private const val PRESS = 0.08
        private const val HEAL_SHARE = 0.5f
        private const val CONTACT_EVERY = 5
        private const val CONTACT_SIP = 4f
        private const val CONTACT_HEAL_SHARE = 0.35f
        private const val REGEN_EVERY = 20
        private const val HOLD_LIFE = 20 * 60

        /** The three sprays' directions round [aim] at [age] ticks into the hold; either side may ask. */
        fun sprays(aim: Vec3, age: Double): List<Vec3> {
            val a = aim.normalize()
            val s1 = a.cross(if (abs(a.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)).normalize()
            val s2 = a.cross(s1)
            return List(SPRAYS) { k ->
                val an = age * TURN + k * 2 * Math.PI / SPRAYS
                a.add(s1.scale(cos(an) * SPLAY)).add(s2.scale(sin(an) * SPLAY)).normalize()
            }
        }

        /** Where a spray from [from] along [dir] lands: the first block (a ship's too), or [REACH] out. */
        fun lands(level: Level, from: Vec3, dir: Vec3): Vec3 {
            val end = from.add(dir.scale(REACH))
            val hit = level.clipIncludeShips(ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null))
            return if (hit.type == HitResult.Type.BLOCK) hit.location else end
        }

        /** Where a bloom grows under [point]: the ground or deck below it, or null. */
        fun groundUnder(level: Level, point: Vec3): Vec3? =
            surfaceY(level, point.x, point.z, point.y + 1.0, 5.0)?.let { Vec3(point.x, it, point.z) }

        /** The blocks a bloom at [ground] lies on: the one under it and those round it at the
         *  same height; either side may ask. */
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

        /** Whether a creature standing at [feet] touches the bloom at [at]. Either side may ask. */
        fun touches(at: Vec3, feet: Vec3): Boolean =
            feet.y - at.y in -1.2..1.6 && Vec3(feet.x - at.x, 0.0, feet.z - at.z).length() <= BLOOM_R

        private class Garden {
            val blooms = ArrayList<Planting>()
            var planted = 0
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            SignatureKit.sound(cast.level, cast.origin, SoundEvents.BIG_DRIPLEAF_TILT_DOWN, 1.4f, 0.5f)
            SignatureKit.sound(cast.level, cast.origin, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, 1f, 0.6f)
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
                    // Netherrack under it turns to real crimson nylium.
                    for (pos in patch(level, ground)) {
                        if (level.getBlockState(pos).`is`(Blocks.NETHERRACK)) SignatureKit.setBlock(e, pos, Blocks.CRIMSON_NYLIUM.defaultBlockState())
                    }
                    if (state.planted % 4 == 1) SignatureKit.sound(level, ground, SoundEvents.NYLIUM_PLACE, 1.3f, 0.7f + level.random.nextFloat() * 0.3f)
                }
            }
            if (held % 8 == 0) SignatureKit.sound(level, e.point, SoundEvents.BIG_DRIPLEAF_TILT_UP, 1f, 1.2f)
            if (held % 10 == 0) SignatureKit.sound(level, e.point, SoundEvents.GENERIC_SPLASH, 0.8f, 0.6f)
        }

        override fun released(e: SignatureEntity, cast: SignatureCast, held: Int) {
            val state = e.state as? Garden
            val last = state?.blooms?.maxOfOrNull { it.born }
            if (last == null) {
                end(e)
                e.discard()
                return
            }
            // The blooms stay their while; the spray stops.
            e.data = e.data or RELEASED
            e.life = last + BLOOM_LIFE
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
                    // A creature in a bloom: its roots catch at it and it bleeds into them, once a
                    // pulse however many blooms it stands in.
                    if (!contact || !drained.add(other.id)) continue
                    other.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, CONTACT_EVERY + 10, 2), e.caster)
                    if (SpellContext.isUndead(other)) continue
                    val drank = drain(e.caster, other, CONTACT_HEAL_SHARE) { SignatureKit.hurtThrough(e, other, EKDamageSources.BLOOD_DRAIN_KEY, CONTACT_SIP) }
                    if (!drank || e.tickCount % (CONTACT_EVERY * 2) != 0) continue
                    SpellVfx.stream(level, SignatureKit.middle(other), bloom.at.add(0.0, 0.2, 0.0), Element.BLOOD.color, 12)
                    SignatureKit.particles(level, DustParticleOptions(Vector3f(0.6f, 0.03f, 0.08f), 1.4f), SignatureKit.middle(other), 10, 0.3, 0.0)
                    SignatureKit.sound(level, other.position(), SoundEvents.HONEY_BLOCK_SLIDE, 1f, 0.5f)
                    val caster = e.caster
                    if (caster != null && caster.isAlive) SpellVfx.stream(level, bloom.at.add(0.0, 0.3, 0.0), caster.position().add(0.0, caster.bbHeight * 0.6, 0.0), Element.BLOOD.color)
                }
            }
        }
    }
}
