package org.shipwrights.enderkinesis.effect

import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.sounds.SoundEvent
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.spell.SpellSounds
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.phys.Vec3
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.FlyingMob
import net.minecraft.world.entity.ai.control.FlyingMoveControl
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.attributes.AttributeMap
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import org.shipwrights.enderkinesis.registry.EKEffects
import org.shipwrights.enderkinesis.dimension.EKDamageSources
import org.shipwrights.enderkinesis.spell.Absence
import org.shipwrights.enderkinesis.spell.BloodPacts
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.FrozenBodies
import org.shipwrights.enderkinesis.spell.SpellContext
import org.shipwrights.enderkinesis.spell.SpellVfx
import org.shipwrights.enderkinesis.spell.SpellVisuals
import java.util.WeakHashMap
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.mixin.LivingEntityLastHurtAccessor

// Jump, fall damage, heal and redirected damage parts are in LivingEntitySpellEffectsMixin and PlayerSpellEffectsMixin.

class EntangledEffect : MobEffect(MobEffectCategory.HARMFUL, Element.GENESIS.color) {
    private val refreshes = RefreshMarks(this)

    init {
        addAttributeModifier(Attributes.MOVEMENT_SPEED, "6f3a2c41-7b1e-4c0d-9b5a-2e8f1d6c3a01", -1.0, AttributeModifier.Operation.MULTIPLY_TOTAL)
    }

    override fun addAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.addAttributeModifiers(entity, attributes, amplifier)
        SpellVisuals.update(entity)
        if (refreshes.isNew(entity)) playAt(entity, EKSounds.SPELL_GENESIS_ENTANGLE)
    }

    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        SpellVisuals.update(entity)
        refreshes.removed(entity)
        if (!EndingEffects.stillHas(entity, this)) playAt(entity, EKSounds.SPELL_GENESIS_RELEASE)
    }

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = true

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        val v = entity.deltaMovement
        entity.setDeltaMovement(0.0, minOf(v.y, 0.0), 0.0)
    }
}

class WeightedEffect : MobEffect(MobEffectCategory.HARMFUL, Element.GRAVITY.color) {
    private val refreshes = RefreshMarks(this)

    init {
        addAttributeModifier(Attributes.KNOCKBACK_RESISTANCE, "0c9d7e52-3f4a-4b6e-8a1d-5b2c7f9e4d02", 1.0, AttributeModifier.Operation.ADDITION)
    }

    override fun addAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.addAttributeModifiers(entity, attributes, amplifier)
        SpellVisuals.update(entity)
        if (refreshes.isNew(entity)) playAt(entity, EKSounds.SPELL_GRAVITY_WEIGHTED_ON)
    }

    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        refreshes.removed(entity)
        SpellVisuals.update(entity)
        lastPositions.remove(entity)
        falls.remove(entity)
    }

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = true

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        if (entity.isInWater) entity.deltaMovement = entity.deltaMovement.add(0.0, -SINK_PER_TICK, 0.0)
        if (entity.level().isClientSide) return
        // Measure from the last effect tick: by the time effects tick, the old position is already the current one.
        val now = entity.position()
        val last = lastPositions.put(entity, now) ?: return
        val moved = Math.hypot(now.x - last.x, now.z - last.z)
        if (moved > MOVING_PER_TICK && moved < TELEPORTED && entity.tickCount % MOVING_DAMAGE_EVERY == 0) {
            entity.hurt(EKDamageSources.ofEffect(entity, this, EKDamageSources.GRAVITY_KEY), MOVING_DAMAGE + MOVING_DAMAGE_PER_LEVEL * amplifier)
        }
    }

    private class Fall {
        var speed = 0.0
        var fallen = 0.0
        var fromY = 0.0
    }

    companion object {
        // Server thread only.
        private val lastPositions = WeakHashMap<LivingEntity, Vec3>()
        private val falls = WeakHashMap<Entity, Fall>()
        /** Movement above this in one tick is a teleport, so it does no damage. */
        private const val TELEPORTED = 4.0
        private const val SINK_PER_TICK = 0.06
        private const val MOVING_PER_TICK = 0.03
        private const val MOVING_DAMAGE_EVERY = 20
        private const val MOVING_DAMAGE = 0.5f
        private const val MOVING_DAMAGE_PER_LEVEL = 0.25f
        private const val FLYER_GRAVITY = 0.08
        private const val FLYER_DRAG = 0.98
        private const val SAFE_FALL = 3f

        /** Vanilla gives these entities no fall damage of their own. */
        private fun flies(entity: LivingEntity): Boolean = entity is FlyingMob || entity is Bat ||
            (entity is Mob && (entity.navigation is FlyingPathNavigation || entity.moveControl is FlyingMoveControl))

        /** Flyer AI sets its motion again each tick, so apply the fall to the move, not to the motion. */
        @JvmStatic
        fun flyerMotion(entity: Entity, type: MoverType, motion: Vec3): Vec3 {
            if (type != MoverType.SELF || entity !is LivingEntity || entity.level().isClientSide) return motion
            if (!entity.hasEffect(EKEffects.WEIGHTED.get()) || entity.isInWater || !flies(entity)) {
                falls.remove(entity)
                return motion
            }
            val fall = falls.getOrPut(entity) { Fall() }
            fall.speed = (fall.speed + FLYER_GRAVITY) * FLYER_DRAG
            fall.fromY = entity.y
            return Vec3(motion.x, minOf(motion.y, -fall.speed), motion.z)
        }

        @JvmStatic
        fun flyerMoved(entity: Entity) {
            val fall = falls[entity] ?: return
            fall.fallen += maxOf(0.0, fall.fromY - entity.y)
            if (!entity.onGround() || entity !is LivingEntity) return
            val damage = Mth.ceil(fall.fallen - SAFE_FALL)
            fall.speed = 0.0
            fall.fallen = 0.0
            if (damage > 0 && entity.hurt(entity.damageSources().fall(), damage.toFloat())) landed(entity, damage)
        }

        /** The fall hit just gave invulnerability frames. Clear them, or vanilla cancels this second hit. */
        @JvmStatic
        fun landed(entity: LivingEntity, fallDamage: Int) {
            if (fallDamage <= 0 || !entity.isAlive || entity.level().isClientSide) return
            if (!entity.hasEffect(EKEffects.WEIGHTED.get())) return
            entity.invulnerableTime = 0
            if (entity.hurt(EKDamageSources.ofEffect(entity, EKEffects.WEIGHTED.get(), EKDamageSources.GRAVITY_KEY), fallDamage.toFloat())) {
                playAt(entity, EKSounds.SPELL_GRAVITY_WEIGHTED_SLAM)
            }
        }
    }
}

class DiscordEffect : MobEffect(MobEffectCategory.HARMFUL, Element.CHAOS.color) {
    private val refreshes = RefreshMarks(this)

    override fun addAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.addAttributeModifiers(entity, attributes, amplifier)
        if (refreshes.isNew(entity)) playAt(entity, EKSounds.SPELL_CHAOS_DISCORD)
    }

    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        refreshes.removed(entity)
    }

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = duration % 20 == 0

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        val mob = entity as? Mob ?: return
        val level = mob.level() as? ServerLevel ?: return
        val nearby = level.getEntitiesOfClass(LivingEntity::class.java, mob.boundingBox.inflate(RANGE)) {
            it !== mob && it.isAlive && !it.isSpectator
        }
        if (nearby.isNotEmpty()) mob.target = nearby[mob.random.nextInt(nearby.size)]
    }

    private companion object {
        const val RANGE = 10.0
    }
}

class EnthralledEffect : MobEffect(MobEffectCategory.HARMFUL, Element.BLOOD.color) {
    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = duration % 10 == 0

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        (entity as? Mob)?.let(BloodPacts::steerThrall)
    }

    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        if (!EndingEffects.stillHas(entity, this)) BloodPacts.release(entity)
    }
}

class BleedingEffect : MobEffect(MobEffectCategory.HARMFUL, Element.BLOOD.color) {
    private val lastWalk = WeakHashMap<LivingEntity, Float>()

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = duration % 20 == 0

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        if (entity.level().isClientSide) return
        val last = lastWalk.put(entity, entity.walkDist) ?: return
        if (entity.walkDist - last <= MOVED) return
        entity.hurt(EKDamageSources.ofEffect(entity, this, EKDamageSources.BLEEDING_KEY), DAMAGE)
        (entity.level() as? ServerLevel)?.sendParticles(
            EKParticles.bloodDrip(), entity.x, entity.y + entity.bbHeight * 0.6, entity.z, 4,
            entity.bbWidth * 0.3, entity.bbHeight * 0.2, entity.bbWidth * 0.3, 0.0,
        )
    }

    private companion object {
        const val MOVED = 0.05f
        const val DAMAGE = 1f
    }
}

class BloodboundEffect : MobEffect(MobEffectCategory.HARMFUL, Element.BLOOD.color) {
    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        if (!EndingEffects.stillHas(entity, this)) BloodPacts.unbind(entity)
    }
}

/** LivingEntitySpellEffectsMixin blocks all healing. */
class HollowedEffect : MobEffect(MobEffectCategory.HARMFUL, Element.VOID.color)

/** Keep the damage above the heal rate of Regeneration III and lower. */
class RotEffect : MobEffect(MobEffectCategory.HARMFUL, Element.CORRUPTION.color) {
    // Server thread only. Not saved.
    private val steps = WeakHashMap<LivingEntity, Int>()

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = true

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        val level = entity.level() as? ServerLevel ?: return
        val time = entity.tickCount
        if (time % DAMAGE_INTERVAL == 0) {
            bite(entity, damage(amplifier))
            level.sendParticles(
                EKParticles.rotSpore(), entity.x, entity.y + entity.bbHeight * 0.5, entity.z, 5,
                entity.bbWidth * 0.4, entity.bbHeight * 0.3, entity.bbWidth * 0.4, 0.01,
            )
        }
        if (time % SICKNESS_INTERVAL == 0 && !SpellContext.isUndead(entity)) {
            entity.addEffect(MobEffectInstance(MobEffects.HUNGER, SICKNESS_TICKS, 0, false, false))
            entity.addEffect(MobEffectInstance(MobEffects.WEAKNESS, SICKNESS_TICKS, 0, false, false))
        }
        val step = steps[entity] ?: 0
        if (time % SPREAD_INTERVAL == 0 && step < MAX_STEPS && entity.random.nextFloat() < SPREAD_CHANCE) spread(level, entity, step)
    }

    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        if (!EndingEffects.stillHas(entity, this)) steps.remove(entity)
    }

    /** Goes through invulnerability frames, then restores them and lastHurt, so a carrier in a fight still rots on time. */
    private fun bite(entity: LivingEntity, amount: Float) {
        val frames = entity.invulnerableTime
        val last = (entity as LivingEntityLastHurtAccessor).`enderkinesis$getLastHurt`()
        entity.invulnerableTime = 0
        entity.hurt(EKDamageSources.ofEffect(entity, this, EKDamageSources.ROT_KEY), amount)
        entity.invulnerableTime = frames
        (entity as LivingEntityLastHurtAccessor).`enderkinesis$setLastHurt`(last)
        playAt(entity, EKSounds.SPELL_CORRUPTION_ROT)
    }

    private fun spread(level: ServerLevel, entity: LivingEntity, step: Int) {
        val current = entity.getEffect(this) ?: return
        val remaining = current.duration
        val next = level.getEntitiesOfClass(Mob::class.java, entity.boundingBox.inflate(SPREAD_RANGE)) {
            it !== entity && it.isAlive && !it.hasEffect(this) && it.distanceToSqr(entity) <= SPREAD_RANGE * SPREAD_RANGE
        }.minByOrNull { it.distanceToSqr(entity) } ?: return
        if (next.addEffect(MobEffectInstance(this, remaining / 2, current.amplifier), SpellCredit.caster(entity, this))) {
            steps[next] = step + 1
            playAt(next, EKSounds.SPELL_CORRUPTION_ROT_SPREAD)
        }
    }

    companion object {
        const val DAMAGE_INTERVAL = 20
        const val DAMAGE = 2f
        const val DAMAGE_PER_LEVEL = 1.5f
        const val MAX_LEVEL = 3
        const val SICKNESS_INTERVAL = 20
        const val SICKNESS_TICKS = 40
        const val SPREAD_INTERVAL = 60
        const val SPREAD_CHANCE = 0.6f
        const val SPREAD_RANGE = 2.5
        const val MAX_STEPS = 3

        fun damage(amplifier: Int): Float = DAMAGE + DAMAGE_PER_LEVEL * amplifier.coerceIn(0, MAX_LEVEL)
    }
}

class FrozenEffect : MobEffect(MobEffectCategory.HARMFUL, Element.WINTER.color) {
    private val refreshes = RefreshMarks(this)

    init {
        addAttributeModifier(Attributes.MOVEMENT_SPEED, "a3f1e0d2-5c6b-4a79-8e2d-9f4b1c7a6e03", -1.0, AttributeModifier.Operation.MULTIPLY_TOTAL)
    }

    override fun addAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.addAttributeModifiers(entity, attributes, amplifier)
        FrozenBodies.freeze(entity)
        if (refreshes.isNew(entity)) playAt(entity, EKSounds.SPELL_WINTER_FREEZE)
        SpellVisuals.update(entity)
    }

    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        SpellVisuals.update(entity)
        refreshes.removed(entity)
        if (EndingEffects.stillHas(entity, this)) return
        FrozenBodies.thaw(entity)
        playAt(entity, EKSounds.SPELL_WINTER_THAW)
    }

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = true

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        if (!entity.level().isClientSide && entity.isUsingItem) entity.releaseUsingItem()
    }

    companion object {
        const val THRESHOLD = 600
    }
}

class AbsentEffect : MobEffect(MobEffectCategory.HARMFUL, Element.VOID.color) {
    init {
        addAttributeModifier(Attributes.MOVEMENT_SPEED, "d7c2b1a0-9e8f-4d3c-b2a1-0f9e8d7c6b04", -1.0, AttributeModifier.Operation.MULTIPLY_TOTAL)
    }

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = true

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) = Absence.hold(entity)

    override fun addAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.addAttributeModifiers(entity, attributes, amplifier)
        Absence.vanish(entity)
    }

    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        if (!EndingEffects.stillHas(entity, this)) Absence.reappear(entity)
    }
}

/** [org.shipwrights.enderkinesis.spell.NamedEffects.anchored] blocks spell pulls and throws. */
class AnchoredEffect : MobEffect(MobEffectCategory.BENEFICIAL, Element.GRAVITY.color) {
    init {
        addAttributeModifier(Attributes.KNOCKBACK_RESISTANCE, "4a7d2e19-6c3b-4f8a-9d1e-7b5c2a8f3e05", 1.0, AttributeModifier.Operation.ADDITION)
        addAttributeModifier(Attributes.MOVEMENT_SPEED, "8e1f3a62-2d4c-4b9e-a7f0-3c6d9b2e1a06", -0.15, AttributeModifier.Operation.MULTIPLY_TOTAL)
    }

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = true

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        if (entity.isInWater) entity.deltaMovement = entity.deltaMovement.add(0.0, -ANCHOR_SINK, 0.0)
    }

    private companion object {
        const val ANCHOR_SINK = 0.03
    }
}

/** The amplifier is the rounded magnitude minus one. */
class PaleCrownEffect : MobEffect(MobEffectCategory.BENEFICIAL, Element.SUNFIRE.color) {
    override fun addAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.addAttributeModifiers(entity, attributes, amplifier)
        SpellVisuals.update(entity)
    }

    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        SpellVisuals.update(entity)
    }

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = duration % HALO_EVERY == 0

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        val level = entity.level() as? ServerLevel ?: return
        val radius = radius(amplifier)
        val centre = entity.position()
        for (undead in level.getEntitiesOfClass(LivingEntity::class.java, entity.boundingBox.inflate(radius)) {
            it !== entity && it.isAlive && SpellContext.isUndead(it) && it.distanceToSqr(centre) <= radius * radius
        }) {
            undead.setSecondsOnFire(HALO_BURN_SECONDS)
            SpellSounds.smite(level, undead, SpellSounds.magnitudeVolume(amplifier + 1f))
            val away = undead.position().subtract(centre).multiply(1.0, 0.0, 1.0)
            if (away.lengthSqr() < 1.0e-4) continue
            undead.deltaMovement = undead.deltaMovement.add(away.normalize().scale(HALO_PUSH))
            undead.hurtMarked = true
        }
    }

    companion object {
        private const val HALO_EVERY = 5
        private const val HALO_BURN_SECONDS = 8
        private const val HALO_PUSH = 0.35

        fun radius(amplifier: Int): Double = 3.0 + (amplifier + 1) * 0.5
    }
}

/** The amplifier is the cast tier. */
abstract class ElementMantleEffect(element: Element) : MobEffect(MobEffectCategory.BENEFICIAL, element.color) {
    override fun addAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.addAttributeModifiers(entity, attributes, amplifier)
        SpellVisuals.update(entity)
    }

    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        SpellVisuals.update(entity)
    }

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = duration % EVERY == 0

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        val level = entity.level() as? ServerLevel ?: return
        wearer(entity)
        val ctx = SpellContext(level, entity, entity.position())
        for (target in level.getEntitiesOfClass(LivingEntity::class.java, entity.boundingBox.inflate(radius)) {
            it !== entity && it.isAlive && it.distanceToSqr(entity) <= radius * radius && bites(entity, it)
        }) touch(ctx, target, amplifier)
    }

    protected open val radius: Double = RADIUS

    protected open fun bites(wearer: LivingEntity, target: LivingEntity): Boolean = true

    /** Runs on each bite tick, also when no target is near. */
    protected open fun wearer(entity: LivingEntity) {}

    protected abstract fun touch(ctx: SpellContext, target: LivingEntity, amplifier: Int)

    protected fun damage(amplifier: Int): Float = BITE + amplifier

    companion object {
        const val EVERY = 10
        const val RADIUS = 2.5
        private const val BITE = 1f
    }
}

class MantleOfFlameEffect : ElementMantleEffect(Element.PYRE) {
    override fun wearer(entity: LivingEntity) {
        entity.ticksFrozen = 0
    }

    override fun touch(ctx: SpellContext, target: LivingEntity, amplifier: Int) {
        target.setSecondsOnFire(BURN_SECONDS)
        ctx.hurt(target, ctx.damage(EKDamageSources.PYRE_KEY), damage(amplifier))
    }

    private companion object {
        const val BURN_SECONDS = 3
    }
}

/** The chill goes to vanilla freezing, never to [FrozenEffect]. */
class MantleOfFrostEffect : ElementMantleEffect(Element.WINTER) {
    override fun wearer(entity: LivingEntity) {
        entity.clearFire()
    }

    override fun touch(ctx: SpellContext, target: LivingEntity, amplifier: Int) {
        target.ticksFrozen = maxOf(target.ticksFrozen, minOf(target.ticksRequiredToFreeze + CHILL_OVER, target.ticksFrozen + CHILL))
        target.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, SLOW_TICKS, amplifier), ctx.caster)
        ctx.hurt(target, ctx.damage(EKDamageSources.WINTER_KEY), damage(amplifier))
    }

    private companion object {
        /** Vanilla thaws 2 a tick, so this builds while the target stays near. */
        const val CHILL = 50
        /** Enough to stay frozen for a short time after the target moves away. */
        const val CHILL_OVER = 40
        const val SLOW_TICKS = 30
    }
}

class MantleOfLightningEffect : ElementMantleEffect(Element.CHAOS) {
    override fun touch(ctx: SpellContext, target: LivingEntity, amplifier: Int) {
        val wearer = ctx.caster ?: return
        SpellVfx.soundedArc(ctx.level, chest(wearer), chest(target), Element.CHAOS.color)
        ctx.hurt(target, ctx.damage(EKDamageSources.CHAOS_KEY), damage(amplifier))
    }
}

class MantleOfAbsorptionEffect : ElementMantleEffect(Element.BLOOD) {
    override val radius = REACH

    override fun touch(ctx: SpellContext, target: LivingEntity, amplifier: Int) {
        val wearer = ctx.caster ?: return
        val drain = DRAIN * (amplifier + 1)
        if (!ctx.hurt(target, ctx.damage(EKDamageSources.BLOOD_DRAIN_KEY), drain)) return
        wearer.heal(drain)
        SpellVfx.stream(ctx.level, chest(target), chest(wearer), Element.BLOOD.color, STREAM_TICKS)
        // One bite can drain many targets in the same tick.
        if (lastStreams.put(wearer, ctx.level.gameTime) != ctx.level.gameTime) playAt(wearer, EKSounds.SPELL_BLOOD_STREAM)
    }

    private companion object {
        const val REACH = 1.75
        const val DRAIN = 0.5f
        const val STREAM_TICKS = 10
        // Server thread only.
        val lastStreams = WeakHashMap<LivingEntity, Long>()
    }
}

/** [org.shipwrights.enderkinesis.spell.GasField] also skips the wearer. */
class MantleOfPoisonEffect : ElementMantleEffect(Element.CORRUPTION) {
    override fun wearer(entity: LivingEntity) {
        entity.removeEffect(MobEffects.POISON)
        entity.removeEffect(EKEffects.ROT.get())
    }

    override fun touch(ctx: SpellContext, target: LivingEntity, amplifier: Int) {
        target.addEffect(MobEffectInstance(MobEffects.POISON, POISON_TICKS, amplifier), ctx.caster)
    }

    private companion object {
        const val POISON_TICKS = 60
    }
}

class MantleOfVoidEffect : ElementMantleEffect(Element.VOID) {
    override val radius = REACH

    override fun bites(wearer: LivingEntity, target: LivingEntity): Boolean =
        target is Enemy || (target is Mob && target.target === wearer)

    override fun touch(ctx: SpellContext, target: LivingEntity, amplifier: Int) {
        val wearer = ctx.caster ?: return
        SpellVfx.lash(ctx.level, wearer, chest(target))
        SpellSounds.play(ctx.level, chest(target), EKSounds.SPELL_VOID_LASH)
        if (ctx.hurt(target, ctx.damage(EKDamageSources.VOID_KEY), damage(amplifier))) {
            target.knockback(KNOCKBACK, wearer.x - target.x, wearer.z - target.z)
        }
    }

    private companion object {
        const val REACH = 3.5
        const val KNOCKBACK = 0.4
    }
}

private fun chest(entity: Entity): Vec3 = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0)

private fun playAt(entity: LivingEntity, sound: RegistrySupplier<SoundEvent>) {
    (entity.level() as? ServerLevel)?.let { SpellSounds.play(it, entity.position(), sound) }
}
