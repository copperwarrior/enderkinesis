package org.shipwrights.enderkinesis.effect

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

// The status effects the Mystic Scroll elements apply (Crystallized has its own file). Each
// is small; the parts that reach outside an effect tick (jumping, fall damage, healing,
// redirected damage) are in LivingEntitySpellEffectsMixin and PlayerSpellEffectsMixin.

/** Growth: roots hold the mob in place. */
class EntangledEffect : MobEffect(MobEffectCategory.HARMFUL, Element.GROWTH.color) {
    init {
        addAttributeModifier(Attributes.MOVEMENT_SPEED, "6f3a2c41-7b1e-4c0d-9b5a-2e8f1d6c3a01", -1.0, AttributeModifier.Operation.MULTIPLY_TOTAL)
    }

    override fun addAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.addAttributeModifiers(entity, attributes, amplifier)
        SpellVisuals.update(entity)
    }

    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        SpellVisuals.update(entity)
    }

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = true

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        val v = entity.deltaMovement
        entity.setDeltaMovement(0.0, minOf(v.y, 0.0), 0.0)
    }
}

/** Gravity: can't jump (see the mixin), sinks in water, ignores knockback, and takes Gravity
 *  damage on top of every fall, as much again as the fall itself (see [landed]). */
class WeightedEffect : MobEffect(MobEffectCategory.HARMFUL, Element.GRAVITY.color) {
    init {
        addAttributeModifier(Attributes.KNOCKBACK_RESISTANCE, "0c9d7e52-3f4a-4b6e-8a1d-5b2c7f9e4d02", 1.0, AttributeModifier.Operation.ADDITION)
    }

    override fun addAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.addAttributeModifiers(entity, attributes, amplifier)
        SpellVisuals.update(entity)
    }

    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        SpellVisuals.update(entity)
        lastPositions.remove(entity)
        falls.remove(entity)
    }

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = true

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        if (entity.isInWater) entity.deltaMovement = entity.deltaMovement.add(0.0, -SINK_PER_TICK, 0.0)
        if (entity.level().isClientSide) return
        // Dragging the weight about hurts a little. Measured from the last effect tick: the old
        // position is already the current one by the time effects tick.
        val now = entity.position()
        val last = lastPositions.put(entity, now) ?: return
        val moved = Math.hypot(now.x - last.x, now.z - last.z)
        if (moved > MOVING_PER_TICK && moved < TELEPORTED && entity.tickCount % MOVING_DAMAGE_EVERY == 0) {
            entity.hurt(EKDamageSources.ofEffect(entity, this, EKDamageSources.GRAVITY_KEY), MOVING_DAMAGE + MOVING_DAMAGE_PER_LEVEL * amplifier)
        }
    }

    /** A Weighted flyer's fall: how fast it's going, how far it has come, and where this move
     *  started. */
    private class Fall {
        var speed = 0.0
        var fallen = 0.0
        var fromY = 0.0
    }

    companion object {
        // Server thread only.
        private val lastPositions = WeakHashMap<LivingEntity, Vec3>()
        private val falls = WeakHashMap<Entity, Fall>()
        /** Farther than this in a tick is a teleport, not dragging the weight. */
        private const val TELEPORTED = 4.0
        private const val SINK_PER_TICK = 0.06
        /** Moving faster than this, in blocks a tick, it takes [MOVING_DAMAGE] (and
         *  [MOVING_DAMAGE_PER_LEVEL] more per level) every [MOVING_DAMAGE_EVERY] ticks. */
        private const val MOVING_PER_TICK = 0.03
        private const val MOVING_DAMAGE_EVERY = 20
        private const val MOVING_DAMAGE = 0.5f
        private const val MOVING_DAMAGE_PER_LEVEL = 0.25f
        /** A flyer under the weight falls as an item does, and takes fall damage as a mob. */
        private const val FLYER_GRAVITY = 0.08
        private const val FLYER_DRAG = 0.98
        private const val SAFE_FALL = 3f

        /** Whether [entity] holds itself up in the air, so has no fall damage of its own. */
        private fun flies(entity: LivingEntity): Boolean = entity is FlyingMob || entity is Bat ||
            (entity is Mob && (entity.navigation is FlyingPathNavigation || entity.moveControl is FlyingMoveControl))

        /**
         * A move [entity] makes of its own, [motion]: a Weighted flyer can't hold itself up, so
         * whatever its flight asks for it falls as anything else does (its own way across is
         * kept). Every flyer's AI sets its motion afresh, so the fall is applied here, on the
         * move, not to its motion.
         */
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

        /** After [entity]'s move: a Weighted flyer's fall is counted, and on landing it takes
         *  fall damage as a mob would (vanilla gives flyers none), then Gravity's on top. */
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

        /** After a Weighted [entity] has taken [fallDamage] from a fall: the same again, as
         *  Gravity. The fall's own hit has just made it briefly invulnerable, so that is
         *  cleared first or this second hit would be swallowed. */
        @JvmStatic
        fun landed(entity: LivingEntity, fallDamage: Int) {
            if (fallDamage <= 0 || !entity.isAlive || entity.level().isClientSide) return
            if (!entity.hasEffect(EKEffects.WEIGHTED.get())) return
            entity.invulnerableTime = 0
            entity.hurt(EKDamageSources.ofEffect(entity, EKEffects.WEIGHTED.get(), EKDamageSources.GRAVITY_KEY), fallDamage.toFloat())
        }
    }
}

/** Chaos: a mob turns on a random nearby target each second. Players see their allies as
 *  zombies (client). */
class DiscordEffect : MobEffect(MobEffectCategory.HARMFUL, Element.CHAOS.color) {
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

/** Blood: the mob fights for whoever enthralled it; see [BloodPacts]. */
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

/** Blood: 1 damage a second, only while the target is moving. */
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

/** Blood: part of the damage its binder takes passes to the bound instead; see [BloodPacts]. */
class BloodboundEffect : MobEffect(MobEffectCategory.HARMFUL, Element.BLOOD.color) {
    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        if (!EndingEffects.stillHas(entity, this)) BloodPacts.unbind(entity)
    }
}

/** Void: the target can't heal from any source (see the mixin). */
class HollowedEffect : MobEffect(MobEffectCategory.HARMFUL, Element.VOID.color)

/**
 * Poison: a venom that also affects undead, and kills. [DAMAGE] a second and [DAMAGE_PER_LEVEL]
 * more for each level past I (Rot I 2, Rot II 3.5, Rot III 5, Rot IV 6.5), which outpaces any
 * Regeneration short of IV. Each bite lands through the i-frames other hits leave, without
 * making the next of those any easier, so a carrier being fought still rots on time. Living
 * targets also get Hunger and Weakness. The level is the spell's tier, and another dose on a
 * carrier deepens it by one, up to Rot IV ([MAX_LEVEL]).
 *
 * Contagion: every 3 s it has a 60% chance to spread to the nearest mob within 2.5 blocks
 * without Rot, at the same level and half the remaining duration. Each infection counts its
 * spread steps (kept here, apart from the level), and the third step spreads no further.
 */
class RotEffect : MobEffect(MobEffectCategory.HARMFUL, Element.POISON.color) {
    // Server only, not saved: how many spreads each carrier's Rot is from the first.
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

    /** Hurts [entity] by [amount] as Rot, credited to whoever infected it, through any
     *  i-frames, leaving them (and the damage a hit inside them must beat) as they were. */
    private fun bite(entity: LivingEntity, amount: Float) {
        val frames = entity.invulnerableTime
        val last = (entity as LivingEntityLastHurtAccessor).`enderkinesis$getLastHurt`()
        entity.invulnerableTime = 0
        entity.hurt(EKDamageSources.ofEffect(entity, this, EKDamageSources.ROT_KEY), amount)
        entity.invulnerableTime = frames
        (entity as LivingEntityLastHurtAccessor).`enderkinesis$setLastHurt`(last)
    }

    private fun spread(level: ServerLevel, entity: LivingEntity, step: Int) {
        val current = entity.getEffect(this) ?: return
        val remaining = current.duration
        val next = level.getEntitiesOfClass(Mob::class.java, entity.boundingBox.inflate(SPREAD_RANGE)) {
            it !== entity && it.isAlive && !it.hasEffect(this) && it.distanceToSqr(entity) <= SPREAD_RANGE * SPREAD_RANGE
        }.minByOrNull { it.distanceToSqr(entity) } ?: return
        // Whoever infected the first carries the blame down the chain.
        if (next.addEffect(MobEffectInstance(this, remaining / 2, current.amplifier), SpellCredit.caster(entity, this))) steps[next] = step + 1
    }

    companion object {
        const val DAMAGE_INTERVAL = 20
        /** Rot I's damage a second. */
        const val DAMAGE = 2f
        /** Each level past I adds this a second. */
        const val DAMAGE_PER_LEVEL = 1.5f
        /** The deepest Rot gets: Rot IV. */
        const val MAX_LEVEL = 3
        const val SICKNESS_INTERVAL = 20
        const val SICKNESS_TICKS = 40
        const val SPREAD_INTERVAL = 60
        const val SPREAD_CHANCE = 0.6f
        const val SPREAD_RANGE = 2.5
        const val MAX_STEPS = 3

        /** One bite of Rot at [amplifier]. */
        fun damage(amplifier: Int): Float = DAMAGE + DAMAGE_PER_LEVEL * amplifier.coerceIn(0, MAX_LEVEL)
    }
}

/**
 * Cold: a mob or player becomes a VS2 physics object for the duration (see [FrozenBodies]),
 * and can't act. Applied once a target's accumulated freeze ticks reach the
 * threshold.
 */
class FrozenEffect : MobEffect(MobEffectCategory.HARMFUL, Element.COLD.color) {
    init {
        addAttributeModifier(Attributes.MOVEMENT_SPEED, "a3f1e0d2-5c6b-4a79-8e2d-9f4b1c7a6e03", -1.0, AttributeModifier.Operation.MULTIPLY_TOTAL)
    }

    override fun addAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.addAttributeModifiers(entity, attributes, amplifier)
        FrozenBodies.freeze(entity)
        SpellVisuals.update(entity)
    }

    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        SpellVisuals.update(entity)
        if (EndingEffects.stillHas(entity, this)) return
        FrozenBodies.thaw(entity)
    }

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = true

    /** Frozen solid, nothing held in the hands keeps going (a drawn bow, a held cast). */
    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        if (!entity.level().isClientSide && entity.isUsingItem) entity.releaseUsingItem()
    }

    companion object {
        /** Accumulated freeze ticks at which Cold freezes a target solid. */
        const val THRESHOLD = 600
    }
}

/** Void: the target is out of the world; see [Absence]. */
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

/** Gravity's self-cast: held fast. No knockback, no spell pulls or throws (see
 *  [org.shipwrights.enderkinesis.spell.NamedEffects.anchored]), sinks in water, and a little slower. */
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

/** Sunfire's self-cast: a ring of sunlight. Undead within its radius burn as in daylight and are
 *  pushed out of it. The amplifier is the magnitude, rounded, less one: the radius is
 *  3 blocks and half a block more for each. */
class HaloEffect : MobEffect(MobEffectCategory.BENEFICIAL, Element.SUNFIRE.color) {
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

/**
 * The elements' cloaks, each its self-cast: a cloak round you that bites whatever comes within
 * [radius], every [EVERY] ticks, and may do something for you while it lasts ([wearer]). The
 * amplifier carries the cast's tier, which sets how hard it bites.
 */
abstract class ElementCloakEffect(element: Element) : MobEffect(MobEffectCategory.BENEFICIAL, element.color) {
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

    /** How far the cloak reaches. */
    protected open val radius: Double = RADIUS

    /** Whether the cloak bites [target], within reach of its [wearer]. */
    protected open fun bites(wearer: LivingEntity, target: LivingEntity): Boolean = true

    /** What the cloak does for whoever wears it, each time it bites. */
    protected open fun wearer(entity: LivingEntity) {}

    /** What the cloak does to [target], close by. */
    protected abstract fun touch(ctx: SpellContext, target: LivingEntity, amplifier: Int)

    protected fun damage(amplifier: Int): Float = BITE + amplifier

    companion object {
        const val EVERY = 10
        const val RADIUS = 2.5
        private const val BITE = 1f
    }
}

/** Heat's self-cast: flames wrap you, setting alight whatever comes close, and thaw you out. */
class FlameCloakEffect : ElementCloakEffect(Element.HEAT) {
    override fun wearer(entity: LivingEntity) {
        entity.ticksFrozen = 0
    }

    override fun touch(ctx: SpellContext, target: LivingEntity, amplifier: Int) {
        target.setSecondsOnFire(BURN_SECONDS)
        ctx.hurt(target, ctx.damage(EKDamageSources.HEAT_KEY), damage(amplifier))
    }

    private companion object {
        const val BURN_SECONDS = 3
    }
}

/** Cold's self-cast: frost wraps you, chilling and slowing whatever comes close, and keeps you
 *  from burning. Its chill builds to vanilla's freezing, never to frozen solid. */
class FrostCloakEffect : ElementCloakEffect(Element.COLD) {
    override fun wearer(entity: LivingEntity) {
        entity.clearFire()
    }

    override fun touch(ctx: SpellContext, target: LivingEntity, amplifier: Int) {
        target.ticksFrozen = maxOf(target.ticksFrozen, minOf(target.ticksRequiredToFreeze + CHILL_OVER, target.ticksFrozen + CHILL))
        target.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, SLOW_TICKS, amplifier), ctx.caster)
        ctx.hurt(target, ctx.damage(EKDamageSources.COLD_KEY), damage(amplifier))
    }

    private companion object {
        /** Frost per bite; vanilla thaws 2 a tick, so it builds while they stay close. */
        const val CHILL = 50
        /** How far past freezing it goes: enough to stay frozen a moment after they step away. */
        const val CHILL_OVER = 40
        const val SLOW_TICKS = 30
    }
}

/** Chaos's self-cast: lightning crackles round you, arcing into whatever comes close. */
class LightningCloakEffect : ElementCloakEffect(Element.CHAOS) {
    override fun touch(ctx: SpellContext, target: LivingEntity, amplifier: Int) {
        val wearer = ctx.caster ?: return
        SpellVfx.arc(ctx.level, chest(wearer), chest(target), Element.CHAOS.color)
        ctx.hurt(target, ctx.damage(EKDamageSources.CHAOS_KEY), damage(amplifier))
    }
}

/** Blood's self-cast: a cloak of blood close round you, drawing a little life out of whatever
 *  comes very close and into you. */
class AbsorptionCloakEffect : ElementCloakEffect(Element.BLOOD) {
    override val radius = REACH

    override fun touch(ctx: SpellContext, target: LivingEntity, amplifier: Int) {
        val wearer = ctx.caster ?: return
        val drain = DRAIN * (amplifier + 1)
        if (!ctx.hurt(target, ctx.damage(EKDamageSources.BLOOD_DRAIN_KEY), drain)) return
        wearer.heal(drain)
        SpellVfx.stream(ctx.level, chest(target), chest(wearer), Element.BLOOD.color, STREAM_TICKS)
    }

    private companion object {
        const val REACH = 1.75
        /** Health drawn per bite, per tier. */
        const val DRAIN = 0.5f
        const val STREAM_TICKS = 10
    }
}

/** Poison's self-cast: a cloak of poison round you, poisoning whatever comes close; Poison and
 *  Rot don't take on you while it lasts (and Miasma passes you by: see
 *  [org.shipwrights.enderkinesis.spell.GasField]). */
class PoisonCloakEffect : ElementCloakEffect(Element.POISON) {
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

/** Void's self-cast: dark swirls round you, and tentacles whipping out of them at enemies
 *  nearby: monsters, and whatever's after you. */
class VoidCloakEffect : ElementCloakEffect(Element.VOID) {
    override val radius = REACH

    override fun bites(wearer: LivingEntity, target: LivingEntity): Boolean =
        target is Enemy || (target is Mob && target.target === wearer)

    override fun touch(ctx: SpellContext, target: LivingEntity, amplifier: Int) {
        val wearer = ctx.caster ?: return
        SpellVfx.lash(ctx.level, wearer, chest(target))
        if (ctx.hurt(target, ctx.damage(EKDamageSources.VOID_KEY), damage(amplifier))) {
            target.knockback(KNOCKBACK, wearer.x - target.x, wearer.z - target.z)
        }
    }

    private companion object {
        const val REACH = 3.5
        const val KNOCKBACK = 0.4
    }
}

/** Halfway up [entity], where a cloak's arcs and streams meet it. */
private fun chest(entity: Entity): Vec3 = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0)
