package org.shipwrights.enderkinesis.spell.signature

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.BlockTags
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.shipwrights.enderkinesis.dimension.EKDamageSources
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.item.BeamTrace
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.spell.BloodPacts
import org.shipwrights.enderkinesis.spell.DarkZones
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Signature
import org.shipwrights.enderkinesis.spell.SpellVfx
import org.shipwrights.enderkinesis.spell.SpellContext
import org.valkyrienskies.mod.common.world.clipIncludeShips
import org.shipwrights.enderkinesis.util.colliderClip

object LinkSignatures {
    val ALL: Map<Signature, SignatureBehaviour> = mapOf(
        Signature.VAMPIRES_BALLROOM to VampiresBallroom,
        Signature.TRANSFUSION to Transfusion,
        Signature.JUDGEMENT_PILLAR to JudgementPillar,
        Signature.SUPPLICATION_OF_SSELITH to SupplicationOfSselith,
    )

    /** Also sets [SignatureEntity.point] to the end of the beam. */
    private fun heldBeam(e: SignatureEntity, cast: SignatureCast, reach: Double): BeamTrace {
        val origin = cast.origin
        val trace = SignatureBeams.register(e, SignatureBeams.trace(e, origin, cast.aim, reach), reach)
        e.point = SignatureBeams.end(trace, origin)
        return trace
    }

    private fun creaturesIn(e: SignatureEntity, trace: BeamTrace, origin: Vec3): List<LivingEntity> =
        SignatureBeams.lines(trace).flatMap { SignatureKit.creaturesAlong(e, it.from, it.to, it.halfWidth) }
            .distinct().sortedBy { it.distanceToSqr(origin) }

    private fun holdInHand(e: SignatureEntity, cast: SignatureCast) {
        e.setPos(cast.origin)
        e.deltaMovement = Vec3.ZERO
        e.life = e.tickCount + HELD_LIFE
    }

    private const val HELD_LIFE = 20 * 60

    private val GRASSES by lazy { setOf(Blocks.GRASS, Blocks.FERN, Blocks.TALL_GRASS, Blocks.LARGE_FERN) }

    private fun sear(e: SignatureEntity, level: ServerLevel, pos: BlockPos) {
        val state = level.getBlockState(pos)
        when {
            state.`is`(Blocks.GRASS_BLOCK) || state.`is`(Blocks.PODZOL) || state.`is`(Blocks.MYCELIUM) ->
                SignatureKit.setBlock(e, pos, Blocks.DIRT.defaultBlockState())
            state.`is`(BlockTags.LEAVES) || state.block is VineBlock || state.block in GRASSES || state.`is`(BlockTags.SMALL_FLOWERS) ->
                SignatureKit.setBlock(e, pos, Blocks.AIR.defaultBlockState())
        }
    }

    private fun placeFire(e: SignatureEntity, level: ServerLevel, pos: BlockPos, face: Direction): Boolean {
        if (!level.getBlockState(pos).isAir || !BaseFireBlock.canBePlacedAt(level, pos, face)) return false
        return SignatureKit.setBlock(e, pos, BaseFireBlock.getState(level, pos))
    }

    /** Keep [fireChance] low, so that fire stays scattered and does not cover the area. */
    private fun searAround(e: SignatureEntity, centre: Vec3, radius: Double, down: Int, up: Int, fireChance: Float = 0f) {
        val level = e.level() as ServerLevel
        val c = BlockPos.containing(centre)
        val ri = Math.ceil(radius).toInt()
        val pos = BlockPos.MutableBlockPos()
        for (dx in -ri..ri) for (dz in -ri..ri) {
            if (dx * dx + dz * dz > radius * radius) continue
            for (dy in -down..up) {
                pos.set(c.x + dx, c.y + dy, c.z + dz)
                val state = level.getBlockState(pos)
                if (state.isAir) continue
                sear(e, level, pos)
                if (fireChance > 0f && level.random.nextFloat() < fireChance && state.ignitedByLava()) {
                    placeFire(e, level, pos.above(), Direction.UP)
                }
            }
        }
    }

    /** [SignatureEntity.data] tells the web, its threads and its sparks apart (see [kind]). */
    object VampiresBallroom : SignatureBehaviour() {
        const val LEAP = 15.0
        const val SNAP = 24.0
        const val LEAP_FIRST = 3
        const val LEAP_EVERY = 4
        const val THREAD_GROW = 4
        const val SPARK_TICKS = 8
        private const val REACH = 48.0
        private const val FIRST_NEAR = 3.0
        private const val BASE_LINKS = 4
        private const val MORE_LINKS = 4
        private const val CAP_SCALE = 4
        /** Damage share for a creature that the web cannot drain. */
        private const val UNDRAINED = 0.6f
        /** Share of the drained health that heals the caster. */
        private const val HEAL_SHARE = 0.2f
        private const val BASE_LIFE = 480
        private const val MORE_LIFE = 320
        private const val STRIKE = 2f
        private const val MORE_STRIKE = 2f
        private const val DRAIN_EVERY = 20
        private const val DRAIN = 1f
        private const val MORE_DRAIN = 1.5f
        private const val BLEED_TICKS = 100

        enum class Kind { WEB, THREAD, SPARK }

        const val WEB_DATA = -1

        fun kind(data: Int): Kind = when {
            data >= 0 -> Kind.THREAD
            data == WEB_DATA -> Kind.WEB
            else -> Kind.SPARK
        }

        fun fromId(data: Int): Int = if (data >= 0) data else -data - 2

        private fun sparkData(fromId: Int): Int = -fromId - 2

        private class Link(val a: LivingEntity, val b: LivingEntity, val thread: SignatureEntity) {
            var snapped = false
        }

        private class Web(val cast: SignatureCast, val host: SignatureEntity, val root: LivingEntity, val cap: Int) {
            val members = ArrayList<LivingEntity>()
            val links = ArrayList<Link>()
            var linked = false

            fun tied(): List<LivingEntity> {
                if (!linked) return if (root.isAlive && members.isNotEmpty()) listOf(root) else emptyList()
                return members.filter { m -> m.isAlive && links.any { !it.snapped && (it.a === m || it.b === m) } }
            }
        }

        private class Blow(val to: LivingEntity, val source: DamageSource, val amount: Float)

        private val webs = ArrayList<Web>()
        private var redirecting = false

        fun clearSession() {
            webs.clear()
            redirecting = false
        }

        private val hooked: Unit by lazy {
            EntityEvent.LIVING_HURT.register { victim, source, amount -> onHurt(victim, source, amount) }
            Unit
        }

        override fun start(cast: SignatureCast): SignatureEntity? {
            hooked
            val aimed = cast.aimed(REACH)
            val first = aimed.entity ?: SignatureKit.creatures(cast.level, aimed.point, FIRST_NEAR, cast.caster).minByOrNull { it.distanceToSqr(aimed.point) }
            if (first == null) {
                SignatureKit.sound(cast.level, cast.origin, EKSounds.SIGNATURE_VAMPIRES_BALLROOM_MISS.get(), 0.5f)
                return null
            }
            val cap = CAP_SCALE * (BASE_LINKS + 1 + Math.round(MORE_LINKS * cast.power))
            val life = BASE_LIFE + (MORE_LIFE * cast.power).toInt()
            SignatureKit.sound(cast.level, cast.origin, EKSounds.SIGNATURE_VAMPIRES_BALLROOM_CAST.get(), 1.6f)
            return spawn(cast, cast.origin, life) {
                data = WEB_DATA
                targetId = first.id
                val web = Web(cast, this, first, cap)
                state = web
                webs += web
            }
        }

        override fun tick(e: SignatureEntity) {
            when (val s = e.state) {
                is Web -> tickWeb(e, s)
                // Keep threads and sparks near their creatures, so that clients continue to see them.
                is Link -> e.setPos(SignatureKit.middle(s.a).lerp(SignatureKit.middle(s.b), 0.5))
                is Blow -> e.setPos(SignatureKit.middle(s.to))
            }
        }

        private fun tickWeb(e: SignatureEntity, web: Web) {
            val level = e.level() as ServerLevel
            if (e.tickCount == LEAP_FIRST) {
                if (!web.root.isAlive) { e.life = e.tickCount; return }
                catch(e, web, web.root)
            } else if (e.tickCount > LEAP_FIRST && (e.tickCount - LEAP_FIRST) % LEAP_EVERY == 0) spread(e, web)
            for (link in web.links) {
                if (link.snapped) continue
                if (!link.a.isAlive || !link.b.isAlive || link.a.distanceTo(link.b) > SNAP) {
                    link.snapped = true
                    link.thread.discard()
                    if (link.a.isAlive && link.b.isAlive) {
                        SignatureKit.sound(level, link.thread.position(), EKSounds.SIGNATURE_VAMPIRES_BALLROOM_SNAP.get(), 1.2f)
                        SignatureKit.particles(level, BLOOD, link.thread.position(), 12, 0.4, 0.05)
                    }
                }
            }
            web.links.removeAll { it.snapped }
            if (e.tickCount > LEAP_FIRST && e.tickCount % DRAIN_EVERY == 0) drain(e, web)
            if (e.tickCount > LEAP_FIRST && web.tied().isEmpty()) e.life = minOf(e.life, e.tickCount + 1)
        }

        private fun spread(e: SignatureEntity, web: Web) {
            val tied = web.tied()
            if (tied.isEmpty() || tied.size >= web.cap) return
            val level = e.level() as ServerLevel
            var from: LivingEntity? = null
            var to: LivingEntity? = null
            var best = Double.MAX_VALUE
            for (m in tied) for (c in SignatureKit.creatures(level, SignatureKit.middle(m), LEAP, web.cast.caster)) {
                if (tied.any { it === c }) continue
                val d = c.distanceToSqr(m)
                if (d < best) { best = d; from = m; to = c }
            }
            val a = from ?: return
            val b = to ?: return
            val thread = spawn(web.cast, SignatureKit.middle(a).lerp(SignatureKit.middle(b), 0.5), e.life - e.tickCount) {
                data = a.id
                targetId = b.id
            }
            val link = Link(a, b, thread)
            thread.state = link
            web.links += link
            web.linked = true
            catch(e, web, b)
        }

        private fun drain(e: SignatureEntity, web: Web) {
            val amount = DRAIN + MORE_DRAIN * e.power
            // The drain is the web's own damage, so onHurt must not send it along the web.
            redirecting = true
            try {
                for (m in web.tied()) strike(e, m, amount)
            } finally {
                redirecting = false
            }
        }

        private fun catch(e: SignatureEntity, web: Web, who: LivingEntity) {
            val level = e.level() as ServerLevel
            val at = SignatureKit.middle(who)
            if (web.members.none { it === who }) {
                web.members += who
                strike(e, who, STRIKE + MORE_STRIKE * e.power)
                who.addEffect(MobEffectInstance(EKEffects.BLEEDING.get(), BLEED_TICKS, 0), e.caster)
                SignatureKit.particles(level, BLOOD, at, 24, 0.35, 0.08)
                SignatureKit.particles(level, ParticleTypes.DAMAGE_INDICATOR, at, 4, 0.3, 0.1)
            }
            val notes = EKSounds.SIGNATURE_VAMPIRES_BALLROOM_CATCH
            SignatureKit.sound(level, at, notes[(web.members.size - 1).mod(notes.size)].get(), 1.3f)
        }

        private fun strike(e: SignatureEntity, who: LivingEntity, amount: Float) {
            val caster = e.caster
            val drainable = !SpellContext.isUndead(who) && (caster == null || !BloodPacts.isThrallOf(who, caster.uuid))
            if (!drainable) {
                SignatureKit.hurt(e, who, EKDamageSources.CHAOS_KEY, amount * UNDRAINED)
                return
            }
            val before = who.health
            if (!SignatureKit.hurt(e, who, EKDamageSources.BLOOD_DRAIN_KEY, amount)) return
            val taken = (before - who.health).coerceAtLeast(0f)
            if (caster != null && caster.isAlive && taken > 0f && !SpellContext.isUndead(caster)) {
                caster.heal(taken * HEAL_SHARE)
                SpellVfx.stream(e.level() as ServerLevel, SignatureKit.middle(who), SignatureKit.middle(caster), Element.BLOOD.color)
            }
        }

        override fun end(e: SignatureEntity) {
            when (val s = e.state) {
                is Web -> {
                    webs.remove(s)
                    for (link in s.links) link.thread.discard()
                    SignatureKit.sound(e.level() as ServerLevel, e.position(), EKSounds.SIGNATURE_VAMPIRES_BALLROOM_END.get(), 1.4f)
                }
                is Blow -> land(e, s)
            }
        }

        private fun land(e: SignatureEntity, blow: Blow) {
            if (!blow.to.isAlive) return
            redirecting = true
            try {
                blow.to.hurt(blow.source, blow.amount)
            } finally {
                redirecting = false
            }
            val level = e.level() as ServerLevel
            SignatureKit.particles(level, BLOOD, SignatureKit.middle(blow.to), 18, 0.35, 0.08)
            SignatureKit.sound(level, SignatureKit.middle(blow.to), EKSounds.SIGNATURE_VAMPIRES_BALLROOM_LAND.get(), 1.1f)
        }

        /** Sends a blow on a tied creature to a random tied creature, along the web. */
        private fun onHurt(victim: LivingEntity, source: DamageSource, amount: Float): EventResult {
            if (redirecting || amount <= 0f) return EventResult.pass()
            val level = victim.level() as? ServerLevel ?: return EventResult.pass()
            if (webs.isEmpty() || source.`is`(DamageTypeTags.BYPASSES_INVULNERABILITY)) return EventResult.pass()
            // A creature in its i-frames does not take this blow, so do not send it on.
            if (victim.invulnerableTime > 10) return EventResult.pass()
            webs.removeAll { it.host.isRemoved }
            val web = webs.firstOrNull { w -> w.members.any { it === victim } } ?: return EventResult.pass()
            val tied = web.tied()
            if (tied.size < 2 || tied.none { it === victim }) return EventResult.pass()
            val to = tied[level.random.nextInt(tied.size)]
            if (to === victim) return EventResult.pass()
            spawn(web.cast, SignatureKit.middle(victim), SPARK_TICKS) {
                data = sparkData(victim.id)
                targetId = to.id
                state = Blow(to, source, amount)
            }
            SignatureKit.sound(level, SignatureKit.middle(victim), EKSounds.SIGNATURE_VAMPIRES_BALLROOM_PASS.get(), 1.2f)
            return EventResult.interruptFalse()
        }

        private val BLOOD = DustParticleOptions(Vector3f(0.6f, 0.04f, 0.08f), 1.3f)
    }

    /** [SignatureEntity.data] is 1 while it burns undead; [SignatureEntity.targetId] is the nearest creature in it. */
    object Transfusion : SignatureBehaviour() {
        const val REACH = 48.0
        private const val EVERY = 8
        private const val GIVE = 3f
        private const val CASTER_FLOOR = 4f
        private const val BURN_EVERY = 4
        private const val BURN = 3.5f
        private const val DRIVE_BACK = 0.2

        override fun start(cast: SignatureCast): SignatureEntity {
            SignatureKit.sound(cast.level, cast.origin, EKSounds.SIGNATURE_TRANSFUSION_BEAM_START.get(), 1.3f)
            return spawn(cast, cast.origin, HELD_LIFE) { point = cast.origin.add(cast.aim.scale(REACH)) }
        }

        override fun held(e: SignatureEntity, cast: SignatureCast, held: Int) {
            holdInHand(e, cast)
            val origin = cast.origin
            val trace = heldBeam(e, cast, REACH)
            val inBeam = creaturesIn(e, trace, origin)
            e.targetId = inBeam.firstOrNull()?.id ?: -1
            e.data = if (inBeam.any { SpellContext.isUndead(it) }) 1 else 0
            val level = cast.level
            if (held % BURN_EVERY == 0) for (target in inBeam) {
                if (!SpellContext.isUndead(target)) continue
                SignatureKit.hurtThrough(e, target, EKDamageSources.SUNFIRE_KEY, BURN)
                target.setSecondsOnFire(5)
                SignatureKit.push(target, SignatureKit.middle(target).subtract(origin).normalize().scale(DRIVE_BACK))
                if (held % (BURN_EVERY * 2) == 0) SignatureKit.sound(level, SignatureKit.middle(target), EKSounds.SIGNATURE_TRANSFUSION_SEAR.get(), 0.9f)
            }
            if (held % EVERY != 0) return
            val caster = cast.caster
            for (target in inBeam) {
                if (SpellContext.isUndead(target) || target is Enemy) continue
                val given = minOf(GIVE, caster.health - CASTER_FLOOR, target.maxHealth - target.health)
                if (given <= 0f) continue
                caster.health -= given
                target.heal(given)
                SignatureKit.particles(level, ParticleTypes.HEART, SignatureKit.middle(target).add(0.0, 0.6, 0.0), 2, 0.3, 0.0)
                SignatureKit.sound(level, SignatureKit.middle(target), EKSounds.SIGNATURE_TRANSFUSION_GIVE.get(), 1.3f)
            }
        }

        override fun released(e: SignatureEntity, cast: SignatureCast, held: Int) {
            SignatureKit.sound(cast.level, cast.origin, EKSounds.SIGNATURE_TRANSFUSION_BEAM_STOP.get(), 1.3f)
            super.released(e, cast, held)
        }
    }

    /** [SignatureEntity.data] is 1 under an open daytime sky. */
    object JudgementPillar : SignatureBehaviour() {
        const val LIFE = 200
        const val ACTIVE = 150
        const val IMPACT = 4
        const val HEIGHT = 64.0
        private const val BASE_RADIUS = 2.2
        private const val MORE_RADIUS = 1.0
        private const val EVERY = 5
        private const val SMITE = 18f
        private const val MORE_SMITE = 14f
        private const val UNDEAD = 1.5f
        private const val SEAR = 4f
        private const val THROW = 1.1
        private const val LIFT = 0.55
        private const val DAYLIGHT = 1.5f
        private const val BURN_DOWN = 2
        private const val BURN_UP = 3
        private const val FIRE_CHANCE = 0.01f

        fun radius(power: Float): Double = BASE_RADIUS + MORE_RADIUS * power

        override fun start(cast: SignatureCast): SignatureEntity {
            val aimed = cast.aimed(80.0 + 48.0 * cast.power)
            val at = aimed.entity?.position() ?: aimed.point
            val foot = Vec3(at.x, SignatureKit.groundNear(cast.level, at, 2, 16) ?: at.y, at.z)
            val level = cast.level
            val daylight = level.isDay && !level.isRaining && level.canSeeSky(BlockPos.containing(foot).above())
            SignatureKit.sound(level, cast.origin, EKSounds.SIGNATURE_JUDGEMENT_PILLAR_CAST.get(), 1.6f)
            return spawn(cast, foot, LIFE) {
                point = foot
                data = if (daylight) 1 else 0
            }
        }

        override fun tick(e: SignatureEntity) {
            val level = e.level() as ServerLevel
            val foot = e.position()
            if (e.tickCount == ACTIVE) SignatureKit.sound(level, foot, EKSounds.SIGNATURE_JUDGEMENT_PILLAR_FADE.get(), 2f)
            if (e.tickCount >= ACTIVE) return
            val impact = e.tickCount == IMPACT
            if (impact) {
                SignatureKit.sound(level, foot, EKSounds.SIGNATURE_JUDGEMENT_PILLAR_IMPACT.get(), 2.5f)
                SignatureKit.particles(level, ParticleTypes.END_ROD, foot.add(0.0, 0.5, 0.0), 60, radius(e.power) * 0.5, 0.25)
            } else if (e.tickCount < IMPACT || e.tickCount % EVERY != IMPACT % EVERY) return
            val searSound = !impact && e.tickCount % (EVERY * 4) == IMPACT
            val daylight = if (e.data == 1) DAYLIGHT else 1f
            val r = radius(e.power)
            searAround(e, foot, r, BURN_DOWN, BURN_UP, FIRE_CHANCE)
            val box = AABB(foot.x - r, foot.y - 1.0, foot.z - r, foot.x + r, foot.y + HEIGHT, foot.z + r)
            for (target in level.getEntitiesOfClass(LivingEntity::class.java, box)) {
                if (target === e.caster || !target.isAlive || target.isSpectator) continue
                if (Math.hypot(target.x - foot.x, target.z - foot.z) > r + target.bbWidth * 0.5) continue
                val undead = SpellContext.isUndead(target)
                val against = daylight * (if (undead) UNDEAD else 1f)
                if (impact) {
                    SignatureKit.hurt(e, target, EKDamageSources.SUNFIRE_KEY, (SMITE + MORE_SMITE * e.power) * against)
                    val out = Vec3(target.x - foot.x, 0.0, target.z - foot.z).let { if (it.lengthSqr() < 1.0e-4) Vec3(1.0, 0.0, 0.0) else it.normalize() }
                    SignatureKit.push(target, out.scale(THROW).add(0.0, LIFT, 0.0))
                } else {
                    SignatureKit.hurtThrough(e, target, EKDamageSources.SUNFIRE_KEY, SEAR * against)
                    if (searSound) SignatureKit.sound(level, SignatureKit.middle(target), EKSounds.SIGNATURE_JUDGEMENT_PILLAR_SEAR.get())
                }
                if (undead) {
                    target.setSecondsOnFire(8)
                    target.addEffect(MobEffectInstance(MobEffects.GLOWING, 60, 0), e.caster)
                }
            }
        }
    }

    /** Pools are more entities of this signature; their [SignatureEntity.data] is the pool radius in tenths (see [Pool]). */
    object SupplicationOfSselith : SignatureBehaviour() {
        const val REACH = 56.0
        const val DARK_RADIUS = 6.0
        private const val EVERY = 5
        private const val DAMAGE = 4.5f
        private const val IGNITE_EVERY = 40
        private const val SEAR_EVERY = 5
        private const val SEAR_RADIUS = 1.6
        private const val POOL_EVERY = 10
        private const val POOL_STEP = 2.5
        private const val MAX_POOLS = 14
        /** Fade time in ticks for the oldest pool when there are more than [MAX_POOLS]. */
        private const val POOL_RETIRE = 40
        /** Beam [SignatureEntity.data] while it strikes a block. Pools use positive data. */
        private const val STRIKING = -1

        fun striking(e: SignatureEntity): Boolean = e.data == STRIKING

        private class Held {
            var struck: Vec3? = null
            val pools = ArrayList<SignatureEntity>()
            // Last pool-damage tick for each creature, so that overlapping pools do not stack.
            val pooledAt = HashMap<Int, Int>()
        }

        private fun lightHurt(e: SignatureEntity, target: LivingEntity, amount: Float) {
            e.ctx ?: return
            val level = e.level() as ServerLevel
            val frames = target.invulnerableTime
            target.invulnerableTime = 0
            target.hurt(EKDamageSources.lightDamage(level, target, e.caster), amount)
            target.invulnerableTime = frames
        }

        override fun start(cast: SignatureCast): SignatureEntity {
            SignatureKit.sound(cast.level, cast.origin, EKSounds.SIGNATURE_SUPPLICATION_OF_SSELITH_BEAM_START.get(), 1.6f)
            return spawn(cast, cast.origin, HELD_LIFE) {
                point = cast.origin.add(cast.aim.scale(REACH))
                state = Held()
            }
        }

        override fun tick(e: SignatureEntity) {
            if (Pool.isPool(e.data)) Pool.tick(e)
        }

        override fun held(e: SignatureEntity, cast: SignatureCast, held: Int) {
            holdInHand(e, cast)
            val origin = cast.origin
            val trace = heldBeam(e, cast, REACH)
            val level = cast.level
            val state = e.state as? Held ?: Held().also { e.state = it }
            if (held % EVERY == 0) for (target in creaturesIn(e, trace, origin)) {
                target.addEffect(MobEffectInstance(MobEffects.BLINDNESS, 80, 0), cast.caster)
                target.setSecondsOnFire(5)
                lightHurt(e, target, DAMAGE)
                if (held % (EVERY * 4) == 0) SignatureKit.sound(level, SignatureKit.middle(target), EKSounds.SIGNATURE_SUPPLICATION_OF_SSELITH_BURN.get())
            }
            if (held % 10 == 0) for (player in level.getEntitiesOfClass(ServerPlayer::class.java, AABB(e.point, e.point).inflate(DARK_RADIUS))) {
                if (player !== cast.caster && player.distanceToSqr(e.point) <= DARK_RADIUS * DARK_RADIUS) {
                    player.addEffect(MobEffectInstance(MobEffects.DARKNESS, 60, 0), cast.caster)
                }
            }
            val block = trace.finalBlockPos
            state.struck = if (block != null) e.point else null
            e.data = if (block != null) STRIKING else 0
            if (block != null) {
                if (held % IGNITE_EVERY == IGNITE_EVERY - 1) ignite(e, level, origin)
                if (held % SEAR_EVERY == 0) searAround(e, e.point, SEAR_RADIUS, 2, 2)
                if (held % POOL_EVERY == 0) leavePool(e, cast, e.point, state)
            }
            if (held % 3 == 0) SignatureKit.particles(level, ParticleTypes.LARGE_SMOKE, e.point, 3, 0.5, 0.02)
        }

        override fun released(e: SignatureEntity, cast: SignatureCast, held: Int) {
            val state = e.state as? Held
            state?.struck?.let { leavePool(e, cast, it, state) }
            SignatureKit.sound(cast.level, cast.origin, EKSounds.SIGNATURE_SUPPLICATION_OF_SSELITH_BEAM_STOP.get(), 1.6f)
            super.released(e, cast, held)
        }

        private fun ignite(e: SignatureEntity, level: ServerLevel, origin: Vec3) {
            val dir = e.point.subtract(origin).normalize()
            val hit = level.clipIncludeShips(colliderClip(e.point.subtract(dir.scale(0.6)), e.point.add(dir.scale(0.6)), e.caster))
            if (hit.type != HitResult.Type.BLOCK) return
            placeFire(e, level, hit.blockPos.relative(hit.direction), hit.direction)
        }

        private fun leavePool(e: SignatureEntity, cast: SignatureCast, at: Vec3, beam: Held) {
            beam.pools.removeAll { it.isRemoved }
            if (beam.pools.any { it.position().distanceTo(at) < POOL_STEP }) return
            if (beam.pools.size >= MAX_POOLS) {
                val oldest = beam.pools.removeAt(0)
                // The new life - END_BEFORE is this tick, which the pool's own tick cannot see.
                if (oldest.tickCount < oldest.life - Pool.END_BEFORE) Pool.end(oldest)
                oldest.life = minOf(oldest.life, oldest.tickCount + POOL_RETIRE)
            }
            val r = Pool.radius(cast.power)
            beam.pools += spawn(cast, at, Pool.LIFE + (Pool.MORE_LIFE * cast.power).toInt()) {
                point = at
                data = Math.round(r * 10).toInt()
                state = beam
            }
            SignatureKit.sound(cast.level, at, EKSounds.SIGNATURE_SUPPLICATION_OF_SSELITH_POOL_FORM.get(), 1.2f)
            SignatureKit.particles(cast.level, ParticleTypes.LARGE_SMOKE, at, 12, r * 0.3, 0.04)
        }

        object Pool {
            const val LIFE = 20 * 20
            const val MORE_LIFE = 20 * 10
            const val END_BEFORE = 40
            private const val BASE_RADIUS = 4.0
            private const val MORE_RADIUS = 2.0
            private const val EVERY = 5
            private const val BURN = 4f
            private const val SEAR_EVERY = 40
            private const val SEAR_DOWN = 3
            private const val SEAR_UP = 2

            const val DARK_LEVELS = 13
            /** Darkness goes past the burn radius, so that most of the burn area is fully dark. */
            const val DARK_REACH = 1.3
            private const val DARK_IN = 12
            private const val DARK_OUT = 40
            /** Each change relights the blocks, so the darkness changes in large steps. */
            private const val DARK_STEP = 4

            fun isPool(data: Int): Boolean = data > 0

            /** Server and client must get the same result, so that the two sides agree on the light. */
            fun darkLevels(age: Int, life: Int): Int {
                val fade = minOf(age.toFloat() / DARK_IN, (life - age).toFloat() / DARK_OUT).coerceIn(0f, 1f)
                return if (fade >= 1f) DARK_LEVELS else (DARK_LEVELS * fade / DARK_STEP).toInt() * DARK_STEP
            }

            fun holdDark(e: SignatureEntity) {
                val levels = darkLevels(e.tickCount, e.life)
                if (levels > 0) DarkZones.hold(e.level(), e, e.position(), radiusOf(e.data) * DARK_REACH, levels)
            }

            fun radius(power: Float): Double = BASE_RADIUS + MORE_RADIUS * power

            fun end(e: SignatureEntity) =
                SignatureKit.sound(e.level() as ServerLevel, e.position(), EKSounds.SIGNATURE_SUPPLICATION_OF_SSELITH_POOL_END.get())

            fun radiusOf(data: Int): Double = data / 10.0

            fun tick(e: SignatureEntity) {
                val level = e.level() as ServerLevel
                val centre = e.position()
                val radius = radiusOf(e.data)
                holdDark(e)
                if (e.tickCount % SEAR_EVERY == 1) searAround(e, centre, radius, SEAR_DOWN, SEAR_UP)
                if (e.tickCount == e.life - END_BEFORE) end(e)
                if (e.tickCount % EVERY != 0) return
                val shared = e.state as? Held
                val now = level.server.tickCount
                for (target in SignatureKit.creatures(e, centre, radius)) {
                    if (shared != null) {
                        if (now - (shared.pooledAt[target.id] ?: Int.MIN_VALUE / 2) < EVERY) continue
                        shared.pooledAt[target.id] = now
                    }
                    target.addEffect(MobEffectInstance(MobEffects.BLINDNESS, 50, 0), e.caster)
                    target.addEffect(MobEffectInstance(MobEffects.WEAKNESS, 50, 0), e.caster)
                    target.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, 0), e.caster)
                    if (target is ServerPlayer) target.addEffect(MobEffectInstance(MobEffects.DARKNESS, 60, 0), e.caster)
                    target.setSecondsOnFire(3)
                    lightHurt(e, target, BURN)
                }
                if (shared != null && shared.pooledAt.size > 256) shared.pooledAt.entries.removeIf { now - it.value >= EVERY }
            }
        }
    }
}
