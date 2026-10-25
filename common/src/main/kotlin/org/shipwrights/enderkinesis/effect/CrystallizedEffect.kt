package org.shipwrights.enderkinesis.effect

import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeMap
import org.shipwrights.enderkinesis.dimension.EKDamageSources
import org.shipwrights.enderkinesis.spell.SpellCurves
import org.shipwrights.enderkinesis.spell.SpellVisuals
import org.shipwrights.enderkinesis.registry.EKSounds
import org.shipwrights.enderkinesis.registry.EKParticles

/**
 * Purity's Crystallized, which replaces the Moonstone gem's old Wither. Crystals grow on the
 * target: 1 Crystallization damage a second, and Slowness that deepens as the effect runs
 * down. When it ends the crystals shatter, dealing 2 × curve to everything within 1.5 blocks.
 *
 * The amplifier carries the spell's magnitude ([SpellCurves.toAmplifier]) so the shatter
 * can scale with it.
 */
class CrystallizedEffect : MobEffect(MobEffectCategory.HARMFUL, 0xB7AEE2) {

    override fun isDurationEffectTick(duration: Int, amplifier: Int): Boolean = duration % 20 == 0

    override fun applyEffectTick(entity: LivingEntity, amplifier: Int) {
        val level = entity.level() as? ServerLevel ?: return
        entity.hurt(EKDamageSources.crystallization(level), DAMAGE_PER_SECOND)
        val remaining = entity.getEffect(this)?.duration ?: return
        val slowness = when {
            remaining <= 40 -> 3
            remaining <= 80 -> 2
            remaining <= 120 -> 1
            else -> 0
        }
        // A little over a second, refreshed every second, so the tier only ever steps up.
        entity.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 25, slowness, false, false, false))
    }

    override fun addAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.addAttributeModifiers(entity, attributes, amplifier)
        SpellVisuals.update(entity)
    }

    /** Called when the effect ends for any reason (running out, being cleansed or milked),
     *  and also when a new dose replaces it. The effect is still on the entity in the second
     *  case, which is how a refresh is told apart from an ending. */
    override fun removeAttributeModifiers(entity: LivingEntity, attributes: AttributeMap, amplifier: Int) {
        super.removeAttributeModifiers(entity, attributes, amplifier)
        SpellVisuals.update(entity)
        val level = entity.level() as? ServerLevel ?: return
        if (!entity.isAlive || entity.hasEffect(this)) return
        shatter(level, entity, SpellCurves.fromAmplifier(amplifier))
    }

    private fun shatter(level: ServerLevel, entity: LivingEntity, magnitude: Float) {
        val damage = SpellCurves.damage(SHATTER_DAMAGE, magnitude)
        val centre = entity.position().add(0.0, entity.bbHeight * 0.5, 0.0)
        val box = entity.boundingBox.inflate(SHATTER_RADIUS)
        for (target in level.getEntitiesOfClass(LivingEntity::class.java, box)) {
            if (!target.isAlive) continue
            if (target !== entity && target.boundingBox.distanceToSqr(centre) > SHATTER_RADIUS * SHATTER_RADIUS) continue
            target.hurt(EKDamageSources.crystallization(level), damage)
        }
        level.sendParticles(
            EKParticles.crystalShard(), centre.x, centre.y, centre.z, 24,
            entity.bbWidth * 0.5, entity.bbHeight * 0.5, entity.bbWidth * 0.5, 0.15,
        )
        level.playSound(null, entity.blockPosition(), EKSounds.CRYSTALLIZED_SHATTER.get(), SoundSource.HOSTILE, 1f, 1f)
    }

    companion object {
        const val DAMAGE_PER_SECOND: Float = 1f
        const val SHATTER_DAMAGE: Float = 2f
        const val SHATTER_RADIUS: Double = 1.5
    }
}
