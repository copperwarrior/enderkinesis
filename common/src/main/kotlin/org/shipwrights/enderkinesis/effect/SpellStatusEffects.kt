package org.shipwrights.enderkinesis.effect

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeMap
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import org.shipwrights.enderkinesis.spell.Absence
import org.shipwrights.enderkinesis.spell.BloodPacts
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.FrozenBodies
import org.shipwrights.enderkinesis.spell.SpellContext
import java.util.WeakHashMap

// The status effects the Mystic Scroll elements apply (Crystallized has its own file). Each
// is small; the parts that reach outside an effect tick (jumping, fall damage, healing,
// redirected damage) are in LivingEntitySpellEffectsMixin and PlayerSpellEffectsMixin.

/** Growth: roots hold the mob in place. */
class EntangledEffect : MobEffect(MobEffectCategory.HARMFUL, Element.GROWTH.color) {
    init {
        addAttributeModifier(Attributes.MOVEMENT_SPEED, "6f3a2c41-7b1e-4c0d-9b5a-2e8f1d6c3a01", -1.0, AttributeModifier.Operation.MULTIPLY_TOTAL)
    }

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = true

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        val v = entity.deltaMovement
        entity.setDeltaMovement(0.0, minOf(v.y, 0.0), 0.0)
    }
}

/** Gravity: can't jump (see the mixin), sinks in water, ignores knockback, and takes double
 *  fall damage (see the mixin). */
class WeightedEffect : MobEffect(MobEffectCategory.HARMFUL, Element.GRAVITY.color) {
    init {
        addAttributeModifier(Attributes.KNOCKBACK_RESISTANCE, "0c9d7e52-3f4a-4b6e-8a1d-5b2c7f9e4d02", 1.0, AttributeModifier.Operation.ADDITION)
    }

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = true

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        if (entity.isInWater) entity.deltaMovement = entity.deltaMovement.add(0.0, -SINK_PER_TICK, 0.0)
    }

    private companion object {
        const val SINK_PER_TICK = 0.06
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
        if (!entity.hasEffect(this)) BloodPacts.release(entity)
    }
}

/** Blood: 1 damage a second, only while the target is moving. */
class BleedingEffect : MobEffect(MobEffectCategory.HARMFUL, Element.BLOOD.color) {
    private val lastWalk = WeakHashMap<LivingEntity, Float>()

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = duration % 20 == 0

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        if (entity.level().isClientSide) return
        val last = lastWalk.put(entity, entity.walkDist) ?: return
        if (entity.walkDist - last > MOVED) entity.hurt(entity.damageSources().magic(), DAMAGE)
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
        if (!entity.hasEffect(this)) BloodPacts.unbind(entity)
    }
}

/** Void: the target can't heal from any source (see the mixin). */
class HollowedEffect : MobEffect(MobEffectCategory.HARMFUL, Element.VOID.color)

/**
 * Poison: a venom that also affects undead. 1 damage every 1.25 s, never below 1 health;
 * living targets also get Hunger and Weakness. Contagion: every 3 s it has a 60% chance to
 * spread to the nearest mob within 2.5 blocks without Rot, at half the remaining duration.
 * The amplifier counts the spread steps, and the third step spreads no further.
 */
class RotEffect : MobEffect(MobEffectCategory.HARMFUL, Element.POISON.color) {
    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = true

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        val level = entity.level() as? ServerLevel ?: return
        val time = entity.tickCount
        if (time % DAMAGE_INTERVAL == 0 && entity.health > 1f) {
            entity.hurt(entity.damageSources().magic(), minOf(DAMAGE, entity.health - 1f))
        }
        if (time % SICKNESS_INTERVAL == 0 && !SpellContext.isUndead(entity)) {
            entity.addEffect(MobEffectInstance(MobEffects.HUNGER, SICKNESS_TICKS, 0, false, false))
            entity.addEffect(MobEffectInstance(MobEffects.WEAKNESS, SICKNESS_TICKS, 0, false, false))
        }
        if (time % SPREAD_INTERVAL == 0 && amplifier < MAX_STEPS && entity.random.nextFloat() < SPREAD_CHANCE) spread(level, entity, amplifier)
    }

    private fun spread(level: ServerLevel, entity: LivingEntity, step: Int) {
        val remaining = entity.getEffect(this)?.duration ?: return
        val next = level.getEntitiesOfClass(Mob::class.java, entity.boundingBox.inflate(SPREAD_RANGE)) {
            it !== entity && it.isAlive && !it.hasEffect(this) && it.distanceToSqr(entity) <= SPREAD_RANGE * SPREAD_RANGE
        }.minByOrNull { it.distanceToSqr(entity) } ?: return
        next.addEffect(MobEffectInstance(this, remaining / 2, step + 1))
    }

    companion object {
        /** Poison II's rate. */
        const val DAMAGE_INTERVAL = 25
        const val DAMAGE = 1f
        const val SICKNESS_INTERVAL = 20
        const val SICKNESS_TICKS = 40
        const val SPREAD_INTERVAL = 60
        const val SPREAD_CHANCE = 0.6f
        const val SPREAD_RANGE = 2.5
        const val MAX_STEPS = 3
    }
}

/**
 * Cold: a mob becomes a VS2 physics object for the duration (see [FrozenBodies]); a player
 * is held still. Applied once a target's accumulated freeze ticks reach the threshold.
 */
class FrozenEffect : MobEffect(MobEffectCategory.HARMFUL, Element.COLD.color) {
    init {
        addAttributeModifier(Attributes.MOVEMENT_SPEED, "a3f1e0d2-5c6b-4a79-8e2d-9f4b1c7a6e03", -1.0, AttributeModifier.Operation.MULTIPLY_TOTAL)
    }

    override fun addAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.addAttributeModifiers(entity, attributes, amplifier)
        FrozenBodies.freeze(entity)
    }

    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        if (!entity.hasEffect(this)) FrozenBodies.thaw(entity)
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
        if (!entity.hasEffect(this)) Absence.reappear(entity)
    }
}
