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
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.spell.BeamGeometry
import org.shipwrights.enderkinesis.spell.BeamManager
import org.shipwrights.enderkinesis.spell.Signature
import org.valkyrienskies.mod.common.world.clipIncludeShips
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.shipwrights.enderkinesis.util.colliderClip
import org.valkyrienskies.core.api.util.GameTickOnly

@OptIn(GameTickOnly::class)
object ReferenceSignatures {
    val ALL: Map<Signature, SignatureBehaviour> = mapOf(
        Signature.BLACK_HOLE_SUN to BlackHoleSun,
        Signature.ROOTQUAKE to Rootquake,
        Signature.PRISM_LANCE to PrismLance,
    )

    /** [SignatureEntity.data]: beam count in the low 16 bits, for the client; arrival tick + 1 above them ([arrivedAt]). */
    object BlackHoleSun : SignatureBehaviour() {
        const val LIFE = 240
        const val REACH = 42.0
        const val CORE = 9.0
        const val FLARE = 27.0
        /** Smaller than [REACH] because ship blocks are scanned one by one. */
        private const val SHIP_REACH = 18.0
        const val BEAM_EVERY = 6
        const val BEAM_TICKS = 6
        private const val SPEED = 0.35
        private const val PULL_EDGE = 0.03
        private const val PULL_CORE = 0.12
        private const val SHIP_PULL = 1.6
        private const val BURN = 5f
        private const val CRUSH = 4f
        private const val BEAM_DAMAGE = 6f
        private const val BEAM_PULL = 0.9
        private const val FLARE_DAMAGE = 30f
        private const val FLARE_THROW = 1.8
        private const val START_SCALE = 0.08
        const val GROW_TICKS = 50

        /** Client and server both call this. */
        fun scale(e: SignatureEntity, age: Float): Double {
            val arrived = arrivedAt(e)
            if (arrived < 0) return START_SCALE
            val u = ((age - arrived) / GROW_TICKS).toDouble().coerceIn(0.0, 1.0)
            return START_SCALE + (1 - START_SCALE) * u * u * u * (u * (u * 6 - 15) + 10)
        }

        private const val STOP_CLEARANCE = 0.25

        // It flies from the hand but aims from the eye, so its path can meet terrain before its point.
        private fun stopAtBlock(e: SignatureEntity, level: ServerLevel) {
            val from = e.position()
            val to = e.point.subtract(from)
            val len = to.length()
            if (len < 1.0e-6) return
            val step = to.scale(minOf(1.0, SPEED / len))
            val hit = level.clipIncludeShips(ClipContext(from, from.add(step), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e))
            if (hit.type == HitResult.Type.BLOCK) e.point = hit.location.subtract(to.scale(STOP_CLEARANCE / len))
        }

        /** -1 while it flies. */
        fun arrivedAt(e: SignatureEntity): Int = (e.data ushr 16) - 1

        fun beams(e: SignatureEntity): Int = e.data and 0xFFFF

        override fun start(cast: SignatureCast): SignatureEntity {
            val stop = cast.aimed(32.0 + 24.0 * cast.power).point
            SignatureKit.sound(cast.level, cast.origin, EKSounds.SIGNATURE_BLACK_HOLE_SUN_CAST.get(), 1.5f)
            // Add the flight time so the flight does not use up the LIFE ticks of hover.
            val flight = Math.ceil(stop.distanceTo(cast.origin) / SPEED).toInt() + 1
            return spawn(cast, cast.origin, flight + LIFE) { point = stop }
        }

        override fun tick(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            if (arrivedAt(e) < 0) stopAtBlock(e, level)
            val to = e.point.subtract(e.position())
            e.deltaMovement = if (to.length() <= SPEED) { e.setPos(e.point); Vec3.ZERO } else to.normalize().scale(SPEED)
            if (e.deltaMovement == Vec3.ZERO && arrivedAt(e) < 0) {
                e.data = e.data or ((e.tickCount + 1) shl 16)
                e.life = e.tickCount + LIFE
                SignatureKit.sound(level, e.position(), EKSounds.SIGNATURE_BLACK_HOLE_SUN_GROW.get(), 3f)
            }
            val core = e.position()
            val grown = scale(e, e.tickCount.toFloat())
            val reach = REACH * grown
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
                    SignatureKit.sound(level, SignatureKit.middle(target), EKSounds.SIGNATURE_BLACK_HOLE_SUN_BEAM.get())
                }
            }
            if (e.tickCount % 5 == 0) for (block in SignatureShips.nearestBlocks(level, core, SHIP_REACH)) {
                val toCore = core.subtract(block.world)
                val d = toCore.length()
                if (d > 1.0e-3) SignatureShips.push(block.ship, block.world, toCore.scale(SHIP_PULL * (0.4 + 0.6 * (1 - d / SHIP_REACH)) / d))
            }
            if (e.tickCount % 30 == 0) scorch(e, BlockPos.containing(core), Math.ceil(CORE * grown).toInt())
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
            for (block in SignatureShips.nearestBlocks(level, core, SHIP_REACH)) {
                val away = block.world.subtract(core)
                if (away.lengthSqr() > 1.0e-6) SignatureShips.push(block.ship, block.world, away.normalize().scale(SHIP_PULL * 3))
            }
            scorch(e, BlockPos.containing(core), CORE.toInt() + 3)
            SignatureKit.particles(level, ParticleTypes.FLASH, core, 6, 3.0)
            SignatureKit.particles(level, ParticleTypes.EXPLOSION_EMITTER, core, 3, 3.0)
            SignatureKit.particles(level, ParticleTypes.LAVA, core, 150, 6.0)
            SignatureKit.particles(level, ParticleTypes.FLAME, core, 300, 5.0, 0.8)
            SignatureKit.sound(level, core, EKSounds.SIGNATURE_BLACK_HOLE_SUN_FLARE.get(), 4f)
        }
    }

    private const val SCORCH_TRIES = 2

    fun scorch(e: SignatureEntity, centre: BlockPos, r: Int) {
        val level = e.level() as ServerLevel
        val random = level.random
        repeat(SCORCH_TRIES) {
            val pos = centre.offset(random.nextInt(2 * r + 1) - r, random.nextInt(2 * r + 1) - r, random.nextInt(2 * r + 1) - r)
            if (!level.getBlockState(pos).isAir || !BaseFireBlock.canBePlacedAt(level, pos, net.minecraft.core.Direction.UP)) return@repeat
            SignatureKit.setBlock(e, pos, BaseFireBlock.getState(level, pos))
        }
    }

    /** Client and server build the same [layout] from position, [point] and seed, so the client draws what the server strikes. */
    object Rootquake : SignatureBehaviour() {
        const val LIFE = 130
        const val SINK = 90
        const val FRONT_SPEED = 0.6
        const val LENGTH = 33.0
        const val ROOTS = 7
        /** Half-angle of the root fan. */
        private val CONE = Math.toRadians(28.0)
        private const val COLUMN_STEP = 0.75
        const val ROOT_STEP = 0.25
        /** Distance from the caster over which the columns grow to full height. */
        private const val RAMP = 6.0
        private const val THROW = 1.35
        private const val DAMAGE = 14f
        private const val DAMAGE_PER_POWER = 12f
        private const val SHIP_KNOCK = 1.2
        private const val ROOTED_CHANCE = 0.35

        class Column(val x: Double, val z: Double, val ground: Double, val distance: Double, val height: Double) {
            val rise: Int get() = Math.ceil((distance + 1.2) / FRONT_SPEED).toInt()
            val sink: Int get() = SINK + (distance * 0.6).toInt()
        }

        class Layout(val roots: List<List<Vec3>>, val columns: List<Column>)

        override fun start(cast: SignatureCast): SignatureEntity {
            val feet = cast.caster.position()
            val aimed = cast.aimed(18.0 + 15.0 * cast.power).point
            SignatureKit.sound(cast.level, feet, EKSounds.SIGNATURE_ROOTQUAKE_CAST.get(), 1.5f)
            return spawn(cast, feet, LIFE) { point = aimed }
        }

        private fun reach(flat: Vec3): Double = minOf(LENGTH, flat.length() + 3.0)

        private fun flat(e: SignatureEntity): Vec3 = Vec3(e.point.x - e.x, 0.0, e.point.z - e.z)

        /** Both sides call this. No column is farther out than [reach], so none rises later than this. */
        fun lastRise(e: SignatureEntity): Int = Math.ceil((reach(flat(e)) + 1.2) / FRONT_SPEED).toInt()

        /** Both sides call this. */
        fun front(e: SignatureEntity, age: Double): Vec3 {
            val flat = flat(e)
            if (flat.lengthSqr() < 1.0e-6) return e.position()
            return e.position().add(flat.normalize().scale(minOf(FRONT_SPEED * age, reach(flat))))
        }

        fun layout(level: Level, from: Vec3, to: Vec3, seed: Int): Layout {
            val flat = Vec3(to.x - from.x, 0.0, to.z - from.z)
            if (flat.lengthSqr() < 1.0e-6) return Layout(emptyList(), emptyList())
            val aim = Math.atan2(flat.z, flat.x)
            val reach = reach(flat)
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
            if (e.tickCount == SINK) {
                val flat = flat(e)
                val middle = if (flat.lengthSqr() < 1.0e-6) from else from.add(flat.normalize().scale(reach(flat) / 2))
                SignatureKit.sound(level, middle, EKSounds.SIGNATURE_ROOTQUAKE_SINK.get(), 2f)
            }
            for ((i, c) in columns.withIndex()) {
                if (e.tickCount != c.rise) continue
                val base = Vec3(c.x, c.ground, c.z)
                val out = Vec3(c.x - from.x, 0.0, c.z - from.z).let { if (it.lengthSqr() < 1.0e-6) Vec3.ZERO else it.normalize() }
                val full = (c.height / 2.5).coerceIn(0.3, 1.0)
                var y = c.ground + 0.25
                while (y <= c.ground + c.height + 0.3) {
                    val hit = SignatureShips.blockAt(level, Vec3(c.x, y, c.z))
                    if (hit != null) { SignatureShips.push(hit.ship, Vec3(c.x, y, c.z), Vec3(0.0, SHIP_KNOCK * full, 0.0)); break }
                    y += 0.5
                }
                for (target in level.getEntitiesOfClass(LivingEntity::class.java, AABB(base, base).inflate(0.9, 1.4, 0.9))) {
                    if (target === e.caster || target.isSpectator || !target.isAlive) continue
                    SignatureKit.hurt(e, target, EKDamageSources.GENESIS_KEY, damage * full.toFloat())
                    SignatureKit.push(target, Vec3(out.x * 0.3, THROW * full, out.z * 0.3))
                }
                val groundPos = BlockPos.containing(c.x, c.ground - 0.5, c.z)
                val ground = level.getBlockState(groundPos)
                val earthy = ground.`is`(Blocks.GRASS_BLOCK) || ground.`is`(Blocks.DIRT) || ground.`is`(Blocks.COARSE_DIRT)
                if (earthy && level.random.nextDouble() < ROOTED_CHANCE) SignatureKit.setBlock(e, groundPos, Blocks.ROOTED_DIRT.defaultBlockState())
                level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, if (ground.isAir) Blocks.DIRT.defaultBlockState() else ground), c.x, c.ground + 0.2, c.z, 12, 0.4, 0.1, 0.4, 0.2)
                if (i % 6 == 0) SignatureKit.sound(level, base, EKSounds.SIGNATURE_ROOTQUAKE_COLUMN.get(), 1.3f)
            }
        }
    }

    object PrismLance : SignatureBehaviour() {
        const val REACH = 64.0
        const val SPLITS = 5
        /** Total length over all bounces. */
        const val SPLIT_REACH = 40.0
        const val SPLIT_BOUNCES = 3
        /** Maximum bend for each bounce, and maximum angle off course for a target. */
        private val STEER = Math.toRadians(5.0)
        private val STEER_CONE = Math.toRadians(30.0)
        /** Maximum gap for two splits to merge, and minimum distance along each before they can. */
        private const val MERGE_GAP = 0.6
        private const val MERGE_AFTER = 1.5
        private const val HIT_EVERY = 4
        private const val LANCE_DAMAGE = 4f
        private const val SPLIT_DAMAGE = 3f
        private const val SHIP_NUDGE = 0.06

        private const val SPLITTING = 1

        /** The server sets this, so the client need not trace the splits to know. */
        fun splitting(e: SignatureEntity): Boolean = e.data and SPLITTING != 0

        /** [splits] holds the split indices it carries: one, or two while merged. */
        class Ray(val trace: BeamTrace, val splits: List<Int>)

        override fun start(cast: SignatureCast): SignatureEntity {
            SignatureKit.sound(cast.level, cast.origin, EKSounds.SIGNATURE_PRISM_LANCE_LANCE_START.get(), 1.5f)
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
            val split = rays.isNotEmpty()
            if (split && !splitting(e)) SignatureKit.sound(level, e.point, EKSounds.SIGNATURE_PRISM_LANCE_SPLIT.get(), 1.5f)
            e.data = if (split) SPLITTING else 0
            if (held % HIT_EVERY != 0) return
            strike(e, lance, LANCE_DAMAGE)
            for ((k, trace) in acting.withIndex()) strike(e, trace, SPLIT_DAMAGE * rays[k].splits.size)
        }

        override fun released(e: SignatureEntity, cast: SignatureCast, held: Int) {
            SignatureKit.sound(cast.level, cast.origin, EKSounds.SIGNATURE_PRISM_LANCE_LANCE_STOP.get(), 1.5f)
            super.released(e, cast, held)
        }

        private fun strike(e: SignatureEntity, trace: BeamTrace, damage: Float) {
            for (seg in trace.segments) for (target in SignatureKit.creaturesAlong(e, seg.start, seg.end, SignatureBeams.HALF_WIDTH)) freeze(e, target, damage)
            val last = trace.segments.lastOrNull() ?: return
            if (trace.finalBlockPos == null) return
            val hit = SignatureShips.blockAt(e.level() as ServerLevel, last.end.add(last.direction.scale(0.1))) ?: return
            SignatureShips.push(hit.ship, last.end, last.direction.scale(SHIP_NUDGE))
        }

        private fun freeze(e: SignatureEntity, target: LivingEntity, damage: Float) {
            SignatureKit.hurtThrough(e, target, EKDamageSources.WINTER_KEY, damage)
            target.ticksFrozen = minOf(target.ticksFrozen + 40, target.ticksRequiredToFreeze + 100)
            target.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 2), e.caster)
        }

        /** Client and server both call this; [age] turns the fan. */
        fun splits(level: Level, owner: Entity?, origin: Vec3, at: Vec3, seed: Int, age: Int, shields: List<AegisBox.Frame>): List<Ray> {
            val dir = at.subtract(origin).let { if (it.lengthSqr() < 1.0e-6) return emptyList() else it.normalize() }
            val probe = level.clip(colliderClip(at.subtract(dir.scale(0.2)), at.add(dir.scale(0.3))))
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

        private fun fan(axis: Vec3, n: Int, spread: Double, turn: Double): List<Vec3> {
            val s1 = axis.cross(if (Math.abs(axis.y) > 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)).normalize()
            val s2 = axis.cross(s1).normalize()
            return List(n) { k ->
                val a = turn + k * Math.PI * 2 / n
                axis.scale(cos(spread)).add(s1.scale(sin(spread) * cos(a))).add(s2.scale(sin(spread) * sin(a))).normalize()
            }
        }

        private class Crossing(val i: Int, val j: Int, val pathI: Double, val pathJ: Double, val point: Vec3, val dirI: Vec3, val dirJ: Vec3)

        /** Each split merges at most once, nearest crossing first. Head-on splits pass through each other. */
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
                val hit = level.clip(colliderClip(end.end.subtract(end.direction.scale(0.2)), end.end.add(end.direction.scale(0.3))))
                if (hit.type != HitResult.Type.BLOCK || hit.blockPos != pos) continue
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
                val hit = level.clip(colliderClip(end.end.subtract(end.direction.scale(0.2)), end.end.add(end.direction.scale(0.3))))
                if (hit.type != HitResult.Type.BLOCK) break
                d = steer(level, owner, end.end, reflect(end.direction, Vec3.atLowerCornerOf(hit.direction.normal)), left)
                from = end.end.add(d.scale(0.02))
            }
            // A split that loops back across itself ends there, so it does not blow the lance.
            val cut = SunderingBeamTrace.truncateAtLoop(BeamTrace(segments, last), SignatureBeams.HALF_WIDTH)
            return BeamTrace(cut.segments, if (cut.loopPoint != null) null else cut.finalBlockPos)
        }

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
