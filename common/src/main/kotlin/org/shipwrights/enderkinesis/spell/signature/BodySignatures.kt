package org.shipwrights.enderkinesis.spell.signature

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

/**
 * Starfall, North Star, Hungering Maw, Faespark, Rotdance and Supernova:
 * bodies that leave the hand and do their work where they go.
 */
object BodySignatures {
    val ALL: Map<Signature, SignatureBehaviour> = mapOf(
        Signature.STARFALL to Starfall,
        Signature.NORTH_STAR to NorthStar,
        Signature.HUNGERING_MAW to HungeringMaw,
        Signature.FAESPARK to Faespark,
        Signature.ROTDANCE to Rotdance,
        Signature.SUPERNOVA to Supernova,
    )

    // ---- Shared ---------------------------------------------------------------------------------

    /** What a body moving from one point to the next struck first: a creature, a ship's block,
     *  or the world's block [block] on its [face]. */
    private class Strike(val at: Vec3, val entity: LivingEntity?, val ship: SignatureShips.ShipBlock?, val block: BlockPos?, val face: Direction?)

    /** The first thing [e] strikes moving from [a] to [b], [width] wide, or null. */
    private fun strike(e: SignatureEntity, a: Vec3, b: Vec3, width: Double): Strike? {
        val level = e.level() as ServerLevel
        val span = b.subtract(a)
        if (span.lengthSqr() < 1.0e-9) return null
        val hit = level.clipIncludeShips(ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e))
        val blockHit = hit.takeIf { it.type == HitResult.Type.BLOCK }
        val end = blockHit?.location ?: b
        // A creature before the block takes the blow.
        val creature = SignatureKit.creaturesAlong(e, a, end, width).minByOrNull { SignatureKit.middle(it).distanceToSqr(a) }
        if (creature != null) return Strike(nearestOn(a, end, SignatureKit.middle(creature)), creature, null, null, null)
        if (blockHit == null) return null
        val ship = SignatureShips.blockAt(level, end.add(span.normalize().scale(0.05)))
        return if (ship != null) Strike(end, null, ship, null, null) else Strike(end, null, null, blockHit.blockPos, blockHit.direction)
    }

    /** The point of segment [a]–[b] nearest [p]. */
    private fun nearestOn(a: Vec3, b: Vec3, p: Vec3): Vec3 {
        val ab = b.subtract(a)
        val len2 = ab.lengthSqr()
        if (len2 < 1.0e-9) return a
        return a.add(ab.scale((p.subtract(a).dot(ab) / len2).coerceIn(0.0, 1.0)))
    }

    /**
     * Lights up to [count] fires at random among the open spots within [r] of [centre] where fire
     * can stand, on the ground or on a ship's blocks. Callers light a few at a time, so fire is
     * left scattered, never a carpet.
     */
    private fun ignite(e: SignatureEntity, centre: Vec3, r: Double, count: Int) {
        if (count <= 0) return
        val level = e.level() as ServerLevel
        val spots = ArrayList<BlockPos>()
        val n = Math.ceil(r).toInt()
        val c = BlockPos.containing(centre)
        for (pos in BlockPos.betweenClosed(c.offset(-n, -n, -n), c.offset(n, n, n))) {
            if (Vec3.atCenterOf(pos).distanceTo(centre) > r) continue
            if (level.getBlockState(pos).isAir && BaseFireBlock.canBePlacedAt(level, pos, Direction.UP)) spots += pos.immutable()
        }
        for (block in SignatureShips.blocksNear(level, centre, r)) {
            val above = block.pos.above()
            if (level.getBlockState(above).isAir && BaseFireBlock.canBePlacedAt(level, above, Direction.UP)) spots += above
        }
        spots.shuffle(java.util.Random(level.random.nextLong()))
        var lit = 0
        for (pos in spots) {
            if (lit >= count) break
            if (SignatureKit.setBlock(e, pos, BaseFireBlock.getState(level, pos))) lit++
        }
    }

    /** Throws every ship block within [r] of [centre] outward on its own, [impulse] at the
     *  centre falling to nothing at the edge. */
    private fun blastShips(level: ServerLevel, centre: Vec3, r: Double, impulse: Double) {
        for (block in SignatureShips.blocksNear(level, centre, r)) {
            val away = block.world.subtract(centre)
            val d = away.length()
            if (d < 1.0e-3) continue
            SignatureShips.push(block.ship, block.world, away.scale(impulse * (1 - d / r) / d))
        }
    }

    /** Where the world point [p] is in [ship]'s yard. */
    private fun toShip(ship: LoadedServerShip, p: Vec3): Vec3 {
        val v = Vector3d()
        ship.transform.worldToShip.transformPosition(p.x, p.y, p.z, v)
        return Vec3(v.x, v.y, v.z)
    }

    /** Pushes [target] away from [centre], [strength] across and [lift] up. */
    private fun knockAway(target: Entity, centre: Vec3, strength: Double, lift: Double) {
        val away = SignatureKit.middle(target).subtract(centre).let { Vec3(it.x, 0.0, it.z) }
        val flat = if (away.lengthSqr() < 1.0e-6) Vec3.ZERO else away.normalize().scale(strength)
        SignatureKit.push(target, flat.add(0.0, lift, 0.0))
    }

    /** 0..1 eased in and out. */
    private fun smooth(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }

    // ---- Starfall: burning rocks called down from the sky ----------------------------------

    /**
     * Let go, and meteors are called down out of the sky onto the point aimed at, one after
     * another: [count] of them, [COUNT] at full charge. The first falls on the mark itself, the
     * rest scattered round it within [SCATTER], all from the same quarter of the sky. Each one
     * that strikes something is a full landing: creatures within [BLAST] are hurt, set alight and
     * thrown back, now and then a fire catches by it ([FIRE_CHANCE] each),
     * and on open ground it digs a crater with its glowing
     * rock in the bottom. On a ship it lands as one impulse at the block it struck, along its
     * fall, and ship blocks round it are thrown out.
     *
     * Both sides lay every meteor's fall out the same from [SignatureEntity.seed] and
     * [SignatureEntity.point] ([fall]); [SignatureEntity.data] holds a bit for each one that has
     * landed. The entity itself waits on the mark.
     */
    object Starfall : SignatureBehaviour() {
        const val COUNT = 14
        /** Ticks before the first meteor appears, and between one and the next. */
        const val DELAY = 6
        const val INTERVAL = 6
        /** Ticks each takes to fall from the sky to its mark, and may fall on past it before it
         *  bursts wherever it is. */
        const val FALL = 14
        const val OVERRUN = 14
        const val HEIGHT = 34.0
        const val SLANT = 14.0
        const val SCATTER = 7.0
        const val BLAST = 5.0
        private const val RANGE = 48.0
        private const val RANGE_PER_POWER = 32.0
        private const val SKY_JITTER = 0.5
        /** A full-charge meteor's blow at its middle; less for less charge. */
        private const val DAMAGE = 26f
        private const val KNOCK = 1.4
        private const val LIFT = 0.8
        private const val BURN_SECONDS = 6
        private const val SHIP_IMPULSE = 4.0
        private const val SHIP_BLAST = 1.5
        private const val WIDTH = 0.6
        /** The chance each meteor leaves a fire by it. */
        private const val FIRE_CHANCE = 0.6f

        /** How many meteors a cast of [power] calls down. */
        fun count(power: Float): Int = (1 + Math.round((COUNT - 1) * power)).coerceIn(1, COUNT)

        /** The tick meteor [k] appears in the sky. */
        fun startOf(k: Int): Int = DELAY + k * INTERVAL

        /** Whether meteor [k] has landed. */
        fun landed(e: SignatureEntity, k: Int): Boolean = e.data and (1 shl k) != 0

        /** One meteor's straight fall from [from] in the sky onto [mark], over [FALL] ticks and on. */
        class Fall(val from: Vec3, val mark: Vec3) {
            fun at(t: Double): Vec3 = from.add(mark.subtract(from).scale(t / FALL))
        }

        /** Meteor [k]'s fall: either side may ask. */
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
            val mark = Vec3(aimed.x, SignatureKit.groundNear(cast.level, aimed) ?: aimed.y, aimed.z)
            val n = count(cast.power)
            SignatureKit.sound(cast.level, cast.origin, SoundEvents.BLAZE_SHOOT, 1.4f, 0.5f)
            SignatureKit.sound(cast.level, mark, SoundEvents.FIRECHARGE_USE, 2f, 0.4f)
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
            if (left == 0) e.discard()
        }

        private fun land(e: SignatureEntity, k: Int, hit: Strike, motion: Vec3) {
            val level = e.level() as ServerLevel
            e.data = e.data or (1 shl k)
            val at = hit.at
            for (target in SignatureKit.creatures(e, at, BLAST)) {
                val near = 1 - 0.5 * SignatureKit.middle(target).distanceTo(at) / BLAST
                // Each meteor is its own blow, so a creature under several takes them all.
                SignatureKit.hurtThrough(e, target, EKDamageSources.HEAT_KEY, (DAMAGE * (0.6 + 0.4 * e.power) * near).toFloat())
                target.setSecondsOnFire(BURN_SECONDS)
                knockAway(target, at, KNOCK * near, LIFT * near)
            }
            // A ship takes the landing at the block it struck, along its fall, and the blocks
            // round it are thrown out.
            if (hit.ship != null && motion.lengthSqr() > 1.0e-6) {
                SignatureShips.push(hit.ship.ship, at, motion.normalize().scale(SHIP_IMPULSE))
                blastShips(level, at, BLAST * 0.6, SHIP_BLAST)
            }
            if (hit.block != null && hit.face == Direction.UP) crater(e, hit.block)
            // Now and then a meteor leaves a fire: a handful over the whole cast.
            if (level.random.nextFloat() < FIRE_CHANCE) ignite(e, at, 2.5, 1)
            SignatureKit.particles(level, ParticleTypes.EXPLOSION_EMITTER, at, 1, 0.0)
            SignatureKit.particles(level, ParticleTypes.EXPLOSION, at, 4, 1.2)
            SignatureKit.particles(level, ParticleTypes.LAVA, at.add(0.0, 0.3, 0.0), 30, 0.8)
            SignatureKit.particles(level, ParticleTypes.FLAME, at.add(0.0, 0.3, 0.0), 60, 1.0, 0.25)
            SignatureKit.particles(level, ParticleTypes.LARGE_SMOKE, at.add(0.0, 0.5, 0.0), 30, 1.2, 0.08)
            level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, Blocks.MAGMA_BLOCK.defaultBlockState()), at.x, at.y + 0.3, at.z, 40, 0.8, 0.4, 0.8, 0.3)
            SignatureKit.sound(level, at, SoundEvents.GENERIC_EXPLODE, 2.5f, 0.6f)
            SignatureKit.sound(level, at, SoundEvents.LAVA_POP, 1.5f, 0.7f)
        }

        /** A crater where it struck the ground at [struck]: that block and the four round it dug
         *  out where they're soft enough, its rock left glowing in the middle of the floor, the
         *  rest of the floor and the ground round it scorched. */
        private fun crater(e: SignatureEntity, struck: BlockPos) {
            val level = e.level() as ServerLevel
            for (dx in -2..2) for (dz in -2..2) {
                val ring = dx * dx + dz * dz
                if (ring > 5) continue
                val pos = struck.offset(dx, 0, dz)
                val state = level.getBlockState(pos)
                if (ring <= 1) {
                    if (!diggable(level, pos, state) || !level.getBlockState(pos.above()).canBeReplaced()) continue
                    SignatureKit.setBlock(e, pos, Blocks.AIR.defaultBlockState())
                    val floor = pos.below()
                    val floorState = level.getBlockState(floor)
                    if (!diggable(level, floor, floorState)) continue
                    if (ring == 0) SignatureKit.setBlock(e, floor, Blocks.MAGMA_BLOCK.defaultBlockState())
                    else scorched(floorState)?.let { SignatureKit.setBlock(e, floor, it) }
                } else if (level.random.nextBoolean()) scorched(state)?.let { SignatureKit.setBlock(e, pos, it) }
            }
        }

        private fun diggable(level: ServerLevel, pos: BlockPos, state: BlockState): Boolean {
            if (state.isAir || state.hasBlockEntity() || !state.fluidState.isEmpty) return false
            val hardness = state.getDestroySpeed(level, pos)
            return hardness in 0f..MAX_DIG_HARDNESS
        }

        /** What [state] is once burnt: earth to coarse dirt; anything else stays as it is. */
        private fun scorched(state: BlockState): BlockState? =
            if (state.`is`(BlockTags.DIRT) && !state.`is`(Blocks.COARSE_DIRT)) Blocks.COARSE_DIRT.defaultBlockState() else null

        /** Cobblestone is as hard as the crater digs. */
        private const val MAX_DIG_HARDNESS = 2f
    }

    // ---- North Star: a frost bolt that sticks and spins a ring of ice -------------------------

    /**
     * A frost bolt that stops on the first thing it hits (or at its range, in the air) and stays
     * there [HOLD] ticks. A creature it strikes takes a hard cold blow, and the bolt then circles
     * it: from the spot on its side where it struck, it glides out over [GLIDE] ticks to
     * [CIRCLE_GAP] off its side and wheels round it ([circling]). Stuck to a ship it rides the
     * block it struck and pulls down there with a set force each tick. A ring of ice shards grows
     * round it, grinding whatever comes within [GRIND]: cold damage through i-frames, slowness,
     * and cold that builds until it freezes solid. The ring freezes water within [WATER_REACH].
     *
     * [SignatureEntity.data] holds the tick it stuck ([stuckAt], 0 while it's flying) and whether
     * it's on a ship ([SHIP_BIT]). [SignatureEntity.point] is where it struck: from the creature's
     * middle while it circles one ([SignatureEntity.targetId]), otherwise in the world.
     */
    object NorthStar : SignatureBehaviour() {
        const val SPEED = 1.6
        const val HOLD = 140
        /** The ice ring's radius: a wide wheel of shards round the core. */
        const val ORBIT = 8.0
        /** Ticks the ring takes to grow out to [ORBIT], and to shrink away at the end. */
        const val GROW = 15
        const val SHRINK = 12
        /** Ticks it glides over from where it struck a creature out to its circle round it. */
        const val GLIDE = 12
        const val CIRCLE_GAP = 2.5
        /** Radians a tick it wheels round a creature. */
        const val CIRCLE_SPIN = 0.1
        const val SHIP_BIT = 1 shl 16
        const val RANGE = 48.0
        const val RANGE_PER_POWER = 24.0
        private const val GRIND = ORBIT + 1.0
        private const val GRIND_EVERY = 5
        /** Four grinds a second at this is about 18 a second to anything in the ring. */
        private const val GRIND_DAMAGE = 4.5f
        /** The bolt's blow at full charge; less for less. */
        private const val IMPACT_DAMAGE = 26f
        private const val FREEZE_DOSE = 80
        private const val SLOW_LEVEL = 3
        private const val SHIP_PULL = 0.1
        /** Wider than before, but kept short of the ring so a lake under it isn't frozen thousands of blocks at once. */
        private const val WATER_REACH = 8.0
        private const val WATER_EVERY = 10

        private class Orbit(val range: Double) {
            var flown = 0.0
            var stuck = false
            var ship: LoadedServerShip? = null
            var local: Vec3 = Vec3.ZERO
        }

        /** When it stuck, from its synced [SignatureEntity.data] (0 while it's still flying). */
        fun stuckAt(e: SignatureEntity): Int = e.data and 0xFFFF

        fun onShip(e: SignatureEntity): Boolean = e.data and SHIP_BIT != 0

        /** How far a cast of [power] flies before it stops in the air. */
        fun range(power: Float): Double = RANGE + RANGE_PER_POWER * power

        /**
         * Where its core is [since] ticks after it struck a creature [width] wide whose middle is
         * now [middle], having struck it at [contact] from that middle: gliding smoothly out from
         * there to [CIRCLE_GAP] off its side, then wheeling round it level. Either side may ask.
         */
        fun circling(middle: Vec3, width: Float, contact: Vec3, since: Double): Vec3 {
            val ease = smooth(since / GLIDE)
            val r = hypot(contact.x, contact.z).coerceAtLeast(0.05)
            val a = atan2(contact.z, contact.x) + CIRCLE_SPIN * since
            val radius = r + (width * 0.5 + CIRCLE_GAP - r) * ease
            return middle.add(cos(a) * radius, contact.y * (1 - 0.6 * ease), sin(a) * radius)
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            val aim = cast.aim
            SignatureKit.sound(cast.level, cast.origin, SoundEvents.SNOW_GOLEM_SHOOT, 1.4f, 0.5f)
            return spawn(cast, cast.origin, ((RANGE + RANGE_PER_POWER) / SPEED).toInt() + HOLD + 2) {
                deltaMovement = aim.scale(SPEED)
                state = Orbit(range(cast.power))
            }
        }

        override fun tick(e: SignatureEntity) {
            val o = e.state as? Orbit ?: return
            val level = e.level() as ServerLevel
            if (!o.stuck) {
                val here = e.position()
                // Its first tick's step out of the hand counts too.
                val from = if (e.tickCount <= 1) here.subtract(e.deltaMovement) else here
                val hit = strike(e, from, here.add(e.deltaMovement), 0.3)
                o.flown += SPEED
                if (hit != null) stick(e, o, hit, from, here.add(e.deltaMovement)) else if (o.flown >= o.range) stick(e, o, Strike(here, null, null, null, null), here, here)
                return
            }
            val since = (e.tickCount - stuckAt(e)).toDouble()
            // Circling the creature it struck, riding the ship, or still in the air.
            val host = (e.target() as? LivingEntity)?.takeIf { it.isAlive }
            if (host == null && e.targetId >= 0) {
                e.point = e.position()
                e.targetId = -1
            }
            val core = host?.let { circling(SignatureKit.middle(it), it.bbWidth, e.point, since) }
                ?: o.ship?.let { SignatureShips.toWorld(it, o.local) }
                ?: e.point
            e.deltaMovement = Vec3.ZERO
            e.setPos(core)
            o.ship?.let { SignatureShips.push(it, core, Vec3(0.0, -SHIP_PULL, 0.0)) }
            if (since > GROW / 3 && e.tickCount % GRIND_EVERY == 0) for (target in SignatureKit.creatures(e, core, GRIND)) grind(e, target)
            if (e.tickCount % WATER_EVERY == 0) freezeWater(e, core)
            if (e.tickCount % 16 == 0) SignatureKit.sound(level, core, SoundEvents.POWDER_SNOW_BREAK, 1.2f, 0.5f)
        }

        /** It stops on [hit], having flown from [a] toward [b]. A creature is struck where the
         *  bolt meets its side, not at its middle. */
        private fun stick(e: SignatureEntity, o: Orbit, hit: Strike, a: Vec3, b: Vec3) {
            val level = e.level() as ServerLevel
            o.stuck = true
            val host = hit.entity
            val at = host?.let { it.boundingBox.inflate(0.3).clip(a, b).orElse(hit.at) } ?: hit.at
            e.deltaMovement = Vec3.ZERO
            e.setPos(at)
            e.life = e.tickCount + HOLD
            if (host != null) {
                e.point = at.subtract(SignatureKit.middle(host))
                e.targetId = host.id
                SignatureKit.hurtThrough(e, host, EKDamageSources.COLD_KEY, IMPACT_DAMAGE * (0.6f + 0.4f * e.power))
                host.ticksFrozen = minOf(host.ticksFrozen + FREEZE_DOSE * 2, FrozenEffect.THRESHOLD + 200)
            } else e.point = at
            hit.ship?.let { o.ship = it.ship; o.local = toShip(it.ship, at) }
            e.data = e.tickCount.coerceIn(1, 0xFFFF) or (if (hit.ship != null) SHIP_BIT else 0)
            SignatureKit.sound(level, at, SoundEvents.GLASS_BREAK, 1.5f, 1.2f)
            SignatureKit.sound(level, at, SoundEvents.AMETHYST_BLOCK_CHIME, 1.5f, 0.6f)
            level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, Blocks.PACKED_ICE.defaultBlockState()), at.x, at.y, at.z, 30, 0.3, 0.3, 0.3, 0.15)
            SignatureKit.particles(level, ParticleTypes.SNOWFLAKE, at, 30, 0.5, 0.1)
        }

        private fun grind(e: SignatureEntity, target: LivingEntity) {
            SignatureKit.hurtThrough(e, target, EKDamageSources.COLD_KEY, GRIND_DAMAGE)
            target.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, SLOW_LEVEL), e.caster)
            // Cold builds until it freezes the target solid, as Cold's does.
            target.ticksFrozen = minOf(target.ticksFrozen + FREEZE_DOSE, FrozenEffect.THRESHOLD + 200)
            val frozen = EKEffects.FROZEN.get()
            if (target.ticksFrozen >= FrozenEffect.THRESHOLD && !target.hasEffect(frozen)) target.addEffect(MobEffectInstance(frozen, 80), e.caster)
        }

        /** Water within [WATER_REACH] of [core], the world's and any ship's, freezes. */
        private fun freezeWater(e: SignatureEntity, core: Vec3) {
            val level = e.level() as ServerLevel
            val r = Math.ceil(WATER_REACH).toInt()
            val centre = BlockPos.containing(core)
            for (pos in BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
                if (Vec3.atCenterOf(pos).distanceTo(core) > WATER_REACH) continue
                FluidFreezing.frozen(level.getBlockState(pos))?.let { SignatureKit.setBlock(e, pos.immutable(), it) }
            }
            for (block in SignatureShips.blocksNear(level, core, WATER_REACH)) FluidFreezing.frozen(block.state)?.let { SignatureKit.setBlock(e, block.pos, it) }
        }

        override fun end(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val at = e.position()
            level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, Blocks.ICE.defaultBlockState()), at.x, at.y, at.z, 160, ORBIT * 0.6, 0.5, ORBIT * 0.6, 0.15)
            SignatureKit.sound(level, at, SoundEvents.GLASS_BREAK, 1.5f, 0.8f)
        }
    }

    // ---- Hungering Maw: a drifting sphere onto the End -----------------------------------------

    /**
     * A dark sphere that leaves the hand and drifts slowly on, an Abyssal Pocket in the air:
     * what it swallows goes in there. It leaves the blocks it drifts into as they are. Projectiles,
     * dropped items and small creatures within [REACH] are drawn in and swallowed at [SWALLOW]:
     * creatures go Absent for a while, and items (and a player's arrows and tridents) are given
     * back where it closes; other projectiles are gone. Larger creatures are held at its edge.
     * Creatures are dragged in hard enough that one running away from the middle of its reach
     * can't get clear, lifted a touch off the ground so they don't snag on it ([drag]). Its
     * hunger reaches past the sphere: anything within [BITE] (about twice its drawn size) is
     * gnawed at through i-frames, harder nearer the middle. Each ship in reach is drawn toward
     * its centre with a set pull, at its nearest block.
     *
     * It eats the world round it too: every [TEAR_EVERY] ticks it rips up the [TEAR_COUNT]
     * nearest weak blocks within [TEAR_REACH] ([eatable]: soft as dirt, sand, gravel, leaves,
     * plants, glass or wool; never a block with a block entity, a fluid, or anything unbreakable),
     * with whatever loose plant stands on them. They're taken as the caster could break them by
     * hand, and gone: no drops. The client sees each one go and draws it flying into the maw.
     * Ship blocks aren't eaten; ships only take its pull.
     */
    object HungeringMaw : SignatureBehaviour() {
        const val REACH = 20.0
        const val SWALLOW = 2.0
        const val EDGE = 3.2
        /** The sphere's drawn radius. */
        const val SPHERE = 1.7
        /** Ticks it grows in over, and still and shrinks away over at the end. */
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
        /** The speed (blocks a tick) a creature is dragged in at, at the edge of [REACH] and at
         *  the middle; a sprint is about 0.28. */
        private const val DRAG_EDGE = 0.25
        private const val DRAG_MIDDLE = 0.7
        /** How much of the way to that speed a creature's motion is taken each tick. */
        private const val DRAG_GRIP = 0.35
        /** Lift a tick for a creature on the ground being dragged, so it doesn't catch on it. */
        private const val DRAG_LIFT = 0.1
        const val BITE = 4.5
        /** Its bite at the middle, every [BITE_EVERY] ticks: about 25 a second there, 15 at its edge hold. */
        private const val BITE_DAMAGE = 5f
        private const val BITE_EVERY = 4
        const val TEAR_REACH = 6.0
        private const val TEAR_EVERY = 2
        private const val TEAR_COUNT = 3
        /** The hardest block it can rip up: a little tougher than gravel, softer than stone. */
        private const val TEAR_HARDNESS = 1.5f
        private const val SHIP_PULL = 0.5
        private const val SHIP_EVERY = 5

        private class Horizon(val drift: Vec3) {
            /** What it swallowed to give back, out of the world meanwhile. */
            val kept = ArrayList<Entity>()
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            val drift = cast.aim.scale(SPEED + SPEED_PER_POWER * cast.power)
            SignatureKit.sound(cast.level, cast.origin, SoundEvents.ENDERMAN_TELEPORT, 1.6f, 0.4f)
            SignatureKit.sound(cast.level, cast.origin, SoundEvents.END_PORTAL_SPAWN, 0.6f, 1.6f)
            return spawn(cast, cast.origin.add(cast.aim.scale(SPHERE + 0.6)), LIFE + (LIFE_PER_POWER * cast.power).toInt()) { state = Horizon(drift) }
        }

        override fun tick(e: SignatureEntity) {
            val h = e.state as? Horizon ?: return
            val level = e.level() as ServerLevel
            val n = e.tickCount
            e.deltaMovement = if (n >= e.life - CLOSE) Vec3.ZERO else h.drift.scale(minOf(1.0, n.toDouble() / GROW))
            val core = e.position()
            for (entity in level.getEntities(e, AABB(core, core).inflate(REACH))) {
                if (entity === e.caster || entity.isSpectator || !entity.isAlive) continue
                val d = SignatureKit.middle(entity).distanceTo(core)
                if (d > REACH) continue
                when (entity) {
                    is LivingEntity -> catch(e, entity, core, d)
                    is ItemEntity, is Projectile -> if (d <= SWALLOW) swallow(e, h, entity) else SignatureKit.pull(entity, core, ITEM_PULL + ITEM_PULL_NEAR * (1 - d / REACH))
                }
            }
            // Each ship in reach is drawn in by its nearest block.
            if (n % SHIP_EVERY == 0) for (block in SignatureShips.nearestBlocks(level, core, REACH)) {
                val to = core.subtract(block.world)
                if (to.lengthSqr() > 1.0e-6) SignatureShips.push(block.ship, block.world, to.normalize().scale(SHIP_PULL))
            }
            if (n % TEAR_EVERY == 0 && n >= GROW && n < e.life - CLOSE) tear(e, level, core)
            if (n % 24 == 0) SignatureKit.sound(level, core, SoundEvents.PORTAL_AMBIENT, 1.2f, 0.9f)
        }

        /** A creature within reach: bitten if it's within [BITE]; then a small one swallowed, a
         *  larger one held at the edge, the rest dragged in. */
        private fun catch(e: SignatureEntity, target: LivingEntity, core: Vec3, d: Double) {
            if (target is ArmorStand || Absence.isAbsent(target)) return
            if (d <= BITE + target.bbWidth * 0.5 && e.tickCount % BITE_EVERY == 0) {
                SignatureKit.hurtThrough(e, target, EKDamageSources.VOID_KEY, (BITE_DAMAGE * (1 - 0.5 * minOf(1.0, d / BITE))).toFloat())
            }
            if (!SignatureKit.movable(target)) return
            val small = target !is Player && target.bbWidth <= SMALL_WIDTH && target.bbHeight <= SMALL_HEIGHT
            when {
                small && d <= SWALLOW + target.bbWidth * 0.5 -> {
                    target.addEffect(MobEffectInstance(EKEffects.ABSENT.get(), ABSENT_TICKS), e.caster)
                    SignatureKit.sound(e.level() as ServerLevel, core, SoundEvents.ENDERMAN_TELEPORT, 1.2f, 1.3f)
                }
                !small && d <= EDGE + target.bbWidth * 0.5 -> {
                    // Held at the edge: drawn to the point of the edge it's nearest, and stilled there.
                    val out = SignatureKit.middle(target).subtract(core).let { if (it.lengthSqr() < 1.0e-6) Vec3(1.0, 0.0, 0.0) else it.normalize() }
                    val spot = core.add(out.scale(EDGE + target.bbWidth * 0.5))
                    target.deltaMovement = spot.subtract(SignatureKit.middle(target)).scale(HOLD_PULL)
                    target.hurtMarked = true
                    target.fallDistance = 0f
                }
                else -> drag(target, core, d)
            }
        }

        /** Drags [target], [d] from [core], toward it: its motion is taken most of the way to a
         *  set speed inward each tick, faster nearer the middle, so walking or running away
         *  doesn't get it clear, and it's lifted a touch off the ground so it doesn't snag. */
        private fun drag(target: LivingEntity, core: Vec3, d: Double) {
            val to = core.subtract(SignatureKit.middle(target))
            if (to.lengthSqr() < 1.0e-6) return
            val want = to.normalize().scale(DRAG_EDGE + (DRAG_MIDDLE - DRAG_EDGE) * (1 - d / REACH))
            var v = target.deltaMovement.lerp(want, DRAG_GRIP)
            if (target.onGround()) v = v.add(0.0, DRAG_LIFT, 0.0)
            target.deltaMovement = v
            target.hurtMarked = true
            target.fallDistance = 0f
        }

        /** Whether the block [state] at [pos] is weak enough for it to rip up and eat. Either
         *  side may ask. */
        fun eatable(level: Level, pos: BlockPos, state: BlockState): Boolean {
            if (state.isAir || state.hasBlockEntity() || !state.fluidState.isEmpty) return false
            return state.getDestroySpeed(level, pos) in 0f..TEAR_HARDNESS
        }

        /** Rips up the nearest weak blocks round [core], each with any loose plant on top of it,
         *  as the caster could by hand. Removed straight away, so nothing drops and nothing is
         *  drawn wiping away in place: the client draws them flying in instead. */
        private fun tear(e: SignatureEntity, level: ServerLevel, core: Vec3) {
            val ctx = e.ctx ?: return
            val r = Math.ceil(TEAR_REACH).toInt()
            val centre = BlockPos.containing(core)
            val near = ArrayList<BlockPos>()
            for (pos in BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
                if (Vec3.atCenterOf(pos).distanceTo(core) > TEAR_REACH) continue
                if (eatable(level, pos, level.getBlockState(pos))) near += pos.immutable()
            }
            near.sortBy { Vec3.atCenterOf(it).distanceToSqr(core) }
            val air = Blocks.AIR.defaultBlockState()
            for (pos in near.take(TEAR_COUNT)) {
                // A loose plant on top goes first, so it isn't knocked off and dropped.
                val above = pos.above()
                val top = level.getBlockState(above)
                if (eatable(level, above, top) && top.getCollisionShape(level, above).isEmpty && ctx.mayChange(above, air)) level.setBlock(above, air, 3)
                val state = level.getBlockState(pos)
                if (!ctx.mayChange(pos, air) || !level.setBlock(pos, air, 3)) continue
                if (level.random.nextInt(3) == 0) {
                    val at = Vec3.atCenterOf(pos)
                    SignatureKit.sound(level, at, state.soundType.breakSound, 0.8f, 0.6f)
                }
            }
        }

        /** Takes [entity] out of the world: kept to give back if it's something a player would
         *  want back, otherwise gone. */
        private fun swallow(e: SignatureEntity, h: Horizon, entity: Entity) {
            val level = e.level() as ServerLevel
            val keep = entity is ItemEntity || entity is ThrownTrident || (entity is AbstractArrow && entity.pickup == AbstractArrow.Pickup.ALLOWED)
            if (keep) entity.type.create(level)?.let { copy -> copy.restoreFrom(entity); h.kept += copy }
            entity.discard()
            SignatureKit.particles(level, ParticleTypes.REVERSE_PORTAL, e.position(), 16, 0.4, 0.05)
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
            SignatureKit.particles(level, ParticleTypes.REVERSE_PORTAL, core, 120, 1.2, 0.3)
            SignatureKit.sound(level, core, SoundEvents.CHORUS_FRUIT_TELEPORT, 2f, 0.5f)
        }
    }

    // ---- Faespark: slow orbs that chain bolts through what's near ------------------------

    /**
     * Held: while the cast is held, a spark crackles in the hand ([isHand]) and every [GAP] ticks
     * it fires another forward along where the caster is looking, up to [ORBS] in all. Letting go
     * only stops the firing; the sparks already out live on.
     *
     * Each spark keeps its own lane ([lane], from its seed and number), so a volley reads as a
     * loose scatter rather than a clump: it leaves the hand a little off the aim to its own side,
     * rides at its own height ([RIDE] plus up to [LIFT_SPREAD]) above the ground beneath it, and
     * round a creature it circles at its own angle and distance, so several after one creature
     * come at it from all sides. It never sinks nearer the ground than [CLEARANCE], and rises
     * smoothly over what it meets. Sparks closer than [SEPARATION] are also nudged gently apart.
     *
     * It drifts toward the nearest creature within [SEEK] it can reach ([reachable]: in sight,
     * and not far above or below the height it rides at); one it loses sight of for [LOST_TICKS],
     * or gets no nearer to for [STALL_TICKS], it gives up on for [IGNORE_TICKS] and picks another,
     * or drifts on. With no creature to chase, it drifts to an iron or copper block within
     * [METAL_REACH] that it can see (ores in the rock don't count).
     *
     * Every [CHAIN_EVERY] ticks it throws a chain of bolts through the creatures near it
     * ([chain]); only once every [METAL_EVERY] ticks does the chain run by way of its metal
     * block ([chainMetal]). A chain only ever arcs at creatures: with no creature in reach it
     * throws nothing at all, so it never shocks bare ground or the same spot over and over.
     * Bolts shock through i-frames and burn.
     *
     * [SignatureEntity.data] holds the spark's number in the volley ([index]) and whether it's
     * drawn to metal ([METAL_BIT]), or [HAND_BIT] for the hand's own entity;
     * [SignatureEntity.point] holds the metal block's top. Both sides throw the same chains from
     * them.
     */
    object Faespark : SignatureBehaviour() {
        const val ORBS = 10
        /** Ticks between one spark leaving the hand and the next while held: about three quarters of a second. */
        const val GAP = 15
        const val LIFE = 280
        const val CHAIN_EVERY = 8
        /** Ticks between one chain by way of a metal block and the next, for each spark. */
        const val METAL_EVERY = 40
        const val CHAIN_REACH = 12.0
        const val MAX_LINKS = 6
        const val METAL_BIT = 16
        const val HAND_BIT = 32
        /** Ticks the hand's entity outlives the last held tick. */
        private const val HOLD_SLACK = 10
        private const val SPEED = 0.22
        private const val MAX_SPEED = 0.36
        private const val STEER = 0.15
        private const val SEEK = 32.0
        private const val METAL_REACH = 12.0
        private const val HOVER = 0.8
        /** How high above the ground beneath it a spark rides, and how fast (of the gap a tick)
         *  it rises back to that height. */
        private const val RIDE = 1.6
        private const val RISE = 0.2
        private const val GROUND_SEARCH = 16
        private const val METAL_HOVER = 1.4
        /** The nearest a spark ever comes to the ground beneath it. */
        private const val CLEARANCE = 1.0
        /** A spark's lane: up to this much higher than [RIDE] it rides, up to this far either side
         *  of the aim it leaves on (radians), and between these distances it circles a creature. */
        private const val LIFT_SPREAD = 1.6
        private const val YAW_SPREAD = 0.25
        private const val CIRCLE_MIN = 2.0
        private const val CIRCLE_SPREAD = 2.0
        /** Radians a tick a spark wheels round the creature it's after. */
        private const val CIRCLE_SPIN = 0.04
        /** Turn between one spark's side of a creature and the next's: the golden angle, so any
         *  number of them spread evenly round it. */
        private const val GOLDEN = 2.39996
        /** Sparks closer than this are nudged gently apart, by up to [SEPARATE] a tick. */
        private const val SEPARATION = 2.5
        private const val SEPARATE = 0.03
        private const val RETARGET_EVERY = 10
        /** How far above or below the height it rides at a creature may be and still be reached. */
        private const val CLIMB = 5.0
        /** Within this of its goal it's arrived, and isn't counted as stalled. */
        private const val ARRIVED = 2.5
        /** It must close on its target by this to count as getting nearer. */
        private const val PROGRESS = 0.25
        private const val STALL_TICKS = 40
        private const val LOST_TICKS = 20
        private const val IGNORE_TICKS = 100
        private const val DAMAGE = 2.5f
        private const val BURN_SECONDS = 3

        fun index(e: SignatureEntity): Int = e.data and 15

        /** Whether [e] is the hand's entity, firing the sparks, rather than a spark. */
        fun isHand(e: SignatureEntity): Boolean = e.data and HAND_BIT != 0
        fun drawnToMetal(e: SignatureEntity): Boolean = e.data and METAL_BIT != 0

        /** Whether this tick is one where spark [e] throws its chain. */
        fun chainsNow(e: SignatureEntity): Boolean = !isHand(e) && (e.tickCount + index(e) * 2) % CHAIN_EVERY == 0

        /** The metal block spark [e]'s chain runs by way of this tick, if any: only once every
         *  [METAL_EVERY] ticks. Either side may ask. */
        fun chainMetal(e: SignatureEntity): Vec3? =
            if (drawnToMetal(e) && (e.tickCount + index(e) * 2) % METAL_EVERY == 0) e.point else null

        /** A spark's own lane: its side of a creature ([angle]), how far out it circles one
         *  ([radius]), how much higher than [RIDE] it rides ([lift]), and how far off the aim it
         *  leaves the hand ([yaw]). */
        class Lane(val angle: Double, val radius: Double, val lift: Double, val yaw: Double)

        /** Spark [e]'s lane, from its seed and number. */
        fun lane(e: SignatureEntity): Lane {
            val r = Random(e.seed * 7 + index(e))
            return Lane(
                index(e) * GOLDEN + r.nextDouble() * 0.6,
                CIRCLE_MIN + r.nextDouble() * CIRCLE_SPREAD,
                r.nextDouble() * LIFT_SPREAD,
                (r.nextDouble() * 2 - 1) * YAW_SPREAD,
            )
        }

        /** One chain: the points it runs through, and the creatures it struck. */
        class Chain(val points: List<Vec3>, val struck: List<LivingEntity>)

        /**
         * The chain a spark at [orb] throws: by way of [metal] first if it's given one, then to
         * the nearest creature within [CHAIN_REACH], from there to the nearest not yet struck, and
         * so on, up to [MAX_LINKS]. [owner] is never struck. If it strikes no creature it throws
         * nothing: no bolt to the metal or anywhere else. Either side may ask.
         */
        fun chain(level: Level, orb: Vec3, metal: Vec3?, owner: Entity?): Chain {
            val points = arrayListOf(orb)
            var from = orb
            if (metal != null) { points += metal; from = metal }
            val near = level.getEntitiesOfClass(LivingEntity::class.java, AABB(from, from).inflate(CHAIN_REACH * MAX_LINKS)) {
                it.isAlive && !it.isSpectator && it !== owner && it !is ArmorStand
            }
            val struck = ArrayList<LivingEntity>()
            repeat(MAX_LINKS) {
                val next = near.filter { it !in struck && SignatureKit.middle(it).distanceTo(from) <= CHAIN_REACH }
                    .minByOrNull { SignatureKit.middle(it).distanceToSqr(from) } ?: return done(orb, points, struck)
                struck += next
                from = SignatureKit.middle(next)
                points += from
            }
            return done(orb, points, struck)
        }

        /** A chain that struck no one is no chain. */
        private fun done(orb: Vec3, points: List<Vec3>, struck: List<LivingEntity>): Chain =
            if (struck.isEmpty()) Chain(listOf(orb), struck) else Chain(points, struck)

        /** How many sparks the hand has fired. */
        private class Volley { var fired = 0 }

        /** The hold begins: a spark crackling in the hand, firing from the first held tick. */
        override fun start(cast: SignatureCast): SignatureEntity {
            SignatureKit.sound(cast.level, cast.origin, SoundEvents.LIGHTNING_BOLT_IMPACT, 0.6f, 1.8f)
            return spawn(cast, cast.origin, HOLD_SLACK) {
                data = HAND_BIT
                state = Volley()
            }
        }

        /** While held it follows the hand, and every [GAP] ticks fires a spark forward along the
         *  caster's aim, until [ORBS] are out. */
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
                // Off the aim a little, to its own side.
                deltaMovement = aim.yRot(lane(this).yaw.toFloat()).scale(SPEED)
            }
            SignatureKit.sound(cast.level, origin, SoundEvents.FIRECHARGE_USE, 1.2f, 1.3f + 0.05f * k)
            SignatureKit.sound(cast.level, origin, SoundEvents.LIGHTNING_BOLT_IMPACT, 0.5f, 1.8f)
        }

        /** Let go: the firing stops; the sparks already out fly on. */
        override fun released(e: SignatureEntity, cast: SignatureCast, held: Int) = e.discard()

        override fun tick(e: SignatureEntity) {
            if (isHand(e)) return
            val level = e.level() as ServerLevel
            val here = e.position()
            if (e.tickCount % RETARGET_EVERY == 1) retarget(e, level, here)
            val lane = lane(e)
            // Its own side of a creature, wheeling slowly round it.
            val a = lane.angle + e.tickCount * CIRCLE_SPIN
            val side = Vec3(cos(a) * lane.radius, HOVER + lane.lift * 0.5, sin(a) * lane.radius)
            val ground = SignatureKit.ground(level, here.x, here.z, here.y + 0.5, GROUND_SEARCH)
            val floor = ground?.plus(RIDE + lane.lift)
            var chased = (e.target() as? LivingEntity)?.takeIf { it.isAlive }
            if (chased != null) {
                val at = SignatureKit.middle(chased).add(side)
                // One it can't get to is given up on, and another sought.
                if (stuck(e, level, here, chased, at.distanceTo(here))) {
                    retarget(e, level, here)
                    chased = (e.target() as? LivingEntity)?.takeIf { it.isAlive }
                }
            }
            // A creature first; metal only when there's none to chase, from its own side too.
            val metal = if (drawnToMetal(e)) e.point else null
            val goal = (chased?.let { SignatureKit.middle(it).add(side) }
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
            // It rides above the ground: never sinking below its ride height, eased back up to it,
            // and never, whatever else moves it, coming nearer the ground than its clearance.
            if (floor != null && ground != null) {
                val gap = floor - here.y
                if (gap > -0.3) v = Vec3(v.x, maxOf(v.y, gap * RISE), v.z)
                val low = ground + CLEARANCE - here.y
                if (v.y < low) v = Vec3(v.x, low, v.z)
            }
            // It rises over blocks in its way rather than going through them.
            val ahead = BlockPos.containing(here.add(v.x * 3, 0.0, v.z * 3))
            if (!level.getBlockState(ahead).getCollisionShape(level, ahead).isEmpty) v = Vec3(v.x * 0.5, maxOf(v.y, SPEED * 0.5), v.z * 0.5)
            e.deltaMovement = v
            if (chainsNow(e)) {
                val c = chain(level, here, chainMetal(e), e.caster)
                for (target in c.struck) {
                    SignatureKit.hurtThrough(e, target, EKDamageSources.CHAOS_KEY, DAMAGE)
                    target.setSecondsOnFire(BURN_SECONDS)
                }
            }
        }

        /** The way orb [e] at [here] steers to keep clear of the other orbs near it: away from
         *  each, harder the closer it is (a flock's separation), up to about 1. */
        private fun separation(e: SignatureEntity, level: ServerLevel, here: Vec3): Vec3 {
            var away = Vec3.ZERO
            val others = level.getEntitiesOfClass(SignatureEntity::class.java, AABB(here, here).inflate(SEPARATION)) {
                it !== e && it.isAlive && it.signature == Signature.FAESPARK && !isHand(it)
            }
            for (other in others) {
                val off = here.subtract(other.position())
                val d = off.length()
                if (d >= SEPARATION) continue
                // Two in the same spot part by their numbers.
                val dir = if (d < 1.0e-3) Vec3(cos(index(e) * 2.4), 0.0, sin(index(e) * 2.4)) else off.scale(1 / d)
                away = away.add(dir.scale(1 - d / SEPARATION))
            }
            return if (away.length() > 1) away.normalize() else away
        }

        /** Picks what the spark drifts toward: the nearest creature it can reach, and any metal
         *  block it can see, for when there's no creature. */
        private fun retarget(e: SignatureEntity, level: ServerLevel, here: Vec3) {
            val metal = nearestMetal(level, here)
            if (metal != null) e.point = metal
            e.data = index(e) or (if (metal != null) METAL_BIT else 0)
            val chase = e.state as? Chase
            val next = SignatureKit.creatures(e, here, SEEK)
                .filter { chase?.ignored(it.id, e.tickCount) != true && reachable(level, here, it) }
                .minByOrNull { it.distanceToSqr(here) }
            if (next?.id != e.targetId) chase?.begin(next?.id ?: -1)
            e.targetId = next?.id ?: -1
        }

        /** Whether a spark at [here] can get to [target]: it can see it (ships' blocks too), and
         *  it isn't far above or below the height the spark rides at. */
        private fun reachable(level: ServerLevel, here: Vec3, target: LivingEntity): Boolean {
            val to = SignatureKit.middle(target)
            val ride = (SignatureKit.ground(level, here.x, here.z, here.y + 0.5, GROUND_SEARCH) ?: (here.y - RIDE)) + RIDE
            if (Math.abs(to.y + HOVER - ride) > CLIMB) return false
            return level.clipIncludeShips(ClipContext(here, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, target)).type == HitResult.Type.MISS
        }

        /** How a spark is getting on after its target: the nearest it has come, how long since it
         *  last got nearer or could see it, and whom it has given up on until when. */
        private class Chase {
            var id = -1
            var best = Double.MAX_VALUE
            var stalled = 0
            var unseen = 0
            val given = HashMap<Int, Int>()

            fun begin(target: Int) { id = target; best = Double.MAX_VALUE; stalled = 0; unseen = 0 }
            fun ignored(target: Int, now: Int): Boolean = (given[target] ?: Int.MIN_VALUE) > now
        }

        /** Keeps track of the chase after [target], [d] from its goal; true when it's time to
         *  give up on it. */
        private fun stuck(e: SignatureEntity, level: ServerLevel, here: Vec3, target: LivingEntity, d: Double): Boolean {
            val chase = e.state as? Chase ?: return false
            if (chase.id != target.id) chase.begin(target.id)
            if (d < chase.best - PROGRESS || d <= ARRIVED) { chase.best = minOf(chase.best, d); chase.stalled = 0 } else chase.stalled++
            if (e.tickCount % 5 == 0) chase.unseen = if (reachable(level, here, target)) 0 else chase.unseen + 5
            if (chase.stalled < STALL_TICKS && chase.unseen < LOST_TICKS) return false
            chase.given[target.id] = e.tickCount + IGNORE_TICKS
            return true
        }

        /** The top of the nearest iron or copper block within [METAL_REACH] of [at] that it can
         *  see, a ship's or the world's. */
        private fun nearestMetal(level: ServerLevel, at: Vec3): Vec3? {
            var best: Vec3? = null
            var bestD = METAL_REACH * METAL_REACH
            val r = Math.ceil(METAL_REACH).toInt()
            val centre = BlockPos.containing(at)
            for (pos in BlockPos.betweenClosed(centre.offset(-r, -r, -r), centre.offset(r, r, r))) {
                if (!metal(level.getBlockState(pos))) continue
                val top = Vec3(pos.x + 0.5, pos.y + 1.0, pos.z + 0.5)
                val d = top.distanceToSqr(at)
                if (d < bestD && seen(level, at, top)) { bestD = d; best = top }
            }
            for (block in SignatureShips.blocksNear(level, at, METAL_REACH)) {
                if (!metal(block.state)) continue
                val top = SignatureShips.toWorld(block.ship, Vec3(block.pos.x + 0.5, block.pos.y + 1.0, block.pos.z + 0.5))
                val d = top.distanceToSqr(at)
                if (d < bestD) { bestD = d; best = top }
            }
            return best
        }

        /** Whether the top of a block at [top] can be seen from [at], nothing in between. */
        private fun seen(level: ServerLevel, at: Vec3, top: Vec3): Boolean =
            level.clip(ClipContext(at, top.add(0.0, 0.05, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null)).type == HitResult.Type.MISS

        /** Worked iron or copper, by the block's name: blocks, bars, doors, cut copper, lightning
         *  rods. Not ores or raw ore blocks, which are only rock with metal in it. */
        private fun metal(state: BlockState): Boolean {
            if (state.isAir) return false
            val name = BuiltInRegistries.BLOCK.getKey(state.block).path
            if ("ore" in name || name.startsWith("raw_")) return false
            return "iron" in name || "copper" in name || name == "lightning_rod"
        }

        override fun end(e: SignatureEntity) {
            if (isHand(e)) return
            val level = e.level() as ServerLevel
            SignatureKit.particles(level, ParticleTypes.ELECTRIC_SPARK, e.position(), 40, 0.5, 0.4)
            SignatureKit.particles(level, ParticleTypes.FLASH, e.position(), 1, 0.0)
            SignatureKit.sound(level, e.position(), SoundEvents.FIREWORK_ROCKET_BLAST, 1.4f, 1.5f)
        }
    }

    // ---- Rotdance: spores that hop from creature to creature ---------------------------------

    /**
     * A swarm of spores that leaves the hand for the creature under the crosshair (or the one
     * nearest where it was aimed), settles on it for [STAY] ticks, poisoning and weakening it and
     * eating at it through i-frames every [PULSE_EVERY] ticks, its spores spilling onto anyone
     * within [SPILL] of it. Then it hops to the nearest creature within [HOP_RANGE] it hasn't yet
     * visited, and so on. When there's no one left to hop to, it's made all its hops, or its time
     * runs out, it dies in a burst of poison gas ([burst]): everyone within [BURST] takes a hard
     * blow of rot and is poisoned and rotting, and the mod's poison gas ([GasField]) is left
     * rolling out over the ground there. No effect on ships.
     *
     * [SignatureEntity.data] is what it's doing: [MOVING], [SETTLED] or [FADING].
     */
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
        /** Its bite on its host every [PULSE_EVERY] ticks: about 16 a second. */
        private const val PULSE_DAMAGE = 4f
        private const val PULSE_EVERY = 5
        private const val SPILL = 3.0
        private const val SPILL_DAMAGE = 2f
        private const val ROT_TICKS = 200
        const val BURST = 5.0
        /** The gas burst's blow at its middle at full charge; less further out and for less charge. */
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
            val first = aimed.entity ?: SignatureKit.creatures(cast.level, aimed.point, SEEK, cast.caster).minByOrNull { it.distanceToSqr(aimed.point) }
            SignatureKit.sound(cast.level, cast.origin, SoundEvents.PUFFER_FISH_BLOW_OUT, 1.5f, 1.1f)
            SignatureKit.sound(cast.level, cast.origin, SoundEvents.BEEHIVE_EXIT, 1.5f, 0.7f)
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
            // Settled: it rides its host a while, eating at it, then hops on.
            s.on?.let { host ->
                if (host.isAlive && --s.stay > 0) {
                    e.deltaMovement = SignatureKit.middle(host).subtract(here)
                    if (s.stay % PULSE_EVERY == 0) pulse(e, host)
                    return
                }
                s.on = null
                val next = if (s.hopsLeft > 0) nextHost(e, s, SignatureKit.middle(host)) else null
                if (next == null) { fade(e); return }
                e.targetId = next.id
                e.point = SignatureKit.middle(next)
                e.data = MOVING
            }
            val target = (e.target() as? LivingEntity)?.takeIf { it.isAlive && it.id !in s.visited }
            val goal = target?.let { SignatureKit.middle(it) } ?: e.point
            val to = goal.subtract(here)
            val speed = if (s.legs == 0) FIRST_SPEED else SPEED
            if (to.length() > speed + 0.2) {
                e.deltaMovement = to.normalize().scale(speed)
                return
            }
            e.deltaMovement = Vec3.ZERO
            if (target == null) {
                // Where it was aimed, with no one there: it seeks one close by, or disperses.
                val near = nextHost(e, s, here)
                if (near == null) { fade(e); return }
                e.targetId = near.id
                e.point = SignatureKit.middle(near)
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
            SignatureKit.hurtThrough(e, host, EKDamageSources.ROT_KEY, DAMAGE)
            val at = SignatureKit.middle(host)
            SignatureKit.particles(level, SPORE, at, 40, host.bbWidth * 0.7, 0.04)
            SignatureKit.particles(level, SPARK, at, 16, host.bbWidth * 0.7, 0.08)
            SignatureKit.particles(level, ParticleTypes.SNEEZE, at, 10, host.bbWidth * 0.5, 0.05)
            SignatureKit.sound(level, at, SoundEvents.PUFFER_FISH_STING, 1.4f, 1.0f + level.random.nextFloat() * 0.3f)
            SignatureKit.sound(level, at, SoundEvents.BEEHIVE_WORK, 1.4f, 0.6f)
        }

        /** While settled: it eats at [host] through i-frames, and its spores spill onto those near. */
        private fun pulse(e: SignatureEntity, host: LivingEntity) {
            val level = e.level() as ServerLevel
            val at = SignatureKit.middle(host)
            SignatureKit.hurtThrough(e, host, EKDamageSources.ROT_KEY, PULSE_DAMAGE)
            for (near in SignatureKit.creatures(e, at, SPILL + host.bbWidth * 0.5)) {
                if (near === host) continue
                SignatureKit.hurtThrough(e, near, EKDamageSources.ROT_KEY, SPILL_DAMAGE)
                near.addEffect(MobEffectInstance(MobEffects.POISON, POISON_TICKS / 2, 1), e.caster)
            }
            SignatureKit.particles(level, SPORE, at, 20, SPILL * 0.5, 0.03)
            SignatureKit.sound(level, at, SoundEvents.SLIME_SQUISH_SMALL, 1f, 0.6f)
        }

        /** The nearest creature within [HOP_RANGE] of [from] the swarm hasn't visited. */
        private fun nextHost(e: SignatureEntity, s: Swarm, from: Vec3): LivingEntity? =
            SignatureKit.creatures(e, from, HOP_RANGE).filter { it.id !in s.visited }.minByOrNull { SignatureKit.middle(it).distanceToSqr(from) }

        private fun fade(e: SignatureEntity) {
            e.data = FADING
            e.deltaMovement = Vec3.ZERO
            e.life = e.tickCount + FADE
            burst(e)
        }

        /** Its time ran out before it faded: it bursts where it is. */
        override fun end(e: SignatureEntity) = burst(e)

        /** It dies in a burst of poison gas, once. */
        private fun burst(e: SignatureEntity) {
            val s = e.state as? Swarm ?: return
            if (s.burst) return
            s.burst = true
            val level = e.level() as ServerLevel
            val at = e.position()
            val strength = 0.6 + 0.4 * e.power
            for (target in SignatureKit.creatures(e, at, BURST)) {
                val near = 1 - 0.5 * SignatureKit.middle(target).distanceTo(at) / BURST
                SignatureKit.hurtThrough(e, target, EKDamageSources.ROT_KEY, (BURST_DAMAGE * strength * near).toFloat())
                target.addEffect(MobEffectInstance(MobEffects.POISON, POISON_TICKS, POISON_LEVEL), e.caster)
                target.addEffect(MobEffectInstance(EKEffects.ROT.get(), ROT_TICKS), e.caster)
            }
            GasField.addBurst(level, at, BURST + 1, BURST_GAS)
            SignatureKit.particles(level, SPORE, at, 160, BURST * 0.5, 0.1)
            SignatureKit.particles(level, SPARK, at, 40, BURST * 0.4, 0.1)
            SignatureKit.particles(level, ParticleTypes.SNEEZE, at, 60, BURST * 0.4, 0.08)
            SignatureKit.particles(level, ParticleTypes.EXPLOSION, at, 2, 0.6)
            SignatureKit.sound(level, at, SoundEvents.PUFFER_FISH_BLOW_UP, 2f, 0.6f)
            SignatureKit.sound(level, at, SoundEvents.GENERIC_EXPLODE, 1f, 1.6f)
        }
    }

    // ---- Supernova: a star charged in the hand -----------------------------------------------------

    /**
     * A small star ahead of the hand, growing for [CHARGE_TICKS] while held. Let go, it flies to
     * where it's aimed (or the first thing in its way) and detonates: everything within
     * [blastRadius] is burnt, blinded and thrown out, harder the longer it was charged. Each ship
     * block in the blast takes its own outward impulse, and a few fires ([FIRES]) are left round it.
     *
     * It doesn't go out at once: for [BURST_TICKS] after, a burning core hangs where it went off.
     * Every [PULSE_EVERY] ticks it sears everything within its blast through i-frames, and every
     * [SHOCK_EVERY] ticks it throws out another shockwave that knocks creatures and ship blocks
     * back again.
     *
     * From the moment it goes off until it gutters out, it stabs thin rays of light at every
     * creature within [RAY_REACH] (half its flight range) it can see ([rayTargets]), every
     * [STAB_EVERY] ticks, each stab searing through i-frames. Both sides find the same creatures.
     *
     * [SignatureEntity.data] holds its [phase] (above the low byte) and its charge in ticks
     * (the low byte), so the client draws it the size it is.
     */
    object Supernova : SignatureBehaviour() {
        const val CHARGE_TICKS = 60
        const val AHEAD = 0.7
        const val BURST_TICKS = 70
        const val SHOCK_EVERY = 16
        const val CHARGING = 0
        const val FLYING = 1
        const val BURST = 2
        private const val SPEED = 1.2
        const val RANGE = 48.0
        /** How far its rays stab: half its flight range. */
        const val RAY_REACH = RANGE / 2
        const val STAB_EVERY = 4
        const val MAX_RAYS = 12
        /** A full-charge stab: about 22 a second to everything it reaches. */
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
        /** Fires it leaves round where it went off. */
        private const val FIRES = 8

        fun phase(e: SignatureEntity): Int = e.data shr 8
        fun charge(e: SignatureEntity): Float = (e.data and 0xFF).toFloat() / CHARGE_TICKS
        fun blastRadius(charge: Float): Double = BLAST_MIN + (BLAST_MAX - BLAST_MIN) * charge

        /** Ticks since it went off, given it's bursting. */
        fun sinceBurst(e: SignatureEntity, age: Float): Float = age - (e.life - BURST_TICKS)

        private class Flight(val to: Vec3)

        /** Whether it stabs its rays out this tick: only once it has gone off, while it burns. */
        fun stabsNow(e: SignatureEntity): Boolean =
            phase(e) == BURST && e.tickCount % STAB_EVERY == 0 && sinceBurst(e, e.tickCount.toFloat()) >= 0 && e.tickCount < e.life - 4

        /** The creatures its rays stab from [core]: up to [MAX_RAYS] of the nearest within
         *  [RAY_REACH] it can see, [owner] left out. Either side may ask. */
        fun rayTargets(level: Level, core: Vec3, owner: Entity?): List<LivingEntity> =
            level.getEntitiesOfClass(LivingEntity::class.java, AABB(core, core).inflate(RAY_REACH)) {
                it.isAlive && !it.isSpectator && it !== owner && it !is ArmorStand && SignatureKit.middle(it).distanceTo(core) <= RAY_REACH
            }.filter {
                level.clip(ClipContext(core, SignatureKit.middle(it), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, it)).type == HitResult.Type.MISS
            }.sortedBy { SignatureKit.middle(it).distanceToSqr(core) }.take(MAX_RAYS)

        /** Its rays stab everything they reach. */
        private fun stab(e: SignatureEntity) {
            if (!stabsNow(e)) return
            val level = e.level() as ServerLevel
            val core = e.position()
            val strength = 0.6f + 0.4f * charge(e)
            for (target in rayTargets(level, core, e.caster)) {
                SignatureKit.hurtThrough(e, target, EKDamageSources.SUNFIRE_KEY, RAY_DAMAGE * strength)
                target.setSecondsOnFire(2)
            }
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            SignatureKit.sound(cast.level, cast.origin, SoundEvents.BEACON_ACTIVATE, 1.2f, 1.8f)
            return spawn(cast, cast.origin.add(cast.aim.scale(AHEAD)), HOLD_SLACK)
        }

        override fun held(e: SignatureEntity, cast: SignatureCast, held: Int) {
            e.setPos(cast.origin.add(cast.aim.scale(AHEAD)))
            e.deltaMovement = Vec3.ZERO
            e.life = e.tickCount + HOLD_SLACK
            e.data = minOf(held, CHARGE_TICKS)
            if (held % 20 == 0) SignatureKit.sound(cast.level, e.position(), SoundEvents.BEACON_AMBIENT, 1.2f, 1f + charge(e))
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
            SignatureKit.sound(cast.level, here, SoundEvents.FIRECHARGE_USE, 1.5f, 1.4f)
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

        /** Its flight ran out before it struck anything: it goes off where it is. */
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
            for (target in SignatureKit.creatures(e, at, r)) {
                val near = 1 - SignatureKit.middle(target).distanceTo(at) / r
                SignatureKit.hurtThrough(e, target, EKDamageSources.SUNFIRE_KEY, ((DAMAGE_MIN + (DAMAGE_MAX - DAMAGE_MIN) * c) * (0.4 + 0.6 * near)).toFloat())
                target.setSecondsOnFire(4 + (6 * c).toInt())
                target.addEffect(MobEffectInstance(MobEffects.BLINDNESS, BLIND_TICKS + (BLIND_PER_CHARGE * c).toInt()), e.caster)
                val away = SignatureKit.middle(target).subtract(at)
                if (away.lengthSqr() > 1.0e-6) SignatureKit.push(target, away.normalize().scale(knock * near).add(0.0, 0.35 * knock * near, 0.0))
            }
            // Every ship block in the blast is thrown outward on its own.
            blastShips(level, at, r, SHIP_IMPULSE * (0.3 + 0.7 * c))
            // A few fires left burning round it, the ground's and ships' alike.
            ignite(e, at, maxOf(1.5, r * 0.6), FIRES)
            SignatureKit.particles(level, ParticleTypes.FLASH, at, 1, 0.0)
            SignatureKit.particles(level, ParticleTypes.EXPLOSION_EMITTER, at, 1, 0.0)
            SignatureKit.particles(level, ParticleTypes.EXPLOSION, at, 3 + (6 * c).toInt(), r * 0.35)
            level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 60 + (80 * c).toInt(), 0.3, 0.3, 0.3, 0.4 + 0.3 * c)
            SignatureKit.particles(level, ParticleTypes.FLAME, at, 60, 0.8, 0.35)
            SignatureKit.sound(level, at, SoundEvents.GENERIC_EXPLODE, 2.5f + 2f * c, 1.4f - 0.6f * c)
            SignatureKit.sound(level, at, SoundEvents.BEACON_DEACTIVATE, 2f, 1.6f)
            SignatureKit.sound(level, at, SoundEvents.LIGHTNING_BOLT_THUNDER, 1f + c, 1.4f)
        }

        /** The burning core after the blast: searing pulses, and a shockwave now and then. */
        private fun aftermath(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val since = sinceBurst(e, e.tickCount.toFloat()).toInt()
            if (since <= 0 || e.tickCount >= e.life - 4) return
            val c = charge(e)
            val r = blastRadius(c)
            val at = e.position()
            val shock = since % SHOCK_EVERY == 0
            if (since % PULSE_EVERY == 0 || shock) for (target in SignatureKit.creatures(e, at, r)) {
                val near = 1 - SignatureKit.middle(target).distanceTo(at) / r
                if (since % PULSE_EVERY == 0) {
                    SignatureKit.hurtThrough(e, target, EKDamageSources.SUNFIRE_KEY, ((PULSE_DAMAGE + PULSE_PER_CHARGE * c) * (0.5 + 0.5 * near)).toFloat())
                    target.setSecondsOnFire(3)
                }
                if (shock) knockAway(target, at, (SHOCK_KNOCK + SHOCK_KNOCK * c) * (0.3 + 0.7 * near), 0.15 * near)
            }
            if (shock) {
                blastShips(level, at, r, SHOCK_SHIP * (0.3 + 0.7 * c))
                SignatureKit.particles(level, ParticleTypes.EXPLOSION, at, 2 + (3 * c).toInt(), r * 0.3)
                SignatureKit.sound(level, at, SoundEvents.GENERIC_EXPLODE, 1.5f + c, 1.7f - 0.5f * c)
            }
            if (since % 20 == 0) SignatureKit.sound(level, at, SoundEvents.FIRE_AMBIENT, 2f, 0.6f)
        }
    }
}
