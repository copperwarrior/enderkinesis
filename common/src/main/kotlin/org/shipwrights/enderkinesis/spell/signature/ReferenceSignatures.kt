package org.shipwrights.enderkinesis.spell.signature

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.BlockTags
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.GlassBlock
import net.minecraft.world.level.block.StainedGlassBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.dimension.EKDamageSources
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.item.AegisBox
import org.shipwrights.enderkinesis.item.BeamSegment
import org.shipwrights.enderkinesis.item.BeamTrace
import org.shipwrights.enderkinesis.item.SunderingBeamTrace
import org.shipwrights.enderkinesis.spell.BeamGeometry
import org.shipwrights.enderkinesis.spell.BeamManager
import org.shipwrights.enderkinesis.spell.Signature
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Black Hole Sun, Rootquake and Prism Lance: a body in the air, a ground effect and a held beam. */
object ReferenceSignatures {
    val ALL: Map<Signature, SignatureBehaviour> = mapOf(
        Signature.BLACK_HOLE_SUN to BlackHoleSun,
        Signature.ROOTQUAKE to Rootquake,
        Signature.PRISM_LANCE to PrismLance,
    )

    // ---- Black Hole Sun: a slow star that hovers where it's aimed -------------------------------

    /**
     * Leaves the hand slowly and stops where it was aimed, then hangs there for [LIFE] ticks: a
     * dark core in a churning ring of fire, [CORE] across, pulling in creatures, dropped items
     * and loose projectiles from [REACH] away, burning and crushing what reaches its core,
     * scorching what's under it, and dragging each ship near it by its nearest block. Every
     * [BEAM_EVERY] ticks a beam of sunlight lances from it to a creature in reach, burning it
     * and yanking it once toward the core. It collapses in a flare that throws everything near
     * it back out, burning.
     *
     * It leaves the hand small and swells exponentially as it flies, to full size after
     * [GROW_TICKS] ([scale]); its reach and core swell with it.
     *
     * The latest beam's target is [SignatureEntity.targetId]; [SignatureEntity.data] counts the
     * beams, so the client draws each as it strikes.
     */
    object BlackHoleSun : SignatureBehaviour() {
        const val LIFE = 240
        const val REACH = 42.0
        const val CORE = 9.0
        /** How far the collapse's flare reaches. */
        const val FLARE = 27.0
        /** How far ship blocks are searched for its pull: a ship's blocks are scanned block by
         *  block, so not over its whole reach. */
        private const val SHIP_REACH = 18.0
        const val BEAM_EVERY = 6
        /** How long a sunbeam shows, in ticks. */
        const val BEAM_TICKS = 6
        private const val SPEED = 0.35
        private const val PULL_EDGE = 0.03
        private const val PULL_CORE = 0.12
        private const val SHIP_PULL = 1.6
        private const val BURN = 5f
        private const val CRUSH = 4f
        private const val BEAM_DAMAGE = 6f
        /** A sunbeam's one yank toward the core, blocks a tick. */
        private const val BEAM_PULL = 0.9
        private const val FLARE_DAMAGE = 30f
        private const val FLARE_THROW = 1.8
        /** How big it leaves the hand, as part of its full size, and how long it takes to swell. */
        private const val START_SCALE = 0.08
        const val GROW_TICKS = 50

        /** How big it is [age] ticks after it was cast, as part of its full size: growing
         *  exponentially from [START_SCALE] to 1 over [GROW_TICKS]. Either side may ask. */
        fun scale(age: Float): Double =
            if (age >= GROW_TICKS) 1.0 else START_SCALE * Math.exp(Math.log(1 / START_SCALE) * age / GROW_TICKS)

        override fun start(cast: SignatureCast): SignatureEntity {
            val stop = cast.aimed(32.0 + 24.0 * cast.power).point
            SignatureKit.sound(cast.level, cast.origin, SoundEvents.BEACON_ACTIVATE, 1.5f, 0.5f)
            SignatureKit.sound(cast.level, cast.origin, SoundEvents.BLAZE_SHOOT, 1.5f, 0.5f)
            return spawn(cast, cast.origin, LIFE) { point = stop }
        }

        override fun tick(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val to = e.point.subtract(e.position())
            e.deltaMovement = if (to.length() <= SPEED) { e.setPos(e.point); Vec3.ZERO } else to.normalize().scale(SPEED)
            val core = e.position()
            val grown = scale(e.tickCount.toFloat())
            val reach = REACH * grown
            // Everything loose within reach is drawn in, much harder nearer the core.
            for (entity in level.getEntities(e, AABB(core, core).inflate(reach))) {
                if (entity === e.caster || entity.isSpectator || !entity.isAlive) continue
                if (entity !is LivingEntity && entity !is ItemEntity && entity !is Projectile) continue
                val d = SignatureKit.middle(entity).distanceTo(core)
                if (d > reach) continue
                val near = 1 - d / reach
                SignatureKit.pull(entity, core, PULL_EDGE + PULL_CORE * near * near)
                if (entity is LivingEntity && d <= CORE * grown && e.tickCount % 10 == 0) {
                    entity.setSecondsOnFire(8)
                    SignatureKit.hurtThrough(e, entity, EKDamageSources.SUNFIRE_KEY, BURN)
                    SignatureKit.hurtThrough(e, entity, EKDamageSources.GRAVITY_KEY, CRUSH)
                }
            }
            // A beam of sunlight at a creature in reach: it burns, and is yanked toward the core.
            if (e.tickCount % BEAM_EVERY == 0 && e.deltaMovement == Vec3.ZERO) {
                val targets = SignatureKit.creatures(e, core, REACH)
                if (targets.isNotEmpty()) {
                    val target = targets[level.random.nextInt(targets.size)]
                    SignatureKit.hurtThrough(e, target, EKDamageSources.SUNFIRE_KEY, BEAM_DAMAGE)
                    target.setSecondsOnFire(5)
                    val toCore = core.subtract(SignatureKit.middle(target))
                    if (toCore.lengthSqr() > 1.0e-6) SignatureKit.push(target, toCore.normalize().scale(BEAM_PULL).add(0.0, 0.15, 0.0))
                    e.targetId = target.id
                    e.data = e.data + 1
                    SignatureKit.sound(level, SignatureKit.middle(target), SoundEvents.BEACON_POWER_SELECT, 1f, 1.6f)
                }
            }
            // Every ship near it is dragged toward it by its nearest block, harder the nearer.
            if (e.tickCount % 5 == 0) for (block in SignatureShips.nearestBlocks(level, core, SHIP_REACH)) {
                val toCore = core.subtract(block.world)
                val d = toCore.length()
                if (d > 1.0e-3) SignatureShips.push(block.ship, block.world, toCore.scale(SHIP_PULL * (0.4 + 0.6 * (1 - d / SHIP_REACH)) / d))
            }
            // What lies under it scorches.
            if (e.tickCount % 30 == 0) scorch(e, BlockPos.containing(core), Math.ceil(CORE * grown).toInt())
            if (e.tickCount % 30 == 0) {
                SignatureKit.sound(level, core, SoundEvents.BEACON_AMBIENT, 3f, 0.4f)
                SignatureKit.sound(level, core, SoundEvents.FIRE_AMBIENT, 3f, 0.5f)
            }
        }

        override fun end(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val core = e.position()
            for (target in SignatureKit.creatures(e, core, FLARE)) {
                val away = SignatureKit.middle(target).subtract(core)
                val near = 1 - away.length() / FLARE
                SignatureKit.hurt(e, target, EKDamageSources.SUNFIRE_KEY, FLARE_DAMAGE * (0.4f + 0.6f * near.toFloat()))
                target.setSecondsOnFire(10)
                if (away.lengthSqr() > 1.0e-6) SignatureKit.push(target, away.normalize().scale(FLARE_THROW * (0.4 + 0.6 * near)).add(0.0, 0.4, 0.0))
            }
            // Each ship near the flare is thrown off by its nearest block.
            for (block in SignatureShips.nearestBlocks(level, core, SHIP_REACH)) {
                val away = block.world.subtract(core)
                if (away.lengthSqr() > 1.0e-6) SignatureShips.push(block.ship, block.world, away.normalize().scale(SHIP_PULL * 3))
            }
            scorch(e, BlockPos.containing(core), CORE.toInt() + 3)
            SignatureKit.particles(level, ParticleTypes.FLASH, core, 6, 3.0)
            SignatureKit.particles(level, ParticleTypes.EXPLOSION_EMITTER, core, 3, 3.0)
            SignatureKit.particles(level, ParticleTypes.LAVA, core, 150, 6.0)
            SignatureKit.particles(level, ParticleTypes.FLAME, core, 300, 5.0, 0.8)
            SignatureKit.sound(level, core, SoundEvents.GENERIC_EXPLODE, 4f, 0.5f)
            SignatureKit.sound(level, core, SoundEvents.BEACON_DEACTIVATE, 3f, 0.5f)
        }
    }

    /** How many random spots each [scorch] tries. */
    private const val SCORCH_TRIES = 2

    /** A fire or two on flammable blocks' open tops within [r] of [centre]: [SCORCH_TRIES] random
     *  spots, each lit if fire could stand there. Called now and then, it leaves fire scattered
     *  sparsely for as long as the spell lasts. */
    fun scorch(e: SignatureEntity, centre: BlockPos, r: Int) {
        val level = e.level() as ServerLevel
        val random = level.random
        repeat(SCORCH_TRIES) {
            val pos = centre.offset(random.nextInt(2 * r + 1) - r, random.nextInt(2 * r + 1) - r, random.nextInt(2 * r + 1) - r)
            if (!level.getBlockState(pos).isAir || !BaseFireBlock.canBePlacedAt(level, pos, net.minecraft.core.Direction.UP)) return@repeat
            SignatureKit.setBlock(e, pos, BaseFireBlock.getState(level, pos))
        }
    }

    // ---- Rootquake: roots creep out and the earth heaves up behind them -------------------------

    /**
     * Roots creep out from the caster's feet in a cone toward where they aim, [ROOTS] of them
     * fanned [CONE] either side, each wandering as it goes. They race out at [FRONT_SPEED]; behind
     * each root's tip earth columns heave up, throwing up and hurting what stands on them, and
     * stopping against any hull above, which they strike upward at that point. The columns start
     * low by the caster, so they can see down the cone, and grow to full height over [RAMP]
     * blocks. They are the spell's, not blocks: they sink back. Where they rise from grass or
     * dirt, some of it is left as rooted dirt.
     *
     * Both sides lay out the same roots and columns ([layout]) from the entity's position, its
     * [point] and its seed, so the client draws exactly what the server strikes with.
     */
    object Rootquake : SignatureBehaviour() {
        const val LIFE = 130
        const val SINK = 90
        const val FRONT_SPEED = 0.6
        /** How far the roots run, at most. */
        const val LENGTH = 33.0
        const val ROOTS = 7
        /** How far either side of the aim the outermost roots run. */
        private val CONE = Math.toRadians(28.0)
        /** How far apart a root's columns are, and its points as drawn. */
        private const val COLUMN_STEP = 0.75
        const val ROOT_STEP = 0.25
        /** Over how many blocks from the caster the columns grow to full height. */
        private const val RAMP = 6.0
        private const val THROW = 1.35
        private const val DAMAGE = 14f
        private const val DAMAGE_PER_POWER = 12f
        private const val SHIP_KNOCK = 1.2
        /** The chance a grass or dirt block a column rises from is left as rooted dirt. */
        private const val ROOTED_CHANCE = 0.35

        class Column(val x: Double, val z: Double, val ground: Double, val distance: Double, val height: Double) {
            /** When it rises (tick), and when it starts sinking. */
            val rise: Int get() = Math.ceil((distance + 1.2) / FRONT_SPEED).toInt()
            val sink: Int get() = SINK + (distance * 0.6).toInt()
        }

        /** The quake: every root's points along the ground (each [ROOT_STEP] further along), and
         *  the columns rising behind them. */
        class Layout(val roots: List<List<Vec3>>, val columns: List<Column>)

        override fun start(cast: SignatureCast): SignatureEntity {
            val feet = cast.caster.position()
            val aimed = cast.aimed(18.0 + 15.0 * cast.power).point
            SignatureKit.sound(cast.level, feet, SoundEvents.ROOTED_DIRT_BREAK, 1.5f, 0.5f)
            SignatureKit.sound(cast.level, feet, SoundEvents.WARDEN_DIG, 0.8f, 1.2f)
            return spawn(cast, feet, LIFE) { point = aimed }
        }

        /** The roots and columns of a quake from [from] toward [to], with [seed]: the roots fan
         *  out in the cone and wander, the middle ones furthest; a column each [COLUMN_STEP]
         *  along each, on whatever ground is there, low near [from]. */
        fun layout(level: Level, from: Vec3, to: Vec3, seed: Int): Layout {
            val flat = Vec3(to.x - from.x, 0.0, to.z - from.z)
            if (flat.lengthSqr() < 1.0e-6) return Layout(emptyList(), emptyList())
            val aim = Math.atan2(flat.z, flat.x)
            val reach = minOf(LENGTH, flat.length() + 3.0)
            val random = Random(seed)
            val grounds = HashMap<Long, Double?>()
            fun ground(x: Double, z: Double): Double? {
                val bx = Math.floor(x).toInt(); val bz = Math.floor(z).toInt()
                return grounds.getOrPut(BlockPos.asLong(bx, 0, bz)) { groundAt(level, bx + 0.5, bz + 0.5, from.y) }
            }
            val roots = ArrayList<List<Vec3>>()
            val columns = HashMap<Long, Column>()
            for (k in 0 until ROOTS) {
                val across = k / (ROOTS - 1.0) * 2 - 1
                val heading0 = aim + across * CONE + (random.nextDouble() - 0.5) * Math.toRadians(8.0)
                val length = reach * (0.7 + 0.3 * (1 - Math.abs(across))) * (0.9 + 0.1 * random.nextDouble())
                val phase = random.nextDouble() * Math.PI * 2
                val points = ArrayList<Vec3>()
                var x = from.x; var z = from.z
                var s = 0.0
                var lastY = from.y
                var nextColumn = 1.0
                while (s <= length) {
                    val heading = heading0 + 0.18 * sin(s * 0.35 + phase)
                    x += Math.cos(heading) * ROOT_STEP; z += Math.sin(heading) * ROOT_STEP
                    s += ROOT_STEP
                    val g = ground(x, z)
                    if (g != null) lastY = g
                    points += Vec3(x, lastY + 0.02, z)
                    if (s >= nextColumn && g != null) {
                        nextColumn += COLUMN_STEP
                        val key = BlockPos.asLong(Math.floor(x).toInt(), 0, Math.floor(z).toInt())
                        val ramp = 0.3 + 0.7 * ((s - 1.0) / RAMP).coerceIn(0.0, 1.0)
                        val taper = 1 - 0.35 * (s / length)
                        val height = (1.6 + random.nextDouble() * 1.2) * ramp * taper
                        val old = columns[key]
                        if (old == null || old.distance > s) columns[key] = Column(Math.floor(x) + 0.5, Math.floor(z) + 0.5, g, s, height)
                    }
                }
                roots += points
            }
            return Layout(roots, columns.values.sortedBy { it.distance })
        }

        /** The ground top near [y] at [x], [z]; either side may ask. */
        private fun groundAt(level: Level, x: Double, z: Double, y: Double): Double? {
            val pos = BlockPos.MutableBlockPos(Math.floor(x).toInt(), Math.floor(y).toInt() + 3, Math.floor(z).toInt())
            repeat(10) {
                val state = level.getBlockState(pos)
                val shape = state.getCollisionShape(level, pos)
                if (!shape.isEmpty && state.isSolidRender(level, pos)) return pos.y + shape.max(net.minecraft.core.Direction.Axis.Y)
                pos.move(0, -1, 0)
            }
            return null
        }

        override fun tick(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val columns = (e.state as? Layout ?: layout(level, e.position(), e.point, e.seed).also { e.state = it }).columns
            val from = e.position()
            val damage = DAMAGE + DAMAGE_PER_POWER * e.power
            for ((i, c) in columns.withIndex()) {
                if (e.tickCount != c.rise) continue
                val base = Vec3(c.x, c.ground, c.z)
                val out = Vec3(c.x - from.x, 0.0, c.z - from.z).let { if (it.lengthSqr() < 1.0e-6) Vec3.ZERO else it.normalize() }
                // Low columns by the caster strike more lightly.
                val full = (c.height / 2.5).coerceIn(0.3, 1.0)
                // A hull above stops the column and takes its blow there.
                var y = c.ground + 0.25
                while (y <= c.ground + c.height + 0.3) {
                    val hit = SignatureShips.blockAt(level, Vec3(c.x, y, c.z))
                    if (hit != null) { SignatureShips.push(hit.ship, Vec3(c.x, y, c.z), Vec3(0.0, SHIP_KNOCK * full, 0.0)); break }
                    y += 0.5
                }
                for (target in level.getEntitiesOfClass(LivingEntity::class.java, AABB(base, base).inflate(0.9, 1.4, 0.9))) {
                    if (target === e.caster || target.isSpectator || !target.isAlive) continue
                    SignatureKit.hurt(e, target, EKDamageSources.GROWTH_KEY, damage * full.toFloat())
                    SignatureKit.push(target, Vec3(out.x * 0.3, THROW * full, out.z * 0.3))
                }
                val groundPos = BlockPos.containing(c.x, c.ground - 0.5, c.z)
                val ground = level.getBlockState(groundPos)
                // Grass and dirt the roots tore through are left rooted.
                val earthy = ground.`is`(Blocks.GRASS_BLOCK) || ground.`is`(Blocks.DIRT) || ground.`is`(Blocks.COARSE_DIRT)
                if (earthy && level.random.nextDouble() < ROOTED_CHANCE) SignatureKit.setBlock(e, groundPos, Blocks.ROOTED_DIRT.defaultBlockState())
                level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, if (ground.isAir) Blocks.DIRT.defaultBlockState() else ground), c.x, c.ground + 0.2, c.z, 12, 0.4, 0.1, 0.4, 0.2)
                if (i % 6 == 0) SignatureKit.sound(level, base, SoundEvents.ROOTED_DIRT_BREAK, 1.3f, 0.6f + level.random.nextFloat() * 0.2f)
            }
        }
    }

    // ---- Prism Lance: a crystal beam that splits where it strikes -------------------------------

    /**
     * A held beam from the hand, [REACH] long, through every creature on it to the first block,
     * as a scroll's beam is: it crosses, merges and clashes with other beams. Where it strikes a
     * block it splits into [SPLITS] smaller beams, beams in their own right, that bounce off
     * blocks up to [SPLIT_BOUNCES] times, each bounce bending up to [STEER] toward an enemy
     * ahead; off glass or ice they fan on through instead. Two splits that cross run on as one
     * merged beam, which splits back into the two where it next strikes a block. All of them
     * freeze and cut what they touch, and nudge a ship's block where they strike it. The splits
     * turn slowly, so a held lance sweeps round what's beyond.
     */
    object PrismLance : SignatureBehaviour() {
        const val REACH = 64.0
        const val SPLITS = 5
        /** How far each split runs, all its bounces together. */
        const val SPLIT_REACH = 40.0
        const val SPLIT_BOUNCES = 3
        /** How far a bounce bends toward an enemy, at most, and how far off its way one may be. */
        private val STEER = Math.toRadians(5.0)
        private val STEER_CONE = Math.toRadians(30.0)
        /** How near two splits must pass to merge, and how far along each they must be first. */
        private const val MERGE_GAP = 0.6
        private const val MERGE_AFTER = 1.5
        private const val HIT_EVERY = 4
        private const val LANCE_DAMAGE = 4f
        private const val SPLIT_DAMAGE = 3f
        private const val SHIP_NUDGE = 0.06

        /** One of the lance's split beams: its path, and which splits ([SPLITS] of them, by
         *  index) it carries: one, or two while merged. */
        class Ray(val trace: BeamTrace, val splits: List<Int>)

        override fun start(cast: SignatureCast): SignatureEntity {
            SignatureKit.sound(cast.level, cast.origin, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.5f, 1.4f)
            SignatureKit.sound(cast.level, cast.origin, SoundEvents.BEACON_POWER_SELECT, 1f, 1.8f)
            return spawn(cast, cast.origin, 20 * 60) { point = cast.origin.add(cast.aim.scale(REACH)) }
        }

        override fun held(e: SignatureEntity, cast: SignatureCast, held: Int) {
            e.setPos(cast.origin)
            e.deltaMovement = Vec3.ZERO
            e.life = e.tickCount + 20 * 60
            val level = cast.level
            val origin = cast.origin
            val lance = SignatureBeams.register(e, SignatureBeams.trace(e, origin, cast.aim, REACH), REACH)
            e.point = SignatureBeams.end(lance, origin)
            val rays = if (lance.finalBlockPos == null) emptyList()
                else splits(level, cast.caster, origin, e.point, e.seed, e.tickCount, BeamManager.mirrors(level, cast.caster))
            val acting = rays.mapIndexed { k, ray -> SignatureBeams.register(e, ray.trace, SPLIT_REACH, SignatureBeams.subId(e, k)) }
            if (held % HIT_EVERY != 0) return
            strike(e, lance, LANCE_DAMAGE)
            for ((k, trace) in acting.withIndex()) strike(e, trace, SPLIT_DAMAGE * rays[k].splits.size)
            if (held % 16 == 0) SignatureKit.sound(level, e.point, SoundEvents.AMETHYST_BLOCK_CHIME, 1.5f, 1.2f + level.random.nextFloat() * 0.4f)
        }

        /** Freezes every creature along [trace], and nudges a ship's block where it ends. */
        private fun strike(e: SignatureEntity, trace: BeamTrace, damage: Float) {
            for (seg in trace.segments) for (target in SignatureKit.creaturesAlong(e, seg.start, seg.end, SignatureBeams.HALF_WIDTH)) freeze(e, target, damage)
            val last = trace.segments.lastOrNull() ?: return
            if (trace.finalBlockPos == null) return
            val hit = SignatureShips.blockAt(e.level() as ServerLevel, last.end.add(last.direction.scale(0.1))) ?: return
            SignatureShips.push(hit.ship, last.end, last.direction.scale(SHIP_NUDGE))
        }

        private fun freeze(e: SignatureEntity, target: LivingEntity, damage: Float) {
            SignatureKit.hurtThrough(e, target, EKDamageSources.COLD_KEY, damage)
            target.ticksFrozen = minOf(target.ticksFrozen + 40, target.ticksRequiredToFreeze + 100)
            target.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 2), e.caster)
        }

        /**
         * The split beams of a lance from [origin] striking a block at [at]. Off a block they fan
         * out round its reflection; through glass or ice, round the way the lance was going.
         * Each bounces off the blocks it meets, and off Aegis boxes in [shields], for
         * [SPLIT_REACH] in all. Where two cross they merge into one ray along their bisector,
         * which splits back into the two where it strikes a block. Either side may ask: [age]
         * turns them.
         */
        fun splits(level: Level, owner: Entity?, origin: Vec3, at: Vec3, seed: Int, age: Int, shields: List<AegisBox.Frame>): List<Ray> {
            val dir = at.subtract(origin).let { if (it.lengthSqr() < 1.0e-6) return emptyList() else it.normalize() }
            val probe = level.clip(ClipContext(at.subtract(dir.scale(0.2)), at.add(dir.scale(0.3)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null))
            val struck = probe.takeIf { it.type == HitResult.Type.BLOCK } ?: return emptyList()
            val state = level.getBlockState(struck.blockPos)
            val through = state.`is`(BlockTags.ICE) || state.block is GlassBlock || state.block is StainedGlassBlock
            val axis = if (through) dir else reflect(dir, Vec3.atLowerCornerOf(struck.direction.normal))
            val start = if (through) at.add(dir.scale(1.05)) else at.add(axis.scale(0.02))
            val turn = Random(seed).nextDouble() * Math.PI * 2 + age * 0.03
            val firsts = fan(axis, SPLITS, Math.toRadians(34.0), turn)
                .map { if (through) it else steer(level, owner, start, it, SPLIT_REACH) }
                .map { bounce(level, owner, start, it, SPLIT_REACH, SPLIT_BOUNCES, shields) }
            return merge(level, owner, firsts, shields)
        }

        /** [n] directions fanned [spread] off [axis], evenly round it from [turn]. */
        private fun fan(axis: Vec3, n: Int, spread: Double, turn: Double): List<Vec3> {
            val s1 = axis.cross(if (Math.abs(axis.y) > 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)).normalize()
            val s2 = axis.cross(s1).normalize()
            return List(n) { k ->
                val a = turn + k * Math.PI * 2 / n
                axis.scale(cos(spread)).add(s1.scale(sin(spread) * cos(a))).add(s2.scale(sin(spread) * sin(a))).normalize()
            }
        }

        /** Where two splits cross: how far along each, and where. */
        private class Crossing(val i: Int, val j: Int, val pathI: Double, val pathJ: Double, val point: Vec3, val dirI: Vec3, val dirJ: Vec3)

        /**
         * [firsts] with each pair that crosses merged, nearest their start first, each split
         * merging once: both are cut at the crossing, and one ray runs on from it along their
         * bisector to the first block, where it splits back into two that bounce on with what
         * reach is left. Splits meeting head-on pass through each other.
         */
        private fun merge(level: Level, owner: Entity?, firsts: List<BeamTrace>, shields: List<AegisBox.Frame>): List<Ray> {
            val crossings = ArrayList<Crossing>()
            for (i in firsts.indices) for (j in i + 1 until firsts.size) crossing(firsts, i, j)?.let { crossings += it }
            crossings.sortBy { it.pathI + it.pathJ }
            val cutAt = HashMap<Int, Double>()
            val out = ArrayList<Ray>()
            for (c in crossings) {
                if (c.i in cutAt || c.j in cutAt) continue
                val bisector = c.dirI.add(c.dirJ)
                if (bisector.lengthSqr() < 0.04) continue
                cutAt[c.i] = c.pathI; cutAt[c.j] = c.pathJ
                val left = SPLIT_REACH - minOf(c.pathI, c.pathJ)
                val merged = SunderingBeamTrace.trace(level, owner, c.point, bisector.normalize(), left, shields)
                out += Ray(merged, listOf(c.i, c.j))
                val end = merged.segments.lastOrNull() ?: continue
                val pos = merged.finalBlockPos ?: continue
                val rest = left - merged.segments.sumOf { it.length }
                if (rest < 1.0) continue
                val hit = level.clip(ClipContext(end.end.subtract(end.direction.scale(0.2)), end.end.add(end.direction.scale(0.3)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null))
                if (hit.type != HitResult.Type.BLOCK || hit.blockPos != pos) continue
                // Apart again: one each side of the reflection, the way they came together.
                val axis = reflect(end.direction, Vec3.atLowerCornerOf(hit.direction.normal))
                val apart = c.dirI.subtract(c.dirJ).let { if (it.lengthSqr() < 1.0e-6) axis.cross(Vec3(0.0, 1.0, 0.0)) else it }.normalize()
                for ((k, sign) in listOf(c.i to 1.0, c.j to -1.0)) {
                    val d = axis.add(apart.scale(sign * 0.45)).normalize()
                    out += Ray(bounce(level, owner, end.end.add(d.scale(0.02)), steer(level, owner, end.end, d, rest), rest, SPLIT_BOUNCES - 1, shields), listOf(k))
                }
            }
            for ((i, trace) in firsts.withIndex()) out += Ray(cutAt[i]?.let { cut(trace, it) } ?: trace, listOf(i))
            return out
        }

        /** The first place splits [i] and [j] pass within [MERGE_GAP], past [MERGE_AFTER] along both. */
        private fun crossing(firsts: List<BeamTrace>, i: Int, j: Int): Crossing? {
            var pathA = 0.0
            for (sa in firsts[i].segments) {
                var pathB = 0.0
                for (sb in firsts[j].segments) {
                    val c = BeamGeometry.closest(sa.start, sa.end, sb.start, sb.end)
                    val pa = pathA + sa.length * c.s; val pb = pathB + sb.length * c.t
                    if (c.distance < MERGE_GAP && pa > MERGE_AFTER && pb > MERGE_AFTER) return Crossing(i, j, pa, pb, c.midpoint, sa.direction, sb.direction)
                    pathB += sb.length
                }
                pathA += sa.length
            }
            return null
        }

        /** [trace] up to [length] along it, ending in the air. */
        private fun cut(trace: BeamTrace, length: Double): BeamTrace {
            val out = ArrayList<BeamSegment>()
            var travelled = 0.0
            for (seg in trace.segments) {
                if (travelled + seg.length >= length) {
                    val keep = (length - travelled).coerceAtLeast(0.0)
                    out += BeamSegment(seg.start, seg.direction, seg.start.add(seg.direction.scale(keep)), keep)
                    break
                }
                out += seg
                travelled += seg.length
            }
            return BeamTrace(out, null)
        }

        /** A beam from [start] along [dir] that bounces off blocks up to [bounces] times, each
         *  bounce bending toward an enemy ahead, [reach] long in all. */
        private fun bounce(level: Level, owner: Entity?, start: Vec3, dir: Vec3, reach: Double, bounces: Int, shields: List<AegisBox.Frame>): BeamTrace {
            val segments = ArrayList<BeamSegment>()
            var from = start
            var d = dir
            var left = reach
            var last: BlockPos? = null
            for (leg in 0..bounces) {
                val t = SunderingBeamTrace.trace(level, owner, from, d, left, shields)
                segments += t.segments
                left -= t.segments.sumOf { it.length }
                last = t.finalBlockPos
                val end = t.segments.lastOrNull() ?: break
                if (last == null || left < 0.5 || t.loopPoint != null || leg == bounces) break
                val hit = level.clip(ClipContext(end.end.subtract(end.direction.scale(0.2)), end.end.add(end.direction.scale(0.3)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null))
                if (hit.type != HitResult.Type.BLOCK) break
                d = steer(level, owner, end.end, reflect(end.direction, Vec3.atLowerCornerOf(hit.direction.normal)), left)
                from = end.end.add(d.scale(0.02))
            }
            // A split that comes back across itself just ends there, rather than blowing the lance.
            val cut = SunderingBeamTrace.truncateAtLoop(BeamTrace(segments, last), SignatureBeams.HALF_WIDTH)
            return BeamTrace(cut.segments, if (cut.loopPoint != null) null else cut.finalBlockPos)
        }

        /** [d] from [from], bent by up to [STEER] toward the enemy nearest its way within
         *  [STEER_CONE] and [reach], if there is one. */
        private fun steer(level: Level, owner: Entity?, from: Vec3, d: Vec3, reach: Double): Vec3 {
            var best: Vec3? = null
            var bestAngle = STEER_CONE
            for (enemy in level.getEntitiesOfClass(LivingEntity::class.java, AABB(from, from).inflate(reach)) { it is Enemy && it.isAlive && it !== owner }) {
                val to = SignatureKit.middle(enemy).subtract(from)
                val dist = to.length()
                if (dist < 1.0e-3 || dist > reach) continue
                val angle = Math.acos((to.dot(d) / dist).coerceIn(-1.0, 1.0))
                if (angle < bestAngle) { bestAngle = angle; best = to.scale(1 / dist) }
            }
            val target = best ?: return d
            val perp = target.subtract(d.scale(d.dot(target)))
            if (perp.lengthSqr() < 1.0e-9) return d
            val bend = minOf(bestAngle, STEER)
            return d.scale(cos(bend)).add(perp.normalize().scale(sin(bend))).normalize()
        }

        private fun reflect(d: Vec3, n: Vec3): Vec3 = d.subtract(n.scale(2 * d.dot(n))).normalize()
    }
}
