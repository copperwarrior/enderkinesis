package org.shipwrights.enderkinesis.entity

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import org.shipwrights.enderkinesis.registry.EKEffects

private const val NO_THORNS = -1
private const val THORNS_MIN_DAMAGE = 1
private const val THORNS_DAMAGE_SPREAD = 2
private const val POISON_TICKS = 100
// Vanilla LivingEntity.hurt: above this, a new hit only tops up the last one, so it is not a fresh strike.
private const val FRESH_HIT_INVULNERABLE_TICKS = 10

/** Hooks the poisonous skin of Horned Frogs and of Phyllobatic Toxicity holders; call once at mod init. */
fun registerToxicThorns() {
    EntityEvent.LIVING_HURT.register(::onToxicThornsHurt)
}

private fun onToxicThornsHurt(entity: LivingEntity, source: DamageSource, amount: Float): EventResult {
    if (!entity.level().isClientSide && amount > 0f) {
        val level = toxicThornsLevel(entity)
        if (level != NO_THORNS) entity.retaliateWithToxicThorns(source, level)
    }
    return EventResult.pass()
}

/** The effect's amplifier; else 0 for a Horned Frog; else [NO_THORNS]. */
private fun toxicThornsLevel(entity: LivingEntity): Int =
    entity.getEffect(EKEffects.PHYLLOBATIC_TOXICITY.get())?.amplifier ?: if (entity is HornedFrogEntity) 0 else NO_THORNS

/**
 * A direct melee attacker takes a thorns hit of 1–2 + [amplifier] and Poison of level [amplifier] + 1 for 5 s.
 * Projectiles are skipped (their direct entity is the projectile), and so is a predator eating this prey:
 * [eatWhole] tags the prey before its killing hit.
 */
fun LivingEntity.retaliateWithToxicThorns(source: DamageSource, amplifier: Int) {
    val attacker = source.entity as? LivingEntity ?: return
    if (source.directEntity !== attacker || attacker === this || source.`is`(DamageTypes.THORNS)) return
    if (tags.contains(ShilleagkDeerEntity.EATEN_TAG) || isInvulnerableTo(source) ||
        invulnerableTime > FRESH_HIT_INVULNERABLE_TICKS
    ) {
        return
    }
    val damage = THORNS_MIN_DAMAGE + random.nextInt(THORNS_DAMAGE_SPREAD) + amplifier
    // Return values ignored: an attacker in invulnerability frames, or immune to Poison, just escapes this time.
    attacker.hurt(damageSources().thorns(this), damage.toFloat())
    attacker.addEffect(MobEffectInstance(MobEffects.POISON, POISON_TICKS, amplifier), this)
}
