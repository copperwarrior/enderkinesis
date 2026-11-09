package org.shipwrights.enderkinesis.spell.signature

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.BlockTags
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.AbstractArrow
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.ThrownTrident
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d
import org.joml.Vector3f
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.dimension.EKDamageSources
import org.shipwrights.enderkinesis.effect.FrozenEffect
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.spell.Absence
import org.shipwrights.enderkinesis.spell.FluidFreezing
import org.shipwrights.enderkinesis.spell.GasField
import org.shipwrights.enderkinesis.spell.Signature
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.mod.common.world.clipIncludeShips
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.shipwrights.enderkinesis.util.CrossFrameBlocks
import org.shipwrights.enderkinesis.util.colliderClip
import org.shipwrights.enderkinesis.util.smoothstep
import org.valkyrienskies.core.api.util.GameTickOnly

@OptIn(GameTickOnly::class)
object BodySignatures {
    val ALL: Map<Signature, SignatureBehaviour> = mapOf(
        Signature.STARFALL to Starfall,
        Signature.NORTH_STAR to NorthStar,
        Signature.HUNGERING_MAW to HungeringMaw,
        Signature.FAESPARK to Faespark,
        Signature.ROTDANCE to Rotdance,
        Signature.SUPERNOVA to Supernova,
    )

    private class Strike(val at: Vec3, val entity: LivingEntity?, val ship: SignatureShips.ShipBlock?, val block: BlockPos?, val face: Direction?)

    private fun strike(e: SignatureEntity, a: Vec3, b: Vec3, width: Double): Strike? {
        val level = e.level() as ServerLevel
        val span = b.subtract(a)
        if (span.lengthSqr() < 1.0e-9) return null
        val hit = level.clipIncludeShips(ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e))
        val blockHit = hit.takeIf { it.type == HitResult.Type.BLOCK }
        val end = blockHit?.location ?: b
        val creature = e.creaturesAlong(a, end, width).minByOrNull { it.middle().distanceToSqr(a) }
        if (creature != null) return Strike(nearestOn(a, end, creature.middle()), creature, null, null, null)
        if (blockHit == null) return null
        val ship = SignatureShips.blockAt(level, end.add(span.normalize().scale(0.05)))
        return if (ship != null) Strike(end, null, ship, null, null) else Strike(end, null, null, blockHit.blockPos, blockHit.direction)
    }

    private fun nearestOn(a: Vec3, b: Vec3, p: Vec3): Vec3 {
        val ab = b.subtract(a)
        val len2 = ab.lengthSqr()
        if (len2 < 1.0e-9) return a
        return a.add(ab.scale((p.subtract(a).dot(ab) / len2).coerceIn(0.0, 1.0)))
    }

    /** Callers give a small [count], so the fire stays scattered and does not become a carpet. */
    private fun ignite(e: SignatureEntity, centre: Vec3, r: Double, count: Int) {
        if (count <= 0) return
        val level = e.level() as ServerLevel
        val spots = ArrayList<BlockPos>()
        CrossFrameBlocks(level, centre, r).forEachWithin(r) { pos, _ ->
            if (level.getBlockState(pos).isAir && BaseFireBlock.canBePlacedAt(level, pos, Direction.UP)) spots += pos.immutable()
        }
        spots.shuffle(java.util.Random(level.random.nextLong()))
        var lit = 0
        for (pos in spots) {
            if (lit >= count) break
            if (e.setBlock(pos, BaseFireBlock.getState(level, pos))) lit++
        }
    }

    private fun blastShips(level: ServerLevel, centre: Vec3, r: Double, impulse: Double) {
        for (block in SignatureShips.blocksNear(level, centre, r)) {
            val away = block.world.subtract(centre)
            val d = away.length()
            if (d < 1.0e-3) continue
            SignatureShips.push(block.ship, block.world, away.scale(impulse * (1 - d / r) / d))
        }
    }

    private fun toShip(ship: LoadedServerShip, p: Vec3): Vec3 {
        val v = Vector3d()
        ship.transform.worldToShip.transformPosition(p.x, p.y, p.z, v)
        return Vec3(v.x, v.y, v.z)
    }

    private fun knockAway(target: Entity, centre: Vec3, strength: Double, lift: Double) {
        val away = target.middle().subtract(centre).let { Vec3(it.x, 0.0, it.z) }
        val flat = if (away.lengthSqr() < 1.0e-6) Vec3.ZERO else away.normalize().scale(strength)
        target.spellPush(flat.add(0.0, lift, 0.0))
    }

    /** Both sides get each fall from [SignatureEntity.seed] and [SignatureEntity.point] ([fall]).
     *  [SignatureEntity.data] holds one landed bit for each meteor. */
    object Starfall : SignatureBehaviour() {
        const val COUNT = 14
        const val DELAY = 6
        const val INTERVAL = 6
        const val FALL = 14
        const val OVERRUN = 14
        const val HEIGHT = 34.0
        const val SLANT = 14.0
        const val SCATTER = 7.0
        const val BLAST = 5.0
        private const val RANGE = 48.0
        private const val RANGE_PER_POWER = 32.0
        private const val SKY_JITTER = 0.5
        private const val DAMAGE = 26f
        private const val KNOCK = 1.4
        private const val LIFT = 0.8
        private const val BURN_SECONDS = 6
        private const val SHIP_IMPULSE = 4.0
        private const val SHIP_BLAST = 1.5
        private const val WIDTH = 0.6
        private const val FIRE_CHANCE = 0.6f

        fun count(power: Float): Int = (1 + Math.round((COUNT - 1) * power)).coerceIn(1, COUNT)

        fun startOf(k: Int): Int = DELAY + k * INTERVAL

        fun landed(e: SignatureEntity, k: Int): Boolean = e.data and (1 shl k) != 0

        class Fall(val from: Vec3, val mark: Vec3) {
            fun at(t: Double): Vec3 = from.add(mark.subtract(from).scale(t / FALL))
        }

        /** Both sides call this, so it must stay deterministic. */
        fun fall(e: SignatureEntity, k: Int): Fall {
            val random = Random(e.seed * 31 + k)
            val mark = if (k == 0) e.point else {
                val a = random.nextDouble() * Math.PI * 2
                val r = SCATTER * sqrt(random.nextDouble())
                e.point.add(cos(a) * r, 0.0, sin(a) * r)
            }
            val sky = Math.floorMod(e.seed, 360) * Math.PI / 180 + (random.nextDouble() - 0.5) * SKY_JITTER
            return Fall(mark.add(cos(sky) * SLANT, HEIGHT, sin(sky) * SLANT), mark)
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            val aimed = cast.aimed(RANGE + RANGE_PER_POWER * cast.power).point
            val mark = Vec3(aimed.x, cast.level.groundNear(aimed) ?: aimed.y, aimed.z)
            val n = count(cast.power)
            cast.level.sound(cast.origin, EKSounds.SIGNATURE_STARFALL_CAST.get(), 1.4f)
            return spawn(cast, mark, startOf(n - 1) + FALL + OVERRUN + 4) { point = mark }
        }

        override fun tick(e: SignatureEntity) {
            val n = count(e.power)
            var left = 0
            for (k in 0 until n) {
                if (landed(e, k)) continue
                left++
                val t = e.tickCount - startOf(k)
                if (t < 1) continue
                val fall = fall(e, k)
                val a = fall.at(t - 1.0)
                val b = fall.at(t.toDouble())
                val step = b.subtract(a)
                val hit = strike(e, a, b.add(step.normalize().scale(0.4)), WIDTH)
                when {
                    hit != null -> land(e, k, hit, step)
                    t >= FALL + OVERRUN -> land(e, k, Strike(b, null, null, null, null), step)
                }
            }
            if (left == 0) {
                settle(e)
                e.discard()
            }
        }

        /** The entity can also reach its life before every meteor lands. */
        override fun end(e: SignatureEntity) = settle(e)

        private fun settle(e: SignatureEntity) = (e.level() as ServerLevel).sound(e.point, EKSounds.SIGNATURE_STARFALL_SETTLE.get(), 2f)

        private fun land(e: SignatureEntity, k: Int, hit: Strike, motion: Vec3) {
            val level = e.level() as ServerLevel
            e.data = e.data or (1 shl k)
            val at = hit.at
            for (target in e.creatures(at, BLAST)) {
                val near = 1 - 0.5 * target.middle().distanceTo(at) / BLAST
                // hurtThrough skips i-frames, so a creature under several meteors takes each blow.
                e.hurtTargetThrough(target, EKDamageSources.PYRE_KEY, (DAMAGE * (0.6 + 0.4 * e.power) * near).toFloat())
                target.setSecondsOnFire(BURN_SECONDS)
                knockAway(target, at, KNOCK * near, LIFT * near)
            }
            if (hit.ship != null && motion.lengthSqr() > 1.0e-6) {
                SignatureShips.push(hit.ship.ship, at, motion.normalize().scale(SHIP_IMPULSE))
                blastShips(level, at, BLAST * 0.6, SHIP_BLAST)
            }
            if (hit.block != null && hit.face == Direction.UP) crater(e, hit.block)
            if (level.random.nextFloat() < FIRE_CHANCE) ignite(e, at, 2.5, 1)
            level.particles(ParticleTypes.EXPLOSION_EMITTER, at, 1, 0.0)
            level.particles(ParticleTypes.EXPLOSION, at, 4, 1.2)
            level.particles(ParticleTypes.LAVA, at.add(0.0, 0.3, 0.0), 30, 0.8)
            level.particles(ParticleTypes.FLAME, at.add(0.0, 0.3, 0.0), 60, 1.0, 0.25)
            level.particles(ParticleTypes.LARGE_SMOKE, at.add(0.0, 0.5, 0.0), 30, 1.2, 0.08)
            level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, Blocks.MAGMA_BLOCK.defaultBlockState()), at.x, at.y + 0.3, at.z, 40, 0.8, 0.4, 0.8, 0.3)
            level.sound(at, EKSounds.SIGNATURE_STARFALL_LAND.get(), 2.5f)
        }

        private fun crater(e: SignatureEntity, struck: BlockPos) {
            val level = e.level() as ServerLevel
            for (dx in -2..2) for (dz in -2..2) {
                val ring = dx * dx + dz * dz
                if (ring > 5) continue
                val pos = struck.offset(dx, 0, dz)
                val state = level.getBlockState(pos)
                if (ring <= 1) {
                    if (!diggable(level, pos, state) || !level.getBlockState(pos.above()).canBeReplaced()) continue
                    e.setBlock(pos, Blocks.AIR.defaultBlockState())
                    val floor = pos.below()
                    val floorState = level.getBlockState(floor)
                    if (!diggable(level, floor, floorState)) continue
                    if (ring == 0) e.setBlock(floor, Blocks.MAGMA_BLOCK.defaultBlockState())
                    else scorched(floorState)?.let { e.setBlock(floor, it) }
                } else if (level.random.nextBoolean()) scorched(state)?.let { e.setBlock(pos, it) }
            }
        }

        private fun diggable(level: ServerLevel, pos: BlockPos, state: BlockState): Boolean {
            if (state.isAir || state.hasBlockEntity() || !state.fluidState.isEmpty) return false
            val hardness = state.getDestroySpeed(level, pos)
            return hardness in 0f..MAX_DIG_HARDNESS
        }

        private fun scorched(state: BlockState): BlockState? =
            if (state.`is`(BlockTags.DIRT) && !state.`is`(Blocks.COARSE_DIRT)) Blocks.COARSE_DIRT.defaultBlockState() else null

        /** Cobblestone hardness: the hardest block the crater digs. */
        private const val MAX_DIG_HARDNESS = 2f
    }

    /**
     * [SignatureEntity.data] packs the stuck tick ([stuckAt], 0 in flight), [SHIP_BIT] and the [hover] height.
     * [SignatureEntity.point] is relative to the host's middle while it circles one, else in world space.
     */
    object NorthStar : SignatureBehaviour() {
        const val SPEED = 1.6
        const val HOLD = 140
        const val ORBIT = 8.0
        const val GROW = 15
        const val SHRINK = 12
        const val GLIDE = 12
        const val CIRCLE_GAP = 2.5
        const val CIRCLE_SPIN = 0.1
        const val SHIP_BIT = 1 shl 16
        const val HOVER = CIRCLE_GAP
        private const val HOVER_SHIFT = 17
        private const val HOVER_STEPS = 16.0
        private const val CORE_CLEARANCE = 0.6
        const val RANGE = 48.0
        const val RANGE_PER_POWER = 24.0
        private const val GRIND = ORBIT + 1.0
        private const val GRIND_EVERY = 5
        /** About 18 damage a second at four grinds a second. */
        private const val GRIND_DAMAGE = 4.5f
        private const val IMPACT_DAMAGE = 26f
        private const val FREEZE_DOSE = 80
        private const val SLOW_LEVEL = 3
        private const val SHIP_PULL = 0.1
        /** Do not increase: a larger reach freezes thousands of lake blocks at once. */
        private const val WATER_REACH = 8.0
        private const val WATER_EVERY = 10

        private class Orbit(val range: Double) {
            var flown = 0.0
            var stuck = false
            var ship: LoadedServerShip? = null
            var local: Vec3 = Vec3.ZERO
            /** Entity id to the tick of its last grind sound. */
            val grindSounds = Int2IntOpenHashMap().apply { defaultReturnValue(NEVER) }
            var freezeSound = NEVER
        }

        private const val NEVER = Int.MIN_VALUE
        private const val SOUND_EVERY = 20

        fun stuckAt(e: SignatureEntity): Int = e.data and 0xFFFF

        fun onShip(e: SignatureEntity): Boolean = e.data and SHIP_BIT != 0

        fun hover(e: SignatureEntity): Double = ((e.data ushr HOVER_SHIFT) and 0xFF) / HOVER_STEPS

        /** Both sides call this. */
        fun hovering(point: Vec3, hover: Double, since: Double): Vec3 = point.add(0.0, hover * smoothstep(since / GLIDE), 0.0)

        fun range(power: Float): Double = RANGE + RANGE_PER_POWER * power

        /** Both sides call this. [contact] is relative to the host's middle. */
        fun circling(middle: Vec3, width: Float, contact: Vec3, since: Double): Vec3 {
            val ease = smoothstep(since / GLIDE)
            val r = hypot(contact.x, contact.z).coerceAtLeast(0.05)
            val a = atan2(contact.z, contact.x) + CIRCLE_SPIN * since
            val radius = r + (width * 0.5 + CIRCLE_GAP - r) * ease
            return middle.add(cos(a) * radius, contact.y * (1 - 0.6 * ease), sin(a) * radius)
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            val aim = cast.aim
            cast.level.sound(cast.origin, EKSounds.SIGNATURE_NORTH_STAR_CAST.get(), 1.4f)
            return spawn(cast, cast.origin, ((RANGE + RANGE_PER_POWER) / SPEED).toInt() + HOLD + 2) {
                deltaMovement = aim.scale(SPEED)
                state = Orbit(range(cast.power))
            }
        }

        override fun tick(e: SignatureEntity) {
            val o = e.state as? Orbit ?: return
            if (!o.stuck) {
                val here = e.position()
                // Include the first step out of the hand, so a near target is not missed.
                val from = if (e.tickCount <= 1) here.subtract(e.deltaMovement) else here
                val hit = strike(e, from, here.add(e.deltaMovement), 0.3)
                o.flown += SPEED
                if (hit != null) stick(e, o, hit, from, here.add(e.deltaMovement)) else if (o.flown >= o.range) stick(e, o, Strike(here, null, null, null, null), here, here)
                return
            }
            val since = (e.tickCount - stuckAt(e)).toDouble()
            val host = (e.target() as? LivingEntity)?.takeIf { it.isAlive }
            if (host == null && e.targetId >= 0) {
                e.point = e.position()
                e.targetId = -1
            }
            val area = host?.let { circling(it.middle(), it.bbWidth, e.point, since) }
                ?: o.ship?.let { SignatureShips.toWorld(it, o.local) }
                ?: e.point
            val core = hovering(area, hover(e), since)
            e.deltaMovement = Vec3.ZERO
            e.setPos(core)
            o.ship?.let { SignatureShips.push(it, area, Vec3(0.0, -SHIP_PULL, 0.0)) }
            if (since > GROW / 3 && e.tickCount % GRIND_EVERY == 0) for (target in e.creatures(area, GRIND)) grind(e, o, target)
            if (e.tickCount % WATER_EVERY == 0) freezeWater(e, o, area)
        }

        /** A creature is struck where the bolt meets its side, not at its middle. */
        private fun stick(e: SignatureEntity, o: Orbit, hit: Strike, a: Vec3, b: Vec3) {
            val level = e.level() as ServerLevel
            o.stuck = true
            val host = hit.entity
            val at = host?.let { it.boundingBox.inflate(0.3).clip(a, b).orElse(hit.at) } ?: hit.at
            e.deltaMovement = Vec3.ZERO
            e.setPos(at)
            e.life = e.tickCount + HOLD
            if (host != null) {
                e.point = at.subtract(host.middle())
                e.targetId = host.id
                e.hurtTargetThrough(host, EKDamageSources.WINTER_KEY, IMPACT_DAMAGE * (0.6f + 0.4f * e.power))
                host.ticksFrozen = minOf(host.ticksFrozen + FREEZE_DOSE * 2, FrozenEffect.THRESHOLD + 200)
            } else e.point = at
            hit.ship?.let { o.ship = it.ship; o.local = toShip(it.ship, at) }
            val hover = if (host == null && (hit.block != null || hit.ship != null)) headroom(e, at, b.subtract(a)) else 0.0
            val hoverBits = (hover * HOVER_STEPS).toInt().coerceIn(0, 0xFF) shl HOVER_SHIFT
            e.data = e.tickCount.coerceIn(1, 0xFFFF) or (if (hit.ship != null) SHIP_BIT else 0) or hoverBits
            level.sound(at, EKSounds.SIGNATURE_NORTH_STAR_STICK.get(), 1.5f)
            level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()), at.x, at.y, at.z, 30, 0.3, 0.3, 0.3, 0.15)
            level.particles(ParticleTypes.SNOWFLAKE, at, 30, 0.5, 0.1)
        }

        private fun headroom(e: SignatureEntity, at: Vec3, heading: Vec3): Double {
            val from = at.subtract(heading.normalize().scale(0.05))
            val to = from.add(0.0, HOVER + CORE_CLEARANCE, 0.0)
            val up = e.level().clipIncludeShips(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e))
            val room = if (up.type == HitResult.Type.BLOCK) up.location.y - from.y else HOVER + CORE_CLEARANCE
            return (room - CORE_CLEARANCE).coerceIn(0.0, HOVER)
        }

        private fun grind(e: SignatureEntity, o: Orbit, target: LivingEntity) {
            e.hurtTargetThrough(target, EKDamageSources.WINTER_KEY, GRIND_DAMAGE)
            val last = o.grindSounds.get(target.id)
            if (last == NEVER || e.tickCount - last >= SOUND_EVERY) {
                o.grindSounds.put(target.id, e.tickCount)
                (e.level() as ServerLevel).sound(target.middle(), EKSounds.SIGNATURE_NORTH_STAR_GRIND.get())
            }
            target.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, SLOW_LEVEL), e.caster)
            target.ticksFrozen = minOf(target.ticksFrozen + FREEZE_DOSE, FrozenEffect.THRESHOLD + 200)
            val frozen = EKEffects.FROZEN.get()
            if (target.ticksFrozen >= FrozenEffect.THRESHOLD && !target.hasEffect(frozen)) target.addEffect(MobEffectInstance(frozen, 80), e.caster)
        }

        private fun freezeWater(e: SignatureEntity, o: Orbit, core: Vec3) {
            val level = e.level() as ServerLevel
            var froze = false
            CrossFrameBlocks(level, core, WATER_REACH).forEachWithin(WATER_REACH) { pos, _ ->
                FluidFreezing.frozen(level.getBlockState(pos))?.let { if (e.setBlock(pos.immutable(), it)) froze = true }
            }
            if (froze && (o.freezeSound == NEVER || e.tickCount - o.freezeSound >= SOUND_EVERY)) {
                o.freezeSound = e.tickCount
                level.sound(core, EKSounds.SIGNATURE_NORTH_STAR_FREEZE.get())
            }
        }

        override fun end(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val at = e.position()
            level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState()), at.x, at.y, at.z, 160, ORBIT * 0.6, 0.5, ORBIT * 0.6, 0.15)
            level.sound(at, EKSounds.SIGNATURE_NORTH_STAR_FADE.get(), 1.5f)
        }
    }

    object HungeringMaw : SignatureBehaviour() {
        const val REACH = 20.0
        const val SWALLOW = 2.0
        const val EDGE = 3.2
        const val SPHERE = 1.7
        const val GROW = 16
        const val CLOSE = 12
        private const val LIFE = 140
        private const val LIFE_PER_POWER = 80
        private const val SPEED = 0.12
        private const val SPEED_PER_POWER = 0.08
        private const val SMALL_WIDTH = 0.9
        private const val SMALL_HEIGHT = 1.4
        private const val ABSENT_TICKS = 160
        private const val HOLD_PULL = 0.35
        private const val ITEM_PULL = 0.1
        private const val ITEM_PULL_NEAR = 0.15
        /** Blocks a tick at the edge and the middle of [REACH]. A sprint is about 0.28. */
        private const val DRAG_EDGE = 0.25
        private const val DRAG_MIDDLE = 0.7
        private const val DRAG_GRIP = 0.35
        /** Lifts a creature on the ground, so it does not snag while it moves in. */
        private const val DRAG_LIFT = 0.1
        const val BITE = 4.5
        /** About 25 damage a second at the middle, and 15 at the edge hold. */
        private const val BITE_DAMAGE = 5f
        private const val BITE_EVERY = 4
        const val TEAR_REACH = 6.0
        private const val TEAR_EVERY = 2
        private const val TEAR_COUNT = 3
        /** Hard bound of blocks per tear step, so a tiny ship scale cannot eat thousands in one tick. */
        private const val TEAR_MAX_BLOCKS = 48
        private const val TEAR_BUDGET_EPSILON = 1.0e-9

        private data class TearCandidate(val pos: BlockPos, val distanceSq: Double, val cost: Double)
        /** A little harder than gravel, and softer than stone. */
        private const val TEAR_HARDNESS = 1.5f
        private const val SHIP_PULL = 0.5
        private const val SHIP_EVERY = 5
        /** After this many bounces in one tick, a corner pushes it straight out. */
        private const val BOUNCE_TRIES = 4
        /** More than half a block diagonal, so the search finds each ship block the sphere can touch. */
        /** A block can touch the sphere when its middle is this near. */
        private const val BLOCK_REACH = SPHERE + 0.9

        private class Horizon(var drift: Vec3) {
            val kept = ArrayList<Entity>()
            var chewed = Int.MIN_VALUE / 2
            var swallowed = Int.MIN_VALUE / 2
        }

        /** At most 4 chews a second. */
        private const val CHEW_EVERY = 5
        private const val SWALLOW_SOUND_EVERY = 5

        override fun start(cast: SignatureCast): SignatureEntity {
            val drift = cast.aim.scale(SPEED + SPEED_PER_POWER * cast.power)
            cast.level.sound(cast.origin, EKSounds.SIGNATURE_HUNGERING_MAW_OPEN.get(), 1.6f)
            return spawn(cast, cast.origin.add(cast.aim.scale(SPHERE + 0.6)), LIFE + (LIFE_PER_POWER * cast.power).toInt()) { state = Horizon(drift) }
        }

        override fun tick(e: SignatureEntity) {
            val h = e.state as? Horizon ?: return
            val level = e.level() as ServerLevel
            val n = e.tickCount
            val core = e.position()
            e.deltaMovement = if (n >= e.life - CLOSE) Vec3.ZERO else rebound(level, h, core).scale(minOf(1.0, n.toDouble() / GROW))
            for (entity in level.getEntities(e, AABB(core, core).inflate(REACH))) {
                if (entity === e.caster || entity.isSpectator || !entity.isAlive) continue
                val d = entity.middle().distanceTo(core)
                if (d > REACH) continue
                when (entity) {
                    is LivingEntity -> catch(e, entity, core, d)
                    is ItemEntity, is Projectile -> if (d <= SWALLOW) swallow(e, h, entity) else entity.pull(core, ITEM_PULL + ITEM_PULL_NEAR * (1 - d / REACH))
                }
            }
            if (n % SHIP_EVERY == 0) for (block in SignatureShips.nearestBlocks(level, core, REACH)) {
                val to = core.subtract(block.world)
                if (to.lengthSqr() > 1.0e-6) SignatureShips.push(block.ship, block.world, to.normalize().scale(SHIP_PULL))
            }
            if (n % TEAR_EVERY == 0 && n >= GROW && n < e.life - CLOSE) tear(e, h, level, core)
            if (n == e.life - CLOSE) level.sound(core, EKSounds.SIGNATURE_HUNGERING_MAW_CLOSE.get(), 1.2f)
        }

        private fun catch(e: SignatureEntity, target: LivingEntity, core: Vec3, d: Double) {
            if (target is ArmorStand || Absence.isAbsent(target)) return
            if (d <= BITE + target.bbWidth * 0.5 && e.tickCount % BITE_EVERY == 0) {
                e.hurtTargetThrough(target, EKDamageSources.VOID_KEY, (BITE_DAMAGE * (1 - 0.5 * minOf(1.0, d / BITE))).toFloat())
            }
            if (!target.movable()) return
            val small = target !is Player && target.bbWidth <= SMALL_WIDTH && target.bbHeight <= SMALL_HEIGHT
            when {
                small && d <= SWALLOW + target.bbWidth * 0.5 -> {
                    target.addEffect(MobEffectInstance(EKEffects.ABSENT.get(), ABSENT_TICKS), e.caster)
                    (e.level() as ServerLevel).sound(core, EKSounds.SIGNATURE_HUNGERING_MAW_SWALLOW.get(), 1.2f)
                }
                !small && d <= EDGE + target.bbWidth * 0.5 -> {
                    val out = target.middle().subtract(core).let { if (it.lengthSqr() < 1.0e-6) Vec3(1.0, 0.0, 0.0) else it.normalize() }
                    val spot = core.add(out.scale(EDGE + target.bbWidth * 0.5))
                    target.deltaMovement = spot.subtract(target.middle()).scale(HOLD_PULL)
                    target.hurtMarked = true
                    target.fallDistance = 0f
                }
                else -> drag(target, core, d)
            }
        }

        private fun drag(target: LivingEntity, core: Vec3, d: Double) {
            val to = core.subtract(target.middle())
            if (to.lengthSqr() < 1.0e-6) return
            val want = to.normalize().scale(DRAG_EDGE + (DRAG_MIDDLE - DRAG_EDGE) * (1 - d / REACH))
            var v = target.deltaMovement.lerp(want, DRAG_GRIP)
            if (target.onGround()) v = v.add(0.0, DRAG_LIFT, 0.0)
            target.deltaMovement = v
            target.hurtMarked = true
            target.fallDistance = 0f
        }

        private fun rebound(level: ServerLevel, h: Horizon, core: Vec3): Vec3 {
            repeat(BOUNCE_TRIES) {
                val n = contact(level, core.add(h.drift), h.drift) ?: return h.drift
                h.drift = h.drift.subtract(n.scale(2 * h.drift.dot(n)))
            }
            contact(level, core.add(h.drift), h.drift)?.let { h.drift = it.scale(h.drift.length()) }
            return h.drift
        }

        private fun contact(level: ServerLevel, at: Vec3, v: Vec3): Vec3? {
            var best: Vec3? = null
            var bestGap = SPHERE
            fun consider(x: Double, y: Double, z: Double) {
                val off = Vec3(at.x - x, at.y - y, at.z - z)
                val gap = off.length()
                if (gap >= bestGap) return
                val n = if (gap < 1.0e-6) v.normalize().reverse() else off.scale(1 / gap)
                if (n.dot(v) >= 0) return
                best = n
                bestGap = gap
            }
            val blocks = CrossFrameBlocks(level, at, BLOCK_REACH)
            val near = Vector3d()
            blocks.forEachWithin(BLOCK_REACH) { pos, ship ->
                val state = level.getBlockState(pos)
                if (state.isAir || eatable(level, pos, state)) return@forEachWithin
                val shape = state.getCollisionShape(level, pos)
                if (shape.isEmpty) return@forEachWithin
                // Clamp in the frame of the block, because a ship can turn.
                if (ship != null) ship.transform.worldToShip.transformPosition(at.x, at.y, at.z, near) else near.set(at.x, at.y, at.z)
                near.set(
                    near.x.coerceIn(pos.x + shape.min(Direction.Axis.X), pos.x + shape.max(Direction.Axis.X)),
                    near.y.coerceIn(pos.y + shape.min(Direction.Axis.Y), pos.y + shape.max(Direction.Axis.Y)),
                    near.z.coerceIn(pos.z + shape.min(Direction.Axis.Z), pos.z + shape.max(Direction.Axis.Z)),
                )
                ship?.transform?.shipToWorld?.transformPosition(near)
                consider(near.x, near.y, near.z)
            }
            return best
        }

        /** Both sides call this. */
        fun eatable(level: Level, pos: BlockPos, state: BlockState): Boolean {
            if (state.isAir || state.hasBlockEntity() || !state.fluidState.isEmpty) return false
            return state.getDestroySpeed(level, pos) in 0f..TEAR_HARDNESS
        }

        /** Remove blocks with no drops and no break effect: the client draws them flying into the maw. */
        private fun tear(e: SignatureEntity, h: Horizon, level: ServerLevel, core: Vec3) {
            val ctx = e.ctx ?: return
            val blocks = CrossFrameBlocks(level, core, TEAR_REACH)
            val near = ArrayList<TearCandidate>()
            blocks.forEachWithin(TEAR_REACH) { pos, ship ->
                if (!eatable(level, pos, level.getBlockState(pos))) return@forEachWithin
                // A block of a scaled-down ship holds s^3 of a world block, so it costs that much of the budget.
                val s = ship?.transform?.shipToWorldScaling?.x() ?: 1.0
                near += TearCandidate(pos.immutable(), blocks.visitedMiddle.distanceSquared(core.x, core.y, core.z), minOf(1.0, s * s * s))
            }
            near.sortBy { it.distanceSq }
            val air = Blocks.AIR.defaultBlockState()
            var budget = TEAR_COUNT.toDouble()
            var eaten = 0
            for ((pos, _, cost) in near) {
                if (budget <= TEAR_BUDGET_EPSILON || eaten >= TEAR_MAX_BLOCKS) break
                budget -= cost
                eaten++
                // Remove a loose plant on top first, so it does not drop as an item.
                val above = pos.above()
                val top = level.getBlockState(above)
                if (eatable(level, above, top) && top.getCollisionShape(level, above).isEmpty && ctx.mayChange(above, air) && !level.setBlock(above, air, 3)) continue
                if (!ctx.mayChange(pos, air) || !level.setBlock(pos, air, 3)) continue
                if (e.tickCount - h.chewed >= CHEW_EVERY) {
                    h.chewed = e.tickCount
                    level.sound(Vec3.atCenterOf(pos), EKSounds.SIGNATURE_HUNGERING_MAW_CHEW.get(), 0.8f)
                }
            }
        }

        private fun swallow(e: SignatureEntity, h: Horizon, entity: Entity) {
            val level = e.level() as ServerLevel
            val keep = entity is ItemEntity || entity is ThrownTrident || (entity is AbstractArrow && entity.pickup == AbstractArrow.Pickup.ALLOWED)
            if (keep) entity.type.create(level)?.let { copy -> copy.restoreFrom(entity); h.kept += copy }
            entity.discard()
            level.particles(ParticleTypes.REVERSE_PORTAL, e.position(), 16, 0.4, 0.05)
            // A dropped stack of items can arrive in one tick.
            if (e.tickCount - h.swallowed >= SWALLOW_SOUND_EVERY) {
                h.swallowed = e.tickCount
                level.sound(e.position(), EKSounds.SIGNATURE_HUNGERING_MAW_SWALLOW.get(), 1.2f)
            }
        }

        override fun end(e: SignatureEntity) {
            val h = e.state as? Horizon ?: return
            val level = e.level() as ServerLevel
            val core = e.position()
            for (copy in h.kept) {
                copy.setPos(core.x, core.y, core.z)
                copy.deltaMovement = Vec3((level.random.nextDouble() - 0.5) * 0.2, 0.15, (level.random.nextDouble() - 0.5) * 0.2)
                level.addFreshEntity(copy)
            }
            level.particles(ParticleTypes.REVERSE_PORTAL, core, 120, 1.2, 0.3)
            if (h.kept.isNotEmpty()) level.sound(core, EKSounds.SIGNATURE_HUNGERING_MAW_SPIT.get(), 2f)
        }
    }

    /**
     * [SignatureEntity.data] holds the volley [index] and [METAL_BIT], or [HAND_BIT] for the hand entity.
     * [SignatureEntity.point] holds the top of the metal block. Both sides throw the same chains from these.
     */
    object Faespark : SignatureBehaviour() {
        const val ORBS = 10
        const val GAP = 15
        const val LIFE = 280
        const val CHAIN_EVERY = 8
        const val METAL_EVERY = 40
        const val CHAIN_REACH = 12.0
        const val MAX_LINKS = 6
        const val METAL_BIT = 16
        const val HAND_BIT = 32
        private const val HOLD_SLACK = 10
        private const val SPEED = 0.22
        private const val MAX_SPEED = 0.36
        private const val STEER = 0.15
        private const val SEEK = 32.0
        private const val METAL_REACH = 12.0
        private const val HOVER = 0.8
        private const val RIDE = 1.6
        private const val RISE = 0.2
        private const val GROUND_SEARCH = 16
        private const val METAL_HOVER = 1.4
        private const val CLEARANCE = 1.0
        private const val LIFT_SPREAD = 1.6
        private const val YAW_SPREAD = 0.25
        private const val CIRCLE_MIN = 2.0
        private const val CIRCLE_SPREAD = 2.0
        private const val CIRCLE_SPIN = 0.04
        /** The golden angle, so any number of sparks spread evenly round a creature. */
        private const val GOLDEN = 2.39996
        private const val SEPARATION = 2.5
        private const val SEPARATE = 0.03
        private const val RETARGET_EVERY = 10
        private const val CLIMB = 5.0
        private const val ARRIVED = 2.5
        private const val PROGRESS = 0.25
        private const val STALL_TICKS = 40
        private const val LOST_TICKS = 20
        private const val IGNORE_TICKS = 100
        private const val DAMAGE = 2.5f
        private const val BURN_SECONDS = 3

        fun index(e: SignatureEntity): Int = e.data and 15

        fun isHand(e: SignatureEntity): Boolean = e.data and HAND_BIT != 0
        fun drawnToMetal(e: SignatureEntity): Boolean = e.data and METAL_BIT != 0

        fun chainsNow(e: SignatureEntity): Boolean = !isHand(e) && (e.tickCount + index(e) * 2) % CHAIN_EVERY == 0

        /** Both sides call this. */
        fun chainMetal(e: SignatureEntity): Vec3? =
            if (drawnToMetal(e) && (e.tickCount + index(e) * 2) % METAL_EVERY == 0) e.point else null

        class Lane(val angle: Double, val radius: Double, val lift: Double, val yaw: Double)

        fun lane(e: SignatureEntity): Lane {
            val r = Random(e.seed * 7 + index(e))
            return Lane(
                index(e) * GOLDEN + r.nextDouble() * 0.6,
                CIRCLE_MIN + r.nextDouble() * CIRCLE_SPREAD,
                r.nextDouble() * LIFT_SPREAD,
                (r.nextDouble() * 2 - 1) * YAW_SPREAD,
            )
        }

        class Chain(val points: List<Vec3>, val struck: List<LivingEntity>)

        /** Both sides call this. A chain that strikes no creature throws nothing, not even a bolt to [metal]. */
        fun chain(level: Level, orb: Vec3, metal: Vec3?, owner: Entity?): Chain {
            val points = arrayListOf(orb)
            var from = orb
            if (metal != null) { points += metal; from = metal }
            val near = level.getEntitiesOfClass(LivingEntity::class.java, AABB(from, from).inflate(CHAIN_REACH * MAX_LINKS)) {
                it.isAlive && !it.isSpectator && it !== owner && it !is ArmorStand
            }
            val struck = ArrayList<LivingEntity>()
            repeat(MAX_LINKS) {
                val next = near.filter { it !in struck && it.middle().distanceTo(from) <= CHAIN_REACH }
                    .minByOrNull { it.middle().distanceToSqr(from) } ?: return done(orb, points, struck)
                struck += next
                from = next.middle()
                points += from
            }
            return done(orb, points, struck)
        }

        private fun done(orb: Vec3, points: List<Vec3>, struck: List<LivingEntity>): Chain =
            if (struck.isEmpty()) Chain(listOf(orb), struck) else Chain(points, struck)

        private class Volley { var fired = 0 }

        override fun start(cast: SignatureCast): SignatureEntity {
            cast.level.sound(cast.origin, EKSounds.SIGNATURE_FAESPARK_HAND_START.get(), 0.6f)
            return spawn(cast, cast.origin, HOLD_SLACK) {
                data = HAND_BIT
                state = Volley()
            }
        }

        override fun held(e: SignatureEntity, cast: SignatureCast, held: Int) {
            val origin = cast.origin
            e.setPos(origin)
            e.deltaMovement = Vec3.ZERO
            e.life = e.tickCount + HOLD_SLACK
            val volley = e.state as? Volley ?: return
            if (held % GAP != 0 || volley.fired >= ORBS) return
            val k = volley.fired++
            val aim = cast.aim
            spawn(cast, origin.add(aim.scale(0.5)), LIFE) {
                data = k
                state = Chase()
                deltaMovement = aim.yRot(lane(this).yaw.toFloat()).scale(SPEED)
            }
            cast.level.sound(origin, EKSounds.SIGNATURE_FAESPARK_LOOSE.get(), 1.2f, 1f + 0.03f * k)
        }

        override fun released(e: SignatureEntity, cast: SignatureCast, held: Int) {
            handStop(e)
            e.discard()
        }

        private fun handStop(e: SignatureEntity) =
            (e.level() as ServerLevel).sound(e.position(), EKSounds.SIGNATURE_FAESPARK_HAND_STOP.get(), 0.6f)

        override fun tick(e: SignatureEntity) {
            if (isHand(e)) return
            val level = e.level() as ServerLevel
            val here = e.position()
            if (e.tickCount % RETARGET_EVERY == 1) retarget(e, level, here)
            val lane = lane(e)
            val a = lane.angle + e.tickCount * CIRCLE_SPIN
            val side = Vec3(cos(a) * lane.radius, HOVER + lane.lift * 0.5, sin(a) * lane.radius)
            val ground = level.ground(here.x, here.z, here.y + 0.5, GROUND_SEARCH)
            val floor = ground?.plus(RIDE + lane.lift)
            var chased = (e.target() as? LivingEntity)?.takeIf { it.isAlive }
            if (chased != null) {
                val at = chased.middle().add(side)
                if (stuck(e, level, here, chased, at.distanceTo(here))) {
                    retarget(e, level, here)
                    chased = (e.target() as? LivingEntity)?.takeIf { it.isAlive }
                }
            }
            val metal = if (drawnToMetal(e)) e.point else null
            val goal = (chased?.let { it.middle().add(side) }
                ?: metal?.add(cos(a) * lane.radius * 0.5, METAL_HOVER + lane.lift * 0.5, sin(a) * lane.radius * 0.5))
                ?.let { if (floor != null && it.y < floor) Vec3(it.x, floor, it.z) else it }
            var v = e.deltaMovement
            if (goal != null) {
                val to = goal.subtract(here)
                val want = if (to.length() < 0.3) Vec3.ZERO else to.normalize().scale(SPEED)
                v = v.lerp(want, STEER)
            }
            v = v.add(separation(e, level, here).scale(SEPARATE))
            v = v.add(0.0, sin(e.tickCount * 0.3 + index(e)) * 0.01, 0.0)
            if (v.length() > MAX_SPEED) v = v.normalize().scale(MAX_SPEED)
            // Apply the clearance clamp last, so no other push takes the spark below it.
            if (floor != null) {
                val gap = floor - here.y
                if (gap > -0.3) v = Vec3(v.x, maxOf(v.y, gap * RISE), v.z)
                val low = ground + CLEARANCE - here.y
                if (v.y < low) v = Vec3(v.x, low, v.z)
            }
            val ahead = BlockPos.containing(here.add(v.x * 3, 0.0, v.z * 3))
            if (!level.getBlockState(ahead).getCollisionShape(level, ahead).isEmpty) v = Vec3(v.x * 0.5, maxOf(v.y, SPEED * 0.5), v.z * 0.5)
            e.deltaMovement = v
            if (chainsNow(e)) {
                val c = chain(level, here, chainMetal(e), e.caster)
                for (target in c.struck) {
                    e.hurtTargetThrough(target, EKDamageSources.CHAOS_KEY, DAMAGE)
                    target.setSecondsOnFire(BURN_SECONDS)
                }
            }
        }

        private fun separation(e: SignatureEntity, level: ServerLevel, here: Vec3): Vec3 {
            var away = Vec3.ZERO
            val others = level.getEntitiesOfClass(SignatureEntity::class.java, AABB(here, here).inflate(SEPARATION)) {
                it !== e && it.isAlive && it.signature == Signature.FAESPARK && !isHand(it)
            }
            for (other in others) {
                val off = here.subtract(other.position())
                val d = off.length()
                if (d >= SEPARATION) continue
                val dir = if (d < 1.0e-3) Vec3(cos(index(e) * 2.4), 0.0, sin(index(e) * 2.4)) else off.scale(1 / d)
                away = away.add(dir.scale(1 - d / SEPARATION))
            }
            return if (away.length() > 1) away.normalize() else away
        }

        private fun retarget(e: SignatureEntity, level: ServerLevel, here: Vec3) {
            val metal = nearestMetal(level, here)
            if (metal != null) e.point = metal
            e.data = index(e) or (if (metal != null) METAL_BIT else 0)
            val chase = e.state as? Chase
            val next = e.creatures(here, SEEK)
                .filter { chase?.ignored(it.id, e.tickCount) != true && reachable(level, here, it) }
                .minByOrNull { it.distanceToSqr(here) }
            if (next?.id != e.targetId) chase?.begin(next?.id ?: -1)
            e.targetId = next?.id ?: -1
        }

        private fun reachable(level: ServerLevel, here: Vec3, target: LivingEntity): Boolean {
            val to = target.middle()
            val ride = (level.ground(here.x, here.z, here.y + 0.5, GROUND_SEARCH) ?: (here.y - RIDE)) + RIDE
            if (Math.abs(to.y + HOVER - ride) > CLIMB) return false
            return level.clipIncludeShips(ClipContext(here, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target)).type == HitResult.Type.MISS
        }

        private class Chase {
            var id = -1
            var best = Double.MAX_VALUE
            var stalled = 0
            var unseen = 0
            val given = HashMap<Int, Int>()

            fun begin(target: Int) { id = target; best = Double.MAX_VALUE; stalled = 0; unseen = 0 }
            fun ignored(target: Int, now: Int): Boolean = (given[target] ?: Int.MIN_VALUE) > now
        }

        private fun stuck(e: SignatureEntity, level: ServerLevel, here: Vec3, target: LivingEntity, d: Double): Boolean {
            val chase = e.state as? Chase ?: return false
            if (chase.id != target.id) chase.begin(target.id)
            if (d < chase.best - PROGRESS || d <= ARRIVED) { chase.best = minOf(chase.best, d); chase.stalled = 0 } else chase.stalled++
            if (e.tickCount % 5 == 0) chase.unseen = if (reachable(level, here, target)) 0 else chase.unseen + 5
            if (chase.stalled < STALL_TICKS && chase.unseen < LOST_TICKS) return false
            chase.given[target.id] = e.tickCount + IGNORE_TICKS
            return true
        }

        private fun nearestMetal(level: ServerLevel, at: Vec3): Vec3? {
            var best: Vec3? = null
            var bestD = METAL_REACH * METAL_REACH
            val top = Vector3d()
            // The top face is half a block above the middle.
            CrossFrameBlocks(level, at, METAL_REACH + 0.5).forEachWithin(METAL_REACH + 0.5) { pos, ship ->
                if (!metal(level.getBlockState(pos))) return@forEachWithin
                top.set(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
                ship?.transform?.shipToWorld?.transformPosition(top)
                val d = top.distanceSquared(at.x, at.y, at.z)
                if (d < bestD && seen(level, at, Vec3(top.x, top.y, top.z))) { bestD = d; best = Vec3(top.x, top.y, top.z) }
            }
            return best
        }

        private fun seen(level: ServerLevel, at: Vec3, top: Vec3): Boolean =
            level.clip(colliderClip(at, top.add(0.0, 0.05, 0.0))).type == HitResult.Type.MISS

        private fun metal(state: BlockState): Boolean {
            if (state.isAir) return false
            val name = BuiltInRegistries.BLOCK.getKey(state.block).path
            if ("ore" in name || name.startsWith("raw_")) return false
            return "iron" in name || "copper" in name || name == "lightning_rod"
        }

        override fun end(e: SignatureEntity) {
            if (isHand(e)) { handStop(e); return }
            val level = e.level() as ServerLevel
            level.particles(ParticleTypes.ELECTRIC_SPARK, e.position(), 40, 0.5, 0.4)
            level.particles(ParticleTypes.FLASH, e.position(), 1, 0.0)
            level.sound(e.position(), EKSounds.SIGNATURE_FAESPARK_FIZZLE.get(), 1.4f)
        }
    }

    object Rotdance : SignatureBehaviour() {
        const val MOVING = 0
        const val SETTLED = 1
        const val FADING = 2
        const val FADE = 20
        private const val LIFE = 600
        private const val RANGE = 64.0
        private const val SEEK = 12.0
        private const val HOP_RANGE = 20.0
        private const val STAY = 60
        private const val FIRST_SPEED = 0.5
        private const val SPEED = 0.45
        private const val HOPS = 6
        private const val HOPS_PER_POWER = 6
        private const val POISON_TICKS = 200
        private const val POISON_LEVEL = 2
        private const val WEAK_TICKS = 160
        private const val DAMAGE = 8f
        /** About 16 damage a second. */
        private const val PULSE_DAMAGE = 4f
        private const val PULSE_EVERY = 5
        private const val SPILL = 3.0
        private const val SPILL_DAMAGE = 2f
        private const val ROT_TICKS = 200
        const val BURST = 5.0
        private const val BURST_DAMAGE = 26f
        private const val BURST_GAS = 0.8f

        private class Swarm(var hopsLeft: Int) {
            val visited = HashSet<Int>()
            var on: LivingEntity? = null
            var stay = 0
            var legs = 0
            var burst = false
        }

        private val SPORE = DustParticleOptions(Vector3f(0.58f, 0.76f, 0.35f), 1.2f)
        private val SPARK = DustParticleOptions(Vector3f(0.92f, 0.42f, 0.6f), 0.9f)

        override fun start(cast: SignatureCast): SignatureEntity {
            val aimed = cast.aimed(RANGE)
            val first = aimed.entity ?: cast.level.creatures(aimed.point, SEEK, cast.caster).minByOrNull { it.distanceToSqr(aimed.point) }
            cast.level.sound(cast.origin, EKSounds.SIGNATURE_ROTDANCE_CAST.get(), 1.5f)
            return spawn(cast, cast.origin, LIFE) {
                point = aimed.point
                targetId = first?.id ?: -1
                state = Swarm(HOPS + (HOPS_PER_POWER * cast.power).toInt())
            }
        }

        override fun tick(e: SignatureEntity) {
            val s = e.state as? Swarm ?: return
            if (e.data == FADING) return
            val here = e.position()
            s.on?.let { host ->
                if (host.isAlive && --s.stay > 0) {
                    e.deltaMovement = host.middle().subtract(here)
                    if (s.stay % PULSE_EVERY == 0) pulse(e, s, host)
                    return
                }
                s.on = null
                val next = if (s.hopsLeft > 0) nextHost(e, s, host.middle()) else null
                if (next == null) { fade(e); return }
                (e.level() as ServerLevel).sound(host.middle(), EKSounds.SIGNATURE_ROTDANCE_HOP.get(), 1.2f)
                e.targetId = next.id
                e.point = next.middle()
                e.data = MOVING
            }
            val target = (e.target() as? LivingEntity)?.takeIf { it.isAlive && it.id !in s.visited }
            val goal = target?.let { it.middle() } ?: e.point
            val to = goal.subtract(here)
            val speed = if (s.legs == 0) FIRST_SPEED else SPEED
            if (to.length() > speed + 0.2) {
                e.deltaMovement = to.normalize().scale(speed)
                return
            }
            e.deltaMovement = Vec3.ZERO
            if (target == null) {
                val near = nextHost(e, s, here)
                if (near == null) { fade(e); return }
                e.targetId = near.id
                e.point = near.middle()
                return
            }
            settle(e, s, target)
        }

        private fun settle(e: SignatureEntity, s: Swarm, host: LivingEntity) {
            val level = e.level() as ServerLevel
            s.visited += host.id
            s.on = host
            s.stay = STAY
            s.legs++
            s.hopsLeft--
            e.data = SETTLED
            host.addEffect(MobEffectInstance(MobEffects.POISON, POISON_TICKS, POISON_LEVEL), e.caster)
            host.addEffect(MobEffectInstance(MobEffects.WEAKNESS, WEAK_TICKS, 1), e.caster)
            host.addEffect(MobEffectInstance(EKEffects.ROT.get(), ROT_TICKS), e.caster)
            e.hurtTargetThrough(host, EKDamageSources.ROT_KEY, DAMAGE)
            val at = host.middle()
            level.particles(SPORE, at, 40, host.bbWidth * 0.7, 0.04)
            level.particles(SPARK, at, 16, host.bbWidth * 0.7, 0.08)
            level.particles(ParticleTypes.SNEEZE, at, 10, host.bbWidth * 0.5, 0.05)
            level.sound(at, EKSounds.SIGNATURE_ROTDANCE_SETTLE.get(), 1.4f)
        }

        private fun pulse(e: SignatureEntity, s: Swarm, host: LivingEntity) {
            val level = e.level() as ServerLevel
            val at = host.middle()
            e.hurtTargetThrough(host, EKDamageSources.ROT_KEY, PULSE_DAMAGE)
            for (near in e.creatures(at, SPILL + host.bbWidth * 0.5)) {
                if (near === host) continue
                e.hurtTargetThrough(near, EKDamageSources.ROT_KEY, SPILL_DAMAGE)
                near.addEffect(MobEffectInstance(MobEffects.POISON, POISON_TICKS / 2, 1), e.caster)
            }
            level.particles(SPORE, at, 20, SPILL * 0.5, 0.03)
            if (s.stay % 20 == 0) level.sound(at, EKSounds.SIGNATURE_ROTDANCE_FEED.get())
        }

        private fun nextHost(e: SignatureEntity, s: Swarm, from: Vec3): LivingEntity? =
            e.creatures(from, HOP_RANGE).filter { it.id !in s.visited }.minByOrNull { it.middle().distanceToSqr(from) }

        private fun fade(e: SignatureEntity) {
            e.data = FADING
            e.deltaMovement = Vec3.ZERO
            e.life = e.tickCount + FADE
            burst(e)
        }

        override fun end(e: SignatureEntity) = burst(e)

        private fun burst(e: SignatureEntity) {
            val s = e.state as? Swarm ?: return
            if (s.burst) return
            s.burst = true
            val level = e.level() as ServerLevel
            val at = e.position()
            val strength = 0.6 + 0.4 * e.power
            for (target in e.creatures(at, BURST)) {
                val near = 1 - 0.5 * target.middle().distanceTo(at) / BURST
                e.hurtTargetThrough(target, EKDamageSources.ROT_KEY, (BURST_DAMAGE * strength * near).toFloat())
                target.addEffect(MobEffectInstance(MobEffects.POISON, POISON_TICKS, POISON_LEVEL), e.caster)
                target.addEffect(MobEffectInstance(EKEffects.ROT.get(), ROT_TICKS), e.caster)
            }
            GasField.addBurst(level, at, BURST + 1, BURST_GAS)
            level.particles(SPORE, at, 160, BURST * 0.5, 0.1)
            level.particles(SPARK, at, 40, BURST * 0.4, 0.1)
            level.particles(ParticleTypes.SNEEZE, at, 60, BURST * 0.4, 0.08)
            level.particles(ParticleTypes.EXPLOSION, at, 2, 0.6)
            level.sound(at, EKSounds.SIGNATURE_ROTDANCE_BURST.get(), 2f)
        }
    }

    /** [SignatureEntity.data] holds the [phase] above the low byte and the charge ticks in the low byte.
     *  The client reads the charge to draw the correct size. */
    object Supernova : SignatureBehaviour() {
        const val CHARGE_TICKS = 60
        /** Ticks before the end of the burst at which the remnant fades. */
        const val FADE_BEFORE = 40
        const val AHEAD = 0.7
        const val BURST_TICKS = 70
        const val SHOCK_EVERY = 16
        const val CHARGING = 0
        const val FLYING = 1
        const val BURST = 2
        private const val SPEED = 1.2
        const val RANGE = 48.0
        const val RAY_REACH = RANGE / 2
        const val STAB_EVERY = 4
        const val MAX_RAYS = 12
        /** About 22 damage a second at full charge. */
        private const val RAY_DAMAGE = 4.5f
        private const val BLAST_MIN = 3.5
        private const val BLAST_MAX = 9.0
        private const val DAMAGE_MIN = 8f
        private const val DAMAGE_MAX = 32f
        private const val KNOCK = 0.6
        private const val KNOCK_PER_CHARGE = 1.4
        private const val BLIND_TICKS = 60
        private const val BLIND_PER_CHARGE = 140
        private const val SHIP_IMPULSE = 1.5
        private const val PULSE_EVERY = 8
        private const val PULSE_DAMAGE = 2f
        private const val PULSE_PER_CHARGE = 2f
        private const val SHOCK_KNOCK = 0.35
        private const val SHOCK_SHIP = 0.6
        private const val HOLD_SLACK = 20
        private const val FIRES = 8

        fun phase(e: SignatureEntity): Int = e.data shr 8
        fun charge(e: SignatureEntity): Float = (e.data and 0xFF).toFloat() / CHARGE_TICKS
        fun blastRadius(charge: Float): Double = BLAST_MIN + (BLAST_MAX - BLAST_MIN) * charge

        fun sinceBurst(e: SignatureEntity, age: Float): Float = age - (e.life - BURST_TICKS)

        private class Flight(val to: Vec3)

        fun stabsNow(e: SignatureEntity): Boolean =
            phase(e) == BURST && e.tickCount % STAB_EVERY == 0 && sinceBurst(e, e.tickCount.toFloat()) >= 0 && e.tickCount < e.life - 4

        /** Both sides call this. */
        fun rayTargets(level: Level, core: Vec3, owner: Entity?): List<LivingEntity> =
            level.getEntitiesOfClass(LivingEntity::class.java, AABB(core, core).inflate(RAY_REACH)) {
                it.isAlive && !it.isSpectator && it !== owner && it !is ArmorStand && it.middle().distanceTo(core) <= RAY_REACH
            }.filter {
                level.clip(ClipContext(core, it.middle(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, it)).type == HitResult.Type.MISS
            }.sortedBy { it.middle().distanceToSqr(core) }.take(MAX_RAYS)

        private fun stab(e: SignatureEntity) {
            if (!stabsNow(e)) return
            val level = e.level() as ServerLevel
            val core = e.position()
            val strength = 0.6f + 0.4f * charge(e)
            for (target in rayTargets(level, core, e.caster)) {
                e.hurtTargetThrough(target, EKDamageSources.SUNFIRE_KEY, RAY_DAMAGE * strength)
                target.setSecondsOnFire(2)
            }
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            return spawn(cast, cast.origin.add(cast.aim.scale(AHEAD)), HOLD_SLACK)
        }

        override fun held(e: SignatureEntity, cast: SignatureCast, held: Int) {
            e.setPos(cast.origin.add(cast.aim.scale(AHEAD)))
            e.deltaMovement = Vec3.ZERO
            e.life = e.tickCount + HOLD_SLACK
            e.data = minOf(held, CHARGE_TICKS)
        }

        override fun released(e: SignatureEntity, cast: SignatureCast, held: Int) {
            val ticks = minOf(held, CHARGE_TICKS)
            val here = cast.origin.add(cast.aim.scale(AHEAD))
            val to = cast.aimed(RANGE).point
            e.setPos(here)
            e.data = (FLYING shl 8) or ticks
            val span = to.subtract(here)
            if (span.length() < SPEED * 1.5) { detonate(e, here); return }
            e.state = Flight(to)
            e.deltaMovement = span.normalize().scale(SPEED)
            e.life = e.tickCount + (span.length() / SPEED).toInt() + 5
            cast.level.sound(cast.origin, EKSounds.SIGNATURE_SUPERNOVA_LOOSE.get(), 1.5f)
        }

        override fun tick(e: SignatureEntity) {
            stab(e)
            if (phase(e) == BURST) { aftermath(e); return }
            if (phase(e) != FLYING) return
            val flight = e.state as? Flight ?: return
            val here = e.position()
            val hit = strike(e, here, here.add(e.deltaMovement), 0.3)
            when {
                hit != null -> detonate(e, hit.at)
                here.distanceTo(flight.to) <= SPEED -> detonate(e, flight.to)
            }
        }

        override fun end(e: SignatureEntity) {
            if (phase(e) == FLYING) detonate(e, e.position())
        }

        private fun detonate(e: SignatureEntity, at: Vec3) {
            val level = e.level() as ServerLevel
            val c = charge(e)
            val r = blastRadius(c)
            e.setPos(at)
            e.deltaMovement = Vec3.ZERO
            e.data = (BURST shl 8) or (e.data and 0xFF)
            e.life = e.tickCount + BURST_TICKS
            val knock = KNOCK + KNOCK_PER_CHARGE * c
            for (target in e.creatures(at, r)) {
                val near = 1 - target.middle().distanceTo(at) / r
                e.hurtTargetThrough(target, EKDamageSources.SUNFIRE_KEY, ((DAMAGE_MIN + (DAMAGE_MAX - DAMAGE_MIN) * c) * (0.4 + 0.6 * near)).toFloat())
                target.setSecondsOnFire(4 + (6 * c).toInt())
                target.addEffect(MobEffectInstance(MobEffects.BLINDNESS, BLIND_TICKS + (BLIND_PER_CHARGE * c).toInt()), e.caster)
                val away = target.middle().subtract(at)
                if (away.lengthSqr() > 1.0e-6) target.spellPush(away.normalize().scale(knock * near).add(0.0, 0.35 * knock * near, 0.0))
            }
            blastShips(level, at, r, SHIP_IMPULSE * (0.3 + 0.7 * c))
            ignite(e, at, maxOf(1.5, r * 0.6), FIRES)
            level.particles(ParticleTypes.FLASH, at, 1, 0.0)
            level.particles(ParticleTypes.EXPLOSION_EMITTER, at, 1, 0.0)
            level.particles(ParticleTypes.EXPLOSION, at, 3 + (6 * c).toInt(), r * 0.35)
            level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 60 + (80 * c).toInt(), 0.3, 0.3, 0.3, 0.4 + 0.3 * c)
            level.particles(ParticleTypes.FLAME, at, 60, 0.8, 0.35)
            level.sound(at, EKSounds.SIGNATURE_SUPERNOVA_NOVA.get(), 2.5f + 2f * c)
        }

        private fun aftermath(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val since = sinceBurst(e, e.tickCount.toFloat()).toInt()
            if (e.tickCount == e.life - FADE_BEFORE) level.sound(e.position(), EKSounds.SIGNATURE_SUPERNOVA_FADE.get(), 1.5f)
            if (since <= 0 || e.tickCount >= e.life - 4) return
            val c = charge(e)
            val r = blastRadius(c)
            val at = e.position()
            val shock = since % SHOCK_EVERY == 0
            if (since % PULSE_EVERY == 0 || shock) for (target in e.creatures(at, r)) {
                val near = 1 - target.middle().distanceTo(at) / r
                if (since % PULSE_EVERY == 0) {
                    e.hurtTargetThrough(target, EKDamageSources.SUNFIRE_KEY, ((PULSE_DAMAGE + PULSE_PER_CHARGE * c) * (0.5 + 0.5 * near)).toFloat())
                    target.setSecondsOnFire(3)
                }
                if (shock) knockAway(target, at, (SHOCK_KNOCK + SHOCK_KNOCK * c) * (0.3 + 0.7 * near), 0.15 * near)
            }
            if (shock) {
                blastShips(level, at, r, SHOCK_SHIP * (0.3 + 0.7 * c))
                level.particles(ParticleTypes.EXPLOSION, at, 2 + (3 * c).toInt(), r * 0.3)
                level.sound(at, EKSounds.SIGNATURE_SUPERNOVA_SHOCK.get(), 1.5f + c)
            }
        }
    }
}
